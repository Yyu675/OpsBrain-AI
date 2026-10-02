package com.devops.agent.domain.alert.repository;

import com.devops.agent.domain.alert.entity.Alert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 告警仓储（sys_alert 表）
 * <p>
 * 提供告警 CRUD、去重查询、状态变更、工单回填等操作。
 * 遵循项目 JdbcTemplate + RowMapper 模式，PG 主键使用 KeyHolder 显式指定列名。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-14
 */
@Slf4j
@Repository
public class AlertRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== RowMapper ====================

    private static final RowMapper<Alert> ALERT_ROW_MAPPER = (rs, rowNum) -> {
        Alert alert = new Alert();
        alert.setId(rs.getLong("id"));
        alert.setSource(rs.getString("source"));
        alert.setSystem(rs.getString("system"));
        alert.setAlertName(rs.getString("alert_name"));
        alert.setLevel(rs.getString("level"));
        alert.setTitle(rs.getString("title"));
        alert.setDescription(rs.getString("description"));
        alert.setStatus(rs.getString("status"));
        alert.setDedupKey(rs.getString("dedup_key"));
        alert.setService(rs.getString("service"));
        alert.setModule(rs.getString("module"));
        alert.setOccurrenceCount(rs.getInt("occurrence_count"));
        alert.setFirstOccurredAt(rs.getObject("first_occurred_at", LocalDateTime.class));
        alert.setLastOccurredAt(rs.getObject("last_occurred_at", LocalDateTime.class));
        alert.setAcknowledgedAt(rs.getObject("acknowledged_at", LocalDateTime.class));
        alert.setResolvedAt(rs.getObject("resolved_at", LocalDateTime.class));
        alert.setTicketId(rs.getString("ticket_id"));
        // V11 原始标签/注解：PG 的 jsonb 列经 getString 得到 JSON 文本
        alert.setLabelsJson(rs.getString("labels_json"));
        alert.setAnnotationsJson(rs.getString("annotations_json"));
        alert.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        alert.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return alert;
    };

    // ==================== 查询 ====================

    /**
     * 按去重键查找活跃告警（FIRING / ACKNOWLEDGED）
     */
    public Optional<Alert> findActiveByDedupKey(String dedupKey) {
        String sql = "SELECT * FROM sys_alert WHERE dedup_key = ? AND status IN ('FIRING', 'ACKNOWLEDGED') LIMIT 1";
        List<Alert> results = jdbcTemplate.query(sql, ALERT_ROW_MAPPER, dedupKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 某告警名最近一次出现的时间（管道心跳用）。
     * <p>看门狗告警每次重复推送都会刷新 last_occurred_at——它停跳就是管道断了。</p>
     *
     * @return 从未收到过该告警返回 empty
     */
    public Optional<java.time.LocalDateTime> findLatestOccurredAtByName(String alertName) {
        String sql = "SELECT MAX(last_occurred_at) FROM sys_alert WHERE alert_name = ?";
        return Optional.ofNullable(
                jdbcTemplate.queryForObject(sql, java.time.LocalDateTime.class, alertName));
    }

    /** 时间窗内的告警总数（效能大盘压缩比的时间窗分子：last_occurred_at >= since）。 */
    public long countSince(java.time.LocalDateTime since) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE last_occurred_at >= ?",
                Long.class, java.sql.Timestamp.valueOf(since));
        return n != null ? n : 0L;
    }

    /**
     * 派生事件数（Incident 方案 C，2026-09-27）。
     * <p>同 system + service + 10 分钟窗口桶的告警归并为同一「事件」——
     * 不建 Incident 表、不动写入路径，只修正报表口径：
     * 让「一次故障反复响」（多条告警同桶）与「多个不同故障」在数字上分开。</p>
     */
    public long countDerivedIncidents(java.time.LocalDateTime since) {
        Long n = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM (
                  SELECT system, service,
                         floor(extract(epoch from first_occurred_at) / 600) AS bucket
                  FROM sys_alert
                  WHERE first_occurred_at >= ?
                  GROUP BY system, service, bucket
                ) t
                """,
                Long.class, java.sql.Timestamp.valueOf(since));
        return n != null ? n : 0L;
    }

    /**
     * 同事件告警（建议3：告警详情页「同事件告警」联动）。
     * <p>口径与 {@link #countDerivedIncidents} 的事件归并一致：
     * 同 system + 同 service、且首次发生时间落在锚点告警 ±window 分钟窗内。
     * 一个「事件」= 一次故障反复响——把散落的兄弟告警拉到同一屏，
     * 值班人一眼看清这条故障波及了多少条告警规则。
     * </p>
     *
     * @param excludeId  锚点告警 id（结果中排除它自身）
     * @param system     锚点的来源系统（空/blank 则不做该维过滤）
     * @param service    锚点的服务名（空/blank 则不做该维过滤）
     * @param anchor     锚点的首次发生时间（first_occurred_at，可为 create_time 兜底）
     * @param windowMinutes 窗口半径（分钟），前后扩张
     */
    public List<Alert> findRelated(Long excludeId, String system, String service,
                                   LocalDateTime anchor, int windowMinutes) {
        boolean hasSystem = system != null && !system.isBlank();
        boolean hasService = service != null && !service.isBlank();
        if (!hasSystem && !hasService) {
            return List.of();   // 两个维度都缺，无法判定「同事件」，不硬凑
        }
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM sys_alert WHERE id <> ? AND first_occurred_at >= ? AND first_occurred_at <= ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(excludeId);
        params.add(java.sql.Timestamp.valueOf(anchor.minusMinutes(windowMinutes)));
        params.add(java.sql.Timestamp.valueOf(anchor.plusMinutes(windowMinutes)));
        if (hasSystem) {
            sql.append(" AND system = ?");
            params.add(system.trim());
        }
        if (hasService) {
            sql.append(" AND service = ?");
            params.add(service.trim());
        }
        sql.append(" ORDER BY first_occurred_at ASC, id ASC");
        return jdbcTemplate.query(sql.toString(), ALERT_ROW_MAPPER, params.toArray());
    }

    /** 按告警名查活跃告警（心跳元告警的「恢复」判定用）。 */
    public Optional<Alert> findActiveByName(String alertName) {
        String sql = "SELECT * FROM sys_alert WHERE alert_name = ? AND status IN ('FIRING', 'ACKNOWLEDGED') LIMIT 1";
        List<Alert> rows = jdbcTemplate.query(sql, ALERT_ROW_MAPPER, alertName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 查找时间窗口内同 service+module 且已建单的活跃告警（方向 E：告警聚合降噪）
     * <p>
     * 用于告警风暴抑制：同一服务+模块在短时间内产生的多条<b>不同 dedup_key</b> 告警
     * （如一个节点挂了导致其上多个 Pod 各报不同告警），只应建一张工单。
     * 窗口内已有已建单（ticket_id 非空）的活跃告警时，新告警关联其 ticket_id 而不新建单。
     * </p>
     * <p>
     * 与 {@link #findActiveByDedupKey} 互补：后者处理「完全同键」重复（occurrence 递增），
     * 本方法处理「同服务不同键」的风暴聚合。取最近一条作为组代表。
     * </p>
     *
     * @param service       服务名（为空则不聚合——无法判定归属）
     * @param module        模块
     * @param windowMinutes 聚合时间窗口（分钟）
     * @return 组代表告警（含可关联的 ticket_id），无则 empty
     */
    public Optional<Alert> findActiveGroupTicket(String service, String module, int windowMinutes) {
        if (service == null || service.isBlank()) {
            return Optional.empty();   // 无 service 无法判定聚合归属，不抑制
        }
        String sql = """
            SELECT * FROM sys_alert
             WHERE service = ? AND module = ?
               AND status IN ('FIRING', 'ACKNOWLEDGED')
               AND ticket_id IS NOT NULL
               AND last_occurred_at >= CURRENT_TIMESTAMP - CAST(? AS INTEGER) * INTERVAL '1 minute'
             ORDER BY last_occurred_at DESC
             LIMIT 1
            """;
        List<Alert> results = jdbcTemplate.query(sql, ALERT_ROW_MAPPER, service, module, windowMinutes);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 查找「自愈观察窗」已到期的告警（FR-3.1：warning 级 10 分钟未自愈才建单）。
     * <p>
     * 条件：活跃（FIRING/ACKNOWLEDGED）+ 未建单（ticket_id 为空）+
     * 级别在观察级集合内 + 首次发生时间已超出观察窗口。
     * 窗口内 resolved 回流的告警状态已变 RESOLVED，天然不会被查出来——
     * 这就是「自愈留统计不留单」的统计口径来源。
     * </p>
     * <p>
     * {@code lookbackHours} 回看上限：防止配置错配（如观察级与建单门槛不相交）
     * 导致永远建不出单的告警被每次扫描反复捞出。超出回看的遗留行靠人工/离线治理。
     * </p>
     *
     * @param levels         观察级集合（如 P2/P3），空集合直接返回空
     * @param windowMinutes  观察窗口（分钟）：first_occurred_at 早于「现在-窗口」才到期
     * @param lookbackHours  回看上限（小时）：更老的未建单活跃告警不再补建
     * @param limit          单批上限：风暴期分批补建，避免一轮扫描打爆工单链
     */
    public List<Alert> findObservationDue(List<String> levels, int windowMinutes, int lookbackHours, int limit) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(levels.size(), "?"));
        String sql = """
            SELECT * FROM sys_alert
             WHERE status IN ('FIRING', 'ACKNOWLEDGED')
               AND ticket_id IS NULL
               AND first_occurred_at <= CURRENT_TIMESTAMP - CAST(? AS INTEGER) * INTERVAL '1 minute'
               AND first_occurred_at >= CURRENT_TIMESTAMP - CAST(? AS INTEGER) * INTERVAL '1 hour'
               AND level IN (%s)
             ORDER BY first_occurred_at ASC
             LIMIT ?
            """.formatted(placeholders);
        List<Object> params = new java.util.ArrayList<>();
        params.add(windowMinutes);
        params.add(lookbackHours);
        params.addAll(levels);
        params.add(limit);
        return jdbcTemplate.query(sql, ALERT_ROW_MAPPER, params.toArray());
    }

    /**
     * 观察窗统计：仍在观察中（活跃 + 未建单 + 观察级 + 未超窗）的告警数。
     */
    public long countObservingNow(List<String> levels, int windowMinutes) {
        if (levels == null || levels.isEmpty()) return 0L;
        String placeholders = String.join(", ", java.util.Collections.nCopies(levels.size(), "?"));
        String sql = """
            SELECT COUNT(*) FROM sys_alert
             WHERE status IN ('FIRING', 'ACKNOWLEDGED')
               AND ticket_id IS NULL
               AND first_occurred_at > CURRENT_TIMESTAMP - CAST(? AS INTEGER) * INTERVAL '1 minute'
               AND level IN (%s)
            """.formatted(placeholders);
        List<Object> params = new java.util.ArrayList<>();
        params.add(windowMinutes);
        params.addAll(levels);
        Long n = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
        return n != null ? n : 0L;
    }

    /**
     * 观察窗自愈数（FR-3.1 核心成效指标）：观察级、窗口内 resolved 回流、从未建单。
     * 「RESOLVED + ticket_id 为空」即「它自己好了，没惊动任何人」。
     */
    public long countSelfHealedSince(List<String> levels, java.time.LocalDateTime since) {
        if (levels == null || levels.isEmpty()) return 0L;
        String placeholders = String.join(", ", java.util.Collections.nCopies(levels.size(), "?"));
        String sql = """
            SELECT COUNT(*) FROM sys_alert
             WHERE status = 'RESOLVED'
               AND ticket_id IS NULL
               AND resolved_at >= ?
               AND level IN (%s)
            """.formatted(placeholders);
        List<Object> params = new java.util.ArrayList<>();
        params.add(java.sql.Timestamp.valueOf(since));
        params.addAll(levels);
        Long n = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
        return n != null ? n : 0L;
    }

    /**
     * 观察级告警到期转单/进组数（含聚合进组的——ticket_id 非空即「最终需要人看」）。
     */
    public long countObservationEscalatedSince(List<String> levels, java.time.LocalDateTime since) {
        if (levels == null || levels.isEmpty()) return 0L;
        String placeholders = String.join(", ", java.util.Collections.nCopies(levels.size(), "?"));
        String sql = """
            SELECT COUNT(*) FROM sys_alert
             WHERE ticket_id IS NOT NULL
               AND first_occurred_at >= ?
               AND level IN (%s)
            """.formatted(placeholders);
        List<Object> params = new java.util.ArrayList<>();
        params.add(java.sql.Timestamp.valueOf(since));
        params.addAll(levels);
        Long n = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
        return n != null ? n : 0L;
    }

    /**
     * 按 ID 查询
     */
    public Optional<Alert> findById(Long id) {
        String sql = "SELECT * FROM sys_alert WHERE id = ?";
        List<Alert> results = jdbcTemplate.query(sql, ALERT_ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 按状态分页查询
     */
    public List<Alert> findByStatus(String status, int page, int size) {
        int offset = (page - 1) * size;
        String sql = "SELECT * FROM sys_alert WHERE status = ? ORDER BY last_occurred_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ALERT_ROW_MAPPER, status, size, offset);
    }

    /**
     * 按状态 + 级别组合条件分页查询（供告警列表页）
     * <p>
     * 筛选分页下沉到 SQL（同 6.15 工单契约）：前端本地过滤只能作用于当前页，
     * 会静默隐藏页外数据。WHERE 与 count 查询共用 {@link #buildWhere}，
     * 保证 {@code total} 与实际行数一致。
     * </p>
     *
     * @param system              来源系统筛选（V9 起，空=全部）
     * @param observingLevels     非空时启用「只看观察中」：活跃 + 未建单 + 观察级 + 未超窗
     *                            （FR-3.1 派生状态，参数由 AlertQueryService 从观察窗配置取）
     * @param observingWindowMin  观察窗口（分钟），observingLevels 非空时生效
     */
    public List<Alert> findPage(String status, String level, String system,
                                List<String> observingLevels, int observingWindowMin, int page, int size) {
        int offset = (page - 1) * size;
        WhereClause where = buildWhere(status, level, system, observingLevels, observingWindowMin);
        String sql = "SELECT * FROM sys_alert " + where.sql()
                + " ORDER BY last_occurred_at DESC LIMIT ? OFFSET ?";
        List<Object> params = new java.util.ArrayList<>(where.params());
        params.add(size);
        params.add(offset);
        return jdbcTemplate.query(sql, ALERT_ROW_MAPPER, params.toArray());
    }

    /**
     * 按状态 + 级别组合条件计数（与 {@link #findPage} 共用 WHERE）
     */
    public int countByQuery(String status, String level, String system,
                            List<String> observingLevels, int observingWindowMin) {
        WhereClause where = buildWhere(status, level, system, observingLevels, observingWindowMin);
        String sql = "SELECT COUNT(*) FROM sys_alert " + where.sql();
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, where.params().toArray());
        return count != null ? count : 0;
    }

    /** 全部来源系统去重列表（告警列表的 system 筛选下拉数据源） */
    public List<String> findDistinctSystems() {
        return jdbcTemplate.query("SELECT DISTINCT system FROM sys_alert ORDER BY system",
                (rs, i) -> rs.getString(1));
    }

    /**
     * 构建动态 WHERE（status / level / system / 观察中 四个可选条件）
     * <p>字段值用参数化占位符，禁止拼接用户输入（SQL 注入防护）；
     * level 与观察级集合是服务端受控枚举，才可进 IN 占位符。</p>
     */
    private WhereClause buildWhere(String status, String level, String system,
                                   List<String> observingLevels, int observingWindowMin) {
        StringBuilder sql = new StringBuilder("WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status.trim().toUpperCase());
        }
        if (level != null && !level.isBlank()) {
            sql.append(" AND level = ?");
            params.add(level.trim().toUpperCase());
        }
        if (system != null && !system.isBlank()) {
            sql.append(" AND system = ?");
            params.add(system.trim());
        }
        if (observingLevels != null && !observingLevels.isEmpty()) {
            // 「观察中」= 活跃 + 未建单 + 观察级 + 未超窗（与 AlertService.isObserving 同口径）
            sql.append(" AND status IN ('FIRING','ACKNOWLEDGED') AND ticket_id IS NULL");
            sql.append(" AND first_occurred_at > CURRENT_TIMESTAMP - CAST(? AS INTEGER) * INTERVAL '1 minute'");
            params.add(observingWindowMin);
            sql.append(" AND level IN (")
                    .append(String.join(", ", java.util.Collections.nCopies(observingLevels.size(), "?")))
                    .append(")");
            params.addAll(observingLevels);
        }
        return new WhereClause(sql.toString(), params);
    }

    /** WHERE 子句与其参数 */
    private record WhereClause(String sql, List<Object> params) {
    }

    /**
     * 查询所有活跃告警（FIRING / ACKNOWLEDGED）
     */
    public List<Alert> findAllActive() {
        String sql = "SELECT * FROM sys_alert WHERE status IN ('FIRING', 'ACKNOWLEDGED') ORDER BY last_occurred_at DESC";
        return jdbcTemplate.query(sql, ALERT_ROW_MAPPER);
    }

    /**
     * 按条件计数
     */
    public int countByStatus(String status) {
        String sql = "SELECT COUNT(*) FROM sys_alert WHERE status = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, status);
        return count != null ? count : 0;
    }

    /**
     * 统计活跃告警总数
     */
    public int countActive() {
        String sql = "SELECT COUNT(*) FROM sys_alert WHERE status IN ('FIRING', 'ACKNOWLEDGED')";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    // ==================== 写入 ====================

    /**
     * 创建告警（FIRING 状态）
     * <p>
     * 使用 KeyHolder 显式指定 id 列，避免 PG 多列返回导致 getKey 异常。
     * </p>
     */
    public Alert save(Alert alert) {
        String sql = "INSERT INTO sys_alert (source, system, alert_name, level, title, description, status, " +
                "dedup_key, service, module, occurrence_count, first_occurred_at, last_occurred_at, " +
                "acknowledged_at, resolved_at, ticket_id, labels_json, annotations_json, create_time, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)";

        LocalDateTime now = LocalDateTime.now();
        if (alert.getCreateTime() == null) alert.setCreateTime(now);
        if (alert.getUpdateTime() == null) alert.setUpdateTime(now);
        if (alert.getStatus() == null) alert.setStatus("FIRING");
        if (alert.getOccurrenceCount() == null) alert.setOccurrenceCount(1);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, alert.getSource());
            ps.setString(2, alert.getSystem());
            ps.setString(3, alert.getAlertName());
            ps.setString(4, alert.getLevel());
            ps.setString(5, alert.getTitle());
            ps.setString(6, alert.getDescription());
            ps.setString(7, alert.getStatus());
            ps.setString(8, alert.getDedupKey());
            ps.setString(9, alert.getService());
            ps.setString(10, alert.getModule());
            ps.setInt(11, alert.getOccurrenceCount());
            ps.setObject(12, alert.getFirstOccurredAt());
            ps.setObject(13, alert.getLastOccurredAt());
            ps.setObject(14, alert.getAcknowledgedAt());
            ps.setObject(15, alert.getResolvedAt());
            ps.setObject(16, alert.getTicketId());
            ps.setString(17, alert.getLabelsJson() == null ? "{}" : alert.getLabelsJson());
            ps.setString(18, alert.getAnnotationsJson() == null ? "{}" : alert.getAnnotationsJson());
            ps.setObject(19, alert.getCreateTime());
            ps.setObject(20, alert.getUpdateTime());
            return ps;
        }, keyHolder);

        alert.setId(Objects.requireNonNull(keyHolder.getKey(), "save alert 主键获取失败").longValue());
        log.info("✅ 告警创建 | id={} alertName={} level={} dedupKey={}", alert.getId(), alert.getAlertName(), alert.getLevel(), alert.getDedupKey());
        return alert;
    }

    /**
     * 更新告警状态（幂等）
     */
    public void updateStatus(Long id, String status) {
        String sql = "UPDATE sys_alert SET status = ?, update_time = ? WHERE id = ?";
        int rows = jdbcTemplate.update(sql, status, LocalDateTime.now(), id);
        if (rows > 0) {
            log.info("✅ 告警状态变更 | id={} status={}", id, status);
        } else {
            log.warn("⚠️ 告警状态变更无影响 | id={} status={}（可能已不存在）", id, status);
        }
    }

    /**
     * 递增重复次数 + 更新最后触发时间
     */
    public void incrementOccurrence(Long id) {
        String sql = "UPDATE sys_alert SET occurrence_count = occurrence_count + 1, " +
                "last_occurred_at = ?, update_time = ? WHERE id = ?";
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(sql, now, now, id);
    }

    /**
     * 原子 upsert：并发同键重复推送一条 SQL 完成「插入新告警 或 计次既有告警」
     * （批 76 / P2-1，报告 174 审计件）。
     * <p>
     * 原实现「查后插」在风暴并发下两条同键推送都过 findActiveByDedupKey 的空判，
     * 后者撞 {@code uk_alert_active_dedup} 部分唯一索引按异常丢弃——告警没丢但
     * occurrence_count 少记，且异常路径污染日志。此方法靠
     * {@code ON CONFLICT ... WHERE 谓词 DO UPDATE} 把竞争窗口收进单条语句：
     * PG 保证部分唯一索引上的 ON CONFLICT 谓词匹配与 DO UPDATE 原子执行。
     * </p>
     *
     * @return true = 插入了新行（调用方走建单路径）；false = 计次了既有活跃行
     */
    public boolean insertOrIncrement(Alert alert) {
        String sql = "INSERT INTO sys_alert (source, system, alert_name, level, title, description, status, " +
                "dedup_key, service, module, occurrence_count, first_occurred_at, last_occurred_at, " +
                "acknowledged_at, resolved_at, ticket_id, labels_json, annotations_json, create_time, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?) " +
                "ON CONFLICT (dedup_key) WHERE status IN ('FIRING','ACKNOWLEDGED') DO UPDATE SET " +
                // 方案①（2026-10-01）：severity 不参与去重键——同键 label 重映射
                // 升级（WARNING→CRITICAL）此前只 +1 计次，level 永远停在首次值，
                // 下游按 level 的分级/建单/风暴抑制全部读旧值。冲突时刷源真值：
                // level/description/labels/annotations 恒取最新推送。
                "level = EXCLUDED.level, " +
                "description = EXCLUDED.description, " +
                "labels_json = EXCLUDED.labels_json, " +
                "annotations_json = EXCLUDED.annotations_json, " +
                "occurrence_count = sys_alert.occurrence_count + 1, " +
                "last_occurred_at = EXCLUDED.last_occurred_at, update_time = EXCLUDED.update_time " +
                "RETURNING (xmax = 0) AS inserted";
        LocalDateTime now = LocalDateTime.now();
        if (alert.getCreateTime() == null) alert.setCreateTime(now);
        if (alert.getUpdateTime() == null) alert.setUpdateTime(now);
        if (alert.getStatus() == null) alert.setStatus("FIRING");
        if (alert.getOccurrenceCount() == null) alert.setOccurrenceCount(1);

        // xmax=0 表示本事务新插入的行；DO UPDATE 走的行 xmax 非 0
        Boolean inserted = jdbcTemplate.query(sql, rs -> rs.next() && rs.getBoolean(1),
                alert.getSource(), alert.getSystem(), alert.getAlertName(), alert.getLevel(), alert.getTitle(),
                alert.getDescription(), alert.getStatus(), alert.getDedupKey(),
                alert.getService(), alert.getModule(), alert.getOccurrenceCount(),
                alert.getFirstOccurredAt(), alert.getLastOccurredAt(),
                alert.getAcknowledgedAt(), alert.getResolvedAt(), alert.getTicketId(),
                alert.getLabelsJson() == null ? "{}" : alert.getLabelsJson(),
                alert.getAnnotationsJson() == null ? "{}" : alert.getAnnotationsJson(),
                alert.getCreateTime(), alert.getUpdateTime());
        boolean isNew = Boolean.TRUE.equals(inserted);
        if (isNew) {
            // 回填 id 供调用方建单/广播用——单行 RETURNING id 即可，避免再查一次
            Long id = jdbcTemplate.query("SELECT id FROM sys_alert WHERE dedup_key = ? AND status IN ('FIRING','ACKNOWLEDGED') ORDER BY id DESC LIMIT 1",
                    (rs, i) -> rs.getLong(1), alert.getDedupKey()).stream().findFirst().orElse(null);
            alert.setId(id != null ? id : 0L);
            log.info("✅ 告警创建(原子upsert) | id={} alertName={} dedupKey={}", alert.getId(), alert.getAlertName(), alert.getDedupKey());
        } else {
            log.info("🔁 重复告警计次(原子upsert) | alertName={} dedupKey={}", alert.getAlertName(), alert.getDedupKey());
        }
        return isNew;
    }

    /**
     * 回填关联工单号
     */
    public void updateTicketId(Long id, String ticketId) {
        String sql = "UPDATE sys_alert SET ticket_id = ?, update_time = ? WHERE id = ?";
        jdbcTemplate.update(sql, ticketId, LocalDateTime.now(), id);
        log.info("✅ 告警工单回填 | id={} ticketId={}", id, ticketId);
    }

    /**
     * 标记人工确认
     * <p>条件带 {@code status <> 'RESOLVED'}：已恢复告警不可再确认。</p>
     *
     * @return 受影响行数（0 表示告警不存在或已恢复）
     */
    public int acknowledge(Long id) {
        String sql = "UPDATE sys_alert SET status = 'ACKNOWLEDGED', acknowledged_at = ?, update_time = ? WHERE id = ? AND status <> 'RESOLVED'";
        LocalDateTime now = LocalDateTime.now();
        int rows = jdbcTemplate.update(sql, now, now, id);
        if (rows > 0) {
            log.info("✅ 告警人工确认 | id={}", id);
        } else {
            log.warn("⚠️ 告警确认无影响 | id={}（可能已不存在或已恢复）", id);
        }
        return rows;
    }

    /**
     * 标记已恢复
     *
     * @return 受影响行数（0 表示告警不存在或已恢复）
     */
    public int resolve(Long id) {
        String sql = "UPDATE sys_alert SET status = 'RESOLVED', resolved_at = ?, update_time = ? WHERE id = ? AND status <> 'RESOLVED'";
        LocalDateTime now = LocalDateTime.now();
        int rows = jdbcTemplate.update(sql, now, now, id);
        if (rows > 0) {
            log.info("✅ 告警已恢复 | id={}", id);
        } else {
            log.warn("⚠️ 告警恢复无影响 | id={}（可能已不存在或已恢复）", id);
        }
        return rows;
    }

    /**
     * 标记已恢复（resolvedAt=真实恢复时刻——Alertmanager endsAt 穿透，方案④；
     * null/零值由调用方兜底为当前时间）。MTTR/持续时长以业务真实恢复为准，
     * 否则恒多算一段推送延迟。
     *
     * @return 受影响行数（0 表示告警不存在或已恢复）
     */
    public int resolve(Long id, LocalDateTime resolvedAt) {
        String sql = "UPDATE sys_alert SET status = 'RESOLVED', resolved_at = ?, update_time = ? WHERE id = ? AND status <> 'RESOLVED'";
        LocalDateTime at = resolvedAt != null ? resolvedAt : LocalDateTime.now();
        int rows = jdbcTemplate.update(sql, at, LocalDateTime.now(), id);
        if (rows > 0) {
            log.info("✅ 告警已恢复 | id={} | resolvedAt={}", id, at);
        } else {
            log.warn("⚠️ 告警恢复无影响 | id={}（可能已不存在或已恢复）", id);
        }
        return rows;
    }

    /** ② 恢复联动 auto 模式的守门计数：该单还有多少其它活跃关联告警（>0 就不关组单）。 */
    public long countOtherActiveByTicket(String ticketId, Long excludeAlertId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_alert WHERE ticket_id = ? AND id <> ? "
                        + "AND status IN ('FIRING','ACKNOWLEDGED')",
                Long.class, ticketId, excludeAlertId);
        return n == null ? 0 : n;
    }

    /**
     * 物理删除（仅用于清理测试数据）
     */
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM sys_alert WHERE id = ?", id);
        log.info("🗑️ 告警删除 | id={}", id);
    }
}