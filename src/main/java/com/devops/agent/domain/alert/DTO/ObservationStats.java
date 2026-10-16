package com.devops.agent.domain.alert.DTO;

import java.util.List;

/**
 * 自愈观察窗统计（PRD FR-3.1 的可视面，效能大盘读它）。
 *
 * <p>放在 domain 层而非 controller.dto：观察窗配置由 domain 持有，
 * record 跟着组装方走（六层依赖方向不允许 domain import controller）。</p>
 *
 * @param enabled        观察窗是否开启（关闭时前端隐藏该区块而非显示 0）
 * @param windowMinutes  观察窗口（分钟）
 * @param levels         观察级集合（如 [P2, P3]）
 * @param observingNow   此刻仍在观察窗内（活跃 + 未建单 + 未超窗）的告警数
 * @param selfHealed30d  近 30 天窗口内自愈（RESOLVED 且从未建单）的观察级告警数
 * @param escalated30d   近 30 天观察级告警到期转单/聚合进组数
 */
public record ObservationStats(
        boolean enabled,
        int windowMinutes,
        List<String> levels,
        long observingNow,
        long selfHealed30d,
        long escalated30d
) {
}
