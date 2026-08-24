package com.devops.agent.infrastructure.persistence.repo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计与 AI 调用日志的查询侧（只读）。
 *
 * <h3>为什么单独一个 Repository</h3>
 * {@link OperationAuditRepository} 只负责写入，且刻意做成「失败只告警不抛出」。
 * 查询侧的诉求完全相反——失败必须让调用方知道。两种语义放一个类里
 * 迟早会有人复用错方法，故分开。
 *
 * <h3>关于 SQL 拼接</h3>
 * 所有用户可控的值一律走 {@code ?} 占位参数，绝不拼进 SQL 字符串。
 * 唯一被拼接的是<b>排序字段名</b>，且它先经白名单校验——
 * 排序字段无法参数化（SQL 语法不允许），白名单是唯一安全做法。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
@Repository
public class AuditLogQueryRepository {

    /**
     * 单页最大条数。
     *
     * <p>不设上限会让 {@code ?size=999999} 一次拖垮数据库与前端渲染。
     * 200 足够覆盖「一屏看完」的诉求，更多数据应通过收窄筛选条件而非加大页长。</p>
     */
    public static final int MAX_PAGE_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;

    public AuditLogQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 操作审计 ====================

    /**
     * 分页查询操作审计。
     *
     * @param actorId    操作者 ID，null 表示不限
     * @param action     操作标识（前缀匹配，如 {@code ticket.} 可查全部工单操作）
     * @param targetType 目标类型
     * @param success    是否成功；null 表示不限
     * @param from       起始时间（含）
     * @param to         结束时间（含）
     * @param page       页码，从 1 开始
     * @param size       每页条数，上限 {@link #MAX_PAGE_SIZE}
     */
    public Map<String, Object> queryOperationAudit(
            String actorId, String action, String targetType, Boolean success,
            LocalDateTime from, LocalDateTime to, int page, int size) {

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (notBlank(actorId)) {
            where.append(" AND actor_id = ?");
            args.add(actorId.trim());
        }
        if (notBlank(action)) {
            // 前缀匹配：`ticket.` 能查到 ticket.create / ticket.approve 等全部子动作。
            // escapeLike 处理 % 与 _，否则用户输入的 `_` 会变成通配符
            where.append(" AND action LIKE ? ESCAPE '\\'");
            args.add(escapeLike(action.trim()) + "%");
        }
        if (notBlank(targetType)) {
            where.append(" AND target_type = ?");
            args.add(targetType.trim());
        }
        if (success != null) {
            where.append(" AND success = ?");
            args.add(success);
        }
        if (from != null) {
            where.append(" AND create_time >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND create_time <= ?");
            args.add(to);
        }

        int safeSize = clampSize(size);
        int safePage = Math.max(1, page);

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_audit" + where, Integer.class, args.toArray());
        int totalCount = total != null ? total : 0;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id, trace_id, actor_id, actor_name, action,
                       target_type, target_id, http_method, http_path,
                       status_code, success, biz_code, error_message,
                       client_ip, duration_ms, create_time
                FROM sys_operation_audit
                """ + where + " ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());

        return page(rows, totalCount, safePage, safeSize);
    }

    // ==================== AI 调用日志 ====================

    /**
     * 分页查询 AI 调用日志。
     *
     * @param modelName     模型名，null 不限
     * @param operationType 操作类型（CHAT / CACHE_HIT / ...）
     * @param cached        是否命中缓存；null 不限
     * @param minLatencyMs  最小耗时（用于筛慢查询）
     * @param from          起始时间
     * @param to            结束时间
     */
    public Map<String, Object> queryAgentCallLog(
            String modelName, String operationType, Boolean cached,
            Integer minLatencyMs, LocalDateTime from, LocalDateTime to,
            int page, int size) {

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (notBlank(modelName)) {
            where.append(" AND model_name = ?");
            args.add(modelName.trim());
        }
        if (notBlank(operationType)) {
            where.append(" AND operation_type = ?");
            args.add(operationType.trim());
        }
        if (cached != null) {
            where.append(" AND is_cached = ?");
            args.add(cached);
        }
        if (minLatencyMs != null && minLatencyMs > 0) {
            where.append(" AND latency_ms >= ?");
            args.add(minLatencyMs);
        }
        if (from != null) {
            where.append(" AND create_time >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND create_time <= ?");
            args.add(to);
        }

        int safeSize = clampSize(size);
        int safePage = Math.max(1, page);

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_agent_call_log" + where, Integer.class, args.toArray());
        int totalCount = total != null ? total : 0;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);

        /*
         * 列表刻意**不返回 user_query / agent_answer 全文**。
         *
         * 两个原因：① 一页 20 条问答全文可达数百 KB，列表根本用不上；
         * ② 问答内容可能含敏感信息，列表页权限较宽，按需下钻更稳妥。
         * 需要看全文时点 traceId 走详情接口。
         */
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id, trace_id, model_name, is_cached, latency_ms, cost_rmb,
                       operation_type, operator_id, affected_resources,
                       LEFT(COALESCE(user_query, ''), 120) AS query_preview,
                       create_time
                FROM sys_agent_call_log
                """ + where + " ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());

        return page(rows, totalCount, safePage, safeSize);
    }

    /** 单条 AI 调用详情（含问答全文与引用） */
    public Map<String, Object> findAgentCallByTraceId(String traceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id, trace_id, user_query, agent_answer, model_name,
                       is_cached, latency_ms, cost_rmb, citations,
                       operation_type, affected_resources, operator_id, create_time
                FROM sys_agent_call_log
                WHERE trace_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, traceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按 traceId 串联同一次请求的操作审计。
     *
     * <p>这是本模块相对通用日志查看器的核心价值：一次 AI 建单会同时留下
     * 「AI 调用记录」与「工单创建审计」，用 traceId 关联起来才能回答
     * 「这张工单是谁、通过什么方式、基于什么问答创建的」。</p>
     */
    public List<Map<String, Object>> findAuditByTraceId(String traceId) {
        return jdbcTemplate.queryForList(
                """
                SELECT id, trace_id, actor_id, actor_name, action,
                       target_type, target_id, http_method, http_path,
                       status_code, success, biz_code, error_message,
                       client_ip, duration_ms, create_time
                FROM sys_operation_audit
                WHERE trace_id = ?
                ORDER BY create_time ASC, id ASC
                """, traceId);
    }

    /** 筛选下拉的候选值（模型名 / 操作类型 / 操作者），从实际数据聚合而非硬编码 */
    public Map<String, Object> queryFilterOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("models", jdbcTemplate.queryForList(
                "SELECT DISTINCT model_name FROM sys_agent_call_log "
                        + "WHERE model_name IS NOT NULL ORDER BY model_name", String.class));
        options.put("operationTypes", jdbcTemplate.queryForList(
                "SELECT DISTINCT operation_type FROM sys_agent_call_log "
                        + "WHERE operation_type IS NOT NULL ORDER BY operation_type", String.class));
        options.put("actions", jdbcTemplate.queryForList(
                "SELECT DISTINCT action FROM sys_operation_audit ORDER BY action", String.class));
        options.put("targetTypes", jdbcTemplate.queryForList(
                "SELECT DISTINCT target_type FROM sys_operation_audit "
                        + "WHERE target_type IS NOT NULL ORDER BY target_type", String.class));
        return options;
    }

    /**
     * 当前筛选条件下的汇总统计。
     *
     * <p>对齐前端统计条的展示需求：总调用、缓存命中率、总成本、平均耗时。
     * <b>与列表用同一套 where 条件</b>——否则「列表显示 20 条、统计说 1 万次」
     * 这种自相矛盾会让用户不知道该信哪个。</p>
     */
    public Map<String, Object> queryAgentCallStats(
            String modelName, String operationType, Boolean cached,
            Integer minLatencyMs, LocalDateTime from, LocalDateTime to) {

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (notBlank(modelName)) { where.append(" AND model_name = ?"); args.add(modelName.trim()); }
        if (notBlank(operationType)) { where.append(" AND operation_type = ?"); args.add(operationType.trim()); }
        if (cached != null) { where.append(" AND is_cached = ?"); args.add(cached); }
        if (minLatencyMs != null && minLatencyMs > 0) { where.append(" AND latency_ms >= ?"); args.add(minLatencyMs); }
        if (from != null) { where.append(" AND create_time >= ?"); args.add(from); }
        if (to != null) { where.append(" AND create_time <= ?"); args.add(to); }

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT COUNT(*)                                              AS total_calls,
                       COALESCE(SUM(CASE WHEN is_cached THEN 1 ELSE 0 END),0) AS cache_hits,
                       COALESCE(SUM(cost_rmb), 0)                             AS total_cost,
                       COALESCE(AVG(latency_ms), 0)                           AS avg_latency
                FROM sys_agent_call_log
                """ + where, args.toArray());

        long totalCalls = ((Number) row.get("total_calls")).longValue();
        long cacheHits = ((Number) row.get("cache_hits")).longValue();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCalls", totalCalls);
        stats.put("cacheHits", cacheHits);
        // 分母为 0 时给 0 而非 NaN——前端 toFixed(NaN) 会显示 "NaN%"
        stats.put("cacheHitRate", totalCalls > 0
                ? Math.round(cacheHits * 10000.0 / totalCalls) / 100.0 : 0.0);
        stats.put("totalCost", Math.round(((Number) row.get("total_cost")).doubleValue() * 10000.0) / 10000.0);
        stats.put("avgLatencyMs", Math.round(((Number) row.get("avg_latency")).doubleValue()));
        return stats;
    }

    // ==================== 内部工具 ====================

    private Map<String, Object> page(List<Map<String, Object>> rows, int total, int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", size > 0 ? (int) Math.ceil((double) total / size) : 0);
        return result;
    }

    private int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 转义 LIKE 的通配符。
     *
     * <p>不转义的话，用户搜 {@code ticket_create} 里的下划线会被当成
     * 「任意单字符」通配，把 {@code ticketXcreate} 也匹配进来——
     * 结果看起来「差不多对」，实际是错的，这类问题最难被发现。</p>
     */
    private String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
