package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.DTO.ZabbixWebhook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 告警源注册表选源测试（L2 跨源接缝的第二源落地）。
 *
 * <h3>要防住的缺陷</h3>
 * <p>
 * 注册表的逻辑很短，但错的代价很大：选错源会把 Zabbix 事件当成 Alertmanager
 * 负载解析（或反之），结果不是报错而是<b>一条字段全空的告警</b>；选不中却静默
 * 跳过则表现为「告警发出来平台没反应」。故本类把「选谁」与「选不中怎么办」
 * 都钉住，包括错误信息里必须列出已注册来源（接入排错的唯一线索）。
 * </p>
 */
@DisplayName("AlertSourceNormalizer 选源")
class AlertSourceNormalizerTest {

    private final AlertmanagerSourceAdapter alertmanager = new AlertmanagerSourceAdapter();
    private final ZabbixSourceAdapter zabbix = new ZabbixSourceAdapter();
    private final AlertSourceNormalizer normalizer =
            new AlertSourceNormalizer(List.of(alertmanager, zabbix));
    private final ObjectMapper mapper = new ObjectMapper();

    private ZabbixWebhook zabbixPayload(String json) {
        try {
            return mapper.readValue(json, ZabbixWebhook.class);
        } catch (Exception e) {
            throw new IllegalStateException("样本 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private static AlertmanagerWebhook alertmanagerPayload() {
        AlertmanagerWebhook webhook = new AlertmanagerWebhook();
        AlertmanagerWebhook.Alert a = new AlertmanagerWebhook.Alert();
        a.setStatus("firing");
        a.setLabels(java.util.Map.of("alertname", "HighCpu", "service", "api", "severity", "critical"));
        a.setStartsAt(OffsetDateTime.parse("2026-10-14T09:00:00Z"));
        webhook.setAlerts(List.of(a));
        return webhook;
    }

    @Test
    @DisplayName("Alertmanager 负载 → prometheus 来源（第二源上线不改变原有解析路径）")
    void dispatchesAlertmanager() {
        List<AlertSignal> signals = normalizer.normalize(alertmanagerPayload());

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).source()).isEqualTo("prometheus");
        assertThat(signals.get(0).alertName()).isEqualTo("HighCpu");
    }

    @Test
    @DisplayName("Zabbix 负载 → zabbix 来源")
    void dispatchesZabbix() {
        List<AlertSignal> signals = normalizer.normalize(zabbixPayload("""
                {"trigger_name": "DB Down", "host_name": "db-01", "event_severity": "High",
                 "event_status": "PROBLEM", "event_date": "2026.10.14", "event_time": "09:15:00"}
                """));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).source()).isEqualTo("zabbix");
    }

    @Test
    @DisplayName("选不中 → 抛异常并把已注册来源列进消息（不许静默跳过）")
    void unknownPayloadIsRejectedLoudly() {
        assertThatThrownBy(() -> normalizer.normalize("随便一段文本"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有适配器认识这份负载")
                .hasMessageContaining("prometheus")
                .hasMessageContaining("zabbix");
    }

    @Test
    @DisplayName("空负载 → 明确报「负载为空」，不是 NullPointerException")
    void nullPayloadIsRejected() {
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("负载为空");
    }

    @Test
    @DisplayName("容器漏装适配器（空注册表）时同样报错 —— 精确原因即「没有来源可用」")
    void emptyRegistryIsVisible() {
        AlertSourceNormalizer empty = new AlertSourceNormalizer(List.of());

        assertThat(empty.size()).isZero();
        assertThatThrownBy(() -> empty.normalize(alertmanagerPayload()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已注册来源");
    }

    @Test
    @DisplayName("来源清单来自适配器自身（接入文档与错误提示共用一份事实）")
    void knownSourcesReportedByAdapters() {
        assertThat(normalizer.knownSources()).containsExactlyInAnyOrder("prometheus", "zabbix");
        assertThat(normalizer.size()).isEqualTo(2);
    }
}
