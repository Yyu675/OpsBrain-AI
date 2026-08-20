package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单附件元数据实体
 * <p>
 * 对应 {@code sys_ticket_attachment}。本表只存元数据，
 * 文件本体在 MinIO，通过 {@code objectKey} 关联。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
public class TicketAttachment implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 所属工单号 */
    private String ticketId;

    /** 对象存储桶名 */
    private String bucket;

    /**
     * 对象键
     * <p>
     * 服务端生成，形如 {@code 2026/08/09/{uuid}.log}。
     * 绝不使用用户提交的文件名——后者可含路径穿越序列。
     * </p>
     */
    private String objectKey;

    /**
     * 用户原始文件名
     * <p>仅用于展示与下载时的 Content-Disposition，不参与路径拼接。</p>
     */
    private String originalName;

    private String contentType;

    private Long sizeBytes;

    /** 内容 SHA-256，用于同工单内重复上传检测 */
    private String sha256;

    private String uploader;

    private LocalDateTime createTime;

    // ==================== 便捷方法 ====================

    /**
     * 人类可读的文件大小
     * <p>前端可直接展示，避免各端重复实现格式化逻辑。</p>
     */
    @SuppressWarnings("unused")   // Jackson 序列化时作为派生字段输出
    public String getSizeText() {
        if (sizeBytes == null) return "-";
        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1048576) return String.format("%.1f KB", sizeBytes / 1024.0);
        return String.format("%.1f MB", sizeBytes / 1048576.0);
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getUploader() { return uploader; }
    public void setUploader(String uploader) { this.uploader = uploader; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}