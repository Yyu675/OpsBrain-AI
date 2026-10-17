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
    public ApiResponse<com.devops.agent.controller.dto.SessionListResponse> listSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "default") String tenantId) {

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        int offset = (safePage - 1) * safeSize;

        List<SessionSummary> sessions = memoryManager.listRecentSessions(tenantId, safeSize, offset);
        long total = memoryManager.countSessions(tenantId);

        List<com.devops.agent.controller.dto.SessionSummaryItem> items = new ArrayList<>(sessions.size());
        for (SessionSummary s : sessions) {
            items.add(toSessionItem(s));
        }

        int totalPages = (int) Math.ceil((double) total / safeSize);
        return ApiResponse.success(new com.devops.agent.controller.dto.SessionListResponse(items, total, safePage, safeSize, totalPages));
    }

    /**
     * 查询单个会话的记忆上下文（续聊时预览）
     *
     * @param sessionId 会话 ID
     */
    @GetMapping("/{sessionId}/context")
    public ApiResponse<com.devops.agent.controller.dto.SessionContextResponse> getContext(@PathVariable String sessionId) {
        var ctx = memoryManager.loadContext(sessionId);
        return ApiResponse.success(new com.devops.agent.controller.dto.SessionContextResponse(
                sessionId,
                ctx.recentHistory() != null ? ctx.recentHistory() : List.of(),
                ctx.keyFactsText(),
                ctx.hasHistory()));
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

    private com.devops.agent.controller.dto.SessionSummaryItem toSessionItem(SessionSummary s) {
        com.devops.agent.controller.dto.SessionKeyFactsDto facts = null;
        if (s.getKeyFacts() != null) {
            facts = new com.devops.agent.controller.dto.SessionKeyFactsDto(
                    s.getKeyFacts().getIntent(),
                    s.getKeyFacts().getConfirmedFacts(),
                    s.getKeyFacts().getConclusion(),
                    s.getKeyFacts().getPendingRisks());
        }
        return new com.devops.agent.controller.dto.SessionSummaryItem(
                s.getSessionId(),
                s.getTraceId(),
                s.getSummary(),
                s.getTurnCount(),
                s.getTotalTokens(),
                s.getTotalCostRmb(),
                s.getFinalState(),
                s.getRelatedTickets(),
                facts,
                s.getCreateTime(),
                s.getUpdateTime());
    }
}