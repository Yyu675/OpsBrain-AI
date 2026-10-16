package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.TicketAction;
import com.devops.agent.domain.biz.entity.TicketActionItem;
import com.devops.agent.domain.biz.entity.TicketPostmortem;
import com.devops.agent.domain.biz.repository.TicketPostmortemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 复盘归档服务（B4：闭环阶段 7）
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
@Service
public class TicketPostmortemService {

    private static final Logger log = LoggerFactory.getLogger(TicketPostmortemService.class);

    private static final Set<String> VALID_ITEM_STATUS = Set.of("OPEN", "DOING", "DONE", "DROPPED");

    private final TicketPostmortemRepository pmRepository;
    private final TicketService ticketService;

    public TicketPostmortemService(TicketPostmortemRepository pmRepository,
                                   TicketService ticketService) {
        this.pmRepository = pmRepository;
        this.ticketService = ticketService;
    }

    /**
     * 获取复盘（不存在返回 null）
     */
    public TicketPostmortem getPostmortem(String ticketId) {
        // 这里曾有两行无效代码：查出 actionItems 后既不返回也不使用
        // （TicketPostmortem 实体根本没有 actionItems 字段），
        // 外加一句 pm.setTimeline(pm.getTimeline()) 的自赋值。
        //
        // 后果是每次打开复盘详情都白跑一次改进项查询——
        // 结果被直接丢弃，而前端本就走独立端点 /postmortem/action-items 取它们。
        // 这类代码不报错、功能也没缺，只是安静地多打一次库，
        // 且会让后来人误以为「返回值里带着改进项」。
        return pmRepository.findByTicketId(ticketId);
    }

    /**
     * 保存复盘（新建或更新）
     */
    public TicketPostmortem savePostmortem(TicketPostmortem pm, String operator) {
        TicketPostmortem existing = pmRepository.findByTicketId(pm.getTicketId());
        if (existing == null) {
            pm.setAuthor(operator);
            Long id = pmRepository.insert(pm);
            pm.setId(id);
            log.info("📝 [PostmortemService] 复盘已创建 | ticketId={} | id={}", pm.getTicketId(), id);
        } else {
            pm.setAuthor(operator != null ? operator : existing.getAuthor());
            pmRepository.update(pm);
            pm.setId(existing.getId());
            log.info("📝 [PostmortemService] 复盘已更新 | ticketId={}", pm.getTicketId());
        }

        // 活动流留痕
        ticketService.recordActivity(pm.getTicketId(), "primary", "复盘归档",
                "复盘内容已" + (existing == null ? "创建" : "更新"), operator, false);

        return pm;
    }

    /**
     * 生成复盘时间线草稿
     * <p>
     * 从工单的处置动作 + 回复 + 活动流自动拼接时间线草稿，
     * 供用户编辑后保存——不是 AI 生成，是结构化数据按时间排列。
     * </p>
     */
    public String generateTimelineDraft(String ticketId) {
        StringBuilder sb = new StringBuilder();

        // 处置动作（含失败尝试——失败尝试同样有价值）
        List<TicketAction> actions = ticketService.listActions(ticketId);
        for (TicketAction a : actions) {
            String effLabel = a.getEffective() == null ? "" : (a.getEffective() ? "（有效）" : "（无效）");
            sb.append("### ").append(a.getCreateTime() != null ? a.getCreateTime() : "未知时间")
              .append(" · 处置动作\n")
              .append("- 类型：").append(actionTypeLabel(a.getActionType())).append("\n")
              .append("- 操作人：").append(a.getOperator()).append("\n")
              .append("- 摘要：").append(a.getSummary()).append(effLabel).append("\n");
            if (a.getDetail() != null && !a.getDetail().isBlank()) {
                sb.append("- 详情：").append(a.getDetail()).append("\n");
            }
            sb.append("\n");
        }

        if (sb.isEmpty()) {
            sb.append("（暂无处置动作记录，请手动补充时间线）\n");
        }

        // 工单的根因与验证信息
        var ticket = ticketService.getTicketWithTags(ticketId);
        if (ticket != null) {
            if (ticket.getRootCause() != null) {
                sb.append("### 根因\n").append("- 分类：").append(ticket.getRootCauseCategory()).append("\n")
                  .append("- 根因：").append(ticket.getRootCause()).append("\n\n");
            }
            if (ticket.getVerifiedAt() != null) {
                sb.append("### 验证\n")
                  .append("- 方式：").append(ticket.getVerifyMethod()).append("\n")
                  .append("- 结论：").append(ticket.getVerifyConclusion() != null ? ticket.getVerifyConclusion() : "（未填写）").append("\n")
                  .append(ticket.getVerifySkipped() != null && ticket.getVerifySkipped()
                          ? "- **已跳过验证**，理由：" + ticket.getVerifySkipReason() + "\n" : "")
                  .append("\n");
            }
        }

        return sb.toString();
    }

    // ==================== 改进项 ====================

    /**
     * 查询改进项清单
     *
     * @param status  null=全部；OPEN/DOING/DONE/DROPPED
     * @param owner   null=全部
     * @param overdue true=只看已逾期且未完成
     */
    public List<TicketActionItem> findActionItems(String status, String owner, boolean overdue) {
        return pmRepository.findActionItems(status, owner, overdue);
    }

    /** 新建改进项 */
    public TicketActionItem addActionItem(TicketActionItem item) {
        if (item.getContent() == null || item.getContent().isBlank()) {
            throw new IllegalArgumentException("改进项内容不能为空");
        }
        if (item.getContent().length() > 500) {
            throw new IllegalArgumentException("改进项内容过长（上限 500 字）");
        }
        if (item.getPostmortemId() == null) {
            throw new IllegalArgumentException("改进项必须关联复盘 ID");
        }
        item.setStatus("OPEN");
        Long id = pmRepository.insertActionItem(item);
        item.setId(id);
        log.info("📌 [PostmortemService] 改进项已创建 | postmortemId={} | id={}", item.getPostmortemId(), id);
        return item;
    }

    /**
     * 更新改进项状态
     */
    public TicketActionItem updateActionItemStatus(long id, String status) {
        String s = status != null ? status.trim().toUpperCase() : "";
        if (!VALID_ITEM_STATUS.contains(s)) {
            throw new IllegalArgumentException("非法改进项状态: " + status + "，合法值为 " + VALID_ITEM_STATUS);
        }
        int rows = pmRepository.updateActionItemStatus(id, s);
        if (rows == 0) {
            throw new IllegalStateException("改进项不存在: " + id);
        }
        log.info("🔄 [PostmortemService] 改进项状态已更新 | id={} | status={}", id, s);
        TicketActionItem item = new TicketActionItem();
        item.setId(id);
        item.setStatus(s);
        return item;
    }

    private String actionTypeLabel(String type) {
        return switch (type) {
            case "MITIGATE" -> "止损";
            case "INVESTIGATE" -> "排查";
            case "FIX" -> "修复";
            case "ROLLBACK" -> "回滚";
            case "VERIFY" -> "验证";
            default -> type;
        };
    }
}
