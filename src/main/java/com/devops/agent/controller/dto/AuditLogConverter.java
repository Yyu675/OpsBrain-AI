package com.devops.agent.controller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计数据结构化转换工具类，用于从通用 Map 转换为强类型 Record。
 */
public final class AuditLogConverter {

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

    public static AuditAiCallsResponse toAuditAiCallsResponse(Map<String, Object> map) {
        if (map == null) {
            return new AuditAiCallsResponse(List.of(), 0, 1, 20, 0, new AiCallStats(0, 0, 0.0, BigDecimal.ZERO, 0));
        }
        List<?> rawItems = (List<?>) map.get("items");
        List<AiCallLogItem> items = new ArrayList<>();
        if (rawItems != null) {
            for (Object obj : rawItems) {
                if (obj instanceof Map<?, ?> m) {
                    items.add(toAiCallLogItem((Map<String, Object>) m));
                }
            }
        }
        int total = getInt(map.get("total"), 0);
        int page = getInt(map.get("page"), 1);
        int size = getInt(map.get("size"), 20);
        int totalPages = getInt(map.get("totalPages"), 0);

        Map<String, Object> rawStats = (Map<String, Object>) map.get("stats");
        AiCallStats stats = toAiCallStats(rawStats);

        return new AuditAiCallsResponse(items, total, page, size, totalPages, stats);
    }

    public static AiCallLogItem toAiCallLogItem(Map<String, Object> m) {
        if (m == null) return null;
        return new AiCallLogItem(
                getLong(m.get("id")),
                getString(m.get("trace_id")),
                getString(m.get("model_name")),
                getBoolean(m.get("is_cached")),
                getInteger(m.get("latency_ms")),
                getBigDecimal(m.get("cost_rmb")),
                getString(m.get("operation_type")),
                getString(m.get("operator_id")),
                getString(m.get("affected_resources")),
                getString(m.get("query_preview")),
                toLocalDateTime(m.get("create_time"))
        );
    }

    public static AiCallStats toAiCallStats(Map<String, Object> m) {
        if (m == null) {
            return new AiCallStats(0, 0, 0.0, BigDecimal.ZERO, 0);
        }
        long totalCalls = getLong(m.get("totalCalls")) != null ? getLong(m.get("totalCalls")) : 0L;
        long cacheHits = getLong(m.get("cacheHits")) != null ? getLong(m.get("cacheHits")) : 0L;
        double cacheHitRate = getDouble(m.get("cacheHitRate"), 0.0);
        BigDecimal totalCost = getBigDecimal(m.get("totalCost"));
        if (totalCost == null) totalCost = BigDecimal.ZERO;
        long avgLatencyMs = getLong(m.get("avgLatencyMs")) != null ? getLong(m.get("avgLatencyMs")) : 0L;

        return new AiCallStats(totalCalls, cacheHits, cacheHitRate, totalCost, avgLatencyMs);
    }

    public static AgentCallDetailItem toAgentCallDetailItem(Map<String, Object> m) {
        if (m == null) return null;
        String modelName = getString(m.get("model_name"));
        if (modelName == null) modelName = getString(m.get("modelName"));
        return new AgentCallDetailItem(
                getLong(m.get("id")),
                getString(m.get("trace_id")),
                getString(m.get("user_query")),
                getString(m.get("agent_answer")),
                modelName,
                getBoolean(m.get("is_cached")),
                getInteger(m.get("latency_ms")),
                getBigDecimal(m.get("cost_rmb")),
                getString(m.get("citations")),
                getString(m.get("operation_type")),
                getString(m.get("affected_resources")),
                getString(m.get("operator_id")),
                toLocalDateTime(m.get("create_time"))
        );
    }

    public static AuditFilterOptionsResponse toAuditFilterOptionsResponse(Map<String, Object> m) {
        if (m == null) {
            return new AuditFilterOptionsResponse(List.of(), List.of(), List.of(), List.of());
        }
        List<String> models = toStringList(m.get("models"));
        List<String> operationTypes = toStringList(m.get("operationTypes"));
        List<String> actions = toStringList(m.get("actions"));
        List<String> targetTypes = toStringList(m.get("targetTypes"));
        return new AuditFilterOptionsResponse(models, operationTypes, actions, targetTypes);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object obj) {
        if (obj instanceof List<?> l) {
            List<String> res = new ArrayList<>();
            for (Object item : l) {
                if (item != null) res.add(item.toString());
            }
            return res;
        }
        return List.of();
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

    private static Double getDouble(Object obj, double def) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static BigDecimal getBigDecimal(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal b) return b;
        if (obj instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (obj instanceof String s) {
            try { return new BigDecimal(s); } catch (Exception ignored) {}
        }
        return null;
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
            try { return LocalDateTime.parse(s); } catch (Exception ignored) {}
        }
        return null;
    }
}
