package com.devops.agent.infrastructure;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    @Value("${devops.ai.alibaba.api-key}")
    private String alibabaApiKey;

    @Value("${devops.ai.alibaba.base-url}")
    private String alibabaBaseUrl;

    @Value("${devops.ai.alibaba.turbo-model}")
    private String turboModel;

    @Value("${devops.ai.alibaba.reasoner-model}")
    private String reasonerModel;

    @Value("${devops.ai.alibaba.embedding-model}")
    private String embeddingModel;

    @Value("${devops.ai.alibaba.timeout}")
    private long timeout;

    @Value("${devops.ai.alibaba.max-retries}")
    private int maxRetries;

    /**
     * 向量维度（全链路唯一来源：devops.ai.vector.dimension）
     * <p>
     * 必须与 {@code init.sql} 的 {@code VECTOR(n)} 和
     * {@link VectorStoreConfig} 读的是同一个配置键——此前这里不读配置、
     * 也不向 Embedding API 传 dimensions，所谓「维度铁律」只是注释，
     * 换模型时会在写库那一刻才炸（列类型不匹配）。
     * </p>
     */
    @Value("${devops.ai.vector.dimension}")
    private int vectorDimension;

    // ==================== Real 模式（生产模式）====================

    /**
     * Turbo 模型（主力模型,日常对话）
     * 特点：快速响应、成本低，适合 80% 的日常咨询场景
     */
    @Bean(name = "turboModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public ChatModel turboModel() {
        log.info("🚀 [AiModelConfig] 初始化 Turbo 模型: {}", turboModel);
        return OpenAiChatModel.builder()
                .baseUrl(alibabaBaseUrl)
                .apiKey(alibabaApiKey)
                .modelName(turboModel)
                .timeout(Duration.ofMillis(timeout))
                .maxRetries(maxRetries) // 最大重试次数
                .logRequests(true) // 开发期开启请求日志（方便调试）
                .logResponses(false) // 生产环境关闭响应日志（避免泄露敏感信息）
                .build();
    }

    /**
     * Reasoner 模型（推理模型，复杂问题）
     * 特点：推理能力强、延迟高、成本高，仅用于复杂堆栈问题（由 DevOpsIntentRouter 路由）
     */
    @Bean(name = "reasonerModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public ChatModel reasonerModel() {
        log.info("🚀 [AiModelConfig] 初始化 Reasoner 模型: {}", reasonerModel);
        return OpenAiChatModel.builder()
                .baseUrl(alibabaBaseUrl)
                .apiKey(alibabaApiKey)
                .modelName(reasonerModel)
                .timeout(Duration.ofMillis(timeout * 2)) // 推理模型超时时间翻倍
                .maxRetries(maxRetries)
                .logRequests(true)
                .logResponses(false)
                .build();
    }

    /**
     * Turbo 流式模型（原生 SSE 流式 + 工具调用，供 Agent 引擎使用）
     * <p>与同步 turboModel 独立：同步版供 HealthCheck 连通性探测，流式版供对话链路。</p>
     */
    @Bean(name = "turboStreamingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public StreamingChatModel turboStreamingModel() {
        // 注意：OpenAiStreamingChatModelBuilder 在 langchain4j-open-ai 1.1.0 中
        // 没有 maxRetries 方法（与同步 OpenAiChatModelBuilder 不同）。
        // 流式重试需在更上层（编排层/HTTP 客户端层）兜底，而非此处。
        log.info("🚀 [AiModelConfig] 初始化 Turbo 流式模型: {} (流式无 maxRetries，由编排层兜底)", turboModel);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(alibabaBaseUrl)
                .apiKey(alibabaApiKey)
                .modelName(turboModel)
                .timeout(Duration.ofMillis(timeout))
                .logRequests(true)
                .logResponses(false)
                .build();
    }

    /**
     * Reasoner 流式模型（复杂推理，超时时间翻倍）
     */
    @Bean(name = "reasonerStreamingModel")
    @ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
    public StreamingChatModel reasonerStreamingModel() {
        // 注意：OpenAiStreamingChatModelBuilder 在 langchain4j-open-ai 1.1.0 中
        // 没有 maxRetries 方法（与同步 OpenAiChatModelBuilder 不同）。
        log.info("🚀 [AiModelConfig] 初始化 Reasoner 流式模型: {} (流式无 maxRetries，由编排层兜底)", reasonerModel);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(alibabaBaseUrl)
                .apiKey(alibabaApiKey)
                .modelName(reasonerModel)
                .timeout(Duration.ofMillis(timeout * 2))
                .logRequests(true)
                .logResponses(false)
                .build();
    }

    /**
     * Embedding 模型（向量化模型）
     * <p>
     * <b>维度铁律</b>：输出维度必须等于 {@code devops.ai.vector.dimension}，
     * 该值同时决定 {@code init.sql} 的 {@code VECTOR(n)} 与
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
    public EmbeddingModel embeddingModel() {
        // 维度取自配置（devops.ai.vector.dimension），与 init.sql 的
        // VECTOR(n) 同源。
        //
        // 必须显式传 dimensions：多数现代 Embedding 模型的原生维度并非 1536
        // （gemini-embedding-001 是 3072、qwen3-embedding-8b 是 4096），
        // 但它们支持 MRL 截断降维。不传这个参数就会拿到原生维度，
        // 而故障只在**写库那一刻**才暴露：
        //   ERROR: expected 1536 dimensions, not 3072
        // 此前这里不传参，日志却硬编码打印「(输出维度: 1536)」——
        // 日志在说谎，反而掩盖了真实维度，排查时会误以为配置已生效。
        log.info("🚀 [AiModelConfig] 初始化 Embedding 模型: {} | 请求降维至 {} 维（与 init.sql VECTOR({}) 对齐）",
                embeddingModel, vectorDimension, vectorDimension);
        return OpenAiEmbeddingModel.builder()
                .baseUrl(alibabaBaseUrl)
                .apiKey(alibabaApiKey)
                .modelName(embeddingModel)
                .dimensions(vectorDimension)
                .timeout(Duration.ofMillis(timeout))
                .maxRetries(maxRetries)
                .logRequests(false) // Embedding 请求频繁，关闭日志
                .build();
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
    public EmbeddingModel mockEmbeddingModel() {
        log.warn("⚠️ [AiModelConfig] Mock 模式：Embedding 模型将返回假向量（1536 维零向量）");
        return new MockEmbeddingModel();
    }
}
