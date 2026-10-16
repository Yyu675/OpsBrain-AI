package com.devops.agent.domain.notify;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(NotifyPayloads.class);

    private NotifyPayloads() {}

    /**
     * 钉钉 markdown 卡片；urgent 时正文追加 @所有人 + at.isAtAll。
     * 钉钉成功判据：HTTP 200 且 body 含 {@code "errcode":0}。
     */
    private static final java.util.regex.Pattern ACTION_LINK_PATTERN =
            java.util.regex.Pattern.compile("\\[([^\\]]+)\\]\\((https://[^\\)]+)\\)");

    /**
     * 钉钉卡片：优先检测正文末尾的受信任 HTTPS 操作回链（如「查看告警并处理」、「查看工单」），
     * 存在时自动升格为原生独立 ActionCard 交互按钮卡片；无回链或不匹配时安全回退为标准 Markdown 卡片。
     * urgent 时追加 @所有人 强提醒与 at.isAtAll。
     */
    public static byte[] dingtalkPayload(NotifyMessage msg, ObjectMapper mapper) throws Exception {
        String rawMarkdown = msg.markdown();
        List<Map<String, String>> buttons = extractActionButtons(rawMarkdown);

        Map<String, Object> payload = new LinkedHashMap<>();
        if (!buttons.isEmpty() && buttons.size() <= 3) {
            String cleanText = ACTION_LINK_PATTERN.matcher(rawMarkdown).replaceAll("").trim();
            Map<String, Object> actionCard = new LinkedHashMap<>();
            actionCard.put("title", msg.title());
            actionCard.put("text", msg.urgent() ? cleanText + "\n\n@所有人" : cleanText);
            actionCard.put("btnOrientation", "0");
            actionCard.put("btns", buttons);
            payload.put("msgtype", "actionCard");
            payload.put("actionCard", actionCard);
        } else {
            Map<String, Object> markdown = new LinkedHashMap<>();
            markdown.put("title", msg.title());
            markdown.put("text", msg.urgent() ? rawMarkdown + "\n\n@所有人" : rawMarkdown);
            payload.put("msgtype", "markdown");
            payload.put("markdown", markdown);
        }

        if (msg.urgent()) {
            payload.put("at", Map.of("isAtAll", true));
        }
        return mapper.writeValueAsBytes(payload);
    }

    private static List<Map<String, String>> extractActionButtons(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        java.util.regex.Matcher matcher = ACTION_LINK_PATTERN.matcher(markdown);
        List<Map<String, String>> btns = new ArrayList<>();
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            String url = matcher.group(2).trim();
            btns.add(Map.of("title", title, "actionURL", url));
        }
        return btns;
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
        markdown.put("content", "**" + msg.title() + "**\n" + adaptWeComMarkdown(msg.markdown()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdown);
        return mapper.writeValueAsBytes(payload);
    }

    /** 渠道成功判据（钉钉/企微同构：HTTP 200 + errcode==0）。 */
    public static boolean accepted(int httpStatus, String body) {
        if (httpStatus != 200 || body == null || body.isBlank()) {
            return false;
        }
        // F4（2026-10-15 实弹抓到）：原实现是报文串匹配 body.contains("\"errcode\":0")。
        // 真实钉钉/企微回紧凑 JSON 所以线上一直"能用"，但等价的合法回执
        // （多一个空格、键序不同、被网关重排）会被判「渠道拒收」——本机假接收端
        // 首跑就撞上：{"errcode": 0, "errmsg": "ok"} 被判拒收。
        // 改为结构化解析：errcode 字段存在且为 0 才算送达。
        try {
            com.fasterxml.jackson.databind.JsonNode node = ACCEPT_MAPPER.readTree(body);
            com.fasterxml.jackson.databind.JsonNode errcode = node == null ? null : node.get("errcode");
            if (errcode != null) {
                return errcode.asInt(Integer.MIN_VALUE) == 0;
            }
            // 无 errcode 字段：不是钉钉/企微回执格式，保守判未送达
            // （宁可多发一次告警，也不把没送达说成送达）
            return false;
        } catch (Exception parseFailure) {
            // 非 JSON 体：渠道可能改了回执格式——保守判未送达，**并且留下线索**。
            // 这里必须自己记日志：调用方只看到「未送达」，现场（渠道到底回了什么）
            // 只有这一处能留下——静默 catch 会造出「渠道拒收但没人知道为什么」。
            log.warn("⚠️ [Notify] 渠道回执非 JSON，按未送达处理 | status={} | body={}",
                    httpStatus, body.length() > 200 ? body.substring(0, 200) + "…(截断)" : body);
            return false;
        }
    }


    /**
     * 将通用 Markdown 中的状态/恢复标签转为企业微信原生带色字体。
     * <p>企微 Markdown 支持标准 &lt;font color="warning/info/comment"&gt; 颜色标签：
     * 告警中标红（warning），已恢复标绿（info），辅助状态标灰色（comment）。</p>
     */
    static String adaptWeComMarkdown(String rawMarkdown) {
        if (rawMarkdown == null || rawMarkdown.isBlank()) {
            return rawMarkdown;
        }
        return rawMarkdown
                .replace("告警中（PROBLEM）", "<font color=\"warning\">告警中（PROBLEM）</font>")
                .replace("监控恢复（OK）", "<font color=\"info\">监控恢复（OK）</font>")
                .replace("P0 灾难级（Disaster）", "<font color=\"warning\">P0 灾难级（Disaster）</font>")
                .replace("P1 严重级（High）", "<font color=\"warning\">P1 严重级（High）</font>")
                .replace("P2 一般严重（Average）", "<font color=\"comment\">P2 一般严重（Average）</font>")
                .replace("P3 警告级（Warning）", "<font color=\"comment\">P3 警告级（Warning）</font>")
                .replace("P4 提示级（Info）", "<font color=\"comment\">P4 提示级（Info）</font>")
                .replace("证据不足，暂无法形成可靠根因结论", "<font color=\"warning\">证据不足，暂无法形成可靠根因结论</font>")
                .replace("证据薄弱，诊断结果需人工复核", "<font color=\"warning\">证据薄弱，诊断结果需人工复核</font>")
                .replace("取证完成；诊断结论及其依据请查看详情，不等于根因已确认", "<font color=\"info\">取证完成；诊断结论及其依据请查看详情，不等于根因已确认</font>")
                .replace("查询失败", "<font color=\"warning\">查询失败</font>")
                .replace("数据源未启用/不可用", "<font color=\"warning\">数据源未启用/不可用</font>")
                .replace("待分配（未认领 PENDING）", "<font color=\"warning\">待分配（未认领 PENDING）</font>")
                .replace("处置中（PROCESSING）", "<font color=\"warning\">处置中（PROCESSING）</font>")
                .replace("已恢复待验证（RESOLVED）", "<font color=\"info\">已恢复待验证（RESOLVED）</font>")
                .replace("已闭环（CLOSED）", "<font color=\"comment\">已闭环（CLOSED）</font>")
                .replace("已作废（VOID）", "<font color=\"comment\">已作废（VOID）</font>");
    }

    /** 判收专用：只读 errcode，不依赖调用方传入的 ObjectMapper（测试探针也能直接用） */
    private static final ObjectMapper ACCEPT_MAPPER = new ObjectMapper();
}
