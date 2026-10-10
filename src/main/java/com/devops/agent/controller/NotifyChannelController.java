package com.devops.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.audit.SecretRevealAuditor;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.notify.NotifyChannelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通知渠道配置控制器（批89 · 方案1——「配置可视化」的 HTTP 面）。
 *
 * <h3>六个端点</h3>
 * <ul>
 *   <li>GET  /                          两渠道+告警接入 secret 打码整屏视图；</li>
 *   <li>PUT  /{key}                     保存渠道（enabled/webhook/secret 三态 null=不动 ""=清空）；</li>
 *   <li>POST /{key}/test                 真发一条测试消息（走 NotifyPayloads 单源）；</li>
 *   <li>GET  /{key}/reveal               渠道明文（ADMIN + 访问日志留痕）；</li>
 *   <li>GET  /ingest-secret?reveal=bool  告警接入 secret（默认打码；reveal=true 明文）；</li>
 *   <li>PUT  /ingest-secret             保存告警接入 secret（holder 热生效，弹警告提示同步 Alertmanager）。</li>
 * </ul>
 *
 * <h3>安全边界</h3>
 * 类级 {@code @SaCheckRole("ADMIN")}；审计走 AuditActionRegistry
 * （PUT → notify.channel.update / notify.ingest-secret.update 已登记）；
 * 明文只出 reveal 端点且打 INFO 日志，列表/整屏视图永远打码。
 *
 * @author OpsBrain AI
 * @since 2026-10-06
 */
@RestController
@RequestMapping("/api/v1/notify-channels")
@SaCheckRole("ADMIN")
public class NotifyChannelController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NotifyChannelController.class);

    private final NotifyChannelService service;
    private final com.devops.agent.common.audit.SecretRevealAuditor revealAuditor;

    public NotifyChannelController(NotifyChannelService service,
                                   com.devops.agent.common.audit.SecretRevealAuditor revealAuditor) {
        this.service = service;
        this.revealAuditor = revealAuditor;
    }

    /**
     * 保存渠道请求（null=不改该字段；""=清空；非空=覆盖——三态语义注释见 Service.update）。
     * hidden=true：@RequestBody record 不产 OpenAPI schema——orval 对本 record 产了
     * 语法坏文件（单点 bug，26 个同形态反例正常，归因失败即工程绕行），且 body 类
     * 本就「前端手写权威、不入 fieldContract 对账」——不进快照=少一个比对面。
     */
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public record UpdateChannelBody(Boolean enabled, String webhook, String secret) {}

    /** 保存告警接入 secret 请求（hidden 同上，防单点生成 bug）。 */
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public record UpdateIngestBody(String secret) {}

    @GetMapping
    public ApiResponse<NotifyChannelService.NotifyChannelList> list() {
        return ApiResponse.success(service.listViews());
    }

    @PutMapping("/{key}")
    public ApiResponse<NotifyChannelService.NotifyChannelView> update(
            @PathVariable String key,
            @RequestBody @io.swagger.v3.oas.annotations.media.Schema(hidden = true) UpdateChannelBody body) {
        return ApiResponse.success(service.update(key, body.enabled(), body.webhook(),
                body.secret(), currentUserId()));
    }

    @PostMapping("/{key}/test")
    public ApiResponse<NotifyChannelService.TestResult> sendTest(@PathVariable String key) {
        return ApiResponse.success(service.sendTest(key));
    }

    @GetMapping("/{key}/reveal")
    public ApiResponse<NotifyChannelService.Revealed> reveal(
            @PathVariable String key, jakarta.servlet.http.HttpServletRequest request) {
        NotifyChannelService.Revealed revealed = service.reveal(key);
        // GET 不进拦截器审计（读量太大刻意跳过），但明文查看是读里的例外——
        // 谁看了哪个密钥必须可追查（批89 清查补口，SecretRevealAuditor 有全文）
        revealAuditor.record(SecretRevealAuditor.ACTION_NOTIFY_REVEAL,
                revealed.channelKey(), request.getRequestURI(), request);
        return ApiResponse.success(revealed);
    }

    @GetMapping("/ingest-secret")
    public ApiResponse<Object> ingestSecret(@RequestParam(defaultValue = "false") boolean reveal,
                                            jakarta.servlet.http.HttpServletRequest request) {
        if (reveal) {
            NotifyChannelService.Revealed revealed = service.revealIngest();
            revealAuditor.record(SecretRevealAuditor.ACTION_NOTIFY_REVEAL,
                    NotifyChannelService.KEY_ALERT_INGEST, request.getRequestURI(), request);
            return ApiResponse.success(revealed);
        }
        // 复用 listViews 的 ingestSecret 视图（打码整屏里已含它，这里给单取入口）
        return ApiResponse.success(service.listViews().ingestSecret());
    }

    @PutMapping("/ingest-secret")
    public ApiResponse<NotifyChannelService.IngestSecretView> updateIngest(
            @RequestBody @io.swagger.v3.oas.annotations.media.Schema(hidden = true) UpdateIngestBody body) {
        return ApiResponse.success(service.updateIngestSecret(body.secret(), currentUserId()));
    }

    /** 审计 actor：登录态取 ID，异常兜底 unknown（同 ModelChannelController 先例）。 */
    private static String currentUserId() {
        try {
            if (StpUtil.isLogin()) return StpUtil.getLoginIdAsString();
        } catch (Exception e) {
            // 未初始化/会话过期是预期分支；按静默 catch 契约留 debug 痕迹（同先例口径）
            log.debug("[NotifyChannel] 操作人解析失败，记 unknown | {}", e.toString());
        }
        return "unknown";
    }

    /** 兼容编译期 Map 导入（ingest-secret 单取用 record，此处保留给未来扩展）。 */
    @SuppressWarnings("unused")
    private static final Class<Map> UNUSED = Map.class;
}
