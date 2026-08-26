package com.devops.agent.eval;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.alert.service.AlertService;
import com.devops.agent.domain.alert.service.AlertWebSocketNotifier;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.notify.DingTalkNotifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警降噪回放评测集（D3，2026-08-26）。
 *
 * <p>数据：{@code src/test/resources/alert_replay_dataset.json} —— 6 个场景，
 * 对齐蓝图 §一.2「5 分钟内相同 Pod/IP 的 1000 条报错压缩为 1 个总事件」与
 * PRD §7.4 三层去重语义（同键去重 / 跨键同源聚合 / 跨源独立）。</p>
 *
 * <p>回放方式：逐条调用生产 {@link AlertService#processWebhook}，mock 仓储层
 * 记录真实交互（save / findActiveByDedupKey / incrementOccurrence），
 * 断言三条关键不变量：</p>
 * <ul>
 *   <li><b>distinctKeys</b>：新建告警去重键去重数（= 应产生的事件数）</li>
 *   <li><b>ticketsCreated</b>：实际建单数（同键命中不重复建单）</li>
 *   <li><b>occurrenceCount</b>：同键累计计次（增量语义）</li>
 * </ul>
 */
@DisplayName("告警降噪回放评测集（D3）")
class AlertReplayEvaluationTest {

    private AlertRepository alertRepository;
    private TicketService ticketService;
    private AlertService service;

    /** 已保存的告警（模拟持久层状态，供 findActiveByDedupKey 命中） */
    private final List<Alert> savedAlerts = new ArrayList<>();
    private int incrementCalls = 0;

    /** 回放场景（与 alert_replay_dataset.json 结构对应） */
    public record ReplayEvent(int seq, String alertName, String service, Map<String, String> labels,
                              String severity, Integer atMinutes) {
    }

    public record ReplayScenario(String scenario, int windowMinutes, List<ReplayEvent> events,
                                 Map<String, Object> expect) {
    }

    @BeforeEach
    void setUp() {
        alertRepository = mock(AlertRepository.class);
        ticketService = mock(TicketService.class);
        AlertWebSocketNotifier notifier = mock(AlertWebSocketNotifier.class);
        DingTalkNotifier dingTalk = mock(DingTalkNotifier.class);
        service = new AlertService(alertRepository, ticketService, notifier, dingTalk);

        ReflectionTestUtils.setField(service, "alertEnabled", true);
        ReflectionTestUtils.setField(service, "autoTicketEnabled", true);
        ReflectionTestUtils.setField(service, "alertCreator", "alert-bot");
        ReflectionTestUtils.setField(service, "aggregateEnabled", true);
        ReflectionTestUtils.setField(service, "aggregateWindowMinutes", 5);

        savedAlerts.clear();
        incrementCalls = 0;

        // 模拟持久层：save 记录；findActiveByDedupKey 命中已保存的同键活跃告警；
        // incrementOccurrence 递增计数
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> {
            Alert a = inv.getArgument(0);
            if (a.getId() == null) a.setId((long) (savedAlerts.size() + 1));
            savedAlerts.add(a);
            return a;
        });
        when(alertRepository.findActiveByDedupKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return savedAlerts.stream()
                    .filter(al -> al.getStatus() != null && !"RESOLVED".equals(al.getStatus()))
                    .filter(al -> key.equals(al.getDedupKey()))
                    .findFirst();
        });
        // incrementOccurrence 返回 void：用 doAnswer 计数（when() 不允许 void 方法）
        org.mockito.Mockito.doAnswer(inv -> {
            incrementCalls++;
            return null;
        }).when(alertRepository).incrementOccurrence(any(Long.class));
        when(ticketService.createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    DevOpsTicket t = new DevOpsTicket();
                    t.setId("TKT-EVAL-" + (savedAlerts.size()));
                    return t;
                });
    }

    // ==================== 夹具 ====================

    private AlertmanagerWebhook.Alert incoming(ReplayEvent e) {
        AlertmanagerWebhook.Alert a = new AlertmanagerWebhook.Alert();
        a.setStatus("firing");
        a.setLabels(new LinkedHashMap<>(e.labels()));
        a.getLabels().putIfAbsent("alertname", e.alertName());
        a.getLabels().putIfAbsent("service", e.service());
        a.getLabels().putIfAbsent("severity", e.severity());
        a.setStartsAt(OffsetDateTime.parse("2026-08-25T09:00:00Z")
                .plusMinutes(e.atMinutes() == null ? 0 : e.atMinutes()));
        a.setFingerprint("fp-" + e.seq());
        return a;
    }

    private long distinctSavedKeys() {
        return savedAlerts.stream().map(Alert::getDedupKey).distinct().count();
    }

    // ==================== 用例（与数据集 6 场景一一对应）====================

    @Test
    @DisplayName("场景 1：同源风暴 3 条同键 → 1 事件 1 工单，其余计次")
    void scenario1_sameKeyStorm() {
        ReplayEvent e = new ReplayEvent(1, "K8sPodCrashLooping", "payment-service",
                Map.of("instance", "10.0.3.21", "pod", "payment-service-6f9d8"), "critical", null);
        service.processWebhook(webhookOf(e));
        service.processWebhook(webhookOf(e));
        service.processWebhook(webhookOf(e));

        assertEquals(1, distinctSavedKeys(), "同键风暴只产生 1 个事件");
        verify(ticketService, times(1)).createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString());
        assertEquals(2, incrementCalls, "第 2、3 条同键应走计次而非建单");
    }

    @Test
    @DisplayName("场景 2：同 Pod 不同 alertName → 不同 dedupKey 各自建单")
    void scenario2_crossKeySameSource() {
        service.processWebhook(webhookOf(new ReplayEvent(1, "K8sPodCrashLooping", "payment-service",
                Map.of("instance", "10.0.3.21", "pod", "payment-service-6f9d8"), "critical", null)));
        service.processWebhook(webhookOf(new ReplayEvent(2, "K8sPodOOMKilled", "payment-service",
                Map.of("instance", "10.0.3.21", "pod", "payment-service-6f9d8"), "critical", null)));

        assertEquals(2, distinctSavedKeys(), "不同 alertName 应产生 2 个 dedupKey");
        verify(ticketService, times(2)).createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("场景 3：不同服务 → 跨源独立建单（不误并）")
    void scenario3_crossSource() {
        service.processWebhook(webhookOf(new ReplayEvent(1, "K8sPodCrashLooping", "payment-service",
                Map.of("instance", "10.0.3.21"), "critical", null)));
        service.processWebhook(webhookOf(new ReplayEvent(2, "MySQLReplicationLag", "mysql-prod",
                Map.of("instance", "10.0.1.5"), "warning", null)));

        assertEquals(2, distinctSavedKeys());
        verify(ticketService, times(2)).createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("场景 4：severity 变化不改变 dedupKey（蓝图：排除 severity）")
    void scenario4_severityNotInKey() {
        service.processWebhook(webhookOf(new ReplayEvent(1, "RedisMemoryHigh", "redis-cache",
                Map.of("instance", "10.0.2.9"), "warning", null)));
        service.processWebhook(webhookOf(new ReplayEvent(2, "RedisMemoryHigh", "redis-cache",
                Map.of("instance", "10.0.2.9"), "critical", null)));

        assertEquals(1, distinctSavedKeys(), "severity 不参与去重键");
        verify(ticketService, times(1)).createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString());
        assertEquals(1, incrementCalls);
    }

    @Test
    @DisplayName("场景 5：labels 顺序不同但内容相同 → 同一 dedupKey（TreeMap 确定性）")
    void scenario5_labelOrderIrrelevant() {
        service.processWebhook(webhookOf(new ReplayEvent(1, "CertExpiring", "api.example.com",
                Map.of("pod", "a", "instance", "10.0.0.1"), "warning", null)));
        service.processWebhook(webhookOf(new ReplayEvent(2, "CertExpiring", "api.example.com",
                Map.of("instance", "10.0.0.1", "pod", "a"), "warning", null)));

        assertEquals(1, distinctSavedKeys(), "labels 顺序无关（TreeMap 排序）");
        verify(ticketService, times(1)).createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("数据集完整性：6 个场景齐全、期望值结构合法")
    void datasetIntegrity() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ReplayScenario> scenarios = mapper.readValue(
                new ClassPathResource("alert_replay_dataset.json").getInputStream(),
                new TypeReference<List<ReplayScenario>>() {
                });
        assertEquals(6, scenarios.size(), "降噪回放集应有 6 个场景");
        for (ReplayScenario s : scenarios) {
            assertTrue(s.events().size() >= 2, "场景应含至少 2 条事件: " + s.scenario());
            assertTrue(s.expect().containsKey("distinctKeys"), "缺少 distinctKeys 期望: " + s.scenario());
            assertTrue(s.expect().containsKey("ticketsCreated"), "缺少 ticketsCreated 期望: " + s.scenario());
        }
        assertTrue(scenarios.stream().anyMatch(s -> s.scenario().contains("同源风暴")));
        assertTrue(scenarios.stream().anyMatch(s -> s.scenario().contains("跨键同源")));
        assertTrue(scenarios.stream().anyMatch(s -> s.scenario().contains("跨源独立")));
        assertTrue(scenarios.stream().anyMatch(s -> s.scenario().contains("severity")));
        assertTrue(scenarios.stream().anyMatch(s -> s.scenario().contains("时间窗")));
        assertTrue(scenarios.stream().anyMatch(s -> s.scenario().contains("标签顺序")));
    }

    private AlertmanagerWebhook webhookOf(ReplayEvent e) {
        AlertmanagerWebhook w = new AlertmanagerWebhook();
        w.setStatus("firing");
        w.setAlerts(List.of(incoming(e)));
        return w;
    }
}
