package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;
import com.devops.agent.domain.notify.NotifyMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警通知卡片模板的信息设计契约（A1，2026-10-12）。
 *
 * <h3>保护的不是排版而是值班行为</h3>
 * <p>卡片是值班人看到的第一屏：影响不先行 → 判断「要不要爬起来」要多读三行；
 * 缺料渲染 "null" → 读者分不清「规则没写」还是「系统坏了」；超长不截 → 钉钉
 * 在不可控处截断，被切掉的可能是唯一一个 runbook 链接。这些都不会报错，
 * 只会静默拉长 MTTR——所以钉进测试。</p>
 *
 * <p>与 NotifierContractTest 分工：那边管 NotifyMessage 的渠道中性，
 * 本类管「同一条告警在卡片上呈现成什么样」。</p>
 *
 * <p><b>2026-10-14 合并说明</b>：分支侧第一批的独立渲染器（AlertNotificationRenderer）已并入本模板，
 * 其行为契约随之迁移到本类（恢复卡三态收尾、负责人/工单状态/平台直达链接的地址白名单、
 * 外源文本转义脱敏、开始时间为兜底时标「（估算）」、诊断卡恒 normal）。</p>
 */
@DisplayName("告警通知卡片模板")
class AlertNotifyTemplateTest {

    private static Alert baseAlert() {
        Alert a = new Alert();
        a.setId(1L);
        a.setAlertName("OrderServiceHttp5xxHigh");
        a.setLevel("P1");
        a.setService("order-service");
        a.setDescription("近 5 分钟 5xx 比例越限");
        a.setFirstOccurredAt(LocalDateTime.now().minusMinutes(8));
        a.setOccurrenceCount(1);
        return a;
    }

    private static Map<String, String> ann(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("触发卡")
    class Firing {

        @Test
        @DisplayName("高危走 urgent + 标题机器可读（[级别] 服务 告警名 | 实例）")
        void highRiskUrgentWithMachineReadableTitle() {
            NotifyMessage msg = AlertNotifyTemplate.firing(baseAlert(),
                    ann("instance", "prod-order-7b9f"), Map.of(), null);

            assertThat(msg.urgent()).isTrue();
            assertThat(msg.title())
                    .isEqualTo("[P1] order-service OrderServiceHttp5xxHigh | prod-order-7b9f");
        }

        @Test
        @DisplayName("P2 走 normal——urgent 语义与 isHighRisk 单源，模板层不另立分级")
        void lowLevelNormal() {
            Alert a = baseAlert();
            a.setLevel("P2");
            assertThat(AlertNotifyTemplate.firing(a, Map.of(), Map.of(), null).urgent()).isFalse();
        }

        @Test
        @DisplayName("信息顺序：影响先于指标——「先说影响再说指标」的钉桩")
        void impactBeforeMetrics() {
            NotifyMessage msg = AlertNotifyTemplate.firing(baseAlert(), Map.of(),
                    annAlert(), "TK-1");
            String md = msg.markdown();

            int impact = md.indexOf("**影响**");
            int metric = md.indexOf("**指标**");
            assertThat(impact).isPositive();
            assertThat(metric).isPositive();
            assertThat(impact)
                    .as("值班第一屏先回答「要不要现在爬起来」，再回答「数值多少」")
                    .isLessThan(metric);
        }

        @Test
        @DisplayName("上下文全段齐活：摘要/影响/当前值对/持续/原因/动作/runbook/大盘/工单")
        void fullContextSections() {
            NotifyMessage msg = AlertNotifyTemplate.firing(baseAlert(),
                    ann("instance", "node-1"), annAlert(), "TK-2026-0001");
            String md = msg.markdown();

            assertThat(md).contains("**摘要**", "**影响**", "**指标**", "12.3%（阈值 5%）",
                    // 未标 START_TIME_ESTIMATED 的实体（非入库路径构造）按「估算」保守渲染：
                    // 宁可多一个字，不能让接收时刻兜底的时间冒充精确时长
                    "**接收后持续（估算）**", "**可能原因**", "**首步动作**", "**Runbook**",
                    "**监控大盘**", "TK-2026-0001");
        }

        @Test
        @DisplayName("缺料整段省略——annotation 没填的键不渲染 null/空占位")
        void missingAnnotationsOmitWholeLine() {
            NotifyMessage msg = AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), null);
            String md = msg.markdown();

            assertThat(md)
                    .doesNotContain("null")
                    .doesNotContain("**影响**")
                    .doesNotContain("**Runbook**")
                    // 恒有段不受影响：详情取实体字段；无可信平台地址时给可执行指引（相对链接在群里点不开）
                    .contains("**详情**", "未配置可信的 HTTPS 平台地址");
        }

        @Test
        @DisplayName("当前值/阈值只填其一时降级为单值行，不编造缺失侧")
        void partialMetricPair() {
            NotifyMessage msg = AlertNotifyTemplate.firing(baseAlert(), Map.of(),
                    ann(AlertAnnotationKeys.CURRENT_VALUE, "12.3%"), null);

            assertThat(msg.markdown()).contains("**当前值**：12.3%").doesNotContain("阈值）");
        }

        @Test
        @DisplayName("单行超长按行截断——一个话痨字段不能把 runbook/首步动作挤出卡片")
        void overlongSingleValueIsCutBeforeItEatsTheCard() {
            Map<String, String> big = ann(AlertAnnotationKeys.FIRST_ACTION, "查".repeat(2000),
                    AlertAnnotationKeys.RUNBOOK_URL, "https://runbooks.internal/x");

            String md = AlertNotifyTemplate.firing(baseAlert(), Map.of(), big, null).markdown();

            // 行级限量（600 字）+ 明说被截断，而不是让它吃满整卡把后面的链接顶掉
            assertThat(md).contains("…（完整内容见详情）", "**Runbook**", "https://runbooks.internal/x")
                    .hasSizeLessThanOrEqualTo(AlertNotifyTemplate.MAX_MARKDOWN_CHARS);
        }

        @Test
        @DisplayName("整卡超长才砍尾留回链——截断有预案，不让钉钉在不可控处截")
        void wholeCardClampedWithBacklink() {
            StringBuilder blob = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                blob.append("第").append(i).append("段").append("字".repeat(150)).append(' ');
            }
            String chunk = blob.toString();
            // 五段各 900+ 字：单行限量之后整卡仍超 1500，走砍尾留回链
            Map<String, String> big = new LinkedHashMap<>(ann(
                    AlertAnnotationKeys.SUMMARY, chunk,
                    AlertAnnotationKeys.IMPACT, chunk,
                    AlertAnnotationKeys.LIKELY_CAUSE, chunk,
                    AlertAnnotationKeys.FIRST_ACTION, chunk,
                    AlertAnnotationKeys.RUNBOOK_URL, "https://runbooks.internal/" + "x".repeat(900)));

            String md = AlertNotifyTemplate.firing(baseAlert(), Map.of(), big, null).markdown();

            assertThat(md).hasSizeLessThanOrEqualTo(AlertNotifyTemplate.MAX_MARKDOWN_CHARS + 40)
                    .endsWith("全文见 OpsBrain 告警详情页）");
        }

        private Map<String, String> annAlert() {
            return ann(
                    AlertAnnotationKeys.SUMMARY, "订单服务 5xx 错误率过高",
                    AlertAnnotationKeys.IMPACT, "部分用户下单失败",
                    AlertAnnotationKeys.CURRENT_VALUE, "12.3%",
                    AlertAnnotationKeys.THRESHOLD, "5%",
                    AlertAnnotationKeys.LIKELY_CAUSE, "payment-service 超时",
                    AlertAnnotationKeys.FIRST_ACTION, "1) 看盘 2) 查发布",
                    AlertAnnotationKeys.RUNBOOK_URL, "https://runbooks.internal/order-5xx",
                    AlertAnnotationKeys.DASHBOARD_URL, "https://grafana.internal/d/order");
        }
    }

    @Nested
    @DisplayName("升级卡")
    class Escalated {

        @Test
        @DisplayName("升级卡=触发卡全部上下文+级别变化首行——不是「⬆️ 升级了」四个字")
        void carriesFullContextWithChangeLine() {
            Alert a = baseAlert();
            a.setTicketId("TK-3");
            a.setAnnotationsJson(null);

            NotifyMessage msg = AlertNotifyTemplate.escalated(a, Map.of(),
                    ann(AlertAnnotationKeys.SUMMARY, "订单服务 5xx 错误率过高"), "P2");
            String md = msg.markdown();

            assertThat(msg.urgent()).isTrue();
            assertThat(msg.title()).startsWith("⬆️ [P1]");
            assertThat(md).contains("**级别**：P2 一般严重（Average） → P1 严重级（High）")
                    // 升级场景值班更需要动作信息——触发卡的段全保留
                    .contains("**摘要**", "**详情**", "TK-3");
            // 级别行紧跟标题之后（第一屏第一条差异事实）
            assertThat(md.indexOf("### ")).isLessThan(md.indexOf("**级别**：P2 一般严重（Average） → P1 严重级（High）"));
            assertThat(md.indexOf("**级别**：P2 一般严重（Average） → P1 严重级（High）")).isLessThan(md.indexOf("**摘要**"));
        }
    }

    @Nested
    @DisplayName("恢复卡")
    class Resolved {

        @Test
        @DisplayName("恒 normal——火已灭不再 @所有人（恢复要发，但别吵）")
        void alwaysNormal() {
            Alert a = baseAlert();
            a.setStatus("RESOLVED");
            a.setResolvedAt(LocalDateTime.now());

            assertThat(AlertNotifyTemplate.resolved(a, Map.of(), Map.of()).urgent()).isFalse();
        }

        @Test
        @DisplayName("触发时取值不冒充恢复取值——没有回落值就明说「未提供」")
        void originalValueNeverMasqueradesAsRecovery() {
            Alert a = baseAlert();
            a.setStatus("RESOLVED");
            a.setResolvedAt(LocalDateTime.now());
            a.setTicketId("TK-9");

            String md = AlertNotifyTemplate.resolved(a, Map.of(),
                    ann(AlertAnnotationKeys.CURRENT_VALUE, "3.1%",
                            AlertAnnotationKeys.THRESHOLD, "5%")).markdown();

            assertThat(md).contains("[已恢复] P1", "TK-9", "恢复取值**：未提供（触发时取值 3.1%）")
                    .doesNotContain("恢复取值**：3.1%");
        }

        @Test
        @DisplayName("恢复推送带回落值就用真回落值（0 = 真的回到 0）")
        void recoveryValueWinsOverOriginal() {
            Alert a = baseAlert();
            a.setResolvedAt(LocalDateTime.now());
            a.setTicketId("TK-9");

            String md = AlertNotifyTemplate.resolved(a, Map.of(),
                    ann(AlertAnnotationKeys.CURRENT_VALUE, "12.3%"), Map.of("value", "0"), false,
                    "PROCESSING", 0L, null).markdown();

            assertThat(md).contains("恢复取值**：0");
        }

        @Test
        @DisplayName("收尾话术按事实三态——工单在途只请核查，不说「无需处理」")
        void openTicketAskForVerification() {
            Alert a = baseAlert();
            a.setResolvedAt(LocalDateTime.now());
            a.setTicketId("TK-9");

            String md = AlertNotifyTemplate.resolved(a, Map.of(), Map.of(), Map.of(), false,
                    "PROCESSING", 0L, null).markdown();

            assertThat(md).contains("请到工单核查其他关联告警、业务验证与处置记录")
                    .doesNotContain("留痕归档");
        }

        @Test
        @DisplayName("同工单仍有活跃告警时明说别关整张单（群恢复 ≠ 工单可关）")
        void siblingActiveAlertsBlockClosure() {
            Alert a = baseAlert();
            a.setResolvedAt(LocalDateTime.now());
            a.setTicketId("TK-9");

            String md = AlertNotifyTemplate.resolved(a, Map.of(), Map.of(), Map.of(), false,
                    "PROCESSING", 2L, null).markdown();

            assertThat(md).contains("仍有 2 条活跃告警", "不要据此关闭整张工单");
        }

        @Test
        @DisplayName("工单已结束才说留痕归档，绝不与「请核查」并列出现")
        void closedTicketNeverContradicts() {
            Alert a = baseAlert();
            a.setResolvedAt(LocalDateTime.now());
            a.setTicketId("TK-9");

            String md = AlertNotifyTemplate.resolved(a, Map.of(), Map.of(), Map.of(), false,
                    "CLOSED", 0L, null).markdown();

            assertThat(md).contains("工单已结束", "留痕归档")
                    .doesNotContain("请到工单核查");
        }

        @Test
        @DisplayName("未建单的恢复卡只说留痕归档，不虚构工单")
        void noTicketMeansNoAction() {
            Alert a = baseAlert();
            a.setResolvedAt(LocalDateTime.now());

            String md = AlertNotifyTemplate.resolved(a, Map.of(), Map.of(), Map.of(), false,
                    null, null, null).markdown();

            assertThat(md).contains("未建单", "无需处理，留痕归档")
                    .doesNotContain("**关联工单**");
        }
    }

    @Nested
    @DisplayName("时长人话化")
    class Duration {

        @Test
        @DisplayName("分/时/天三档，值班读「3 天 2 小时」比「4517 分钟」快")
        void humanBuckets() {
            LocalDateTime now = LocalDateTime.now();
            assertThat(AlertNotifyTemplate.humanDuration(now.minusSeconds(30), now))
                    .isEqualTo("不足 1 分钟");
            assertThat(AlertNotifyTemplate.humanDuration(now.minusMinutes(5), now))
                    .isEqualTo("5 分钟");
            assertThat(AlertNotifyTemplate.humanDuration(now.minusMinutes(125), now))
                    .isEqualTo("2 小时 5 分");
            // 4620 分 = 3 天整 4320 分 + 300 分（5 小时）
            assertThat(AlertNotifyTemplate.humanDuration(now.minusMinutes(4620), now))
                    .isEqualTo("3 天 5 小时");
        }

        @Test
        @DisplayName("时钟回拨/缺值返回 null 交给上层省略整行，不渲染负时长")
        void invalidInputYieldsNull() {
            LocalDateTime now = LocalDateTime.now();
            assertThat(AlertNotifyTemplate.humanDuration(now, now.minusMinutes(1))).isNull();
            assertThat(AlertNotifyTemplate.humanDuration(null, now)).isNull();
        }
    }

    @Nested
    @DisplayName("分支增量（2026-10-13 批次并入单源模板）")
    class MergedIncrements {

        private static final String BASE = "https://ops.example.com/opsbrain/";

        @Test
        @DisplayName("负责人/工单状态随卡下发——「谁在管、卡在哪」不用回平台才知道")
        void assigneeAndTicketStatusOnCard() {
            Alert a = baseAlert();
            a.setTicketId("TK-2026-0001");

            String md = AlertNotifyTemplate.firing(a, Map.of(), Map.of(), a.getTicketId(),
                    "sre-oncall", "PROCESSING", BASE, null).markdown();

            assertThat(md).contains("**关联工单**：TK-2026-0001", "**负责人**：sre-oncall",
                    "**工单状态**：处置中（PROCESSING）");
        }

        @Test
        @DisplayName("无人认领就明说「待分配」——留白会让人以为已有人看")
        void unassignedIsExplicit() {
            Alert a = baseAlert();
            a.setTicketId("TK-2026-0002");

            String md = AlertNotifyTemplate.firing(a, Map.of(), Map.of(), a.getTicketId(),
                    null, null, BASE, null).markdown();

            assertThat(md).contains("**负责人**：待分配，请值班人员认领");
        }

        @Test
        @DisplayName("建单提示走「建单状态」行，不占「关联工单」槽位（否则会去找不存在的单）")
        void ticketHintNeverLooksLikeTicketId() {
            String md = AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), null,
                    null, null, BASE, "自动建单已关闭；请到告警页处理").markdown();

            assertThat(md).contains("**建单状态**：自动建单已关闭；请到告警页处理")
                    .doesNotContain("**关联工单**");
        }

        @Test
        @DisplayName("平台直达链接只由可信 HTTPS 地址 + 真实 ID 构造（真实 ID 一律转义）")
        void linksBuiltOnlyFromTrustedBase() {
            Alert a = baseAlert();
            a.setTicketId("TK/../x) [evil]");

            String md = AlertNotifyTemplate.firing(a, Map.of(), Map.of(), a.getTicketId(),
                    null, null, BASE, null).markdown();

            assertThat(md).contains("(https://ops.example.com/opsbrain/alerts/1)",
                    "(https://ops.example.com/opsbrain/tickets/TK%2F..%2Fx%29%20%5Bevil%5D)");
        }

        @Test
        @DisplayName("不可信地址（http/伪协议/带凭据/带 query）不给链接，改给查询指引")
        void untrustedBaseYieldsGuidance() {
            for (String base : new String[]{"http://ops.example.com", "javascript:alert(1)",
                    "https://user:password@ops.example.com", "https://ops.example.com?q=token"}) {
                String md = AlertNotifyTemplate.firing(baseAlert(), Map.of(), Map.of(), "TK-1",
                        null, null, base, null).markdown();
                assertThat(md).as("不可信地址：%s", base)
                        .doesNotContain("](")
                        .contains("未配置可信的 HTTPS 平台地址");
            }
        }

        @Test
        @DisplayName("外源文本不得制造链接/HTML/@提醒，凭据一律脱敏")
        void externalTextIsEscapedRedactedAndAtNeutralised() {
            Alert a = baseAlert();
            a.setDescription("[evil](https://evil.example) <script> @所有人 password=secret123 bearer abc.def");

            String md = AlertNotifyTemplate.firing(a, Map.of(), Map.of(), null).markdown();

            assertThat(md).doesNotContain("[evil](", "<script>", "@所有人", "secret123", "abc.def")
                    .contains("＠所有人", "已脱敏");
        }

        @Test
        @DisplayName("开始时间是接收兜底时必须标「（估算）」；来源明确才报精确时长")
        void estimatedStartIsLabelled() {
            Alert a = baseAlert();
            String estimated = AlertNotifyTemplate.firing(a, Map.of(),
                    ann(AlertNotifyTemplate.START_TIME_ESTIMATED, "true"), null).markdown();
            String precise = AlertNotifyTemplate.firing(a, Map.of(),
                    ann(AlertNotifyTemplate.START_TIME_ESTIMATED, "false"), null).markdown();

            assertThat(estimated).contains("**接收后持续（估算）**");
            assertThat(precise).contains("**持续**：8 分钟").doesNotContain("（估算）");
        }

        @Test
        @DisplayName("诊断补充卡恒 normal——证据不足是「需要人看」，不是全员紧急")
        void diagnosisIsNeverUrgent() {
            Alert a = baseAlert();

            assertThat(AlertNotifyTemplate.diagnosis(a, Map.of(), null, "TK-9", BASE).urgent())
                    .isFalse();
        }

        @Test
        @DisplayName("诊断卡逐方向给结论状态，不转发取证原文（群摘要与审计明细分离）")
        void diagnosisSummarisesDirectionsWithoutRawEvidence() {
            List<Evidence> collected = List.of(
                    evidence(Evidence.EvidenceStatus.NO_DATA, Evidence.Type.METRICS),
                    evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.CHANGES),
                    evidence(Evidence.EvidenceStatus.UNAVAILABLE, Evidence.Type.LOGS),
                    evidence(Evidence.EvidenceStatus.SUCCESS, Evidence.Type.KNOWLEDGE));

            String md = AlertNotifyTemplate.diagnosis(baseAlert(), Map.of(),
                    EvidenceAggregator.aggregate(collected), "TK-DIAG", BASE).markdown();

            assertThat(md).contains("无匹配数据（不能据此排除）", "查询失败", "数据源未启用/不可用",
                            "查询成功", "TK-DIAG", "/tickets/TK-DIAG", "OrderServiceHttp5xxHigh")
                    .doesNotContain("secret-raw-evidence");
        }

        @Test
        @DisplayName("标题里的外源字段不能造链接，结构性 [级别] 标记不被转义")
        void titleEscapesForeignPartsOnly() {
            Alert a = baseAlert();
            a.setAlertName("[evil](https://evil.example)");

            String title = AlertNotifyTemplate.firing(a, Map.of(), Map.of(), null).title();

            assertThat(title).doesNotContain("[evil](")
                    .startsWith("[P1] order-service ");
        }

        private static Evidence evidence(Evidence.EvidenceStatus status, String type) {
            return new Evidence(status, type, "secret-raw-evidence", Map.of(), "ref", null,
                    Instant.parse("2026-10-14T01:00:00Z"));
        }

        @Test
        @DisplayName("企业真实通知对齐：支持主机/IP/监控项目与文本取值（吸收Zabbix实战截图样例）")
        void enterpriseSampleFiringAndResolvedSymmetry() {
            Alert alert = new Alert();
            alert.setId(12002061L);
            alert.setAlertName("HPE Primera: There are errors in requests to WSAPI");
            alert.setLevel("P2");
            alert.setService("storage-infra");
            alert.setDescription("HPE Primera: There are errors in requests to WSAPI");
            alert.setFirstOccurredAt(LocalDateTime.of(2026, 10, 13, 17, 37, 55));
            alert.setResolvedAt(LocalDateTime.of(2026, 10, 13, 17, 38, 47));
            alert.setTicketId("TK-20261013-0021");

            Map<String, String> labels = Map.of(
                    "host", "primera01",
                    "instance", "10.210.44.11"
            );
            Map<String, String> annotations = Map.of(
                    AlertAnnotationKeys.SUMMARY, "HPE Primera WSAPI请求报错",
                    AlertAnnotationKeys.MONITOR_ITEM, "HPE Primera: Get errors",
                    AlertAnnotationKeys.HOST_NAME, "primera01",
                    AlertAnnotationKeys.TARGET_ADDRESS, "10.210.44.11",
                    AlertAnnotationKeys.CURRENT_VALUE, "Failed to receive data: volumes : Error: cannot get URL: Timeout was reached.",
                    AlertAnnotationKeys.IMPACT, "存储管理API超时，部分卷监控离线",
                    AlertNotifyTemplate.START_TIME_ESTIMATED, "false"
            );

            // 触发卡断言
            NotifyMessage firingMsg = AlertNotifyTemplate.firing(alert, labels, annotations, alert.getTicketId(),
                    "oncall-sre", "PROCESSING", BASE, null);
            String firingMd = firingMsg.markdown();

            assertThat(firingMd)
                    .contains("**当前状态**：告警中（PROBLEM）")
                    .contains("**告警等级**：P2 一般严重（Average）")
                    .contains("**告警主机**：primera01")
                    .contains("**告警地址**：10.210.44.11")
                    .contains("**监控项目**：HPE Primera: Get errors")
                    .contains("**当前值**：Failed to receive data: volumes : Error: cannot get URL: Timeout was reached.")
                    .contains("**影响**：存储管理API超时，部分卷监控离线")
                    .contains("**关联工单**：TK-20261013-0021");

            // 恢复卡断言：对称包含恢复主机、恢复地址、监控项，并精准计算 52/54 秒级持续时间（无估算污染）
            NotifyMessage resolvedMsg = AlertNotifyTemplate.resolved(alert, labels, annotations,
                    Map.of(AlertAnnotationKeys.CURRENT_VALUE, "WSAPI response OK (HTTP 200)"),
                    false, "PROCESSING", 1L, BASE);
            String resolvedMd = resolvedMsg.markdown();

            assertThat(resolvedMd)
                    .contains("[已恢复] P2")
                    .contains("**当前状态**：监控恢复（OK）")
                    .contains("**告警等级**：P2 一般严重（Average）")
                    .contains("**恢复主机**：primera01")
                    .contains("**恢复地址**：10.210.44.11")
                    .contains("**监控项目**：HPE Primera: Get errors")
                    .contains("**恢复取值**：WSAPI response OK (HTTP 200)")
                    .contains("**本次持续**：不足 1 分钟")
                    .contains("仍有 1 条活跃告警——**不要据此关闭整张工单**");
        }

        @Test
        @DisplayName("诊断不足时卡片明确输出排查建议与引导，严禁将未采集视作正常")
        void diagnosisInsufficientProvidesActionableGuidance() {
            Alert alert = baseAlert();
            List<Evidence> collected = List.of(
                    evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.LOGS),
                    evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.METRICS)
            );

            NotifyMessage diagMsg = AlertNotifyTemplate.diagnosis(alert, Map.of("host", "primera01"),
                    EvidenceAggregator.aggregate(collected), "TK-DIAG-01", BASE);

            String diagMd = diagMsg.markdown();
            assertThat(diagMd)
                    .contains("**诊断状态**：证据不足，暂无法形成可靠根因结论")
                    .contains("**排查建议**：诊断数据源缺失或采集异常，请先核验监控探针、日志上报链路与权限，优先转人工研判")
                    .contains("不要据此认定业务正常");
            assertThat(diagMsg.urgent()).isFalse();
        }
    }
}
