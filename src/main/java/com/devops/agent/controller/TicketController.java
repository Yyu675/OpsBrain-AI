package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.common.exception.OptimisticLockException;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketAction;
import com.devops.agent.domain.biz.entity.TicketActivity;
import com.devops.agent.domain.biz.entity.TicketAiAnalysis;
import com.devops.agent.domain.biz.entity.TicketAttachment;
import com.devops.agent.domain.biz.entity.TicketReply;
import com.devops.agent.domain.biz.repository.TicketQuery;
import com.devops.agent.domain.biz.service.TicketAttachmentService;
import com.devops.agent.domain.biz.service.TicketAiAnalysisService;
import com.devops.agent.domain.biz.service.TicketService;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单管理接口
 * <p>
 * 提供工单的完整 CRUD：分页查询、详情、创建、更新、状态变更、转派、删除。
 * </p>
 * <p>
 * 对应 CLAUDE.md 6.2 决策：工单创建双入口（AI 对话 + 手动表单），
 * 手动表单走本控制器的 {@code POST /api/v1/tickets}。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-17
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private static final Logger log = LoggerFactory.getLogger(TicketController.class);

    private final TicketService ticketService;

    private final TicketAttachmentService attachmentService;

    private final TicketAiAnalysisService aiAnalysisService;

    public TicketController(TicketService ticketService,
                            TicketAttachmentService attachmentService,
                            TicketAiAnalysisService aiAnalysisService) {
        this.ticketService = ticketService;
        this.attachmentService = attachmentService;
        this.aiAnalysisService = aiAnalysisService;
    }

    /**
     * 分页查询工单列表
     *
     * @param page     页码(从1开始)
     * @param size     每页大小
     * @param priority 优先级筛选(可选)
     * @param status   状态筛选(可选)
     * @return 工单列表分页数据
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> getTickets(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "false") boolean sortAsc) {

        // 分页参数兜底：page < 1 或 size 越界会让 OFFSET 变负导致 SQL 报错
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        // 排序下沉到 SQL：前端表格本地排序只作用于当前页，
        // 「按优先级排序」会漏掉页外更高优先级的工单（同 6.15 筛选下沉的理由）
        TicketQuery query = new TicketQuery(
                keyword, priority, status, module, category, assignee,
                createdFrom, createdTo, tags, sortBy, sortAsc);

        log.info("[TicketController] 查询工单列表: page={}, size={}, keyword={}, priority={}, status={}, tags={}, sortBy={}, sortAsc={}",
                safePage, safeSize, keyword, priority, status, tags, sortBy, sortAsc);

        List<DevOpsTicket> tickets = ticketService.findTickets(safePage, safeSize, query);
        // 总数必须按同一条件统计，否则页码与实际数据矛盾
        long total = ticketService.countTickets(query);

        // 批量装填标签（一次查询，避免 N+1）
        ticketService.fillTags(tickets);

        Map<String, Object> result = new HashMap<>();
        result.put("tickets", tickets);
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("totalPages", (int) Math.ceil((double) total / safeSize));

        return ApiResponse.success(result);
    }

    /**
     * 根据工单 ID 查询工单详情
     *
     * @param id 工单 ID
     * @return 工单详情
     */
    @GetMapping("/{id}")
    public ApiResponse<DevOpsTicket> getTicketById(@PathVariable String id) {
        log.info("[TicketController] 根据 ID 查询工单: {}", id);

        // 走 Service 以自动装填标签
        DevOpsTicket ticket = ticketService.getTicketWithTags(id);
        if (ticket == null) {
            return ApiResponse.error(40004, "工单不存在");
        }

        return ApiResponse.success(ticket);
    }

    /**
     * 根据追踪 ID 查询工单
     *
     * @param traceId 追踪 ID
     * @return 工单详情
     */
    @GetMapping("/by-trace/{traceId}")
    public ApiResponse<DevOpsTicket> getByTraceId(@PathVariable String traceId) {
        log.info("[TicketController] 根据 traceId 查询工单: {}", traceId);

        DevOpsTicket ticket = ticketService.findByTraceId(traceId);
        if (ticket == null) {
            return ApiResponse.error(40004, "工单不存在");
        }

        return ApiResponse.success(ticket);
    }

    /**
     * 创建工单（手动表单入口）
     *
     * @param req 创建请求
     * @return 创建后的完整工单（含生成的工单号）
     */
    @PostMapping
    public ApiResponse<DevOpsTicket> createTicket(@RequestBody CreateTicketRequest req) {
        log.info("[TicketController] 创建工单: title={}, priority={}, module={}",
                req.title(), req.priority(), req.module());
        try {
            DevOpsTicket created = ticketService.createTicket(
                    req.title(), req.priority(), req.module(), req.description(),
                    req.assignee(), req.category(), req.sla(), req.creator(), req.tags());
            return ApiResponse.success(created);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 创建工单失败", e);
            return ApiResponse.error(50001, "创建工单失败: " + e.getMessage());
        }
    }

    /**
     * 更新工单
     * <p>仅覆盖请求体中的非空字段，避免漏传导致数据被清空。</p>
     *
     * @param id  工单号
     * @param req 更新请求
     * @return 更新后的完整工单
     */
    @PutMapping("/{id}")
    public ApiResponse<DevOpsTicket> updateTicket(@PathVariable String id,
                                                  @RequestBody UpdateTicketRequest req) {
        log.info("[TicketController] 更新工单: id={}, version={}", id, req.version());
        try {
            DevOpsTicket patch = new DevOpsTicket();
            patch.setTitle(req.title());
            patch.setDescription(req.description());
            patch.setPriority(req.priority());
            patch.setModule(req.module());
            patch.setStatus(req.status());
            patch.setAssignee(req.assignee());
            patch.setCategory(req.category());
            patch.setSla(req.sla());
            patch.setStackTrace(req.stackTrace());
            patch.setVersion(req.version());   // P1-4 并发校验，为空则退化为无锁覆盖
            patch.setTags(req.tags());         // null=不改标签，空数组=清空

            DevOpsTicket updated = ticketService.updateTicket(id, patch);
            return ApiResponse.success(updated);
        } catch (OptimisticLockException e) {
            // 版本冲突独立错误码：前端需据此提示刷新而非重试
            log.warn("[TicketController] 版本冲突 | id={} | {}", id, e.getMessage());
            return ApiResponse.error(OptimisticLockException.CODE, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 更新工单失败 | id={}", id, e);
            return ApiResponse.error(50001, "更新工单失败: " + e.getMessage());
        }
    }

    /**
     * 变更工单状态
     *
     * @param id  工单号
     * @param req 状态变更请求
     * @return 更新后的工单
     */
    @PatchMapping("/{id}/status")
    public ApiResponse<DevOpsTicket> updateStatus(@PathVariable String id,
                                                  @RequestBody StatusRequest req) {
        log.info("[TicketController] 变更工单状态: id={}, status={}", id, req.status());
        try {
            DevOpsTicket updated = ticketService.updateStatus(id, req.status());
            return ApiResponse.success(updated);
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 变更状态失败 | id={}", id, e);
            return ApiResponse.error(50001, "变更状态失败: " + e.getMessage());
        }
    }

    // ==================== B1 首响 / 派单 / SLA 风险 ====================

    /**
     * 确认接单（显式首响）
     * <p>对应告警侧 ACKNOWLEDGED 语义。可选同时认领给指定负责人。</p>
     */
    @PostMapping("/{id}/acknowledge")
    public ApiResponse<DevOpsTicket> acknowledge(@PathVariable String id,
                                                 @RequestBody(required = false) AcknowledgeRequest req) {
        String responder = req != null ? req.responder() : null;
        String assignee = req != null ? req.assignee() : null;
        log.info("[TicketController] 确认接单: id={}, responder={}, assignee={}", id, responder, assignee);
        try {
            return ApiResponse.success(ticketService.acknowledgeTicket(id, responder, assignee));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 确认接单失败 | id={}", id, e);
            return ApiResponse.error(50001, "确认接单失败");
        }
    }

    /**
     * 升级工单
     * <p>L1 阶段只记录 + 留痕，不自动改优先级或换负责人（属 L3 审批范畴）。</p>
     */
    @PostMapping("/{id}/escalate")
    public ApiResponse<DevOpsTicket> escalate(@PathVariable String id,
                                              @RequestBody EscalateRequest req) {
        log.info("[TicketController] 升级工单: id={}, reason={}", id, req == null ? null : req.reason());
        try {
            String reason = req != null ? req.reason() : null;
            String operator = req != null ? req.operator() : null;
            return ApiResponse.success(ticketService.escalateTicket(id, reason, operator));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 升级失败 | id={}", id, e);
            return ApiResponse.error(50001, "升级失败");
        }
    }

    /**
     * SLA 风险清单（首响/解决即将超时或已超时）
     *
     * @param withinMinutes 前瞻窗口（分钟），默认 30；传 0 只看已超时
     * @param size          上限，默认 50，最大 200
     */
    @GetMapping("/sla/at-risk")
    public ApiResponse<Map<String, Object>> slaAtRisk(
            @RequestParam(defaultValue = "30") int withinMinutes,
            @RequestParam(defaultValue = "50") int size) {
        try {
            List<DevOpsTicket> list = ticketService.findSlaAtRisk(withinMinutes, size);
            Map<String, Object> data = new HashMap<>();
            data.put("total", list.size());
            data.put("withinMinutes", Math.max(0, withinMinutes));
            data.put("tickets", list);
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.error("[TicketController] 查询 SLA 风险清单失败", e);
            return ApiResponse.error(50001, "查询 SLA 风险清单失败");
        }
    }

    /**
     * 首响统计（MTTA）
     */
    @GetMapping("/sla/first-response-stats")
    public ApiResponse<Map<String, Object>> firstResponseStats() {
        try {
            return ApiResponse.success(ticketService.getFirstResponseStats());
        } catch (Exception e) {
            log.error("[TicketController] 查询首响统计失败", e);
            return ApiResponse.error(50001, "查询首响统计失败");
        }
    }

    // ==================== B2 现场处置 ====================

    /**
     * 记录处置动作
     * <p>effective 允许为 false——失败尝试同样有价值（PRD §2.1 排查占 40% 且依赖经验）。</p>
     */
    @PostMapping("/{id}/actions")
    public ApiResponse<Map<String, Object>> addAction(@PathVariable String id,
                                                     @RequestBody ActionRequest req) {
        log.info("[TicketController] 记录处置动作: id={}, type={}", id, req.actionType());
        try {
            var action = ticketService.addAction(id, req.actionType(), req.summary(),
                    req.detail(), req.operator(), req.effective());
            return ApiResponse.success(Map.of("id", action.getId(), "action", action));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 记录处置动作失败 | id={}", id, e);
            return ApiResponse.error(50001, "记录处置动作失败");
        }
    }

    /**
     * 查询工单的处置动作列表
     */
    @GetMapping("/{id}/actions")
    public ApiResponse<List<com.devops.agent.domain.biz.entity.TicketAction>> listActions(
            @PathVariable String id) {
        try {
            return ApiResponse.success(ticketService.listActions(id));
        } catch (Exception e) {
            log.error("[TicketController] 查询处置动作失败 | id={}", id, e);
            return ApiResponse.error(50001, "查询处置动作失败");
        }
    }

    /**
     * 切换处置阶段
     * <p>阶段可跳跃与回退——真实运维不是线性的，强制线性会让用户绕过系统。</p>
     */
    @PatchMapping("/{id}/stage")
    public ApiResponse<DevOpsTicket> updateStage(@PathVariable String id,
                                                  @RequestBody StageRequest req) {
        log.info("[TicketController] 切换处置阶段: id={}, stage={}", id, req.stage());
        try {
            return ApiResponse.success(ticketService.updateStage(id, req.stage(), req.operator()));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 切换处置阶段失败 | id={}", id, e);
            return ApiResponse.error(50001, "切换处置阶段失败");
        }
    }

    /**
     * 标记已止损（业务恢复）
     * <p>不等同于「已解决」——业务虽恢复，根因可能尚未定位。</p>
     */
    @PostMapping("/{id}/mitigate")
    public ApiResponse<DevOpsTicket> markMitigated(@PathVariable String id,
                                                    @RequestBody(required = false) OperatorRequest req) {
        log.info("[TicketController] 标记止损: id={}", id);
        try {
            String operator = req != null ? req.operator() : null;
            return ApiResponse.success(ticketService.markMitigated(id, operator));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 标记止损失败 | id={}", id, e);
            return ApiResponse.error(50001, "标记止损失败");
        }
    }

    // ==================== B3 根因分析 + 修复验证 ====================

    /**
     * 确认根因
     * <p>人工确认的根因，≠ AI 建议——AI 建议是参考材料。</p>
     */
    @PutMapping("/{id}/root-cause")
    public ApiResponse<DevOpsTicket> confirmRootCause(@PathVariable String id,
                                                      @RequestBody RootCauseRequest req) {
        log.info("[TicketController] 确认根因: id={}, category={}", id, req.category());
        try {
            return ApiResponse.success(ticketService.confirmRootCause(
                    id, req.rootCause(), req.category(), req.operator()));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 确认根因失败 | id={}", id, e);
            return ApiResponse.error(50001, "确认根因失败");
        }
    }

    /**
     * 根因分类聚合统计
     */
    @GetMapping("/root-cause/stats")
    public ApiResponse<Map<String, Object>> rootCauseStats() {
        try {
            return ApiResponse.success(ticketService.getRootCauseStats());
        } catch (Exception e) {
            log.error("[TicketController] 根因统计失败", e);
            return ApiResponse.error(50001, "根因统计失败");
        }
    }

    /**
     * 提交修复验证
     * <p>D3：必填但允许带理由跳过。通过验证则同时转 RESOLVED。</p>
     */
    @PostMapping("/{id}/verify")
    public ApiResponse<DevOpsTicket> submitVerification(@PathVariable String id,
                                                         @RequestBody VerifyRequest req) {
        log.info("[TicketController] 提交验证: id={}, method={}", id, req.method());
        try {
            return ApiResponse.success(ticketService.submitVerification(
                    id, req.method(), req.conclusion(), req.verifier()));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 提交验证失败 | id={}", id, e);
            return ApiResponse.error(50001, "提交验证失败");
        }
    }

    /**
     * 跳过验证
     * <p>强制填写理由（同 6.21 purge 的 complianceReason 做法）。
     * 跳过的工单仍转 RESOLVED，但 verify_skipped=true，MTTR 统计时排除。</p>
     */
    @PostMapping("/{id}/verify/skip")
    public ApiResponse<DevOpsTicket> skipVerification(@PathVariable String id,
                                                     @RequestBody VerifySkipRequest req) {
        log.info("[TicketController] 跳过验证: id={}", id);
        try {
            return ApiResponse.success(ticketService.skipVerification(
                    id, req.reason(), req.operator()));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 跳过验证失败 | id={}", id, e);
            return ApiResponse.error(50001, "跳过验证失败");
        }
    }

    /**
     * 闭环度量：MTTA / MTTM / MTTR + 各阶段完成率 + 跳过验证率
     */
    @GetMapping("/metrics/closure")
    public ApiResponse<Map<String, Object>> closureMetrics() {
        try {
            return ApiResponse.success(ticketService.getClosureMetrics());
        } catch (Exception e) {
            log.error("[TicketController] 闭环度量查询失败", e);
            return ApiResponse.error(50001, "闭环度量查询失败");
        }
    }

    /**
     * 转派工单
     *
     * @param id  工单号
     * @param req 转派请求
     * @return 更新后的工单
     */
    @PatchMapping("/{id}/assignee")
    public ApiResponse<DevOpsTicket> transferTicket(@PathVariable String id,
                                                    @RequestBody AssigneeRequest req) {
        log.info("[TicketController] 转派工单: id={}, assignee={}", id, req.assignee());
        try {
            DevOpsTicket updated = ticketService.transferTicket(id, req.assignee());
            return ApiResponse.success(updated);
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 转派失败 | id={}", id, e);
            return ApiResponse.error(50001, "转派失败: " + e.getMessage());
        }
    }

    /**
     * 删除工单
     * <p>
     * ⚠️ 物理删除。返回被删除的工单供前端实现「撤销」（重新创建）。
     * 若需保留审计痕迹，应改用作废接口。
     * </p>
     *
     * @param id 工单号
     * @return 被删除的工单快照
     */
    @DeleteMapping("/{id}")
    public ApiResponse<DevOpsTicket> deleteTicket(@PathVariable String id) {
        log.warn("[TicketController] 删除工单: id={}", id);
        try {
            // 先清附件（含 MinIO 对象），再删工单主体。
            // 顺序原因：删完工单后 attachmentService 内部记活动流会
            // 因工单不存在而失败，且已无从查证附件归属
            int attachments = attachmentService.deleteAllByTicketId(id);
            if (attachments > 0) {
                log.info("[TicketController] 已级联清理附件 | id={} | count={}", id, attachments);
            }

            // 级联清理 AI 分析（表无外键约束，需应用层保证，否则积累孤儿数据）
            int analyses = aiAnalysisService.deleteByTicketId(id);
            if (analyses > 0) {
                log.info("[TicketController] 已级联清理 AI 分析 | id={} | count={}", id, analyses);
            }

            DevOpsTicket deleted = ticketService.deleteTicket(id);
            return ApiResponse.success(deleted);
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 删除工单失败 | id={}", id, e);
            return ApiResponse.error(50001, "删除工单失败: " + e.getMessage());
        }
    }

    /**
     * 作废工单（保留审计痕迹，推荐替代删除）
     *
     * @param id  工单号
     * @param req 作废请求（含原因）
     * @return 作废结果描述
     */
    @PostMapping("/{id}/void")
    public ApiResponse<Map<String, Object>> voidTicket(@PathVariable String id,
                                                       @RequestBody(required = false) VoidRequest req) {
        String reason = (req != null && req.reason() != null && !req.reason().isBlank())
                ? req.reason() : "人工作废";
        log.warn("[TicketController] 作废工单: id={}, reason={}", id, reason);
        try {
            String message = ticketService.voidTicket(id, reason);
            Map<String, Object> data = new HashMap<>();
            data.put("ticketId", id);
            data.put("message", message);
            return ApiResponse.success(data);
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 作废工单失败 | id={}", id, e);
            return ApiResponse.error(50001, "作废工单失败: " + e.getMessage());
        }
    }

    /**
     * 工单统计（供列表页 KPI 卡片）
     * <p>
     * 含「今日新增」，前端此前该字段缺失只能显示 0。
     * </p>
     *
     * @return 各状态数量 + 今日新增 + 总数
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        return ApiResponse.success(ticketService.getTicketStats());
    }

    // ==================== 请求体 ====================

    /**
     * 创建工单请求
     *
     * @param title       标题（必填）
     * @param priority    优先级 HIGH/MEDIUM/LOW，空则 MEDIUM
     * @param module      故障模块，空则 OTHER
     * @param description 问题描述（必填）
     * @param assignee    负责人，空则「待分配」
     * @param category    分类，空则按 module 推导
     * @param sla         SLA，空则按优先级推导
     * @param creator     创建人，空则 devops-admin
     */
    public record CreateTicketRequest(
            String title, String priority, String module, String description,
            String assignee, String category, String sla, String creator,
            /** 标签列表，可为 null。此前用户输入的标签在提交时被丢弃 */
            List<String> tags) {
    }

    // ==================== 附件 ====================

    /**
     * 上传附件
     * <p>
     * 安全控制：扩展名白名单、双扩展名绕过检测、路径穿越拒绝、
     * 大小与数量上限、内容哈希查重。详见 {@code AttachmentSecurityGuard}。
     * </p>
     */
    @PostMapping("/{id}/attachments")
    public ApiResponse<TicketAttachment> uploadAttachment(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String uploader) {

        log.info("[TicketController] 上传附件 | id={} | name={} | size={}",
                id, file != null ? file.getOriginalFilename() : null,
                file != null ? file.getSize() : 0);
        try {
            return ApiResponse.success(attachmentService.upload(id, file, uploader));
        } catch (IllegalArgumentException e) {
            // 校验类失败：文件类型/大小/文件名非法
            return ApiResponse.error(40001, e.getMessage());
        } catch (IllegalStateException e) {
            // 状态类失败：工单不存在/数量超限/重复/存储不可用
            return ApiResponse.error(40004, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 上传附件失败 | id={}", id, e);
            return ApiResponse.error(50001, "上传附件失败: " + e.getMessage());
        }
    }

    /**
     * 查询工单附件列表
     */
    @GetMapping("/{id}/attachments")
    public ApiResponse<List<TicketAttachment>> listAttachments(@PathVariable String id) {
        try {
            return ApiResponse.success(attachmentService.list(id));
        } catch (Exception e) {
            log.error("[TicketController] 查询附件失败 | id={}", id, e);
            return ApiResponse.error(50001, "查询附件失败: " + e.getMessage());
        }
    }

    /**
     * 获取附件下载链接（预签名 URL）
     * <p>
     * 返回 URL 而非文件流：文件不经应用进程，不占用带宽与线程。
     * URL 有效期默认 5 分钟，桶为 private，链接泄露时间窗有限。
     * </p>
     */
    @GetMapping("/attachments/{attachmentId}/download-url")
    public ApiResponse<Map<String, Object>> attachmentDownloadUrl(@PathVariable Long attachmentId) {
        try {
            String url = attachmentService.presignDownloadUrl(attachmentId);
            Map<String, Object> data = new HashMap<>();
            data.put("url", url);
            data.put("expiresInSeconds", 300);
            return ApiResponse.success(data);
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 生成下载链接失败 | id={}", attachmentId, e);
            return ApiResponse.error(50001, "生成下载链接失败: " + e.getMessage());
        }
    }

    /**
     * 删除附件
     */
    @DeleteMapping("/attachments/{attachmentId}")
    public ApiResponse<TicketAttachment> deleteAttachment(@PathVariable Long attachmentId) {
        log.info("[TicketController] 删除附件 | id={}", attachmentId);
        try {
            return ApiResponse.success(attachmentService.delete(attachmentId));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 删除附件失败 | id={}", attachmentId, e);
            return ApiResponse.error(50001, "删除附件失败: " + e.getMessage());
        }
    }

    // ==================== 标签 ====================

    /**
     * 替换工单标签（全量）
     * <p>传空数组表示清空全部标签。</p>
     */
    @PutMapping("/{id}/tags")
    public ApiResponse<List<String>> replaceTags(@PathVariable String id,
                                                 @RequestBody TagsRequest req) {
        log.info("[TicketController] 替换标签 | id={} | tags={}", id, req.tags());
        try {
            return ApiResponse.success(ticketService.replaceTags(id, req.tags()));
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 替换标签失败 | id={}", id, e);
            return ApiResponse.error(50001, "替换标签失败: " + e.getMessage());
        }
    }

    /**
     * 查询热门标签
     * <p>供前端输入时建议历史标签，减少同义异形标签。</p>
     *
     * @param limit 数量上限，默认 20
     */
    @GetMapping("/tags/hot")
    public ApiResponse<Map<String, Object>> hotTags(
            @RequestParam(defaultValue = "20") int limit) {
        try {
            var hot = ticketService.getHotTags(limit);
            Map<String, Object> data = new HashMap<>();
            // 保序输出：按热度降序的标签名列表
            data.put("tags", new ArrayList<>(hot.keySet()));
            data.put("counts", hot);
            return ApiResponse.success(data);
        } catch (Exception e) {
            log.error("[TicketController] 查询热门标签失败", e);
            return ApiResponse.error(50001, "查询热门标签失败: " + e.getMessage());
        }
    }

    /**
     * 标签请求体
     *
     * @param tags 标签列表，空数组表示清空
     */
    public record TagsRequest(List<String> tags) {
    }

    // ==================== 回复与活动流 ====================

    /**
     * 查询工单回复（时间正序）
     */
    @GetMapping("/{id}/replies")
    public ApiResponse<List<TicketReply>> listReplies(@PathVariable String id) {
        try {
            return ApiResponse.success(ticketService.listReplies(id));
        } catch (Exception e) {
            log.error("[TicketController] 查询回复失败 | id={}", id, e);
            return ApiResponse.error(50001, "查询回复失败: " + e.getMessage());
        }
    }

    /**
     * 追加工单回复
     */
    @PostMapping("/{id}/replies")
    public ApiResponse<TicketReply> addReply(@PathVariable String id,
                                             @RequestBody AddReplyRequest req) {
        log.info("[TicketController] 新增回复 | id={} | role={} | author={}", id, req.role(), req.author());
        try {
            TicketReply reply = ticketService.addReply(
                    id, req.role(), req.author(), req.authorColor(), req.content());
            return ApiResponse.success(reply);
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 新增回复失败 | id={}", id, e);
            return ApiResponse.error(50001, "新增回复失败: " + e.getMessage());
        }
    }

    /**
     * 查询工单活动流（时间倒序，最新在前）
     */
    @GetMapping("/{id}/activities")
    public ApiResponse<List<TicketActivity>> listActivities(@PathVariable String id) {
        try {
            return ApiResponse.success(ticketService.listActivities(id));
        } catch (Exception e) {
            log.error("[TicketController] 查询活动流失败 | id={}", id, e);
            return ApiResponse.error(50001, "查询活动流失败: " + e.getMessage());
        }
    }

    // ==================== AI 分析（策略 B：结构化 + 多版本 + 反馈） ====================

    /**
     * 保存一次 AI 分析
     * <p>
     * 结构化字段由前端解析后传入（前端已有 parseStructuredAnalysis，content 是真相源），
     * 后端不重复实现解析器。version 由服务端自增。
     * </p>
     */
    @PostMapping("/{id}/ai-analysis")
    public ApiResponse<TicketAiAnalysis> saveAiAnalysis(@PathVariable String id,
                                                        @RequestBody SaveAnalysisRequest req) {
        try {
            TicketAiAnalysis saved = aiAnalysisService.save(
                    id, req.content(), req.reasons(), req.commands(),
                    req.citations(), req.confidence(), req.costRmb());
            return ApiResponse.success(saved);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[TicketController] 保存 AI 分析失败 | id={}", id, e);
            return ApiResponse.error(50001, "保存 AI 分析失败: " + e.getMessage());
        }
    }

    /**
     * 查询工单最新 AI 分析（当前结论）
     * <p>data 为 null 表示尚无分析，前端据此决定是否触发实时分析。</p>
     */
    @GetMapping("/{id}/ai-analysis/latest")
    public ApiResponse<TicketAiAnalysis> latestAiAnalysis(@PathVariable String id) {
        try {
            return ApiResponse.success(aiAnalysisService.getLatest(id));
        } catch (Exception e) {
            log.error("[TicketController] 查询最新 AI 分析失败 | id={}", id, e);
            return ApiResponse.error(50001, "查询 AI 分析失败: " + e.getMessage());
        }
    }

    /**
     * 查询工单全部 AI 分析版本（历史对比）
     */
    @GetMapping("/{id}/ai-analysis/versions")
    public ApiResponse<List<TicketAiAnalysis>> aiAnalysisVersions(@PathVariable String id) {
        try {
            return ApiResponse.success(aiAnalysisService.listVersions(id));
        } catch (Exception e) {
            log.error("[TicketController] 查询 AI 分析版本失败 | id={}", id, e);
            return ApiResponse.error(50001, "查询 AI 分析版本失败: " + e.getMessage());
        }
    }

    /**
     * 记录 AI 分析反馈（有用 / 没用）——AI 准确率统计数据来源
     */
    @PostMapping("/ai-analysis/{analysisId}/feedback")
    public ApiResponse<Map<String, Object>> aiAnalysisFeedback(@PathVariable Long analysisId,
                                                               @RequestBody FeedbackRequest req) {
        try {
            boolean ok = aiAnalysisService.recordFeedback(analysisId, req.helpful());
            if (!ok) {
                return ApiResponse.error(40004, "分析不存在: " + analysisId);
            }
            return ApiResponse.success(Map.of("analysisId", analysisId, "helpful", req.helpful()));
        } catch (Exception e) {
            log.error("[TicketController] 记录 AI 分析反馈失败 | analysisId={}", analysisId, e);
            return ApiResponse.error(50001, "记录反馈失败: " + e.getMessage());
        }
    }

    /**
     * AI 分析准确率统计（供数据概览展示）
     */
    @GetMapping("/ai-analysis/stats")
    public ApiResponse<Map<String, Object>> aiAnalysisStats() {
        try {
            return ApiResponse.success(aiAnalysisService.accuracyStats());
        } catch (Exception e) {
            log.error("[TicketController] 查询 AI 分析统计失败", e);
            return ApiResponse.error(50001, "查询统计失败: " + e.getMessage());
        }
    }

    /**
     * 保存 AI 分析请求
     *
     * @param content    原始 markdown 全文（真相源）
     * @param reasons    可能原因（前端解析）
     * @param commands   排查命令（前端解析）
     * @param citations  引用来源（前端解析）
     * @param confidence 置信度 0-100，可空
     * @param costRmb    本次成本
     */
    public record SaveAnalysisRequest(String content, List<String> reasons, List<String> commands,
                                      List<String> citations, Integer confidence,
                                      java.math.BigDecimal costRmb) {
    }

    /**
     * 反馈请求
     *
     * @param helpful true=有用 / false=没用
     */
    public record FeedbackRequest(boolean helpful) {
    }

    /**
     * 新增回复请求
     *
     * @param role        角色 creator/agent/ai，非法值降级为 agent
     * @param author      回复人
     * @param authorColor 头像色值，可为空
     * @param content     回复正文，1~5000 字
     */
    public record AddReplyRequest(String role, String author, String authorColor, String content) {
    }

    /**
     * 更新工单请求（null 字段不更新）
     *
     * @param version 乐观锁版本号（P1-4）。传入读取时的 version 启用并发校验；
     *                为空则退化为无锁覆盖，兼容旧客户端但丢失并发保护
     */
    public record UpdateTicketRequest(
            String title, String description, String priority, String module,
            String status, String assignee, String category, String sla, String stackTrace,
            Integer version,
            /** 标签列表。null=保持原样，空数组=清空全部标签 */
            List<String> tags) {
    }

    /** 状态变更请求 */
    public record StatusRequest(String status) {
    }

    /** 转派请求 */
    public record AssigneeRequest(String assignee) {
    }

    /**
     * 确认接单请求（B1）
     *
     * @param responder 首响人
     * @param assignee  可选：确认的同时认领给此人
     */
    public record AcknowledgeRequest(String responder, String assignee) {
    }

    /**
     * 升级请求（B1）
     *
     * @param reason   升级原因，必填——无理由的升级无法追溯，也无法据此改进流程
     * @param operator 操作人
     */
    public record EscalateRequest(String reason, String operator) {
    }

    /** 操作人请求（B2 止损标记等简化场景） */
    public record OperatorRequest(String operator) {
    }

    /** 作废请求 */
    public record VoidRequest(String reason) {
    }

    /** 处置动作请求（B2） */
    public record ActionRequest(
            String actionType,   // MITIGATE/INVESTIGATE/FIX/ROLLBACK/VERIFY
            String summary,      // 一句话：做了什么
            String detail,       // 命令/配置/日志片段（可选）
            String operator,
            Boolean effective    // null=未判定 / true=有效 / false=无效——失败尝试同样记录
    ) {
    }

    /** 处置阶段切换请求（B2） */
    public record StageRequest(String stage, String operator) {
    }

    /** 根因确认请求（B3） */
    public record RootCauseRequest(String rootCause, String category, String operator) {
    }

    /** 修复验证请求（B3） */
    public record VerifyRequest(String method, String conclusion, String verifier) {
    }

    /** 跳过验证请求（B3，强制 reason） */
    public record VerifySkipRequest(String reason, String operator) {
    }
}
