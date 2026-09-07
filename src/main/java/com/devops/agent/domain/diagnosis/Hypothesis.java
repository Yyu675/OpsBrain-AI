package com.devops.agent.domain.diagnosis;

import java.time.Instant;
import java.util.List;

/**
 * 根因假设（S2-2，路线图 §6.2 2-2.1）。
 * <p>
 * record 字段名即 JSON 键名（与 Evidence 工具载荷同风格，回放零转换）。
 * </p>
 *
 * @param rank            排名（1 起；Top-3 假设中位次）
 * @param statement       假设陈述（人类可读的一句话根因）
 * @param reasoning       推理链（引用了哪些证据、如何推出）
 * @param confidence      自评置信度（0~1；经置信度引擎钳制后的最终值）
 * @param evidenceIds     支撑证据 id 列表（点开假设看证据的关联键）
 * @param contradictIds   与本假设冲突的证据 id 列表
 * @param suggestedAction 建议动作（人类视角的下一步）
 * @param createdAt       生成时间
 */
public record Hypothesis(
        int rank,
        String statement,
        String reasoning,
        double confidence,
        List<Long> evidenceIds,
        List<Long> contradictIds,
        String suggestedAction,
        Instant createdAt) {
}
