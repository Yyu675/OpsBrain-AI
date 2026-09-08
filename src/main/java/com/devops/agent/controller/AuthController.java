package com.devops.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.dto.ApiCode;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.auth.AuthService;
import com.devops.agent.domain.auth.User;
import com.devops.agent.domain.auth.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 鉴权接口（方向三：Sa-Token 登录授权）
 *
 * <ul>
 *   <li>POST /api/v1/auth/login  —— 用户名密码登录，Sa-Token 签发 token</li>
 *   <li>GET  /api/v1/auth/me     —— 取当前登录用户</li>
 *   <li>POST /api/v1/auth/logout —— Sa-Token 服务端登出（token 失效，非仅前端删除）</li>
 * </ul>
 *
 * <p>职责分工：密码校验（BCrypt）由 {@link AuthService} 负责，登录态由 Sa-Token 负责。
 * 本控制器属白名单（{@code /api/v1/auth/**} 未登录可访问），由 SaInterceptor 放行。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    /**
     * 登录请求。
     * <p>
     * 长度上限是<b>防御性的</b>：登录端点免鉴权，任何人可调。
     * 不限长时，攻击者可提交超长字符串迫使服务端做 BCrypt 计算
     * （BCrypt 是刻意设计的慢哈希，成本随输入增长），
     * 少量并发即可耗尽 CPU —— 一种低成本的 DoS。
     * </p>
     * <p>上限取自 sys_user 表：username VARCHAR(64)。
     * 密码上限 128 足够容纳任何合理口令，且远小于 BCrypt 的 72 字节有效长度。</p>
     */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(max = 64, message = "用户名过长")
            String username,

            @NotBlank(message = "密码不能为空")
            @Size(max = 128, message = "密码过长")
            String password) {}

    /** 登录：BCrypt 校验通过后 Sa-Token 签发 token，返回 token + 用户信息（不含密码） */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        try {
            User user = authService.login(req.username(), req.password());
            // Sa-Token 登录：以 userId 为 loginId，token 存 Redis（服务端可控失效）
            StpUtil.login(user.getId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", StpUtil.getTokenValue());
            data.put("tokenName", StpUtil.getTokenName());   // 前端据此设置请求头名（默认 satoken）
            data.put("user", toUserView(user));
            return ApiResponse.success(data, "登录成功");
        } catch (AuthService.AuthException e) {
            log.info("🔒 [Auth] 登录失败 | username={} | reason={}", req.username(), e.getMessage());
            return ApiResponse.error(ApiCode.LOGIN_FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("❌ [Auth] 登录异常 | username={}", req.username(), e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "登录失败，请稍后重试");
        }
    }

    /** 取当前用户：Sa-Token 从请求上下文读 token 判定登录态 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        if (!StpUtil.isLogin()) {
            return ApiResponse.error(ApiCode.UNAUTHORIZED, "未登录");
        }
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || !user.isActive()) {
                // 用户被删/停用：踢下线，避免残留 token 继续可用
                StpUtil.logout();
                return ApiResponse.error(ApiCode.UNAUTHORIZED, "用户不存在或已停用");
            }
            return ApiResponse.success(toUserView(user));
        } catch (Exception e) {
            return ApiResponse.error(ApiCode.UNAUTHORIZED, "登录已失效，请重新登录");
        }
    }

    /** 登出：Sa-Token 服务端失效当前 token（真正登出，非仅前端删除） */
    @PostMapping("/logout")
    public ApiResponse<String> logout() {
        try {
            StpUtil.logout();
        } catch (Exception ignored) {
            // 未登录时 logout 无副作用，忽略
        }
        return ApiResponse.success("已登出");
    }

    /** 用户视图：绝不含 password */
    private Map<String, Object> toUserView(User user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("displayName", user.getDisplayName());
        view.put("role", user.getRole());
        return view;
    }
}
