package com.devops.agent.domain.alert.DTO;

/**
 * 日志管道心跳响应（GET /api/v1/alerts/logs-heartbeat，效能大盘「日志管道」卡读它）。
 *
 * <p>P0-2b 强契约化：替代旧有的 {@code Map<String, Object>}。
 * 字段名与前端 {@code alerts.ts#LogsHeartbeat} 完全一致。</p>
 *
 * @param freshestLogAt  最新一条日志时间（ISO 字符串或 null）
 * @param silent         是否超阈值静默
 * @param silenceMinutes 静默阈值（分钟）
 * @param probed         是否已探测过（false 表示处于启动宽限期）
 * @param lokiEnabled    Loki 日志数据源是否启用
 */
public record LogsHeartbeat(
        String freshestLogAt,
        boolean silent,
        int silenceMinutes,
        boolean probed,
        boolean lokiEnabled
) {
}
