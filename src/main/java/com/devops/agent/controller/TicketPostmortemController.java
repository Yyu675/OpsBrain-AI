package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.biz.entity.TicketActionItem;
import com.devops.agent.domain.biz.entity.TicketPostmortem;
import com.devops.agent.domain.biz.service.TicketPostmortemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 复盘归档接口（B4：闭环阶段 7）
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketPostmortemController {

    private static final Logger log = LoggerFactory.getLogger(TicketPostmortemController.class);

    private final TicketPostmortemService pmService;

    public TicketPostmortemController(TicketPostmortemService pmService) {
        this.pmService = pmService;
    }

    /**
     * 获取工单复盘详情
     */
    @GetMapping("/{id}/postmortem")
    public ApiResponse<TicketPostmortem> getPostmortem(@PathVariable String id) {
        TicketPostmortem pm = pmService.getPostmortem(id);
        return ApiResponse.success(pm);
    }

    /**
     * 保存复盘（新建或更新）
     */
    @PutMapping("/{id}/postmortem")
    public ApiResponse<TicketPostmortem> savePostmortem(@PathVariable String id,
                                                        @RequestBody PostmortemRequest req) {
        log.info("[PostmortemController] 保存复盘: id={}", id);
        TicketPostmortem pm = new TicketPostmortem();
        pm.setTicketId(id);
        pm.setTimeline(req.timeline());
        pm.setImpactScope(req.impactScope());
        pm.setImpactDuration(req.impactDuration());
        pm.setLessons(req.lessons());
        pm.setDocId(req.docId());
        return ApiResponse.success(pmService.savePostmortem(pm, req.author()));
    }

    /**
     * 生成复盘时间线草稿
     * <p>基于处置动作 + 根因 + 验证信息自动拼接，供用户编辑后保存。</p>
     */
    @PostMapping("/{id}/postmortem/draft")
    public ApiResponse<Map<String, Object>> generateDraft(@PathVariable String id) {
        String timeline = pmService.generateTimelineDraft(id);
        return ApiResponse.success(Map.of("timeline", timeline));
    }

    // ==================== 改进项 ====================

    /**
     * 查询改进项清单
     *
     * @param status  null=全部；OPEN/DOING/DONE/DROPPED
     * @param owner   null=全部
     * @param overdue true=只看已逾期且未完成
     */
    @GetMapping("/postmortem/action-items")
    public ApiResponse<List<TicketActionItem>> listActionItems(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String owner,
            @RequestParam(defaultValue = "false") boolean overdue) {
        return ApiResponse.success(pmService.findActionItems(status, owner, overdue));
    }

    /**
     * 新建改进项
     */
    @PostMapping("/{id}/postmortem/action-items")
    public ApiResponse<TicketActionItem> addActionItem(@PathVariable String id,
                                                       @RequestBody ActionItemRequest req) {
        log.info("[PostmortemController] 新建改进项: id={}", id);
        TicketActionItem item = new TicketActionItem();
        item.setTicketId(id);
        item.setPostmortemId(req.postmortemId());
        item.setContent(req.content());
        item.setOwner(req.owner());
        item.setDueDate(req.dueDate() != null ? java.time.LocalDate.parse(req.dueDate()) : null);
        return ApiResponse.success(pmService.addActionItem(item));
    }

    /**
     * 更新改进项状态
     */
    @PatchMapping("/postmortem/action-items/{itemId}")
    public ApiResponse<TicketActionItem> updateActionItem(@PathVariable long itemId,
                                                          @RequestBody ActionItemStatusRequest req) {
        log.info("[PostmortemController] 更新改进项状态: itemId={}, status={}", itemId, req.status());
        return ApiResponse.success(pmService.updateActionItemStatus(itemId, req.status()));
    }

    // ==================== Request Records ====================

    public record PostmortemRequest(
            String timeline,
            String impactScope,
            Integer impactDuration,
            String lessons,
            Long docId,
            String author
    ) {}

    public record ActionItemRequest(
            Long postmortemId,
            String content,
            String owner,
            String dueDate    // yyyy-MM-dd
    ) {}

    public record ActionItemStatusRequest(String status) {}
}
