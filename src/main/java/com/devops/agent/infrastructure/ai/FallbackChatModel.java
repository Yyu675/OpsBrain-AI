package com.devops.agent.infrastructure.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主备自动降级 ChatModel（方案 A：模型池降级，2026-09-22）。
 *
 * <h3>降级语义</h3>
 * <ul>
 *   <li>熔断 CLOSED/HALF_OPEN：走主模型；成功/失败都记入 CircuitBreaker；</li>
 *   <li>主模型调用异常（超时/限流/宕机）：记录失败并<b>立即用备用模型重试一次</b>；</li>
 *   <li>熔断 OPEN（主模型持续故障）：跳过主模型直接走备用，不再浪费一次超时等待；</li>
 *   <li>备用模型也失败：原样抛出（上层 SSE error 事件处理不变）。</li>
 * </ul>
 *
 * <h3>为什么备用调用不记入熔断器</h3>
 * CB 度量的是「主模型健康度」，混入备用的成败会让恢复探测失真：
 * 备用一直成功会掩盖主模型仍在挂的事实，备用偶发失败又会误判主模型更坏。
 *
 * <h3>与 Refreshable 包装器的关系</h3>
 * 本类实例被包在 {@link RefreshableChatModel} 里；渠道热更新时整个
 * 「主+备+CB」束一起原子替换（新 CB 实例，旧健康历史不带入——配置都换了，
 * 旧统计已无意义）。
 */
public class FallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

    private final ChatModel primary;
    private final ChatModel fallback;
    private final CircuitBreaker breaker;
    private final String primaryName;
    private final String fallbackName;

    public FallbackChatModel(ChatModel primary, ChatModel fallback, CircuitBreaker breaker,
                             String primaryName, String fallbackName) {
        this.primary = primary;
        this.fallback = fallback;
        this.breaker = breaker;
        this.primaryName = primaryName;
        this.fallbackName = fallbackName;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        // 熔断 OPEN：主模型持续故障，直接走备用（不浪费一次完整超时等待）
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            log.warn("⚠️ [FallbackChat] 主模型 {} 熔断中（OPEN），直接走备用 {}", primaryName, fallbackName);
            return fallback.chat(request);
        }
        long start = System.nanoTime();
        try {
            ChatResponse resp = primary.chat(request);
            breaker.onSuccess(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
            return resp;
        } catch (RuntimeException e) {
            breaker.onError(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS, e);
            log.warn("⚠️ [FallbackChat] 主模型 {} 调用失败（{}），降级到备用 {} | CB 状态={}",
                    primaryName, brief(e), fallbackName, breaker.getState());
            return fallback.chat(request);
        }
    }

    private static String brief(Throwable t) {
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }
}
