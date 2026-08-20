package com.devops.agent.domain.tools;

import java.time.LocalDateTime;

/**
 * 工具执行记录（Saga 步骤）
 * <p>
 * 对应表 {@code sys_agent_tool_execution}。每次工具调用产生一条记录，
 * 同一次 Agent 执行内的所有工具共享 {@code sagaId}，补偿时按
 * {@code stepSeq} 逆序执行。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
public class ToolExecutionRecord {

    private Long id;

    // ==================== 关联标识 ====================

    private String traceId;
    private String sessionId;

    /** Saga 事务 ID：同一次 Agent 执行内共享 */
    private String sagaId;

    /** 步骤序号：从 1 递增，补偿时逆序 */
    private Integer stepSeq;

    // ==================== 工具信息 ====================

    private String toolName;
    private ToolRiskLevel riskLevel;

    /** 入参快照（JSON），补偿与回放用 */
    private String toolArgs;

    /** 执行结果摘要 */
    private String toolResult;

    // ==================== 状态 ====================

    private ToolExecutionState state;
    private ToolFailureType failureType;
    private String errorMessage;

    // ==================== 补偿信息 ====================

    /** 是否可补偿（声明了 compensationAction） */
    private Boolean compensable = false;

    /** 补偿方法名 */
    private String compensationAction;

    /** 业务标识（如工单号），补偿动作的入参 */
    private String businessKey;

    private LocalDateTime compensatedAt;
    private String compensationError;

    // ==================== 度量 ====================

    private Integer attemptCount = 1;
    private Integer durationMs;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ==================== 便捷判定 ====================

    /**
     * 是否为待补偿步骤
     * <p>已成功、声明了补偿动作、有业务标识、且尚未补偿</p>
     */
    public boolean isPendingCompensation() {
        return state != null && state.isCompensable()
                && Boolean.TRUE.equals(compensable)
                && compensationAction != null && !compensationAction.isBlank()
                && businessKey != null && !businessKey.isBlank()
                && compensatedAt == null;
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSagaId() { return sagaId; }
    public void setSagaId(String sagaId) { this.sagaId = sagaId; }

    public Integer getStepSeq() { return stepSeq; }
    public void setStepSeq(Integer stepSeq) { this.stepSeq = stepSeq; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public ToolRiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(ToolRiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public String getToolArgs() { return toolArgs; }
    public void setToolArgs(String toolArgs) { this.toolArgs = toolArgs; }

    public String getToolResult() { return toolResult; }
    public void setToolResult(String toolResult) { this.toolResult = toolResult; }

    public ToolExecutionState getState() { return state; }
    public void setState(ToolExecutionState state) { this.state = state; }

    public ToolFailureType getFailureType() { return failureType; }
    public void setFailureType(ToolFailureType failureType) { this.failureType = failureType; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Boolean getCompensable() { return compensable; }
    public void setCompensable(Boolean compensable) { this.compensable = compensable; }

    public String getCompensationAction() { return compensationAction; }
    public void setCompensationAction(String compensationAction) { this.compensationAction = compensationAction; }

    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }

    public LocalDateTime getCompensatedAt() { return compensatedAt; }
    public void setCompensatedAt(LocalDateTime compensatedAt) { this.compensatedAt = compensatedAt; }

    public String getCompensationError() { return compensationError; }
    public void setCompensationError(String compensationError) { this.compensationError = compensationError; }

    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }

    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}