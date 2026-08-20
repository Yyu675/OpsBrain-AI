package com.devops.agent.domain.memory;

import java.time.LocalDateTime;

/**
 * 会话摘要实体（温记忆）
 * <p>
 * 对应表 {@code sys_agent_session_summary}。
 * 三层记忆中的温记忆层：可持久化、可审计、可续聊。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
public class SessionSummary {

    private Long id;

    /** 会话 ID（多轮对话归属，唯一） */
    private String sessionId;

    /** 末次请求的 traceId */
    private String traceId;

    /** 租户标识（多租户预留，P1-9） */
    private String tenantId = "default";

    /** 会话摘要（自然语言） */
    private String summary;

    /** 关键事实结构化（JSONB 存储） */
    private KeyFacts keyFacts;

    /** 对话轮次 */
    private Integer turnCount = 0;

    /** 累计 Token */
    private Integer totalTokens = 0;

    /** 累计成本（元） */
    private Double totalCostRmb = 0.0;

    /** 状态机终态：SUCCESS/FAILED/MANUAL_ESCALATED */
    private String finalState;

    /** 关联工单（JSON 数组字符串） */
    private String relatedTickets;

    /** 是否已归档到冷存储 */
    private Boolean archived = false;

    /** 冷存储路径 */
    private String archivePath;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public KeyFacts getKeyFacts() { return keyFacts; }
    public void setKeyFacts(KeyFacts keyFacts) { this.keyFacts = keyFacts; }

    public Integer getTurnCount() { return turnCount; }
    public void setTurnCount(Integer turnCount) { this.turnCount = turnCount; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public Double getTotalCostRmb() { return totalCostRmb; }
    public void setTotalCostRmb(Double totalCostRmb) { this.totalCostRmb = totalCostRmb; }

    public String getFinalState() { return finalState; }
    public void setFinalState(String finalState) { this.finalState = finalState; }

    public String getRelatedTickets() { return relatedTickets; }
    public void setRelatedTickets(String relatedTickets) { this.relatedTickets = relatedTickets; }

    public Boolean getArchived() { return archived; }
    public void setArchived(Boolean archived) { this.archived = archived; }

    public String getArchivePath() { return archivePath; }
    public void setArchivePath(String archivePath) { this.archivePath = archivePath; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}