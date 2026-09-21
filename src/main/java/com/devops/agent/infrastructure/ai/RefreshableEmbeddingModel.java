package com.devops.agent.infrastructure.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可热刷新的 EmbeddingModel（P3-1）。
 */
public class RefreshableEmbeddingModel implements EmbeddingModel {

    private final AtomicReference<EmbeddingModel> delegate = new AtomicReference<>();

    public RefreshableEmbeddingModel(EmbeddingModel initial) { this.delegate.set(initial); }

    public void refreshTo(EmbeddingModel fresh) { delegate.set(fresh); }

    @Override
    public Response<Embedding> embed(String text) { return delegate.get().embed(text); }

    @Override
    public Response<Embedding> embed(TextSegment segment) { return delegate.get().embed(segment); }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return delegate.get().embedAll(segments);
    }
}