package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.audit.OperationAuditTraceRow;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
