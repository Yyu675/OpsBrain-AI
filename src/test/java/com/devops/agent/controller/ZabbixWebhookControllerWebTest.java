package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.common.exception.WebhookRejectedException;
import com.devops.agent.common.web.WebhookGuard;
import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.DTO.ZabbixWebhook;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ZabbixWebhookController} HTTP 契约测试。
 *
 * <h3>本类最重要的两条断言</h3>
 * <ol>
 *   <li><b>路由归属</b>：Zabbix 的接入路径 {@code /api/v1/alerts/webhook/zabbix} 与
 *       Alertmanager 的 FR-1.2 通配端点 {@code /api/v1/alerts/webhook/{system}} 形状重叠
 *       （literal 与模板并存时 Spring 选更具体的 literal）。这条优先级是本端点的
 *       立身之本——若哪天被反转，Zabbix 事件会被当成「system=zabbix 的 Alertmanager
 *       推送」，表现为一批字段全空的告警，且<b>不会有任何报错</b>。故两类控制器
 *       同时装进本测试上下文，把优先级钉成可执行断言。</li>
 *   <li><b>错误语义</b>：负载形状不合法 → 400（让 Zabbix 事件日志里直接出现原因），
 *       密钥不对 → 401，限流 → 429 + Retry-After。与 Alertmanager 端点「无论如何
 *       都 200」的取舍相反，理由写在控制器类注释里。</li>
 * </ol>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = {ZabbixWebhookController.class, AlertWebhookController.class},
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
@DisplayName("Zabbix Webhook 端点契约")
class ZabbixWebhookControllerWebTest {

    private static final String PATH = "/api/v1/alerts/webhook/zabbix";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ZabbixWebhookController controller;

    private MockMvc mockMvc;

    @MockitoBean
    private AlertService alertService;

    @MockitoBean
    private WebhookGuard webhookGuard;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .build();
    }

    /** 真实事件形态的最小负载（键名与 Zabbix 媒介类型脚本一致） */
    private static final String ZABBIX_PAYLOAD = """
            {
              "event_id": "12002061",
              "event_name": "primera01 数据接收异常：Failed to receive data: volumes : Error: cannot get URL: Timeout was reached.",
              "event_severity": "Average",
              "event_date": "2026.10.13",
              "event_time": "17:37:55",
              "event_status": "PROBLEM",
              "event_value": "1",
              "trigger_id": "20531",
              "trigger_name": "NodeDown-IT44c08600 数据接收异常",
              "host_name": "primera01",
              "host_ip": "10.210.44.11"
            }
            """;

    private static final String ALERTMANAGER_PAYLOAD = """
            {
              "receiver": "opsbrain-webhook",
              "status": "firing",
              "alerts": [{
                "status": "firing",
                "labels": {"alertname": "HighCpu", "service": "api", "severity": "critical"},
                "startsAt": "2026-10-14T09:00:00Z"
              }]
            }
            """;

    // ==================================================================

    @Nested
    @DisplayName("放行路径")
    class Accepted {

        @Test
        @DisplayName("校验通过 → 交给 AlertService.processSourceWebhook，返回 200 + code 0")
        void acceptedPayloadForwarded() throws Exception {
            doNothing().when(webhookGuard).verify(any());

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(ZABBIX_PAYLOAD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").value("ok"));

            verify(alertService).processSourceWebhook(any(ZabbixWebhook.class));
            // 换路径不换防护：机器回调面同样过 WebhookGuard
            verify(webhookGuard).verify(any());
        }

        @Test
        @DisplayName("空负载（{}）→ 200 且不触达业务：接入自测的「测试」按钮不能吃 400")
        void emptyPayloadReturns200() throws Exception {
            doNothing().when(webhookGuard).verify(any());

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            verify(alertService, never()).processSourceWebhook(any());
        }

        @Test
        @DisplayName("总开关关闭 → 200 且不处理（关闭接收不等于放开鉴权，Guard 仍在最前）")
        void disabledSwitchSkipsProcessing() throws Exception {
            doNothing().when(webhookGuard).verify(any());
            ReflectionTestUtils.setField(controller, "alertEnabled", false);
            try {
                mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(ZABBIX_PAYLOAD))
                        .andExpect(status().isOk());

                verify(webhookGuard).verify(any());
                verify(alertService, never()).processSourceWebhook(any());
            } finally {
                ReflectionTestUtils.setField(controller, "alertEnabled", true);
            }
        }
    }

    @Nested
    @DisplayName("路由归属（literal 与 {system} 模板并存）")
    class Routing {

        @Test
        @DisplayName("/webhook/zabbix 归属 Zabbix 端点 —— 不被 {system} 通配端点截胡")
        void zabbixPathBelongsToZabbixController() throws Exception {
            doNothing().when(webhookGuard).verify(any());

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(ZABBIX_PAYLOAD))
                    .andExpect(status().isOk());

            // 正向证据才是路由的正解：被截胡时 AlertmanagerWebhook 会绑成「alerts 为空」，
            // 端点直接返回 200 不调任何 service——只看「没调错方法」是看不出来的
            verify(alertService).processSourceWebhook(any(ZabbixWebhook.class));
            verify(alertService, never()).processWebhook(any(AlertmanagerWebhook.class), anyString());
            verify(alertService, never()).processWebhook(any(AlertmanagerWebhook.class), isNull());
            verify(alertService, never()).processWebhook(any(AlertmanagerWebhook.class));
        }

        @Test
        @DisplayName("其它 system 路径仍归 Alertmanager 端点 —— 新增 literal 没挤掉 FR-1.2")
        void otherSystemPathsStillGoToAlertmanager() throws Exception {
            doNothing().when(webhookGuard).verify(any());

            mockMvc.perform(post("/api/v1/alerts/webhook/mes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ALERTMANAGER_PAYLOAD))
                    .andExpect(status().isOk());

            verify(alertService).processWebhook(any(AlertmanagerWebhook.class), eq("mes"));
            verify(alertService, never()).processSourceWebhook(any());
        }
    }

    @Nested
    @DisplayName("拒绝路径")
    class Rejected {

        @Test
        @DisplayName("密钥不对 → 401 / 40104，不带 Retry-After，且绝不触达业务")
        void unauthorizedMapsTo401() throws Exception {
            doThrow(WebhookRejectedException.unauthorized()).when(webhookGuard).verify(any());

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(ZABBIX_PAYLOAD))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(40104))
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));

            verify(alertService, never()).processSourceWebhook(any());
        }

        @Test
        @DisplayName("限流 → 429 + Retry-After（退避后重投，告警不丢）")
        void rateLimitedMapsTo429() throws Exception {
            doThrow(WebhookRejectedException.rateLimited(30)).when(webhookGuard).verify(any());

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(ZABBIX_PAYLOAD))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value(42901))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"));

            verify(alertService, never()).processSourceWebhook(any());
        }

        @Test
        @DisplayName("适配器不认这份负载 → 400 且带人话原因（接入方在 Zabbix 事件日志里就能看到）")
        void unrecognizedPayloadMapsTo400() throws Exception {
            doNothing().when(webhookGuard).verify(any());
            doThrow(new IllegalArgumentException("Zabbix 负载缺少 trigger_name/event_name：无法构造告警名"))
                    .when(alertService).processSourceWebhook(any());

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"trigger_id\":\"1\",\"host_name\":\"h1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("trigger_name")));
        }
    }
}
