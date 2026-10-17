package com.devops.agent.domain.governance;

import com.devops.agent.domain.governance.GovernanceViews.ActionStats;
import com.devops.agent.domain.governance.GovernanceViews.PolicyStats;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernanceStatsRepositoryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void actionStatsMapFixedAggregateColumnsToRecord() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("total")).thenReturn(12L);
        when(rs.getLong("enabled_count")).thenReturn(9L);
        when(rs.getLong("high_risk_enabled")).thenReturn(3L);
        when(rs.getLong("prod_enabled")).thenReturn(6L);
        doAnswer(invocation -> ((RowMapper<?>) invocation.getArgument(1)).mapRow(rs, 0))
                .when(jdbc).queryForObject(contains("FROM sys_action_allowlist"), any(RowMapper.class));

        ActionStats stats = new ActionAllowlistRepository(jdbc).stats();

        assertThat(stats).isEqualTo(new ActionStats(12, 9, 3, 6));
        verify(jdbc).queryForObject(contains("high_risk_enabled"), any(RowMapper.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void policyStatsMapFixedAggregateColumnsToRecord() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("total")).thenReturn(20L);
        when(rs.getLong("enabled_count")).thenReturn(8L);
        when(rs.getLong("dry_run_count")).thenReturn(5L);
        when(rs.getLong("live_count")).thenReturn(3L);
        when(rs.getLong("prod_live_count")).thenReturn(2L);
        doAnswer(invocation -> ((RowMapper<?>) invocation.getArgument(1)).mapRow(rs, 0))
                .when(jdbc).queryForObject(contains("FROM sys_automation_policy"), any(RowMapper.class));

        PolicyStats stats = new AutomationPolicyRepository(jdbc).stats();

        assertThat(stats).isEqualTo(new PolicyStats(20, 8, 5, 3, 2));
        verify(jdbc).queryForObject(contains("prod_live_count"), any(RowMapper.class));
    }
}
