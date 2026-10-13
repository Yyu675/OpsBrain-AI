package com.devops.agent.domain.notify;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 与 JUnit 共用的真实渲染行为断言。无外部依赖，可在 Maven 不可达时独立编译运行。
 * 不 mock 模板，不读取源码替代行为测试；失败直接抛 AssertionError。
 */
public final class AlertNotificationChecks {
    private static final String BASE = "https://ops.example.com/opsbrain";
    private static final Map<String, String> PRECISE = Map.of(AlertNotificationRenderer.START_TIME_ESTIMATED, "false");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T09:38:47Z"), ZoneId.of("Asia/Shanghai"));
    private static final AlertNotificationRenderer RENDERER = new AlertNotificationRenderer(CLOCK);

    private AlertNotificationChecks() {}

    static Alert alert() {
        Alert a = new Alert();
        a.setId(15L);
        a.setAlertName("WSAPIRequestError");
        a.setService("infra");
        a.setSystem("storage");
        a.setSource("prometheus");
        a.setLevel("P0");
        a.setStatus("FIRING");
        a.setTicketId("TKT-20261013-0015");
        a.setDescription("获取 volumes 数据失败：Timeout was reached.");
        a.setFirstOccurredAt(LocalDateTime.parse("2026-10-13T17:37:55"));
        a.setResolvedAt(LocalDateTime.parse("2026-10-13T17:38:47"));
        return a;
    }

    public static void firingHasFactsAndOneHeading() {
        var msg = RENDERER.firing(alert(), Map.of("host", "primera01", "instance", "10.210.44.11:9100",
                "severity", "warning"), PRECISE, null, BASE);
        contains(msg.markdown(), "primera01", "10.210.44.11:9100", "WSAPIRequestError",
                "Timeout was reached.", "Asia/Shanghai", "52 秒", "平台事件 ID", "/alerts/15",
                "/tickets/TKT-20261013-0015", "业务影响**：待确认");
        check(msg.urgent(), "P0 首报必须保留强提醒");
        check(msg.markdown().lines().filter(s -> s.startsWith("### ")).count() == 1, "正文只能有一个标题");
        check(!msg.markdown().contains("54 秒"), "不能凭源示例补造 54 秒");
    }

    public static void lowRiskAndMissingFieldsAreHonest() {
        var a = alert();
        a.setLevel("P3");
        a.setTicketId(null);
        var msg = RENDERER.firing(a, Map.of(), PRECISE, "自动建单失败", "");
        check(!msg.urgent(), "低危不全员紧急");
        contains(msg.markdown(), "环境**：未提供", "告警对象**：未提供", "自动建单失败", "未配置有效 HTTPS");
        check(!msg.markdown().contains("]("), "缺基础地址不能有假链接");
        check(!msg.markdown().contains("生产"), "不得猜生产环境");
    }

    public static void stringEvidenceAndThresholdStaySourceBased() {
        var ann = Map.of(AlertNotificationRenderer.START_TIME_ESTIMATED, "false",
                "monitor_item", "HPE Primera: Get errors", "value", "Failed to receive data: volumes",
                "threshold", "源规则定义值");
        var msg = RENDERER.firing(alert(), Map.of(), ann, null, BASE);
        contains(msg.markdown(), "HPE Primera: Get errors", "Failed to receive data: volumes", "源规则定义值");
        check(!msg.markdown().contains("主机宕机"), "字符串取证不得推断成宕机");
    }

    public static void assigneeIsActualNotImpliedAcknowledgement() {
        var msg = RENDERER.firing(alert(), Map.of(), PRECISE, null, "sre-oncall", "PENDING", BASE);
        contains(msg.markdown(), "负责人**：sre-oncall", "工单状态**：PENDING", "处理状态**：待认领");
    }

    public static void recoveryDoesNotReuseFailureValue() {
        var msg = RENDERER.resolved(alert(), Map.of(), Map.of("value", "Timeout",
                AlertNotificationRenderer.START_TIME_ESTIMATED, "false"), Map.of(), false, "PROCESSING", 0L, BASE);
        check(!msg.urgent(), "恢复不得全员紧急");
        contains(msg.markdown(), "恢复取值**：未提供恢复取值", "收到监控源 resolved 事件", "本次持续**：0 小时 0 分 52 秒",
                "业务验证**：待确认", "PROCESSING");
        check(!msg.markdown().contains("恢复取值**：Timeout"), "不能拿旧错误冒充恢复取值");
        check(!msg.markdown().contains("业务已恢复"), "不能把源恢复当作业务验证");
    }

    public static void recoveryFreshValueAndGroupGuard() {
        var msg = RENDERER.resolved(alert(), Map.of(), PRECISE, Map.of("value", "0"), false,
                "PROCESSING", 2L, BASE);
        contains(msg.markdown(), "恢复取值**：0", "仍有 2 条活跃告警", "不应据此关闭整张工单");
    }

    public static void closedTicketDoesNotAskToCloseAgain() {
        var msg = RENDERER.resolved(alert(), Map.of(), PRECISE, Map.of(), false, "CLOSED", 0L, BASE);
        contains(msg.markdown(), "工单已结束");
        check(!msg.markdown().contains("请确认关单"), "已关单不能要求关单");
    }

    public static void fallbackTimesAreExplicit() {
        var msg = RENDERER.resolved(alert(), Map.of(), Map.of(AlertNotificationRenderer.START_TIME_ESTIMATED, "true"),
                Map.of(), true, null, null, BASE);
        contains(msg.markdown(), "首次接收时间", "恢复接收时间", "本次持续（估算）");
        var legacy = RENDERER.resolved(alert(), Map.of(), Map.of(), Map.of(), false, null, null, BASE);
        contains(legacy.markdown(), "历史时间来源未验证", "本次持续（估算）");
    }

    public static void invalidAndLongDurationsDoNotLie() {
        var a = alert();
        a.setResolvedAt(a.getFirstOccurredAt().minusSeconds(1));
        contains(RENDERER.resolved(a, Map.of(), PRECISE, Map.of(), false, null, null, BASE).markdown(), "结束早于开始");
        a.setResolvedAt(a.getFirstOccurredAt().plusDays(49).plusHours(2));
        contains(RENDERER.resolved(a, Map.of(), PRECISE, Map.of(), false, null, null, BASE).markdown(), "49 天 2 小时");
        a.setFirstOccurredAt(null);
        contains(RENDERER.resolved(a, Map.of(), PRECISE, Map.of(), false, null, null, BASE).markdown(), "时间缺失");
    }

    public static void unsafeBaseUrlsCannotBecomeLinks() {
        for (String base : List.of("http://ops.example.com", "javascript:alert(1)",
                "https://user:password@ops.example.com", "https://ops.example.com?q=token",
                "https://ops.example.com#fragment", "https://ops.example.com/evil)")) {
            var msg = RENDERER.firing(alert(), Map.of(), PRECISE, null, base);
            check(!msg.markdown().contains("]("), "不安全基础地址产生了链接：" + base);
        }
        var a = alert();
        a.setTicketId("TKT/../x) [evil]");
        var msg = RENDERER.firing(a, Map.of(), PRECISE, null, BASE + "/");
        contains(msg.markdown(), "/tickets/TKT%2F..%2Fx%29%20%5Bevil%5D");
    }

    public static void externalTextIsEscapedRedactedAndBounded() {
        var a = alert();
        a.setDescription("[evil](https://evil.example) <script> @所有人\npassword=secret123 token=token123 " + "x".repeat(5000));
        var msg = RENDERER.firing(a, Map.of("host", "node"), PRECISE, null, BASE);
        check(!msg.markdown().contains("[evil]("), "外部值不能制造 Markdown 链接");
        check(!msg.markdown().contains("<script>"), "外部值不能制造 HTML");
        check(!msg.markdown().contains("@所有人"), "外部值不能制造 @");
        check(!msg.markdown().contains("secret123") && !msg.markdown().contains("token123"), "常见凭据不得进入群摘要");
        check(msg.markdown().length() < 2200, "单条输入必须有界");
        contains(msg.markdown(), "完整内容见详情");
    }

    public static void diagnosisSeparatesEvidenceStatusesAndNeverUrgent() {
        var evidence = List.of(
                evidence(Evidence.EvidenceStatus.NO_DATA, Evidence.Type.METRICS),
                evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.CHANGES),
                evidence(Evidence.EvidenceStatus.UNAVAILABLE, Evidence.Type.LOGS),
                evidence(Evidence.EvidenceStatus.SUCCESS, Evidence.Type.KNOWLEDGE));
        var msg = RENDERER.diagnosis(alert(), Map.of(), EvidenceAggregator.aggregate(evidence), "TK-DIAG", BASE);
        check(!msg.urgent(), "诊断不足或查询失败不是全员紧急事件");
        contains(msg.markdown(), "无匹配数据（不能据此排除）", "查询失败", "数据源未启用/不可用", "查询成功",
                "TK-DIAG", "/tickets/TK-DIAG", "WSAPIRequestError");
        check(!msg.markdown().contains("secret-raw-evidence"), "诊断群摘要不能直接转发原文");
    }

    private static Evidence evidence(Evidence.EvidenceStatus status, String type) {
        return new Evidence(status, type, "secret-raw-evidence", Map.of(), "ref", null, CLOCK.instant());
    }

    private static void contains(String actual, String... expected) {
        for (String value : expected) check(actual.contains(value), "缺少：" + value + "\n实际：\n" + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        firingHasFactsAndOneHeading();
        lowRiskAndMissingFieldsAreHonest();
        stringEvidenceAndThresholdStaySourceBased();
        assigneeIsActualNotImpliedAcknowledgement();
        recoveryDoesNotReuseFailureValue();
        recoveryFreshValueAndGroupGuard();
        closedTicketDoesNotAskToCloseAgain();
        fallbackTimesAreExplicit();
        invalidAndLongDurationsDoNotLie();
        unsafeBaseUrlsCannotBecomeLinks();
        externalTextIsEscapedRedactedAndBounded();
        diagnosisSeparatesEvidenceStatusesAndNeverUrgent();
        System.out.println("PASS: 12 notification rendering behavior checks");
    }
}
