package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.service.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prometheus Alertmanager Webhook 接收端点（L2 实时监测）
 * <p>
 * 接收 Alertmanager 告警推送（receiver=opsbrain-webhook），
 * 调用 {@link AlertService#processWebhook(AlertmanagerWebhook)} 完成去重、持久化、自动建单。
 * </p>
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>必须返回 HTTP 200 —— Prometheus 对非 200 响应会按配置重试，返回 4xx/5xx 会造成重复推送</li>
 *   <li>单条告警失败不影响整体 —— 失败隔离由 {@link AlertService} 内部保证，本端点始终返回成功</li>
 *   <li>响应体为 {@link ApiResponse} 标准结构，业务码 0 表示接收成功</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-14
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/alerts/webhook")
public class AlertWebhookController {

    private final AlertService alertService;

    /** 告警接收总开关（application.yml devops.alert.enabled）。关闭后仍返回 200 但跳过处理 */
    @Value("${devops.alert.enabled:true}")
    private boolean alertEnabled;

    public AlertWebhookController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 接收 Alertmanager Webhook 推送（批量告警）
     * <p>
     * 入参为 Alertmanager 标准 JSON 结构（receiver/status/alerts[]），
     * Jackson 自动反序列化为 {@link AlertmanagerWebhook}。
     * 无论内部处理结果如何，均返回 HTTP 200 与业务码 0。
     * </p>
     */
    @PostMapping
    public ApiResponse<String> receiveWebhook(@RequestBody AlertmanagerWebhook webhook) {
        // 告警接收总开关（devops.alert.enabled）：关闭时仍返回 200 但跳过处理——
        // Prometheus 对非 200 会重试，若直接返回错误会在关闭期间造成告警反复推送
        if (!alertEnabled) {
            log.warn("⏸️ [AlertWebhookController] 告警接收已关闭（devops.alert.enabled=false），跳过处理");
            return ApiResponse.success("ok");
        }

        if (webhook == null || webhook.getAlerts() == null || webhook.getAlerts().isEmpty()) {
            log.warn("🛜 [AlertWebhookController] 收到空告警负载，跳过处理");
            return ApiResponse.success("ok");
        }

        log.info("🛜 [AlertWebhookController] 收到告警推送 | receiver={} | status={} | alerts={}",
                webhook.getReceiver(), webhook.getStatus(), webhook.getAlerts().size());

        // 失败隔离在 AlertService.processWebhook 内部完成，单条失败不影响其余
        alertService.processWebhook(webhook);

        return ApiResponse.success("ok");
    }
}