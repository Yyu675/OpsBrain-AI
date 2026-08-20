package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单 AI 分析实体（策略 B）
 * <p>
 * 对应数据库表 {@code sys_ticket_ai_analysis}。
 * </p>
 * <p>
 * 背景：策略 A（6.39）把 AI 分析存进 {@code sys_ticket_reply}（{@code role='ai'}），
 * 结构化字段（原因/命令/置信度/引用）被压成纯文本。策略 B 用独立表保留结构化字段，
 * 并支持多版本（重新分析递增 version）与用户反馈（AI 准确率统计的数据来源）。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-17
 */
public class TicketAiAnalysis implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 反馈：有用 */
    public static final String FEEDBACK_HELPFUL = "HELPFUL";
    /** 反馈：没用 */
    public static final String FEEDBACK_UNHELPFUL = "UNHELPFUL";

    private Long id;

    /** 所属工单号 */
    private String ticketId;

    /** 第几次分析（同工单递增，最新为当前结论） */
    private Integer version;

    /** 原始 markdown 全文（渲染与二次解析的真相源） */
    private String content;

    /** 可能原因（从 content 解析） */
    private List<String> reasons;

    /** 排查命令（从 content 解析） */
    private List<String> commands;

    /** 引用来源（从 content 的【来源：X - Y】标记解析） */
    private List<String> citations;

    /** 置信度 0-100，null=模型未给出 */
    private Integer confidence;

    /** 本次分析成本（人民币元） */
    private BigDecimal costRmb;

    /** 用户反馈：null=未评价 / HELPFUL / UNHELPFUL */
    private String feedback;

    private LocalDateTime feedbackAt;

    private LocalDateTime createTime;

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getReasons() { return reasons; }
    public void setReasons(List<String> reasons) { this.reasons = reasons; }

    public List<String> getCommands() { return commands; }
    public void setCommands(List<String> commands) { this.commands = commands; }

    public List<String> getCitations() { return citations; }
    public void setCitations(List<String> citations) { this.citations = citations; }

    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }

    public BigDecimal getCostRmb() { return costRmb; }
    public void setCostRmb(BigDecimal costRmb) { this.costRmb = costRmb; }

    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }

    public LocalDateTime getFeedbackAt() { return feedbackAt; }
    public void setFeedbackAt(LocalDateTime feedbackAt) { this.feedbackAt = feedbackAt; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
