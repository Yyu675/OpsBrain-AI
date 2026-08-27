package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运维工单数据访问层
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Repository
public class DevOpsTicketRepository {

    private static final Logger log = LoggerFactory.getLogger(DevOpsTicketRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public DevOpsTicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存工单
     */
    public int save(DevOpsTicket ticket) {
        log.debug("📝 [Repository] 准备执行工单插入 SQL - ticketId: {}", ticket.getId());

        // 显式写入 version：若依赖数据库 DEFAULT，返回给前端的实体该字段为 null，
        // 前端创建后立即编辑会因缺版本号而丧失并发保护
        String sql = """
            INSERT INTO sys_devops_ticket (id, title, priority, module, description, stack_trace, status, source_trace_id, assignee, creator, category, sla, response_deadline, resolve_deadline, first_response_at, first_responder, response_breached, escalated_at, escalate_reason, handling_stage, mitigated_at, root_cause, root_cause_category, root_cause_by, root_cause_at, verified_at, verifier, verify_method, verify_conclusion, verify_skipped, verify_skip_reason, version, create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        log.debug("📋 [Repository] SQL 参数详情: id={}, title={}, priority={}, module={}, status={}, sourceTraceId={}, assignee={}, creator={}, category={}, sla={}",
                ticket.getId(), ticket.getTitle(), ticket.getPriority(), ticket.getModule(),
                ticket.getStatus(), ticket.getSourceTraceId(), ticket.getAssignee(),
                ticket.getCreator(), ticket.getCategory(), ticket.getSla());

        try {
            int affectedRows = jdbcTemplate.update(sql,
                    ticket.getId(),
                    ticket.getTitle(),
                    ticket.getPriority(),
                    ticket.getModule(),
                    ticket.getDescription(),
                    ticket.getStackTrace(),
                    ticket.getStatus(),
                    ticket.getSourceTraceId(),
                    ticket.getAssignee(),
                    ticket.getCreator(),
                    ticket.getCategory(),
                    ticket.getSla(),
                    ticket.getResponseDeadline(),
                    ticket.getResolveDeadline(),
                    ticket.getFirstResponseAt(),
                    ticket.getFirstResponder(),
                    ticket.getResponseBreached() != null ? ticket.getResponseBreached() : false,
                    ticket.getEscalatedAt(),
                    ticket.getEscalateReason(),
                    ticket.getHandlingStage(),
                    ticket.getMitigatedAt(),
                    ticket.getRootCause(),
                    ticket.getRootCauseCategory(),
                    ticket.getRootCauseBy(),
                    ticket.getRootCauseAt(),
                    ticket.getVerifiedAt(),
                    ticket.getVerifier(),
                    ticket.getVerifyMethod(),
                    ticket.getVerifyConclusion(),
                    ticket.getVerifySkipped() != null ? ticket.getVerifySkipped() : false,
                    ticket.getVerifySkipReason(),
                    ticket.getVersion() != null ? ticket.getVersion() : 0,
                    ticket.getCreateTime(),
                    ticket.getUpdateTime()
            );

            log.info("✅ [Repository] 工单插入成功 - ticketId: {}, affectedRows: {}", ticket.getId(), affectedRows);
            return affectedRows;

        } catch (Exception e) {
            log.error("❌ [Repository] 工单插入失败 - ticketId: {}, error: {}", ticket.getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 查询工单总数
     */
    public long countAll() {
        String sql = "SELECT COUNT(*) FROM sys_devops_ticket";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * 分页查询工单列表（兼容旧签名）
     */
    public List<DevOpsTicket> findPage(int page, int size, String priority, String status) {
        return findPage(page, size, new TicketQuery(
                null, priority, status, null, null, null, null, null, null));
    }

    /**
     * 分页查询工单列表（全条件）
     * <p>
     * 筛选下沉到 SQL 的原因：此前前端只拉 100 条再本地过滤，
     * 第 101 条起的工单对搜索<b>静默不可见</b>。
     * </p>
     * <p>
     * 排序同样必须下沉：前端 el-table 本地排序只作用于当前页，
     * 「按优先级排序」会漏掉页外更高优先级的工单——与筛选同一类静默错误。
     * </p>
     */
    public List<DevOpsTicket> findPage(int page, int size, TicketQuery q) {
        WhereClause where = buildWhere(q);

        String sql = "SELECT * FROM sys_devops_ticket " + where.sql()
                + " ORDER BY " + buildOrderBy(q) + " LIMIT ? OFFSET ?";

        List<Object> params = new ArrayList<>(where.params());
        params.add(size);
        params.add((long) (page - 1) * size);

        return jdbcTemplate.query(sql, new TicketRowMapper(), params.toArray());
    }

    /**
     * 排序字段白名单
     * <p>
     * 排序列名<b>不能</b>用参数占位符（SQL 语法不允许），只能拼接字符串。
     * 因此必须用白名单把用户输入映射为固定列名——直接拼接前端传值即为
     * SQL 注入漏洞（如 {@code sortBy=id;DROP TABLE...}）。
     * </p>
     * <p>
     * 键为前端字段名（camelCase，对齐 el-table 的 prop），值为数据库列名。
     * </p>
     */
    private static final Map<String, String> SORTABLE_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("title", "title"),
            Map.entry("status", "status"),
            Map.entry("assignee", "assignee"),
            // 前端「服务」列展示的是 module 枚举的可读标签，排序按 module 列分组
            Map.entry("service", "module"),
            Map.entry("module", "module"),
            Map.entry("category", "category"),
            Map.entry("createdAt", "create_time"),
            Map.entry("createTime", "create_time"),
            Map.entry("updatedAt", "update_time"),
            Map.entry("updateTime", "update_time")
    );

    /**
     * 构建 ORDER BY 子句
     * <p>
     * 优先级排序特殊处理：{@code priority} 存的是 HIGH/MEDIUM/LOW 字符串，
     * 按字典序排会得到 HIGH → LOW → MEDIUM（"L" < "M"），与业务语义相反。
     * 故用 CASE 映射为数值权重后再排。
     * </p>
     * <p>
     * 二级排序固定为 create_time DESC：主排序字段有大量相同值时（如状态只有 5 种），
     * 若无稳定的二级排序，同一条记录可能在不同页重复出现或消失。
     * </p>
     */
    private String buildOrderBy(TicketQuery q) {
        String sortBy = q == null ? null : q.sortBy();
        boolean asc = q != null && q.sortAsc();

        if (sortBy == null || sortBy.isBlank()) {
            return "create_time DESC";
        }

        String dir = asc ? "ASC" : "DESC";

        // 优先级按业务权重排，不按字典序
        if ("priority".equals(sortBy)) {
            return "CASE priority "
                    + "WHEN 'P0' THEN 1 WHEN 'P1' THEN 2 WHEN 'P2' THEN 3 WHEN 'P3' THEN 4 "
                    // 旧三档兜底：若有未迁移残留数据，按等价档位归入而非落到 ELSE 末尾，
                    // 否则排序会把旧数据全甩到最后，看起来像「优先级排序失效」
                    + "WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 "
                    + "ELSE 5 END "
                    + dir + ", create_time DESC";
        }

        String column = SORTABLE_COLUMNS.get(sortBy);
        if (column == null) {
            // 未在白名单内：降级为默认排序而非报错。
            // 前端新增可排序列但后端未同步时，列表仍可用（只是排序不生效），
            // 比整个列表 500 更可接受
            log.warn("⚠️ [Repository] 不支持的排序字段，已降级为默认排序: sortBy={}", sortBy);
            return "create_time DESC";
        }

        // create_time 本身作为主排序时不重复追加二级排序
        if ("create_time".equals(column)) {
            return column + " " + dir;
        }
        return column + " " + dir + ", create_time DESC";
    }

    /**
     * 按条件统计总数
     * <p>
     * <b>必须与 {@link #findPage} 共用 WHERE 构建逻辑</b>。
     * 二者条件不一致会导致总数与实际行数矛盾，页码随之错误。
     * </p>
     */
    public long countByQuery(TicketQuery q) {
        WhereClause where = buildWhere(q);
        String sql = "SELECT COUNT(*) FROM sys_devops_ticket " + where.sql();
        Long n = jdbcTemplate.queryForObject(sql, Long.class, where.params().toArray());
        return n != null ? n : 0L;
    }

    /**
     * 构建 WHERE 子句
     * <p>
     * 全部使用参数占位符，不做字符串拼接值——防 SQL 注入。
     * </p>
     */
    private WhereClause buildWhere(TicketQuery q) {
        StringBuilder sql = new StringBuilder("WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (q == null) {
            return new WhereClause(sql.toString(), params);
        }

        // 关键词：工单号 / 标题 / 描述，不区分大小写
        if (notBlank(q.keyword())) {
            sql.append(" AND (LOWER(id) LIKE ? OR LOWER(title) LIKE ? OR LOWER(description) LIKE ?)");
            // 转义 LIKE 元字符，避免用户输入的 % 与 _ 被当通配符
            String kw = "%" + escapeLike(q.keyword().trim().toLowerCase()) + "%";
            params.add(kw);
            params.add(kw);
            params.add(kw);
        }

        if (notBlank(q.priority())) {
            sql.append(" AND priority = ?");
            params.add(q.priority().trim().toUpperCase());
        }
        if (notBlank(q.status())) {
            sql.append(" AND status = ?");
            params.add(q.status().trim().toUpperCase());
        }
        if (notBlank(q.module())) {
            sql.append(" AND module = ?");
            params.add(q.module().trim().toUpperCase());
        }
        if (notBlank(q.category())) {
            sql.append(" AND category = ?");
            params.add(q.category().trim());
        }
        if (notBlank(q.assignee())) {
            sql.append(" AND assignee = ?");
            params.add(q.assignee().trim());
        }

        // 创建时间区间。
        // 上界用 < 次日 0 点而非 <= 当天：后者会漏掉当天 00:00:00 之后的记录
        if (notBlank(q.createdFrom())) {
            sql.append(" AND create_time >= ?::date");
            params.add(q.createdFrom().trim());
        }
        if (notBlank(q.createdTo())) {
            sql.append(" AND create_time < (?::date + INTERVAL '1 day')");
            params.add(q.createdTo().trim());
        }

        // 标签 AND 语义：工单须同时含全部指定标签。
        // 用 EXISTS 子查询而非 JOIN，避免多标签时产生笛卡尔积重复行
        if (q.hasTagFilter()) {
            List<String> tags = q.tags().stream()
                    .filter(this::notBlank).map(String::trim).distinct().toList();
            if (!tags.isEmpty()) {
                String placeholders = String.join(",", tags.stream().map(x -> "?").toList());
                sql.append(" AND (SELECT COUNT(DISTINCT tag) FROM sys_ticket_tag t")
                   .append(" WHERE t.ticket_id = sys_devops_ticket.id AND t.tag IN (")
                   .append(placeholders).append(")) = ?");
                params.addAll(tags);
                params.add(tags.size());
            }
        }

        return new WhereClause(sql.toString(), params);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 转义 LIKE 元字符
     * <p>
     * 用户搜索「50%」时，未转义的 % 会变成通配符匹配任意内容。
     * PostgreSQL 默认转义符是反斜杠。
     * </p>
     */
    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * WHERE 子句与其参数
     */
    private record WhereClause(String sql, List<Object> params) {
    }

    /**
     * 根据工单 ID 查询工单
     */
    public DevOpsTicket findById(String id) {
        String sql = "SELECT * FROM sys_devops_ticket WHERE id = ? LIMIT 1";
        List<DevOpsTicket> tickets = jdbcTemplate.query(sql, new TicketRowMapper(), id);
        return tickets.isEmpty() ? null : tickets.get(0);
    }

    /**
     * 根据追踪 ID 查询工单
     */
    public DevOpsTicket findByTraceId(String traceId) {
        String sql = "SELECT * FROM sys_devops_ticket WHERE source_trace_id = ? LIMIT 1";
        List<DevOpsTicket> tickets = jdbcTemplate.query(sql, new TicketRowMapper(), traceId);
        return tickets.isEmpty() ? null : tickets.get(0);
    }

    /**
     * 全量更新工单可编辑字段（P1-4 乐观锁）
     * <p>
     * 不更新 {@code id}/{@code creator}/{@code create_time}/{@code source_trace_id}——
     * 这些是创建时确定的不可变字段。
     * </p>
     * <p>
     * <b>并发控制</b>：{@code ticket.version} 非空时以
     * {@code WHERE id=? AND version=?} 为条件并自增版本号；
     * 返回 0 表示版本冲突（已被他人修改）。
     * version 为空时退化为无锁覆盖，兼容不传版本的旧调用。
     * </p>
     *
     * @return 受影响行数（0 表示工单不存在<b>或版本冲突</b>）
     */
    public int update(DevOpsTicket ticket) {
        boolean withLock = ticket.getVersion() != null;

        // B0：deadline 一并更新——优先级变更时 Service 会重算时限，
        // 若此处不写回，SLA 计时仍按旧优先级，进度与超时判定全部失真。
        // B2：handling_stage / mitigated_at 也一并更新
        // B3：root_cause / verify 系列字段也一并更新
        String sql = withLock ? """
            UPDATE sys_devops_ticket
               SET title = ?, priority = ?, module = ?, description = ?,
                   stack_trace = ?, status = ?, assignee = ?, category = ?, sla = ?,
                   response_deadline = ?, resolve_deadline = ?,
                   handling_stage = ?, mitigated_at = ?,
                   root_cause = ?, root_cause_category = ?, root_cause_by = ?, root_cause_at = ?,
                   verified_at = ?, verifier = ?, verify_method = ?, verify_conclusion = ?,
                   verify_skipped = ?, verify_skip_reason = ?,
                   version = version + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """ : """
            UPDATE sys_devops_ticket
               SET title = ?, priority = ?, module = ?, description = ?,
                   stack_trace = ?, status = ?, assignee = ?, category = ?, sla = ?,
                   response_deadline = ?, resolve_deadline = ?,
                   handling_stage = ?, mitigated_at = ?,
                   root_cause = ?, root_cause_category = ?, root_cause_by = ?, root_cause_at = ?,
                   verified_at = ?, verifier = ?, verify_method = ?, verify_conclusion = ?,
                   verify_skipped = ?, verify_skip_reason = ?,
                   version = version + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;

        try {
            int rows;
            if (withLock) {
                rows = jdbcTemplate.update(sql,
                        ticket.getTitle(), ticket.getPriority(), ticket.getModule(),
                        ticket.getDescription(), ticket.getStackTrace(), ticket.getStatus(),
                        ticket.getAssignee(), ticket.getCategory(), ticket.getSla(),
                        ticket.getResponseDeadline(), ticket.getResolveDeadline(),
                        ticket.getHandlingStage(), ticket.getMitigatedAt(),
                        ticket.getRootCause(), ticket.getRootCauseCategory(),
                        ticket.getRootCauseBy(), ticket.getRootCauseAt(),
                        ticket.getVerifiedAt(), ticket.getVerifier(),
                        ticket.getVerifyMethod(), ticket.getVerifyConclusion(),
                        ticket.getVerifySkipped() != null ? ticket.getVerifySkipped() : false,
                        ticket.getVerifySkipReason(),
                        ticket.getId(), ticket.getVersion());
            } else {
                rows = jdbcTemplate.update(sql,
                        ticket.getTitle(), ticket.getPriority(), ticket.getModule(),
                        ticket.getDescription(), ticket.getStackTrace(), ticket.getStatus(),
                        ticket.getAssignee(), ticket.getCategory(), ticket.getSla(),
                        ticket.getResponseDeadline(), ticket.getResolveDeadline(),
                        ticket.getHandlingStage(), ticket.getMitigatedAt(),
                        ticket.getRootCause(), ticket.getRootCauseCategory(),
                        ticket.getRootCauseBy(), ticket.getRootCauseAt(),
                        ticket.getVerifiedAt(), ticket.getVerifier(),
                        ticket.getVerifyMethod(), ticket.getVerifyConclusion(),
                        ticket.getVerifySkipped() != null ? ticket.getVerifySkipped() : false,
                        ticket.getVerifySkipReason(),
                        ticket.getId());
            }

            if (rows == 0 && withLock) {
                log.warn("⚠️ [Repository] 工单更新受影响行数为 0（版本冲突或不存在）- ticketId: {}, expectedVersion: {}",
                        ticket.getId(), ticket.getVersion());
            } else {
                log.info("✅ [Repository] 工单更新完成 - ticketId: {}, affectedRows: {}, lock: {}",
                        ticket.getId(), rows, withLock ? "CAS" : "none");
            }
            return rows;
        } catch (Exception e) {
            log.error("❌ [Repository] 工单更新失败 - ticketId: {}, error: {}", ticket.getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 更新工单状态（版本号自增）
     * <p>
     * 状态变更是单字段原子操作，天然无字段级冲突，故不做 CAS 校验；
     * 但仍自增版本号，使并发的全量更新能感知到状态已变。
     * </p>
     *
     * @return 受影响行数（0 表示工单不存在或状态未变）
     */
    public int updateStatus(String ticketId, String status) {
        String sql = """
            UPDATE sys_devops_ticket
               SET status = ?, version = version + 1, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND status <> ?
            """;
        return jdbcTemplate.update(sql, status, ticketId, status);
    }

    /**
     * 转派工单（版本号自增）
     * <p>理由同 {@link #updateStatus}：单字段操作，自增版本使全量更新可感知。</p>
     *
     * @return 受影响行数
     */
    public int updateAssignee(String ticketId, String assignee) {
        String sql = """
            UPDATE sys_devops_ticket
               SET assignee = ?, version = version + 1, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;
        return jdbcTemplate.update(sql, assignee, ticketId);
    }

    /**
     * 物理删除工单
     * <p>
     * ⚠️ 不可逆。业务上优先用 {@link #voidTicket} 作废以保留审计痕迹，
     * 仅在明确要清理数据时使用本方法。
     * </p>
     *
     * @return 受影响行数（0 表示工单不存在）
     */
    public int deleteById(String ticketId) {
        String sql = "DELETE FROM sys_devops_ticket WHERE id = ?";
        int rows = jdbcTemplate.update(sql, ticketId);
        log.warn("🗑️ [Repository] 工单已物理删除 - ticketId: {}, affectedRows: {}", ticketId, rows);
        return rows;
    }

    /**
     * 按状态统计工单数（供看板 KPI）
     *
     * @return [status, count] 列表
     */
    public List<Object[]> countGroupByStatus() {
        String sql = "SELECT status, COUNT(*) FROM sys_devops_ticket GROUP BY status";
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new Object[]{rs.getString(1), rs.getLong(2)});
    }

    /**
     * 按优先级统计工单数（供看板 KPI）
     * <p>
     * 此前无此维度，前端「紧急工单待处理」只能从当前页 tickets 数组本地过滤。
     * 分页下沉后（6.15）该数组仅含当前页，且用户未打开列表页时为空——
     * 会把「有紧急工单」误报为<b>「暂无紧急待处理工单」</b>，是虚假事实陈述。
     * </p>
     *
     * @return [priority, count] 列表
     */
    public List<Object[]> countGroupByPriority() {
        String sql = "SELECT priority, COUNT(*) FROM sys_devops_ticket GROUP BY priority";
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new Object[]{rs.getString(1), rs.getLong(2)});
    }

    /**
     * 统计「未完结的高优先级工单」数（P0 + P1）
     * <p>
     * 前端「高优先级待处理」提醒的唯一可信来源。
     * 未完结 = 非 RESOLVED / CLOSED / VOID——已解决或作废的工单不需要再催办。
     * </p>
     * <p>
     * <b>B0 口径说明</b>：优先级改四档后，旧 HIGH 统一迁移为 P1（旧 HIGH 混装了
     * 「紧急」与「高」两种语义，无法区分）。若此处只统计 P0，迁移后该提醒会恒为 0，
     * 使一个本可用的告警能力静默失效。故统计 P0+P1 两档，并把前端标签由
     * 「紧急」改为「高优先级」——保持 6.41 契约「KPI 口径必须与标签语义一致」。
     * 需要单独看 P0 时用 stats 的 byPriority 分布。
     * </p>
     */
    public long countUrgentPending() {
        String sql = """
            SELECT COUNT(*) FROM sys_devops_ticket
             WHERE priority IN (?, ?)
               AND status NOT IN (?, ?, ?)
            """;
        Long n = jdbcTemplate.queryForObject(sql, Long.class,
                TicketEnums.Priority.P0,
                TicketEnums.Priority.P1,
                TicketEnums.Status.RESOLVED,
                TicketEnums.Status.CLOSED,
                TicketEnums.Status.VOID);
        return n != null ? n : 0L;
    }

    /**
     * 统计今日新建工单数（供看板 KPI「今日新增」）
     */
    public long countCreatedToday() {
        String sql = """
            SELECT COUNT(*) FROM sys_devops_ticket
             WHERE create_time >= CURRENT_DATE
               AND create_time <  CURRENT_DATE + INTERVAL '1 day'
            """;
        Long n = jdbcTemplate.queryForObject(sql, Long.class);
        return n != null ? n : 0L;
    }

    /**
     * 按天统计建单数（供趋势分析）
     * <p>
     * 只统计 {@code days} 天窗口内（含今天）的建单，按天聚合。
     * 缺失的日期由 Service 层补零——固定周期趋势必须呈现连续 N 点（6.41 契约），
     * 不得只渲染有数据的天。
     * </p>
     *
     * @param days 统计窗口天数（>= 1）
     * @return [日期 java.sql.Date, 数量 Long] 列表，按日期升序
     */
    public List<Object[]> countCreatedByDay(int days) {
        return countCreatedByDay(days, null);
    }

    /**
     * 按天统计建单数，可按 module 下钻
     * <p>
     * {@code module} 为值而非列名，故可安全用占位符（列名无法参数化，
     * 那是 6.37 排序白名单存在的原因；此处不涉及该风险）。
     * </p>
     *
     * @param days   统计窗口天数（>= 1）
     * @param module 服务模块，null/空=全局口径
     */
    public List<Object[]> countCreatedByDay(int days, String module) {
        boolean byModule = module != null && !module.isBlank();
        String sql = """
            SELECT DATE(create_time) AS day, COUNT(*) AS cnt
              FROM sys_devops_ticket
             WHERE create_time >= (CURRENT_DATE - ?)
            """
            + (byModule ? " AND module = ?" : "")
            + """
             GROUP BY DATE(create_time)
             ORDER BY day
            """;
        return byModule
                ? jdbcTemplate.query(sql, (rs, rowNum) ->
                        new Object[]{rs.getDate("day"), rs.getLong("cnt")}, days - 1, module)
                : jdbcTemplate.query(sql, (rs, rowNum) ->
                        new Object[]{rs.getDate("day"), rs.getLong("cnt")}, days - 1);
    }

    /**
     * 按天统计「验证通过」数（供趋势分析，MTTR 口径）
     * <p>
     * 以 {@code verified_at} 为「解决」时刻，与闭环度量 MTTR（B3/B5）口径一致。
     * 跳过验证（verify_skipped=true）的工单其 verified_at 为空，天然不计入——
     * 遵循 6.41「KPI 口径必须与标签语义一致」，不把「点一下已解决」混入解决趋势。
     * </p>
     *
     * @param days 统计窗口天数（>= 1）
     * @return [日期 java.sql.Date, 数量 Long] 列表，按日期升序
     */
    public List<Object[]> countResolvedByDay(int days) {
        return countResolvedByDay(days, null);
    }

    /**
     * 按天统计「验证通过」数，可按 module 下钻
     *
     * @param days   统计窗口天数（>= 1）
     * @param module 服务模块，null/空=全局口径
     */
    public List<Object[]> countResolvedByDay(int days, String module) {
        boolean byModule = module != null && !module.isBlank();
        String sql = """
            SELECT DATE(verified_at) AS day, COUNT(*) AS cnt
              FROM sys_devops_ticket
             WHERE verified_at >= (CURRENT_DATE - ?)
            """
            + (byModule ? " AND module = ?" : "")
            + """
             GROUP BY DATE(verified_at)
             ORDER BY day
            """;
        return byModule
                ? jdbcTemplate.query(sql, (rs, rowNum) ->
                        new Object[]{rs.getDate("day"), rs.getLong("cnt")}, days - 1, module)
                : jdbcTemplate.query(sql, (rs, rowNum) ->
                        new Object[]{rs.getDate("day"), rs.getLong("cnt")}, days - 1);
    }

    /**
     * 仅刷新更新时间
     * <p>
     * 用于回复等不改工单字段但需反映活跃度的场景，使列表按
     * update_time 排序时能体现最新互动。
     * </p>
     * <p>不自增 version：未改业务字段，不应触发他人的并发冲突。</p>
     *
     * @return 受影响行数
     */
    public int touchUpdateTime(String ticketId) {
        String sql = "UPDATE sys_devops_ticket SET update_time = CURRENT_TIMESTAMP WHERE id = ?";
        try {
            return jdbcTemplate.update(sql, ticketId);
        } catch (Exception e) {
            log.warn("⚠️ [Repository] 刷新更新时间失败 | ticketId={} | {}", ticketId, e.getMessage());
            return 0;
        }
    }

    /**
     * 作废工单（Saga 补偿）
     * <p>
     * 状态置为 VOID 并在描述追加补偿原因，不做物理删除以保留审计痕迹。
     * 条件带 {@code status <> 'VOID'} 保证幂等。
     * </p>
     *
     * @param ticketId 工单号
     * @param reason   作废原因
     * @return 受影响行数（0 表示不存在或已作废）
     */
    public int voidTicket(String ticketId, String reason) {
        String sql = """
            UPDATE sys_devops_ticket
               SET status = 'VOID',
                   description = COALESCE(description, '')
                                 || E'\\n\\n[Saga 补偿作废] ' || ?,
                   version = version + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = ?
               AND status <> 'VOID'
            """;
        return jdbcTemplate.update(sql, reason != null ? reason : "未说明原因", ticketId);
    }

    /**
     * 回填工单的来源追踪 ID
     * <p>
     * 用途：LangChain4j 1.1.0 流式模式下工具在模型 HTTP 回调线程执行，
     * {@code TraceContext} 的 ThreadLocal 无法跨线程传递，导致建单时
     * {@code source_trace_id} 为空。故在 {@code onToolExecuted} 回调
     * （已持有 traceId）中回填，保证会话与工单可关联追溯。
     * </p>
     * <p>仅在原值为空时回填，避免覆盖已有正确值。</p>
     *
     * @param ticketId 工单号
     * @param traceId  会话追踪 ID
     * @return 受影响行数（0 表示工单不存在或已有 traceId）
     */
    public int backfillSourceTraceId(String ticketId, String traceId) {
        String sql = """
            UPDATE sys_devops_ticket
               SET source_trace_id = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
               AND (source_trace_id IS NULL OR source_trace_id = '')
            """;
        return jdbcTemplate.update(sql, traceId, ticketId);
    }

    // ==================== B1 首响 / 升级 ====================

    /**
     * 记录首响（仅当尚未首响时写入）
     * <p>
     * <b>SQL 层加 {@code first_response_at IS NULL} 条件</b>是关键：首响是
     * 「第一次」的语义，必须天然幂等。多个触发点（状态变更 / 首条回复 /
     * 显式确认）可能并发到达，若不加条件，后到的会覆盖先到的时刻，
     * 把首响时间越推越晚，MTTA 被系统性拉长。
     * </p>
     * <p>不自增 version：首响是旁路事实记录，不参与业务并发编辑冲突。</p>
     *
     * @return 1=本次写入成功（即本次就是首响）；0=已有首响，本次忽略
     */
    public int markFirstResponse(String ticketId, String responder, LocalDateTime at) {
        String sql = """
            UPDATE sys_devops_ticket
               SET first_response_at = ?, first_responder = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND first_response_at IS NULL
            """;
        int rows = jdbcTemplate.update(sql, at, responder, ticketId);
        if (rows > 0) {
            log.info("✅ [Repository] 首响已记录 | ticketId={} | responder={} | at={}", ticketId, responder, at);
        }
        return rows;
    }

    /**
     * 标记首响超时（仅对未首响且未标记过的工单）
     * <p>幂等：{@code response_breached = FALSE} 条件保证重复扫描不重复告警。</p>
     *
     * @return 受影响行数
     */
    public int markResponseBreached(String ticketId) {
        String sql = """
            UPDATE sys_devops_ticket
               SET response_breached = TRUE, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND response_breached = FALSE AND first_response_at IS NULL
            """;
        return jdbcTemplate.update(sql, ticketId);
    }

    /**
     * 记录升级
     */
    public int markEscalated(String ticketId, String reason, LocalDateTime at) {
        String sql = """
            UPDATE sys_devops_ticket
               SET escalated_at = ?, escalate_reason = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;
        return jdbcTemplate.update(sql, at, reason, ticketId);
    }

    /**
     * 查询首响已超时但尚未标记的工单（供定时扫描）
     * <p>
     * 排除终态工单——已解决/关闭/作废的工单不需要再催首响。
     * </p>
     */
    public List<DevOpsTicket> findResponseBreachCandidates(int limit) {
        String sql = """
            SELECT * FROM sys_devops_ticket
             WHERE first_response_at IS NULL
               AND response_breached = FALSE
               AND response_deadline IS NOT NULL
               AND response_deadline < CURRENT_TIMESTAMP
               AND status NOT IN (?, ?, ?)
             ORDER BY response_deadline ASC
             LIMIT ?
            """;
        return jdbcTemplate.query(sql, new TicketRowMapper(),
                TicketEnums.Status.RESOLVED, TicketEnums.Status.CLOSED, TicketEnums.Status.VOID,
                limit);
    }

    /**
     * 查询 SLA 风险清单
     * <p>
     * 两类：① 首响即将超时或已超时（未首响且 response_deadline 临近/已过）；
     * ② 解决即将超时或已超时（未终结且 resolve_deadline 临近/已过）。
     * </p>
     *
     * @param withinMinutes 「即将超时」的前瞻窗口（分钟）；传 0 表示只看已超时
     * @param limit 上限，防止一次拉爆
     */
    public List<DevOpsTicket> findSlaAtRisk(int withinMinutes, int limit) {
        // 用 INTERVAL 拼接会引入注入风险，故用 make_interval 传参数
        String sql = """
            SELECT * FROM sys_devops_ticket
             WHERE status NOT IN (?, ?, ?)
               AND (
                     (first_response_at IS NULL
                      AND response_deadline IS NOT NULL
                      AND response_deadline < CURRENT_TIMESTAMP + make_interval(mins => ?))
                  OR (resolve_deadline IS NOT NULL
                      AND resolve_deadline < CURRENT_TIMESTAMP + make_interval(mins => ?))
                   )
             ORDER BY COALESCE(response_deadline, resolve_deadline) ASC
             LIMIT ?
            """;
        return jdbcTemplate.query(sql, new TicketRowMapper(),
                TicketEnums.Status.RESOLVED, TicketEnums.Status.CLOSED, TicketEnums.Status.VOID,
                withinMinutes, withinMinutes, limit);
    }

    /**
     * 统计首响相关指标（供看板 MTTA）
     * <p>
     * 只统计**有首响记录**的工单求均值——历史数据 first_response_at 为 NULL
     * （v17 迁移刻意不回填，见其注释），计入会把 MTTA 严重拉偏。
     * </p>
     *
     * @return {@code {responded, notResponded, breached, avgFirstResponseMinutes}}
     */
    public java.util.Map<String, Object> countFirstResponseStats() {
        String sql = """
            SELECT
                COUNT(*) FILTER (WHERE first_response_at IS NOT NULL) AS responded,
                COUNT(*) FILTER (WHERE first_response_at IS NULL
                                   AND status NOT IN ('RESOLVED','CLOSED','VOID')) AS not_responded,
                COUNT(*) FILTER (WHERE response_breached = TRUE) AS breached,
                AVG(EXTRACT(EPOCH FROM (first_response_at - create_time)) / 60)
                    FILTER (WHERE first_response_at IS NOT NULL) AS avg_minutes
              FROM sys_devops_ticket
            """;
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                result.put("responded", rs.getLong("responded"));
                result.put("notResponded", rs.getLong("not_responded"));
                result.put("breached", rs.getLong("breached"));
                double avg = rs.getDouble("avg_minutes");
                // NULL（无任何首响记录）→ null，不用 0 冒充「秒级响应」
                result.put("avgFirstResponseMinutes", rs.wasNull() ? null : Math.round(avg * 10) / 10.0);
            });
        } catch (Exception e) {
            log.warn("⚠️ [Repository] 首响统计失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 根因分类聚合统计（B3）
     */
    public java.util.Map<String, Object> countRootCauseStats() {
        String sql = """
            SELECT COALESCE(NULLIF(root_cause_category, ''), 'UNKNOWN') AS category,
                   COUNT(*) AS count
              FROM sys_devops_ticket
             WHERE root_cause IS NOT NULL
             GROUP BY COALESCE(NULLIF(root_cause_category, ''), 'UNKNOWN')
             ORDER BY count DESC
            """;
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                result.put(rs.getString("category"), rs.getLong("count"));
            });
        } catch (Exception e) {
            log.warn("⚠️ [Repository] 根因统计失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 闭环度量（B3）：MTTA / MTTM / MTTR + 各阶段完成率 + 跳过验证率
     * <p>
     * MTTR 只统计 verify_skipped=false 的工单——跳过验证的工单不计入，
     * 否则"点一下已解决"就能刷低 MTTR（6.41 契约）。
     * </p>
     */
    public java.util.Map<String, Object> countClosureMetrics() {
        String sql = """
            SELECT
                COUNT(*) AS total,
                COUNT(*) FILTER (WHERE first_response_at IS NOT NULL) AS first_responded,
                COUNT(*) FILTER (WHERE mitigated_at IS NOT NULL) AS mitigated,
                COUNT(*) FILTER (WHERE root_cause IS NOT NULL) AS root_cause_confirmed,
                COUNT(*) FILTER (WHERE verified_at IS NOT NULL) AS verified,
                COUNT(*) FILTER (WHERE verify_skipped = TRUE) AS verify_skipped,
                AVG(EXTRACT(EPOCH FROM (first_response_at - create_time)) / 60)
                    FILTER (WHERE first_response_at IS NOT NULL) AS mtta,
                AVG(EXTRACT(EPOCH FROM (mitigated_at - create_time)) / 60)
                    FILTER (WHERE mitigated_at IS NOT NULL) AS mttm,
                AVG(EXTRACT(EPOCH FROM (verified_at - create_time)) / 60)
                    FILTER (WHERE verified_at IS NOT NULL AND verify_skipped = FALSE) AS mttr
              FROM sys_devops_ticket
            """;
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                long verified = rs.getLong("verified");
                long skipped = rs.getLong("verify_skipped");
                result.put("total", rs.getLong("total"));
                result.put("firstResponded", rs.getLong("first_responded"));
                result.put("mitigated", rs.getLong("mitigated"));
                result.put("rootCauseConfirmed", rs.getLong("root_cause_confirmed"));
                result.put("verified", verified);
                result.put("verifySkipped", skipped);
                double mtta = rs.getDouble("mtta");
                result.put("mttaMinutes", rs.wasNull() ? null : Math.round(mtta * 10) / 10.0);
                double mttm = rs.getDouble("mttm");
                result.put("mttmMinutes", rs.wasNull() ? null : Math.round(mttm * 10) / 10.0);
                double mttr = rs.getDouble("mttr");
                result.put("mttrMinutes", rs.wasNull() ? null : Math.round(mttr * 10) / 10.0);
                result.put("skipRate", verified > 0
                        ? Math.round(skipped * 10000.0 / verified) / 100.0 : null);
            });
        } catch (Exception e) {
            log.warn("⚠️ [Repository] 闭环度量查询失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * RowMapper 实现
     */
    private static class TicketRowMapper implements RowMapper<DevOpsTicket> {
        @Override
        public DevOpsTicket mapRow(ResultSet rs, int rowNum) throws SQLException {
            DevOpsTicket ticket = new DevOpsTicket();
            ticket.setId(rs.getString("id"));
            ticket.setTitle(rs.getString("title"));
            ticket.setPriority(rs.getString("priority"));
            ticket.setModule(rs.getString("module"));
            ticket.setDescription(rs.getString("description"));
            ticket.setStackTrace(rs.getString("stack_trace"));
            ticket.setStatus(rs.getString("status"));
            ticket.setSourceTraceId(rs.getString("source_trace_id"));
            ticket.setAssignee(rs.getString("assignee"));
            ticket.setCreator(rs.getString("creator"));
            ticket.setCategory(rs.getString("category"));
            ticket.setSla(rs.getString("sla"));
            // B0：SLA 计时字段。历史数据可能为 NULL，实体的派生逻辑会退化为解析 sla 串
            ticket.setResponseDeadline(rs.getObject("response_deadline", LocalDateTime.class));
            ticket.setResolveDeadline(rs.getObject("resolve_deadline", LocalDateTime.class));
            // B1 首响/升级
            ticket.setFirstResponseAt(rs.getObject("first_response_at", LocalDateTime.class));
            ticket.setFirstResponder(rs.getString("first_responder"));
            boolean rb = rs.getBoolean("response_breached");
            ticket.setResponseBreached(rs.wasNull() ? Boolean.FALSE : rb);
            ticket.setEscalatedAt(rs.getObject("escalated_at", LocalDateTime.class));
            ticket.setEscalateReason(rs.getString("escalate_reason"));
            // B2 处置阶段
            ticket.setHandlingStage(rs.getString("handling_stage"));
            ticket.setMitigatedAt(rs.getObject("mitigated_at", LocalDateTime.class));
            // B3 根因 + 验证
            ticket.setRootCause(rs.getString("root_cause"));
            ticket.setRootCauseCategory(rs.getString("root_cause_category"));
            ticket.setRootCauseBy(rs.getString("root_cause_by"));
            ticket.setRootCauseAt(rs.getObject("root_cause_at", LocalDateTime.class));
            ticket.setVerifiedAt(rs.getObject("verified_at", LocalDateTime.class));
            ticket.setVerifier(rs.getString("verifier"));
            ticket.setVerifyMethod(rs.getString("verify_method"));
            ticket.setVerifyConclusion(rs.getString("verify_conclusion"));
            boolean vs = rs.getBoolean("verify_skipped");
            ticket.setVerifySkipped(rs.wasNull() ? Boolean.FALSE : vs);
            ticket.setVerifySkipReason(rs.getString("verify_skip_reason"));
            int v = rs.getInt("version");
            ticket.setVersion(rs.wasNull() ? 0 : v);
            ticket.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            ticket.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return ticket;
        }
    }
}
