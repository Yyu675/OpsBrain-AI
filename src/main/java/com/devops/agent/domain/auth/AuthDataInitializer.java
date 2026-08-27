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

    /**
     * 种子密码。默认值仅供本地开发——生产必须用 AUTH_SEED_PASSWORD 覆盖。
     *
     * <p>本字段<b>绝不可打进日志</b>，理由见 {@link #run} 内注释。</p>
     */
    @Value("${devops.auth.seed.password:admin123}")
    private String seedPassword;

    /** 内置默认密码。用于判断「是否仍在用默认值」，不作他用 */
    private static final String BUILTIN_DEFAULT_PASSWORD = "admin123";

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
            // ⚠️ 绝不打印密码本身。
            //
            // 原实现把 seedPassword 明文写进 WARN 日志。后果不是「日志难看」：
            // 日志会被采集到 ELK / 对象存储 / 监控平台，这些系统的访问面
            // 通常远宽于数据库——一个只有看板权限的人也能搜到管理员密码。
            // 而且日志一旦归档就<b>收不回来</b>：即便运维事后改了密码，
            // 历史日志里那条记录仍然有效地泄露了「这套系统的初始密码是什么」，
            // 而多数部署根本不会改。
            //
            // 仍然要给出提示，否则本地开发起不来会不知道密码——
            // 折中是：只在「用的是内置默认密码」时提示去查文档，
            // 用了自定义密码则什么都不说（说明部署方已经知道自己设了什么）。
            if (BUILTIN_DEFAULT_PASSWORD.equals(seedPassword)) {
                log.warn("🔐 [Auth] 已创建种子管理员 | username={} | 当前使用<内置默认密码> —— "
                        + "生产环境必须用 AUTH_SEED_PASSWORD 环境变量覆盖"
                        + "（默认值见 CLAUDE.md「登录与鉴权」一节）", seedUsername);
            } else {
                log.info("🔐 [Auth] 已创建种子管理员 | username={} | 密码取自 AUTH_SEED_PASSWORD", seedUsername);
            }
        } catch (Exception e) {
            // 种子失败不阻塞应用启动（表可能未迁移）——仅告警，登录时会因无用户而失败
            log.warn("⚠️ [Auth] 种子用户初始化失败（可能 sys_user 表未迁移）: {}", e.getMessage());
        }
    }
}
