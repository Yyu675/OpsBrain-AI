package com.devops.agent.application.runtime;

import com.devops.agent.domain.approval.ApprovalRequest;
import com.devops.agent.domain.approval.ApprovalService;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.notify.Notifier;
import com.devops.agent.domain.notify.NotifyMessage;
import com.devops.agent.domain.tools.TicketDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审批编排器（方向 D，application 层）
 *
 * <p><b>为何在 application 层而非 domain</b>：批准后要<b>重放执行业务动作</b>（如创建工单），
 * 这是编排职责。写入点保持在编排层，符合 Single Writer 契约（6.10）；
 * 同时 domain 的 {@code ApprovalService} 不依赖 application，符合六层架构。</p>
 *
 * <h3>关键设计：APPROVED 与 EXECUTED 分离</h3>
 * 先由 {@code ApprovalService.approve} 落 APPROVED（人的决策已确定，必须先固化），
 * 再重放执行，最后回写 EXECUTED / EXECUTE_FAILED。
 * 反序（先执行后标批准）会在执行成功但标记失败时留下「已建单却显示待审」的脏状态。
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@Component
public class ApprovalOrchestrator {

    /** 动作类型：创建工单（当前唯一需审批的写动作） */
    public static final String ACTION_CREATE_TICKET = "CREATE_TICKET";

    private final ApprovalService approvalService;
    private final TicketService ticketService;
    private final ObjectMapper objectMapper;
    /** 通知渠道：依赖接口而非具体厂商实现（可插拔） */
    private final Notifier notifier;

    public ApprovalOrchestrator(ApprovalService approvalService,
                                TicketService ticketService,
                                ObjectMapper objectMapper,
                                Notifier notifier) {
        this.approvalService = approvalService;
        this.ticketService = ticketService;
        this.objectMapper = objectMapper;
        this.notifier = notifier;
    }

    /**
     * 批准并重放执行
     *
     * @param id       审批单 ID
     * @param approver 审批人（来自 Sa-Token 真实身份，非前端传入）
     * @param reason   批准理由（可选）
     * @return 执行后的审批单（含 status=EXECUTED/EXECUTE_FAILED 与 executeResult）
     */
    public ApprovalRequest approveAndExecute(Long id, String approver, String reason) {
        // 步骤 1：先固化人的决策（APPROVED）。执行失败也不回退批准——
        // 「已批准」是既成事实，失败体现在 EXECUTE_FAILED（可人工重试）
        ApprovalRequest approved = approvalService.approve(id, approver, reason);

        // 步骤 2：按动作类型重放执行
        try {
            String result = replay(approved, approver);
            approvalService.recordExecution(id, true, result);
            notifyDecision(approved, approver, true, result);
        } catch (Exception e) {
            log.error("❌ [ApprovalOrchestrator] 批准后执行失败 | id={} | action={} | {}",
                    id, approved.getActionType(), e.getMessage(), e);
            approvalService.recordExecution(id, false, "执行失败: " + e.getMessage());
            notifyDecision(approved, approver, false, e.getMessage());
        }
        return approvalService.getById(id);
    }

    /** 驳回（无执行动作，仅状态流转 + 通知） */
    public ApprovalRequest reject(Long id, String approver, String reason) {
        ApprovalRequest rejected = approvalService.reject(id, approver, reason);
        try {
            String title = "🚫 审批驳回 · " + rejected.getSummary();
            String md = "### " + title + "\n\n"
                    + "- **审批单**：#" + rejected.getId() + "\n"
                    + "- **动作**：" + rejected.getActionType() + "\n"
                    + "- **审批人**：" + approver + "\n"
                    + "- **驳回理由**：" + reason + "\n";
            notifier.send(NotifyMessage.normal(title, md));
        } catch (Exception e) {
            log.warn("⚠️ [ApprovalOrchestrator] 驳回通知失败（已忽略）| id={} | {}", id, e.getMessage());
        }
        return rejected;
    }

    /**
     * 重放动作
     *
     * @return 执行结果摘要（回写 execute_result）
     * @throws Exception 执行失败，由调用方标记 EXECUTE_FAILED
     */
    private String replay(ApprovalRequest req, String approver) throws Exception {
        String actionType = req.getActionType();
        if (ACTION_CREATE_TICKET.equals(actionType)) {
            return replayCreateTicket(req, approver);
        }
        // 未知动作类型：不猜测执行，明确失败——猜测执行可能造成意外副作用
        throw new IllegalStateException("不支持的动作类型，无法重放执行: " + actionType);
    }

    /** 重放「创建工单」：payload 反序列化为 TicketDraft 后落库 */
    private String replayCreateTicket(ApprovalRequest req, String approver) throws Exception {
        if (req.getPayload() == null || req.getPayload().isBlank()) {
            throw new IllegalStateException("审批单缺少动作上下文（payload），无法重放执行");
        }
        TicketDraft draft = objectMapper.readValue(req.getPayload(), TicketDraft.class);

        // category / sla 传 null：TicketService 会按 module / priority 自动推导（单一来源）
        // creator 记审批人：AI 提议、人授权，工单归属授权者更可追溯
        DevOpsTicket ticket = ticketService.createTicket(
                draft.title(), draft.priority(), draft.module(), draft.description(),
                null, null, null, approver);

        if (ticket == null || ticket.getId() == null) {
            throw new IllegalStateException("工单创建返回空 ID");
        }
        log.warn("🎫 [ApprovalOrchestrator] 审批通过后已建单 | approvalId={} | ticketId={} | approver={}",
                req.getId(), ticket.getId(), approver);
        return "已创建工单 " + ticket.getId();
    }

    /** 决策通知（旁路，失败不影响审批主流程） */
    private void notifyDecision(ApprovalRequest req, String approver, boolean success, String detail) {
        try {
            String title = (success ? "✅ 审批通过并已执行 · " : "⚠️ 审批通过但执行失败 · ") + req.getSummary();
            String md = "### " + title + "\n\n"
                    + "- **审批单**：#" + req.getId() + "\n"
                    + "- **动作**：" + req.getActionType() + "\n"
                    + "- **风险等级**：" + req.getRiskLevel() + "\n"
                    + "- **审批人**：" + approver + "\n"
                    + "- **执行结果**：" + detail + "\n";
            // 执行失败需人工介入 → 强提醒
            notifier.send(success
                    ? NotifyMessage.normal(title, md)
                    : NotifyMessage.urgent(title, md));
        } catch (Exception e) {
            log.warn("⚠️ [ApprovalOrchestrator] 决策通知失败（已忽略）| id={} | {}", req.getId(), e.getMessage());
        }
    }
}
