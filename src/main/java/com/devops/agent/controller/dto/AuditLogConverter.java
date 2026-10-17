package com.devops.agent.controller.dto;

import com.devops.agent.domain.audit.AgentCallLogRow;
import com.devops.agent.domain.audit.AgentCallTraceRow;
import com.devops.agent.domain.audit.AuditAgentCallStats;
import com.devops.agent.domain.audit.AuditPage;
import com.devops.agent.domain.audit.OperationAuditTraceRow;

import java.math.BigDecimal;
import java.util.List;

/** Maps typed audit query results to the public response records. */
public final class AuditLogConverter {

    private AuditLogConverter() {}

    public static AuditOperationsResponse toAuditOperationsResponse(
            AuditPage<OperationAuditTraceRow> page) {
        if (page == null) {
            return new AuditOperationsResponse(List.of(), 0, 1, 20, 0);
        }
        List<OperationAuditItem> items = page.items() == null ? List.of() : page.items().stream()
                .map(AuditLogConverter::toOperationAuditItem).toList();
        return new AuditOperationsResponse(items, page.total(), page.page(), page.size(), page.totalPages());
    }

    public static OperationAuditItem toOperationAuditItem(OperationAuditTraceRow row) {
        if (row == null) return null;
        return new OperationAuditItem(
                row.id(), row.traceId(), row.actorId(), row.actorName(), row.action(),
                row.targetType(), row.targetId(), row.httpMethod(), row.httpPath(),
                row.statusCode(), row.success(), row.bizCode(), row.errorMessage(),
                row.clientIp(), row.durationMs(), row.createTime());
    }

    public static AuditAiCallsResponse toAuditAiCallsResponse(
            AuditPage<AgentCallLogRow> page, AuditAgentCallStats rawStats) {
        if (page == null) {
            return new AuditAiCallsResponse(List.of(), 0, 1, 20, 0,
                    new AiCallStats(0, 0, 0.0, BigDecimal.ZERO, 0));
        }
        List<AiCallLogItem> items = page.items() == null ? List.of() : page.items().stream()
                .map(AuditLogConverter::toAiCallLogItem).toList();
        return new AuditAiCallsResponse(items, page.total(), page.page(), page.size(),
                page.totalPages(), toAiCallStats(rawStats));
    }

    public static AiCallLogItem toAiCallLogItem(AgentCallLogRow row) {
        if (row == null) return null;
        return new AiCallLogItem(row.id(), row.traceId(), row.modelName(), row.isCached(),
                row.latencyMs(), row.costRmb(), row.operationType(), row.operatorId(),
                row.affectedResources(), row.queryPreview(), row.createTime());
    }

    public static AiCallStats toAiCallStats(AuditAgentCallStats stats) {
        if (stats == null) {
            return new AiCallStats(0, 0, 0.0, BigDecimal.ZERO, 0);
        }
        BigDecimal totalCost = stats.totalCost() == null ? BigDecimal.ZERO : stats.totalCost();
        return new AiCallStats(stats.totalCalls(), stats.cacheHits(), stats.cacheHitRate(),
                totalCost, stats.avgLatencyMs());
    }

    public static AgentCallDetailItem toAgentCallDetailItem(AgentCallTraceRow row) {
        if (row == null) return null;
        return new AgentCallDetailItem(
                row.id(), row.traceId(), row.userQuery(), row.agentAnswer(), row.modelName(),
                row.isCached(), row.latencyMs(), row.costRmb(), row.citations(),
                row.operationType(), row.affectedResources(), row.operatorId(), row.createTime());
    }
}
