package com.devops.agent.domain.rag;

import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.domain.auth.User;
import com.devops.agent.domain.auth.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 解析当前请求方的知识可见范围（C1）。
 *
 * <h3>⚠️ 必须在「请求线程」调用</h3>
 * 本类依赖 Sa-Token 的 {@link StpUtil}，而它基于 ThreadLocal。
 * 一旦切到 {@code sessionExecutor} 虚拟线程或模型 HTTP 回调线程就取不到登录态。
 * 因此调用点应当在 Controller 或 Service 的<b>入口</b>，
 * 解析出 {@link KnowledgeScope} 后作为参数向下传递。
 * 项目里同类约束已有先例：{@code DevOpsAgentServiceImpl.resolveQuotaKey()}
 * 的注释明确写了「下方 runAsync 一旦切到虚拟线程，StpUtil.isLogin() 就取不到登录态」。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
@Component
public class KnowledgeScopeResolver {

    private final UserRepository userRepository;

    public KnowledgeScopeResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 解析当前登录用户的可见范围。
     *
     * <p>取不到登录态时返回 {@link KnowledgeScope#anonymous()}（仅 PUBLIC），
     * 而<b>不是</b>放行全部。权限组件的失败方向必须是「更严」而非「更松」——
     * 与限流/配额那种 fail-open 的取舍相反：那里失败会让排障工具不可用，
     * 这里失败会造成数据越权，后果不对称。</p>
     */
    public KnowledgeScope resolveCurrent() {
        try {
            if (!StpUtil.isLogin()) {
                return KnowledgeScope.anonymous();
            }
            long userId = StpUtil.getLoginIdAsLong();
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                // token 有效但用户已被删除：按匿名处理并告警
                log.warn("⚠️ [KnowledgeScope] 登录态有效但用户不存在，降级为匿名 | userId={}", userId);
                return KnowledgeScope.anonymous();
            }
            boolean admin = "ADMIN".equalsIgnoreCase(user.getRole());
            return admin
                    ? KnowledgeScope.admin(String.valueOf(userId), user.getDept())
                    : KnowledgeScope.user(String.valueOf(userId), user.getDept());
        } catch (Exception e) {
            // 非请求上下文（定时任务/异步线程）或 Sa-Token 未就绪
            log.debug("🔒 [KnowledgeScope] 无法解析登录态，按匿名处理: {}", e.getMessage());
            return KnowledgeScope.anonymous();
        }
    }

    /**
     * 系统内部调用使用的范围（定时任务、索引重建等）。
     * <p>显式命名为 internal 而非复用 admin()，是为了让审计日志能区分
     * 「管理员查的」与「系统任务查的」。</p>
     */
    public KnowledgeScope systemScope() {
        return KnowledgeScope.admin("SYSTEM", null);
    }
}
