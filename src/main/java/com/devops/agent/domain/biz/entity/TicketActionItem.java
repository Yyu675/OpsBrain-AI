package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 复盘改进项实体（B4）
 * <p>
 * 对应数据库表 {@code sys_postmortem_action_item}。
 * </p>
 * <p>
 * <b>为何独立成表</b>：改进项若只是复盘文档里的一段文字，就无法查询
 * 「所有逾期未完成的改进项」——不可查询 = 不会被跟踪 = 等于没写。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
public class TicketActionItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long postmortemId;
    private String ticketId;

    /** 改进内容 */
    private String content;

    /** 责任人 */
    private String owner;

    /** 截止日 */
    private LocalDate dueDate;

    /**
     * 状态：OPEN 待开始 / DOING 进行中 / DONE 已完成 / DROPPED 已放弃
     */
    private String status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPostmortemId() { return postmortemId; }
    public void setPostmortemId(Long postmortemId) { this.postmortemId = postmortemId; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
