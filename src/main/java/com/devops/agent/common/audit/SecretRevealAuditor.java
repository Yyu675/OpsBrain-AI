package com.devops.agent.common.audit;

import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.context.TraceContext;
import com.devops.agent.common.web.ClientIpResolver;
import com.devops.agent.infrastructure.persistence.repo.OperationAuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 密钥明文查看（reveal）的审计留痕器（批89 全方位安全清查）。
 *
 * <h3>为什么拦截器覆盖不了</h3>
 * {@link OperationAuditInterceptor} 刻意跳过 GET/HEAD/OPTIONS（读操作量大，
 * 全量记录会把审计表撑成最大的表）。但 reveal 是「读」里唯一的例外——
 * 它把打码保护整个撤掉，泄露责任必须可追查：「谁在什么时候看了哪个密钥」
 * 与「谁改了密钥」同等重要。GET 端点在 Service/Controller 显式调用本类补记。
 *
 * <h3>只记三要素，绝不碰明文</h3>
 * 记录的是 actor / targetId / 时间 / IP / UA；{@code requestDigest} 恒为
 * null——审计表若存了明文，查审计的人就成了新的泄露面。
 *
 * <h3>审计失败不拦查看</h3>
 * 写审计失败（DB 抖动）时照常返回明文并 WARN——reveal 的场景是运维正在
 * 排障，把工具锁死只会逼人截图传微信（更坏的泄露渠道）。失败留痕是
 * 「降级可见」约定：WARN 里带 targetId，事后能补认。
 *
 * @author OpsBrain AI
 * @since 2026-10-07（批89 清查补口）
 */
@Component
public class SecretRevealAuditor {

    private static final Logger log = LoggerFactory.getLogger(SecretRevealAuditor.class);

    /** 通知域密钥明文查看（渠道 webhook/加签密钥、告警接入 secret 共用，targetId 区分） */
    public static final String ACTION_NOTIFY_REVEAL = "notify.secret.reveal";
    /** 模型渠道 API Key 明文查看 */
    public static final String ACTION_AI_CHANNEL_REVEAL = "ai-channel.key.reveal";

    private final OperationAuditRepository repository;
    private final ClientIpResolver clientIpResolver;

    public SecretRevealAuditor(OperationAuditRepository repository, ClientIpResolver clientIpResolver) {
        this.repository = repository;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * 记一条明文查看审计。调用点应在「明文确实取到之后」——没取到东西的
     * 失败请求不值得留痕（错误已由业务日志承载）。
     *
     * @param action   {@link #ACTION_NOTIFY_REVEAL} / {@link #ACTION_AI_CHANNEL_REVEAL}
     * @param targetId 被查看的密钥标识（dingtalk / wecom / alert-ingest / chat …）
     * @param httpPath 请求路径（不含 context-path，与拦截器口径一致）
     */
    public void record(String action, String targetId, String httpPath, HttpServletRequest request) {
        try {
            String actor = resolveActorId();
            repository.save(new OperationAuditRecord(
                    TraceContext.getTraceId(),
                    actor,
                    null,
                    action,
                    "secret",
                    targetId,
                    "GET",
                    truncate(httpPath, 255),
                    200,
                    true,
                    null,
                    null, // 明文绝不进审计表
                    null,
                    clientIpResolver.resolve(request),
                    truncate(request.getHeader("User-Agent"), 255),
                    0,
                    LocalDateTime.now()));
            log.info("👁️ [SecretReveal] {} | actor={} | target={}", action, actor, targetId);
        } catch (Exception e) {
            // 查看照常放行（理由见类注释），但失败必须可见
            log.warn("⚠️ [SecretReveal] 审计写入失败（查看未受影响）| action={} | target={} | {}",
                    action, targetId, e.toString());
        }
    }

    private String resolveActorId() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getLoginIdAsString();
            }
        } catch (Exception ignore) {
            // 非请求上下文（单测/内部调用）是预期分支；按静默 catch 契约留 debug 痕迹
            log.debug("[SecretReveal] actor 解析失败（非请求上下文），记 ANONYMOUS");
        }
        return "ANONYMOUS";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
