package com.devops.agent.infrastructure;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

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

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        try {
            String userText = chatRequest.messages().stream()
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
