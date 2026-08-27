package com.devops.agent.controller;

import com.devops.agent.application.memory.AgentMemoryManager;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.memory.SessionSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆查询控制器
 * <p>
 * 对外暴露温记忆（会话摘要与关键事实），支持前端「历史会话」列表与续聊。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionMemoryController {

    private final AgentMemoryManager memoryManager;

    public SessionMemoryController(AgentMemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * 分页查询历史会话
     *
     * @param page     页码（从 1 开始）
     * @param size     每页条数
     * @param tenantId 租户标识（多租户预留）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "default") String tenantId) {

        int safePage = page;
        int safeSize = size;
        int offset = (safePage - 1) * safeSize;

        List<SessionSummary> sessions = memoryManager.listRecentSessions(tenantId, safeSize, offset);
        long total = memoryManager.countSessions(tenantId);

        List<Map<String, Object>> items = new ArrayList<>(sessions.size());
        for (SessionSummary s : sessions) {
            items.add(toBrief(s));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("sessions", items);
        data.put("total", total);
        data.put("page", safePage);
        data.put("size", safeSize);
        data.put("totalPages", (int) Math.ceil((double) total / safeSize));

        return ApiResponse.success(data);
    }

    /**
     * 查询单个会话的记忆上下文（续聊时预览）
     *
     * @param sessionId 会话 ID
     */
    @GetMapping("/{sessionId}/context")
    public ApiResponse<Map<String, Object>> getContext(@PathVariable String sessionId) {
        var ctx = memoryManager.loadContext(sessionId);

        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("recentHistory", ctx.recentHistory());
        data.put("keyFactsText", ctx.keyFactsText());
        data.put("hasHistory", ctx.hasHistory());

        return ApiResponse.success(data);
    }

    /**
     * 清除会话热记忆
     * <p>温记忆保留，仍可基于关键事实续聊。用于「重新开始但保留结论」场景。</p>
     *
     * @param sessionId 会话 ID
     */
    @DeleteMapping("/{sessionId}/hot-memory")
    public ApiResponse<String> clearHotMemory(@PathVariable String sessionId) {
        memoryManager.clearHotMemory(sessionId);
        log.info("🗑️ [SessionMemory] 已清除热记忆 | sessionId={}", sessionId);
        return ApiResponse.success("热记忆已清除，温记忆（关键事实）保留");
    }

    // ==================== 内部转换 ====================

    /**
     * 转为前端展示用的精简结构
     */
    private Map<String, Object> toBrief(SessionSummary s) {
        Map<String, Object> m = new HashMap<>();
        m.put("sessionId", s.getSessionId());
        m.put("traceId", s.getTraceId());
        m.put("summary", s.getSummary());
        m.put("turnCount", s.getTurnCount());
        m.put("totalTokens", s.getTotalTokens());
        m.put("totalCostRmb", s.getTotalCostRmb());
        m.put("finalState", s.getFinalState());
        m.put("relatedTickets", s.getRelatedTickets());
        m.put("createTime", s.getCreateTime());
        m.put("updateTime", s.getUpdateTime());

        // 关键事实摘要（供列表页展示标签）
        if (s.getKeyFacts() != null) {
            Map<String, Object> facts = new HashMap<>();
            facts.put("intent", s.getKeyFacts().getIntent());
            facts.put("confirmedFacts", s.getKeyFacts().getConfirmedFacts());
            facts.put("conclusion", s.getKeyFacts().getConclusion());
            facts.put("pendingRisks", s.getKeyFacts().getPendingRisks());
            m.put("keyFacts", facts);
        }
        return m;
    }
}