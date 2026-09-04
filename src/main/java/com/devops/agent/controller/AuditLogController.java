package com.devops.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.devops.agent.common.dto.ApiCode;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.infrastructure.persistence.repo.AuditLogQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计与 AI 调用日志查询接口。
 *
 * <h3>为什么需要这组接口</h3>
 * {@code sys_operation_audit}（C5 通用写操作审计）与 {@code sys_agent_call_log}
 * （AI 调用审计）两张表<b>一直在写，但前端没有任何页面能看</b>。
 * 后果是：想查「谁在什么时候改了这张工单」只能连数据库；
 * 想知道「这周 AI 花了多少钱、哪些查询最慢」只有 Dashboard 的聚合值，无法下钻。
 *
 * <h3>权限</h3>
 * 全部端点限 {@code ADMIN}（与 ApprovalController 一致）。审计日志包含操作者、IP、请求摘要与 AI 问答内容，
 * 是典型的高敏数据——普通运维能看自己处理的工单即可，不需要看全站操作记录。
 * 用 {@code @SaCheckRole} 而非仅靠前端路由 meta：前端权限只是体验优化，
 * 真正的边界必须在服务端。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@RestController
@RequestMapping("/api/v1/audit")
@SaCheckRole("ADMIN")
public class AuditLogController {

    private static final Logger log = LoggerFactory.getLogger(AuditLogController.class);

    private final AuditLogQueryRepository repository;

    public AuditLogController(AuditLogQueryRepository repository) {
        this.repository = repository;
    }

    /**
     * 操作审计分页查询。
     *
     * @param action 操作标识前缀，如 {@code ticket.} 可查全部工单相关操作
     */
    @GetMapping("/operations")
    public ApiResponse<Map<String, Object>> listOperations(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.success(repository.queryOperationAudit(
                actorId, action, targetType, success, from, to, page, size));
    }

    /**
     * AI 调用日志分页查询。
     *
     * @param minLatencyMs 最小耗时，用于筛出慢调用
     */
    @GetMapping("/ai-calls")
    public ApiResponse<Map<String, Object>> listAiCalls(
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) Boolean cached,
            @RequestParam(required = false) Integer minLatencyMs,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, Object> result = repository.queryAgentCallLog(
                modelName, operationType, cached, minLatencyMs, from, to, page, size);

        // 统计与列表用同一套筛选条件——否则「列表 20 条、统计说 1 万次」
        // 这种自相矛盾会让用户不知道该信哪个
        result.put("stats", repository.queryAgentCallStats(
                modelName, operationType, cached, minLatencyMs, from, to));

        return ApiResponse.success(result);
    }

    /**
     * 按 traceId 下钻：一次请求的完整链路。
     *
     * <p>这是本模块相对通用日志查看器的核心价值。一次 AI 建单会同时留下
     * 「AI 调用记录」与「工单创建审计」，用 traceId 关联起来才能回答
     * 「这张工单是谁、通过什么方式、基于什么问答创建的」。</p>
     */
    @GetMapping("/trace/{traceId}")
    public ApiResponse<Map<String, Object>> traceDetail(@PathVariable String traceId) {
        Map<String, Object> aiCall = repository.findAgentCallByTraceId(traceId);
        List<Map<String, Object>> operations = repository.findAuditByTraceId(traceId);

        if (aiCall == null && operations.isEmpty()) {
            log.debug("[Audit] traceId 无任何记录 | trace={}", traceId);
            return ApiResponse.error(ApiCode.NOT_FOUND, "该链路无记录，可能已过保留期");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("aiCall", aiCall);
        result.put("operations", operations);
        return ApiResponse.success(result);
    }

    /** 筛选下拉候选值，从实际数据聚合而非硬编码——避免列出库里根本没有的选项 */
    @GetMapping("/filter-options")
    public ApiResponse<Map<String, Object>> filterOptions() {
        return ApiResponse.success(repository.queryFilterOptions());
    }
}
