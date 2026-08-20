package com.devops.agent.domain.alert.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 告警实体（L2 实时监测）
 * <p>
 * 对应 sys_alert 表。状态机：
 * FIRING（触发中）→ ACKNOWLEDGED（人工确认，P0/P1）→ RESOLVED（已恢复）
 * FIRING → SUPPRESSED（5 分钟窗口内重复告警静默跟随）
 * 自动建单后回填 ticket_id。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-14
 */
public class Alert implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 告警来源（prometheus / 预留） */
    private String source;

    /** 告警规则名（如 PodCrashLoopBackOff） */
    private String alertName;

    /** 严重级别 P0/P1/P2/P3/P4 */
    private String level;

    /** 展示标题 */
    private String title;

    /** 告警详情/标签渲染文本 */
    private String description;

    /** 状态：FIRING/ACKNOWLEDGED/SUPPRESSED/RESOLVED */
    private String status;

    /** 去重键（alertName+service+关键标签哈希） */
    private String dedupKey;

    /** 服务名 */
    private String service;

    /** 映射后的业务模块枚举（HOST/POD/DB/CACHE/NETWORK 等） */
    private String module;

    /** 窗口内重复触达次数 */
    private Integer occurrenceCount;

    /** 首次触发时间 */
    private LocalDateTime firstOccurredAt;

    /** 最近一次触发时间 */
    private LocalDateTime lastOccurredAt;

    /** 人工确认时间（P0/P1 必填） */
    private LocalDateTime acknowledgedAt;

    /** 恢复时间 */
    private LocalDateTime resolvedAt;

    /** 关联工单号（自动建单后回填） */
    private String ticketId;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录更新时间 */
    private LocalDateTime updateTime;

    // ==================== 便利方法 ====================

    /**
     * 是否为 P0/P1 高危告警（需人工审批）
     */
    public boolean isHighRisk() {
        return "P0".equalsIgnoreCase(level) || "P1".equalsIgnoreCase(level);
    }

    /**
     * 是否为 P3/P4 低危告警（AI 可自动处理）
     */
    public boolean isLowRisk() {
        return "P3".equalsIgnoreCase(level) || "P4".equalsIgnoreCase(level);
    }

    // ==================== Getters / Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

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

    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }

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

    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}