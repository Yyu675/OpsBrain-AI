package com.devops.agent.controller.dto;

/**
 * 登录成功响应（POST /api/v1/auth/login）。
 *
 * @param token     Sa-Token 凭证值
 * @param tokenName 请求头键名（默认 satoken）
 * @param user      用户信息视图
 */
public record LoginResponse(
        String token,
        String tokenName,
        UserView user
) {
}
