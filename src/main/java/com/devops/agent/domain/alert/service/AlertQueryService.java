package com.devops.agent.domain.alert.service;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 告警查询与处置服务（L2 实时监测 Stage 3）
 * <p>
 * 职责：为告警列表页提供分页筛选查询，以及人工确认（acknowledge）与恢复（resolve）处置。
 * 与 {@link AlertService}（告警摄取/去重/自动建单）职责分离——后者面向 Alertmanager 推送，
 * 本服务面向运维人员在前端的列表操作。
 * </p>
 *
 * <h3>六层架构契约</h3>
 * <p>Controller 不得直接依赖 infrastructure 层的 {@link AlertRepository}，
 * 必须经本 domain Service 封装（6.45 契约）。</p>
 *
 * <h3>处置语义</h3>
 * <ul>
 *   <li>{@code acknowledge}：FIRING/ACKNOWLEDGED → ACKNOWLEDGED（幂等），广播 UPDATE</li>
 *   <li>{@code resolve}：任意非终态 → RESOLVED（幂等），广播 RESOLVED</li>
 *   <li>不存在的告警抛 {@link IllegalStateException}，由 Controller 映射为 40004</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-19
 */
@Slf4j
@Service
public class AlertQueryService {

    private final AlertRepository alertRepository;
    private final AlertWebSocketNotifier alertNotifier;
    /** 观察窗判定（FR-3.1 读路径派生）——配置单一事实源在 AlertService */
    private final AlertService alertService;

    public AlertQueryService(AlertRepository alertRepository, AlertWebSocketNotifier alertNotifier,
                             AlertService alertService) {
        this.alertRepository = alertRepository;
        this.alertNotifier = alertNotifier;
        this.alertService = alertService;
    }

    /**
     * 管道心跳状态（FR-1.6 看门狗的可视面，供告警页顶部展示）。
     *
     * <p>看门狗告警每次重复推送都刷新 {@code last_occurred_at}——它停跳就是
     * 「Prometheus → Alertmanager → webhook」管道断了。没有这条，告警列表
     * 长期空白会被误读成「天下太平」。</p>
     *
     * @param silenceMinutes 静默阈值，与 PipelineHeartbeatScheduler 同配置键
     * @return {lastSeenAt, silent, silenceMinutes}；从未收到看门狗时 lastSeenAt 为 null
     */
    public java.util.Map<String, Object> pipelineHeartbeat(int silenceMinutes) {
        java.util.Optional<java.time.LocalDateTime> lastSeen =
                alertRepository.findLatestOccurredAtByName(
                        com.devops.agent.domain.alert.ReservedAlertNames.PIPELINE_WATCHDOG);
        boolean silent = lastSeen.isEmpty()
                || lastSeen.get().isBefore(java.time.LocalDateTime.now().minusMinutes(silenceMinutes));
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("lastSeenAt", lastSeen.map(java.time.LocalDateTime::toString).orElse(null));
        body.put("silent", silent);
        body.put("silenceMinutes", silenceMinutes);
        return body;
    }

    /**
     * 分页查询告警列表（按状态 + 级别筛选）
     *
     * <p>P0-2b 起与 {@link #countAlerts} 拆成两个调用，由控制器组装
     * {@code AlertDto.AlertPage}——与 {@code TicketService.findTickets/countTickets}
     * 同款分工：service 只管数据，分页包装在控制器层完成，
     * 这样 record 才能放在 controller.dto（domain 不得 import controller）。</p>
     *
     * @param status 状态筛选（FIRING/ACKNOWLEDGED/RESOLVED，空=全部）
     * @param level  级别筛选（P0~P4，空=全部）
     * @param page   页码（从 1 开始，越界由 Controller 兜底）
     * @param size   每页大小（越界由 Controller 兜底）
     */
    /**
     * 分页查询告警列表（按状态 + 级别 + 来源系统筛选，可选「只看观察中」）
     *
     * <p>P0-2b 起与 {@link #countAlerts} 拆成两个调用，由控制器组装
     * {@code AlertDto.AlertPage}——与 {@code TicketService.findTickets/countTickets}
     * 同款分工：service 只管数据，分页包装在控制器层完成，
     * 这样 record 才能放在 controller.dto（domain 不得 import controller）。</p>
     *
     * @param observing true 时按观察窗配置过滤为「观察中」（活跃+未建单+观察级+未超窗）；
     *                  观察窗关闭或观察级为空时该条件不生效（空集语义=不过滤，不是查空）
     */
    public List<Alert> findAlerts(String status, String level, String system, boolean observing,
                                  int page, int size) {
        ObservationFilter obs = observationFilter(observing);
        List<Alert> alerts = alertRepository.findPage(status, level, system,
                obs.levels(), obs.windowMinutes(), page, size);
        // 读路径派生「观察中」标识：观察级别/窗口是可调配置，落库会随配置变更腐烂
        alerts.forEach(a -> a.setObserving(alertService.isObserving(a)));
        return alerts;
    }

    /**
     * 与 {@link #findAlerts} 同条件的总数统计。
     *
     * <p>必须<b>按同一筛选条件</b>统计，否则页码与实际数据矛盾
     * （用户看到「共 3 页」翻到第 2 页却是空的）。</p>
     */
    public long countAlerts(String status, String level, String system, boolean observing) {
        ObservationFilter obs = observationFilter(observing);
        return alertRepository.countByQuery(status, level, system, obs.levels(), obs.windowMinutes());
    }

    /** 全部来源系统去重列表（告警列表的 system 筛选下拉数据源） */
    public List<String> listSystems() {
        return alertRepository.findDistinctSystems();
    }

    /** 观察中筛选条件解析：未勾选或观察窗关闭 → 空条件（不过滤） */
    private record ObservationFilter(List<String> levels, int windowMinutes) {}

    private ObservationFilter observationFilter(boolean observing) {
        if (!observing) {
            return new ObservationFilter(null, 0);
        }
        AlertService.ObservationPolicy policy = alertService.observationPolicy();
        if (!policy.enabled() || policy.levels().isEmpty()) {
            return new ObservationFilter(null, 0);
        }
        return new ObservationFilter(policy.levels().stream().sorted().toList(), policy.windowMinutes());
    }

    /**
     * 人工确认告警
     * <p>广播 UPDATE 事件（非阻塞旁路——推送失败不影响处置主流程）。</p>
     *
     * @param id 告警 ID
     * @return 处置后的告警
     * @throws IllegalStateException 告警不存在或已恢复
     */
    public Alert acknowledge(Long id) {
        Alert alert = requireExisting(id);
        int rows = alertRepository.acknowledge(id);
        if (rows == 0) {
            // 竞态：查询时存在，但更新瞬间被标记 RESOLVED（如 Alertmanager resolved 推送晚到）
            throw new IllegalStateException("告警已恢复，无法确认");
        }
        // 更新内存态以对齐数据库，供广播与响应使用
        alert.setStatus("ACKNOWLEDGED");
        alertNotifier.broadcastUpdate(alert);
        log.info("✅ [AlertQuery] 人工确认告警 | id={} | alertName={}", id, alert.getAlertName());
        return alert;
    }

    /**
     * 标记告警已恢复
     * <p>广播 RESOLVED 事件（非阻塞旁路）。</p>
     *
     * @param id 告警 ID
     * @return 处置后的告警
     * @throws IllegalStateException 告警不存在或已恢复
     */
    public Alert resolve(Long id) {
        Alert alert = requireExisting(id);
        int rows = alertRepository.resolve(id);
        if (rows == 0) {
            throw new IllegalStateException("告警已恢复，无需重复操作");
        }
        alert.setStatus("RESOLVED");
        alertNotifier.broadcastResolved(alert);
        log.info("✅ [AlertQuery] 标记告警恢复 | id={} | alertName={}", id, alert.getAlertName());
        return alert;
    }

    /**
     * 查询告警，不存在则抛 {@link IllegalStateException}（映射 40004）
     */
    private Alert requireExisting(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("告警 ID 不能为空");
        }
        Optional<Alert> alert = alertRepository.findById(id);
        if (alert.isEmpty()) {
            throw new IllegalStateException("告警不存在");
        }
        return alert.get();
    }

    /**
     * 查询单个告警详情（供告警详情页 /alerts/:id）
     * <p>复用 {@link #requireExisting} 的错误语义：空 ID → 40001，不存在 → 40004。</p>
     *
     * @param id 告警 ID
     * @return 完整告警实体（含处置时间线字段 acknowledgedAt/resolvedAt/ticketId）
     */
    public Alert getAlert(Long id) {
        Alert alert = requireExisting(id);
        alert.setObserving(alertService.isObserving(alert));
        return alert;
    }

    /**
     * 同事件告警查询（建议3：告警详情页「同事件告警」联动）。
     * <p>以锚点告警的 system + service + 首次发生时间为口径，
     * 取 ±window 分钟窗内的兄弟告警（排除自身）。窗口上限由 Controller 夹紧。
     * 返回的每条也补「观察中」派生标识，与列表口径一致。</p>
     */
    public List<Alert> findRelated(Long id, int windowMinutes) {
        Alert alert = requireExisting(id);
        java.time.LocalDateTime anchor = alert.getFirstOccurredAt() != null
                ? alert.getFirstOccurredAt() : alert.getCreateTime();
        // 时间锚点都没了（理论不会）——无法圈窗，返回空不硬凑
        if (anchor == null) {
            return List.of();
        }
        List<Alert> related = alertRepository.findRelated(
                alert.getId(), alert.getSystem(), alert.getService(), anchor, windowMinutes);
        related.forEach(a -> a.setObserving(alertService.isObserving(a)));
        return related;
    }

    /**
     * 全局风暴模式状态快照（FR-2.5 可视面，告警列表横幅读它）。
     * <p>透传 AlertService 的风暴守卫状态——控制器只认本服务（六层分工）。</p>
     */
    public com.devops.agent.domain.alert.DTO.StormStatus stormStatus() {
        return alertService.stormStatus();
    }

    /**
     * 自愈观察窗统计（FR-3.1 的可视面，效能大盘读它）。
     *
     * <p>三个读数回答三个问题：现在有多少在观察（observingNow）、
     * 观察窗救了多少单（selfHealed30d）、有多少最终还是要人处理（escalated30d）。
     * 观察窗关闭时返回 enabled=false，前端据此隐藏区块而非显示一排 0
     * （null/0 与「未启用」的区分纪律，同 6.38 口径契约）。</p>
     */
    public com.devops.agent.domain.alert.DTO.ObservationStats observationStats() {
        AlertService.ObservationPolicy policy = alertService.observationPolicy();
        if (!policy.enabled() || policy.levels().isEmpty()) {
            return new com.devops.agent.domain.alert.DTO.ObservationStats(
                    false, policy.windowMinutes(), List.of(), 0, 0, 0);
        }
        List<String> levels = policy.levels().stream().sorted().toList();
        java.time.LocalDateTime since30d = java.time.LocalDateTime.now().minusDays(30);
        return new com.devops.agent.domain.alert.DTO.ObservationStats(
                true,
                policy.windowMinutes(),
                levels,
                alertRepository.countObservingNow(levels, policy.windowMinutes()),
                alertRepository.countSelfHealedSince(levels, since30d),
                alertRepository.countObservationEscalatedSince(levels, since30d));
    }
}
