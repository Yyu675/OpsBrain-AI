package com.devops.agent.application.impl;

import com.devops.agent.application.DashboardCallTrends;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceImplTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void callTrendsReturnsTypedSeriesWithZeroFilledDaysAndExistingRounding() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        DashboardServiceImpl service = new DashboardServiceImpl(jdbc);
        LocalDate today = LocalDate.now();
        when(rs.getDate("day")).thenReturn(Date.valueOf(today));
        when(rs.getDouble("cost")).thenReturn(1.23456);
        when(rs.getLong("served")).thenReturn(4L);
        when(rs.getLong("hits")).thenReturn(1L);
        when(rs.wasNull()).thenReturn(false);
        doAnswer(invocation -> {
            RowMapper<?> rowMapper = invocation.getArgument(1);
            List<Object> mappedRows = new ArrayList<>();
            mappedRows.add(rowMapper.mapRow(rs, 0));
            return mappedRows;
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        DashboardCallTrends trends = service.getCallTrends(2);

        assertThat(trends.days()).containsExactly(today.minusDays(1).toString(), today.toString());
        assertThat(trends.cost()).containsExactly(0.0, 1.2346);
        assertThat(trends.cacheHitRate()).containsExactly(0.0, 25.0);
        verify(jdbc).query(anyString(), any(RowMapper.class), aryEq(new Object[]{1}));
    }
}
