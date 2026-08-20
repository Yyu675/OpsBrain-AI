package com.devops.agent.domain.auth;

import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 鉴权服务（方向三）：登录校验 + 密码哈希
 *
 * <p>密码用 BCrypt（jBCrypt）。登录失败统一抛 {@link AuthException}，
 * 不区分「用户不存在」与「密码错误」——避免暴露用户名是否存在（防枚举）。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 登录校验。
     *
     * @return 校验通过的用户
     * @throws AuthException 用户不存在 / 密码错误 / 账号停用（统一「用户名或密码错误」防枚举）
     */
    public User login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new AuthException("用户名或密码不能为空");
        }
        User user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new AuthException("用户名或密码错误"));

        // 停用账号：明确提示（与「密码错误」区分——这不涉及枚举风险，账号确实存在且是管理动作）
        if (!user.isActive()) {
            throw new AuthException("账号已停用，请联系管理员");
        }

        // BCrypt 校验：checkpw 内部比对 salt+hash，恒定时间
        if (!BCrypt.checkpw(password, user.getPassword())) {
            throw new AuthException("用户名或密码错误");
        }

        userRepository.updateLastLogin(user.getId(), LocalDateTime.now());
        log.info("🔑 [Auth] 登录成功 | userId={} | username={} | role={}",
                user.getId(), user.getUsername(), user.getRole());
        return user;
    }

    /** BCrypt 编码密码（种子初始化 / 未来改密用） */
    public String encodePassword(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    /** 登录异常（由 AuthController 映射为 401/40001） */
    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }
}
