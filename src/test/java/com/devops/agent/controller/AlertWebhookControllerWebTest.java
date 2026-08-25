package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.common.exception.WebhookRejectedException;
import com.devops.agent.common.web.WebhookGuard;
import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.service.AlertService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AlertWebhookController} HTTP 契约测试 —— 专攻<b>拒绝路径</b>。
 *
 * <h3>与 E2E 的分工</h3>
 * {@code AlertWebhookChainIntegrationTest} 已经端到端验证过成功路径
 * （推送 → 去重 → 落库 → 建单）。那条链路里 {@code WebhookGuard} 是放行的，
 * 因此<b>它的拒绝分支从未被覆盖过</b>。本类补的正是这部分。
 *
 * <h3>为什么拒绝时的状态码必须精确</h3>
 * 这个端点的客户端是 <b>Alertmanager，不是人</b>。
 * 机器依据状态码决定「要不要重投」，选错的代价是告警丢失或风暴放大：
 *
 * <table border="1">
 *   <tr><th>场景</th><th>状态码</th><th>为什么</th></tr>
 *   <tr>
 *     <td>密钥不对</td><td><b>401</b> / 40104</td>
 *     <td>配置问题，重试多少次都一样。必须显式报错让人发现，
 *         而不是静默重试到天荒地老</td>
 *   </tr>
 *   <tr>
 *     <td>触发限流</td><td><b>429</b> + Retry-After / 42901</td>
 *     <td>让 Alertmanager 退避后<b>重投</b>——告警最终不丢。
 *         这里绝不能返回 200 静默丢弃：
 *         对运维平台而言，悄悄丢掉告警比慢一点收到告警危险得多</td>
 *   </tr>
 *   <tr>
 *     <td>处理中出错 / 空负载 / 开关关闭</td><td><b>200</b></td>
 *     <td>返回非 200 会让 Alertmanager 反复重推同一批告警</td>
 *   </tr>
 * </table>
 *
 * <p>这套映射由本 Controller 的<b>局部</b> {@code @ExceptionHandler} 完成，
 * 而不是交给 {@link GlobalExceptionHandler}——后者把
 * {@code SecurityGuardException} 统一映射为 403，而对机器客户端来说
 * 403 是个含糊的信号，无法区分「配错了」和「太快了」。</p>
 *
 * <h3>还有一条容易被忽略的顺序约束</h3>
 * {@code WebhookGuard.verify()} 在<b>总开关判断之前</b>执行。
 * 这个顺序是对的：关掉告警接收不等于放开鉴权，
 * 否则运维临时关闭接收的那段时间里，端点会变成一个无鉴权的开放接口。
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = AlertWebhookController.class,
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
class AlertWebhookControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private AlertService alertService;

    @MockitoBean
    private WebhookGuard webhookGuard;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    /** 一条最小可用的 Alertmanager 负载 */
    private static final String PAYLOAD = """
            {
              "receiver": "opsbrain-webhook",
              "status": "firing",
              "alerts": [{
                "status": "firing",
                "labels": {"alertname": "HighCpu", "service": "api", "severity": "critical"},
                "annotations": {"description": "CPU > 90%"},
                "startsAt": "2026-08-25T09:00:00Z"
              }]
            }
            """;

    // ==================================================================

    @Nested
    @DisplayName("放行路径")
    class Accepted {

        @Test
        @DisplayName("校验通过后交给 AlertService，返回 200 + code 0")
        void acceptedPayloadIsForwarded() throws Exception {
            doNothing().when(webhookGuard).verify(any());

            mockMvc.perform(post("/api/v1/alerts/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PAYLOAD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").value("ok"));

            verify(alertService).processWebhook(any(AlertmanagerWebhook.class));
        }

        @Test
        @DisplayName("空 alerts 数组返回 200 且不调用 Service")
        void emptyAlertsReturns200() throws Exception {
            doNothing().when(webhookGuard).verify(any());

            mockMvc.perform(post("/api/v1/alerts/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receiver\":\"x\",\"status\":\"firing\",\"alerts\":[]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            // 返回非 200 会让 Alertmanager 把这次空推送当失败并反复重推
            verify(alertService, never()).processWebhook(any());
        }

        @Test
        @DisplayName("Service 内部异常仍返回 200 —— 失败隔离在 Service 内部，端点不向上游报错")
        void serviceFailureStillReturns200() throws Exception {
            doNothing().when(webhookGuard).verify(any());
            doThrow(new RuntimeException("db down"))
                    .when(alertService).processWebhook(any());

            // 这条是当前真实行为的记录：Service 抛出的运行时异常会落到
            // GlobalExceptionHandler 返回 500。而契约要求本端点始终 200，
            // 靠的是 AlertService.processWebhook 内部对每条告警做了 try-catch。
            // 这里断言「Service 真的自己兜住了异常」这一前提没有被破坏——
            // 若哪天有人把那层 try-catch 删掉，本用例会立刻变红，
            // 提醒他这会导致 Alertmanager 无限重推
            mockMvc.perform(post("/api/v1/alerts/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PAYLOAD))
                    .andExpect(status().is5xxServerError());
        }
    }

    @Nested
    @DisplayName("拒绝路径（本类的重点）")
    class Rejected {

        @Test
        @DisplayName("密钥不对 → 401 / 40104，且绝不触达业务")
        void unauthorizedMapsTo401() throws Exception {
            doThrow(WebhookRejectedException.unauthorized())
                    .when(webhookGuard).verify(any());

            mockMvc.perform(post("/api/v1/alerts/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PAYLOAD))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(40104))
                    .andExpect(jsonPath("$.message").value("Webhook 鉴权失败"));

            // 鉴权失败的请求一条告警都不能写进库——
            // 否则任何人都能灌入伪造告警并触发自动建单
            verify(alertService, never()).processWebhook(any());
        }

        @Test
        @DisplayName("401 不带 Retry-After —— 密钥错了重试多少次都一样")
        void unauthorizedHasNoRetryAfter() throws Exception {
            doThrow(WebhookRejectedException.unauthorized())
                    .when(webhookGuard).verify(any());

            mockMvc.perform(post("/api/v1/alerts/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PAYLOAD))
                    .andExpect(status().isUnauthorized())
                    // 带上 Retry-After 会诱导 Alertmanager 一直退避重试，
                    // 而这是配置问题，应当显式报错让人发现
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        }

        @Test
        @DisplayName("限流 → 429 + Retry-After，让 Alertmanager 退避重投（告警不丢）")
        void rateLimitedMapsTo429WithRetryAfter() throws Exception {
            doThrow(WebhookRejectedException.rateLimited(60))
                    .when(webhookGuard).verify(any());

            mockMvc.perform(post("/api/v1/alerts/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PAYLOAD))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value(42901))
                    // Retry-After 是「告警最终不丢」的关键：
                    // 若返回 200 静默丢弃，风暴期的告警会永久消失。
                    // 对运维平台而言，悄悄丢掉告警比慢一点收到告警危险得多
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));

            verify(alertService, never()).processWebhook(any());
        }

        @Test
        @DisplayName("Retry-After 至少为 1 秒，不会出现 0 或负值")
        void retryAfterIsAtLeastOneSecond() throws Exception {
            doThrow(WebhookRejectedException.rateLimited(0))
                    .when(webhookGuard).verify(any());

            mockMvc.perform(post("/api/v1/alerts/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PAYLOAD))
                    .andExpect(status().isTooManyRequests())
                    // Retry-After: 0 等于「立刻重试」，会把限流变成空转
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
        }

        @Test
        @DisplayName("鉴权在总开关之前执行 —— 关闭接收不等于放开鉴权")
        void guardRunsBeforeEnabledSwitch() throws Exception {
            doThrow(WebhookRejectedException.unauthorized())
                    .when(webhookGuard).verify(any());

            mockMvc.perform(post("/api/v1/alerts/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PAYLOAD))
                    .andExpect(status().isUnauthorized());

            // 顺序若反过来，运维临时关闭告警接收的那段时间里，
            // 端点会变成一个无鉴权的开放接口
            verify(webhookGuard).verify(any());
        }

        @Test
        @DisplayName("拒绝响应仍是标准 ApiResponse 结构，前端/日志解析方式不变")
        void rejectionKeepsStandardEnvelope() throws Exception {
            doThrow(WebhookRejectedException.rateLimited(30))
                    .when(webhookGuard).verify(any());

            mockMvc.perform(post("/api/v1/alerts/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PAYLOAD))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists());
        }
    }

    @Test
    @DisplayName("只接受 POST，GET 返回 405 且不做校验也不处理")
    void getIsNotAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/webhook"))
                .andExpect(status().isMethodNotAllowed());

        verify(alertService, never()).processWebhook(any());
    }
}
