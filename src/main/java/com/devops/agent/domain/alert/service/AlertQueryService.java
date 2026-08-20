package com.devops.agent.domain.alert.service;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public AlertQueryService(AlertRepository alertRepository, AlertWebSocketNotifier alertNotifier) {
        this.alertRepository = alertRepository;
        this.alertNotifier = alertNotifier;
    }

    /**
     * 分页查询告警列表（按状态 + 级别筛选）
     *
     * @param status 状态筛选（FIRING/ACKNOWLEDGED/RESOLVED，空=全部）
     * @param level  级别筛选（P0~P4，空=全部）
     * @param page   页码（从 1 开始，越界由 Controller 兜底）
     * @param size   每页大小（越界由 Controller 兜底）
     * @return {@code {total, alerts}}
     */
    public Map<String, Object> listAlerts(String status, String level, int page, int size) {
        List<Alert> alerts = alertRepository.findPage(status, level, page, size);
        int total = alertRepository.countByQuery(status, level);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alerts", alerts);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        return result;
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
        return requireExisting(id);
    }
}
