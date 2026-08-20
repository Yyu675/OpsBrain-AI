package com.devops.agent.infrastructure;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Mock ChatModel - 开发期不调用真实 API,返回固定响应
 */
public class MockChatModel implements ChatModel {

    private final String modelName;

    public MockChatModel(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        String mockText = String.format(
            "[MOCK响应 from %s] 这是模拟的AI回复。实际部署时会调用真实API。提问内容摘要: %s",
            modelName,
            chatRequest.messages().stream()
                .filter(msg -> msg instanceof dev.langchain4j.data.message.UserMessage)
                .map(msg -> ((dev.langchain4j.data.message.UserMessage) msg).singleText())
                .reduce((a, b) -> a + " " + b)
                .orElse("无")
        );

        return ChatResponse.builder()
            .aiMessage(AiMessage.from(mockText))
            .build();
    }
}
