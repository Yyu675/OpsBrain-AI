package com.devops.agent.application.impl;

import com.devops.agent.application.DashboardCallTrends;
import com.devops.agent.controller.dto.DashboardOverviewDTO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void overviewRowsMapModelDistributionAndZeroFilledCostTrends() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DashboardServiceImpl service = new DashboardServiceImpl(jdbc);
        LocalDate today = LocalDate.now();
        ResultSet modelA = mock(ResultSet.class);
        when(modelA.getString("model")).thenReturn("model-a");
        when(modelA.getLong("count")).thenReturn(2L);
        ResultSet modelB = mock(ResultSet.class);
        when(modelB.getString("model")).thenReturn("model-b");
        when(modelB.getLong("count")).thenReturn(3L);
        ResultSet yesterday = mock(ResultSet.class);
        when(yesterday.getDate("date")).thenReturn(Date.valueOf(today.minusDays(1)));
        when(yesterday.getDouble("cost")).thenReturn(0.0);
        when(yesterday.wasNull()).thenReturn(true);
        ResultSet todayRow = mock(ResultSet.class);
        when(todayRow.getDate("date")).thenReturn(Date.valueOf(today));
        when(todayRow.getDouble("cost")).thenReturn(1.23456);
        when(todayRow.wasNull()).thenReturn(false);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> rowMapper = invocation.getArgument(1);
            if (sql.contains("GROUP BY model_name")) {
                return List.of(rowMapper.mapRow(modelA, 0), rowMapper.mapRow(modelB, 1));
            }
            return List.of(rowMapper.mapRow(yesterday, 0), rowMapper.mapRow(todayRow, 1));
        }).when(jdbc).query(anyString(), any(RowMapper.class));

        List<DashboardOverviewDTO.ModelDistribution> models =
                ReflectionTestUtils.invokeMethod(service, "buildModelDistribution");
        List<DashboardOverviewDTO.CostTrend> costs =
                ReflectionTestUtils.invokeMethod(service, "buildCostTrends");

        assertThat(models).extracting(DashboardOverviewDTO.ModelDistribution::getModel)
                .containsExactly("model-a", "model-b");
        assertThat(models).extracting(DashboardOverviewDTO.ModelDistribution::getCount)
                .containsExactly(2L, 3L);
        assertThat(models).extracting(DashboardOverviewDTO.ModelDistribution::getPercentage)
                .containsExactly(40.0, 60.0);
        assertThat(costs).hasSize(7);
        assertThat(costs).extracting(DashboardOverviewDTO.CostTrend::getDate)
                .containsExactly(today.minusDays(6).toString(), today.minusDays(5).toString(),
                        today.minusDays(4).toString(), today.minusDays(3).toString(),
                        today.minusDays(2).toString(), today.minusDays(1).toString(), today.toString());
        assertThat(costs).extracting(DashboardOverviewDTO.CostTrend::getCost)
                .containsExactly(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.2346);
    }
}
