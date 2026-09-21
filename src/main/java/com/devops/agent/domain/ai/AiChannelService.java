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

    private final AiChannelRepository repo;
    private final int vectorDimension;
    private final String cryptSecret;

    public AiChannelService(AiChannelRepository repo,
                            @Value("${devops.ai.vector.dimension:1536}") int vectorDimension,
                            @Value("${MODEL_KEY_CRYPT_SECRET:}") String cryptSecret) {
        this.repo = repo;
        this.vectorDimension = vectorDimension;
        this.cryptSecret = cryptSecret;
    }

    /**
     * 合并 patch 后写回。渠道不存在抛 {@link IllegalStateException}（映射 404）；
     * 参数不合法抛 {@link IllegalArgumentException}（映射 400）。
     */
    public AiChannel update(ChannelUpdate patch) {
        if (patch == null || patch.channelKey() == null || patch.channelKey().isBlank()) {
            throw new IllegalArgumentException("channelKey 不能为空");
        }
        String key = patch.channelKey().trim();
        if (!KNOWN_KEYS.contains(key)) {
            throw new IllegalArgumentException("未知渠道: " + key + "（仅 chat / embedding / reranker）");
        }

        AiChannel existing = repo.findByKey(key)
                .orElseThrow(() -> new IllegalStateException("渠道不存在: " + key));

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
                fbUrl, fbModel, fbKeyEnc, fbMasked);

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
