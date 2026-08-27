package com.devops.agent.application.memory;

import com.devops.agent.domain.memory.KeyFacts;
import com.devops.agent.domain.memory.SessionSummary;
import com.devops.agent.domain.memory.SummaryDistiller;
import com.devops.agent.infrastructure.cache.HotMemoryStore;
import com.devops.agent.infrastructure.persistence.repo.SessionSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentMemoryManager} 写入路径单元测试。
 *
 * <h3>为什么补这两个方法</h3>
 * {@code recordUserTurn} 与 {@code recordCompletedTurn} 在
 * {@code baseline.json} 里被标为「确属缺口」，是三层记忆的写入端。
 *
 * <h3>记忆写坏了，症状出在别处</h3>
 * 这两个方法整体包在 try-catch 里、失败只打 WARN——这是对的
 * （用户已经收到回答了，不该因为记忆没存上而报错）。
 * 但代价是<b>写错时没有任何信号</b>，而后果会在下一轮对话里显现为
 * 「AI 忘了刚说过的话」或「AI 记住了错的事」，排查时几乎不会有人
 * 想到是上一轮的记忆写入出了问题。
 *
 * <h3>重点：轮次数「一处用总数、一处传增量」</h3>
 * {@code recordCompletedTurn} 里有个容易写错的地方：
 * <ul>
 *   <li>摘要文本用的是<b>累计轮次</b>（DB 已有 + 1）——它要展示「聊了几轮」；</li>
 *   <li>而 UPSERT 传的 {@code turnCount} 是<b>本轮增量 1</b>——
 *       仓储侧是累加语义。</li>
 * </ul>
 * 两者写反的后果：传总数进去会让轮次<b>指数增长</b>（1→2→4→8），
 * 而摘要里若用增量则永远显示「第 1 轮」。两种都不会报错。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("AgentMemoryManager 记忆写入")
class AgentMemoryManagerTest {

    private HotMemoryStore hotMemory;
    private SessionSummaryRepository summaryRepo;
    private SummaryDistiller distiller;
    private AgentMemoryManager manager;

    @BeforeEach
    void setUp() {
        hotMemory = mock(HotMemoryStore.class);
        summaryRepo = mock(SessionSummaryRepository.class);
        distiller = mock(SummaryDistiller.class);
        manager = new AgentMemoryManager(hotMemory, summaryRepo, distiller, new ObjectMapper());

        // 默认：蒸馏与合并返回空事实、摘要文本固定，具体用例再覆盖
        when(distiller.distill(any(), any(), any())).thenReturn(new KeyFacts());
        when(distiller.merge(any(), any())).thenReturn(new KeyFacts());
        when(distiller.buildSummaryText(any(), anyInt())).thenReturn("摘要");
    }

    private SessionSummary upserted() {
        ArgumentCaptor<SessionSummary> cap = ArgumentCaptor.forClass(SessionSummary.class);
        verify(summaryRepo).upsert(cap.capture());
        return cap.getValue();
    }

    // ==================================================================
    // recordUserTurn
    // ==================================================================

    @Nested
    @DisplayName("recordUserTurn 记录用户提问")
    class RecordUserTurn {

        @Test
        @DisplayName("以 user 角色写入热记忆")
        void appendsWithUserRole() {
            // 角色写错（比如写成 assistant）会让下一轮的上下文里
            // 用户的问题变成 AI 说的话，模型据此作答会答非所问，
            // 而这看起来像是「模型变笨了」
            manager.recordUserTurn("S1", "K8s Pod 起不来");
            verify(hotMemory).appendMessage("S1", "user", "K8s Pod 起不来");
        }
    }

    // ==================================================================
    // recordCompletedTurn
    // ==================================================================

    @Nested
    @DisplayName("recordCompletedTurn 记录完整一轮")
    class RecordCompletedTurn {

        @Test
        @DisplayName("AI 回答以 assistant 角色写入热记忆，并累积统计")
        void appendsAnswerAndStats() {
            manager.recordCompletedTurn("S1", "T1", "问", "答", List.of(), 120, 0.05, "DONE");

            verify(hotMemory).appendMessage("S1", "assistant", "答");
            verify(hotMemory).accumulateStats("S1", 120, 0.05);
        }

        @Test
        @DisplayName("摘要文本用累计轮次（DB 已有 + 1），不是本轮增量")
        void summaryUsesCumulativeTurnCount() {
            // DB 里已有 3 轮，本轮是第 4 轮。摘要要展示「聊了 4 轮」，
            // 若传 1 进去，摘要会永远显示「第 1 轮」——
            // 用户看到的是一个越聊越不对劲、却始终自称第一轮的摘要
            SessionSummary existing = new SessionSummary();
            existing.setTurnCount(3);
            when(summaryRepo.findBySessionId("S1")).thenReturn(existing);

            manager.recordCompletedTurn("S1", "T1", "问", "答", List.of(), 10, 0.01, "DONE");

            verify(distiller).buildSummaryText(any(), org.mockito.ArgumentMatchers.eq(4));
        }

        @Test
        @DisplayName("首轮（DB 无记录）累计轮次为 1")
        void firstTurnCountsAsOne() {
            when(summaryRepo.findBySessionId("S1")).thenReturn(null);

            manager.recordCompletedTurn("S1", "T1", "问", "答", List.of(), 10, 0.01, "DONE");

            verify(distiller).buildSummaryText(any(), org.mockito.ArgumentMatchers.eq(1));
        }

        @Test
        @DisplayName("UPSERT 传的是本轮增量 1，不是累计值——仓储侧是累加语义")
        void upsertCarriesIncrementNotTotal() {
            // ── 本组最重要的一条 ──────────────────────────────
            // 与上一条恰好相反：摘要要总数，UPSERT 要增量。
            // 传总数进去会让轮次指数增长（1→2→4→8），
            // token 与成本同理会被重复累加，用量统计整体虚高。
            // 两处写反都不会报错，只会让数字慢慢偏离真实值
            SessionSummary existing = new SessionSummary();
            existing.setTurnCount(3);
            when(summaryRepo.findBySessionId("S1")).thenReturn(existing);

            manager.recordCompletedTurn("S1", "T1", "问", "答", List.of(), 10, 0.01, "DONE");

            assertThat(upserted().getTurnCount())
                    .as("UPSERT 是累加语义，必须传本轮增量 1 而非累计 4")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("本轮 token 与成本原样传入（同为累加语义的增量）")
        void upsertCarriesThisTurnUsage() {
            manager.recordCompletedTurn("S1", "T1", "问", "答", List.of(), 250, 0.08, "DONE");

            SessionSummary s = upserted();
            assertThat(s.getTotalTokens()).isEqualTo(250);
            assertThat(s.getTotalCostRmb()).isEqualTo(0.08);
        }

        @Test
        @DisplayName("历史事实与本轮事实合并后写入，不能只写本轮")
        void mergesWithExistingFacts() {
            // 只写本轮事实会让温记忆每轮被覆盖，等于没有长期记忆——
            // 表现为「AI 记得上一句，但忘了三轮前确认过的环境信息」
            KeyFacts existingFacts = new KeyFacts();
            SessionSummary existing = new SessionSummary();
            existing.setKeyFacts(existingFacts);
            existing.setTurnCount(1);
            when(summaryRepo.findBySessionId("S1")).thenReturn(existing);

            manager.recordCompletedTurn("S1", "T1", "问", "答", List.of(), 10, 0.01, "DONE");

            // 合并时必须把「库里已有的事实」作为第一个入参传进去
            verify(distiller).merge(org.mockito.ArgumentMatchers.same(existingFacts), any());
        }

        @Test
        @DisplayName("会话首次出现时，合并的历史事实为 null 而非报错")
        void mergesNullWhenNoHistory() {
            when(summaryRepo.findBySessionId("S1")).thenReturn(null);

            manager.recordCompletedTurn("S1", "T1", "问", "答", List.of(), 10, 0.01, "DONE");

            verify(distiller).merge(org.mockito.ArgumentMatchers.isNull(), any());
        }

        @Test
        @DisplayName("从工具结果里提取工单号，写成 JSON 数组")
        void extractsTicketIds() {
            List<Map<String, Object>> toolResults = List.of(
                    Map.of("result", Map.of("ticketId", "TKT-20260827-0001")),
                    Map.of("result", Map.of("ticketId", "TKT-20260827-0002")));

            manager.recordCompletedTurn("S1", "T1", "问", "答", toolResults, 10, 0.01, "DONE");

            assertThat(upserted().getRelatedTickets())
                    .contains("TKT-20260827-0001")
                    .contains("TKT-20260827-0002");
        }

        @Test
        @DisplayName("工具结果为空时关联工单为空数组 []，不是 null")
        void emptyToolResultsYieldEmptyArray() {
            // 写 null 进去会让读取侧解析 JSON 时抛异常，
            // 而这个字段只在「回看历史会话」时才读——故障延迟暴露
            manager.recordCompletedTurn("S1", "T1", "问", "答", List.of(), 10, 0.01, "DONE");
            assertThat(upserted().getRelatedTickets()).isEqualTo("[]");
        }

        @Test
        @DisplayName("工具结果里没有 ticketId 时同样得到 []，不抛异常")
        void toolResultsWithoutTicketIdAreSkipped() {
            List<Map<String, Object>> toolResults = List.of(
                    Map.of("result", Map.of("someOtherKey", "value")),
                    Map.of("noResultKey", "x"));

            manager.recordCompletedTurn("S1", "T1", "问", "答", toolResults, 10, 0.01, "DONE");

            assertThat(upserted().getRelatedTickets()).isEqualTo("[]");
        }

        @Test
        @DisplayName("sessionId 为空时直接返回，不触碰任何存储")
        void blankSessionIdIsNoop() {
            manager.recordCompletedTurn(null, "T1", "问", "答", List.of(), 10, 0.01, "DONE");
            manager.recordCompletedTurn("  ", "T1", "问", "答", List.of(), 10, 0.01, "DONE");

            verify(hotMemory, never()).appendMessage(anyString(), anyString(), anyString());
            verify(summaryRepo, never()).upsert(any());
        }

        @Test
        @DisplayName("热记忆写入失败不抛异常——用户已收到回答，不能因记忆失败而报错")
        void hotMemoryFailureIsSwallowed() {
            doThrow(new RuntimeException("Redis 连接断了"))
                    .when(hotMemory).appendMessage(anyString(), anyString(), anyString());

            assertThatCode(() -> manager.recordCompletedTurn(
                    "S1", "T1", "问", "答", List.of(), 10, 0.01, "DONE"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("温记忆 UPSERT 失败同样不抛异常")
        void summaryRepoFailureIsSwallowed() {
            doThrow(new RuntimeException("库连接断了")).when(summaryRepo).upsert(any());

            assertThatCode(() -> manager.recordCompletedTurn(
                    "S1", "T1", "问", "答", List.of(), 10, 0.01, "DONE"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("蒸馏器抛异常时也不影响主流程，但热记忆已写入的部分保留")
        void distillerFailureStillKeepsHotMemory() {
            // 顺序很重要：热记忆先写、温记忆后写。蒸馏在中间失败时，
            // 至少「这轮说了什么」还在热记忆里，下一轮上下文不至于断层
            when(distiller.distill(any(), any(), any()))
                    .thenThrow(new RuntimeException("蒸馏失败"));

            assertThatCode(() -> manager.recordCompletedTurn(
                    "S1", "T1", "问", "答", List.of(), 10, 0.01, "DONE"))
                    .doesNotThrowAnyException();

            verify(hotMemory).appendMessage("S1", "assistant", "答");
            verify(hotMemory).accumulateStats("S1", 10, 0.01);
            verify(summaryRepo, never()).upsert(any());
        }

        @Test
        @DisplayName("traceId 与终态一并落库——事后回溯这轮发生了什么全靠它们")
        void persistsTraceIdAndFinalState() {
            manager.recordCompletedTurn("S1", "trace-9", "问", "答", List.of(), 10, 0.01, "DEGRADED");

            SessionSummary s = upserted();
            assertThat(s.getTraceId()).isEqualTo("trace-9");
            assertThat(s.getFinalState()).isEqualTo("DEGRADED");
        }
    }
}
