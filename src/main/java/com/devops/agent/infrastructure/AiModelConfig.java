package com.devops.agent.infrastructure;

import com.devops.agent.infrastructure.llm.LlmEndpointSpec;
import com.devops.agent.infrastructure.llm.OpenAiCompatibleModelFactory;
import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import com.devops.agent.infrastructure.llm.RateLimitedEmbeddingModel;
import com.devops.agent.infrastructure.guard.ModelFingerprintGuard;
import com.devops.agent.infrastructure.ai.FallbackChatModel;
import com.devops.agent.infrastructure.ai.FallbackStreamingChatModel;
import com.devops.agent.infrastructure.ai.RefreshableChatModel;
import com.devops.agent.infrastructure.ai.RefreshableEmbeddingModel;
import com.devops.agent.infrastructure.ai.RefreshableStreamingChatModel;
import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI 模型配置类（Infrastructure 层）
 * <p>
 * 职责：
 * 1. 根据 devops.ai.mode 开关，注册 Real / Mock 双模 Bean
 * 2. Real 模式：对接阿里云百炼（通义千问），OpenAI 兼容协议
 * 3. Mock 模式：返回硬编码假数据，开发期不消耗 API 额度
 * 4. 与厂商解耦：上层只依赖 LangChain4j 的 ChatLanguageModel / EmbeddingModel 接口
 * <p>
 * 架构约束：
 * - 本类属于 Infrastructure 层，不得 import Application / Domain 层的类
 * - 大小模型分流由 Application 层的 DevOpsIntentRouter 负责，此处只注册连接池
 */
@Slf4j
@Configuration
public class AiModelConfig {

    // ==================== 三渠道运行时配置（DB 权威源，P1）====================
    // 模型的 base-url / model / api-key 在<b>启动装配时</b>从 sys_ai_channel 读取；
    // P3-1 起渠道编辑保存后由 ChannelRefreshService 调 build* 原子热替换，无需重启。
    // application.yml 的 devops.ai.channels.* 仍是<b>空库种子与回落默认</b>——
    // AiChannelMirror 只在渠道不存在时把 yml 值种进表。
    private final AiChannelRepository channelRepo;

    private RateLimiter embeddingRateLimiter;
    // 主备降级熔断器注册表（方案 A）：实例名 llm-chat-turbo / llm-chat-reasoner
    private final io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${MODEL_KEY_CRYPT_SECRET:}")
    private String modelKeyCryptSecret;

    public AiModelConfig(AiChannelRepository channelRepo, RateLimiterRegistry rateLimiterRegistry,
                         io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry) {
        this.channelRepo = channelRepo;
        this.embeddingRateLimiter = rateLimiterRegistry.rateLimiter("llm-embedding");
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /** chat 渠道当前生效配置（DB 为主，缺失回落已装配的 yml 字段）。 */
    private AiChannel chatChannel() {
        return channelRepo.findByKey(AiChannel.KEY_CHAT).orElse(null);
    }

    /** embedding 渠道当前生效配置。 */
    private AiChannel embeddingChannel() {
        return channelRepo.findByKey(AiChannel.KEY_EMBEDDING).orElse(null);
    }

    // ==================== 多渠道配置（方案 C，批 74）====================
    // 三角色各自独立渠道。AI_CHAT_* / AI_EMBEDDING_* 未配置时回落
    // ALIBABA_* 旧键——application.yml 里的 ${A:${B:default}} 双层回落完成兼容,
    // 这里只消费已解析的最终值。
    @Value("${devops.ai.channels.chat.base-url}")
    private String chatBaseUrl;

    @Value("${devops.ai.channels.chat.api-key}")
    private String chatApiKey;

    @Value("${devops.ai.channels.chat.turbo-model}")
    private String turboModel;

    @Value("${devops.ai.channels.chat.reasoner-model}")
    private String reasonerModel;

    @Value("${devops.ai.channels.chat.timeout-ms}")
    private long timeout;

    @Value("${devops.ai.channels.chat.max-retries}")
    private int maxRetries;

    @Value("${devops.ai.channels.embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${devops.ai.channels.embedding.api-key}")
    private String embeddingApiKey;

    @Value("${devops.ai.channels.embedding.model}")
    private String embeddingModel;

    @Value("${devops.ai.channels.embedding.timeout-ms}")
    private long embeddingTimeout;

    @Value("${devops.ai.channels.embedding.max-retries}")
    private int embeddingMaxRetries;

    /** 向量维度（全链路唯一来源：devops.ai.vector.dimension） */
    @Value("${devops.ai.vector.dimension}")
    private int vectorDimension;

    public int vectorDimension() { return vectorDimension; }

    // ==================== 端点描述（配置 → 中性模型）====================
    //
    // 五个 Bean 曾各自手写一遍 baseUrl/apiKey/modelName/timeout/maxRetries，
    // 同样六行复制五份。后果不是「代码丑」而是「改一处漏四处」——
    // Embedding Bean 就曾漏传 dimensions，日志却照常打印「输出维度 1536」，
    // 故障要到写库那一刻才以 expected 1536 dimensions, not 3072 暴露。
    // 现在配置的解读只发生在下面三个方法里，且它们是纯函数、可单测。

    /** Turbo（日常对话）端点——chat 渠道。baseUrl/apiKey/model 以 DB 为准，缺则回落 yml。 */
    public LlmEndpointSpec turboSpec() {
        AiChannel ch = chatChannel();
        String url = ch != null && notBlank(ch.baseUrl()) ? ch.baseUrl() : chatBaseUrl;
        String key = ch != null && notBlank(ch.apiKeyEnc()) ? decrypt(ch.apiKeyEnc()) : chatApiKey;
        String model = ch != null && notBlank(ch.turboModel()) ? ch.turboModel() : turboModel;
        return LlmEndpointSpec.chat(url, key, model, Duration.ofMillis(timeout), maxRetries);
    }

    /** Reasoner（复杂推理）端点，超时按 REASONER_TIMEOUT_MULTIPLIER 放大——chat 渠道 */
    public LlmEndpointSpec reasonerSpec() {
        AiChannel ch = chatChannel();
        String url = ch != null && notBlank(ch.baseUrl()) ? ch.baseUrl() : chatBaseUrl;
        String key = ch != null && notBlank(ch.apiKeyEnc()) ? decrypt(ch.apiKeyEnc()) : chatApiKey;
        String model = ch != null && notBlank(ch.reasonerModel()) ? ch.reasonerModel() : reasonerModel;
        return LlmEndpointSpec.reasoner(url, key, model, Duration.ofMillis(timeout), maxRetries);
    }

    // ---- 备用模型端点（方案 A）：未配置返回 null，build* 据此决定是否包装 ----

    /** chat 渠道 Turbo 备用端点；DB 里 fallback 未配置（或非 chat 渠道）返回 null。 */
    public LlmEndpointSpec turboFallbackSpec() {
        return chatFallbackSpec(false);
    }

    /** chat 渠道 Reasoner 备用端点（超时同主 reasoner 放大规则）。 */
    public LlmEndpointSpec reasonerFallbackSpec() {
        return chatFallbackSpec(true);
    }

    private LlmEndpointSpec chatFallbackSpec(boolean reasoner) {
        AiChannel ch = chatChannel();
        if (ch == null || !ch.hasFallback()) return null;
        String key = notBlank(ch.fallbackApiKeyEnc()) ? decrypt(ch.fallbackApiKeyEnc()) : null;
        if (!notBlank(key)) {
            log.warn("⚠️ [AiModelConfig] chat 备用模型已配置但 key 缺失/不可解密——本次装配忽略备用（降级不可用）");
            return null;
        }
        return reasoner
                ? LlmEndpointSpec.reasoner(ch.fallbackBaseUrl(), key, ch.fallbackModel(),
                        Duration.ofMillis(timeout), maxRetries)
                : LlmEndpointSpec.chat(ch.fallbackBaseUrl(), key, ch.fallbackModel(),
                        Duration.ofMillis(timeout), maxRetries);
    }

    /** Embedding 端点——embedding 独立渠道（DB 为准，缺则回落 yml），维度取自铁律键 */
    public LlmEndpointSpec embeddingSpec() {
        AiChannel ch = embeddingChannel();
        String url = ch != null && notBlank(ch.baseUrl()) ? ch.baseUrl() : embeddingBaseUrl;
        String key = ch != null && notBlank(ch.apiKeyEnc()) ? decrypt(ch.apiKeyEnc()) : embeddingApiKey;
        String model = ch != null && notBlank(ch.model()) ? ch.model() : embeddingModel;
        return LlmEndpointSpec.embedding(url, key, model,
                Duration.ofMillis(embeddingTimeout), embeddingMaxRetries, vectorDimension);
    }

    /** 当前 embedding 渠道指纹（base-url+model+dimension 摘要）——指纹锁用 */
    public String embeddingFingerprint() {
        AiChannel ch = embeddingChannel();
        String url = ch != null && notBlank(ch.baseUrl()) ? ch.baseUrl() : embeddingBaseUrl;
        String model = ch != null && notBlank(ch.model()) ? ch.model() : embeddingModel;
        return ModelFingerprintGuard.fingerprint(url, model, vectorDimension);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 解密 DB 里加密的 key；非 enc:v1: 前缀（明文/降级）原样返回。 */
    private String decrypt(String enc) {
        return ApiKeyCrypt.decrypt(enc, modelKeyCryptSecret);
    }

    // ==================== Real 模式（生产模式）====================

    // P3-1：每个 @Bean 返回 Refreshable 包装器，Spring 注入的引用永不变化，
    // 但内部 delegate 可被 ChannelRefreshService 原子替换——热更新无需重启。
    // 方案 A：chat 渠道 build* 在备用已配置时包一层 Fallback 装饰器（主备降级）；
    // embedding 不包——维度铁律禁止换模型（见 V4 注释）。

    public ChatModel buildTurboChat() {
        ChatModel primary = OpenAiCompatibleModelFactory.chat(turboSpec(), true);
        LlmEndpointSpec fb = turboFallbackSpec();
        if (fb == null) return primary;
        log.info("🛡 [AiModelConfig] Turbo 备用模型已启用: {}（主模型熔断/失败自动切换）", fb.describe());
        return new FallbackChatModel(primary, OpenAiCompatibleModelFactory.chat(fb, true),
                circuitBreakerRegistry.circuitBreaker("llm-chat-turbo"),
                turboSpec().modelName(), fb.modelName());
    }

    public ChatModel buildReasonerChat() {
        ChatModel primary = OpenAiCompatibleModelFactory.chat(reasonerSpec(), true);
        LlmEndpointSpec fb = reasonerFallbackSpec();
        if (fb == null) return primary;
        log.info("🛡 [AiModelConfig] Reasoner 备用模型已启用: {}（主模型熔断/失败自动切换）", fb.describe());
        return new FallbackChatModel(primary, OpenAiCompatibleModelFactory.chat(fb, true),
                circuitBreakerRegistry.circuitBreaker("llm-chat-reasoner"),
                reasonerSpec().modelName(), fb.modelName());
    }

    public StreamingChatModel buildTurboStreaming() {
        StreamingChatModel primary = OpenAiCompatibleModelFactory.streamingChat(turboSpec().streaming(), true);
        LlmEndpointSpec fb = turboFallbackSpec();
        if (fb == null) return primary;
        return new FallbackStreamingChatModel(primary,
                OpenAiCompatibleModelFactory.streamingChat(fb.streaming(), true),
                circuitBreakerRegistry.circuitBreaker("llm-chat-turbo"),
                turboSpec().modelName(), fb.modelName());
    }

    public StreamingChatModel buildReasonerStreaming() {
        StreamingChatModel primary = OpenAiCompatibleModelFactory.streamingChat(reasonerSpec().streaming(), true);
        LlmEndpointSpec fb = reasonerFallbackSpec();
        if (fb == null) return primary;
        return new FallbackStreamingChatModel(primary,
                OpenAiCompatibleModelFactory.streamingChat(fb.streaming(), true),
                circuitBreakerRegistry.circuitBreaker("llm-chat-reasoner"),
                reasonerSpec().modelName(), fb.modelName());
    }

    public EmbeddingModel buildEmbedding() {
        LlmEndpointSpec spec = embeddingSpec();
        return new RateLimitedEmbeddingModel(
                OpenAiCompatibleModelFactory.embedding(spec), embeddingRateLimiter);
    }

    @Bean(name = "turboModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public RefreshableChatModel turboModel() {
        RefreshableChatModel r = new RefreshableChatModel(buildTurboChat());
        log.info("🚀 [AiModelConfig] 初始化 Turbo 模型（热更新就绪）: {}", turboSpec().describe());
        return r;
    }

    @Bean(name = "reasonerModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public RefreshableChatModel reasonerModel() {
        RefreshableChatModel r = new RefreshableChatModel(buildReasonerChat());
        log.info("🚀 [AiModelConfig] 初始化 Reasoner 模型（热更新就绪）: {}", reasonerSpec().describe());
        return r;
    }

    @Bean(name = "turboStreamingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public RefreshableStreamingChatModel turboStreamingModel() {
        RefreshableStreamingChatModel r = new RefreshableStreamingChatModel(buildTurboStreaming());
        log.info("🚀 [AiModelConfig] 初始化 Turbo 流式模型（热更新就绪）: {}", turboSpec().streaming().describe());
        return r;
    }

    @Bean(name = "reasonerStreamingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public RefreshableStreamingChatModel reasonerStreamingModel() {
        RefreshableStreamingChatModel r = new RefreshableStreamingChatModel(buildReasonerStreaming());
        log.info("🚀 [AiModelConfig] 初始化 Reasoner 流式模型（热更新就绪）: {}", reasonerSpec().streaming().describe());
        return r;
    }

    @Bean(name = "embeddingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public RefreshableEmbeddingModel embeddingModel() {
        RefreshableEmbeddingModel r = new RefreshableEmbeddingModel(buildEmbedding());
        log.info("🚀 [AiModelConfig] 初始化 Embedding 模型（热更新就绪）: {}（与 V1 基线 VECTOR({}) 对齐）",
                embeddingSpec().describe(), vectorDimension);
        return r;
    }

    // ==================== Mock 模式（开发模式）====================

    /**
     * Mock Turbo 模型（开发期不调 API）
     */
    @Bean(name = "turboModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "MOCK", matchIfMissing = true)
    public ChatModel mockTurboModel() {
        log.warn("⚠️ [AiModelConfig] Mock 模式：Turbo 模型将返回硬编码假数据（不消耗 API 额度）");
        return new MockChatModel("Mock-Turbo");
    }

    /**
     * Mock Reasoner 模型
     */
    @Bean(name = "reasonerModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "MOCK", matchIfMissing = true)
    public ChatModel mockReasonerModel() {
        log.warn("⚠️ [AiModelConfig] Mock 模式：Reasoner 模型将返回硬编码假数据");
        return new MockChatModel("Mock-Reasoner");
    }

    /**
     * Mock Turbo 流式模型
     */
    @Bean(name = "turboStreamingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "MOCK", matchIfMissing = true)
    public StreamingChatModel mockTurboStreamingModel() {
        log.warn("⚠️ [AiModelConfig] Mock 模式：Turbo 流式模型将逐字返回硬编码假数据");
        return new MockStreamingChatModel("Mock-Turbo");
    }

    /**
     * Mock Reasoner 流式模型
     */
    @Bean(name = "reasonerStreamingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "MOCK", matchIfMissing = true)
    public StreamingChatModel mockReasonerStreamingModel() {
        log.warn("⚠️ [AiModelConfig] Mock 模式：Reasoner 流式模型将逐字返回硬编码假数据");
        return new MockStreamingChatModel("Mock-Reasoner");
    }

    /**
     * Mock Embedding 模型
     */
    @Bean(name = "embeddingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "MOCK", matchIfMissing = true)
    public EmbeddingModel mockEmbeddingModel(RateLimiterRegistry rateLimiterRegistry) {
        log.warn("⚠️ [AiModelConfig] Mock 模式：Embedding 模型将返回假向量（{} 维确定性向量）", vectorDimension);
        // 维度传给 Mock——S0-2-J1 曾证明硬编码 1536 会让维度注入在 MOCK 路径无声落空
        // 与 REAL 同样过限流装饰（llm-embedding 独立实例）：护栏通路在 MOCK 下也可被测试断言
        return new RateLimitedEmbeddingModel(new MockEmbeddingModel(vectorDimension),
                rateLimiterRegistry.rateLimiter("llm-embedding"));
    }
}
