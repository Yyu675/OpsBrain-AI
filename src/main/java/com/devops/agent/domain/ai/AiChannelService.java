package com.devops.agent.domain.ai;

import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Set;

/**
 * 渠道配置编辑服务（P1：DB 权威源）。
 *
 * <h3>为什么不把校验散在 Controller</h3>
 * 维度铁律、占位 key、未知渠道键——这些规则一旦只写在 HTTP 层，
 * 启动镜像 / 后续 CLI 都会绕过。集中在本类后，Controller 只做鉴权与 DTO 映射。
 *
 * <h3>保存即热更新（P3-1）</h3>
 * 写入只改 {@code sys_ai_channel}；模型 Bean 是 Refreshable 包装器，
 * {@code ChannelRefreshService} 在保存成功后原子替换内部 delegate，
 * 新请求立即走新配置。热更新失败时响应带 {@code restartRequired=true}
 * 回退重启生效，保存本身不丢。
 *
 * <h3>备用模型（方案 A，V4）</h3>
 * 仅 chat 渠道接受 fallback 配置；baseUrl 与 model 必须同存同空——
 * 半套备用配置会在主模型熔断那一刻才暴露，属于延迟爆炸。
 */
@Service
public class AiChannelService {

    private static final Logger log = LoggerFactory.getLogger(AiChannelService.class);

    private static final Set<String> KNOWN_KEYS = Set.of(
            AiChannel.KEY_CHAT, AiChannel.KEY_EMBEDDING, AiChannel.KEY_RERANKER);
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");
    /** V12：API 协议合法枚举（显式配置，不从 URL 推断）。 */
    private static final Set<String> PROTOCOLS = Set.of(
            "OPENAI_COMPATIBLE", "AZURE_OPENAI", "ANTHROPIC", "CUSTOM");

    private final AiChannelRepository repo;
    private final AiChannelHistoryRepository historyRepo;
    private final int vectorDimension;
    private final String cryptSecret;

    public AiChannelService(AiChannelRepository repo,
                            AiChannelHistoryRepository historyRepo,
                            @Value("${devops.ai.vector.dimension:1536}") int vectorDimension,
                            @Value("${MODEL_KEY_CRYPT_SECRET:}") String cryptSecret) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.vectorDimension = vectorDimension;
        this.cryptSecret = cryptSecret;
    }

    /**
     * 合并 patch 后写回。渠道不存在抛 {@link IllegalStateException}（映射 404）；
     * 参数不合法抛 {@link IllegalArgumentException}（映射 400）。
     * 变更前把旧行快照进历史表（V5），操作人不明时记 system。
     */
    public AiChannel update(ChannelUpdate patch) {
        return update(patch, "system");
    }

    /** 带操作人的编辑入口（Controller 传 Sa-Token loginId）。 */
    public AiChannel update(ChannelUpdate patch, String operator) {
        if (patch == null || patch.channelKey() == null || patch.channelKey().isBlank()) {
            throw new IllegalArgumentException("channelKey 不能为空");
        }
        String key = patch.channelKey().trim();
        if (!KNOWN_KEYS.contains(key)) {
            throw new IllegalArgumentException("未知渠道: " + key + "（仅 chat / embedding / reranker）");
        }

        AiChannel existing = repo.findByKey(key)
                .orElseThrow(() -> new IllegalStateException("渠道不存在: " + key));

        // 读-改-写之间无版本 CAS：findByKey → 校验合并 → updateMerged 不是原子的，
        // 两个管理员并发编辑会丢更新且历史快照错位。这是**有意接受**的取舍——
        // 渠道配置是低频管理操作（撞车概率极低），且后果不致命（旧值还在历史表里）。
        // 若将来撞车成为现实问题，参照 6.11 乐观锁先例：加 version 列 + WHERE version = ?。

        String baseUrl = firstNonBlank(patch.baseUrl(), existing.baseUrl());
        validateBaseUrl(baseUrl);

        String status = existing.status();
        if (patch.status() != null && !patch.status().isBlank()) {
            String s = patch.status().trim().toUpperCase();
            if (!STATUSES.contains(s)) {
                throw new IllegalArgumentException("status 只能是 ACTIVE 或 DISABLED");
            }
            status = s;
        }

        // V12：协议显式配置（null/blank = 保留既有，不从 URL 推断——协议是调用契约）
        String protocol = existing.protocol();
        if (patch.protocol() != null && !patch.protocol().isBlank()) {
            String p = patch.protocol().trim().toUpperCase();
            if (!PROTOCOLS.contains(p)) {
                throw new IllegalArgumentException("protocol 只能是 " + String.join("/", PROTOCOLS));
            }
            protocol = p;
        }
        // V12：供应商显式可编辑（null/blank = 保留既有，展示层从 baseUrl 推断兜底）
        String provider = patch.provider() != null && !patch.provider().isBlank()
                ? patch.provider().trim() : existing.provider();

        String turbo = existing.turboModel();
        String reasoner = existing.reasonerModel();
        String model = existing.model();
        Integer dimension = existing.dimension();

        if (AiChannel.KEY_CHAT.equals(key)) {
            turbo = firstNonBlank(patch.turboModel(), existing.turboModel());
            reasoner = firstNonBlank(patch.reasonerModel(), existing.reasonerModel());
            if (blank(turbo) || blank(reasoner)) {
                throw new IllegalArgumentException("chat 渠道必须同时配置 turbo 与 reasoner 模型名");
            }
        } else {
            model = firstNonBlank(patch.model(), existing.model());
            if (blank(model)) {
                throw new IllegalArgumentException(key + " 渠道必须配置模型名");
            }
            if (AiChannel.KEY_EMBEDDING.equals(key)) {
                if (patch.dimension() != null && patch.dimension() != vectorDimension) {
                    throw new IllegalArgumentException(
                            "向量维度不允许通过本接口修改（当前铁律 " + vectorDimension
                                    + " 维）。改维必须同步 V1 基线 VECTOR(n) + devops.ai.vector.dimension + 全库重建。");
                }
                dimension = vectorDimension;
            }
        }

        String apiKeyEnc = existing.apiKeyEnc();
        String masked = existing.maskedKey();
        if (patch.apiKey() != null && !patch.apiKey().isBlank()) {
            String raw = patch.apiKey().trim();
            rejectPlaceholderKey(raw);
            apiKeyEnc = ApiKeyCrypt.encrypt(raw, cryptSecret);
            masked = ApiKeyCrypt.mask(raw);
        }

        // ---- 备用模型（方案 A）：仅 chat 渠道；baseUrl/model 同存同空 ----
        String fbUrl = existing.fallbackBaseUrl();
        String fbModel = existing.fallbackModel();
        String fbKeyEnc = existing.fallbackApiKeyEnc();
        String fbMasked = existing.fallbackMaskedKey();
        if (Boolean.TRUE.equals(patch.clearFallback())) {
            if (!AiChannel.KEY_CHAT.equals(key)) {
                throw new IllegalArgumentException("仅 chat 渠道支持备用模型配置");
            }
            fbUrl = fbModel = fbKeyEnc = fbMasked = null;
        } else if (!blank(patch.fallbackBaseUrl()) || !blank(patch.fallbackModel())) {
            if (!AiChannel.KEY_CHAT.equals(key)) {
                throw new IllegalArgumentException("仅 chat 渠道支持备用模型配置（embedding 换模型 = 维度语义空间不兼容 = 全库重建）");
            }
            // 二者必须同存同空：半套备用配置在主模型熔断时才暴露，属延迟爆炸
            String newUrl = firstNonBlank(patch.fallbackBaseUrl(), fbUrl);
            String newModel = firstNonBlank(patch.fallbackModel(), fbModel);
            if (blank(newUrl) || blank(newModel)) {
                throw new IllegalArgumentException("备用模型 baseUrl 与 model 必须同时配置（半套备用配置在熔断时才会暴露）");
            }
            validateBaseUrl(newUrl);
            fbUrl = newUrl.trim();
            fbModel = newModel.trim();
            if (!blank(patch.fallbackApiKey())) {
                String rawFb = patch.fallbackApiKey().trim();
                rejectPlaceholderKey(rawFb);
                fbKeyEnc = ApiKeyCrypt.encrypt(rawFb, cryptSecret);
                fbMasked = ApiKeyCrypt.mask(rawFb);
            }
            if (blank(fbKeyEnc)) {
                throw new IllegalArgumentException("备用模型必须配置 API Key（无 key 的备用在主模型熔断时等于没有备用）");
            }
        }

        AiChannel merged = new AiChannel(
                key, baseUrl, apiKeyEnc, masked,
                AiChannel.KEY_CHAT.equals(key) ? turbo : null,
                AiChannel.KEY_CHAT.equals(key) ? reasoner : null,
                AiChannel.KEY_CHAT.equals(key) ? null : model,
                dimension, status, null,
                fbUrl, fbModel, fbKeyEnc, fbMasked,
                protocol, provider);

        // V5：变更前快照旧行——回滚的唯一依据。快照失败不阻断编辑（历史是兜底，不是主流程），
        // 但必须留 WARN 痕迹，否则「改错了回不去」时无任何线索（静默 catch 契约）。
        try {
            historyRepo.snapshot(AiChannelHistory.snapshotOf(existing, operator, "编辑"));
        } catch (Exception e) {
            log.warn("⚠️ [AiChannel] 变更历史快照失败（本次编辑仍生效，但回滚链将缺一环）| key={} | {}", key, e.toString());
        }

        int rows = repo.updateMerged(merged);
        if (rows == 0) {
            throw new IllegalStateException("渠道不存在: " + key);
        }
        log.info("🛰 [AiChannel] 渠道已更新 | key={} | model={} | status={} | keyChanged={} | fallback={} | 热更新由调用方触发",
                key,
                AiChannel.KEY_CHAT.equals(key) ? turbo + "/" + reasoner : model,
                status,
                patch.apiKey() != null && !patch.apiKey().isBlank(),
                AiChannel.KEY_CHAT.equals(key) && !blank(fbModel) ? fbModel : "无");
        return repo.findByKey(key).orElse(merged);
    }

    /**
     * 回滚到指定历史版本（V5）。把当前行先快照（回滚本身也是一次变更，可再回滚回来），
     * 再把历史行整行写回。历史不存在抛 {@link IllegalStateException}（404）；
     * 历史行渠道与请求不符抛 {@link IllegalArgumentException}（400，防跨渠道回滚）。
     */
    public AiChannel rollback(String channelKey, long historyId, String operator) {
        if (!KNOWN_KEYS.contains(channelKey)) {
            throw new IllegalArgumentException("未知渠道: " + channelKey);
        }
        AiChannelHistory history = historyRepo.findById(historyId)
                .orElseThrow(() -> new IllegalStateException("历史版本不存在: #" + historyId));
        if (!channelKey.equals(history.channelKey())) {
            throw new IllegalArgumentException(
                    "历史版本 #" + historyId + " 属于渠道 " + history.channelKey() + "，不能回滚到 " + channelKey);
        }
        AiChannel current = repo.findByKey(channelKey)
                .orElseThrow(() -> new IllegalStateException("渠道不存在: " + channelKey));

        // 回滚前快照当前态——回滚错了还能再回滚回来，链路不设单向门。
        // 【有意不捕获快照异常，与 update() 的"失败不阻断"相反】
        // update 快照失败只丢「改前旧值」、主流程仍能生效；rollback 快照失败
        // 若放行，当前自定义态会被历史行整行覆盖且**永久无处可寻**。
        // 所以回滚对快照是硬依赖：快照失败必须阻断回滚，让用户知道危险再决定。
        historyRepo.snapshot(AiChannelHistory.snapshotOf(current, operator, "回滚前快照（目标 #" + historyId + "）"));

        AiChannel restored = history.toChannel();
        int rows = repo.updateMerged(restored);
        if (rows == 0) {
            throw new IllegalStateException("渠道不存在: " + channelKey);
        }
        log.info("⏪ [AiChannel] 渠道已回滚 | key={} | 到历史 #{} | operator={}", channelKey, historyId, operator);
        return repo.findByKey(channelKey).orElse(restored);
    }

    /** 拉渠道的变更历史（新→旧），供「变更历史」弹窗。 */
    public java.util.List<AiChannelHistory> history(String channelKey, int limit) {
        if (!KNOWN_KEYS.contains(channelKey)) {
            throw new IllegalArgumentException("未知渠道: " + channelKey);
        }
        return historyRepo.findByChannel(channelKey, limit);
    }

    private static void validateBaseUrl(String baseUrl) {
        if (blank(baseUrl)) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("baseUrl 必须是 http/https 地址");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("baseUrl 缺少主机名");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("baseUrl 不是合法 URL: " + e.getMessage());
        }
    }

    private static void rejectPlaceholderKey(String raw) {
        String k = raw.toLowerCase();
        if (k.startsWith("your-") || k.contains("-here") || k.contains("placeholder")) {
            throw new IllegalArgumentException("拒绝占位 API Key（your-… / …-here）");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String incoming, String existing) {
        return blank(incoming) ? existing : incoming.trim();
    }
}
