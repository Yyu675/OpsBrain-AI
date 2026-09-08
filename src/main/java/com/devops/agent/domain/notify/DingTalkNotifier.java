package com.devops.agent.domain.notify;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 钉钉自定义机器人通知发送器（L2 通知渠道，方向二）
 *
 * <h3>职责</h3>
 * 把 {@link NotifyMessage} 以 markdown 卡片推送到钉钉群机器人。承接蓝图 §二
 * 「P0/P1 高危告警通过钉钉一键弹窗强提醒值班 SRE」。
 *
 * <h3>三条铁律</h3>
 * <ul>
 *   <li><b>失败旁路，绝不阻塞主流程</b>：告警建单、工单状态变更是主业务，通知是旁路增值。
 *       任何异常（网络、加签、钉钉限流）只记 WARN，不外抛——与告警/建单解耦（同 6.34 契约）。</li>
 *   <li><b>异步发送</b>：走独立 daemon 线程池，不占用调用方线程（告警回调/定时扫描）。</li>
 *   <li><b>开关默认关</b>：{@code devops.notify.dingtalk.enabled=false}。未配 webhook 或关闭时
 *       仅打印日志（MOCK 语义），需运维显式填 .env 并开启。</li>
 * </ul>
 *
 * <h3>加签</h3>
 * 钉钉安全设置「加签」模式：{@code sign = urlEncode(base64(HmacSHA256(timestamp+"\n"+secret, secret)))}，
 * 附加到 webhook。未配 secret 时不加签（适用于「自定义关键词」或「IP 白名单」安全模式）。
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@Component
public class DingTalkNotifier implements Notifier {

    /** 通知总开关。默认关闭——需运维显式填 webhook 并开启 */
    @Value("${devops.notify.dingtalk.enabled:false}")
    private boolean enabled;

    /** 钉钉机器人 Webhook URL（从环境变量读，不写入代码/配置文件） */
    @Value("${devops.notify.dingtalk.webhook:}")
    private String webhook;

    /** 加签密钥（钉钉安全设置「加签」模式；为空则不加签） */
    @Value("${devops.notify.dingtalk.secret:}")
    private String secret;

    private final ObjectMapper objectMapper;

    /**
     * 发送专用线程池：单线程够用（通知量小）。
     *
     * <p>改用 {@link com.devops.agent.infrastructure.concurrent.ManagedExecutors#forBestEffort}
     * 而非 {@code Executors.newSingleThreadExecutor}：后者内部是<b>无界队列</b>
     * （容量 {@code Integer.MAX_VALUE}），钉钉侧持续超时时任务会无限堆积直到 OOM，
     * 且堆积期间毫无征兆。</p>
     *
     * <p>选 best-effort 而非 critical-writes：通知丢了只影响体验，
     * 不该用 CallerRuns 把主链路（告警回调、定时扫描）拖慢。
     * 这与审计池的取舍相反，依据是<b>丢掉这个任务的代价</b>。</p>
     */
    private final ExecutorService sendExecutor =
            com.devops.agent.infrastructure.concurrent.ManagedExecutors
                    .forBestEffort("dingtalk-notify", 1, 200);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 优雅停机：给在途通知留出发送时间
     *
     * <p>daemon 线程不阻塞 JVM 退出，队列里排队的通知会随进程消失。
     * 对通知而言丢失的代价低于审计，故等待时间取 3 秒（审计是 5 秒）——
     * 但仍要等：应用停止往往伴随发布或故障处置，
     * 此刻积压的恰恰是「服务即将不可用」这类最该送达的消息。</p>
     */
    @PreDestroy
    public void shutdown() {
        com.devops.agent.infrastructure.concurrent.ManagedExecutors
                .shutdownGracefully(sendExecutor, "dingtalk-notify", 3);
    }

    public DingTalkNotifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void logStatus() {
        if (!enabled) {
            log.info("🔕 [DingTalk] 通知已关闭（devops.notify.dingtalk.enabled=false），将以 MOCK 方式仅打印日志");
        } else if (webhook == null || webhook.isBlank()) {
            log.warn("⚠️ [DingTalk] 通知已开启但未配 webhook（devops.notify.dingtalk.webhook 为空），退化为仅打印日志");
        } else {
            log.info("🔔 [DingTalk] 通知已开启 | 加签={}", (secret != null && !secret.isBlank()) ? "是" : "否");
        }
    }

    /** 渠道标识：多渠道并存时按此选用，日志与审计里标明发到了哪 */
    @Override
    public String channel() {
        return "dingtalk";
    }

    /**
     * 是否真正可用：开关已开且 webhook 已配。
     *
     * <p>与「发送成功」是两回事——这里只回答「配没配」，
     * 连通性由实际发送时的响应判断。区分这两者是为了让
     * 健康检查能说清「没配」还是「配了但连不通」。</p>
     */
    @Override
    public boolean available() {
        return enabled && webhook != null && !webhook.isBlank();
    }

    /**
     * 异步发送通知（失败旁路，不阻塞调用方）
     *
     * @param msg 通知消息
     */
    @Override
    public void send(NotifyMessage msg) {
        if (msg == null) return;
        // 异步：不占用调用方线程（告警回调线程/定时扫描线程）
        CompletableFuture.runAsync(() -> doSend(msg), sendExecutor)
                .exceptionally(ex -> {
                    // 兜底：runAsync 拒绝或 doSend 逃逸异常都不外抛
                    log.warn("⚠️ [DingTalk] 通知发送异常（已忽略，不影响主流程）: {}", ex.getMessage());
                    return null;
                });
    }

    private void doSend(NotifyMessage msg) {
        // 未开启 / 未配 webhook：MOCK 语义，仅打印（便于开发期验证内容与触发时机）
        if (!enabled || webhook == null || webhook.isBlank()) {
            log.info("🔕 [DingTalk-MOCK] {}{}\n{}",
                    msg.urgent() ? "【紧急】" : "", msg.title(), msg.markdown());
            return;
        }

        try {
            String url = buildSignedUrl();
            byte[] body = buildPayload(msg);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 钉钉成功返回 {"errcode":0,...}；errcode!=0 记 WARN（如限流 130101、加签失败 310000）
            if (resp.statusCode() == 200 && resp.body() != null && resp.body().contains("\"errcode\":0")) {
                log.info("✅ [DingTalk] 通知已推送 | {}", msg.title());
            } else {
                log.warn("⚠️ [DingTalk] 推送返回异常（已忽略）| status={} | body={}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            // 网络/加签/序列化任何异常都不外抛——通知失败绝不影响告警与工单主流程
            log.warn("⚠️ [DingTalk] 推送失败（已忽略，不影响主流程）: {}", e.getMessage());
        }
    }

    /** 构造 markdown 卡片 JSON；urgent=true 时 @所有人 */
    private byte[] buildPayload(NotifyMessage msg) throws Exception {
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("title", msg.title());
        // urgent 时正文追加 @所有人 提示（钉钉 markdown 内 @all 需配合 at.isAtAll）
        markdown.put("text", msg.urgent() ? msg.markdown() + "\n\n@所有人" : msg.markdown());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdown);

        if (msg.urgent()) {
            Map<String, Object> at = new LinkedHashMap<>();
            at.put("isAtAll", true);
            payload.put("at", at);
        }
        return objectMapper.writeValueAsBytes(payload);
    }

    /** 加签：未配 secret 则原样返回 webhook */
    private String buildSignedUrl() throws Exception {
        if (secret == null || secret.isBlank()) {
            return webhook;
        }
        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(
                java.util.Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
        String sep = webhook.contains("?") ? "&" : "?";
        return webhook + sep + "timestamp=" + timestamp + "&sign=" + sign;
    }
}
