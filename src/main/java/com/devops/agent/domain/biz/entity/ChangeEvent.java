package com.devops.agent.domain.biz.entity;

import java.time.LocalDateTime;

/**
 * 变更事件（S1-2，sys_change_event 行模型）。
 * record 字段名即对外 JSON 键名，逐字为准（台账铁律）。
 *
 * @param relevanceScore 仅查询侧填充（距取证时刻的时间相关性分，S1-2 分档口径），
 *                       写入侧恒 null；不进库，相关SQL勿依赖
 */
public record ChangeEvent(
        Long id,
        String serviceName,
        String changeType,
        String operator,
        String summary,
        LocalDateTime changeTime,
        LocalDateTime reportedAt,
        String source,
        String externalId,
        Double relevanceScore) {

    /** 写入侧构造（无 id/relevance）。 */
    public static ChangeEvent of(String serviceName, String changeType, String operator,
                                 String summary, LocalDateTime changeTime,
                                 String source, String externalId) {
        return new ChangeEvent(null, serviceName, changeType, operator, summary,
                changeTime, null, source, externalId, null);
    }

    /** 读出后附加相关性分（值对象风格，不改原行）。 */
    public ChangeEvent withRelevance(double score) {
        return new ChangeEvent(id, serviceName, changeType, operator, summary,
                changeTime, reportedAt, source, externalId, score);
    }
}
