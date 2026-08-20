package com.devops.agent.application.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 智能意图路由器
 * <p>
 * 职责: 根据问题复杂度分发到 Turbo 模型或 Reasoner 模型
 * 分流规则:
 * - 短问(< 60字符) 且无堆栈特征 → turboEngine (deepseek-chat)
 * - 长问(≥ 150字符) 或含堆栈特征 → reasoningEngine (deepseek-reasoner)
 * - 灰度地带 → 兜底 turboEngine
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Component
public class DevOpsIntentRouter {

    private static final Logger log = LoggerFactory.getLogger(DevOpsIntentRouter.class);

    /**
     * 路由结果：引擎实例 + 对应模型名（P2-18）
     * <p>
     * 引擎与模型名必须成对返回，否则降级后「SSE start 事件展示的模型名」
     * 与实际使用的引擎不一致：配额按 REASONER 计价、展示却用 Turbo。
     * </p>
     */
    public record RoutingResult(DevOpsAgentEngine engine, String modelName) {
        static RoutingResult of(DevOpsAgentEngine engine, String modelName) {
            return new RoutingResult(engine, modelName);
        }
    }

    /**
     * 堆栈特征词正则(不区分大小写)
     * 匹配: exception、stacktrace、at com.、caused by、panic、error、failed、timeout 等
     */
    private static final Pattern STACK_TRACE_PATTERN = Pattern.compile(
            ".*(exception|stacktrace|stack trace|at com\\.|at java\\.|caused by|panic|" +
                    "error:|failed:|timeout|java\\.lang\\.|NullPointerException|RuntimeException).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * 短问阈值(字符数)
     */
    private static final int SHORT_QUERY_THRESHOLD = 60;

    /**
     * 长问阈值(字符数)
     */
    private static final int LONG_QUERY_THRESHOLD = 150;

    private final DevOpsAgentEngine turboAgentEngine;
    private final DevOpsAgentEngine reasoningAgentEngine;

    /**
     * Turbo 模型名（从配置读取，供 SSE start 事件展示真实模型名）
     */
    @org.springframework.beans.factory.annotation.Value("${devops.ai.alibaba.turbo-model:turbo}")
    private String turboModelName;

    /**
     * Reasoner 模型名（从配置读取）
     */
    @org.springframework.beans.factory.annotation.Value("${devops.ai.alibaba.reasoner-model:reasoner}")
    private String reasonerModelName;

    public DevOpsIntentRouter(@org.springframework.beans.factory.annotation.Qualifier("turboAgentEngine") DevOpsAgentEngine turboAgentEngine,
                              @org.springframework.beans.factory.annotation.Qualifier("reasoningAgentEngine") DevOpsAgentEngine reasoningAgentEngine) {
        this.turboAgentEngine = turboAgentEngine;
        this.reasoningAgentEngine = reasoningAgentEngine;
    }

    /**
     * 路由到具体引擎
     *
     * <p>P2-18：Reasoner 引擎不可用时自动降级到 Turbo 引擎，
     * 避免因单引擎不可用导致整个对话链路中断。</p>
     *
     * @param userQuery 用户提问
     * @return 选中的引擎实例
     */
    public DevOpsAgentEngine routeEngine(String userQuery) {
        return route(userQuery).engine();
    }

    /**
     * 一次路由决策，同时返回引擎与模型名（P2-18）
     *
     * <p>原实现 {@code routeEngine} 与 {@code routedModelName} 各自调用一次
     * {@link #routeDecision}，同一查询被判定两次。本方法把路由决策收敛到
     * <b>一次</b>调用，引擎与模型名成对返回，杜绝两条路径判定不一致
     * （如一次 REASONING、一次 TURBO）与无谓的重复正则匹配。</p>
     *
     * @param userQuery 用户提问
     * @return 引擎 + 模型名（Reasoner 不可用时自动降级为 Turbo 对）
     */
    public RoutingResult route(String userQuery) {
        if ("REASONING".equals(routeDecision(userQuery))) {
            log.info("🧠 [Router] 路由到 Reasoner 引擎 | 原因: 复杂推理场景");
            try {
                return RoutingResult.of(reasoningAgentEngine, reasonerModelName);
            } catch (Exception e) {
                // 返回 Bean 引用本身不会抛异常，但 Bean 可能在运行时处于不可用状态
                // （如底层 StreamingChatModel 连接异常），提前捕获让降级路径清晰
                log.warn("⚠️ [Router] Reasoner 引擎不可用，降级到 Turbo | error={}", e.getMessage());
                return RoutingResult.of(turboAgentEngine, turboModelName);
            }
        } else {
            log.info("⚡ [Router] 路由到 Turbo 引擎 | 原因: 日常问答场景");
            return RoutingResult.of(turboAgentEngine, turboModelName);
        }
    }

    /**
     * 获取路由后的模型名称(供 SSE start 事件使用)
     *
     * <p>P2-18：与 {@link #routeEngine} 保持一致的降级逻辑，
     * Reasoner 不可用时返回 Turbo 模型名，避免 SSE start 事件展示错误的模型名。</p>
     *
     * @param userQuery 用户提问
     * @return 模型名称
     */
    public String routedModelName(String userQuery) {
        return route(userQuery).modelName();
    }

    /**
     * 路由决策逻辑(纯本地规则,0延迟0成本)
     *
     * @param userQuery 用户提问
     * @return "TURBO" 或 "REASONING"
     */
    private String routeDecision(String userQuery) {
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return "TURBO";
        }

        int length = userQuery.length();
        boolean hasStackTrace = STACK_TRACE_PATTERN.matcher(userQuery).matches();

        // 优先级 1: 短问 + 无堆栈特征 → TURBO
        if (length < SHORT_QUERY_THRESHOLD && !hasStackTrace) {
            log.debug("📊 [RouterDecision] TURBO | 原因: 短问({}<60) 且无堆栈特征", length);
            return "TURBO";
        }

        // 优先级 2: 长问 或 含堆栈特征 → REASONING
        if (length >= LONG_QUERY_THRESHOLD || hasStackTrace) {
            log.debug("📊 [RouterDecision] REASONING | 原因: 长问({}≥150) 或含堆栈特征={}", length, hasStackTrace);
            return "REASONING";
        }

        // 优先级 3: 灰度地带(60~150) → 兜底 TURBO
        log.debug("📊 [RouterDecision] TURBO(兜底) | 原因: 灰度地带({} in 60~150)", length);
        return "TURBO";
    }
}
