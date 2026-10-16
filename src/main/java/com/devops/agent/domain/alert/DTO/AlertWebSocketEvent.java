package com.devops.agent.domain.alert.DTO;

import com.devops.agent.domain.alert.entity.Alert;
import java.time.LocalDateTime;

/**
 * WebSocket 告警推送事件 DTO
 * <p>
 * 用于 AlertStreamMode 推送告警变更。三事件类型：NEW / UPDATE / RESOLVED。
 * 非阻塞旁路广播——推送失败不影响告警主流程。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-15
 */
public class AlertWebSocketEvent {

    /** 事件类型：NEW / UPDATE / RESOLVED */
    private String type;

    /** 事件时间戳 */
    private LocalDateTime timestamp;

    /** 告警载荷 */
    private AlertPayload alert;

    public static AlertWebSocketEvent of(String type, Alert alert) {
        AlertWebSocketEvent event = new AlertWebSocketEvent();
        event.type = type;
        event.timestamp = LocalDateTime.now();
        event.alert = AlertPayload.from(alert);
        return event;
    }

    // ==================== Getters / Setters ====================

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public AlertPayload getAlert() { return alert; }
    public void setAlert(AlertPayload alert) { this.alert = alert; }

    /**
     * 告警载荷（轻量，仅含前端展示所需字段，不含 acknowledgedAt/resolvedAt 等审计字段）
     */
    public static class AlertPayload {
        private Long id;
        private String alertName;
        private String level;
        private String title;
        private String description;
        private String status;
        private String service;
        private String module;
        private Integer occurrenceCount;
        private LocalDateTime firstOccurredAt;
        private LocalDateTime lastOccurredAt;
        private String ticketId;

        public static AlertPayload from(Alert alert) {
            AlertPayload p = new AlertPayload();
            p.id = alert.getId();
            p.alertName = alert.getAlertName();
            p.level = alert.getLevel();
            p.title = alert.getTitle();
            p.description = alert.getDescription();
            p.status = alert.getStatus();
            p.service = alert.getService();
            p.module = alert.getModule();
            p.occurrenceCount = alert.getOccurrenceCount();
            p.firstOccurredAt = alert.getFirstOccurredAt();
            p.lastOccurredAt = alert.getLastOccurredAt();
            p.ticketId = alert.getTicketId();
            return p;
        }

        // ==================== Getters / Setters ====================

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getAlertName() { return alertName; }
        public void setAlertName(String alertName) { this.alertName = alertName; }

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getService() { return service; }
        public void setService(String service) { this.service = service; }

        public String getModule() { return module; }
        public void setModule(String module) { this.module = module; }

        public Integer getOccurrenceCount() { return occurrenceCount; }
        public void setOccurrenceCount(Integer occurrenceCount) { this.occurrenceCount = occurrenceCount; }

        public LocalDateTime getFirstOccurredAt() { return firstOccurredAt; }
        public void setFirstOccurredAt(LocalDateTime firstOccurredAt) { this.firstOccurredAt = firstOccurredAt; }

        public LocalDateTime getLastOccurredAt() { return lastOccurredAt; }
        public void setLastOccurredAt(LocalDateTime lastOccurredAt) { this.lastOccurredAt = lastOccurredAt; }

        public String getTicketId() { return ticketId; }
        public void setTicketId(String ticketId) { this.ticketId = ticketId; }
    }
}