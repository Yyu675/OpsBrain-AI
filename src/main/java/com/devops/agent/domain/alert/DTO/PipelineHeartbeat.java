package com.devops.agent.domain.alert.DTO;

/**
 * 告警管道心跳响应（FR-1.6 看门狗可视面，GET /api/v1/alerts/heartbeat）。
 *
 * <p>P0-2b 强契约化：替代旧有的 {@code Map<String, Object>}。
 * 字段名与前端 {@code alerts.ts#PipelineHeartbeat} 完全一致。</p>
 *
 * @param lastSeenAt     看门狗最近一次送达时间（ISO 本地时间或 null）
 * @param silent         是否超阈值静默（true 意味着告警可能正在丢失）
 * @param silenceMinutes 静默阈值（分钟）
 */
public record PipelineHeartbeat(
        String lastSeenAt,
        boolean silent,
        int silenceMinutes
) {
}
