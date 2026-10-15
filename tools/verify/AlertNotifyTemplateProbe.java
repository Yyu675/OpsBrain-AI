/*
 * 告警通知卡片模板的「无 Maven」行为探针（2026-10-14）。
 *
 * 存在理由：沙箱/离线环境装不上 Maven 依赖（无 maven central 网络），但模板是
 * 纯函数，行为契约可以脱离 Spring 单独验证——CI 里跑的是 {@code AlertNotifyTemplateTest}
 * （JUnit + AssertJ），本探针是同一批契约的离线替身，两边断言口径必须一致：
 * 改动其一必须同步另一个，否则会出现「CI 绿但本地看不出」或反过来的假象。
 *
 * 运行（需要 ECJ + 任一 JDK 的 java，见同目录 README.md）：
 *   java -jar ecj.jar -encoding UTF-8 -source 19 -target 19 -proc:none -nowarn \
 *        -d /tmp/tpl-probe -sourcepath src/main/java tools/verify/AlertNotifyTemplateProbe.java
 *   java -Dstdout.encoding=UTF-8 -cp /tmp/tpl-probe com.devops.agent.domain.alert.AlertNotifyTemplateProbe
 *
 * 覆盖范围：触发卡（事实/链接/转义/估算标注）、恢复卡（三态收尾 + 回落取值）、
 * 升级卡插入位置、诊断卡（逐方向状态 + 恒 normal）、行级与整卡两级截断。
 * 输出 `PASS: 合并模板 N 项行为校验` 即全过；任一断言失败直接抛 AssertionError。
 */
package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.entity.Alert;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class AlertNotifyTemplateProbe {
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }
    static void contains(String a, String... xs) { for (String x : xs) check(a.contains(x), "缺少：" + x + "\n实际：\n" + a); }
    static Map<String, String> ann(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
    static Alert baseAlert() {
        Alert a = new Alert();
        a.setId(1L); a.setAlertName("OrderServiceHttp5xxHigh"); a.setLevel("P1");
        a.setService("order-service"); a.setDescription("近 5 分钟 5xx 比例越限");
        a.setFirstOccurredAt(LocalDateTime.now().minusMinutes(8)); a.setOccurrenceCount(1);
        return a;
    }
    public static void main(String[] args) {
        final String BASE = "https://ops.example.com/opsbrain/";
        Alert t = baseAlert(); t.setTicketId("TK-2026-0001");
        contains(AlertNotifyTemplate.firing(t, Map.of(), Map.of(), t.getTicketId(), "sre-oncall", "PROCESSING", BASE, null).markdown(),
                "**关联工单**：TK-2026-0001", "**负责人**：sre-oncall", "**工单状态**：PROCESSING");
        Alert u = baseAlert(); u.setTicketId("TK-2026-0002");
        contains(AlertNotifyTemplate.firing(u, Map.of(), Map.of(), u.getTicketId(), null, null, BASE, null).markdown(),
                "**负责人**：待分配，请值班人员认领");
        contains(AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), null, null, null, BASE, "自动建单已关闭；请到告警页处理").markdown(),
                "**建单状态**：自动建单已关闭；请到告警页处理");
        check(!AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), null, null, null, BASE, "自动建单已关闭；请到告警页处理").markdown().contains("**关联工单**"), "hint 不应占关联工单槽位");
        Alert e = baseAlert(); e.setTicketId("TK/../x) [evil]");
        contains(AlertNotifyTemplate.firing(e, Map.of(), Map.of(), e.getTicketId(), null, null, BASE, null).markdown(),
                "(https://ops.example.com/opsbrain/alerts/1)", "(https://ops.example.com/opsbrain/tickets/TK%2F..%2Fx%29%20%5Bevil%5D)");
        for (String base : new String[]{"http://ops.example.com", "javascript:alert(1)", "https://user:password@ops.example.com", "https://ops.example.com?q=token"}) {
            String md = AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), "TK-1", null, null, base, null).markdown();
            check(!md.contains("]("), "不可信地址产生链接：" + base);
            contains(md, "未配置可信的 HTTPS 平台地址");
        }
        Alert x = baseAlert();
        x.setDescription("[evil](https://evil.example) <script> @所有人 password=secret123 bearer abc.def");
        String md = AlertNotifyTemplate.firing(x, Map.of(), Map.of(), null).markdown();
        check(!md.contains("[evil](") && !md.contains("<script>") && !md.contains("@所有人") && !md.contains("secret123") && !md.contains("abc.def"), "转义/脱敏失败：\n" + md);
        contains(md, "＠所有人", "已脱敏");
        String est = AlertNotifyTemplate.firing(baseAlert(), Map.of(), ann(AlertNotifyTemplate.START_TIME_ESTIMATED, "true"), null).markdown();
        String pre = AlertNotifyTemplate.firing(baseAlert(), Map.of(), ann(AlertNotifyTemplate.START_TIME_ESTIMATED, "false"), null).markdown();
        contains(est, "**接收后持续（估算）**");
        contains(pre, "**持续**：8 分钟");
        check(!pre.contains("（估算）"), "精确来源不应标估算：\n" + pre);
        // main 既有契约
        contains(AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), null).markdown(), "**详情**", "未配置可信的 HTTPS 平台地址");
        check(!AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), null).markdown().contains("null"), "缺料不得渲染 null");
        check(AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), null, null, null, null, null).title()
                .equals("[P1] order-service OrderServiceHttp5xxHigh"), "标题格式");
        check(AlertNotifyTemplate.firing(baseAlert(), ann("instance", "prod-order-7b9f"), Map.of(), null).title()
                .equals("[P1] order-service OrderServiceHttp5xxHigh | prod-order-7b9f"), "带实例标题格式");
        // 标题注入：外源片段里的 markdown 链接不能成形，但结构性 [P1] 不许被转义
        Alert inj = baseAlert();
        inj.setAlertName("[evil](https://evil.example)");
        String injTitle = AlertNotifyTemplate.firing(inj, Map.of(), Map.of(), null).title();
        check(!injTitle.contains("[evil]("), "标题产生了链接：" + injTitle);
        check(injTitle.startsWith("[P1] order-service "), "结构性方括号被转义了：" + injTitle);
        // main 既有契约：P2 normal / 影响在指标之前 / 单值降级行
        Alert p2 = baseAlert(); p2.setLevel("P2");
        check(!AlertNotifyTemplate.firing(p2, Map.of(), Map.of(), null).urgent(), "P2 必须 normal");
        String ctx = AlertNotifyTemplate.firing(baseAlert(), Map.of(),
                ann(AlertAnnotationKeys.SUMMARY, "订单服务 5xx 错误率过高", AlertAnnotationKeys.IMPACT, "部分用户下单失败",
                        AlertAnnotationKeys.CURRENT_VALUE, "12.3%", AlertAnnotationKeys.THRESHOLD, "5%"), "TK-2026-0001").markdown();
        int impact = ctx.indexOf("**影响**"), metric = ctx.indexOf("**指标**");
        check(impact > 0 && metric > impact, "影响必须先于指标");
        contains(ctx, "12.3%（阈值 5%）");
        String single = AlertNotifyTemplate.firing(baseAlert(), Map.of(), ann(AlertAnnotationKeys.CURRENT_VALUE, "12.3%"), null).markdown();
        contains(single, "**当前值**：12.3%");
        check(!single.contains("阈值）"), "单值行不得编造阈值");
        // 升级卡：级别变化行插在标题之后、摘要之前
        String esc = AlertNotifyTemplate.escalated(baseAlert(), Map.of(), ann(AlertAnnotationKeys.SUMMARY, "s"), "P2").markdown();
        check(esc.contains("**级别**：P2 → P1") && esc.indexOf("### ") < esc.indexOf("**级别**：P2 → P1")
                && esc.indexOf("**级别**：P2 → P1") < esc.indexOf("**摘要**"), "升级卡插入位置");
        check(AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), null).urgent(), "P1 must be urgent");
        check(!AlertNotifyTemplate.diagnosis(baseAlert(), Map.of(), null, "TK-9", BASE).urgent(), "诊断卡恒 normal");
        // 恢复卡三态
        Alert r = baseAlert(); r.setResolvedAt(LocalDateTime.now()); r.setTicketId("TK-9");
        String open = AlertNotifyTemplate.resolved(r, Map.of(), Map.of(), Map.of(), false, "PROCESSING", 0L, null).markdown();
        contains(open, "请到工单核查其他关联告警、业务验证与处置记录");
        check(!open.contains("留痕归档"), "在途工单不得说留痕归档");
        String sib = AlertNotifyTemplate.resolved(r, Map.of(), Map.of(), Map.of(), false, "PROCESSING", 2L, null).markdown();
        contains(sib, "仍有 2 条活跃告警", "不要据此关闭整张工单");
        String closed = AlertNotifyTemplate.resolved(r, Map.of(), Map.of(), Map.of(), false, "CLOSED", 0L, null).markdown();
        contains(closed, "工单已结束", "留痕归档");
        check(!closed.contains("请到工单核查"), "已关单不得再要求核查");
        Alert noT = baseAlert(); noT.setResolvedAt(LocalDateTime.now());
        String none = AlertNotifyTemplate.resolved(noT, Map.of(), Map.of(), Map.of(), false, null, null, null).markdown();
        contains(none, "未建单", "无需处理，留痕归档");
        check(!none.contains("**关联工单**"), "无工单不得虚构");
        Alert ov = baseAlert(); ov.setResolvedAt(LocalDateTime.now()); ov.setTicketId("TK-9");
        String orig = AlertNotifyTemplate.resolved(ov, Map.of(), ann(AlertAnnotationKeys.CURRENT_VALUE, "3.1%", AlertAnnotationKeys.THRESHOLD, "5%")).markdown();
        contains(orig, "[已恢复] P1", "TK-9", "恢复取值**：未提供（触发时取值 3.1%）");
        check(!orig.contains("恢复取值**：3.1%"), "旧值冒充恢复值");
        String rv = AlertNotifyTemplate.resolved(ov, Map.of(), ann(AlertAnnotationKeys.CURRENT_VALUE, "12.3%"), Map.of("value", "0"), false, "PROCESSING", 0L, null).markdown();
        contains(rv, "恢复取值**：0");
        check(!AlertNotifyTemplate.resolved(ov, Map.of(), Map.of()).urgent(), "恢复卡恒 normal");
        // 行级截断：话痨字段不能顶掉 runbook 行
        String big = AlertNotifyTemplate.firing(baseAlert(), Map.of(), ann(AlertAnnotationKeys.FIRST_ACTION, "查".repeat(2000),
                AlertAnnotationKeys.RUNBOOK_URL, "https://runbooks.internal/x"), null).markdown();
        check(big.length() <= AlertNotifyTemplate.MAX_MARKDOWN_CHARS, "行级截断失效：len=" + big.length());
        contains(big, "…（完整内容见详情）", "**Runbook**", "https://runbooks.internal/x");
        // 整卡截断：多行中等长度 → 砍尾留回链
        StringBuilder blob = new StringBuilder();
        for (int i = 0; i < 6; i++) blob.append("第").append(i).append("段").append("字".repeat(150)).append(' ');
        String chunk = blob.toString();
        String big2 = AlertNotifyTemplate.firing(baseAlert(), Map.of(), ann(AlertAnnotationKeys.SUMMARY, chunk,
                AlertAnnotationKeys.IMPACT, chunk, AlertAnnotationKeys.LIKELY_CAUSE, chunk,
                AlertAnnotationKeys.FIRST_ACTION, chunk,
                AlertAnnotationKeys.RUNBOOK_URL, "https://runbooks.internal/" + "x".repeat(900)), null).markdown();
        check(big2.length() <= AlertNotifyTemplate.MAX_MARKDOWN_CHARS + 40, "整卡截断失效：len=" + big2.length());
        check(big2.endsWith("全文见 OpsBrain 告警详情页）"), "截断回链缺失");
        // 诊断卡：逐方向状态 + 不转发原文 + 恒 normal
        var collected = java.util.List.of(
                new com.devops.agent.domain.evidence.Evidence(com.devops.agent.domain.evidence.Evidence.EvidenceStatus.NO_DATA, com.devops.agent.domain.evidence.Evidence.Type.METRICS, "secret-raw-evidence", Map.of(), "ref", null, java.time.Instant.parse("2026-10-14T01:00:00Z")),
                new com.devops.agent.domain.evidence.Evidence(com.devops.agent.domain.evidence.Evidence.EvidenceStatus.FAILED, com.devops.agent.domain.evidence.Evidence.Type.CHANGES, "secret-raw-evidence", Map.of(), "ref", null, java.time.Instant.parse("2026-10-14T01:00:00Z")),
                new com.devops.agent.domain.evidence.Evidence(com.devops.agent.domain.evidence.Evidence.EvidenceStatus.UNAVAILABLE, com.devops.agent.domain.evidence.Evidence.Type.LOGS, "secret-raw-evidence", Map.of(), "ref", null, java.time.Instant.parse("2026-10-14T01:00:00Z")),
                new com.devops.agent.domain.evidence.Evidence(com.devops.agent.domain.evidence.Evidence.EvidenceStatus.SUCCESS, com.devops.agent.domain.evidence.Evidence.Type.KNOWLEDGE, "secret-raw-evidence", Map.of(), "ref", null, java.time.Instant.parse("2026-10-14T01:00:00Z")));
        String diag = AlertNotifyTemplate.diagnosis(baseAlert(), Map.of(),
                com.devops.agent.domain.evidence.EvidenceAggregator.aggregate(collected), "TK-DIAG", BASE).markdown();
        contains(diag, "无匹配数据（不能据此排除）", "查询失败", "数据源未启用/不可用", "查询成功", "TK-DIAG", "/tickets/TK-DIAG", "OrderServiceHttp5xxHigh");
        check(!diag.contains("secret-raw-evidence"), "诊断卡转发了取证原文");
        System.out.println("PASS: 合并模板 29 项行为校验");
    }
}
