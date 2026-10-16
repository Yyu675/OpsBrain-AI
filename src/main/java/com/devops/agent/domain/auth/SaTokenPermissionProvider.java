package com.devops.agent.domain.auth;

import cn.dev33.satoken.stp.StpInterface;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sa-Token 权限数据源（方向 D 前置）
 *
 * <p>Sa-Token 的 {@code StpUtil.checkRole} / {@code @SaCheckRole} 依赖本接口提供角色列表。
 * <b>不实现会在首次角色校验时抛异常</b>——审批端点限管理员（@SaCheckRole("ADMIN")）必须有它。</p>
 *
 * <p>角色来源 {@code sys_user.role}（ADMIN/OPS），单一真相源；不做缓存——
 * 用户角色变更应立即生效，且 sys_user 查询极轻（主键索引）。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@Component
public class SaTokenPermissionProvider implements StpInterface {

    private final UserRepository userRepository;

    public SaTokenPermissionProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 角色列表（供 checkRole / @SaCheckRole）
     * <p>用户不存在或已停用返回空列表——校验必然失败，比抛异常更符合「无权」语义。</p>
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        List<String> roles = new ArrayList<>();
        try {
            Long userId = Long.valueOf(String.valueOf(loginId));
            userRepository.findById(userId).ifPresent(u -> {
                if (u.isActive() && u.getRole() != null && !u.getRole().isBlank()) {
                    roles.add(u.getRole().toUpperCase());
                }
            });
        } catch (Exception e) {
            log.warn("⚠️ [SaToken] 取角色失败（视为无角色）| loginId={} | {}", loginId, e.getMessage());
        }
        return roles;
    }

    /**
     * 权限码列表（供 checkPermission / @SaCheckPermission）
     * <p>当前未启用细粒度权限码（方向 F 待做）：ADMIN 给通配 {@code *}，其余为空。
     * 通配符语义与前端 {@code permissions:['*']} 对齐。</p>
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        List<String> permissions = new ArrayList<>();
        if (getRoleList(loginId, loginType).contains("ADMIN")) {
            permissions.add("*");
        }
        return permissions;
    }
}
