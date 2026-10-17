package com.devops.agent.controller.dto;

/**
 * 诊断充分性计数项。
 *
 * @param sufficiency 充分性等级
 * @param count       数量
 */
public record DiagnosisSufficiencyStat(
        String sufficiency,
        long count
) {
}
