package com.devops.agent.domain.biz.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * sys_diagnosis_session 仓储（S2-1，路线图 §6.1）。
 * <p>
 * 幂等设计：{@code INSERT .. ON CONFLICT (alert_id) DO NOTHING} —— 并发批量告警
 * 触发同一告警的去重诊断时，只有一条会话被真正创建
 * （PostgreSQL 级别的「存在即幂等」，不靠应用层 SELECT-then-INSERT 竞态窗口）。
 * </p>
 */
@Repository
public class DiagnosisSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public DiagnosisSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 创建诊断会话；若该告警已有活动会话（唯一约束冲突），幂等返回 null。
     *
     * @return 新会话 id；已存在返回 null（调用方据此决定不重复派发）
     */
    public Long createIfAbsent(String traceId, Long alertId, String service) {
        String sql = """
                INSERT INTO sys_diagnosis_session (trace_id, alert_id, service, status)
                VALUES (?, ?, ?, 'RUNNING')
                ON CONFLICT (alert_id) WHERE status = 'RUNNING' DO NOTHING
                RETURNING id
                """;
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, traceId, alertId, service);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 直接落一条 REJECTED 会话（诊断线满负荷的拒绝降级，仍留痕迹）。
     * 不抢 alert_id 唯一锁——被拒的会话对去重语义无意义。
     */
    public long saveRejected(String traceId, Long alertId, String ticketId, String service,
                             String reason) {
        String sql = """
                INSERT INTO sys_diagnosis_session (trace_id, alert_id, ticket_id, service, status, error_message)
                VALUES (?, ?, ?, ?, 'REJECTED', ?)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, Long.class,
                traceId, alertId, ticketId, service, reason);
        return id == null ? -1 : id;
    }

    /**
     * 完成态更新：状态 + 充分性 + 结论、回填工单 id。
     * <p>前置状态守卫：仅 {@code RUNNING -> COMPLETED}；并发重跑只落一次
     * （0 行 = 「已有别人收尾」），ERROR 终态也不会被覆盖。</p>
     */
    public int complete(Long id, String ticketId, String sufficiency, String summary) {
        String sql = """
                UPDATE sys_diagnosis_session
                SET status = 'COMPLETED', ticket_id = ?, sufficiency = ?,
                    summary = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """;
        return jdbcTemplate.update(sql, ticketId, sufficiency, summary, id);
    }

    /**
     * 示意：诊断推理阶段因异常要放弃会话。供调用方在失败分支补齐终态。
     * <p>前置状态守卫：仅 {@code RUNNING -> ERROR}——已 COMPLETED 的结论
     * 优先于迟到的失败信号（尾部异常不抹掉已落库的成功）。</p>
     */
    public int fail(Long id, String errorMessage) {
        String sql = """
                UPDATE sys_diagnosis_session
                SET status = 'ERROR', error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """;
        return jdbcTemplate.update(sql, errorMessage, id);
    }

    /** 按 trace_id 取会话（诊断详情 API：证据链回放的入口）。 */
    public Map<String, Object> findByTraceId(String traceId) {
        String sql = "SELECT id, trace_id, alert_id, ticket_id, service, status, sufficiency, summary, "
                + "created_at, updated_at FROM sys_diagnosis_session WHERE trace_id = ? "
                + "ORDER BY id DESC LIMIT 1";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, traceId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** 供诊断详情 API 汇报：按 alert_id 取活动会话。 */
    public Map<String, Object> findByAlertId(Long alertId) {
        String sql = "SELECT id, trace_id, alert_id, ticket_id, service, status, sufficiency, summary, created_at, updated_at FROM sys_diagnosis_session WHERE alert_id = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, alertId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
}
