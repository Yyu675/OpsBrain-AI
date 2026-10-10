package com.devops.agent.common.web;

/**
 * 告警接入 secret 的运行期持有者（批89 · 热生效接线）。
 *
 * <h3>为什么存在（分层考量）</h3>
 * 页面改 secret 要在 {@link WebhookGuard} 校验时即时生效，但 WebhookGuard 在
 * common/web（横切层）——横切层不允许依赖 domain/通知配置服务（全仓 common→domain
 * 依赖零先例，会把「横切在下」的地基掀了）。反转依赖：
 * <ul>
 *   <li>domain/notify/NotifyChannelService 保存成功后 {@code set}（domain→common ✓ 标准方向）；</li>
 *   <li>启动时 {@code @PostConstruct} 用三层回落终值初始化（DB 行在→DB，否则 yml）；</li>
 *   <li>WebhookGuard（同包）直接读——不依赖任何 domain 类型。</li>
 * </ul>
 *
 * <h3>null 语义</h3>
 * 字段默认 <b>null=未初始化</b>（区分「初始化成空串=真没配」与「还没人设过」）。
 * WebhookGuard 字段注入 holder 为 null（契约测试直接 new）→ 回落自己的 @Value 字段，
 * 既有测试零改。初始化后 holder 永不回 null，@Value 字段自然退位为测试专用。
 *
 * <h3>线程安全</h3>
 * volatile 单值引用——写方（配置页保存）低频，读方（每个 webhook 请求）高频，
 * 无需锁。
 */
@org.springframework.stereotype.Component
public class IngestSecretHolder {

    /** token 通道（X-Webhook-Token 校验用的共享密钥）。null=未初始化。 */
    private volatile String tokenSecret;
    /** HMAC 通道（X-Webhook-Signature 用）。null=未初始化。 */
    private volatile String hmacSecret;

    public String token() {
        return tokenSecret;
    }

    public String hmac() {
        return hmacSecret;
    }

    public void setToken(String secret) {
        this.tokenSecret = secret;
    }

    public void setHmac(String secret) {
        this.hmacSecret = secret;
    }
}
