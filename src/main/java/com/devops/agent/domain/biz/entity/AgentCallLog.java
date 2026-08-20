package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 调用日志实体
 * <p>
 * 对应数据库表: sys_agent_call_log
 * <p>
 * MVP-4 审计增强：新增 operation_type、affected_resources、operator_id 字段
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
public class AgentCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 追踪 ID
     */
    private String traceId;

    /**
     * 用户提问
     */
    private String userQuery;

    /**
     * Agent 回答
     */
    private String agentAnswer;

    /**
     * 使用的模型名称
     */
    private String modelName;

    /**
     * 是否命中语义缓存
     */
    private Boolean isCached;

    /**
     * 调用耗时(毫秒)
     */
    private Integer latencyMs;

    /**
     * 成本(人民币元)
     */
    private Double costRmb;

    /**
     * 引用出处(JSON数组)
     */
    private String citations;

    /**
     * 操作类型（NEW/SEARCH/CREATE_TICKET/APPROVE/EXECUTE/COMPENSATE/VOID等）
     * 用于审计分类与统计
     */
    private String operationType;

    /**
     * 影响的资源标识（JSON数组，如 ["TKT-20260808-0001", "pod-xyz"]）
     * 用于关联审计、影响范围分析
     */
    private String affectedResources;

    /**
     * 操作人（系统/用户ID/定时器）
     * 用于责任归属
     */
    private String operatorId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    // ==================== Getters & Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = userQuery;
    }

    public String getAgentAnswer() {
        return agentAnswer;
    }

    public void setAgentAnswer(String agentAnswer) {
        this.agentAnswer = agentAnswer;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Boolean getIsCached() {
        return isCached;
    }

    public void setIsCached(Boolean isCached) {
        this.isCached = isCached;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Double getCostRmb() {
        return costRmb;
    }

    public void setCostRmb(Double costRmb) {
        this.costRmb = costRmb;
    }

    public String getCitations() {
        return citations;
    }

    public void setCitations(String citations) {
        this.citations = citations;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getAffectedResources() {
        return affectedResources;
    }

    public void setAffectedResources(String affectedResources) {
        this.affectedResources = affectedResources;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
