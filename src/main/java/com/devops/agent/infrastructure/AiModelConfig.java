package com.devops.agent.infrastructure;

import com.devops.agent.infrastructure.llm.LlmEndpointSpec;
import com.devops.agent.infrastructure.llm.OpenAiCompatibleModelFactory;
import com.devops.agent.infrastructure.guard.ModelFingerprintGuard;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.devops.agent.infrastructure.llm.RateLimitedEmbeddingModel;
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

    // ==================== 端点描述（配置 → 中性模型）====================
    //
    // 五个 Bean 曾各自手写一遍 baseUrl/apiKey/modelName/timeout/maxRetries，
    // 同样六行复制五份。后果不是「代码丑」而是「改一处漏四处」——
    // Embedding Bean 就曾漏传 dimensions，日志却照常打印「输出维度 1536」，
    // 故障要到写库那一刻才以 expected 1536 dimensions, not 3072 暴露。
    // 现在配置的解读只发生在下面三个方法里，且它们是纯函数、可单测。

    /** Turbo（日常对话）端点——chat 渠道 */
    public LlmEndpointSpec turboSpec() {
        return LlmEndpointSpec.chat(chatBaseUrl, chatApiKey, turboModel,
                Duration.ofMillis(timeout), maxRetries);
    }

    /** Reasoner（复杂推理）端点，超时按 REASONER_TIMEOUT_MULTIPLIER 放大——chat 渠道 */
    public LlmEndpointSpec reasonerSpec() {
        return LlmEndpointSpec.reasoner(chatBaseUrl, chatApiKey, reasonerModel,
                Duration.ofMillis(timeout), maxRetries);
    }

    /** Embedding 端点——embedding 独立渠道（可与 chat 不同厂商），维度取自铁律键 */
    public LlmEndpointSpec embeddingSpec() {
        return LlmEndpointSpec.embedding(embeddingBaseUrl, embeddingApiKey, embeddingModel,
                Duration.ofMillis(embeddingTimeout), embeddingMaxRetries, vectorDimension);
    }

    /** 当前 embedding 渠道指纹（base-url+model+dimension 摘要）——指纹锁用 */
    public String embeddingFingerprint() {
        return ModelFingerprintGuard.fingerprint(embeddingBaseUrl, embeddingModel, vectorDimension);
    }

    // ==================== Real 模式（生产模式）====================

    /**
     * Turbo 模型（主力模型,日常对话）
     * 特点：快速响应、成本低，适合 80% 的日常咨询场景
     */
    @Bean(name = "turboModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public ChatModel turboModel() {
        LlmEndpointSpec spec = turboSpec();
        log.info("🚀 [AiModelConfig] 初始化 Turbo 模型: {}", spec.describe());
        return OpenAiCompatibleModelFactory.chat(spec, true);
    }

    /**
     * Reasoner 模型（推理模型，复杂问题）
     * 特点：推理能力强、延迟高、成本高，仅用于复杂堆栈问题（由 DevOpsIntentRouter 路由）
     */
    @Bean(name = "reasonerModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public ChatModel reasonerModel() {
        // 超时翻倍这条规则由 LlmEndpointSpec.reasoner() 统一表达，
        // 不再以裸的 timeout * 2 散落在两个方法里
        LlmEndpointSpec spec = reasonerSpec();
        log.info("🚀 [AiModelConfig] 初始化 Reasoner 模型: {}", spec.describe());
        return OpenAiCompatibleModelFactory.chat(spec, true);
    }

    /**
     * Turbo 流式模型（原生 SSE 流式 + 工具调用，供 Agent 引擎使用）
     * <p>与同步 turboModel 独立：同步版供 HealthCheck 连通性探测，流式版供对话链路。</p>
     */
    @Bean(name = "turboStreamingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public StreamingChatModel turboStreamingModel() {
        // .streaming() 把重试数显式归零：LangChain4j 1.1.0 的流式 builder
        // 没有 maxRetries 方法，配置里留个非 0 值会让人误以为流式也会重试。
        // 流式重试需在更上层（编排层/HTTP 客户端层）兜底。
        LlmEndpointSpec spec = turboSpec().streaming();
        log.info("🚀 [AiModelConfig] 初始化 Turbo 流式模型: {} (流式无 maxRetries，由编排层兜底)",
                spec.describe());
        return OpenAiCompatibleModelFactory.streamingChat(spec, true);
    }

    /**
     * Reasoner 流式模型（复杂推理，超时时间翻倍）
     */
    @Bean(name = "reasonerStreamingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public StreamingChatModel reasonerStreamingModel() {
        LlmEndpointSpec spec = reasonerSpec().streaming();
        log.info("🚀 [AiModelConfig] 初始化 Reasoner 流式模型: {} (流式无 maxRetries，由编排层兜底)",
                spec.describe());
        return OpenAiCompatibleModelFactory.streamingChat(spec, true);
    }

    /**
     * Embedding 模型（向量化模型）
     * <p>
     * <b>维度铁律</b>：输出维度必须等于 {@code devops.ai.vector.dimension}，
     * 该值同时决定 {@code V1__baseline.sql} 的 {@code VECTOR(n)} 与
     * {@link VectorStoreConfig} 的配置。三者同源，不允许各写一份。
     * </p>
     * <p>
     * 注意别把「模型原生维度」当成 1536——那是 {@code text-embedding-v2}
     * 的特性，不是通用规律。当前网关的三个模型原生维度分别是
     * 3072（gemini）、4096（qwen3 / nv-embed），全都需要显式降维。
     * </p>
     */
    @Bean(name = "embeddingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public EmbeddingModel embeddingModel(RateLimiterRegistry rateLimiterRegistry) {
        // 维度取自配置（devops.ai.vector.dimension），与 V1__baseline.sql 的
        // VECTOR(n) 同源。
        //
        // 必须显式传 dimensions：多数现代 Embedding 模型的原生维度并非 1536
        // （gemini-embedding-001 是 3072、qwen3-embedding-8b 是 4096），
        // 但它们支持 MRL 截断降维。不传这个参数就会拿到原生维度，
        // 而故障只在**写库那一刻**才暴露：
        //   ERROR: expected 1536 dimensions, not 3072
        // 此前这里不传参，日志却硬编码打印「(输出维度: 1536)」——
        // 日志在说谎，反而掩盖了真实维度，排查时会误以为配置已生效。
        LlmEndpointSpec spec = embeddingSpec();
        log.info("🚀 [AiModelConfig] 初始化 Embedding 模型: {}（与 V1 基线 VECTOR({}) 对齐）",
                spec.describe(), vectorDimension);
        // S0-3 + 方案 C 批 74：Bean 收口限流（llm-embedding 独立实例）——
        // chat 与 embedding 可能来自不同厂商不同配额档位，独立桶防连带饿死。
        // 装饰而非注解贴调用方——无论检索、摄取还是重建索引，拿到的
        // embeddingModel 都已带配额护栏
        return new RateLimitedEmbeddingModel(
                OpenAiCompatibleModelFactory.embedding(spec),
                rateLimiterRegistry.rateLimiter("llm-embedding"));
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
