package com.devops.agent.infrastructure.ai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主备自动降级 StreamingChatModel（方案 A，2026-09-22）。
 *
 * <h3>首包边界——为什么流式降级只在这里安全</h3>
 * 流式输出一旦开始吐 token，用户已经看到了半截回答。此时换备用模型重发
 * 会导致输出错乱/重复。因此降级窗口<b>严格限定在「尚未收到第一个
 * onPartialResponse」之前</b>——恰好覆盖连接超时、限流拒绝、上游宕机这些
 * 最高发的故障阶段。已经吐过 token 后失败，原样把 error 透传给上层
 * （SSE error 事件路径不变）。
 *
 * <h3>异步错误路径</h3>
 * 流式失败不抛同步异常，而是回调 {@code handler.onError}。降级逻辑因此
 * 活在包装 handler 的 onError 里：首包前失败 → 记熔断 + 用备用模型对
 * <b>原始 handler</b> 重新发起（上层感知不到中间换过模型）。
 *
 * <h3>熔断语义</h3>
 * 与 {@link FallbackChatModel} 一致：CB 只度量主模型；OPEN 时直接走备用；
 * 备用自身的成败不记入 CB。
 */
public class FallbackStreamingChatModel implements StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackStreamingChatModel.class);

    private final StreamingChatModel primary;
    private final StreamingChatModel fallback;
    private final CircuitBreaker breaker;
    private final String primaryName;
    private final String fallbackName;

    public FallbackStreamingChatModel(StreamingChatModel primary, StreamingChatModel fallback,
                                      CircuitBreaker breaker, String primaryName, String fallbackName) {
        this.primary = primary;
        this.fallback = fallback;
        this.breaker = breaker;
        this.primaryName = primaryName;
        this.fallbackName = fallbackName;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        // 熔断 OPEN / 探测名额满：跳过主模型直接走备用
        CircuitBreaker.State state = breaker.getState();
        if (state == CircuitBreaker.State.OPEN) {
            log.warn("⚠️ [FallbackStream] 主模型 {} 熔断中（OPEN），直接走备用 {}", primaryName, fallbackName);
            fallback.chat(request, handler);
            return;
        }

        long start = System.nanoTime();
        AtomicBoolean firstTokenEmitted = new AtomicBoolean(false);
        // 降级只能发生一次：防止备用也失败时再次进入降级路径造成无限递归
        AtomicBoolean fellBack = new AtomicBoolean(false);

        StreamingChatResponseHandler tracking = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                firstTokenEmitted.set(true);
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                // 已吐过 token：输出错乱风险 > 降级收益，原样上报
                if (firstTokenEmitted.get()) {
                    log.warn("⚠️ [FallbackStream] 主模型 {} 流中断（已输出部分 token，不可降级）| {}",
                            primaryName, brief(error));
                    breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, error);
                    handler.onError(error);
                    return;
                }
                // 首包前失败：记熔断 + 切备用（对原始 handler 重发，上层无感知）
                if (fellBack.compareAndSet(false, true)) {
                    breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, error);
                    log.warn("⚠️ [FallbackStream] 主模型 {} 首包前失败（{}），降级到备用 {} | CB 状态={}",
                            primaryName, brief(error), fallbackName, breaker.getState());
                    try {
                        fallback.chat(request, handler);
                    } catch (RuntimeException fe) {
                        log.error("🔴 [FallbackStream] 备用 {} 同步启动也失败 | {}", fallbackName, brief(fe));
                        handler.onError(error);
                    }
                } else {
                    handler.onError(error);
                }
            }
        };

        try {
            primary.chat(request, tracking);
        } catch (RuntimeException e) {
            // 少数实现会在 chat() 同步抛出（如连接池立即拒绝）：同样走首包前降级
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            log.warn("⚠️ [FallbackStream] 主模型 {} 同步失败（{}），降级到备用 {}", primaryName, brief(e), fallbackName);
            fallback.chat(request, handler);
        }
    }

    private static String brief(Throwable t) {
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }
}
