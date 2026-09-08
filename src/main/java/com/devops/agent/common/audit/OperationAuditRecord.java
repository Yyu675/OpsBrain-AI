package com.devops.agent.common.audit;

import java.time.LocalDateTime;

/**
 * 一条操作审计记录（C5）。
 *
 * <p>不可变值对象——审计记录一旦产生就不该被改动。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
public record OperationAuditRecord(
        String traceId,
        String actorId,
        String actorName,
        /** 语言无关的操作标识，如 {@code knowledge.doc.delete} */
        String action,
        String targetType,
        String targetId,
        String httpMethod,
        String httpPath,
        int statusCode,
        boolean success,
        Integer bizCode,
        /** 请求体摘要：已脱敏并截断，绝不存全文 */
        String requestDigest,
        String errorMessage,
        String clientIp,
        String userAgent,
        int durationMs,
        LocalDateTime createTime
) {
}
