package com.devops.agent.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * 把 {@link LlmEndpointSpec} 变成 LangChain4j 模型对象的<b>唯一去处</b>（2026-08-27）。
 *
 * <h3>这个类的存在意义：把「OpenAI 协议」这件事收敛到一个文件里</h3>
 * 项目对接的是阿里云百炼的 OpenAI 兼容端点，但这只是<b>当下</b>的选择。
 * 此前 {@code OpenAiChatModel.builder()} 这类协议专有调用散布在
 * {@code AiModelConfig} 的 5 个 Bean 方法中。想换成 Azure OpenAI SDK、
 * Ollama、Bedrock 或自研网关时，要在配置类里逐个方法改。
 *
 * <p>收敛之后，换协议 = 换掉本类一个实现（或新增一个工厂 + 改配置），
 * {@code AiModelConfig} 只负责「读配置、决定注册哪些 Bean」，
 * 不再知道底层用的是什么协议。</p>
 *
 * <h3>为什么不做成接口 + 多实现</h3>
 * 现在只有一种协议在用。过早抽接口会得到一个只有单实现的抽象，
 * 徒增一层却挡不住任何东西——真到要换的那天，接口形状多半也不合适。
 * 这里先做到「协议细节只出现在一个文件里」，
 * 这是可插拔的第一步，也是当前收益最高的一步。
 * 需要多协议并存时再抽接口，那时才知道该抽成什么样。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
public final class OpenAiCompatibleModelFactory {

    private OpenAiCompatibleModelFactory() {
    }

    /**
     * 同步对话模型。
     *
     * @param spec        端点描述
     * @param logRequests 是否打印请求体。<b>响应体一律不打</b>——
     *                    模型响应里可能带用户输入的敏感信息，
     *                    进了日志采集系统就收不回来了
     */
    public static ChatModel chat(LlmEndpointSpec spec, boolean logRequests) {
        return OpenAiChatModel.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .timeout(spec.timeout())
                .maxRetries(spec.maxRetries())
                .logRequests(logRequests)
                .logResponses(false)
                .build();
    }

    /**
     * 流式对话模型。
     *
     * <p>注意<b>没有 maxRetries</b>：LangChain4j 1.1.0 的
     * {@code OpenAiStreamingChatModelBuilder} 不提供该方法（与同步 builder 不同）。
     * {@link LlmEndpointSpec#streaming()} 已把 spec 的重试数显式归零，
     * 使配置读起来与实际行为一致——流式重试由编排层兜底。</p>
     */
    public static StreamingChatModel streamingChat(LlmEndpointSpec spec, boolean logRequests) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .timeout(spec.timeout())
                .logRequests(logRequests)
                .logResponses(false)
                .build();
    }

    /**
     * Embedding 模型。
     *
     * <p>{@code dimensions} 必须显式传给 API——原因见
     * {@link LlmEndpointSpec#embedding}。这里再加一道运行期校验：
     * 拿到一个没有维度的 spec 说明调用方走错了工厂方法，
     * 与其静默用原生维度（写库时才炸），不如现在就炸。</p>
     */
    public static EmbeddingModel embedding(LlmEndpointSpec spec) {
        if (spec.dimensions() == null) {
            throw new IllegalArgumentException(
                    "Embedding 端点必须指定维度：不传 dimensions 会拿到模型原生维度"
                            + "（如 3072/4096），而错误要到写库那一刻才暴露");
        }
        return OpenAiEmbeddingModel.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .dimensions(spec.dimensions())
                .timeout(spec.timeout())
                .maxRetries(spec.maxRetries())
                // Embedding 请求频繁（每个切片一次），打日志会淹没其它信息
                .logRequests(false)
                .build();
    }
}
