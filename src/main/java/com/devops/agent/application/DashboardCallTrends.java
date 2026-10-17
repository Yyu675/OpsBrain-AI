package com.devops.agent.application;

import java.util.List;

/** Typed result for the fixed-shape AI call trend series. */
public record DashboardCallTrends(
        List<String> days,
        List<Double> cost,
        List<Double> cacheHitRate) {
}
