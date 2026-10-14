package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.common.exception.WebhookRejectedException;
import com.devops.agent.common.web.WebhookGuard;
import com.devops.agent.domain.alert.DTO.ZabbixWebhook;

import java.util.Map;
import com.devops.agent.domain.alert.service.AlertService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Zabbix Webhook 接收端点（L2 多信号源第二源，2026-10-14）。
 *
 * <h3>与 Alertmanager 端点的三处有意差异</h3>
 * <ol>
 *   <li><b>形状不收窄</b>：{@code AlertWebhookController} 用强类型
 *       {@code AlertmanagerWebhook} 反序列化；Zabbix 的媒介类型负载由管理员自定义，
 *       故这里收 {@link ZabbixWebhook}（保留全部字段），形状判断交给适配器。
 *       字段写错的后果因此是「400 + 一句人话」，而不是 Jackson 的
 *       「Unrecognized field」把运维引向错误方向。</li>
 *   <li><b>负载不认识 → 400，不是 200</b>：与 Alertmanager 端点「无论如何都返回 200」
 *       的取舍相反，理由在客户端不同：Alertmanager 会给非 200 重投，静默丢告警更危险；
 *       而 Zabbix 的 Webhook 媒介类型在脚本里读响应状态，返回 400 会让
 *       「动作的 Webhook 调用失败」直接出现在 Zabbix 的事件日志里——
 *       接入方自测阶段就能看到，比静默 200 后再去平台里找好得多。
 *       真正的空负载（没带任何字段）仍返回 200：那多半是探测/心跳，不是配置错误。</li>
 *   <li><b>共享密钥复用同一套 {@link WebhookGuard}</b>：Zabbix 与 Alertmanager 走同一条
 *       加固通道（token / HMAC / 限流），没有为第二源再造一套。差异留给部署侧的
 *       {@code devops.alert.webhook.secret} 取值（同一密钥即可——两端点都在鉴权白名单内）。</li>
 * </ol>
 *
 * <h3>接入方式（Zabbix 侧）</h3>
 * <pre>
 * 管理 → 媒介类型 → 创建媒介类型（类型：Webhook）
 *   参数：event_id={EVENT.ID} trigger_id={TRIGGER.ID} trigger_name={TRIGGER.NAME}
 *         event_name={EVENT.NAME} event_severity={EVENT.SEVERITY}
 *         event_status={EVENT.STATUS} event_value={EVENT.VALUE}
 *         event_date={EVENT.DATE} event_time={EVENT.TIME}
 *         host_name={HOST.NAME} host_ip={HOST.IP}
 *         trigger_description={TRIGGER.DESCRIPTION} item_value={ITEM.LASTVALUE}
 *         event_tags={EVENT.TAGSJSON}
 *   HTTP 端点：http://&lt;opsbrain&gt;/ai/api/v1/alerts/webhook/zabbix
 *   HTTP 头：X-Webhook-Token: &lt;ALERT_WEBHOOK_SECRET&gt;
 * 动作 → 操作 → 发送消息到「OpsBrain」，并在恢复操作里同样勾选
 * </pre>
 * 完整的键清单与命名纪律见 {@code docs/09-operations/告警接入命名规范.md}。
 *
 * @author OpsBrain AI
 * @since 2026-10-14
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/alerts/webhook/zabbix")
public class ZabbixWebhookController {

    private final AlertService alertService;
    private final WebhookGuard webhookGuard;

    /** 告警接收总开关（devops.alert.enabled）。关闭后仍返回 200 但跳过处理 */
    @Value("${devops.alert.enabled:true}")
    private boolean alertEnabled;

    public ZabbixWebhookController(AlertService alertService, WebhookGuard webhookGuard) {
        this.alertService = alertService;
        this.webhookGuard = webhookGuard;
    }

    /**
     * 接收 Zabbix 动作推送的单条事件（问题与恢复走同一 URL，靠负载里的
     * {@code event_status} 区分——Zabbix 的恢复操作配置麻烦，能少一处配置就少一处）。
     *
     * <p>body 按 {@code Map<String, Object>} 收、边界处再包成 {@link ZabbixWebhook}：
     * 这样请求体在 OpenAPI 里是内联对象（不给契约快照添命名 schema），
     * 形状校验仍然只发生在适配器一处。</p>
     */
    @PostMapping
    public ApiResponse<String> receive(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        ZabbixWebhook webhook = ZabbixWebhook.of(body);
        webhookGuard.verify(request);

        if (!alertEnabled) {
            log.warn("⏸️ [ZabbixWebhook] 告警接收已关闭（devops.alert.enabled=false），跳过处理");
            return ApiResponse.success("ok");
        }

        if (webhook.isEmpty()) {
            // 空负载 ≠ 配置错误：Zabbix 的「测试」按钮会发一份最小负载，
            // 返回非 200 会让接入方在自测阶段就卡住
            log.warn("🛜 [ZabbixWebhook] 收到空负载，跳过处理");
            return ApiResponse.success("ok");
        }

        log.info("🛜 [ZabbixWebhook] 收到 Zabbix 事件 | event_id={} | trigger={} | host={} | status={}",
                webhook.text("event_id"), webhook.text("trigger_name"),
                webhook.text("host_name"),
                webhook.text("event_status") != null ? webhook.text("event_status") : webhook.text("event_value"));

        try {
            alertService.processSourceWebhook(webhook);
        } catch (IllegalArgumentException e) {
            // 适配器给的说明已经是人话（缺哪个宏、怎么改），原样带回给 Zabbix 事件日志
            throw WebhookRejectedException.badRequest(e.getMessage());
        }
        return ApiResponse.success("ok");
    }

    /**
     * 与 {@code AlertWebhookController} 同款局部映射：密钥不对 401（重试无意义，
     * 必须让人发现）、限流 429 + Retry-After（退避后重投，告警不丢）、负载不合法 400。
     * <p>不复用全局 handler——那里把安全异常统一成 403，机器客户端无法据此决策。</p>
     */
    @ExceptionHandler(WebhookRejectedException.class)
    public ResponseEntity<ApiResponse<String>> handleRejected(WebhookRejectedException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getHttpStatus());
        if (ex.getRetryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return builder.body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }
}
