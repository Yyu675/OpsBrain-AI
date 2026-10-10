package com.devops.agent.domain.notify;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 企业微信群机器人通知发送器（批89 · 方案1——通知从「假发送」变「真送达」的钥匙）。
 *
 * <h3>与钉钉实现的同构关系</h3>
 * 结构整套镜像 {@link DingTalkNotifier}（三条约定/异步 executor/MOCK 降级/
 * 送达回执埋点/每发送时 effective 三层回落）——唯一差异是渠道协议：
 * <ul>
 *   <li>普通消息 markdown（{@code markdown.content}，标题并入正文首行——企微无 title 字段）；</li>
 *   <li>紧急消息走 text + {@code mentioned_list:[@all]}（企微 markdown 不支持 @）；</li>
 *   <li>成功判据同构：HTTP 200 + {@code errcode==0}；</li>
 *   <li>安全设置按「自定义关键词/IP 白名单」交付（key 直连无签名参数——
 *       当前真实 key 形态 {@code ?key=xxx} 无 sign 即实证；「加签」模式待真实需求再按官方 genSign 实现）。</li>
 * </ul>
 *
 * <h3>限流（企微官方 20 条/分钟）</h3>
 * 不在渠道层加节流器：上游告警链已有风暴模式聚合 + 指纹去重压流量，
 * 通知发送走 best-effort 单线程池天然串行限速；企微 429 由
 * {@link NotifyPayloads#accepted} 判非 → notifyDelivery(false) 计数 →
 * 看门板/OpsBrainNotifyDeliveryFail 元告警可见（既有闭环，不重复造轮子）。
 *
 * @author OpsBrain AI
 * @since 2026-10-06
 */
@Slf4j
@Component
public class WeComNotifier implements Notifier {

    /** 通知总开关。默认关闭——需运维在配置页（或环境变量）填 webhook 后显式开启 */
    @Value("${devops.notify.wecom.enabled:false}")
    private boolean enabled;

    /** 企业微信群机器人 Webhook URL（key 在 query 参数里，等同敏感凭据） */
    @Value("${devops.notify.wecom.webhook:}")
    private String webhook;

    /** 送达回执指标（ROI#1 同款，字段注入可缺席——通知主链不依赖指标存在） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.devops.agent.infrastructure.metrics.BusinessMetrics businessMetrics;

    /** DB 权威源服务（批89）——字段注入：契约测试直接 new 单参实例时为 null，回落 @Value */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NotifyChannelService channelService;

    /**
     * 发送专用线程池：单线程够用（通知量小）。
     * best-effort 语义同 DingTalkNotifier 注释——丢任务代价低、不拖主链。
     */
    private final ExecutorService sendExecutor =
            com.devops.agent.infrastructure.concurrent.ManagedExecutors
                    .forBestEffort("wecom-notify", 1, 200);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public WeComNotifier(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        com.devops.agent.infrastructure.concurrent.ManagedExecutors
                .shutdownGracefully(sendExecutor, "wecom-notify", 3);
    }

    @PostConstruct
    void logStatus() {
        NotifyChannelService.Effective eff = resolve();
        switch (eff.source()) {
            case "db" -> log.info("🔔 [WeCom] 通知已开启（DB 权威源）| configured={}", eff.configured());
            case "yml" -> {
                if (!enabled) {
                    log.info("🔕 [WeCom] 通知已关闭（devops.notify.wecom.enabled=false），将以 MOCK 方式仅打印日志");
                } else if (webhook == null || webhook.isBlank()) {
                    log.warn("⚠️ [WeCom] 通知已开启但未配 webhook，退化为仅打印日志");
                } else {
                    log.info("🔔 [WeCom] 通知已开启 | 源=环境配置");
                }
            }
            default -> log.info("🔕 [WeCom] 未配置，MOCK 模式");
        }
    }

    /** 渠道标识：多渠道并存时按此选用，日志与审计里标明发到了哪 */
    @Override
    public String channel() {
        return "wecom";
    }

    /**
     * 是否真正可用：DB/yml 裁决后开关已开且 webhook 已配。
     * 与「发送成功」两回事——只回答「配没配」，连通性由实际发送的响应判断。
     */
    @Override
    public boolean available() {
        return resolve().configured();
    }

    /** 三层回落裁决（每发送时读——页面改完即生效，灰度期零重启）。 */
    private NotifyChannelService.Effective resolve() {
        if (channelService == null) {
            // 直接 new 的场景（契约测试）：@Value 字段即真相，未配置=MOCK
            return new NotifyChannelService.Effective(enabled, webhook, null, "yml");
        }
        return channelService.effective(NotifyChannelService.KEY_WECOM, enabled, webhook, null);
    }

    /** 异步发送通知（失败旁路，不阻塞调用方） */
    @Override
    public void send(NotifyMessage msg) {
        if (msg == null) return;
        CompletableFuture.runAsync(() -> doSend(msg), sendExecutor)
                .exceptionally(ex -> {
                    log.warn("⚠️ [WeCom] 通知发送异常（已忽略，不影响主流程）: {}", ex.getMessage());
                    return null;
                });
    }

    private void doSend(NotifyMessage msg) {
        NotifyChannelService.Effective eff = resolve();
        // 未开启 / 未配 webhook：MOCK 语义，仅打印（开发期可见内容与触发时机）
        if (!eff.configured()) {
            log.info("🔕 [WeCom-MOCK] {}{}\n{}",
                    msg.urgent() ? "【紧急】" : "", msg.title(), msg.markdown());
            return;
        }

        try {
            byte[] body = NotifyPayloads.wecomPayload(msg, objectMapper);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(eff.webhook()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (NotifyPayloads.accepted(resp.statusCode(), resp.body())) {
                log.info("✅ [WeCom] 通知已推送 | {}", msg.title());
                if (businessMetrics != null) businessMetrics.notifyDelivery(true);
            } else {
                log.warn("⚠️ [WeCom] 推送返回异常（已忽略）| status={} | body={}",
                        resp.statusCode(), resp.body());
                if (businessMetrics != null) businessMetrics.notifyDelivery(false);
            }
        } catch (Exception e) {
            // 任何异常都不外抛——通知失败绝不影响告警与工单主流程（三约定之一）
            log.warn("⚠️ [WeCom] 推送失败（已忽略，不影响主流程）: {}", e.getMessage());
            if (businessMetrics != null) businessMetrics.notifyDelivery(false);
        }
    }
}
