package com.devops.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 自愈执行台账详情响应（GET /api/v1/healing/executions/{id}）。
 *
 * <p>P0-2b 强契约化：替代弱类型 {@code Map<String, Object>}。
 * 字段严格对齐前端 {@code healing.ts#HealingExecutionDetail}。</p>
 *
 * @param execution 执行台账实体详情
 * @param steps     步骤时间线列表
 */
public record HealingExecutionDetailResponse(
        com.devops.agent.domain.healing.HealingExecution execution,
        List<Map<String, Object>> steps
) {
}
