package com.devops.agent.controller.dto;

import com.devops.agent.domain.audit.AgentCallLogRow;
import com.devops.agent.domain.audit.AuditAgentCallStats;
import com.devops.agent.domain.audit.AuditPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计数据结构化转换工具类，用于从通用 Map 转换为强类型 Record。
 */
public final class AuditLogConverter {

    private static final Logger log = LoggerFactory.getLogger(AuditLogConverter.class);

    private AuditLogConverter() {}

    public static AuditOperationsResponse toAuditOperationsResponse(Map<String, Object> map) {
        if (map == null) {
            return new AuditOperationsResponse(List.of(), 0, 1, 20, 0);
        }
        List<?> rawItems = (List<?>) map.get("items");
        List<OperationAuditItem> items = new ArrayList<>();
        if (rawItems != null) {
            for (Object obj : rawItems) {
                if (obj instanceof Map<?, ?> m) {
                    items.add(toOperationAuditItem((Map<String, Object>) m));
                }
            }
        }
        int total = getInt(map.get("total"), 0);
        int page = getInt(map.get("page"), 1);
        int size = getInt(map.get("size"), 20);
        int totalPages = getInt(map.get("totalPages"), 0);
        return new AuditOperationsResponse(items, total, page, size, totalPages);
    }

    public static OperationAuditItem toOperationAuditItem(
            com.devops.agent.domain.audit.OperationAuditTraceRow row) {
        if (row == null) return null;
        return new OperationAuditItem(
                row.id(), row.traceId(), row.actorId(), row.actorName(), row.action(),
                row.targetType(), row.targetId(), row.httpMethod(), row.httpPath(),
                row.statusCode(), row.success(), row.bizCode(), row.errorMessage(),
                row.clientIp(), row.durationMs(), row.createTime());
    }

    public static OperationAuditItem toOperationAuditItem(Map<String, Object> m) {
        if (m == null) return null;
        return new OperationAuditItem(
                getLong(m.get("id")),
                getString(m.get("trace_id")),
                getString(m.get("actor_id")),
                getString(m.get("actor_name")),
                getString(m.get("action")),
                getString(m.get("target_type")),
                getString(m.get("target_id")),
                getString(m.get("http_method")),
                getString(m.get("http_path")),
                getInteger(m.get("status_code")),
                getBoolean(m.get("success")),
                getInteger(m.get("biz_code")),
                getString(m.get("error_message")),
                getString(m.get("client_ip")),
                getInteger(m.get("duration_ms")),
                toLocalDateTime(m.get("create_time"))
        );
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

    public static AgentCallDetailItem toAgentCallDetailItem(
            com.devops.agent.domain.audit.AgentCallTraceRow row) {
        if (row == null) return null;
        return new AgentCallDetailItem(
                row.id(), row.traceId(), row.userQuery(), row.agentAnswer(), row.modelName(),
                row.isCached(), row.latencyMs(), row.costRmb(), row.citations(),
                row.operationType(), row.affectedResources(), row.operatorId(), row.createTime());
    }

    private static String getString(Object obj) {
        return obj == null ? null : obj.toString();
    }

    private static Long getLong(Object obj) {
        if (obj instanceof Number n) return n.longValue();
        if (obj instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Integer getInteger(Object obj) {
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static int getInt(Object obj, int def) {
        Integer val = getInteger(obj);
        return val != null ? val : def;
    }

    private static Boolean getBoolean(Object obj) {
        if (obj instanceof Boolean b) return b;
        if (obj instanceof String s) return Boolean.parseBoolean(s);
        return null;
    }

    private static LocalDateTime toLocalDateTime(Object obj) {
        if (obj instanceof LocalDateTime ldt) return ldt;
        if (obj instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (obj instanceof String s) {
            try {
                return LocalDateTime.parse(s);
            } catch (Exception e) {
                log.debug("[AuditLogConverter] LocalDateTime 解析失败，回落 null: {}", s, e);
            }
        }
        return null;
    }
}
