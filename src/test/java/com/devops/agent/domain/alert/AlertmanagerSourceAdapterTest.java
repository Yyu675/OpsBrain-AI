package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Alertmanager 源适配器单测（L2 跨源接缝的解析边界）。
 *
 * <h3>为什么这个适配器值得单测</h3>
 * 它把「读懂 Alertmanager」的规则从 AlertService 搬到边界处。解析任何一处
 * 写错（module 没大写化、description 回退错、labels 持有上游引用），
 * 下游都拿不到报错——只会建出一张字段看着正常、实际来源错的告警。
 * 核心链（去重/建单/诊断）的行为已被 AlertServiceTest 大量覆盖，
 * 适配器这层是新增的独立风险点，必须单独锁住。
 */
@DisplayName("AlertmanagerSourceAdapter 归一化解析")
class AlertmanagerSourceAdapterTest {

    private final AlertmanagerSourceAdapter adapter = new AlertmanagerSourceAdapter();

    private static AlertmanagerWebhook.Alert alert(String alertName, String service,
                                                   String severity, String module,
                                                   Map<String, String> annotations) {
        AlertmanagerWebhook.Alert a = new AlertmanagerWebhook.Alert();
        a.setStatus("firing");
        a.setLabels(Map.of(
                "alertname", alertName,
                "service", service,
                "severity", severity,
                "module", module));
        a.setAnnotations(annotations);
        a.setStartsAt(OffsetDateTime.parse("2026-09-24T10:00:00Z"));
        a.setFingerprint("fp-1");
        return a;
    }

    private List<AlertSignal> normalize(AlertmanagerWebhook.Alert... alerts) {
        AlertmanagerWebhook wh = new AlertmanagerWebhook();
        wh.setAlerts(List.of(alerts));
        return adapter.normalize(wh);
    }

    @Test
    @DisplayName("负载字段一一映射：alertname/service/severity/module 大写化/source")
    void mapsAllFields() {
        List<AlertSignal> signals = normalize(
                alert("PodDown", "order-service", "critical", "pod", Map.of("description", "Pod 重启")));

        assertThat(signals).hasSize(1);
        AlertSignal s = signals.get(0);
        assertThat(s.alertName()).isEqualTo("PodDown");
        assertThat(s.service()).isEqualTo("order-service");
        assertThat(s.severity()).isEqualTo("critical");
        assertThat(s.module()).isEqualTo("POD");
        assertThat(s.source()).isEqualTo("prometheus");
        assertThat(s.description()).isEqualTo("Pod 重启");
        assertThat(s.fingerprint()).isEqualTo("fp-1");
        assertThat(s.resolved()).isFalse();
    }

    @Test
    @DisplayName("无 module 标签 → OTHER")
    void defaultModuleIsOther() {
        AlertmanagerWebhook.Alert a = new AlertmanagerWebhook.Alert();
        a.setStatus("firing");
        a.setLabels(Map.of("alertname", "X", "severity", "warning"));
        a.setAnnotations(Map.of());

        assertThat(normalize(a).get(0).module()).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("description 回退：description 缺失 → summary")
    void descriptionFallsBackToSummary() {
        AlertSignal s = normalize(alert("X", "svc", "warning", "pod",
                Map.of("summary", "摘要"))).get(0);

        assertThat(s.description()).isEqualTo("摘要");
    }

    @Test
    @DisplayName("resolved 状态透传")
    void resolvedFlagPassedThrough() {
        AlertmanagerWebhook.Alert a = new AlertmanagerWebhook.Alert();
        a.setStatus("resolved");
        a.setLabels(Map.of("alertname", "X"));

        assertThat(normalize(a).get(0).resolved()).isTrue();
    }

    @Test
    @DisplayName("labels 防御性拷贝：改动上游 map 不影响信号")
    void labelsAreDefensivelyCopied() {
        AlertmanagerWebhook.Alert a = new AlertmanagerWebhook.Alert();
        a.setStatus("firing");
        Map<String, String> labels = new java.util.HashMap<>();
        labels.put("alertname", "X");
        a.setLabels(labels);

        AlertSignal s = normalize(a).get(0);
        labels.put("extra", "changed");
        assertThat(s.labels()).doesNotContainKey("extra");
    }

    @Test
    @DisplayName("不支持的负载类型拒绝")
    void unsupportedPayloadRejected() {
        assertThatThrownBy(() -> adapter.normalize("not-a-webhook"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("supports 只认 AlertmanagerWebhook")
    void supportsOnlyAlertmanagerWebhook() {
        assertThat(adapter.supports(new AlertmanagerWebhook())).isTrue();
        assertThat(adapter.supports("x")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }
}