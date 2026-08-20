package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.service.AlertQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 告警列表与处置接口（L2 实时监测 Stage 3）
 * <p>
 * 提供告警列表分页查询（状态/级别筛选）、人工确认、标记恢复。
 * 与 {@link AlertWebhookController}（告警接收）职责分离——
 * 前者面向 Prometheus 推送，本控制器面向运维人员的前端列表操作。
 * </p>
 *
 * <h3>六层架构契约</h3>
 * <p>本控制器不依赖 infrastructure 层，所有查询与处置经 {@link AlertQueryService} 封装（6.45）。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-19
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertQueryService alertQueryService;

    public AlertController(AlertQueryService alertQueryService) {
        this.alertQueryService = alertQueryService;
    }

    /**
     * 分页查询告警列表
     *
     * @param page   页码（从 1 开始）
     * @param size   每页大小
     * @param status 状态筛选（FIRING/ACKNOWLEDGED/RESOLVED，可选）
     * @param level  级别筛选（P0~P4，可选）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listAlerts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String level) {

        // 分页参数兜底：page < 1 会让 OFFSET 变负，size 越界会一次拉爆
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        log.info("[AlertController] 查询告警列表: page={}, size={}, status={}, level={}",
                safePage, safeSize, status, level);

        Map<String, Object> result = alertQueryService.listAlerts(status, level, safePage, safeSize);
        return ApiResponse.success(result);
    }

    /**
     * 查询单个告警详情（告警详情页 /alerts/:id）
     * <p>供告警详情页展示完整字段（含处置时间线 acknowledgedAt/resolvedAt/ticketId）。
     * 空 ID → 40001，不存在 → 40004，与工单详情三态语义一致（6.18 契约）。</p>
     */
    @GetMapping("/{id}")
    public ApiResponse<Alert> getAlert(@PathVariable Long id) {
        log.info("[AlertController] 查询告警详情: id={}", id);
        try {
            return ApiResponse.success(alertQueryService.getAlert(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (Exception e) {
            log.error("[AlertController] 查询告警详情失败 | id={}", id, e);
            return ApiResponse.error(50001, "查询告警详情失败");
        }
    }

    /**
     * 人工确认告警
     */
    @PostMapping("/{id}/acknowledge")
    public ApiResponse<Alert> acknowledge(@PathVariable Long id) {
        log.info("[AlertController] 确认告警: id={}", id);
        try {
            return ApiResponse.success(alertQueryService.acknowledge(id), "已确认");
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[AlertController] 确认告警失败 | id={}", id, e);
            return ApiResponse.error(50001, "确认告警失败");
        }
    }

    /**
     * 标记告警已恢复
     */
    @PostMapping("/{id}/resolve")
    public ApiResponse<Alert> resolve(@PathVariable Long id) {
        log.info("[AlertController] 标记告警恢复: id={}", id);
        try {
            return ApiResponse.success(alertQueryService.resolve(id), "已标记恢复");
        } catch (IllegalStateException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[AlertController] 标记告警恢复失败 | id={}", id, e);
            return ApiResponse.error(50001, "标记恢复失败");
        }
    }
}
