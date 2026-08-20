package com.devops.agent.domain.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批服务（方向 D，domain 层）
 *
 * <p><b>职责边界</b>：只管审批单本身（提交/查询/决策状态流转），<b>不执行动作</b>。
 * 批准后的动作执行由 application 层的 {@code ApprovalOrchestrator} 编排——
 * 保持 Single Writer 契约（6.10：业务写入只在编排层）与六层架构（domain 不依赖 application）。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@Service
public class ApprovalService {

    private final ApprovalRequestRepository repository;

    /** 审批时限（小时）。超时由定时任务标 EXPIRED——避免待审单无限积压 */
    @Value("${devops.ai.approval.timeout-hours:24}")
    private int timeoutHours;

    public ApprovalService(ApprovalRequestRepository repository) {
        this.repository = repository;
    }

    /**
     * 提交审批单
     *
     * @param payload 可重放的动作上下文 JSON——不传则批准后无从执行
     * @return 审批单 ID
     */
    public Long submit(String actionType, String toolName, String riskLevel, String summary,
                       String payload, String requester, String traceId, String sessionId) {
        ApprovalRequest req = new ApprovalRequest();
        req.setActionType(actionType);
        req.setToolName(toolName);
        req.setRiskLevel(riskLevel);
        req.setSummary(truncate(summary, 255));
        req.setPayload(payload);
        req.setRequester(requester != null && !requester.isBlank() ? requester : "AI");
        req.setTraceId(traceId);
        req.setSessionId(sessionId);
        req.setStatus(ApprovalStatus.PENDING.name());
        req.setExpiresAt(LocalDateTime.now().plusHours(Math.max(1, timeoutHours)));

        Long id = repository.insert(req);
        log.warn("🛑 [Approval] 已提交审批单 | id={} | action={} | risk={} | summary={} | traceId={}",
                id, actionType, riskLevel, req.getSummary(), traceId);
        return id;
    }

    public ApprovalRequest getById(Long id) {
        if (id == null) throw new ApprovalException("审批单 ID 不能为空");
        return repository.findById(id)
                .orElseThrow(() -> new ApprovalException("审批单不存在: " + id));
    }

    /** 待审队列（最早提交的先审） */
    public Map<String, Object> listPending(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        List<ApprovalRequest> list = repository.findPending(safeSize, (safePage - 1) * safeSize);
        int total = repository.countByStatus(ApprovalStatus.PENDING.name());
        return page(list, total, safePage, safeSize);
    }

    /** 按状态查询（空=全部），最新在前 */
    public Map<String, Object> listByStatus(String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        List<ApprovalRequest> list = repository.findByStatus(status, safeSize, (safePage - 1) * safeSize);
        int total = repository.countByStatus(status);
        return page(list, total, safePage, safeSize);
    }

    /**
     * 批准（仅状态流转，不执行动作）
     * <p>CAS 保证并发下只有一个管理员的批准生效——否则同一动作会被执行两次。</p>
     *
     * @return 批准后的审批单（含 payload，供编排层重放执行）
     */
    public ApprovalRequest approve(Long id, String approver, String reason) {
        ApprovalRequest existing = getById(id);
        if (!existing.isDecidable()) {
            throw new ApprovalException("该审批单已被处理（当前状态：" + existing.getStatus() + "），无法重复审批");
        }
        int rows = repository.markApproved(id, approver, truncate(reason, 500));
        if (rows == 0) {
            // 竞态：查询时 PENDING，更新瞬间被他人决策
            throw new ApprovalException("该审批单刚被他人处理，请刷新后查看");
        }
        log.warn("✅ [Approval] 已批准 | id={} | approver={} | summary={}", id, approver, existing.getSummary());
        return getById(id);
    }

    /**
     * 驳回（理由必填——驳回是对 AI 提议的否决，必须留下依据供后续改进）
     */
    public ApprovalRequest reject(Long id, String approver, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApprovalException("驳回必须填写理由");
        }
        ApprovalRequest existing = getById(id);
        if (!existing.isDecidable()) {
            throw new ApprovalException("该审批单已被处理（当前状态：" + existing.getStatus() + "），无法重复审批");
        }
        int rows = repository.markRejected(id, approver, truncate(reason, 500));
        if (rows == 0) {
            throw new ApprovalException("该审批单刚被他人处理，请刷新后查看");
        }
        log.warn("🚫 [Approval] 已驳回 | id={} | approver={} | reason={}", id, approver, reason);
        return getById(id);
    }

    /** 回写执行结果（由编排层在重放执行后调用） */
    public void recordExecution(Long id, boolean success, String result) {
        int rows = repository.markExecuted(id, success, truncate(result, 2000));
        if (rows == 0) {
            log.warn("⚠️ [Approval] 回写执行结果未命中（状态非 APPROVED）| id={}", id);
        } else {
            log.info("{} [Approval] 执行结果已回写 | id={} | success={}", success ? "✅" : "❌", id, success);
        }
    }

    /** 标记超时未审批的单（供定时任务调用） */
    public int expireOverdue() {
        int n = repository.markExpired(LocalDateTime.now());
        if (n > 0) {
            log.warn("⏰ [Approval] 标记超时未审批 {} 单（超过 {} 小时）", n, timeoutHours);
        }
        return n;
    }

    private Map<String, Object> page(List<ApprovalRequest> list, int total, int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", list);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        return result;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 审批业务异常（由 Controller 映射业务码） */
    public static class ApprovalException extends RuntimeException {
        public ApprovalException(String message) {
            super(message);
        }
    }
}
