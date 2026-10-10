package com.devops.agent.domain.notify;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知消息 → 渠道载荷的<b>单源</b>构造器（批89 从 DingTalkNotifier 抽出）。
 *
 * <p>为什么抽：配置页的「发送测试消息」探针与真实发送必须用<b>同一套</b>
 * payload 构造——两套实现必然在某次改动里漂移成「测试能过、真发格式错」
 * 或反过来（同「单源字典」判例：重复实现先在，漂移只是时间问题）。</p>
 *
 * <p>测试消息复用 {@link NotifyMessage} 正常通道：探针发的就是一条真实
 * markdown/text 消息，格式错会被渠道亲口拒绝——这正是探针要测的。</p>
 */
public final class NotifyPayloads {

    private NotifyPayloads() {}

    /**
     * 钉钉 markdown 卡片；urgent 时正文追加 @所有人 + at.isAtAll。
     * 钉钉成功判据：HTTP 200 且 body 含 {@code "errcode":0}。
     */
    public static byte[] dingtalkPayload(NotifyMessage msg, ObjectMapper mapper) throws Exception {
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("title", msg.title());
        markdown.put("text", msg.urgent() ? msg.markdown() + "\n\n@所有人" : msg.markdown());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdown);
        if (msg.urgent()) {
            payload.put("at", Map.of("isAtAll", true));
        }
        return mapper.writeValueAsBytes(payload);
    }

    /**
     * 钉钉加签 URL：未配 secret 原样返回。
     * sign = urlEncode(base64(HmacSHA256(key=secret, data=timestamp+"\n"+secret)))。
     */
    public static String dingtalkSignedUrl(String webhook, String secret) throws Exception {
        if (secret == null || secret.isBlank()) return webhook;
        long timestamp = System.currentTimeMillis();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
        return webhook + (webhook.contains("?") ? "&" : "?")
                + "timestamp=" + timestamp + "&sign=" + sign;
    }

    /**
     * 企业微信 markdown；urgent 改走 text + mentioned_list=@all
     * （企微 markdown 不支持 @，@全员只能走 text 类型——官方文档约束）。
     * 安全设置按「自定义关键词/IP 白名单」交付（key 直连无签名参数）；
     * 「加签」模式待有真实需求再按官方 genSign 实现（当前 key 形态无 sign 参数=实证）。
     */
    public static byte[] wecomPayload(NotifyMessage msg, ObjectMapper mapper) throws Exception {
        if (msg.urgent()) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("content", "【紧急】" + msg.title() + "\n" + msg.markdown());
            text.put("mentioned_list", List.of("@all"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("msgtype", "text");
            payload.put("text", text);
            return mapper.writeValueAsBytes(payload);
        }
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("content", "**" + msg.title() + "**\n" + msg.markdown());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdown);
        return mapper.writeValueAsBytes(payload);
    }

    /** 渠道成功判据（钉钉/企微同构：HTTP 200 + errcode==0）。 */
    public static boolean accepted(int httpStatus, String body) {
        return httpStatus == 200 && body != null && body.contains("\"errcode\":0");
    }
}
