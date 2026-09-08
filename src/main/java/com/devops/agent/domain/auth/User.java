package com.devops.agent.domain.auth;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体（方向三：真实鉴权）
 *
 * <p>对应 {@code sys_user} 表。密码字段存 BCrypt 哈希，绝不明文。
 * 角色 {@code role} 对齐前端 {@code Role}（ADMIN/OPS）。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Data
public class User {

    private Long id;

    /** 登录名（唯一） */
    private String username;

    /** BCrypt 密码哈希（$2a$...），仅后端持有，绝不下发前端 */
    private String password;

    /** 展示名（可对齐 sys_team_member.name） */
    private String displayName;

    /** 角色：ADMIN / OPS */
    private String role;

    /** 状态：ACTIVE / DISABLED */
    private String status;

    /**
     * 所属部门（C1）：决定该用户能看到哪些 RESTRICTED 知识文档。
     * <p>为 null 时看不到任何受限文档（最小权限）。</p>
     */
    private String dept;

    /** 末次登录时刻 */
    private LocalDateTime lastLoginAt;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 是否可用（DISABLED 用户禁止登录） */
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
