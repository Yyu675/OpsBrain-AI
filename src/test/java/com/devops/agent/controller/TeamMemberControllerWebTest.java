package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.biz.entity.TeamMember;
import com.devops.agent.domain.biz.service.TeamMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamMemberController} HTTP 契约测试。
 *
 * <h3>这个端点存在的意义决定了它的测试重点</h3>
 * 它是为修复一个真实缺陷而生的：前端曾硬编码
 * {@code ASSIGNEE_OPTIONS = ['张明','李四','王五','赵六','孙七','周八','待分配']}，
 * 而库里只有「张明」一个真实成员。用户选人后写进
 * {@code sys_devops_ticket.assignee}（自由文本），
 * <b>工单被指派给了不存在的人</b>，且没有任何报错。
 *
 * <p>所以这里守的不是「接口能不能通」，而是三件与那个缺陷直接相关的事：</p>
 * <ol>
 *   <li><b>名单来自数据库</b>——Service 返回几个就下发几个，
 *       Web 层不补默认项。一旦有人「为了下拉框好看」在这里塞回
 *       「待分配」之类的占位，缺陷就复活了；</li>
 *   <li><b>默认不含停用成员</b>——{@code includeDisabled} 默认 false。
 *       默认反了会让离职的人重新出现在选人框里；</li>
 *   <li><b>{@code total} 与 {@code users.length} 必须一致</b>——
 *       前端用 total 判空态。两者不一致会出现「显示共 7 人但列表是空的」。</li>
 * </ol>
 *
 * <p>切片装配沿用 {@code TicketControllerWebTest} 的说明。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = TeamMemberController.class,
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
@Import({GlobalExceptionHandler.class, com.devops.agent.common.web.TraceIdFilter.class})
class TeamMemberControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private TeamMemberService teamMemberService;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private static TeamMember member(Long id, String name, String status, Integer activeTickets) {
        TeamMember m = new TeamMember();
        m.setId(id);
        m.setName(name);
        m.setEmail(name + "@example.com");
        m.setRole("operator");
        m.setTitle("运维工程师");
        m.setStatus(status);
        m.setSortOrder(10);
        m.setActiveTicketCount(activeTickets);
        return m;
    }

    // ==================================================================

    @Test
    @DisplayName("名录字段齐备：选人时要看到负载（activeTicketCount），否则只能凭感觉派单")
    void listReturnsMembersWithWorkload() throws Exception {
        when(teamMemberService.listAssignableMembers(false))
                .thenReturn(List.of(member(1L, "张明", "ACTIVE", 3)));

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.users[0].id").value(1))
                .andExpect(jsonPath("$.data.users[0].name").value("张明"))
                .andExpect(jsonPath("$.data.users[0].role").value("operator"))
                .andExpect(jsonPath("$.data.users[0].title").value("运维工程师"))
                .andExpect(jsonPath("$.data.users[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.users[0].activeTicketCount").value(3))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("默认不含停用成员 —— 默认反了会让离职的人重新出现在选人框")
    void defaultsToExcludingDisabled() throws Exception {
        when(teamMemberService.listAssignableMembers(false)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk());

        verify(teamMemberService).listAssignableMembers(false);
    }

    @Test
    @DisplayName("includeDisabled=true 透传给 Service（管理页需要看到全量名录）")
    void passesIncludeDisabledFlag() throws Exception {
        when(teamMemberService.listAssignableMembers(true)).thenReturn(List.of(
                member(1L, "张明", "ACTIVE", 3),
                member(2L, "李四", "DISABLED", 0)));

        mockMvc.perform(get("/api/v1/users").param("includeDisabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.users[1].status").value("DISABLED"));

        verify(teamMemberService).listAssignableMembers(true);
    }

    @Test
    @DisplayName("空名录返回 total=0 与空数组，不返回 null —— 前端不必对 users 判空")
    void emptyRosterIsEmptyArrayNotNull() throws Exception {
        when(teamMemberService.listAssignableMembers(false)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.users").isArray())
                .andExpect(jsonPath("$.data.users").isEmpty());
    }

    @Test
    @DisplayName("Web 层不补任何默认项 —— 名单必须与库一致，这正是本端点存在的原因")
    void doesNotInjectPlaceholderAssignee() throws Exception {
        when(teamMemberService.listAssignableMembers(false))
                .thenReturn(List.of(member(1L, "张明", "ACTIVE", 3)));

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                // 库里只有一个人，就只能下发一个人。
                // 若有人在这里补回「待分配」等占位，工单又会被指派给不存在的人
                .andExpect(jsonPath("$.data.users.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("total 与 users.length 始终一致 —— 不一致会出现「共 3 人但列表为空」")
    void totalMatchesListLength() throws Exception {
        when(teamMemberService.listAssignableMembers(false)).thenReturn(List.of(
                member(1L, "张明", "ACTIVE", 3),
                member(2L, "王五", "ACTIVE", 0),
                member(3L, "赵六", "ACTIVE", 7)));

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.users.length()").value(3));
    }

    @Test
    @DisplayName("activeTicketCount 为 null 时字段保留为 null，不伪装成 0")
    void nullWorkloadIsNotZero() throws Exception {
        when(teamMemberService.listAssignableMembers(false))
                .thenReturn(List.of(member(1L, "张明", "ACTIVE", null)));

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                // 「没统计到负载」和「负载为 0」是两回事：
                // 后者意味着这人很闲、可以派单，前者意味着这个数不能作为派单依据
                .andExpect(jsonPath("$.data.users[0].activeTicketCount").doesNotExist());
    }

    @Test
    @DisplayName("includeDisabled 传非法值时按 400 处理，不静默当成 false")
    void invalidFlagIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users").param("includeDisabled", "yes-please"))
                .andExpect(status().is4xxClientError());
    }
}
