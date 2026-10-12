package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.notify.NotifyMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
                    "**持续**", "**可能原因**", "**首步动作**", "**Runbook**",
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
                    // 恒有段不受影响：详情取实体字段，认领去处恒打印
                    .contains("**详情**", "/alerts");
        }

        @Test
        @DisplayName("当前值/阈值只填其一时降级为单值行，不编造缺失侧")
        void partialMetricPair() {
            NotifyMessage msg = AlertNotifyTemplate.firing(baseAlert(), Map.of(),
                    ann(AlertAnnotationKeys.CURRENT_VALUE, "12.3%"), null);

            assertThat(msg.markdown()).contains("**当前值**：12.3%").doesNotContain("阈值）");
        }

        @Test
        @DisplayName("超长砍尾留回链——截断有预案，不让钉钉在不可控处截")
        void overlongClampedWithBacklink() {
            Map<String, String> big = ann(AlertAnnotationKeys.FIRST_ACTION, "查".repeat(2000));

            String md = AlertNotifyTemplate.firing(baseAlert(), Map.of(), big, null).markdown();

            assertThat(md).hasSizeLessThanOrEqualTo(
                            AlertNotifyTemplate.MAX_MARKDOWN_CHARS + 40)
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
            assertThat(md).contains("**级别**：P2 → P1")
                    // 升级场景值班更需要动作信息——触发卡的段全保留
                    .contains("**摘要**", "**详情**", "TK-3");
            // 级别行紧跟标题之后（第一屏第一条差异事实）
            assertThat(md.indexOf("### ")).isLessThan(md.indexOf("**级别**：P2 → P1"));
            assertThat(md.indexOf("**级别**：P2 → P1")).isLessThan(md.indexOf("**摘要**"));
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
        @DisplayName("带回落值/持续/工单收尾提示，读完知道下一步只剩关单")
        void carriesClosureInfo() {
            Alert a = baseAlert();
            a.setStatus("RESOLVED");
            a.setResolvedAt(LocalDateTime.now());
            a.setTicketId("TK-9");

            String md = AlertNotifyTemplate.resolved(a, Map.of(),
                    ann(AlertAnnotationKeys.CURRENT_VALUE, "3.1%",
                            AlertAnnotationKeys.THRESHOLD, "5%")).markdown();

            assertThat(md).contains("[已恢复] P1", "3.1%（阈值 5%）",
                    "TK-9（处置完成后请确认关单）", "留痕归档");
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
}
