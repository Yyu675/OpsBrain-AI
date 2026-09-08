package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HealthCheckController} 付费探针<b>开启后</b>的行为。
 *
 * <h3>为什么单独一个类，而不是用 {@code @Nested} + {@code @TestPropertySource}</h3>
 * 最初就是那么写的，CI 直接告诉我不行：
 * <pre>
 *   enabledProbeCallsModelsAndReportsFailure
 *   JSON path "$.overallStatus" expected:&lt;FAILED&gt; but was:&lt;DISABLED&gt;
 * </pre>
 * {@code @TestPropertySource} 标在 {@code @Nested} 内嵌类上<b>不会覆盖外层类的属性</b>——
 * 内嵌类继承外层的 {@code ApplicationContext} 配置，
 * 属性源以外层为准，于是开关仍是 false。
 *
 * <p>属性不同就意味着<b>不同的 ApplicationContext</b>，只能拆成独立的顶层测试类。</p>
 *
 * <h3>这个类是「对照组」，不是可有可无的补充</h3>
 * {@code HealthCheckControllerWebTest} 断言的是「开关关闭时一次模型都不调」。
 * 但只有这一条的话，<b>把开关判断写死成永远返回 DISABLED 也能全绿</b>——
 * 那样付费探针就彻底废了，而测试不会有任何反应。
 *
 * <p>必须有一组证明「开启后确实会调模型」，
 * 两组合起来才真正锁住了这个开关的行为。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = HealthCheckController.class,
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
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK",
        // 与 HealthCheckControllerWebTest 的唯一差异：这里显式打开付费探测
        "devops.ai.health.ai-model-enabled=true"
})
@DisplayName("健康检查 · 付费探针（显式开启）")
class HealthCheckAiProbeEnabledWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean(name = "turboModel")
    private ChatModel turboModel;

    @MockitoBean(name = "reasonerModel")
    private ChatModel reasonerModel;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    @Test
    @DisplayName("开启后确实调用模型 —— 这条证明开关不是被写死成永远关闭")
    void enabledProbeActuallyCallsModels() throws Exception {
        // 让第一个模型就抛错：既能验证「确实调了」，
        // 又能顺带验证异常兜底。健康检查端点自己崩掉是最糟的结果——
        // 运维连「哪里坏了」都看不到
        when(turboModel.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("上游 401 Unauthorized"));

        mockMvc.perform(get("/api/v1/health/ai-model"))
                .andExpect(status().isOk())
                // 关键：不再是 DISABLED，说明开关真的被读取并生效了
                .andExpect(jsonPath("$.overallStatus").value("FAILED"))
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("401")));

        verify(turboModel).chat(any(ChatRequest.class));
    }

    @Test
    @DisplayName("开启后响应里不再出现 DISABLED 与那段关闭说明")
    void enabledProbeHasNoDisabledMarker() throws Exception {
        when(turboModel.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/v1/health/ai-model"))
                .andExpect(status().isOk())
                // reason 只在关闭态出现；开启后还带着它会让运维误以为没开成功
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.mode").value("MOCK"));
    }
}
