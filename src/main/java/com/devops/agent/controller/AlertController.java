package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.controller.dto.AlertDto;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.service.AlertQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
     * @param page      页码（从 1 开始）
     * @param size      每页大小
     * @param status    状态筛选（FIRING/ACKNOWLEDGED/RESOLVED，可选）
     * @param level     级别筛选（P0~P4，可选）
     * @param system    来源系统筛选（V9 起；mes/erp/wms/qms…，可选）
     * @param observing 只看观察中（FR-3.1 派生：活跃+未建单+观察级+未超窗）
     */
    @GetMapping
    public ApiResponse<AlertDto.AlertPage> listAlerts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String system,
            @RequestParam(defaultValue = "false") boolean observing) {

        // 分页参数兜底：page < 1 会让 OFFSET 变负，size 越界会一次拉爆
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        log.info("[AlertController] 查询告警列表: page={}, size={}, status={}, level={}, system={}, observing={}",
                safePage, safeSize, status, level, system, observing);

        List<Alert> alerts = alertQueryService.findAlerts(status, level, system, observing, safePage, safeSize);
        // 总数必须按同一条件统计，否则页码与实际数据矛盾
        long total = alertQueryService.countAlerts(status, level, system, observing);

        // 用 record 而非 Map（P0-2b 第三步）：Map 让 OpenAPI 只能生成
        // additionalProperties:true，前端拿不到类型；且 map.put("totalPages", ...)
        // 改个键名不会有编译信号，只是前端悄悄拿到 undefined。
        return ApiResponse.success(AlertDto.AlertPage.of(alerts, total, safePage, safeSize));
    }

    /**
     * 全部来源系统去重列表（告警列表 system 筛选下拉的数据源）。
     * <p>从 sys_alert 实表 DISTINCT——不维护配置清单，接了哪个系统表里自然有它。</p>
     */
    @GetMapping("/systems")
    public ApiResponse<List<String>> listSystems() {
        return ApiResponse.success(alertQueryService.listSystems());
    }

    /**
     * 查询单个告警详情（告警详情页 /alerts/:id）
     * <p>供告警详情页展示完整字段（含处置时间线 acknowledgedAt/resolvedAt/ticketId）。
     * 空 ID → 40001，不存在 → 40004，与工单详情三态语义一致（6.18 契约）。</p>
     */
    @GetMapping("/{id}")
    public ApiResponse<Alert> getAlert(@PathVariable Long id) {
        log.info("[AlertController] 查询告警详情: id={}", id);
        return ApiResponse.success(alertQueryService.getAlert(id));
    }

    /**
     * 同事件告警联动（建议3：告警详情页「同事件告警」列表）。
     * <p>口径 = 同 system + service ±window 分钟窗（默认 10 分钟），
     * 与 Incident 方案 C 的「事件」归并一致。窗口夹紧到 [1, 60]——过小查不到兄弟，
     * 过大则把无关告警卷进来。</p>
     */
    @GetMapping("/{id}/related")
    public ApiResponse<List<Alert>> related(@PathVariable Long id,
                                            @RequestParam(defaultValue = "10") int window) {
        int safeWindow = Math.min(Math.max(1, window), 60);
        log.info("[AlertController] 查询同事件告警: id={} window={}min", id, safeWindow);
        return ApiResponse.success(alertQueryService.findRelated(id, safeWindow));
    }

    /**
     * 管道心跳状态（FR-1.6 看门狗的可视面）。
     * <p>
     * 返回看门狗最近一次送达时间与是否超阈值静默——告警页顶部据此展示
     * 「管道活着/管道断了」。没有这条，告警列表长期空白会被误读成「天下太平」。
     * </p>
     *
     * @return {lastSeenAt, silent, silenceMinutes}；从未收到看门狗时 lastSeenAt 为 null
     */
    @GetMapping("/heartbeat")
    public ApiResponse<java.util.Map<String, Object>> heartbeat() {
        return ApiResponse.success(alertQueryService.pipelineHeartbeat(pipelineHeartbeatSilenceMinutes));
    }

    /**
     * 日志管道心跳（与告警心跳同族，效能大盘「日志管道」卡读它）。
     * <p>数据来自 LogsPipelineWatchdogScheduler 的最近探测快照——
     * 探测是 5 分钟一轮的低频旁路，前端读快照不重复打 Loki。</p>
     */
    @GetMapping("/logs-heartbeat")
    public ApiResponse<java.util.Map<String, Object>> logsHeartbeat() {
        if (logsWatchdog == null) {
            return ApiResponse.success(java.util.Map.of("probed", false, "silent", false));
        }
        return ApiResponse.success(logsWatchdog.snapshot());
    }

    /** 日志看门狗（可选装配：未启用 Loki 时 bean 仍在，快照如实报 lokiEnabled=false） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.devops.agent.application.runtime.LogsPipelineWatchdogScheduler logsWatchdog;

    /** 心跳静默阈值与调度器同配置键——两处读同一值，页面口径与报警口径不分叉 */
    @org.springframework.beans.factory.annotation.Value("${devops.alert.pipeline-heartbeat.silence-minutes:3}")
    private int pipelineHeartbeatSilenceMinutes;

    /**
     * 自愈观察窗统计（FR-3.1 的可视面，效能大盘「自愈观察窗」卡读它）。
     * <p>观察窗关闭时返回 {@code enabled=false}，前端据此隐藏区块而非显示一排 0。</p>
     */
    @GetMapping("/observation-stats")
    public ApiResponse<com.devops.agent.domain.alert.DTO.ObservationStats> observationStats() {
        return ApiResponse.success(alertQueryService.observationStats());
    }

    /**
     * 全局风暴模式状态（FR-2.5 的可视面，告警列表顶部横幅读它）。
     * <p>风暴期间低级别告警不再单独建单——没有这个指示，值班人会以为系统漏单。</p>
     */
    @GetMapping("/storm-status")
    public ApiResponse<com.devops.agent.domain.alert.DTO.StormStatus> stormStatus() {
        return ApiResponse.success(alertQueryService.stormStatus());
    }

    /**
     * 人工确认告警
     */
    @PostMapping("/{id}/acknowledge")
    public ApiResponse<Alert> acknowledge(@PathVariable Long id) {
        log.info("[AlertController] 确认告警: id={}", id);
        return ApiResponse.success(alertQueryService.acknowledge(id), "已确认");
    }

    /**
     * 标记告警已恢复
     */
    @PostMapping("/{id}/resolve")
    public ApiResponse<Alert> resolve(@PathVariable Long id) {
        log.info("[AlertController] 标记告警恢复: id={}", id);
        return ApiResponse.success(alertQueryService.resolve(id), "已标记恢复");
    }
}
