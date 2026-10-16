package com.devops.agent.domain.alert.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Prometheus Alertmanager Webhook 负载 DTO（L2 实时监测）
 * <p>
 * 解析 Alertmanager HTTP 告警回调的标准 JSON 结构：
 * <pre>
 * {
 *   "receiver": "opsbrain-webhook",
 *   "status": "firing",
 *   "alerts": [{
 *     "status": "firing",
 *     "labels": { "alertname": "...", "severity": "...", "service": "..." },
 *     "annotations": { "summary": "...", "description": "..." },
 *     "startsAt": "2026-08-14T10:00:00Z",
 *     "endsAt": "0001-01-01T00:00:00Z",
 *     "generatorURL": "http://prometheus/...",
 *     "fingerprint": "abc123"
 *   }]
 * }
 * </pre>
 * {@code labels}/{@code annotations} 为自由映射（告警规则可携带任意标签），
 * 服务侧通过 {@link #getLabel(String)} / {@link #getAnnotation(String)} 取用。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-14
 */
public class AlertmanagerWebhook {

    /** Webhook 接收器名（Alertmanager route 配置，仅日志展示用） */
    private String receiver;

    /** 告警组状态：firing / resolved */
    private String status;

    /**
     * 同一告警组内的告警列表
     * <p>JSON 缺省时返回空列表而非 null，避免服务侧 NPE。</p>
     */
    private List<Alert> alerts;

    // ==================== Getters ====================

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<Alert> getAlerts() {
        return alerts != null ? alerts : Collections.emptyList();
    }
    public void setAlerts(List<Alert> alerts) { this.alerts = alerts; }

    /**
     * 取第一组（通常只有一组）告警，空负载返回 null
     */
    public Alert firstAlert() {
        List<Alert> list = getAlerts();
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 内部告警项 ====================

    /**
     * 单条告警
     * <p>
     * 时间戳使用 {@link OffsetDateTime}——Alertmanager 下发 RFC3339（含时区）时间。
     * Jackson 自动完成反序列化；解析失败的字段为 null，由服务侧兜底。
     * </p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Alert {

        /** 单条告警状态：firing / resolved */
        private String status;

        /** 标签集（alertname/severity/service/module 等，规则可自定义） */
        private Map<String, String> labels;

        /** 注释集（summary/description 等人工可读信息） */
        private Map<String, String> annotations;

        /** 触发起始时间（RFC3339） */
        private OffsetDateTime startsAt;

        /** 恢复时间；未恢复时为 0001-01-01T00:00:00Z（Alertmanager 约定零值） */
        private OffsetDateTime endsAt;

        /** 生成该告警的 Prometheus 表达式链接 */
        private String generatorURL;

        /** Alertmanager 告警指纹（去重参考，非本项目 dedup 主键） */
        private String fingerprint;

        public String getStatus() { return status; }
        @JsonProperty("status")
        public void setStatus(String status) { this.status = status; }

        public Map<String, String> getLabels() {
            return labels != null ? labels : Collections.emptyMap();
        }
        @JsonProperty("labels")
        public void setLabels(Map<String, String> labels) { this.labels = labels; }

        public Map<String, String> getAnnotations() {
            return annotations != null ? annotations : Collections.emptyMap();
        }
        @JsonProperty("annotations")
        public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }

        public OffsetDateTime getStartsAt() { return startsAt; }
        @JsonProperty("startsAt")
        public void setStartsAt(OffsetDateTime startsAt) { this.startsAt = startsAt; }

        public OffsetDateTime getEndsAt() { return endsAt; }
        @JsonProperty("endsAt")
        public void setEndsAt(OffsetDateTime endsAt) { this.endsAt = endsAt; }

        public String getGeneratorURL() { return generatorURL; }
        @JsonProperty("generatorURL")
        public void setGeneratorURL(String generatorURL) { this.generatorURL = generatorURL; }

        public String getFingerprint() { return fingerprint; }
        public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

        // ==================== 标签/注释取用工具 ====================

        /**
         * 是否已恢复：status=resolved 或 endsAt 早于当前时间且非零值
         */
        public boolean isResolved() {
            if ("resolved".equalsIgnoreCase(status)) {
                return true;
            }
            return endsAt != null && endsAt.getYear() > 1;
        }

        /**
         * 取标签值（忽略大小写 key，Alertmanager 的字段名均为小写但规则可自定义）
         */
        public String getLabel(String key) {
            if (labels == null || key == null) return null;
            return labels.get(key);
        }

        /**
         * 取注释值，缺失时回退取 summary/description 任一非空
         */
        public String getAnnotation(String key) {
            if (annotations == null || key == null) return null;
            return annotations.get(key);
        }

        /**
         * 汇聚描述文本：优先 description，其次 summary，最低为标签组合
         */
        public String descriptionText() {
            String desc = getAnnotation("description");
            if (desc != null && !desc.isBlank()) return desc;
            String summary = getAnnotation("summary");
            if (summary != null && !summary.isBlank()) return summary;

            List<String> parts = new ArrayList<>();
            getLabels().forEach((k, v) -> parts.add(k + "=" + v));
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
    }
}