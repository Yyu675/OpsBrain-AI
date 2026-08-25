package com.devops.agent.infrastructure;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;

/**
 * Mock StreamingChatModel - 开发期不调用真实 API，逐字模拟流式响应
 * <p>
 * 用途：MOCK 模式下让流式引擎（TokenStream）链路可跑通，不消耗 API 额度。
 * 逐字回调 onPartialResponse，模拟真实流式的打字机效果。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-23
 */
public class MockStreamingChatModel implements StreamingChatModel {

    private final String modelName;

    public MockStreamingChatModel(String modelName) {
        this.modelName = modelName;
    }

    /**
     * {@inheritDoc}
     *
     * <h3>为什么要先把消息列表拷贝一份再遍历</h3>
     * {@code chatRequest.messages()} 返回的列表最终来自
     * {@code MessageWindowChatMemory} 持有的会话消息集合。
     * AI Service 在准备请求与写回记忆之间会对它做增删（滑动窗口裁剪、
     * 追加 AiMessage），而<b>本方法运行在同一条链路上</b>——
     * 并发请求下，一边遍历一边被改动即抛
     * {@link java.util.ConcurrentModificationException}。
     *
     * <p>该异常会在 {@code TokenStream.start()} 阶段抛出，被
     * {@code DevOpsAgentServiceImpl} 的兜底 catch 吞成一句
     * 「50001 服务内部异常」，用户看到的是「AI 挂了」。
     * 单请求下永远撞不到——遍历只有几微秒，而两次改动之间隔着整个模型调用。
     * 这个缺陷是 SSE 并发集成测试（4 路并发）抓出来的。</p>
     *
     * <p>虽然本类只在 MOCK 模式启用，但同样的遍历模式若出现在真实模型
     * 适配层就是线上缺陷，因此按正确写法修，而不是「反正是 mock」放过。</p>
     */
    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        try {
            // 先物化成不可变快照再遍历：List.copyOf 本身是一次性拷贝，
            // 之后集合再被改动也影响不到这里
            String userText = List.copyOf(chatRequest.messages()).stream()
                    .filter(msg -> msg instanceof UserMessage)
                    .map(msg -> ((UserMessage) msg).singleText())
                    .reduce((a, b) -> a + " " + b)
                    .orElse("无");

            String mockText = String.format(
                    "[MOCK流式响应 from %s] 这是模拟的 AI 流式回复。提问摘要：%s",
                    modelName, userText);

            // 逐字回调，模拟打字机
            for (int i = 0; i < mockText.length(); i++) {
                handler.onPartialResponse(String.valueOf(mockText.charAt(i)));
            }

            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.from(mockText))
                    .build();
            handler.onCompleteResponse(response);

        } catch (Exception e) {
            handler.onError(e);
        }
    }
}
