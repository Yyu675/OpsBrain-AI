package com.devops.agent.controller.dto;

/**
 * 工单附件预签名下载地址响应（GET /api/v1/tickets/attachments/{attachmentId}/download-url）。
 *
 * <p>P0-2b 强契约化：替代弱类型 {@code Map<String, Object>}。
 * 字段严格对齐前端 {@code ticket.service.ts#fetchAttachmentDownloadUrl}。</p>
 *
 * @param url              MinIO 预签名只读下载 URL（有限时效）
 * @param expiresInSeconds URL 有效期（秒）
 */
public record AttachmentDownloadUrlResponse(
        String url,
        int expiresInSeconds
) {
}
