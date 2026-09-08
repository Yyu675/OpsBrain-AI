package com.devops.agent.domain.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S1 取证证据轻模型（路线图 §5.1/§5.6 的子集落地）。
 * <p>
 * 四态语义（路线图 §5.3 的 📌 表 + §5.2 1-1.5 的 UNAVAILABLE）：
 * <ul>
 *   <li>{@code SUCCESS}   —— 查到了，有数据；正常参与推理；</li>
 *   <li>{@code NO_DATA}   —— 查了，确实没有；是<b>有效证据</b>（排除该因素），
 *       与 FAILED 在推理层权重完全不同；</li>
 *   <li>{@code FAILED}    —— 查不了（数据源挂了/超时）；形成<b>证据缺口</b>，
 *       必须降低整体置信度，绝不可静默当作「已排除」；</li>
 *   <li>{@code UNAVAILABLE} —— 数据源未启用（缺配置）；与 FAILED 区分：
 *       FAILED 是运行期故障，UNAVAILABLE 是部署形态。</li>
 * </ul>
 * </p>
 * <p>
 * 本类只管<b>结构与序列化口径</b>；落库表 {@code sys_diagnosis_evidence}、
 * 聚合与充分性判定归 S1-5（路线图 §5.6）单独任务，不在此处预建。
 * </p>
 * <p>
 * record 字段名即 JSON 键名，逐字为准（台账铁律）。
 * </p>
 */
public record Evidence(
        EvidenceStatus status,
        String evidenceType,
        String title,
        Map<String, Object> content,
        String sourceRef,
        Double relevanceScore,
        Instant collectedAt) {

    /** 取证方向（evidenceType 的约定值，避免散写字面量漂移）。 */
    public static final class Type {
        private Type() {}
        public static final String METRICS = "metrics";
        public static final String CHANGES = "changes";
        public static final String LOGS    = "logs";
        public static final String TOPOLOGY= "topology";
    }

    public enum EvidenceStatus { SUCCESS, NO_DATA, FAILED, UNAVAILABLE }

    /** 紧凑构造：content 允许为 null（归一为空 Map）；relevanceScore 允许 null。 */
    public Evidence {
        content = content == null ? Map.of() : new LinkedHashMap<>(content);
        collectedAt = collectedAt == null ? Instant.now() : collectedAt;
    }

    /** 工具返回层的稳定序列化：JSON 键序固定，便于 LLM 阅读与测试断言。 */
    public String toToolPayload() {
        StringBuilder sb = new StringBuilder(384);
        sb.append('{');
        kv(sb, "status", status.name());
        kv(sb, "evidenceType", evidenceType);
        kv(sb, "title", title);
        kv(sb, "sourceRef", sourceRef);
        if (relevanceScore != null) {
            if (sb.length() > 1) sb.append(',');
            sb.append("\"relevanceScore\":").append(String.format(java.util.Locale.ROOT, "%.2f", relevanceScore));
        }
        if (sb.length() > 1) sb.append(',');
        sb.append("\"collectedAt\":\"").append(collectedAt).append("\",");
        sb.append("\"content\":").append(mapJson(content));
        sb.append('}');
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, String v) {
        if (sb.length() > 1) sb.append(',');
        sb.append('"').append(k).append("\":").append(v == null ? "null" : '"' + esc(v) + '"');
    }

    private static String mapJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder(128).append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> inner = (Map<String, Object>) v;
                sb.append(mapJson(inner));
            } else if (v instanceof List) {
                sb.append(listJson((List<?>) v));
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String listJson(List<?> list) {
        StringBuilder sb = new StringBuilder(64).append('[');
        boolean first = true;
        for (Object v : list) {
            if (!first) sb.append(',');
            first = false;
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> inner = (Map<String, Object>) v;
                sb.append(mapJson(inner));
            } else if (v instanceof List) {
                sb.append(listJson((List<?>) v));
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
        }
        return sb.append(']').toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
