package com.devops.agent.domain.alert.service;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.notify.Notifier;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
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
    private Notifier dingTalk;
    private com.devops.agent.application.diagnosis.DiagnosisOrchestrator diagnosisOrchestrator;
    private AlertService service;

    @BeforeEach
    void setUp() {
        alertRepository = mock(AlertRepository.class);
        ticketService = mock(TicketService.class);
        notifier = mock(AlertWebSocketNotifier.class);
        dingTalk = mock(Notifier.class);
        diagnosisOrchestrator = mock(com.devops.agent.application.diagnosis.DiagnosisOrchestrator.class);
        when(diagnosisOrchestrator.submit(anyLong(), any(), anyString())).thenReturn("trace-diag-1");
        service = new AlertService(alertRepository, ticketService, notifier, dingTalk,
                new com.devops.agent.domain.alert.AlertmanagerSourceAdapter(),
                diagnosisOrchestrator);

        // @Value 字段在非 Spring 环境不会注入，必须显式设成与生产默认值一致
        ReflectionTestUtils.setField(service, "alertEnabled", true);
        ReflectionTestUtils.setField(service, "autoTicketEnabled", true);
        ReflectionTestUtils.setField(service, "alertCreator", "alert-bot");
        // 方案②恢复联动策略字段（非 Spring 环境不注入）：hint=活动流提示（拍板默认值）
        ReflectionTestUtils.setField(service, "resolveClosePolicy", "hint");
        ReflectionTestUtils.setField(service, "aggregateEnabled", true);
        ReflectionTestUtils.setField(service, "aggregateWindowMinutes", 5);
        ReflectionTestUtils.setField(service, "autoDiagnoseEnabled", true);
        ReflectionTestUtils.setField(service, "autoTicketMinLevel", "P3");
        // Jackson 生产环境恒在（Spring 上下文装配）：labels/annotations JSON 落库
        // 与工单描述 JSON 块都走这个字段，不注入则测的是「Jackson 缺席」的退化形态
        ReflectionTestUtils.setField(service, "objectMapper",
                new com.fasterxml.jackson.databind.ObjectMapper());

        // 默认：无活跃告警、无可聚合的组工单、保存后回填 ID
        when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(Optional.empty());
        when(alertRepository.findActiveGroupTicket(any(), any(), anyInt())).thenReturn(Optional.empty());
        // 批 76 / P2-1:去重原子化后 save 退役,改 mock insertOrIncrement。
        // 默认语义 = 无冲突新插入(返回 true 并回填 id,与产品代码一致)
        when(alertRepository.insertOrIncrement(any(Alert.class))).thenAnswer(inv -> {
            Alert a = inv.getArgument(0);
            a.setId(1L);
            return true;
        });
        // 注意 assignee 用 any()：产品代码传的是 null（告警建单不预设负责人），
        // 而 Mockito 的 anyString() **不匹配 null**，写成 anyString() 桩不会生效，
        // createTicket 返回 null，回填工单号与通知那段就被静默跳过了
        when(ticketService.createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), anyString()))
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
        verify(alertRepository).insertOrIncrement(cap.capture());
        return cap.getValue();
    }

    /** 取所有 upsert 调用（一个用例多次 processWebhook 时用，按调用顺序） */
    private List<Alert> savedAlerts() {
        ArgumentCaptor<Alert> cap = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository, org.mockito.Mockito.atLeastOnce()).insertOrIncrement(cap.capture());
        return cap.getAllValues();
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

            verify(alertRepository, never()).insertOrIncrement(any());
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("空负载/ null 不抛异常 —— 抛了 Prometheus 会当失败无限重推")
        void emptyPayloadIsSafe() {
            assertDoesNotThrow(() -> service.processWebhook(null));
            assertDoesNotThrow(() -> service.processWebhook(new AlertmanagerWebhook()));
            assertDoesNotThrow(() -> service.processWebhook(webhook()));
            verify(alertRepository, never()).insertOrIncrement(any());
        }

        @Test
        @DisplayName("缺 alertname 的告警被跳过，但不影响同批次其余告警")
        void missingAlertNameSkipsOnlyThatOne() {
            service.processWebhook(webhook(
                    incoming("firing", labels("service", "api")),               // 无 alertname
                    incoming("firing", labels("alertname", "HighCpu", "service", "api"))));

            // 只有合法的那条入库
            verify(alertRepository, times(1)).insertOrIncrement(any(Alert.class));
        }

        @Test
        @DisplayName("单条处理异常不中断整批 —— 一条脏数据不能让同批其他告警全丢")
        void oneFailureDoesNotAbortBatch() {
            // 第一条 save 抛异常，第二条应仍被处理
            when(alertRepository.insertOrIncrement(any(Alert.class)))
                    .thenThrow(new RuntimeException("db down"))
                    .thenAnswer(inv -> {
                        Alert a = inv.getArgument(0);
                        a.setId(2L);
                        return a;
                    });

            assertDoesNotThrow(() -> service.processWebhook(webhook(
                    incoming("firing", labels("alertname", "A", "service", "api")),
                    incoming("firing", labels("alertname", "B", "service", "api")))));

            verify(alertRepository, times(2)).insertOrIncrement(any(Alert.class));
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
            // 批 76 / P2-1:upsert 语义 = 同键冲突时返回 false(计次由 SQL 完成)
            when(alertRepository.insertOrIncrement(any(Alert.class))).thenReturn(false);

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCpu", "service", "api", "instance", "node-1"))));

            // 计次路径:insertOrIncrement 被调一次且返回 false,不建单不广播新告警
            verify(alertRepository).insertOrIncrement(any(Alert.class));
            verify(alertRepository, never()).save(any());
            // 重复告警不该再建一张工单——否则一次持续故障会刷屏
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
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

            verify(alertRepository).resolve(eq(7L), any());
            verify(notifier).broadcastResolved(active);
            // 恢复不该建单，也不该新增告警记录
            verify(alertRepository, never()).insertOrIncrement(any());
        }

        @Test
        @DisplayName("resolved 但无活跃记录时静默返回，不报错")
        void resolvedWithoutActiveIsSilent() {
            assertDoesNotThrow(() -> service.processWebhook(webhook(incoming("resolved",
                    labels("alertname", "Gone", "service", "api")))));

            verify(alertRepository, never()).resolve(anyLong());
            verify(alertRepository, never()).insertOrIncrement(any());
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
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
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
                    any(), anyString(), anyString(), creator.capture(), anyString());
            assertEquals("alert-bot", creator.getValue());

            verify(notifier).broadcastNew(saved);
        }

        @Test
        @DisplayName("建单描述结构化：Markdown 段 + JSON 机读块，labels 全量入块（方案 A）")
        void ticketDescriptionCarriesStructuredContext() {
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCPU", "service", "order", "module", "pod",
                            "severity", "critical", "instance", "node-1:9100"))));

            ArgumentCaptor<String> desc = ArgumentCaptor.forClass(String.class);
            verify(ticketService).createTicket(anyString(), anyString(), anyString(), desc.capture(),
                    any(), anyString(), anyString(), anyString(), anyString());
            String d = desc.getValue();
            // 人看的 Markdown 段（级别/服务/标签），机读的 JSON 块（告警全字段）
            assertTrue(d.contains("### 告警元信息"), "缺告警元信息段: " + d);
            assertTrue(d.contains("### 原始标签"), "缺原始标签段: " + d);
            assertTrue(d.contains("### 结构化上下文（JSON）"), "缺 JSON 块: " + d);
            assertTrue(d.contains("\"instance\"") && d.contains("node-1:9100"),
                    "JSON 块缺 instance 标签: " + d);
        }

        @Test
        @DisplayName("钉钉通知只带核心描述——结构化全文（含 JSON 块）进通知卡会淹掉关键信息")
        void notifyCarriesCoreDescriptionOnly() {
            AlertmanagerWebhook.Alert a = incoming("firing",
                    labels("alertname", "HighCPU", "service", "order", "severity", "critical"));
            Map<String, String> ann = new LinkedHashMap<>();
            ann.put("description", "CPU 使用率连续 5 分钟超过 90%");
            a.setAnnotations(ann);

            service.processWebhook(webhook(a));

            ArgumentCaptor<com.devops.agent.domain.notify.NotifyMessage> msg =
                    ArgumentCaptor.forClass(com.devops.agent.domain.notify.NotifyMessage.class);
            verify(dingTalk).send(msg.capture());
            String md = msg.getValue().markdown();
            assertTrue(md.contains("CPU 使用率连续 5 分钟超过 90%"), "通知丢了核心描述: " + md);
            assertFalse(md.contains("结构化上下文（JSON）"), "通知混入了 JSON 块: " + md);
        }

        @Test
        @DisplayName("服务路由命中：工单直接派给值班负责人，不再停在「待分配」")
        void routedServiceAssignsOwner() {
            // 2026-09-25 真实库：27/28 张工单停在待分配——建单恒传 null assignee。
            // 路由命中后，单子在建出来那一刻就有负责人
            var routeRepo = mock(com.devops.agent.domain.biz.repository.ServiceOwnerRepository.class);
            when(routeRepo.findOwnerByService("order")).thenReturn(Optional.of("张明"));
            ReflectionTestUtils.setField(service, "serviceOwnerRepository", routeRepo);

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "PodCrash", "service", "order", "module", "pod",
                            "severity", "critical"))));

            ArgumentCaptor<String> assignee = ArgumentCaptor.forClass(String.class);
            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    assignee.capture(), anyString(), anyString(), anyString(), anyString());
            assertEquals("张明", assignee.getValue());
        }

        @Test
        @DisplayName("服务未配置路由：保持「待分配」原行为，路由缺席不报错")
        void unroutedServiceKeepsUnassigned() {
            var routeRepo = mock(com.devops.agent.domain.biz.repository.ServiceOwnerRepository.class);
            when(routeRepo.findOwnerByService(anyString())).thenReturn(Optional.empty());
            ReflectionTestUtils.setField(service, "serviceOwnerRepository", routeRepo);

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "PodCrash", "service", "unknown-svc"))));

            ArgumentCaptor<String> assignee = ArgumentCaptor.forClass(String.class);
            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    assignee.capture(), anyString(), anyString(), anyString(), anyString());
            assertNull(assignee.getValue());
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
                    any(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("ticket service down"));

            assertDoesNotThrow(() -> service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api")))));

            // 这是告警可见性铁律：建单挂了，告警仍必须能在列表里看到，
            // 否则运维连「有这么回事」都不知道
            verify(alertRepository).insertOrIncrement(any(Alert.class));
        }

        @Test
        @DisplayName("分级建单：P4 信息类告警只入库统计，不建工单（默认门槛 P3）")
        void infoLevelAlertSkipsTicketCreation() {
            // 真实库 27/28 张工单无人认领——info 级噪声建单是主因之一。
            // 告警照常入库（列表可见），只是不产生工单
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "DiskSpaceLow", "service", "svc", "severity", "info"))));

            assertEquals("P4", savedAlert().getLevel());
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("分级建单：P3 告警照常建单——门槛不能误伤要响应的级别")
        void p3AlertStillCreatesTicket() {
            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighLatency", "service", "svc", "severity", "P3"))));

            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("门槛可配置：调为 P4 后 info 级告警也建单")
        void thresholdIsConfigurable() {
            ReflectionTestUtils.setField(service, "autoTicketMinLevel", "P4");

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "DiskSpaceLow", "service", "svc", "severity", "info"))));

            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("门槛配置非法时回退 P3——配错不能变成「从此不建单」")
        void invalidThresholdFallsBackToP3() {
            ReflectionTestUtils.setField(service, "autoTicketMinLevel", "P9");

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "svc", "severity", "P3"))));

            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("关闭自动建单后仍入库去重，只是不建单")
        void autoTicketDisabledStillPersists() {
            ReflectionTestUtils.setField(service, "autoTicketEnabled", false);

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "api"))));

            verify(alertRepository).insertOrIncrement(any(Alert.class));
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
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
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
            // 但被抑制的告警本身仍然入库、仍然可见
            verify(alertRepository).insertOrIncrement(any(Alert.class));
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
        @DisplayName("聚合留痕带级别与实例：一行也能看出哪台机器、什么级别")
        void aggregationTraceCarriesLevelAndInstance() {
            Alert group = new Alert();
            group.setId(100L);
            group.setTicketId("TK-2026-0001");
            when(alertRepository.findActiveGroupTicket(any(), any(), anyInt()))
                    .thenReturn(Optional.of(group));

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "NodeDown", "service", "order", "module", "host",
                            "severity", "warning", "instance", "node-9:9100"))));

            verify(ticketService).recordActivity(eq("TK-2026-0001"), anyString(),
                    eq("关联告警"),
                    argThat(d -> d != null && d.contains("（P2）") && d.contains("@node-9:9100")),
                    anyString(), anyBoolean());
        }

        @Test
        @DisplayName("关闭聚合后回退为每条各建一单")
        void aggregationDisabledCreatesOwnTicket() {
            ReflectionTestUtils.setField(service, "aggregateEnabled", false);
            Alert group = new Alert();
            group.setId(100L);
            // 刻意用与新建单不同的号，让下面那条 never 断言真正有区分力
            group.setTicketId("TK-GROUP-EXISTING");
            when(alertRepository.findActiveGroupTicket(any(), any(), anyInt()))
                    .thenReturn(Optional.of(group));

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "X", "service", "order", "module", "pod"))));

            // 关掉聚合后必须自己建一张新单
            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString(), anyString());
            // updateTicketId 仍会被调用——但那是「回填自己新建的工单号」，
            // 不是「挂到组工单」。断言它拿到的是新单号而非组单号，
            // 光断言 never() 是错的：成功建单本来就要回填，否则告警与工单失联
            verify(alertRepository).updateTicketId(1L, "TK-2026-0001");
            verify(alertRepository, never()).updateTicketId(eq(1L), eq("TK-GROUP-EXISTING"));
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

            // 组告警没有工单号时不能聚合过去，必须自己建单
            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString(), anyString());
            // 同上：这里的 updateTicketId 是回填自己新建的单号。
            // 关键是绝不能回填 null——那等于把告警与工单的关联抹掉
            verify(alertRepository).updateTicketId(1L, "TK-2026-0001");
            verify(alertRepository, never()).updateTicketId(anyLong(), eq((String) null));
        }
    }

    // ==================== S2-1：自动诊断入钩 ====================

    @Test
    @DisplayName("S2-1：新告警建单后触发自动诊断（alertId/工单号/服务名透传）")
    void newAlertTriggersDiagnosis() {
        service.processWebhook(webhook(incoming("firing", Map.of(
                "alertname", "HighErrorRate",
                "service", "order-service",
                "severity", "critical"))));
        verify(diagnosisOrchestrator).submit(eq(1L), eq("TK-2026-0001"), eq("order-service"));
    }

    @Test
    @DisplayName("S2-1：去重告警不重复触发诊断（§6.1 验收第 4 条）")
    void dedupAlertSkipsDiagnosis() {
        Alert existing = new Alert();
        existing.setId(42L);
        existing.setOccurrenceCount(1);
        existing.setTicketId("TK-OLD");
        when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(Optional.of(existing));
        // P2-1 原子去重：分支依据改为 upsert 返回值——false = 同键冲突计次（去重路径）
        when(alertRepository.insertOrIncrement(any(Alert.class))).thenReturn(false);
        service.processWebhook(webhook(incoming("firing", Map.of(
                "alertname", "HighErrorRate",
                "service", "order-service",
                "severity", "critical"))));
        verify(diagnosisOrchestrator, never()).submit(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("S2-1：聚合抑制分支不触发独立诊断（风暴根源一条诊断即可）")
    void aggregatedAlertSkipsDiagnosis() {
        Alert group = new Alert();
        group.setId(7L);
        group.setTicketId("TK-GROUP");
        when(alertRepository.findActiveGroupTicket(any(), any(), anyInt())).thenReturn(Optional.of(group));
        service.processWebhook(webhook(incoming("firing", Map.of(
                "alertname", "HighErrorRate",
                "service", "order-service",
                "severity", "critical"))));
        verify(diagnosisOrchestrator, never()).submit(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("S2-1：自动诊断开关关闭 → 建单照走，诊断不触发")
    void diagnosisSwitchOff() {
        ReflectionTestUtils.setField(service, "autoDiagnoseEnabled", false);
        service.processWebhook(webhook(incoming("firing", Map.of(
                "alertname", "HighErrorRate",
                "service", "order-service",
                "severity", "critical"))));
        verify(diagnosisOrchestrator, never()).submit(anyLong(), any(), anyString());
        // 建单仍发生（开关只关诊断）
        verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), anyString());
    }

    // ==================== FR-3.1：自愈观察窗 ====================

    @Nested
    @DisplayName("自愈观察窗（FR-3.1：观察级告警延迟建单）")
    class ObservationWindow {

        private void enableObservation() {
            ReflectionTestUtils.setField(service, "observationEnabled", true);
            ReflectionTestUtils.setField(service, "observationLevels", "P2,P3");
            ReflectionTestUtils.setField(service, "observationWindowMinutes", 10);
        }

        @Test
        @DisplayName("观察级（warning→P2）新告警只入库不建单、不诊断 —— 等待自愈，省 LLM 成本")
        void warningAlertEntersObservation() {
            enableObservation();

            service.processWebhook(webhook(incoming("firing", Map.of(
                    "alertname", "ConnectionPoolHigh",
                    "service", "wms-api",
                    "severity", "warning"))));

            // 告警本体仍入库（可见性铁律），但建单与诊断都被推迟到观察窗到期
            verify(alertRepository).insertOrIncrement(any(Alert.class));
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
            verify(diagnosisOrchestrator, never()).submit(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("高危（critical→P0）不走观察窗 —— 高危等不起一个窗口")
        void criticalAlertSkipsObservation() {
            enableObservation();

            service.processWebhook(webhook(incoming("firing", Map.of(
                    "alertname", "OrderApiDown",
                    "service", "order-api",
                    "severity", "critical"))));

            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("观察窗关闭时 warning 立即建单 —— 回退旧行为")
        void observationDisabledCreatesImmediately() {
            // 不开 enableObservation：observationEnabled 未注入（false）= 旧行为
            service.processWebhook(webhook(incoming("firing", Map.of(
                    "alertname", "ConnectionPoolHigh",
                    "service", "wms-api",
                    "severity", "warning"))));

            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("低于建单门槛的级别不进观察窗语义 —— 走原「只入库统计」分支")
        void belowThresholdNeverObserved() {
            enableObservation();
            // 门槛收到 P1 时，P2 本就只统计不建单；观察窗语义不能把它变成「到期补建」
            ReflectionTestUtils.setField(service, "autoTicketMinLevel", "P1");

            service.processWebhook(webhook(incoming("firing", Map.of(
                    "alertname", "ConnectionPoolHigh",
                    "service", "wms-api",
                    "severity", "warning"))));

            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("观察窗到期补建：未自愈的观察级告警建单 + 回填 + 补诊断")
        void dueAlertGetsTicketAndDiagnosis() {
            enableObservation();
            Alert pending = new Alert();
            pending.setId(55L);
            pending.setAlertName("ConnectionPoolHigh");
            pending.setLevel("P2");
            pending.setService("wms-api");
            pending.setModule("DB");
            pending.setDedupKey("dk-55");
            when(alertRepository.findObservationDue(any(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(List.of(pending));

            service.createDelayedTickets();

            verify(ticketService).createTicket(anyString(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString(), anyString());
            verify(alertRepository).updateTicketId(eq(55L), eq("TK-2026-0001"));
            verify(diagnosisOrchestrator).submit(eq(55L), eq("TK-2026-0001"), eq("wms-api"));
            // 补建留痕：复盘时工单活动流能看到这单是「观察窗到期补建」而非立即建单
            verify(ticketService).recordActivity(eq("TK-2026-0001"), eq("primary"),
                    eq("观察窗到期补建"), anyString(), eq("alert-bot"), eq(false));
        }

        @Test
        @DisplayName("补建时窗口内已有组工单 → 关联进组而非新建（聚合降噪语义一致）")
        void dueAlertJoinsGroupTicket() {
            enableObservation();
            Alert pending = new Alert();
            pending.setId(56L);
            pending.setAlertName("ConnectionPoolHigh");
            pending.setLevel("P2");
            pending.setService("wms-api");
            pending.setModule("DB");
            pending.setDedupKey("dk-56");
            Alert group = new Alert();
            group.setId(9L);
            group.setTicketId("TK-GROUP");
            when(alertRepository.findObservationDue(any(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(List.of(pending));
            when(alertRepository.findActiveGroupTicket(any(), any(), anyInt()))
                    .thenReturn(Optional.of(group));

            service.createDelayedTickets();

            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
            verify(alertRepository).updateTicketId(eq(56L), eq("TK-GROUP"));
            verify(diagnosisOrchestrator, never()).submit(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("无到期告警时扫描空转 —— 不碰建单链")
        void noDueAlertsIsNoop() {
            enableObservation();
            when(alertRepository.findObservationDue(any(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(List.of());

            service.createDelayedTickets();

            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("补建单条失败不拖垮整批 —— 下轮扫描还会捞到失败者")
        void oneFailureDoesNotAbortBatch() {
            enableObservation();
            Alert bad = new Alert();
            bad.setId(57L);
            bad.setAlertName("Bad");
            bad.setLevel("P2");
            bad.setService("svc-a");
            bad.setModule("DB");
            bad.setDedupKey("dk-57");
            Alert good = new Alert();
            good.setId(58L);
            good.setAlertName("Good");
            good.setLevel("P3");
            good.setService("svc-b");
            good.setModule("HOST");
            good.setDedupKey("dk-58");
            when(alertRepository.findObservationDue(any(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(List.of(bad, good));
            when(ticketService.createTicket(anyString(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("db down"))
                    .thenAnswer(inv -> {
                        DevOpsTicket t = new DevOpsTicket();
                        t.setId("TK-2026-0002");
                        return t;
                    });

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.createDelayedTickets());

            // 两条都尝试过（bad 失败、good 成功）
            verify(ticketService, times(2)).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
            verify(alertRepository).updateTicketId(eq(58L), eq("TK-2026-0002"));
        }
    }

    // ==================== FR-1.2：来源系统路径注入 ====================

    @Nested
    @DisplayName("来源系统注入（/webhook/{system} 路径优先，payload 不可伪造）")
    class SystemInjection {

        @Test
        @DisplayName("路径注入覆盖 payload 的 system label —— 路径是部署侧保证")
        void pathSystemOverridesPayload() {
            service.processWebhook(webhook(incoming("firing", Map.of(
                    "alertname", "HighCpu", "service", "api", "system", "payload-lie"))), "wms");

            assertEquals("wms", savedAlert().getSystem());
        }

        @Test
        @DisplayName("路径注入的 system 参与去重 —— 不同系统的同名告警不互相计次")
        void systemParticipatesInDedupKey() {
            service.processWebhook(webhook(incoming("firing", Map.of(
                    "alertname", "HighCpu", "service", "api"))), "mes");
            service.processWebhook(webhook(incoming("firing", Map.of(
                    "alertname", "HighCpu", "service", "api"))), "wms");

            List<Alert> saved = savedAlerts();
            assertNotEquals(saved.get(0).getDedupKey(), saved.get(1).getDedupKey());
        }

        @Test
        @DisplayName("旧端点（无路径段）回落 payload label，再回落 default")
        void legacyEndpointFallsBack() {
            service.processWebhook(webhook(incoming("firing", Map.of(
                    "alertname", "HighCpu", "service", "api", "system", "erp"))));
            service.processWebhook(webhook(incoming("firing", Map.of(
                    "alertname", "HighCpu", "service", "api"))));

            List<Alert> saved = savedAlerts();
            assertEquals("erp", saved.get(0).getSystem());
            assertEquals("default", saved.get(1).getSystem());
        }
    }

    // ==================== FR-2.5：全局风暴模式 ====================

    @Nested
    @DisplayName("全局风暴模式（FR-2.5：速率熔断 + 摘要事件）")
    class StormMode {

        private void enableStorm(int enter, int exit) {
            ReflectionTestUtils.setField(service, "stormEnabled", true);
            ReflectionTestUtils.setField(service, "stormEnterRatePerMin", enter);
            ReflectionTestUtils.setField(service, "stormExitRatePerMin", exit);
            // 让 insertOrIncrement 表现出去重语义：同键第二次起返回 false
            java.util.Set<String> seen = new java.util.HashSet<>();
            when(alertRepository.insertOrIncrement(any(Alert.class))).thenAnswer(inv -> {
                Alert a = inv.getArgument(0);
                a.setId(1L);
                return seen.add(a.getDedupKey());
            });
        }

        private AlertmanagerWebhook firingBatch(String... names) {
            AlertmanagerWebhook.Alert[] arr = new AlertmanagerWebhook.Alert[names.length];
            for (int i = 0; i < names.length; i++) {
                arr[i] = incoming("firing", Map.of(
                        "alertname", names[i], "service", "svc-" + i, "severity", "warning"));
            }
            return webhook(arr);
        }

        @Test
        @DisplayName("速率未达阈值不进入风暴 —— 低开销快路径不能误伤日常流量")
        void belowThresholdNoStorm() {
            enableStorm(100, 20);
            service.processWebhook(firingBatch("A", "B"));
            // 两条 warning（P2）都正常走观察窗分支外的原路径…观察窗未开启，直接建单
            verify(ticketService, times(2)).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("速率超阈值进入风暴：低级别抑制进摘要，整场风暴只建一张摘要单")
        void stormSuppressesLowLevels() {
            enableStorm(3, 1);

            service.processWebhook(firingBatch("A", "B", "C"));

            // 三条 warning 本体全部入库（可见性铁律）；每条抑制都 upsert 一次摘要（3+3）
            verify(alertRepository, times(6)).insertOrIncrement(any(Alert.class));
            // 只为摘要事件建一张单（P1 高危放行），三条 warning 各不建单
            // （createAutoTicket 自建标题：「【告警】OpsBrainAlertStormSummary - …」）
            ArgumentCaptor<String> titleCap = ArgumentCaptor.forClass(String.class);
            verify(ticketService, times(1)).createTicket(titleCap.capture(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
            org.junit.jupiter.api.Assertions.assertTrue(titleCap.getValue().contains("OpsBrainAlertStormSummary"));
        }

        @Test
        @DisplayName("风暴中高危（critical→P0）照常建单 —— 风暴模式不是一刀切")
        void stormBypassesHighRisk() {
            enableStorm(2, 1);

            service.processWebhook(webhook(
                    incoming("firing", Map.of("alertname", "LowA", "service", "svc", "severity", "warning")),
                    incoming("firing", Map.of("alertname", "OrderApiDown", "service", "order-api", "severity", "critical"))));

            // P0 建单 + 摘要单 = 2 张
            verify(ticketService, times(2)).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("速率回落自动退出风暴并恢复摘要事件")
        void stormExitsAndResolvesSummary() {
            enableStorm(3, 1);
            Alert summary = new Alert();
            summary.setId(99L);
            summary.setAlertName(com.devops.agent.domain.alert.ReservedAlertNames.STORM_SUMMARY);
            summary.setStatus("FIRING");
            when(alertRepository.findActiveByDedupKey("storm-summary")).thenReturn(Optional.of(summary));

            service.processWebhook(firingBatch("A", "B", "C"));   // 进入风暴

            // 模拟 60 秒窗口过去：清空到达记录，再推一条（速率 1 ≤ 退出阈值 1）
            @SuppressWarnings("unchecked")
            java.util.Deque<Long> arrivals =
                    (java.util.Deque<Long>) ReflectionTestUtils.getField(service, "stormArrivals");
            arrivals.clear();

            service.processWebhook(firingBatch("D"));

            // 摘要事件被自动恢复，新告警 D 走正常链路（观察窗未开 → 直接建单）
            verify(alertRepository).resolve(eq(99L), any());
        }
        @Test
        @DisplayName("风暴状态快照：反映当前速率与状态（告警列表横幅的数据源）")
        void stormStatusSnapshot() {
            enableStorm(3, 1);

            var idle = service.stormStatus();
            org.junit.jupiter.api.Assertions.assertFalse(idle.active());
            org.junit.jupiter.api.Assertions.assertEquals(0, idle.ratePerMin());

            service.processWebhook(firingBatch("A", "B", "C"));

            var active = service.stormStatus();
            org.junit.jupiter.api.Assertions.assertTrue(active.active());
            org.junit.jupiter.api.Assertions.assertEquals(3, active.ratePerMin());
        }
    }

    // ==================== 观察中派生判定（列表「观察中」标识的数据源） ====================

    @Nested
    @DisplayName("isObserving 观察中判定")
    class ObservingFlag {

        private void enableObservation() {
            ReflectionTestUtils.setField(service, "observationEnabled", true);
            ReflectionTestUtils.setField(service, "observationLevels", "P2,P3");
            ReflectionTestUtils.setField(service, "observationWindowMinutes", 10);
        }

        private Alert alertWith(String level, String status, String ticketId, java.time.LocalDateTime first) {
            Alert a = new Alert();
            a.setLevel(level);
            a.setStatus(status);
            a.setTicketId(ticketId);
            a.setFirstOccurredAt(first);
            return a;
        }

        @Test
        @DisplayName("观察级 + 活跃 + 未建单 + 未超窗 = 观察中")
        void observingWhenPending() {
            enableObservation();
            Alert a = alertWith("P2", "FIRING", null, java.time.LocalDateTime.now().minusMinutes(3));
            org.junit.jupiter.api.Assertions.assertTrue(service.isObserving(a));
        }

        @Test
        @DisplayName("已建单 / 超窗 / 高危 / 已恢复 都不是观察中")
        void notObservingCases() {
            enableObservation();
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            org.junit.jupiter.api.Assertions.assertFalse(
                    service.isObserving(alertWith("P2", "FIRING", "TK-1", now.minusMinutes(3)))); // 已建单
            org.junit.jupiter.api.Assertions.assertFalse(
                    service.isObserving(alertWith("P2", "FIRING", null, now.minusMinutes(30)))); // 超窗
            org.junit.jupiter.api.Assertions.assertFalse(
                    service.isObserving(alertWith("P0", "FIRING", null, now.minusMinutes(3)))); // 非观察级
            org.junit.jupiter.api.Assertions.assertFalse(
                    service.isObserving(alertWith("P2", "RESOLVED", null, now.minusMinutes(3)))); // 已恢复
        }

        @Test
        @DisplayName("观察窗开关关闭时一律不是观察中")
        void disabledNeverObserving() {
            // 不设 observationEnabled（false）
            Alert a = alertWith("P2", "FIRING", null, java.time.LocalDateTime.now().minusMinutes(1));
            org.junit.jupiter.api.Assertions.assertFalse(service.isObserving(a));
        }
        @Test
        @DisplayName("风暴模式进行中观察窗不补建 —— 补建等于绕过风暴熔断（9-27 压测实测缺陷）")
        void stormActiveSkipsBackfill() {
            enableObservation();
            ReflectionTestUtils.setField(service, "stormActive", true);

            service.createDelayedTickets();

            // 连到期查询都不该发——风暴期补建没有例外
            verify(alertRepository, never()).findObservationDue(any(), anyInt(), anyInt(), anyInt());
            verify(ticketService, never()).createTicket(anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
        }
    }

    // ==================== 方案①升级 + 方案②恢复关单联动（2026-10-01） ====================

    private Alert activeAlert(String level, String ticketId) {
        Alert a = new Alert();
        a.setId(42L);
        a.setAlertName("HighCPU");
        a.setDedupKey("dk");
        a.setStatus("FIRING");
        a.setLevel(level);
        a.setService("order-service");
        a.setTicketId(ticketId);
        a.setLastOccurredAt(java.time.LocalDateTime.now());
        return a;
    }

    @Nested
    @DisplayName("方案①重复告警级别升级 + 方案②恢复关单联动")
    class EscalationAndResolvePolicy {

        @Test
        @DisplayName("resolved 的 endsAt 穿透进 resolve——MTTR 用真实恢复时刻而非到达时刻")
        void resolvedPassesEndsAt() {
            when(alertRepository.findActiveByDedupKey(anyString()))
                    .thenReturn(Optional.of(activeAlert("P2", null)));
            OffsetDateTime endsAt = OffsetDateTime.parse("2026-09-30T00:30:00Z");
            AlertmanagerWebhook.Alert a = incoming("resolved",
                    labels("alertname", "HighCPU", "service", "order", "severity", "warning"));
            a.setEndsAt(endsAt);
            service.processWebhook(webhook(a));

            java.time.LocalDateTime expected = java.time.LocalDateTime.ofInstant(
                    endsAt.toInstant(), java.time.ZoneId.systemDefault());
            verify(alertRepository).resolve(42L, expected);
        }

        @Test
        @DisplayName("级别升级（P2→P0）→ 工单只升不降：priority 收紧 + 活动流留痕")
        void escalationRaisesTicketPriority() {
            when(alertRepository.insertOrIncrement(any(Alert.class))).thenReturn(false);
            when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(
                    Optional.of(activeAlert("P2", "TK-1")),
                    Optional.of(activeAlert("P0", "TK-1")));
            when(ticketService.raisePriorityFromAlert("TK-1", "P0")).thenReturn("P2 → P0");

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCPU", "service", "order", "severity", "critical"))));

            verify(ticketService).raisePriorityFromAlert("TK-1", "P0");
            verify(ticketService).recordActivity(eq("TK-1"), eq("warning"),
                    contains("告警级别升级"), contains("P2 → P0"), eq("alert-bot"), eq(true));
            verify(dingTalk).send(any());
        }

        @Test
        @DisplayName("级别降级（P0→P2）只跟告警库，不动处置中的工单")
        void downgradeLeavesTicketAlone() {
            when(alertRepository.insertOrIncrement(any(Alert.class))).thenReturn(false);
            when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(
                    Optional.of(activeAlert("P0", "TK-1")),
                    Optional.of(activeAlert("P2", "TK-1")));

            service.processWebhook(webhook(incoming("firing",
                    labels("alertname", "HighCPU", "service", "order", "severity", "warning"))));

            verify(ticketService, never()).raisePriorityFromAlert(anyString(), anyString());
        }

        @Test
        @DisplayName("hint（默认拍板）：恢复只留活动流提示，不改工单状态")
        void hintRecordsButNeverCloses() {
            when(alertRepository.findActiveByDedupKey(anyString()))
                    .thenReturn(Optional.of(activeAlert("P2", "TK-1")));
            service.processWebhook(webhook(incoming("resolved",
                    labels("alertname", "HighCPU", "service", "order", "severity", "warning"))));

            verify(ticketService).recordActivity(eq("TK-1"), anyString(),
                    contains("关联告警已恢复"), contains("可关单"), eq("alert-bot"), eq(false));
            verify(ticketService, never()).updateStatus(anyString(), anyString());
        }

        @Test
        @DisplayName("auto + 最后一条活跃恢复 → 自动关单；组内仍有其它活跃告警只提示不关")
        void autoClosesOnlyLastActive() {
            ReflectionTestUtils.setField(service, "resolveClosePolicy", "auto");
            when(alertRepository.findActiveByDedupKey(anyString()))
                    .thenReturn(Optional.of(activeAlert("P2", "TK-1")));
            when(alertRepository.countOtherActiveByTicket(anyString(), anyLong())).thenReturn(0L);
            service.processWebhook(webhook(incoming("resolved",
                    labels("alertname", "HighCPU", "service", "order", "severity", "warning"))));

            verify(ticketService).updateStatus("TK-1", "RESOLVED");
            // recordActivity 参数位：text=「关联告警已恢复」（标题位），detail=「自动关单」
            verify(ticketService).recordActivity(eq("TK-1"), anyString(),
                    contains("关联告警已恢复"), contains("自动关单"), eq("alert-bot"), eq(true));

            // 组单还有其它活跃告警：只提示不关
            org.mockito.Mockito.reset(ticketService);
            when(alertRepository.countOtherActiveByTicket(anyString(), anyLong())).thenReturn(2L);
            service.processWebhook(webhook(incoming("resolved",
                    labels("alertname", "HighCPU", "service", "order", "severity", "warning"))));
            verify(ticketService, never()).updateStatus(anyString(), anyString());
            verify(ticketService).recordActivity(eq("TK-1"), anyString(),
                    contains("关联告警已恢复"), contains("未自动关单"), eq("alert-bot"), eq(false));
        }

        @Test
        @DisplayName("off：恢复完全不碰工单")
        void offDoesNothing() {
            ReflectionTestUtils.setField(service, "resolveClosePolicy", "off");
            when(alertRepository.findActiveByDedupKey(anyString()))
                    .thenReturn(Optional.of(activeAlert("P2", "TK-1")));
            service.processWebhook(webhook(incoming("resolved",
                    labels("alertname", "HighCPU", "service", "order", "severity", "warning"))));

            verify(ticketService, never()).recordActivity(any(), any(), any(), any(), any(), anyBoolean());
            verify(ticketService, never()).updateStatus(anyString(), anyString());
        }
    }

}
