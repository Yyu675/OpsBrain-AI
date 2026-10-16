package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单活动流实体
 * <p>
 * 对应数据库表 {@code sys_ticket_activity}。记录工单全生命周期的
 * 操作轨迹：创建、状态变更、转派、优先级升级、AI 分类等。
 * </p>
 * <p>
 * 与 {@code sys_agent_call_log} 的区别：后者记录 AI 调用审计（技术视角），
 * 本表记录工单业务操作轨迹（用户视角），供工单详情页时间线展示。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
public class TicketActivity implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 所属工单号 */
    private String ticketId;

    /**
     * 圆点颜色语义
     * <p>success=成功 / primary=常规 / warning=告警 / gray=中性</p>
     */
    private String color;

    /** 操作摘要，如「状态变更」「工单转派」 */
    private String text;

    /** 操作详情，如「待处理 → 处理中」 */
    private String detail;

    /** 操作人，系统操作填「系统自动」 */
    private String userName;

    /** 是否高亮（前端用主色渲染 detail） */
    private Boolean highlight;

    private LocalDateTime createTime;

    // ==================== 静态工厂 ====================

    /**
     * 构造活动记录
     *
     * @param ticketId  工单号
     * @param color     颜色语义
     * @param text      操作摘要
     * @param detail    操作详情，可为 null
     * @param userName  操作人
     * @param highlight 是否高亮
     */
    public static TicketActivity of(String ticketId, String color, String text,
                                    String detail, String userName, boolean highlight) {
        TicketActivity a = new TicketActivity();
        a.ticketId = ticketId;
        a.color = color;
        a.text = text;
        a.detail = detail;
        a.userName = userName;
        a.highlight = highlight;
        a.createTime = LocalDateTime.now();
        return a;
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public Boolean getHighlight() { return highlight; }
    public void setHighlight(Boolean highlight) { this.highlight = highlight; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}