package com.devops.agent.domain.alert.service;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.notify.DingTalkNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AlertService} 单元测试。
 *
 * <h3>为什么这是 L2 最该补测试的一个类</h3>
 * 它是<b>告警链路上唯一的写入方</b>：Alertmanager 推来的每条告警都要经过它
 * 决定「是新告警还是重复」「要不要建单」「要不要强提醒」。
 * 而在此之前它<b>一行测试都没有</b>——514 行、五个配置开关、三条分支路径。
 *
 * <h3>这里的错误都是「静默」的</h3>
 * 与页面 bug 不同，本类的缺陷不会有人报障：
 * <ul>
 *   <li><b>去重键算错</b> → 同一个故障反复建单，值班人被工单刷屏，
 *       或者反过来：两个不同故障被判成同一个，第二个故障<b>永远不会有人知道</b>；</li>
 *   <li><b>聚合窗口失效</b> → 一次节点宕机引发十几条不同告警各建一张工单；</li>
 *   <li><b>级别映射塌缩</b> → P0 生产宕机与 P3 磁盘告警建出同优先级的工单，
 *       分级响应无从谈起（这正是 B0 改造要修的问题，这里把它钉住防回退）；</li>
 *   <li><b>建单失败阻塞入库</b> → 告警本身丢失。而告警可见性是这条链路的铁律：
 *       工单是附属增值，<b>告警本体必须先落库</b>。</li>
 * </ul>
 *
 * <p>五个 {@code @Value} 配置项用 {@link ReflectionTestUtils} 注入——
 * 本类不走 Spring 上下文，字段注入不会自动发生，
 * 不设就全是 false/0，测出来的行为与生产完全不同。</p>
 */
@DisplayName("告警服务（L2 告警接入链路唯一写入方）")
class AlertServiceTest {

    private AlertRepository alertRepository;
    private TicketService ticketService;
    private AlertWebSocketNotifier notifier;
    private DingTalkNotifier dingTalk;
    private AlertService service;

    @BeforeEach
    void setUp() {
        alertRepository = mock(AlertRepository.class);
        ticketService = mock(TicketService.class);
        notifier = mock(AlertWebSocketNotifier.class);
        dingTalk = mock(DingTalkNotifier.class);
        service = new AlertService(alertRepository, ticketService, notifier, dingTalk);

        // @Value 字段在非 Spring 环境不会注入，必须显式设成与生产默认值一致
        ReflectionTestUtils.setField(service, "alertEnabled", true);
        ReflectionTestUtils.setField(service, "autoTicketEnabled", true);
        ReflectionTestUtils.setField(service, "alertCreator", "alert-bot");
        ReflectionTestUtils.setField(service, "aggregateEnabled", true);
        ReflectionTestUtils.setField(service, "aggregateWindowMinutes", 5);

        // 默认：无活跃告警、无可聚合的组工单、保存后回填 ID
        when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(Optional.empty());
        when(alertRepository.findActiveGroupTicket(any(), any(), anyInt())).thenReturn(Optional.empty());
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> {
            Alert a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(ticketService.createTicket(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    DevOpsTicket t = new DevOpsTicket();
                    t.setId("TK-2026-0001");
                    t.setPriority(inv.getArgument(1));
                    return t;
                });
    }

    // ==================== 夹具 ====================

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

    /** 取本次 save 进去的告警实体 */
    private Alert savedAlert() {
        ArgumentCaptor<Alert> cap = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(cap.capture());
        return cap.getValue();
    }

    // ==================================================================

    @Nested
    @DisplayName("入口防护")
    class Guards {

        @Test
        @DisplayName("总开关关闭时完全不处理 —— 但这不该让 Webhook 返回非 200")
        void disabledSkipsEverything() {
            ReflectionTestUtils.setField(service, "alertEnabled", false);

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCpu", "service", "api"))));

            verify(alertRepository, never()).save(any());
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("空负载/ null 不抛异常 —— 抛了 Prometheus 会当失败无限重推")
        void emptyPayloadIsSafe() {
            assertDoesNotThrow(() -> service.processWebhook(null));
            assertDoesNotThrow(() -> service.processWebhook(new AlertmanagerWebhook()));
            assertDoesNotThrow(() -> service.processWebhook(webhook()));
            verify(alertRepository, never()).save(any());
        }

        @Test
        @DisplayName("缺 alertname 的告警被跳过，但不影响同批次其余告警")
        void missingAlertNameSkipsOnlyThatOne() {
            service.processWebhook(webhook(
                    incoming("firing", labels("service", "api")),               // 无 alertname
                    incoming("firing", labels("alertname", "HighCpu", "service", "api"))));

            // 只有合法的那条入库
            verify(alertRepository, times(1)).save(any(Alert.class));
        }

        @Test
        @DisplayName("单条处理异常不中断整批 —— 一条脏数据不能让同批其他告警全丢")
        void oneFailureDoesNotAbortBatch() {
            // 第一条 save 抛异常，第二条应仍被处理
            when(alertRepository.save(any(Alert.class)))
                    .thenThrow(new RuntimeException("db down"))
                    .thenAnswer(inv -> {
                        Alert a = inv.getArgument(0);
                        a.setId(2L);
                        return a;
                    });

            assertDoesNotThrow(() -> service.processWebhook(webhook(
                    incoming("firing", labels("alertname", "A", "service", "api")),
                    incoming("firing", labels("alertname", "B", "service", "api")))));

            verify(alertRepository, times(2)).save(any(Alert.class));
        }
    }

    @Nested
    @DisplayName("去重")
    class Dedup {

        @Test
        @DisplayName("同一告警重复推送：只递增次数，不重复建单")
        void duplicateIncrementsInsteadOfCreating() {
            Alert existing = new Alert();
            existing.setId(9L);
            existing.setOccurrenceCount(3);
            when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(Optional.of(existing));

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCpu", "service", "api", "instance", "node-1"))));

            verify(alertRepository).incrementOccurrence(9L);
            verify(alertRepository, never()).save(any());
            // 重复告警不该再建一张工单——否则一次持续故障会刷屏
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
            verify(notifier).broadcastUpdate(existing);
        }

        @Test
        @DisplayName("标签顺序不同但内容相同 → 同一个去重键（否则同一故障会建两张单）")
        void dedupKeyIsOrderInsensitive() {
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCpu", "service", "api", "instance", "node-1", "pod", "p1"))));
            String key1 = savedAlert().getDedupKey();

            setUp(); // 重置 mock
            service.processWebhook(webhook(incoming("firing",
                    labels("pod", "p1", "instance", "node-1", "service", "api", "alertname", "HighCpu"))));
            String key2 = savedAlert().getDedupKey();

            assertEquals(key1, key2, "标签顺序不应影响去重键");
        }

        @Test
        @DisplayName("severity 变化不改变去重键 —— 同一故障升级不该被当成新告警")
        void severityDoesNotAffectDedupKey() {
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCpu", "service", "api", "severity", "warning"))));
            String warn = savedAlert().getDedupKey();

            setUp();
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCpu", "service", "api", "severity", "critical"))));
            String crit = savedAlert().getDedupKey();

            assertEquals(warn, crit);
        }

        @Test
        @DisplayName("不同实例 → 不同去重键（否则第二个实例的故障永远没人知道）")
        void differentInstanceYieldsDifferentKey() {
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCpu", "service", "api", "instance", "node-1"))));
            String k1 = savedAlert().getDedupKey();

            setUp();
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCpu", "service", "api", "instance", "node-2"))));
            String k2 = savedAlert().getDedupKey();

            assertNotEquals(k1, k2);
        }
    }

    @Nested
    @DisplayName("恢复")
    class Resolve {

        @Test
        @DisplayName("resolved 推送把活跃告警标记恢复并广播")
        void resolvedMarksAlert() {
            Alert active = new Alert();
            active.setId(7L);
            when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(Optional.of(active));

            service.processWebhook(webhook(incoming("resolved",
                    labels("alertname", "HighCpu", "service", "api"))));

            verify(alertRepository).resolve(7L);
            verify(notifier).broadcastResolved(active);
            // 恢复不该建单，也不该新增告警记录
            verify(alertRepository, never()).save(any());
        }

        @Test
        @DisplayName("resolved 但无活跃记录时静默返回，不报错")
        void resolvedWithoutActiveIsSilent() {
            assertDoesNotThrow(() -> service.processWebhook(webhook(incoming("resolved",
                    labels("alertname", "Gone", "service", "api")))));

            verify(alertRepository, never()).resolve(anyLong());
            verify(alertRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("级别映射（B0 改造：不再塌缩）")
    class LevelMapping {

        private String priorityOfSeverity(String severity) {
            setUp();
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api", "severity", severity))));
            ArgumentCaptor<String> pr = ArgumentCaptor.forClass(String.class);
            verify(ticketService).createTicket(anyString(), pr.capture(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
            return pr.getValue();
        }

        @Test
        @DisplayName("critical→P0、warning→P2、info→P4，且 P0 与 P1 建出的工单优先级不同")
        void severityMapsToDistinctPriorities() {
            // 塌缩是这里最危险的回退：一条 P0 生产宕机与一条 P1 告警
            // 若建出同优先级工单，分级响应就形同虚设
            String p0 = priorityOfSeverity("critical");
            String p1 = priorityOfSeverity("P1");
            assertNotEquals(p0, p1, "P0 与 P1 必须映射到不同的工单优先级");
        }

        @Test
        @DisplayName("已是 P0-P4 格式的 severity 直接采用，不再二次映射")
        void explicitLevelPassesThrough() {
            setUp();
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api", "severity", "P1"))));
            assertEquals("P1", savedAlert().getLevel());
        }

        @Test
        @DisplayName("severity 缺失或无法识别时降级为 P3，而不是当成 P0 惊动所有人")
        void unknownSeverityFallsBackToP3() {
            setUp();
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api"))));
            assertEquals("P3", savedAlert().getLevel());

            setUp();
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api", "severity", "whatever"))));
            assertEquals("P3", savedAlert().getLevel());
        }
    }

    @Nested
    @DisplayName("自动建单与聚合抑制")
    class TicketCreation {

        @Test
        @DisplayName("新告警先落库再建单，且建单人是 alert-bot")
        void newAlertPersistsThenCreatesTicket() {
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "PodCrash", "service", "order", "module", "pod",
                            "severity", "critical"))));

            Alert saved = savedAlert();
            assertEquals("FIRING", saved.getStatus());
            assertEquals("prometheus", saved.getSource());
            assertEquals(1, saved.getOccurrenceCount());

            ArgumentCaptor<String> creator = ArgumentCaptor.forClass(String.class);
            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), creator.capture());
            assertEquals("alert-bot", creator.getValue());

            verify(notifier).broadcastNew(saved);
        }

        @Test
        @DisplayName("module 标签大写归一；缺失时为 OTHER")
        void moduleIsNormalized() {
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api", "module", "  db  "))));
            assertEquals("DB", savedAlert().getModule());

            setUp();
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api"))));
            assertEquals("OTHER", savedAlert().getModule());
        }

        @Test
        @DisplayName("建单失败不影响告警入库 —— 告警本体有效，工单是附属增值")
        void ticketFailureDoesNotBlockAlertPersistence() {
            when(ticketService.createTicket(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("ticket service down"));

            assertDoesNotThrow(() -> service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api")))));

            // 这是告警可见性铁律：建单挂了，告警仍必须能在列表里看到，
            // 否则运维连「有这么回事」都不知道
            verify(alertRepository).save(any(Alert.class));
        }

        @Test
        @DisplayName("关闭自动建单后仍入库去重，只是不建单")
        void autoTicketDisabledStillPersists() {
            ReflectionTestUtils.setField(service, "autoTicketEnabled", false);

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api"))));

            verify(alertRepository).save(any(Alert.class));
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("聚合窗口内同 service+module 的新告警挂到已有工单，不再新建")
        void aggregatesIntoExistingGroupTicket() {
            Alert group = new Alert();
            group.setId(100L);
            group.setTicketId("TK-2026-0001");
            when(alertRepository.findActiveGroupTicket(eq("order"), eq("POD"), eq(5)))
                    .thenReturn(Optional.of(group));

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "AnotherSymptom", "service", "order", "module", "pod"))));

            // 一次节点宕机会引发十几条不同告警，各建一张工单会把值班人淹没
            verify(alertRepository).updateTicketId(1L, "TK-2026-0001");
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
            // 但被抑制的告警本身仍然入库、仍然可见
            verify(alertRepository).save(any(Alert.class));
        }

        @Test
        @DisplayName("聚合命中时在组工单上留痕，让人看得出这张单关联了多条告警")
        void aggregationLeavesTrace() {
            Alert group = new Alert();
            group.setId(100L);
            group.setTicketId("TK-2026-0001");
            when(alertRepository.findActiveGroupTicket(any(), any(), anyInt()))
                    .thenReturn(Optional.of(group));

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "AnotherSymptom", "service", "order", "module", "pod"))));

            verify(ticketService).recordActivity(eq("TK-2026-0001"), anyString(), anyString(),
                    anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("关闭聚合后回退为每条各建一单")
        void aggregationDisabledCreatesOwnTicket() {
            ReflectionTestUtils.setField(service, "aggregateEnabled", false);
            Alert group = new Alert();
            group.setId(100L);
            group.setTicketId("TK-2026-0001");
            when(alertRepository.findActiveGroupTicket(any(), any(), anyInt()))
                    .thenReturn(Optional.of(group));

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "order", "module", "pod"))));

            verify(alertRepository, never()).updateTicketId(anyLong(), anyString());
            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("组告警存在但没有工单 ID 时不聚合 —— 挂到 null 工单等于丢失关联")
        void groupWithoutTicketIdDoesNotAggregate() {
            Alert group = new Alert();
            group.setId(100L);
            group.setTicketId(null);
            when(alertRepository.findActiveGroupTicket(any(), any(), anyInt()))
                    .thenReturn(Optional.of(group));

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "order", "module", "pod"))));

            verify(alertRepository, never()).updateTicketId(anyLong(), any());
            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }
    }
}
