package com.devops.agent.infrastructure.llm;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

import java.util.List;

/**
 * 限流 Embedding 模型装饰器（S0-3，路线图 §4.3 任务 0-3.5）。
 *
 * <h3>解决什么问题</h3>
 * Embedding 调用是配额消耗最重的 LLM 出口（摄取/重建索引按批打），
 * 无节制时一个失控任务（例：误触发 reindex 循环）能在账单反应过来之前
 * 把云侧 quota 打到限流，随后所有正常对话检索跟着 429——
 * <b>一次意外，全站失能</b>。装饰器在应用侧先用自己的限流器拦住，
 * 让超额调用<b>快速失败并显式提示</b>（{@link LlmRateLimitedException}），
 * 而不是在云侧堆出模糊的 HTTP 429。
 *
 * <h3>为什么用装饰器而不是在调用方加注解</h3>
 * Embedding 的调用方有三个（检索、摄取、重建索引），注解各贴一份会重演
 * 「同一事实写两处」的老问题；装饰 Bean 层是自然收口——
 * 任何路径拿到的 {@code embeddingModel} 都已带限流。
 *
 * <h3>范围说明（诚实边界）</h3>
 * <ul>
 *   <li>限流拒绝即失败：{@code timeout-duration: 0}（application.yml 已注明依据），
 *       不排队——SSE/调度线程不应挂在许可等待上；</li>
 *   <li>本装饰器不管超时：单次调用的时长上限由端点级 HTTP 超时承担
 *       （{@code LlmEndpointSpec.timeout}），chat 流式（TokenStream）在
 *       Bean 层既不能 TimeLimit 也不宜 RateLimit 打断，其治理随阶段 1
 *       的调用网关落地（见决策表）。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-09-07（S0-3）
 */
public class RateLimitedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final RateLimiter rateLimiter;

    public RateLimitedEmbeddingModel(EmbeddingModel delegate, RateLimiter rateLimiter) {
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Response<Embedding> embed(String text) {
        acquireOrThrow("embed(String)");
        return delegate.embed(text);
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        acquireOrThrow("embed(TextSegment)");
        return delegate.embed(textSegment);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        acquireOrThrow("embedAll(" + (textSegments == null ? 0 : textSegments.size()) + ")");
        return delegate.embedAll(textSegments);
    }

    private void acquireOrThrow(String callSite) {
        try {
            RateLimiter.waitForPermission(rateLimiter);
        } catch (RequestNotPermitted e) {
            throw new LlmRateLimitedException(
                    "LLM 限流：" + callSite + " 超过应用侧配额（"
                            + rateLimiter.getName() + " 实例），本次调用未发出。"
                            + "若是批量摄取/重建索引，请降低并发或增大 resilience4j.ratelimiter.instances.llm 限额", e);
        }
    }
}
