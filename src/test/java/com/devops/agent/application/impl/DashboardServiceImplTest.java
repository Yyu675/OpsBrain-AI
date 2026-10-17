package com.devops.agent.application.impl;

import com.devops.agent.application.DashboardCallTrends;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceImplTest {

    @Test
    void callTrendsReturnsTypedSeriesWithZeroFilledDaysAndExistingRounding() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DashboardServiceImpl service = new DashboardServiceImpl(jdbc);
        LocalDate today = LocalDate.now();
        when(jdbc.queryForList(anyString(), eq(1))).thenReturn(List.of(Map.of(
                "day", Date.valueOf(today),
                "served", 4L,
                "hits", 1L,
                "cost", new BigDecimal("1.23456"))));

        DashboardCallTrends trends = service.getCallTrends(2);

        assertThat(trends.days()).containsExactly(today.minusDays(1).toString(), today.toString());
        assertThat(trends.cost()).containsExactly(0.0, 1.2346);
        assertThat(trends.cacheHitRate()).containsExactly(0.0, 25.0);
        verify(jdbc).queryForList(anyString(), eq(1));
    }
}
