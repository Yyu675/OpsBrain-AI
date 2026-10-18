package com.devops.agent.domain.biz.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DiagnosisSessionRepository")
class DiagnosisSessionRepositoryTest {

    @Test
    @DisplayName("alert_id query maps the fixed session projection and preserves nullable columns")
    void findByAlertIdMapsSessionRow() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(17L);
        when(rs.getString("trace_id")).thenReturn("trace-17");
        when(rs.getObject("alert_id", Long.class)).thenReturn(17L);
        when(rs.getString("ticket_id")).thenReturn(null);
        when(rs.getString("service")).thenReturn("payments");
        when(rs.getString("status")).thenReturn("COMPLETED");
        when(rs.getString("sufficiency")).thenReturn("SUFFICIENT");
        when(rs.getString("summary")).thenReturn(null);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<DiagnosisSessionRepository.SessionRow>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<DiagnosisSessionRepository.SessionRow> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        Optional<DiagnosisSessionRepository.SessionRow> result =
                new DiagnosisSessionRepository(jdbc).findByAlertId(17L);

        assertThat(result).contains(new DiagnosisSessionRepository.SessionRow(17L, "trace-17", 17L,
                null, "payments", "COMPLETED", "SUFFICIENT", null, null, null));
    }

    @Test
    @DisplayName("no matching alert_id returns an empty Optional")
    void findByAlertIdReturnsEmptyWhenMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<DiagnosisSessionRepository.SessionRow>>any(), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(new DiagnosisSessionRepository(jdbc).findByAlertId(17L)).isEmpty();
    }
}
