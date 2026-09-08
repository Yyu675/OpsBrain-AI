package com.devops.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.devops.agent.application.runtime.AgentState;
import com.devops.agent.application.runtime.AgentStateManager;
import com.devops.agent.application.runtime.AgentStateTransition;
import com.devops.agent.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话轨迹查询控制器
 * <p>
 * 把 {@link AgentStateManager} 攒下的状态迁移记录暴露出来，供运维回放一次
 * 对话到底走过哪些环节、每段花了多久、在哪里断掉。
 * </p>
 *
 * <h3>为什么需要这个接口</h3>
 * {@code AgentStateManager} 的类注释一直把「提供状态查询、回放数据导出」列为职责，
 * 每次对话也确实在忠实记录迁移轨迹——但此前<b>没有任何生产代码读取过它们</b>。
 * 数据只进不出，等会话被空闲清理就彻底消失。出问题时运维只能翻散落在
 * 各处的日志文本，靠 traceId 手工拼凑执行链路。
 *
 * <p>这与「业务码词表零调用方」是同一类问题：<b>能力建好了，但没接线</b>。
 * 单独看每个环节都没错，合起来就是一份从未被使用的数据。</p>
 *
 * <h3>为什么是内存查询而非查库</h3>
 * 轨迹存活在 {@code AgentStateManager} 的内存 Map 里，30 分钟空闲后被清理。
 * 这个接口因此只能查<b>近期会话</b>——它服务的场景是「刚出问题，马上排查」，
 * 而不是长期审计（长期审计走 {@code recordLogAsync} 落库那条线）。
 * 接口对「查不到」与「没有轨迹」明确区分，不让运维误以为流程没走。
 *
 * <h3>权限：限 ADMIN（与 AuditLogController / ApprovalController 一致）</h3>
 * 这个接口<b>看起来</b>只读无害，实际上轨迹的 {@code triggerDetail} 字段
 * 逐条拼进了相当敏感的内容：
 * <ul>
 *   <li>{@code "HIGH 优先级工单待审批: " + draft.title()} —— <b>工单标题原文</b>，
 *       往往直接写着「XX 生产库主从延迟」这类内部系统与故障细节；</li>
 *   <li>{@code "系统异常: " + e.getMessage()} / {@code "异常：" + error.getMessage()}
 *       —— <b>原始异常消息</b>，可能带出内网地址、SQL 片段、依赖服务名；</li>
 *   <li>{@code "安全拦截: " + e.getMessage()} —— <b>安全规则的命中原因</b>，
 *       等于把提示词注入防线的判定逻辑透露给攻击者；</li>
 *   <li>{@code "Saga 补偿失败，需人工清理: " + result.failed()} —— 脏数据位置。</li>
 * </ul>
 *
 * <p>换言之它与 {@code AuditLogController} 是同一量级的高敏数据，
 * 只是载体从数据库换成了内存。<b>「只读」说的是不改数据，不等于可以随便看。</b>
 * 定级应当看<b>数据内容</b>而非操作类型——这一点在最初落地本控制器时判断失误，
 * 此处修正并记录，避免后来人沿用「反正是 GET」的直觉。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/traces")
@SaCheckRole("ADMIN")
public class AgentTraceController {

    private final AgentStateManager stateManager;

    public AgentTraceController(AgentStateManager stateManager) {
        this.stateManager = stateManager;
    }

    /**
     * 查询单次会话的完整状态迁移轨迹
     *
     * @param traceId 追踪 ID（= 前端 SSE 事件里回传的 traceId）
     * @return 轨迹列表 + 当前状态 + 汇总信息
     */
    @GetMapping("/{traceId}")
    public ApiResponse<Map<String, Object>> getTrace(@PathVariable String traceId) {
        AgentState current = stateManager.getCurrentState(traceId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("traceId", traceId);

        // 「会话不存在」和「会话存在但还没发生迁移」是两件事，必须分开告诉运维：
        // 前者说明会话已被清理或 traceId 抄错了，后者说明流程真的卡在最开始。
        // 混成一个空列表会让人在错误的方向上排查很久。
        if (current == null) {
            data.put("found", false);
            data.put("currentState", null);
            data.put("transitions", List.of());
            data.put("message", "会话不存在：可能已超过 30 分钟空闲期被清理，或 traceId 有误");
            return ApiResponse.success(data);
        }

        List<AgentStateTransition> transitions = stateManager.exportTransitions(traceId);

        data.put("found", true);
        data.put("currentState", current.name());
        data.put("currentStateText", current.getDisplayName());
        // 是否还在自动流程中——补偿中/人工升级虽非终态，但已脱离自动化链路，
        // 前端据此决定要不要继续轮询
        data.put("settled", current.isSettled());
        data.put("terminal", current.isTerminal());

        List<Map<String, Object>> items = new ArrayList<>(transitions.size());
        long totalMs = 0;
        for (AgentStateTransition t : transitions) {
            items.add(toItem(t));
            totalMs += t.getDurationMs();
        }
        data.put("transitions", items);
        data.put("transitionCount", items.size());
        data.put("totalDurationMs", totalMs);

        if (items.isEmpty()) {
            data.put("message", "会话已创建但尚未发生任何状态迁移");
        }

        return ApiResponse.success(data);
    }

    /**
     * 查询当前驻留的会话数
     * <p>
     * 用于确认空闲清理确实在工作。这个数字只涨不跌，就说明清理逻辑失效了
     * ——那是一条随请求量线性增长的内存泄漏，早发现比等 OOM 好。
     * </p>
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeSessions", stateManager.sessionCount());
        data.put("idleTimeoutMinutes", 30);
        return ApiResponse.success(data);
    }

    private Map<String, Object> toItem(AgentStateTransition t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("sessionId", t.getSessionId());
        m.put("fromState", t.getFromState() == null ? null : t.getFromState().name());
        m.put("fromStateText", t.getFromState() == null ? null : t.getFromState().getDisplayName());
        m.put("toState", t.getToState() == null ? null : t.getToState().name());
        m.put("toStateText", t.getToState() == null ? null : t.getToState().getDisplayName());
        m.put("triggerType", t.getTriggerType() == null ? null : t.getTriggerType().name());
        m.put("triggerDetail", t.getTriggerDetail());
        m.put("operator", t.getOperator());
        m.put("timestamp", t.getTimestamp());
        m.put("durationMs", t.getDurationMs());
        m.put("metadata", t.getMetadata());
        return m;
    }
}
