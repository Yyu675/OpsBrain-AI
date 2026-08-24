package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.common.exception.OptimisticLockException;
import com.devops.agent.domain.governance.ActionAllowlistEntry;
import com.devops.agent.domain.governance.ApprovalMode;
import com.devops.agent.domain.governance.AutomationGovernanceService;
import com.devops.agent.domain.governance.AutomationPolicy;
import com.devops.agent.domain.governance.EscalateTarget;
import com.devops.agent.domain.governance.RiskPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AutomationGovernanceController} HTTP 契约测试。
 *
 * <h3>为什么这个 Controller 值得优先补测试</h3>
 * 它是本项目端点最多的一个（19 个），且配置的是<b>「AI 能不能自动动生产系统」</b>
 * 的边界。这里的契约一旦漂移，后果不是「页面显示不对」，
 * 而是安全开关的语义变了却无人察觉。
 *
 * <h3>覆盖重点：契约而非业务</h3>
 * Service 全部 mock。这里只验证 HTTP 层的四件事：
 * <ol>
 *   <li><b>路由与参数绑定</b>——尤其是 {@code enabled=false} 这类
 *       「有效的假值」不能在绑定时被当成 null 丢掉；</li>
 *   <li><b>异常到业务码的映射</b>——40001/40004/40009 三条，
 *       它们由 {@link GlobalExceptionHandler} 统一接管，
 *       而这类跨切面的映射在编译期完全看不出对错；</li>
 *   <li><b>版本号必填</b>——缺 version 必须被拒，
 *       这是乐观锁不被悄悄关掉的最后一道防线；</li>
 *   <li><b>Bean Validation 生效</b>——{@code @Valid} 漏标是常见疏忽，
 *       漏了就等于服务端校验全线失守。</li>
 * </ol>
 *
 * <h3>切片装配（沿用 TicketControllerWebTest 已验证的做法）</h3>
 * 排除 {@code WebConfig}（会注册 SaInterceptor，切片里缺其运行时上下文，
 * 所有请求会变 401）与 {@code OperationAuditInterceptor}
 * （依赖 {@code @Repository}，切片不实例化 JDBC 层，会导致整个上下文启动失败）。
 * {@code TraceIdFilter} 无依赖且 traceId 是响应契约的一部分，故显式装回。
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = AutomationGovernanceController.class,
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
class AutomationGovernanceControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private AutomationGovernanceService service;

    /** addFilters=false 会关掉全部 Filter，这里只把 TraceIdFilter 织回 */
    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    // ==================== 夹具 ====================

    private static RiskPolicy samplePolicy() {
        RiskPolicy p = new RiskPolicy();
        p.setRiskLevel("CONTROLLED_WRITE");
        p.setDisplayName("受控写操作");
        p.setApprovalMode(ApprovalMode.SINGLE);
        p.setApprovalTimeoutMinutes(30);
        p.setAutoExecuteAllowed(false);
        p.setMaxBlastRadiusPercent(20);
        p.setMaxBlastRadiusCount(5);
        p.setCooldownSeconds(60);
        p.setMaxRetries(1);
        p.setEscalateAfterMinutes(15);
        p.setEscalateTarget(EscalateTarget.TICKET);
        p.setAllowedEnvironments("staging,dev");
        p.setVersion(2);
        return p;
    }

    private static ActionAllowlistEntry sampleAction() {
        ActionAllowlistEntry e = new ActionAllowlistEntry();
        e.setId(1L);
        e.setActionKey("k8s.pod.restart");
        e.setDisplayName("优雅重启 Pod");
        e.setCategory("k8s");
        e.setRiskLevel("CONTROLLED_WRITE");
        e.setTargetPattern("ns:staging/*");
        e.setEnvironments("staging,dev");
        e.setEnabled(false);
        e.setVersion(0);
        e.setEffectiveRequiresApproval(Boolean.TRUE);
        e.setEffectiveBlastRadiusCount(5);
        return e;
    }

    private static AutomationPolicy sampleAutomationPolicy() {
        AutomationPolicy p = new AutomationPolicy();
        p.setId(7L);
        p.setName("P3 Pod 崩溃自动重启");
        p.setMatchAlertLevels("P3");
        p.setActionKey("k8s.pod.restart");
        p.setEnvironment("staging");
        p.setPriority(20);
        p.setStopOnMatch(true);
        p.setCooldownMinutes(30);
        p.setMaxExecutionsPerDay(10);
        p.setDryRun(true);
        p.setEnabled(true);
        p.setVersion(0);
        return p;
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    // ==================================================================

    @Nested
    @DisplayName("风险等级策略")
    class RiskPolicies {

        @Test
        @DisplayName("列表：返回 items 与词表，且响应带 traceId")
        void listReturnsItemsAndVocabulary() throws Exception {
            when(service.listPolicies()).thenReturn(List.of(samplePolicy()));

            mockMvc.perform(get("/api/v1/governance/risk-policies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.items[0].riskLevel").value("CONTROLLED_WRITE"))
                    // 词表随数据下发，前端不维护枚举镜像
                    .andExpect(jsonPath("$.data.approvalModes").isArray())
                    .andExpect(jsonPath("$.data.escalateTargets").isArray())
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("更新：路径等级与 version 正确传给 Service")
        void updatePassesLevelAndVersion() throws Exception {
            when(service.updatePolicy(anyString(), any(), anyInt(), anyString()))
                    .thenReturn(samplePolicy());

            mockMvc.perform(put("/api/v1/governance/risk-policies/CONTROLLED_WRITE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "approvalMode", "SINGLE",
                                    "approvalTimeoutMinutes", 30,
                                    "autoExecuteAllowed", false,
                                    "maxBlastRadiusPercent", 20,
                                    "maxBlastRadiusCount", 5,
                                    "cooldownSeconds", 60,
                                    "maxRetries", 1,
                                    "escalateAfterMinutes", 15,
                                    "escalateTarget", "TICKET",
                                    "allowedEnvironments", "staging,dev",
                                    "version", 2))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            verify(service).updatePolicy(eq("CONTROLLED_WRITE"), any(), eq(2), anyString());
        }

        @Test
        @DisplayName("缺 version 被拒（40001）——缺版本号等于关掉乐观锁")
        void rejectsMissingVersion() throws Exception {
            mockMvc.perform(put("/api/v1/governance/risk-policies/CONTROLLED_WRITE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "approvalMode", "SINGLE",
                                    "approvalTimeoutMinutes", 30,
                                    "autoExecuteAllowed", false,
                                    "maxBlastRadiusPercent", 20,
                                    "maxBlastRadiusCount", 5,
                                    "cooldownSeconds", 60,
                                    "maxRetries", 1,
                                    "escalateAfterMinutes", 15,
                                    "escalateTarget", "TICKET",
                                    "allowedEnvironments", "staging,dev"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            verify(service, never()).updatePolicy(anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Service 抛 IllegalArgument 映射为 40001 / HTTP 400")
        void illegalArgumentMapsTo40001() throws Exception {
            when(service.updatePolicy(anyString(), any(), anyInt(), anyString()))
                    .thenThrow(new IllegalArgumentException("高风险执行不允许配置为免审批"));

            mockMvc.perform(put("/api/v1/governance/risk-policies/HIGH_RISK_EXECUTION")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(fullPolicyBody())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value("高风险执行不允许配置为免审批"));
        }

        @Test
        @DisplayName("版本冲突映射为 40009 / HTTP 409")
        void optimisticLockMapsTo40009() throws Exception {
            when(service.updatePolicy(anyString(), any(), anyInt(), anyString()))
                    .thenThrow(new OptimisticLockException("CONTROLLED_WRITE", 2, 3));

            mockMvc.perform(put("/api/v1/governance/risk-policies/CONTROLLED_WRITE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(fullPolicyBody())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40009));
        }

        private Map<String, Object> fullPolicyBody() {
            return Map.ofEntries(
                    Map.entry("approvalMode", "DUAL"),
                    Map.entry("approvalTimeoutMinutes", 15),
                    Map.entry("autoExecuteAllowed", false),
                    Map.entry("maxBlastRadiusPercent", 5),
                    Map.entry("maxBlastRadiusCount", 1),
                    Map.entry("cooldownSeconds", 300),
                    Map.entry("maxRetries", 0),
                    Map.entry("escalateAfterMinutes", 10),
                    Map.entry("escalateTarget", "ONCALL"),
                    Map.entry("allowedEnvironments", "dev"),
                    Map.entry("version", 2));
        }
    }

    @Nested
    @DisplayName("动作白名单")
    class Actions {

        @Test
        @DisplayName("列表：enabled=false 必须传到 Service，而不是被当成空值丢掉")
        void passesEnabledFalseAsFilter() throws Exception {
            when(service.listActions(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Map.of("items", List.of(), "total", 0L));

            mockMvc.perform(get("/api/v1/governance/actions").param("enabled", "false"))
                    .andExpect(status().isOk());

            // 「只看已停用」是有效筛选；若被当成 null，用户勾了却看到全部
            verify(service).listActions(any(), any(), any(), eq(Boolean.FALSE), anyInt(), anyInt());
        }

        @Test
        @DisplayName("列表：不传 enabled 时 Service 收到 null（表示不限）")
        void omittedEnabledBecomesNull() throws Exception {
            when(service.listActions(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Map.of("items", List.of(), "total", 0L));

            mockMvc.perform(get("/api/v1/governance/actions"))
                    .andExpect(status().isOk());

            verify(service).listActions(any(), any(), any(), eq(null), anyInt(), anyInt());
        }

        @Test
        @DisplayName("详情：下发服务端算好的 effective 生效值")
        void detailExposesEffectiveValues() throws Exception {
            when(service.getAction(1L)).thenReturn(sampleAction());

            mockMvc.perform(get("/api/v1/governance/actions/1"))
                    .andExpect(status().isOk())
                    // 生效值由后端合并，前端复算必然与引擎漂移
                    .andExpect(jsonPath("$.data.effectiveRequiresApproval").value(true))
                    .andExpect(jsonPath("$.data.effectiveBlastRadiusCount").value(5));
        }

        @Test
        @DisplayName("创建：@Valid 生效，空 actionKey 被拒 400")
        void validationRejectsBlankActionKey() throws Exception {
            mockMvc.perform(post("/api/v1/governance/actions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "actionKey", "",
                                    "displayName", "测试",
                                    "category", "k8s",
                                    "riskLevel", "READ_ONLY",
                                    "environments", "dev"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            // @Valid 漏标就等于服务端校验全线失守，这里确认它确实拦住了
            verify(service, never()).createAction(any(), anyString());
        }

        @Test
        @DisplayName("创建：合法请求透传给 Service")
        void createPassesThrough() throws Exception {
            when(service.createAction(any(), anyString())).thenReturn(sampleAction());

            mockMvc.perform(post("/api/v1/governance/actions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "actionKey", "k8s.pod.restart",
                                    "displayName", "优雅重启 Pod",
                                    "category", "k8s",
                                    "riskLevel", "CONTROLLED_WRITE",
                                    "targetPattern", "ns:staging/*",
                                    "environments", "staging,dev"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.actionKey").value("k8s.pod.restart"));
        }

        @Test
        @DisplayName("重复 actionKey：IllegalState 映射为 40004 / HTTP 409")
        void duplicateKeyMapsTo40004() throws Exception {
            when(service.createAction(any(), anyString()))
                    .thenThrow(new IllegalStateException("动作标识已存在：k8s.pod.restart"));

            mockMvc.perform(post("/api/v1/governance/actions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "actionKey", "k8s.pod.restart",
                                    "displayName", "优雅重启 Pod",
                                    "category", "k8s",
                                    "riskLevel", "CONTROLLED_WRITE",
                                    "targetPattern", "ns:staging/*",
                                    "environments", "staging,dev"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40004));
        }

        @Test
        @DisplayName("启停：enabled 与 version 都传给 Service")
        void togglePassesEnabledAndVersion() throws Exception {
            when(service.toggleAction(anyLong(), anyBoolean(), anyInt(), anyString()))
                    .thenReturn(sampleAction());

            mockMvc.perform(post("/api/v1/governance/actions/1/toggle")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("enabled", true, "version", 3))))
                    .andExpect(status().isOk());

            verify(service).toggleAction(eq(1L), eq(true), eq(3), anyString());
        }

        @Test
        @DisplayName("启停：缺 enabled 被拒 400")
        void toggleRejectsMissingEnabled() throws Exception {
            mockMvc.perform(post("/api/v1/governance/actions/1/toggle")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("version", 3))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            verify(service, never()).toggleAction(anyLong(), anyBoolean(), anyInt(), anyString());
        }
    }

    @Nested
    @DisplayName("模拟校验与预演")
    class Simulation {

        @Test
        @DisplayName("evaluate：空 actionKey 被拒 400")
        void evaluateRejectsBlankKey() throws Exception {
            mockMvc.perform(post("/api/v1/governance/evaluate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("actionKey", "  ", "environment", "prod"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("evaluate：省略 environment 时默认 prod")
        void evaluateDefaultsToProd() throws Exception {
            when(service.evaluate(anyString(), anyString()))
                    .thenReturn(Map.of("allowed", false, "reason", "未登记"));

            mockMvc.perform(post("/api/v1/governance/evaluate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("actionKey", "k8s.pod.restart"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.allowed").value(false));

            // 默认问 prod：用户最想确认的永远是生产环境能不能跑
            verify(service).evaluate("k8s.pod.restart", "prod");
        }

        @Test
        @DisplayName("predict/simulate：输入原样透传，结论逐条返回")
        void simulatePassesInput() throws Exception {
            when(service.simulate(any(), any(), any(), any(), anyString()))
                    .thenReturn(Map.of("matchedCount", 1L, "summary", "将由策略处理"));

            mockMvc.perform(post("/api/v1/governance/policies/simulate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "level", "P3",
                                    "module", "K8S",
                                    "service", "order-svc",
                                    "alertName", "PodCrashLoopBackOff",
                                    "environment", "staging"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.matchedCount").value(1));

            verify(service).simulate("P3", "K8S", "order-svc", "PodCrashLoopBackOff", "staging");
        }
    }

    @Nested
    @DisplayName("自动化策略")
    class Policies {

        @Test
        @DisplayName("列表：装填的 effective / ineffectiveReason 会下发")
        void listExposesEffectiveState() throws Exception {
            AutomationPolicy p = sampleAutomationPolicy();
            p.setEffective(Boolean.FALSE);
            p.setIneffectiveReason("引用的动作已停用");
            when(service.listAutomationPolicies(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Map.of("items", List.of(p), "total", 1L));

            mockMvc.perform(get("/api/v1/governance/policies"))
                    .andExpect(status().isOk())
                    // 「已启用但不生效」必须能被前端看到，否则界面在说谎
                    .andExpect(jsonPath("$.data.items[0].effective").value(false))
                    .andExpect(jsonPath("$.data.items[0].ineffectiveReason").value("引用的动作已停用"));
        }

        @Test
        @DisplayName("创建：dryRun 缺省为 true——默认只演练不执行")
        void createDefaultsToDryRun() throws Exception {
            when(service.createAutomationPolicy(any(), anyString()))
                    .thenReturn(sampleAutomationPolicy());

            mockMvc.perform(post("/api/v1/governance/policies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "name", "P3 重启",
                                    "matchAlertLevels", "P3",
                                    "actionKey", "k8s.pod.restart",
                                    "environment", "staging"))))
                    .andExpect(status().isOk());

            // 缺省成 false 会让「忘了传这个字段」变成「直接上线真实执行」
            org.mockito.ArgumentCaptor<AutomationPolicy> captor =
                    org.mockito.ArgumentCaptor.forClass(AutomationPolicy.class);
            verify(service).createAutomationPolicy(captor.capture(), anyString());
            org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().isDryRun(),
                    "新建策略必须默认演练模式");
            org.junit.jupiter.api.Assertions.assertFalse(captor.getValue().isEnabled(),
                    "新建策略必须默认未启用");
        }

        @Test
        @DisplayName("创建：stopOnMatch 缺省为 true")
        void createDefaultsStopOnMatch() throws Exception {
            when(service.createAutomationPolicy(any(), anyString()))
                    .thenReturn(sampleAutomationPolicy());

            mockMvc.perform(post("/api/v1/governance/policies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "name", "P3 重启",
                                    "matchAlertLevels", "P3",
                                    "actionKey", "k8s.pod.restart",
                                    "environment", "staging"))))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<AutomationPolicy> captor =
                    org.mockito.ArgumentCaptor.forClass(AutomationPolicy.class);
            verify(service).createAutomationPolicy(captor.capture(), anyString());
            org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().isStopOnMatch());
        }

        @Test
        @DisplayName("演练开关：dryRun 与 version 都传给 Service")
        void dryRunTogglePassesFields() throws Exception {
            when(service.toggleDryRun(anyLong(), anyBoolean(), anyInt(), anyString()))
                    .thenReturn(sampleAutomationPolicy());

            mockMvc.perform(post("/api/v1/governance/policies/7/dry-run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("dryRun", false, "version", 1))))
                    .andExpect(status().isOk());

            verify(service).toggleDryRun(eq(7L), eq(false), eq(1), anyString());
        }

        @Test
        @DisplayName("演练开关：缺 dryRun 被拒 400")
        void dryRunRejectsMissingField() throws Exception {
            mockMvc.perform(post("/api/v1/governance/policies/7/dry-run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("version", 1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            verify(service, never()).toggleDryRun(anyLong(), anyBoolean(), anyInt(), anyString());
        }

        @Test
        @DisplayName("删除：version 从 query 读取并必填")
        void deleteRequiresVersion() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/governance/policies/7"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            verify(service, never()).deleteAutomationPolicy(anyLong(), anyInt());
        }

        @Test
        @DisplayName("删除：带 version 时正常删除")
        void deleteWithVersion() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/governance/policies/7").param("version", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.deleted").value(true));

            verify(service).deleteAutomationPolicy(7L, 2);
        }
    }

    @Nested
    @DisplayName("统一响应契约")
    class ResponseContract {

        @Test
        @DisplayName("成功响应恒有 code/message/traceId/timestamp 四字段")
        void successEnvelopeShape() throws Exception {
            when(service.actionStats()).thenReturn(Map.of("total", 9));

            mockMvc.perform(get("/api/v1/governance/actions/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.traceId").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("失败响应同样带 traceId——排障时最需要它的正是失败请求")
        void errorEnvelopeKeepsTraceId() throws Exception {
            when(service.getAction(anyLong()))
                    .thenThrow(new IllegalArgumentException("动作不存在: 99"));

            mockMvc.perform(get("/api/v1/governance/actions/99"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.traceId").exists());
        }
    }
}
