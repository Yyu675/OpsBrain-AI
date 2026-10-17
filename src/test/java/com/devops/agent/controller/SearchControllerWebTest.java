package com.devops.agent.controller;

import com.devops.agent.controller.dto.SearchResultItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = SearchController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        com.devops.agent.controller.config.WebConfig.class,
                        com.devops.agent.common.audit.OperationAuditInterceptor.class
                }),
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@AutoConfigureMockMvc(addFilters = false)
class SearchControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("全局搜索以 typed rows 返回原有字段、类别与查询组顺序")
    void searchReturnsTypedResultsInExistingOrder() throws Exception {
        doReturn(List.of(new SearchResultItem("T-9", "登录故障", "ticket", "OPEN")))
                .when(jdbc).query(contains("FROM sys_devops_ticket"), any(RowMapper.class), any(Object[].class));
        doReturn(List.of(new SearchResultItem("21", "集群手册", "knowledge", "PUBLISHED")))
                .when(jdbc).query(contains("FROM sys_knowledge_doc"), any(RowMapper.class), any(Object[].class));
        doReturn(List.of(new SearchResultItem("31", "节点告警", "alert", "T-9")))
                .when(jdbc).query(contains("FROM sys_alert"), any(RowMapper.class), any(Object[].class));

        mockMvc.perform(get("/api/v1/search").param("q", "故障"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value("T-9"))
                .andExpect(jsonPath("$.data[0].kind").value("ticket"))
                .andExpect(jsonPath("$.data[0].sub").value("OPEN"))
                .andExpect(jsonPath("$.data[1].id").value("21"))
                .andExpect(jsonPath("$.data[1].kind").value("knowledge"))
                .andExpect(jsonPath("$.data[2].id").value("31"))
                .andExpect(jsonPath("$.data[2].kind").value("alert"))
                .andExpect(jsonPath("$.data[2].sub").value("T-9"));
    }
}
