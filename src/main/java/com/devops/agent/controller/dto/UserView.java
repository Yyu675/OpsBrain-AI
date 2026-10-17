package com.devops.agent.controller.dto;

/**
 * 用户视图（绝不含敏感凭证）。
 *
 * @param id          用户 ID
 * @param username    用户名
 * @param displayName 显示名称
 * @param role        角色代码
 */
public record UserView(
        Long id,
        String username,
        String displayName,
        String role
) {
}
