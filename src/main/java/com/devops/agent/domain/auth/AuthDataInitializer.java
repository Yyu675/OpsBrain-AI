package com.devops.agent.domain.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 种子用户初始化（方向三）：启动时确保存在一个管理员账号
 *
 * <p>幂等：已存在同名用户则跳过。密码用 BCrypt 编码写入（迁移不写死哈希，
 * 因 BCrypt 每次 salt 不同，运行时编码才能与登录校验一致）。</p>
 *
 * <p>默认 admin/admin123 仅供本地开发——启动日志会提示改密。生产用环境变量
 * {@code AUTH_SEED_USERNAME}/{@code AUTH_SEED_PASSWORD} 覆盖，或首次登录后改密。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@Component
public class AuthDataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final AuthService authService;

    @Value("${devops.auth.seed.username:admin}")
    private String seedUsername;

    @Value("${devops.auth.seed.password:admin123}")
    private String seedPassword;

    @Value("${devops.auth.seed.enabled:true}")
    private boolean seedEnabled;

    public AuthDataInitializer(UserRepository userRepository, AuthService authService) {
        this.userRepository = userRepository;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("[Auth] 种子用户初始化已关闭（devops.auth.seed.enabled=false）");
            return;
        }
        try {
            if (userRepository.findByUsername(seedUsername).isPresent()) {
                log.info("[Auth] 种子用户已存在，跳过初始化 | username={}", seedUsername);
                return;
            }
            User admin = new User();
            admin.setUsername(seedUsername);
            admin.setPassword(authService.encodePassword(seedPassword));
            admin.setDisplayName("管理员");
            admin.setRole("ADMIN");
            admin.setStatus("ACTIVE");
            userRepository.insert(admin);
            log.warn("🔐 [Auth] 已创建种子管理员 | username={} | 默认密码={} —— 生产环境请立即改密或用 AUTH_SEED_* 环境变量覆盖",
                    seedUsername, seedPassword);
        } catch (Exception e) {
            // 种子失败不阻塞应用启动（表可能未迁移）——仅告警，登录时会因无用户而失败
            log.warn("⚠️ [Auth] 种子用户初始化失败（可能 sys_user 表未迁移）: {}", e.getMessage());
        }
    }
}
