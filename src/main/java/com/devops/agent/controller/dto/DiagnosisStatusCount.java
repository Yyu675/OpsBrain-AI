package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 诊断会话状态计数项。
 *
 * @param status 状态代码
 * @param count  数量
 */
public record DiagnosisStatusCount(
        String status,
        long count
) {
}
