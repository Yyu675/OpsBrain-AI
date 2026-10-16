package com.devops.agent.infrastructure.ai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 可热刷新的 StreamingChatModel（P3-1）。
 */
public class RefreshableStreamingChatModel implements StreamingChatModel {

    private final AtomicReference<StreamingChatModel> delegate = new AtomicReference<>();

    public RefreshableStreamingChatModel(StreamingChatModel initial) { this.delegate.set(initial); }

    public void refreshTo(StreamingChatModel fresh) { delegate.set(fresh); }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.get().chat(request, handler);
    }
}