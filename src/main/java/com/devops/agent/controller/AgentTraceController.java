package com.devops.agent.controller;

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
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/traces")
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
