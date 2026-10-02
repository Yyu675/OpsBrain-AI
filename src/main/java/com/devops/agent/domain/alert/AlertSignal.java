package com.devops.agent.domain.alert;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 归一化后的告警信号（L2 接入层跨源中间表示）。
 *
 * <p>
 * 各监控体系（Alertmanager / Zabbix / 云监控…）负载形状各异，本 record 是
 * 「源适配器 → 核心处理链」的唯一边界：上游 {@link AlertSourceAdapter} 负责
 * 把具体负载解析成它，下游 {@code AlertService.processSignals} 只认它——
 * 接第二个信号源时新增一个适配器即可，核心链路零改动。
 * </p>
 *
 * <p>
 * {@code module / description / resolved / startsAt} 这些「算出来」的字段在
 * 适配器侧收敛：不同源的推断规则不同（如 Alertmanager 才有 label 语义），
 * 核心链不该承担「读懂某种源」的职责。
 * </p>
 *
 * @param severity 原始 severity 标签（CRITICAL/WARNING/INFO 或 P0~P4）——
 *                 到 P0~P4 的归一化留核心链（跨源统一的规则）
 * @param source   来源标识（如 "prometheus"），落库到 {@code sys_alert.source}
 * @param endsAt   真实恢复时刻（Alertmanager RFC3339 endsAt 透传）；firing 信号为 null，
 *                 零值（0001-01-01）由 resolve 侧回退为处理时刻
 */
public record AlertSignal(
        String alertName,
        String service,
        String severity,
        String module,
        String fingerprint,
        String source,
        Map<String, String> labels,
        Map<String, String> annotations,
        String description,
        OffsetDateTime startsAt,
        boolean resolved,
        OffsetDateTime endsAt) {
}