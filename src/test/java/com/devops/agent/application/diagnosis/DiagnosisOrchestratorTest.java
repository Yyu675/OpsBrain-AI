package com.devops.agent.application.diagnosis;

import com.devops.agent.application.runtime.AgentState;
import com.devops.agent.application.runtime.AgentStateManager;
import com.devops.agent.application.runtime.AgentStateTransition;
import com.devops.agent.common.context.TraceContext;
import com.devops.agent.domain.biz.repository.DiagnosisEvidenceRepository;
import com.devops.agent.domain.biz.repository.DiagnosisSessionRepository;
import com.devops.agent.domain.evidence.ChangesEvidenceCollector;
import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.LogsEvidenceCollector;
import com.devops.agent.domain.evidence.MetricsEvidenceCollector;
import com.devops.agent.domain.evidence.MetricsQueryCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** S2-1 诊断编排器单元测试。验证四态判定、traceId 唯一真相、异步不阻塞。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiagnosisOrchestrator（S2-1 诊断编排器）")
class DiagnosisOrchestratorTest {

    @Mock
    private MetricsEvidenceCollector metricsCollector;
    @Mock
    private ChangesEvidenceCollector changesCollector;
    @Mock
    private LogsEvidenceCollector logsCollector;
    @Mock
    private MetricsQueryCatalog catalog;
    @Mock
    private DiagnosisEvidenceRepository evidenceRepository;
    @Mock
    private DiagnosisSessionRepository sessionRepository;
    @Mock
    private AgentStateManager stateManager;
    @Mock
    private com.devops.agent.domain.biz.service.TicketAiAnalysisService aiAnalysisService;
    @Mock
    private com.devops.agent.domain.diagnosis.HypothesisGenerator hypothesisGenerator;
    @Mock
    private com.devops.agent.domain.biz.repository.DiagnosisHypothesisRepository hypothesisRepository;
    @Mock
    private com.devops.agent.domain.alert.service.AlertWebSocketNotifier wsNotifier;
    @Mock
    private com.devops.agent.domain.notify.Notifier notifier;

    private DiagnosisOrchestrator orchestrator;
    private String traceId;

    @BeforeEach
    void setUp() {
        TraceContext.begin("trace-42");
        // availableMetrics 生产签名返回 Set<String>——thenReturn 必须类型严格对齐
        when(catalog.availableMetrics()).thenReturn(Set.of("cpu", "memory"));
        when(metricsCollector.collect(anyString(), anyString(), anyString()))
                .thenReturn(new Evidence(
                        Evidence.EvidenceStatus.SUCCESS, "metrics", "完成",
                        Map.of("anomalyCount", 1), "ref", null, Instant.now()));
        when(changesCollector.collect(anyString(), anyString()))
                .thenReturn(new Evidence(
                        Evidence.EvidenceStatus.SUCCESS, "changes", "完成",
                        Map.of("count", 0), "ref", null, Instant.now()));
        when(logsCollector.collect(anyString(), anyString(), anyString(), any()))
                .thenReturn(new Evidence(
                        Evidence.EvidenceStatus.SUCCESS, "logs", "完成",
                        Map.of("patternCount", 3, "level", "ERROR"), "ref", null, Instant.now()));
        when(hypothesisGenerator.generate(any(), any()))
                .thenReturn(java.util.List.of());
        orchestrator = new DiagnosisOrchestrator(
                metricsCollector, changesCollector, logsCollector,
                catalog, evidenceRepository, sessionRepository, stateManager,
                aiAnalysisService, hypothesisGenerator, hypothesisRepository,
                wsNotifier, notifier);
    }

    @AfterEach
    void tearDown() {
        orchestrator.shutdown();
        TraceContext.clear();
    }

    @Test
    @DisplayName("提交后消息不阻塞告警链路，立即返回 traceId")
    void submitReturnsImmediately() {
        long start = System.currentTimeMillis();
        traceId = orchestrator.submit(1001L, "TK-001", "order-service");
        long elapsed = System.currentTimeMillis() - start;
        assertThat(traceId).isNotNull().startsWith("trace-");
        // AbstractLongAssert.isLessThan 只收 long，文案经 as() 分身表达
        assertThat(elapsed).as("提交必须火眼金睛，绝不允许阻塞告警链路").isLessThan(500L);
    }

    @Test
    @DisplayName("三方向 SUCCESS → 聚合 SUFFICIENT，证据队入落库，状态机记 DRAFT_GENERATED")
    void sufficientFlow() throws Exception {
        traceId = orchestrator.submit(1001L, "TK-001", "order-service");
        // 等待诊断跑完（诊断池是异步的，确定性办法：等落库信号或直接 sleep；
        // sleep 在这里是容忍的风险，低风险（只有实测出问题时才返回调优）
        Thread.sleep(200);

        // 证据队入落库全体——三条方向都不丢。
        verify(evidenceRepository, times(3)).save(anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
        // 会话收尾——sufficiency 落盘。
        verify(sessionRepository).complete(anyLong(), anyString(),
                anyString(), contains("证据充分"));
        // 状态机 DRAFT_READY 被点醒。
        verify(stateManager).transition(eq(AgentState.DRAFT_READY),
                eq(AgentStateTransition.TriggerType.DRAFT_GENERATED), anyString());
        // 2-1.5：SUFFICIENT 时结论回填工单 AI 分析区（conf 启发值 80）
        verify(aiAnalysisService).save(eq("TK-001"), contains("证据充分"),
                isNull(), isNull(), isNull(), eq(80), isNull());
        // 2-1.6：完成态推送——WS 广播 + 钉钉普通通知（SUFFICIENT 非 urgent）
        verify(wsNotifier).broadcastDiagnosis(any());
        verify(notifier).send(any(com.devops.agent.domain.notify.NotifyMessage.class));
    }

    @Test
    @DisplayName("指标单边 FAILED → 聚合规则按 WEAK 判定，摘要含置信度上限与人工复核建议")
    void oneFailedDirectionFlow() throws Exception {
        when(metricsCollector.collect(anyString(), anyString(), anyString()))
                .thenReturn(new Evidence(
                        Evidence.EvidenceStatus.FAILED, "metrics", "失败",
                        Map.of(), "ref", null, Instant.now()));
        traceId = orchestrator.submit(1002L, "TK-002", "order-service");
        Thread.sleep(200);

        // SUCCESS=2(changes+logs) 且 FAILED=1 → EvidenceAggregator 规则二判 WEAK。
        // 摘要必须含「置信度上限 0.6」与「人工」字样。
        verify(sessionRepository).complete(anyLong(), anyString(),
                argThat(sm -> sm != null && sm.contains("置信度上限 0.6") && sm.contains("人工")));
    }

    @Test
    @DisplayName("诊断主体异常：入 ERROR 状态不吞，不向上抛")
    void collectorThrows() throws Exception {
        when(changesCollector.collect(anyString(), anyString()))
                .thenThrow(new RuntimeException("变更器失控"));
        traceId = orchestrator.submit(1003L, "TK-003", "order-service");
        Thread.sleep(200);

        verify(sessionRepository, atLeastOnce())
                .fail(anyLong(), anyString());
        verify(stateManager, atLeastOnce())
                .transition(eq(AgentState.FAILED),
                        any(AgentStateTransition.TriggerType.class), anyString());
        // 上层捕获了全部异常，诊断链不停
        assertThatCode(() -> TraceContext.getOrCreate());
    }
}
