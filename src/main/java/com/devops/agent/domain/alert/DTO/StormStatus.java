package com.devops.agent.domain.alert.DTO;

/**
 * 全局风暴模式状态快照（FR-2.5 的可视面，告警列表横幅读它）。
 *
 * <p>放在 domain 层（同 ObservationStats 的归置理由）：
 * 状态由 AlertService 的风暴守卫持有，组装发生在 domain。</p>
 *
 * @param enabled         风暴模式是否开启（关闭时前端隐藏横幅位）
 * @param active          当前是否处于风暴模式
 * @param ratePerMin      近 60 秒 firing 告警数（当前速率）
 * @param enterRatePerMin 进入阈值
 * @param exitRatePerMin  退出阈值（迟滞带下沿）
 */
public record StormStatus(
        boolean enabled,
        boolean active,
        int ratePerMin,
        int enterRatePerMin,
        int exitRatePerMin
) {
}
