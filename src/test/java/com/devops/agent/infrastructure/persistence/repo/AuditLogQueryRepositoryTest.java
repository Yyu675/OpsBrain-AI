package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.audit.AgentCallLogRow;
import com.devops.agent.domain.audit.AgentCallTraceRow;
import com.devops.agent.domain.audit.AuditPage;
import com.devops.agent.domain.audit.AuditAgentCallStats;
import com.devops.agent.domain.audit.OperationAuditTraceRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogQueryRepositoryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void traceAuditQueryReturnsTypedRowsAndKeepsTraceIdParameter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogQueryRepository repository = new AuditLogQueryRepository(jdbc);
        OperationAuditTraceRow row = new OperationAuditTraceRow(
                12L, "trace-7", "u-1", "Alice", "ticket.create", "ticket", "T-9",
                "POST", "/api/v1/tickets", 200, true, 0, null, "127.0.0.1", 42, null);
        doReturn(List.of(row)).when(jdbc).query(
                anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));

        assertThat(repository.findAuditByTraceId("trace-7")).containsExactly(row);
        verify(jdbc).query(contains("ORDER BY create_time ASC, id ASC"),
                any(org.springframework.jdbc.core.RowMapper.class), aryEq(new Object[]{"trace-7"}));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aiCallTraceQueryReturnsTypedRowAndPreservesLatestRowLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogQueryRepository repository = new AuditLogQueryRepository(jdbc);
        AgentCallTraceRow row = new AgentCallTraceRow(
                7L, "trace-7", "question", "answer", "model", true, 125,
                new java.math.BigDecimal("0.0123"), "citations", "CHAT", "ticket:T-9", "u-1", null);
        doReturn(List.of(row)).when(jdbc).query(
                anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));

        assertThat(repository.findAgentCallByTraceId("trace-7")).isEqualTo(row);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),
                any(org.springframework.jdbc.core.RowMapper.class), aryEq(new Object[]{"trace-7"}));
        assertThat(sql.getValue()).contains("ORDER BY id DESC", "LIMIT 1");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aiCallPageUsesTypedRowsAndPreservesFiltersOrderAndPagination() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogQueryRepository repository = new AuditLogQueryRepository(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        java.time.LocalDateTime createdAt = java.time.LocalDateTime.of(2026, 10, 18, 10, 0);
        when(rs.getObject("id", Long.class)).thenReturn(9L);
        when(rs.getString("trace_id")).thenReturn("trace-9");
        when(rs.getString("model_name")).thenReturn("model-a");
        when(rs.getObject("is_cached", Boolean.class)).thenReturn(false);
        when(rs.getObject("latency_ms", Integer.class)).thenReturn(125);
        when(rs.getBigDecimal("cost_rmb")).thenReturn(new java.math.BigDecimal("0.0123"));
        when(rs.getString("operation_type")).thenReturn("CHAT");
        when(rs.getString("operator_id")).thenReturn("u-1");
        when(rs.getString("affected_resources")).thenReturn("ticket:T-9");
        when(rs.getString("query_preview")).thenReturn("query preview");
        when(rs.getObject("create_time", java.time.LocalDateTime.class)).thenReturn(createdAt);
        doAnswer(invocation -> ((org.springframework.jdbc.core.RowMapper<?>) invocation.getArgument(1))
                .mapRow(rs, 0)).when(jdbc).query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));

        AuditPage<AgentCallLogRow> page = repository.queryAgentCallLog(
                " model-a ", null, false, 100, null, null, 2, 30);

        assertThat(page).isEqualTo(new AuditPage<>(List.of(new AgentCallLogRow(
                9L, "trace-9", "model-a", false, 125, new java.math.BigDecimal("0.0123"),
                "CHAT", "u-1", "ticket:T-9", "query preview", createdAt)), 1, 2, 30, 1));
        verify(jdbc).queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class),
                aryEq(new Object[]{"model-a", false, 100}));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class),
                aryEq(new Object[]{"model-a", false, 100, 30, 30L}));
        assertThat(sql.getValue()).contains("ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
                "LEFT(COALESCE(user_query, ''), 120) AS query_preview")
                .doesNotContain("SELECT user_query", "agent_answer");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aiCallStatsAreTypedRoundedAndUseTheSameFilterPredicates() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogQueryRepository repository = new AuditLogQueryRepository(jdbc);
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getLong("total_calls")).thenReturn(3L);
        when(rs.getLong("cache_hits")).thenReturn(1L);
        when(rs.getBigDecimal("total_cost")).thenReturn(new java.math.BigDecimal("1.234567"));
        when(rs.getDouble("avg_latency")).thenReturn(99.7);
        doAnswer(invocation -> ((org.springframework.jdbc.core.RowMapper<?>) invocation.getArgument(1))
                .mapRow(rs, 0)).when(jdbc).queryForObject(
                contains("FROM sys_agent_call_log"), any(org.springframework.jdbc.core.RowMapper.class),
                aryEq(new Object[]{"model-a", true, 100}));

        AuditAgentCallStats stats = repository.queryAgentCallStats(
                "model-a", null, true, 100, null, null);

        assertThat(stats).isEqualTo(new AuditAgentCallStats(3, 1, 33.33,
                java.math.BigDecimal.valueOf(1.2346), 100));
        verify(jdbc).queryForObject(contains("AND model_name = ?"),
                any(org.springframework.jdbc.core.RowMapper.class), aryEq(new Object[]{"model-a", true, 100}));
    }

    @Test
    void filterOptionsReturnTypedListsAndContinueFilteringNullCandidates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogQueryRepository repository = new AuditLogQueryRepository(jdbc);
        when(jdbc.queryForList(contains("DISTINCT model_name"), eq(String.class)))
                .thenReturn(List.of("deepseek-chat"));
        when(jdbc.queryForList(contains("DISTINCT operation_type"), eq(String.class)))
                .thenReturn(List.of("CHAT"));
        when(jdbc.queryForList(contains("DISTINCT action"), eq(String.class)))
                .thenReturn(Arrays.asList("ticket.create", null, "ticket.close"));
        when(jdbc.queryForList(contains("DISTINCT target_type"), eq(String.class)))
                .thenReturn(Arrays.asList("ticket", null));

        var options = repository.queryFilterOptions();

        assertThat(options.models()).containsExactly("deepseek-chat");
        assertThat(options.operationTypes()).containsExactly("CHAT");
        assertThat(options.actions()).containsExactly("ticket.create", "ticket.close");
        assertThat(options.targetTypes()).containsExactly("ticket");
    }
}
