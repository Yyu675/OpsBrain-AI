package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单回复实体
 * <p>
 * 对应数据库表 {@code sys_ticket_reply}。
 * </p>
 * <p>
 * 此前该表仅存在于 DDL，无 Java 层实现，前端回复只写 Pinia 内存
 * 导致刷新即丢失。本实体补齐持久化链路。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
public class TicketReply implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 所属工单号 */
    private String ticketId;

    /**
     * 回复角色
     * <p>creator=提单人（前端右侧气泡）/ agent=处理人（左侧气泡）/ ai=AI 助手</p>
     */
    private String role;

    /** 回复人姓名 */
    private String author;

    /** 头像色值（前端展示用，如 #6366F1） */
    private String authorColor;

    /** 回复正文 */
    private String content;

    private LocalDateTime createTime;

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getAuthorColor() { return authorColor; }
    public void setAuthorColor(String authorColor) { this.authorColor = authorColor; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}