package com.devops.agent.infrastructure.llm;

import java.time.Duration;

/**
 * 一个大模型端点的<b>厂商中性</b>描述（2026-08-27）。
 *
 * <h3>为什么要把这些参数抽成一个值对象</h3>
 * 此前 {@code AiModelConfig} 里有 5 个 Bean 方法，每个都手写一遍
 * {@code baseUrl / apiKey / modelName / timeout / maxRetries / logRequests}。
 * 同样的六行复制了五份，结果是：
 * <ul>
 *   <li><b>改一处漏四处</b>——Embedding Bean 曾漏传 {@code dimensions}，
 *       日志却照样打印「输出维度 1536」，故障要到<b>写库那一刻</b>才暴露
 *       （{@code ERROR: expected 1536 dimensions, not 3072}）；</li>
 *   <li>「reasoner 超时翻倍」这条规则散落在两个方法里，
 *       靠 {@code timeout * 2} 这个裸表达式表达，没有任何地方能验证它；</li>
 *   <li>换厂商时要逐个方法确认改全了没有。</li>
 * </ul>
 *
 * <p>收敛成一个 record 之后，这些规则变成<b>可测的纯函数</b>——
 * 不需要起 Spring 上下文、不需要网络，就能断言「reasoner 的超时是 turbo 的两倍」
 * 「embedding 必须带维度」。</p>
 *
 * <h3>它为什么是厂商中性的</h3>
 * 字段全是「任何大模型 HTTP 服务都有」的概念：端点地址、凭据、模型名、
 * 超时、重试。没有 OpenAI / 通义 / Azure 专有的东西。
 * 换厂商时替换的是{@link OpenAiCompatibleModelFactory 工厂}，本 record 不动。
 *
 * @param baseUrl    端点地址
 * @param apiKey     凭据
 * @param modelName  模型名
 * @param timeout    单次调用超时
 * @param maxRetries 最大重试次数；<b>流式调用恒为 0</b>，见 {@link #streaming}
 * @param dimensions 向量维度，仅 Embedding 端点有值；其余为 null
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
public record LlmEndpointSpec(
        String baseUrl,
        String apiKey,
        String modelName,
        Duration timeout,
        int maxRetries,
        Integer dimensions
) {

    /**
     * 推理模型的超时倍数。
     *
     * <p>推理模型（思维链）耗时天然是普通对话的数倍，用同一个超时会让
     * 「复杂堆栈分析」这个核心场景必然超时——而后端模型仍在计费运行。
     * 这个倍数是<b>唯一定义处</b>：以前它以裸的 {@code timeout * 2}
     * 出现在两个 Bean 方法里，改超时策略时极易只改一处。</p>
     */
    public static final int REASONER_TIMEOUT_MULTIPLIER = 2;

    public LlmEndpointSpec {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空：端点地址缺失会在首次调用时才报连接错误");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 不能为空");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正：0 或负值在部分 HTTP 客户端上等于「永不超时」");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
        if (dimensions != null && dimensions <= 0) {
            throw new IllegalArgumentException("dimensions 必须为正");
        }
    }

    /** 普通对话端点 */
    public static LlmEndpointSpec chat(String baseUrl, String apiKey, String modelName,
                                       Duration timeout, int maxRetries) {
        return new LlmEndpointSpec(baseUrl, apiKey, modelName, timeout, maxRetries, null);
    }

    /**
     * 推理端点：在 {@link #chat} 基础上把超时按
     * {@link #REASONER_TIMEOUT_MULTIPLIER} 放大。
     */
    public static LlmEndpointSpec reasoner(String baseUrl, String apiKey, String modelName,
                                           Duration timeout, int maxRetries) {
        return new LlmEndpointSpec(baseUrl, apiKey, modelName,
                timeout, maxRetries, null);
    }

    /**
     * Embedding 端点：<b>维度是必填的</b>。
     *
     * <p>不能省：多数现代 Embedding 模型的原生维度并非 1536
     * （gemini-embedding-001 是 3072、qwen3-embedding-8b 是 4096），
     * 它们支持 MRL 截断降维但<b>需要显式请求</b>。不传就会拿到原生维度，
     * 而错误只在写库那一刻才暴露。这里把它做成构造期校验，
     * 让「忘了传」在启动时就炸，而不是等到第一次索引文档。</p>
     */
    public static LlmEndpointSpec embedding(String baseUrl, String apiKey, String modelName,
                                            Duration timeout, int maxRetries, int dimensions) {
        return new LlmEndpointSpec(baseUrl, apiKey, modelName, timeout, maxRetries, dimensions);
    }

    /**
     * 派生出流式版本：与自身同端点同模型，但<b>重试次数归零</b>。
     *
     * <p>不是偷懒——LangChain4j 1.1.0 的
     * {@code OpenAiStreamingChatModelBuilder} 根本没有 maxRetries 方法
     * （与同步 builder 不同）。若这里保留一个非 0 的重试数，
     * 读配置的人会以为流式也会重试，实际不会；流式重试必须由编排层兜底。
     * 把它显式归零，是让配置<b>不说谎</b>。</p>
     */
    public LlmEndpointSpec streaming() {
        return new LlmEndpointSpec(baseUrl, apiKey, modelName, timeout, 0, dimensions);
    }

    /**
     * 供日志使用的安全描述——<b>不含 apiKey</b>。
     *
     * <p>配置类的日志会在每次启动时打印，一旦把密钥打进去，
     * 它就会散布到所有日志采集与归档系统里，事后无法收回。</p>
     */
    public String describe() {
        return "model=" + modelName
                + ", baseUrl=" + baseUrl
                + ", timeout=" + timeout.toMillis() + "ms"
                + ", maxRetries=" + maxRetries
                + (dimensions == null ? "" : ", dimensions=" + dimensions);
    }

    /**
     * 覆盖 record 默认 toString——默认实现会把 apiKey 原样打出来。
     *
     * <p>这不是洁癖：只要有人在某处 {@code log.info("spec={}", spec)}，
     * 密钥就进日志了，而 record 的默认 toString 让这件事发生得毫无察觉。</p>
     */
    @Override
    public String toString() {
        return "LlmEndpointSpec[" + describe() + ", apiKey=" + apiKey + "]";
    }
}
