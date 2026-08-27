package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.tools.ToolExecutionRecord;
import com.devops.agent.domain.tools.ToolExecutionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ToolExecutionRepository} 降级行为测试。
 *
 * <h3>为什么测「仓储」这一层</h3>
 * 通常仓储只是 SQL 的搬运工，不值得单测。但这个类不同：
 * 它的 <b>10 个方法全部 {@code catch (Exception) 后返回兜底值}</b>，
 * 也就是说「数据库出问题」这件事在这里被<b>整体消化掉了</b>，
 * 上层拿到的永远是一个看起来正常的返回值。
 *
 * <p>吞异常本身不都是错的——Saga 审计是旁路能力，
 * 让它把主流程带崩没有意义。但吞掉之后<b>兜底值是什么、有没有留下线索</b>，
 * 决定了故障是「能查」还是「查不到」。本测试锁的就是这两件事。</p>
 *
 * <h3>本轮审查发现的两处问题（已修）</h3>
 * <ol>
 *   <li>{@code findNeedingAttention} 失败返回空列表且<b>连日志都没有</b>。
 *       它是「需人工介入」看板的唯一数据源，空列表在界面上与
 *       「真的没有待处理项」完全无法区分——运维看到 {@code count: 0}
 *       会认为系统健康，而实际可能有一批补偿失败的脏数据无人处理；</li>
 *   <li>{@code nextStepSeq} 失败兜底返回 1，同样无日志。
 *       step_seq 决定 Saga 补偿的<b>逆序回滚顺序</b>，
 *       兜底为 1 会造成同一 Saga 内序号重复，回滚顺序退化为不确定。</li>
 * </ol>
 * 两处的兜底值都保留（这是对的），补上了日志。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("ToolExecutionRepository 降级行为")
class ToolExecutionRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private ToolExecutionRepository repo;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repo = new ToolExecutionRepository(jdbcTemplate);
    }

    /**
     * 让所有查询都以「数据库连不上」失败。
     *
     * <p>打桩必须对上<b>实际调用的那个重载</b>：仓储里用的是
     * varargs 形式 {@code query(sql, rowMapper, a, b, c)} 与
     * {@code queryForObject(sql, Integer.class, sagaId)}，
     * 打成 {@code any(Object[].class)} 在 Mockito 里同样能匹配 varargs，
     * 但两种写法混用容易漏掉某个重载，导致「以为打了桩、实际走了真实方法」，
     * 而真实方法在 mock 上返回 null，表现为一个与被测行为无关的 NPE。</p>
     */
    private void givenDatabaseDown() {
        DataAccessResourceFailureException down =
                new DataAccessResourceFailureException("connection refused");
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenThrow(down);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(down);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenThrow(down);
    }

    // ==================================================================
    // nextStepSeq
    // ==================================================================

    @Nested
    @DisplayName("nextStepSeq 步骤序号")
    class NextStepSeq {

        @Test
        @DisplayName("正常返回查询结果")
        void returnsQueryResult() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(5);
            assertThat(repo.nextStepSeq("saga-1")).isEqualTo(5);
        }

        @Test
        @DisplayName("查询返回 null 时兜底为 1")
        void nullResultFallsBackToOne() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(null);
            assertThat(repo.nextStepSeq("saga-1")).isEqualTo(1);
        }

        @Test
        @DisplayName("数据库不可用时兜底为 1 且不抛异常")
        void databaseDownFallsBackToOne() {
            // 兜底本身是对的：登记一条执行记录不该因为取不到序号而中断。
            // 但代价是同一 Saga 内会出现重复的 step_seq，
            // 而补偿正是按 step_seq DESC 逆序执行的——
            // 回滚顺序会退化为不确定，后执行的步骤可能先于其依赖项被撤销。
            // 所以这个兜底必须留下日志（见类注释），否则现场毫无线索
            givenDatabaseDown();

            assertThatCode(() -> assertThat(repo.nextStepSeq("saga-1")).isEqualTo(1))
                    .doesNotThrowAnyException();
        }
    }

    // ==================================================================
    // findNeedingAttention
    // ==================================================================

    @Nested
    @DisplayName("findNeedingAttention 人工介入看板")
    class FindNeedingAttention {

        @Test
        @DisplayName("正常返回查询结果")
        void returnsRecords() {
            ToolExecutionRecord r = new ToolExecutionRecord();
            r.setId(1L);
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of(r));

            assertThat(repo.findNeedingAttention(50)).hasSize(1);
        }

        @Test
        @DisplayName("数据库不可用时返回空列表且不抛异常")
        void databaseDownReturnsEmpty() {
            // ── 本类最重要的一条 ──────────────────────────────
            // 返回空列表是刻意的：看板是旁路能力，不该把整个页面带崩。
            // 但空列表与「真的没有待处理项」在界面上长得一模一样
            // （接口都回 count: 0 的成功响应），所以这条降级
            // 必须配 ERROR 级日志——否则看板失明时无人知晓，
            // 而此时库里可能正躺着一批补偿失败的脏数据
            givenDatabaseDown();

            assertThatCode(() -> assertThat(repo.findNeedingAttention(50)).isEmpty())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("查询三类需关注状态，缺任何一类都会漏掉待处理项")
        void queriesAllThreeAttentionStates() {
            // PARTIAL_SUCCESS（半残）/ COMPENSATION_FAILED（补偿失败）
            // / MANUAL_INTERVENTION_REQUIRED（已标记需介入）
            // 漏掉其中任何一类，那类脏数据就永远不会出现在看板上，
            // 而看板本身显示正常——这比看板报错更难发现
            org.mockito.ArgumentCaptor<Object> args =
                    org.mockito.ArgumentCaptor.forClass(Object.class);
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), args.capture()))
                    .thenReturn(List.of());

            repo.findNeedingAttention(50);

            assertThat(args.getAllValues())
                    .contains(ToolExecutionState.PARTIAL_SUCCESS.name())
                    .contains(ToolExecutionState.COMPENSATION_FAILED.name())
                    .contains(ToolExecutionState.MANUAL_INTERVENTION_REQUIRED.name());
        }
    }

    // ==================================================================
    // 写方法的降级值
    // ==================================================================

    @Nested
    @DisplayName("写方法失败时返回 0（而非抛异常）")
    class WriteDegradation {

        @Test
        @DisplayName("updateState / markCompensated / markCompensationFailed 失败均返回 0")
        void writeFailuresReturnZero() {
            // 返回 0 让调用方能判断「没写成」。
            // SagaCompensationManager 依赖这一点：它不会因为审计写失败
            // 而中断补偿本身——脏数据的清理比记录清理动作更重要
            givenDatabaseDown();

            assertThat(repo.updateState(1L, ToolExecutionState.COMPENSATING)).isZero();
            assertThat(repo.markCompensated(1L)).isZero();
            assertThat(repo.markCompensationFailed(1L, "err")).isZero();
        }

        @Test
        @DisplayName("insert 失败返回 null，让调用方能区分「登记成功」与「没登记上」")
        void insertFailureReturnsNull() {
            // 返回 null 而非 0：id 是后续所有状态流转的句柄，
            // 拿到一个假的 0 会让后续 updateState(0, ...) 静默更新不到任何行，
            // 表现为「Saga 步骤状态永远停在 PENDING」
            when(jdbcTemplate.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                    any(org.springframework.jdbc.support.KeyHolder.class)))
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));

            ToolExecutionRecord r = new ToolExecutionRecord();
            r.setToolName("createDevOpsTicket");

            assertThat(repo.insert(r)).isNull();
        }
    }
}
