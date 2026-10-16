package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单复盘归档实体（B4：闭环阶段 7）
 * <p>
 * 对应数据库表 {@code sys_ticket_postmortem}。一张工单一份复盘。
 * </p>
 * <p>
 * PRD §2.1 阶段 7 指出"最容易被忽视……相同故障在不同团队反复发生"。
 * 复盘的价值不在写报告本身，而在「改进项是否被跟踪到完成」——
 * 故 {@link TicketActionItem} 独立成表而非混在正文里。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
public class TicketPostmortem implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String ticketId;

    /** 时间线（可由 action + reply 自动生成草稿） */
    private String timeline;

    /** 影响范围 */
    private String impactScope;

    /** 影响时长（分钟） */
    private Integer impactDuration;

    /** 经验教训 */
    private String lessons;

    /** 已转知识库文档 ID（来源回链） */
    private Long docId;

    private String author;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getTimeline() { return timeline; }
    public void setTimeline(String timeline) { this.timeline = timeline; }

    public String getImpactScope() { return impactScope; }
    public void setImpactScope(String impactScope) { this.impactScope = impactScope; }

    public Integer getImpactDuration() { return impactDuration; }
    public void setImpactDuration(Integer impactDuration) { this.impactDuration = impactDuration; }

    public String getLessons() { return lessons; }
    public void setLessons(String lessons) { this.lessons = lessons; }

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
