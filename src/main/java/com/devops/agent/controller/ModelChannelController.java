package com.devops.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.dto.ApiCode;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelHistory;
import com.devops.agent.domain.ai.AiChannelHistoryRepository;
import com.devops.agent.domain.ai.AiChannelRepository;
import com.devops.agent.domain.ai.AiChannelService;
import com.devops.agent.domain.ai.ChannelUpdate;
import com.devops.agent.infrastructure.ai.ChannelCapabilityProbe;
import com.devops.agent.infrastructure.ai.ChannelRefreshService;
import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/model-channels")
@SaCheckRole("ADMIN")
public class ModelChannelController {

    private static final Logger log = LoggerFactory.getLogger(ModelChannelController.class);

    private final AiChannelRepository repo;
    private final AiChannelService channelService;
    private final AiChannelHistoryRepository historyRepo;
    private final com.devops.agent.infrastructure.ai.ChannelCapabilityProbe capabilityProbe;
    // MOCK 模式下 Refreshable* 模型 Bean 与 ChannelRefreshService 均不装配（热更新无对象），
    // 故用 ObjectProvider 延迟解析，保证 MOCK 上下文正常启动；编辑后的热更新尝试只在 REAL 模式有意义。
    private final org.springframework.beans.factory.ObjectProvider<ChannelRefreshService> refreshServiceProvider;

    @Value("${devops.ai.channels.chat.base-url}") private String ymlChatBaseUrl;
    @Value("${devops.ai.channels.chat.api-key}") private String ymlChatKey;
    @Value("${devops.ai.channels.chat.turbo-model}") private String ymlTurboModel;
    @Value("${devops.ai.channels.chat.reasoner-model}") private String ymlReasonerModel;
    @Value("${devops.ai.channels.embedding.base-url}") private String ymlEmbBaseUrl;
    @Value("${devops.ai.channels.embedding.api-key}") private String ymlEmbKey;
    @Value("${devops.ai.channels.embedding.model}") private String ymlEmbModel;
    @Value("${devops.ai.channels.reranker.base-url}") private String ymlRerankerBaseUrl;
    @Value("${devops.ai.channels.reranker.api-key}") private String ymlRerankerKey;
    @Value("${devops.ai.channels.reranker.model}") private String ymlRerankerModel;
    @Value("${devops.ai.vector.dimension:1536}") private int vectorDimension;
    @Value("${MODEL_KEY_CRYPT_SECRET:}") private String cryptSecret;

    /** 当前 AI 模式（MOCK/REAL）——前端据此给出「演示数据/真实模型」的人性化提示。 */
    @Value("${devops.ai.mode:MOCK}") private String aiMode;

    public ModelChannelController(AiChannelRepository repo, AiChannelService channelService,
            AiChannelHistoryRepository historyRepo,
            com.devops.agent.infrastructure.ai.ChannelCapabilityProbe capabilityProbe,
            org.springframework.beans.factory.ObjectProvider<ChannelRefreshService> refreshServiceProvider) {
        this.repo = repo;
        this.channelService = channelService;
        this.historyRepo = historyRepo;
        this.capabilityProbe = capabilityProbe;
        this.refreshServiceProvider = refreshServiceProvider;
    }

    @GetMapping
    public ApiResponse<ChannelList> listChannels() {
        List<ChannelView> views = repo.findAll().stream().map(ch -> toView(ch, false)).toList();
        return ApiResponse.success(new ChannelList(views, aiMode));
    }

    @PutMapping("/{channelKey}")
    public ApiResponse<ChannelView> updateChannel(@PathVariable String channelKey, @RequestBody Object rawBody) {
        try {
            ChannelUpdate patch = new ChannelUpdate(channelKey,
                    readString(rawBody, "baseUrl"), readString(rawBody, "turboModel"),
                    readString(rawBody, "reasonerModel"), readString(rawBody, "model"),
                    readInteger(rawBody, "dimension"), readString(rawBody, "apiKey"),
                    readString(rawBody, "status"),
                    readString(rawBody, "fallbackBaseUrl"), readString(rawBody, "fallbackModel"),
                    readString(rawBody, "fallbackApiKey"), readBoolean(rawBody, "clearFallback"));
            AiChannel saved = channelService.update(patch, currentOperator());
            boolean hotReloaded = false;
            ChannelRefreshService refreshService = refreshServiceProvider.getIfAvailable();
            if (refreshService != null) {
                try { refreshService.refresh(channelKey); hotReloaded = true; }
                catch (Exception e) { log.warn("[ModelChannel] 热更新失败 | {}", e.getMessage()); }
            }
            return ApiResponse.success(toView(saved, !hotReloaded));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, "渠道配置未保存：" + e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(ApiCode.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * 拉渠道变更历史（新→旧，脱敏视图）。历史表承载密文，
     * 视图只透出脱敏串——与列表同一安全契约。
     */
    @GetMapping("/{channelKey}/history")
    public ApiResponse<HistoryList> history(@PathVariable String channelKey, @RequestParam int limit) {
        try {
            int safeLimit = Math.min(Math.max(limit, 1), 100);
            List<HistoryView> views = channelService.history(channelKey, safeLimit).stream()
                    .map(this::toHistoryView).toList();
            return ApiResponse.success(new HistoryList(views));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 回滚到指定历史版本（V5）。回滚也是变更：service 会先快照当前态再写回，
     * 滚错了还能再滚回来。写回后与编辑同路径触发热更新，失败降级为需重启。
     */
    @PostMapping("/{channelKey}/rollback/{historyId}")
    public ApiResponse<ChannelView> rollback(@PathVariable String channelKey, @PathVariable long historyId) {
        try {
            AiChannel restored = channelService.rollback(channelKey, historyId, currentOperator());
            boolean hotReloaded = false;
            ChannelRefreshService refreshService = refreshServiceProvider.getIfAvailable();
            if (refreshService != null) {
                try { refreshService.refresh(channelKey); hotReloaded = true; }
                catch (Exception e) { log.warn("[ModelChannel] 回滚后热更新失败（配置已落库，需重启生效）| {}", e.getMessage()); }
            }
            return ApiResponse.success(toView(restored, !hotReloaded));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, "回滚未执行：" + e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(ApiCode.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{channelKey}/test-connectivity")
    public ApiResponse<ChannelRefreshService.ConnectivityResult> testConnectivity(@PathVariable String channelKey) {
        ChannelRefreshService refreshService = refreshServiceProvider.getIfAvailable();
        if (refreshService == null) {
            return ApiResponse.<ChannelRefreshService.ConnectivityResult>success(
                    new ChannelRefreshService.ConnectivityResult(false, 0, null,
                            "当前为 MOCK 模式，模型未装配，无法进行连通性测试"));
        }
        return ApiResponse.<ChannelRefreshService.ConnectivityResult>success(refreshService.testConnectivity(channelKey));
    }

    @PostMapping("/{channelKey}/available-models")
    public ApiResponse<List<String>> availableModels(@PathVariable String channelKey) {
        AiChannel ch = repo.findByKey(channelKey).orElse(null);
        if (ch == null || ch.baseUrl() == null || ch.baseUrl().isBlank()) return ApiResponse.success(List.of());
        String apiKey = decryptKey(ch);
        if (apiKey == null || apiKey.isBlank()) return ApiResponse.success(List.of());
        try {
            RestTemplate rt = new RestTemplate();
            HttpHeaders h = new HttpHeaders(); h.set("Authorization", "Bearer " + apiKey);
            String url = ch.baseUrl().replaceAll("/+$", "") + "/models";
            ResponseEntity<Map> resp = rt.exchange(url, HttpMethod.GET, new HttpEntity<>(h), Map.class);
            List<String> ids = extractModelIds(resp.getBody());
            log.info("[ModelChannel] {} 上游模型列表 | count={}", channelKey, ids.size());
            return ApiResponse.success(ids);
        } catch (Exception e) {
            log.warn("[ModelChannel] {} 获取模型列表失败 | {}", channelKey, e.getMessage());
            return ApiResponse.success(List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractModelIds(Map body) {
        List<String> ids = new ArrayList<>();
        if (body == null) return ids;
        Object data = body.get("data");
        if (data instanceof List<?> list) for (Object item : list)
            if (item instanceof Map<?, ?> m && m.get("id") instanceof String id && !id.isBlank()) ids.add(id);
        return ids.stream().distinct().sorted().toList();
    }

    // ==================== 能力探测（P4） ====================

    /**
     * 触发一轮能力实测（真实 API 调用，计费+秒级延迟，故为 POST 手动触发）。
     * 结果落库复用；三态语义见 {@link ChannelCapabilityProbe}。
     */
    @PostMapping("/{channelKey}/capability-probe")
    public ApiResponse<com.devops.agent.infrastructure.ai.ChannelCapabilityProbe.ProbeResult> capabilityProbe(@PathVariable String channelKey) {
        try {
            return ApiResponse.success(capabilityProbe.probe(channelKey));
        } catch (IllegalStateException e) {
            return ApiResponse.error(ApiCode.NOT_FOUND, e.getMessage());
        }
    }

    /** 读已落库的最近探测结果（不重新实测，页面加载用）。从未探测返回 null。 */
    @GetMapping("/{channelKey}/capabilities")
    public ApiResponse<com.devops.agent.infrastructure.ai.ChannelCapabilityProbe.ProbeResult> capabilities(@PathVariable String channelKey) {
        return ApiResponse.success(capabilityProbe.readStored(channelKey));
    }

    @PostMapping("/{channelKey}/reset")
    public ApiResponse<ChannelView> resetToDefaults(@PathVariable String channelKey) {
        AiChannel seed;
        switch (channelKey) {
            case "chat":
                seed = new AiChannel("chat", nullToEmpty(ymlChatBaseUrl), encrypt(ymlChatKey),
                    ApiKeyCrypt.mask(ymlChatKey), nullToEmpty(ymlTurboModel), nullToEmpty(ymlReasonerModel),
                    null, null, "ACTIVE", null); break;
            case "embedding":
                seed = new AiChannel("embedding", nullToEmpty(ymlEmbBaseUrl), encrypt(ymlEmbKey),
                    ApiKeyCrypt.mask(ymlEmbKey), null, null, nullToEmpty(ymlEmbModel), vectorDimension, "ACTIVE", null); break;
            case "reranker":
                seed = new AiChannel("reranker", nullToEmpty(ymlRerankerBaseUrl), encrypt(ymlRerankerKey),
                    ApiKeyCrypt.mask(ymlRerankerKey), null, null, nullToEmpty(ymlRerankerModel), null, "ACTIVE", null); break;
            default: return ApiResponse.error(ApiCode.BAD_REQUEST, "未知渠道: " + channelKey);
        }
        // 重置是最需要回滚的操作——V5 的动机就是「改错了只剩重置、回不到上一个自定义值」。
        // 快照失败不阻断重置（与编辑同契约：历史是兜底，失败留 WARN 线索）。
        repo.findByKey(channelKey).ifPresent(current -> {
            try {
                historyRepo.snapshot(AiChannelHistory.snapshotOf(current, currentOperator(), "重置为 yml 默认值"));
            } catch (Exception e) {
                log.warn("⚠️ [ModelChannel] 重置前历史快照失败（重置仍生效，但当前自定义值将不可回滚）| key={} | {}", channelKey, e.toString());
            }
        });
        repo.upsert(seed);
        AiChannel saved = repo.findByKey(channelKey).orElse(seed);
        log.info("🔄 [ModelChannel] {} 已重置为 yml 默认值", channelKey);
        // 重置与编辑同理尝试热更新：成功则 restartRequired=false 即时生效，
        // 失败降级为「需重启」——配置已落库不丢（静默 catch 契约：失败留 WARN 线索）。
        boolean hotReloaded = false;
        ChannelRefreshService refreshService = refreshServiceProvider.getIfAvailable();
        if (refreshService != null) {
            try { refreshService.refresh(channelKey); hotReloaded = true; }
            catch (Exception e) { log.warn("[ModelChannel] 重置后热更新失败（配置已落库，需重启生效）| {}", e.getMessage()); }
        }
        return ApiResponse.success(toView(saved, !hotReloaded));
    }

    private String encrypt(String raw) { return raw != null && !raw.isBlank() ? ApiKeyCrypt.encrypt(raw, cryptSecret) : null; }
    private static String nullToEmpty(String s) { return s == null || s.isBlank() ? null : s; }
    private String decryptKey(AiChannel ch) { return ch.apiKeyEnc() != null && !ch.apiKeyEnc().isBlank() ? ApiKeyCrypt.decrypt(ch.apiKeyEnc(), cryptSecret) : null; }

    private ChannelView toView(AiChannel c, boolean restartRequired) {
        return new ChannelView(c.channelKey(), c.baseUrl(), c.maskedKey(), c.turboModel(), c.reasonerModel(),
                c.model(), c.dimension(), c.status(), c.updatedAt(), restartRequired,
                c.fallbackBaseUrl(), c.fallbackModel(), c.fallbackMaskedKey(),
                inferProvider(c.baseUrl()), inferProtocol(c.baseUrl()));
    }

    /**
     * 从 baseUrl 推断供应商名称（人性化展示——用户看「阿里云 assistant」比看一串 URL 直观）。
     * 未识别的回落为 URL 的 host，不编造供应商名。
     */
    private String inferProvider(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "未配置";
        String u = baseUrl.toLowerCase();
        if (u.contains("dashscope.aliyuncs.com")) return "阿里云 assistant（通义）";
        if (u.contains("api.openai.com")) return "assistant";
        if (u.contains("api.deepseek.com")) return "DeepSeek";
        if (u.contains("api.moonshot.cn")) return "Moonshot（Kimi）";
        if (u.contains("bigmodel.cn")) return "智谱（GLM）";
        if (u.contains("localhost") || u.contains("127.0.0.1") || u.contains("host.docker.internal")) return "本地/自建";
        // 未识别：透出 host 帮助识别，不硬编一个供应商名
        return u.replaceFirst("^https?://", "").replaceAll("/.*$", "");
    }

    /** 从 baseUrl 推断 API 协议（/v1 或 compatible-mode 即 OpenAI 兼容协议）。 */
    private String inferProtocol(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "未配置";
        String u = baseUrl.toLowerCase();
        if (u.contains("compatible-mode") || u.contains("/v1")) return "OpenAI 兼容";
        return "自定义/其他";
    }

    /** 历史视图：密文字段一概不映射——历史行同样承载 key 密文，安全契约与列表一致。 */
    private HistoryView toHistoryView(AiChannelHistory h) {
        return new HistoryView(h.id(), h.channelKey(), h.baseUrl(), h.maskedKey(),
                h.turboModel(), h.reasonerModel(), h.model(), h.dimension(), h.status(),
                h.fallbackBaseUrl(), h.fallbackModel(), h.fallbackMaskedKey(),
                h.changedAt(), h.changedBy(), h.changeNote());
    }

    /** 与 OperationAuditInterceptor 同款容错：非请求上下文/未登录记 system，不让审计字段炸主流程。 */
    private String currentOperator() {
        try {
            if (StpUtil.isLogin()) return StpUtil.getLoginIdAsString();
        } catch (Exception e) {
            // 未登录或非请求上下文（单测切片/内部调用）：回落 system 是预期分支，
            // 但按静默 catch 契约留下 debug 痕迹——真在线上出现时指向明确。
            log.debug("[ModelChannel] 操作人解析失败，记 system | {}", e.toString());
        }
        return "system";
    }
    private String readString(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Object v = map.get(field); return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
    private Integer readInteger(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Object v = map.get(field);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) { try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; } }
        return null;
    }
    private Boolean readBoolean(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Object v = map.get(field);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return null;
    }

    public record ChannelView(String channelKey, String baseUrl, String maskedKey, String turboModel,
            String reasonerModel, String model, Integer dimension, String status, LocalDateTime updatedAt,
            boolean restartRequired,
            String fallbackBaseUrl, String fallbackModel, String fallbackMaskedKey,
            String provider, String protocol) {}
    public record ChannelList(List<ChannelView> channels, String aiMode) {}
    public record HistoryView(Long id, String channelKey, String baseUrl, String maskedKey, String turboModel,
            String reasonerModel, String model, Integer dimension, String status,
            String fallbackBaseUrl, String fallbackModel, String fallbackMaskedKey,
            LocalDateTime changedAt, String changedBy, String changeNote) {}
    public record HistoryList(List<HistoryView> history) {}
}