package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 诊断逐日趋势。
 *
 * @param days      日期标签列表（MM-dd）
 * @param created   发起诊断数
 * @param completed 完成诊断数
 */
public record DiagnosisSessionTrend(
        List<String> days,
        List<Long> created,
        List<Long> completed
) {
}
