package com.devops.agent.domain.alert.service;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.healing.HealingAutoTrigger;
import com.devops.agent.domain.notify.Notifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4-1 欠账清偿（报告 114 §六候选 2）：告警 → 策略引擎的<b>触发语义钉测</b>。
 *
 * <h3>钉什么</h3>
 * 引擎挂点的分支语义目前只写在注释里（「去重与聚合抑制分支在上方已
 * return——两条旁路天然不重复触发」）。注释不会红，测试才会。本类把以下契约钉死：
 * <ul>
 *   <li>新告警正常路径 → 引擎被调一次，拿到的就是落库那条告警；</li>
 *   <li>重复告警（dedup 命中）→ 引擎零调用——同告警窗口内不应重复自愈；</li>
 *   <li>聚合抑制（风暴归并到组工单）→ 引擎零调用——根源一条策略求值即可，
 *       被抑制的从属告警不各触发一次（与 S2-1 诊断同一条「风暴旁路」语义）；</li>
 *   <li>引擎缺席 / 引擎炸 → 告警入库与建单主链照走（不可阻断铁律）。</li>
 * </ul>
 *
 * <p>装配方式与 {@link AlertServiceTest} 同款：直连五参构造 +
 * {@link ReflectionTestUtils} 显式填字段（含 required=false 的引擎字段）。</p>
 */
@DisplayName("告警→策略引擎 触发语义（S4-1 挂点钉测）")
class AlertHealingTriggerWiringTest {

    private AlertRepository alertRepository;
    private TicketService ticketService;
    private AlertWebSocketNotifier notifier;
    private Notifier dingTalk;
    private com.devops.agent.application.diagnosis.DiagnosisOrchestrator diagnosisOrchestrator;
    private HealingAutoTrigger healingAutoTrigger;
    private AlertService service;

    @BeforeEach
    void setUp() {
        alertRepository = mock(AlertRepository.class);
        ticketService = mock(TicketService.class);
        notifier = mock(AlertWebSocketNotifier.class);
        dingTalk = mock(Notifier.class);
        diagnosisOrchestrator = mock(com.devops.agent.application.diagnosis.DiagnosisOrchestrator.class);
        healingAutoTrigger = mock(HealingAutoTrigger.class);
        when(diagnosisOrchestrator.submit(anyLong(), any(), anyString())).thenReturn("trace-diag-1");
        service = new AlertService(alertRepository, ticketService, notifier, dingTalk,
                diagnosisOrchestrator);

        // @Value 字段在非 Spring 环境不会注入，显式设成与生产默认值一致
        ReflectionTestUtils.setField(service, "alertEnabled", true);
        ReflectionTestUtils.setField(service, "autoTicketEnabled", true);
        ReflectionTestUtils.setField(service, "alertCreator", "alert-bot");
        ReflectionTestUtils.setField(service, "aggregateEnabled", true);
        ReflectionTestUtils.setField(service, "aggregateWindowMinutes", 5);
        ReflectionTestUtils.setField(service, "autoDiagnoseEnabled", true);
        // required=false 的引擎字段：装上 mock 才能断言调用面
        ReflectionTestUtils.setField(service, "healingAutoTrigger", healingAutoTrigger);

        when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(Optional.empty());
        when(alertRepository.findActiveGroupTicket(any(), any(), anyInt())).thenReturn(Optional.empty());
        // P2-1:save 退役,改 mock insertOrIncrement(默认=新插入)
        when(alertRepository.insertOrIncrement(any(Alert.class))).thenAnswer(inv -> {
            Alert a = inv.getArgument(0);
            a.setId(1L);
            return true;
        });
        // assignee 传 null —— anyString() 不匹配 null，必须 any()（AlertServiceTest 同款坑）
        when(ticketService.createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    DevOpsTicket t = new DevOpsTicket();
                    t.setId("TK-2026-0001");
                    return t;
                });
    }

    private static AlertmanagerWebhook.Alert incoming(String status, Map<String, String> labels) {
        AlertmanagerWebhook.Alert a = new AlertmanagerWebhook.Alert();
        a.setStatus(status);
        a.setLabels(labels);
        a.setStartsAt(OffsetDateTime.parse("2026-08-25T09:00:00Z"));
        a.setFingerprint("fp-" + labels.hashCode());
        return a;
    }

    private static Map<String, String> labels(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static AlertmanagerWebhook webhook(AlertmanagerWebhook.Alert... alerts) {
        AlertmanagerWebhook w = new AlertmanagerWebhook();
        w.setAlerts(List.of(alerts));
        return w;
    }

    private AlertmanagerWebhook firingPodCrash() {
        return webhook(incoming("firing", labels(
                "alertname", "PodCrashLooping", "service", "order-service",
                "module", "k8s", "severity", "critical")));
    }

    @Test
    @DisplayName("新告警正常路径：引擎被调一次，且拿到的正是落库那条（id/service 对得上）")
    void newAlertTriggersEngineOnce() {
        service.processWebhook(firingPodCrash());

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(healingAutoTrigger).onAlertFired(captor.capture());
        assertEquals(1L, captor.getValue().getId());
        assertEquals("order-service", captor.getValue().getService());
    }

    @Test
    @DisplayName("重复告警（dedup 命中）只叠加次数：引擎零调用——同告警窗口内不应重复自愈")
    void dedupRepeatSkipsEngine() {
        Alert existing = new Alert();
        existing.setId(9L);
        when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(Optional.of(existing));
        // P2-1:分支依据 = upsert 返回 false(同键冲突计次,去重路径)
        when(alertRepository.insertOrIncrement(any(Alert.class))).thenReturn(false);

        service.processWebhook(firingPodCrash());

        // 计次由 upsert SQL 原子完成;引擎零调用不变
        verify(alertRepository).insertOrIncrement(any(Alert.class));
        verify(healingAutoTrigger, never()).onAlertFired(any());
    }

    @Test
    @DisplayName("聚合抑制（归并组工单）：引擎零调用——风暴根源一条求值即可，从属告警不各自触发")
    void aggregatedAlertSkipsEngine() {
        Alert group = new Alert();
        group.setId(7L);
        group.setTicketId("TK-GROUP");
        when(alertRepository.findActiveGroupTicket(any(), any(), anyInt()))
                .thenReturn(Optional.of(group));

        service.processWebhook(webhook(incoming("firing", labels(
                "alertname", "HighErrorRate", "service", "order-service", "severity", "critical")))) ;

        // 入库仍发生（告警可见性铁律），但策略求值与独立诊断一样走风暴旁路
        verify(alertRepository).updateTicketId(1L, "TK-GROUP");
        verify(healingAutoTrigger, never()).onAlertFired(any());
    }

    @Test
    @DisplayName("引擎缺席（required=false 未装配）：入库与建单主链照常——降级即契约")
    void absentEngineDegradesGracefully() {
        ReflectionTestUtils.setField(service, "healingAutoTrigger", null);

        assertDoesNotThrow(() -> service.processWebhook(firingPodCrash()));

        verify(alertRepository).insertOrIncrement(any(Alert.class));
        verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("引擎炸（抛出异常）：告警入库/建单主链不被反噬——不可阻断铁律的最后一道")
    void explodingEngineNeverBlocksIngress() {
        org.mockito.Mockito.doThrow(new RuntimeException("模拟策略池提交失败"))
                .when(healingAutoTrigger).onAlertFired(any());

        assertDoesNotThrow(() -> service.processWebhook(firingPodCrash()));

        verify(alertRepository).insertOrIncrement(any(Alert.class));
        verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString());
    }
}
