package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单处置动作实体（B2：闭环阶段 4 现场处置留痕）
 * <p>
 * 对应数据库表 {@code sys_ticket_action}。
 * </p>
 * <p>
 * <b>为何 {@code effective} 允许为 false</b>：PRD §2.1 指出排查定位占 40% 耗时
 * 且严重依赖经验——「我试过重启，没用」这种失败尝试恰恰是最有价值的知识，
 * 能避免后人重走弯路。只记成功动作等于丢弃大部分经验。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
public class TicketAction implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String ticketId;

    /** 动作类型：MITIGATE止损 / INVESTIGATE排查 / FIX修复 / ROLLBACK回滚 / VERIFY验证 */
    private String actionType;

    /** 一句话：做了什么 */
    private String summary;

    /** 命令/配置/日志片段 */
    private String detail;

    /** 操作人 */
    private String operator;

    /** 是否有效（NULL=未判定 / true=有效 / false=无效——失败尝试同样记录） */
    private Boolean effective;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createTime;

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public Boolean getEffective() { return effective; }
    public void setEffective(Boolean effective) { this.effective = effective; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
