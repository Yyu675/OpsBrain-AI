package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 运维团队成员实体
 * <p>
 * 对应数据库表 {@code sys_team_member}，是工单负责人（assignee）的唯一来源。
 * </p>
 * <p>
 * 背景：前端此前硬编码 {@code ASSIGNEE_OPTIONS} 七人名单（张明/李四/王五/赵六/孙七/周八/待分配），
 * 而库里只有「张明」一个真实负责人。用户选人后写入 {@code sys_devops_ticket.assignee}
 * （VARCHAR(64) 自由文本），导致工单被指派给不存在的人，且筛选下拉框恒定七项不随真实数据变化。
 * 本实体补齐名录持久化，由 {@code GET /api/v1/users} 下发。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-17
 */
public class TeamMember implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 显示名，与 {@code sys_devops_ticket.assignee} 按姓名对应 */
    private String name;

    private String email;

    /** 角色：admin / operator / viewer（对齐前端 Role 词表） */
    private String role;

    /** 职位，如「高级运维工程师」 */
    private String title;

    /** 状态：ACTIVE / DISABLED（停用者不再出现在选人列表） */
    private String status;

    /** 排序权重，越小越靠前 */
    private Integer sortOrder;

    /**
     * 当前进行中的工单数（PENDING / PROCESSING）
     * <p>派生字段，由 Repository 聚合查询填充，用于选人时展示负载。</p>
     */
    private Integer activeTicketCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Integer getActiveTicketCount() { return activeTicketCount; }
    public void setActiveTicketCount(Integer activeTicketCount) { this.activeTicketCount = activeTicketCount; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
