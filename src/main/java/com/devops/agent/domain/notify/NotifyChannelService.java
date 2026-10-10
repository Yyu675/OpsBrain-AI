package com.devops.agent.domain.notify;

import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 通知渠道配置服务——「页面配置、DB 权威、热生效」的裁决中心（批89 · 方案1）。
 *
 * <h3>三层回落（effective 是唯一裁决点）</h3>
 * <ol>
 *   <li><b>DB 行在</b> → {@code sys_notify_channel} 解密后的值，source=db
 *       （页面保存过即 DB 权威，yml/env 被覆盖——改完即生效，零重启）；</li>
 *   <li><b>无行</b> → 调用方传入的 yml/env bootstrap，source=yml
 *       （现状 DINGTALK_WEBHOOK 等环境变量部署零迁移成本，刻意不种子行）；</li>
 *   <li><b>都没配</b> → source=none，Notifier 以 MOCK 仅打印（既有三约定不破）。</li>
 * </ol>
 *
 * <h3>解密失败 fail-open 回落 bootstrap</h3>
 * {@link ApiKeyCrypt#decrypt} 主密钥变更会抛错——通知是旁路，不能因
 * 密钥轮换把整条通知链打死：catch → WARN + 用 yml/env 值兜底，
 * 并在 list 视图标 {@code needsRe-entry=true} 让运维看见「库里密文解不开，
 * 请重新保存一次」。宁可降级不可静默断（同三约定的「降级可见」精神）。
 *
 * <h3>webhook 打码（maskWebhook，与 mask 不同职责）</h3>
 * ApiKeyCrypt.mask 对整串头 6 尾 4——对 URL 会把 host 打掉、把 key 大头留下，
 * 方向反了。webhook 的敏感点是 <b>query 参数值</b>（access_token/key），
 * host+path 反而要给运维看（确认发到哪个机器人）：
 * {@code https://qyapi.weixin.qq.com/...?key=d24d****60a7}。
 */
@Service
public class NotifyChannelService {

    private static final Logger log = LoggerFactory.getLogger(NotifyChannelService.class);

    public static final String KEY_DINGTALK = "dingtalk";
    public static final String KEY_WECOM = "wecom";
    /** 告警接入 HMAC/secret 的展示与热改（复用同表存储；行存在=DB 权威，语义与渠道一致）。 */
    public static final String KEY_ALERT_INGEST = "alert-ingest";

    private static final List<String> CHANNEL_KEYS = List.of(KEY_DINGTALK, KEY_WECOM);

    /** 渠道裁决结果：webhook/secret 为解密后的可用明文；source ∈ db|yml|none。 */
    public record Effective(boolean enabled, String webhook, String secret, String source) {
        public boolean configured() {
            return enabled && webhook != null && !webhook.isBlank();
        }
    }

    /** 列表视图（打码；明文只走 reveal 端点）。 */
    public record NotifyChannelView(
            String channelKey, boolean enabled, boolean configured, String source,
            String webhookMasked, boolean hasSecret, boolean needsReEntry,
            String updatedBy, String updateTime) {}

    /** reveal 结果（明文——端点限 ADMIN + 访问日志留痕）。 */
    public record Revealed(String channelKey, String webhook, String secret) {}

    /**
     * 列表整屏视图（springdoc 反射 → 前端类型配 fieldContract 对账，吃自己狗粮）。
     * keyCryptConfigured=false 时 webhook/secret 以明文落库（ApiKeyCrypt 刻意降级）——
     * 这个事实必须在页面上看见，不能只躺日志（批89 清查 P0 项）。
     */
    public record NotifyChannelList(List<NotifyChannelView> channels, IngestSecretView ingestSecret,
                                    boolean keyCryptConfigured) {}

    /** 告警接入 secret 视图（只读打码；修改走 PUT /ingest-secret）。 */
    public record IngestSecretView(boolean configured, String source, String secretMasked,
                                   boolean hmacConfigured, String hint) {}

    /** 测试发送结果（record 化——Map 返回产 additionalProperties 弱契约，配不了对账）。 */
    public record TestResult(boolean success, String message, long latencyMs) {}

    private final NotifyChannelRepository repo;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.devops.agent.common.web.IngestSecretHolder ingestSecrets;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${MODEL_KEY_CRYPT_SECRET:}")
    private String cryptSecret;

    /** yml/env bootstrap（与 Notifier @Value 同源同默认——两侧必须同值，由同名 yml 键保证）。 */
    @Value("${devops.notify.dingtalk.enabled:false}")
    private boolean ymlDingTalkEnabled;
    @Value("${devops.notify.dingtalk.webhook:}")
    private String ymlDingTalkWebhook;
    @Value("${devops.notify.dingtalk.secret:}")
    private String ymlDingTalkSecret;
    @Value("${devops.notify.wecom.enabled:false}")
    private boolean ymlWeComEnabled;
    @Value("${devops.notify.wecom.webhook:}")
    private String ymlWeComWebhook;
    @Value("${devops.alert.webhook.secret:}")
    private String ymlIngestSecret;
    @Value("${devops.alert.webhook.hmac-secret:}")
    private String ymlIngestHmacSecret;

    public NotifyChannelService(NotifyChannelRepository repo,
                                com.fasterxml.jackson.databind.ObjectMapper mapper,
                                com.devops.agent.common.web.IngestSecretHolder ingestSecrets) {
        this.repo = repo;
        this.mapper = mapper;
        this.ingestSecrets = ingestSecrets;
    }

    /**
     * 启动初始化 holder（批89 热生效闭环）：三层回落终值进 holder，
     * 之后 WebhookGuard 全程读 holder——页面保存时 updateIngestSecret 直接 set。
     * 失败保底 yml 值，绝不因 DB 异常把告警鉴权链打死。
     */
    @jakarta.annotation.PostConstruct
    void initIngestHolder() {
        try {
            Effective e = effectiveIngestSecret();
            ingestSecrets.setToken(e.webhook());
            // HMAC 通道本批只读不改（Alertmanager v0.27 发不了自定义签名头=现网不用；
            // 页面编辑面留待真实 HMAC 客户端接入时再开）
            ingestSecrets.setHmac(ymlIngestHmacSecret);
            log.info("🔑 [NotifyChannel] 告警接入密钥 holder 初始化完成 | 密钥来源={} | hmac 已配置={}",
                    e.source(), ymlIngestHmacSecret != null && !ymlIngestHmacSecret.isBlank());
        } catch (RuntimeException ex) {
            log.warn("⚠️ [NotifyChannel] holder 初始化失败，回退环境变量：{}", ex.getMessage());
            ingestSecrets.setToken(ymlIngestSecret != null && !ymlIngestSecret.isBlank()
                    ? ymlIngestSecret : ymlIngestHmacSecret);
            ingestSecrets.setHmac(ymlIngestHmacSecret);
        }
    }

    // ==================== 三层回落裁决 ====================

    /**
     * 解析渠道有效配置。ymlWebhook/secret 即 Notifier 的 @Value 字段——
     * Notifier 每次发送时把自己读到的 bootstrap 传进来，本方法做 DB 覆盖。
     */
    public Effective effective(String channelKey, boolean ymlEnabled, String ymlWebhook, String ymlSecret) {
        Optional<NotifyChannelRepository.NotifyChannelRow> row = repo.findByKey(channelKey);
        if (row.isEmpty()) {
            return new Effective(ymlEnabled, ymlWebhook, ymlSecret, "yml");
        }
        String webhook;
        String secret;
        try {
            webhook = decryptOrRaw(row.get().webhookEnc());
            secret = decryptOrRaw(row.get().secretEnc());
        } catch (RuntimeException ex) {
            // 解密失败（主密钥变更/密文损坏）：回落 bootstrap，绝不静默断通知链
            log.warn("⚠️ [NotifyChannel] 密文解不开，回落环境变量（请在配置页重新保存一次）| key={} | {}",
                    channelKey, ex.getMessage());
            webhook = ymlWebhook;
            secret = ymlSecret;
        }
        return new Effective(row.get().enabled(), webhook, secret, "db");
    }

    /** alert-ingest secret 的有效值（行存在=DB 权威，回落双环境变量之一）。 */
    public Effective effectiveIngestSecret() {
        Optional<NotifyChannelRepository.NotifyChannelRow> row = repo.findByKey(KEY_ALERT_INGEST);
        if (row.isEmpty()) {
            String yml = (ymlIngestSecret != null && !ymlIngestSecret.isBlank()) ? ymlIngestSecret : ymlIngestHmacSecret;
            return new Effective(yml != null && !yml.isBlank(), yml, "", "yml");
        }
        try {
            String v = decryptOrRaw(row.get().webhookEnc());
            return new Effective(row.get().enabled() && v != null && !v.isBlank(), v, "", "db");
        } catch (RuntimeException ex) {
            log.warn("⚠️ [NotifyChannel] alert-ingest 密文解不开，回落环境变量：{}", ex.getMessage());
            String yml = (ymlIngestSecret != null && !ymlIngestSecret.isBlank()) ? ymlIngestSecret : ymlIngestHmacSecret;
            return new Effective(yml != null && !yml.isBlank(), yml, "", "yml");
        }
    }

    private String decryptOrRaw(String stored) {
        if (stored == null || stored.isBlank()) return "";
        return ApiKeyCrypt.decrypt(stored, cryptSecret);
    }

    // ==================== 页面操作 ====================

    /** 两渠道视图 + 告警接入 secret 视图（打码一次给齐，页面一屏装配）。 */
    public NotifyChannelList listViews() {
        List<NotifyChannelView> channels = List.of(
                viewOf(KEY_DINGTALK, ymlDingTalkEnabled, ymlDingTalkWebhook, ymlDingTalkSecret),
                viewOf(KEY_WECOM, ymlWeComEnabled, ymlWeComWebhook, null));
        Effective ingest = effectiveIngestSecret();
        IngestSecretView ingestView = new IngestSecretView(
                ingest.configured(), ingest.source(), ApiKeyCrypt.mask(ingest.webhook()),
                ymlIngestHmacSecret != null && !ymlIngestHmacSecret.isBlank(),
                "修改需与 Alertmanager webhook_configs 同步，否则告警静默断链");
        return new NotifyChannelList(channels, ingestView, ApiKeyCrypt.enabled(cryptSecret));
    }

    private NotifyChannelView viewOf(String key, boolean ymlEnabled, String ymlWebhook, String ymlSecret) {
        Effective eff = effective(key, ymlEnabled, ymlWebhook, ymlSecret);
        boolean needsReEntry = false;
        Optional<NotifyChannelRepository.NotifyChannelRow> row = repo.findByKey(key);
        String updatedBy = null;
        String updateTime = null;
        if (row.isPresent()) {
            updatedBy = row.get().updatedBy();
            updateTime = row.get().updateTime() == null ? null : row.get().updateTime().toString();
            try {
                decryptOrRaw(row.get().webhookEnc());
            } catch (RuntimeException ex) {
                needsReEntry = true;
            }
        }
        return new NotifyChannelView(key, eff.enabled(), eff.configured(), eff.source(),
                maskWebhook(eff.webhook()), eff.secret() != null && !eff.secret().isBlank(),
                needsReEntry, updatedBy, updateTime);
    }

    /**
     * 保存（PUT 语义：null=不动该字段，""=清空，非空=覆盖）。
     * 行不存在时按本次字段建行——只要调用过保存，该渠道即转入 DB 管理。
     */
    public NotifyChannelView update(String channelKey, Boolean enabled, String webhook, String secret, String updatedBy) {
        requireKnownChannel(channelKey);
        boolean ymlEnabled = KEY_DINGTALK.equals(channelKey) ? ymlDingTalkEnabled : ymlWeComEnabled;
        String ymlWebhook = KEY_DINGTALK.equals(channelKey) ? ymlDingTalkWebhook : ymlWeComWebhook;
        String ymlSecret = KEY_DINGTALK.equals(channelKey) ? ymlDingTalkSecret : null;

        Optional<NotifyChannelRepository.NotifyChannelRow> row = repo.findByKey(channelKey);
        boolean newEnabled = enabled != null ? enabled : row.map(NotifyChannelRepository.NotifyChannelRow::enabled).orElse(ymlEnabled);
        String newWebhookRaw;
        if (webhook == null) {
            newWebhookRaw = row.isPresent()
                    ? decryptOrRaw(row.get().webhookEnc())
                    : ymlWebhook;
        } else {
            newWebhookRaw = webhook; // "" = 清空
        }
        String newSecretRaw;
        if (secret == null) {
            newSecretRaw = row.isPresent()
                    ? decryptOrRaw(row.get().secretEnc())
                    : ymlSecret;
        } else {
            newSecretRaw = secret;
        }

        if (newEnabled && (newWebhookRaw == null || newWebhookRaw.isBlank())) {
            throw new IllegalArgumentException("启用通知渠道必须填写 webhook 地址");
        }
        if (newWebhookRaw != null && !newWebhookRaw.isBlank()
                && !(newWebhookRaw.startsWith("https://") || newWebhookRaw.startsWith("http://"))) {
            throw new IllegalArgumentException("webhook 必须是 http(s) 地址");
        }

        repo.upsert(channelKey, newEnabled,
                ApiKeyCrypt.encrypt(newWebhookRaw, cryptSecret),
                ApiKeyCrypt.encrypt(newSecretRaw, cryptSecret),
                updatedBy);
        log.info("🔔 [NotifyChannel] 渠道已保存（DB 权威，即时生效）| key={} | enabled={} | webhook={} | by={}",
                channelKey, newEnabled, maskWebhook(newWebhookRaw), updatedBy);
        if (!ApiKeyCrypt.enabled(cryptSecret) && newWebhookRaw != null && !newWebhookRaw.isBlank()) {
            log.warn("⚠️ [NotifyChannel] MODEL_KEY_CRYPT_SECRET 未配置——webhook 以明文落库（能用，但建议配密钥）");
        }
        return viewOf(channelKey, ymlEnabled, ymlWebhook, ymlSecret);
    }

    /** 保存告警接入 secret（复用 alert-ingest 行；保存即 holder.set 热生效）。 */
    public IngestSecretView updateIngestSecret(String secret, String updatedBy) {
        String toStore = (secret == null || secret.isBlank()) ? "" : secret;
        repo.upsert(KEY_ALERT_INGEST, !toStore.isBlank(),
                ApiKeyCrypt.encrypt(toStore, cryptSecret), "", updatedBy);
        // 热生效闭环：DB 落盘后 holder 直接换值，下一个 webhook 请求即用新密钥
        ingestSecrets.setToken(toStore);
        log.info("🔑 [NotifyChannel] 告警接入密钥已更新（by={}）——⚠️ 记得同步 Alertmanager webhook_configs", updatedBy);
        Effective ingest = effectiveIngestSecret();
        return new IngestSecretView(ingest.configured(), ingest.source(),
                ApiKeyCrypt.mask(toStore),
                ymlIngestHmacSecret != null && !ymlIngestHmacSecret.isBlank(),
                "修改需与 Alertmanager webhook_configs 同步，否则告警静默断链");
    }

    /** reveal 明文（ADMIN 端点专用；访问即日志留痕）。 */
    public Revealed reveal(String channelKey) {
        requireKnownChannel(channelKey);
        boolean ymlEnabled = KEY_DINGTALK.equals(channelKey) ? ymlDingTalkEnabled : ymlWeComEnabled;
        String ymlWebhook = KEY_DINGTALK.equals(channelKey) ? ymlDingTalkWebhook : ymlWeComWebhook;
        String ymlSecret = KEY_DINGTALK.equals(channelKey) ? ymlDingTalkSecret : null;
        Effective eff = effective(channelKey, ymlEnabled, ymlWebhook, ymlSecret);
        log.info("👁️ [NotifyChannel] 明文查看 | key={} | source={}", channelKey, eff.source());
        return new Revealed(channelKey, eff.webhook(), eff.secret());
    }

    public Revealed revealIngest() {
        Effective eff = effectiveIngestSecret();
        log.info("👁️ [NotifyChannel] 明文查看 | key=alert-ingest | source={}", eff.source());
        return new Revealed(KEY_ALERT_INGEST, eff.webhook(), "");
    }

    /**
     * 发送测试消息（同步，直返结果给页面——探针就是要「现在立刻」的答案）。
     * 走 NotifyPayloads 单源构造：探针绿 = 真实消息格式也对。
     */
    public TestResult sendTest(String channelKey) {
        requireKnownChannel(channelKey);
        boolean ymlEnabled = KEY_DINGTALK.equals(channelKey) ? ymlDingTalkEnabled : ymlWeComEnabled;
        String ymlWebhook = KEY_DINGTALK.equals(channelKey) ? ymlDingTalkWebhook : ymlWeComWebhook;
        String ymlSecret = KEY_DINGTALK.equals(channelKey) ? ymlDingTalkSecret : null;
        Effective eff = effective(channelKey, ymlEnabled, ymlWebhook, ymlSecret);
        if (!eff.configured()) {
            return new TestResult(false, "渠道未配置或未启用——先填写 webhook 并打开开关", 0);
        }
        NotifyMessage msg = NotifyMessage.normal("OpsBrain 连通性测试",
                "通知渠道「" + channelKey + "」配置页发送的测试消息——收到即配置有效。");
        long t0 = System.currentTimeMillis();
        try {
            String url = KEY_DINGTALK.equals(channelKey)
                    ? NotifyPayloads.dingtalkSignedUrl(eff.webhook(), eff.secret())
                    : eff.webhook();
            byte[] body = KEY_DINGTALK.equals(channelKey)
                    ? NotifyPayloads.dingtalkPayload(msg, mapper)
                    : NotifyPayloads.wecomPayload(msg, mapper);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long ms = System.currentTimeMillis() - t0;
            boolean ok = NotifyPayloads.accepted(resp.statusCode(), resp.body());
            return new TestResult(ok,
                    ok ? "已送达（渠道回执 errcode=0，" + ms + "ms）"
                       : "渠道拒收：HTTP " + resp.statusCode() + " " + abbrev(resp.body()),
                    ms);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            // 详情已回传页面（TestResult.message）；日志只记异常类型——消息里可能含
            // webhook 完整 URL（token 在 query），落日志即泄漏（批89 静默 catch 契约修正）
            log.warn("[NotifyChannel] 测试发送失败 | channel={} | {}（详情已回传页面）",
                    channelKey, e.getClass().getSimpleName());
            return new TestResult(false, "发送失败：" + abbrev(e.getMessage()), ms);
        }
    }

    private void requireKnownChannel(String channelKey) {
        if (!CHANNEL_KEYS.contains(channelKey)) {
            throw new IllegalArgumentException("未知通知渠道：" + channelKey + "（可选：" + CHANNEL_KEYS + "）");
        }
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * webhook 打码：host+path 可见（运维要确认发到哪），query 参数值打码（敏感在参数值）。
     * 无 query 参数时退化为 ApiKeyCrypt.mask 整串。
     *
     * <p>参数名命中 {@link #SENSITIVE_PARAMS} 时只留尾 4 位（连头 4 位都不给——
     * token 类值常常前缀固定如 {@code 8f3a-}，露头等于送字典半边；
     * GitHub 式尾 4 位足够比对「是不是我贴的那个」）。</p>
     */
    public static String maskWebhook(String url) {
        if (url == null || url.isBlank()) return null;
        int q = url.indexOf('?');
        if (q < 0) return ApiKeyCrypt.mask(url);
        String base = url.substring(0, q + 1);
        String query = url.substring(q + 1);
        StringBuilder sb = new StringBuilder();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) { sb.append(pair); continue; }
            if (sb.length() > 0) sb.append('&');
            String name = pair.substring(0, eq);
            sb.append(name).append('=');
            String v = pair.substring(eq + 1);
            if (SENSITIVE_PARAMS.contains(name.toLowerCase())) {
                sb.append(v.length() <= 4 ? "****" : "****" + v.substring(v.length() - 4));
            } else if (v.length() <= 4) {
                sb.append("****");
            } else {
                sb.append(v, 0, 4).append("****").append(v.substring(v.length() - 4));
            }
        }
        return base + sb;
    }

    /** query 参数名黑名单：值是凭据形态的，打码不留头。 */
    private static final java.util.Set<String> SENSITIVE_PARAMS = java.util.Set.of(
            "key", "token", "secret", "sign", "signature", "sig",
            "password", "passwd", "access_token", "api_key", "apikey");
}
