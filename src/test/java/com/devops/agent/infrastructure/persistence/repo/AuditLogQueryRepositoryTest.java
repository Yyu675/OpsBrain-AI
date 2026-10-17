package com.devops.agent.infrastructure.persistence.repo;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditLogQueryRepositoryTest {

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
