package com.devops.agent.domain.alert.DTO;

/**
 * 通知链自监控健康快照（GET /api/v1/alerts/notify-health，告警页/效能卡读它）。
 *
 * <p>P0-2b 强契约化：替代旧有的 {@code Map<String, Object>}。</p>
 *
 * @param attempts        进程启动以来累计发送尝试次数
 * @param successes       进程启动以来累计发送成功次数
 * @param failed          进程启动以来累计发送失败次数
 * @param degraded        进程启动以来累计降级次数
 * @param delivered       进程启动以来累计送达次数
 * @param deliverFailed   进程启动以来累计送达失败次数
 * @param lastDeliveredAt 最近一次送达时间（ISO 字符串或 null）
 * @param lastSuccessAt   最近一次成功时间（ISO 字符串或 null）
 * @param configured      是否已配置通知渠道
 * @param enabled         通知看门狗是否开启
 * @param silent          是否处于静默告警状态
 * @param lastScanAt      最近一次扫描时间（ISO 字符串或 null）
 * @param windowMinutes   健康检查窗口大小（分钟）
 */
public record NotifyHealthResponse(
        long attempts,
        long successes,
        long failed,
        long degraded,
        long delivered,
        long deliverFailed,
        String lastDeliveredAt,
        String lastSuccessAt,
        boolean configured,
        boolean enabled,
        boolean silent,
        String lastScanAt,
        long windowMinutes
) {
    public static NotifyHealthResponse unconfigured() {
        return new NotifyHealthResponse(
                0, 0, 0, 0, 0, 0,
                null, null,
                false, false, false,
                null, 5
        );
    }
}
