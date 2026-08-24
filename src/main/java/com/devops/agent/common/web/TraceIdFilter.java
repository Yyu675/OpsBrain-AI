package com.devops.agent.common.web;

import com.devops.agent.common.context.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全链路 traceId 注入过滤器（A5 修复）。
 *
 * <p>职责：
 * <ol>
 *   <li>为每个请求建立 traceId（优先复用上游 {@code X-Request-Id}，便于跨服务串联）；</li>
 *   <li>写入 MDC，使所有日志自动带上 {@code %X{traceId}}；</li>
 *   <li><b>回写到响应头</b>，前端可展示/上报——用户截图报错时凭 traceId
 *       即可 grep 到后端全链路日志，这是排障效率的关键；</li>
 *   <li>请求结束在 finally 清理，防止线程池复用导致 traceId 串号。</li>
 * </ol>
 *
 * <h3>为什么是 HIGHEST_PRECEDENCE</h3>
 * 必须排在所有其他 Filter（含 Sa-Token 鉴权、CORS）之前。否则鉴权失败等
 * 早期返回路径产生的日志和响应就没有 traceId，而这些恰恰是最需要排查的场景。
 *
 * <h3>⚠️ 关于异步请求（SSE）</h3>
 * {@code OncePerRequestFilter} 默认<b>不</b>对异步派发重复执行
 * （{@code shouldNotFilterAsyncDispatch()} 返回 true）。对 SSE 而言，
 * 本 Filter 在初始请求线程设置 MDC，而真正的流式输出发生在业务自己的
 * 异步线程（{@code sessionExecutor}）——那里的 MDC 需要由业务代码
 * 通过 {@code TraceContext.wrap/capture/restore} 显式搬运，Filter 管不到。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.begin(request.getHeader(TraceContext.HEADER));
        try {
            // 尽早回写：即使后续抛异常，响应头也已带上 traceId
            response.setHeader(TraceContext.HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
