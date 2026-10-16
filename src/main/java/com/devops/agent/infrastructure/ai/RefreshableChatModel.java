package com.devops.agent.infrastructure.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 可热刷新的 ChatModel（P3-1：编辑保存 → 即时生效，无需重启）。
 *
 * <p>Spring 注入此 Bean 的引用永不变化，但内部 delegate 可在运行时原子替换。
 * 所有调用委托给当前 delegate——Agent 引擎感知不到热更新。</p>
 */
public class RefreshableChatModel implements ChatModel {

    private final AtomicReference<ChatModel> delegate = new AtomicReference<>();

    public RefreshableChatModel(ChatModel initial) { this.delegate.set(initial); }

    public void refreshTo(ChatModel fresh) { delegate.set(fresh); }

    @Override
    public ChatResponse chat(ChatRequest request) { return delegate.get().chat(request); }
}