package com.devops.agent.domain.governance;

import com.devops.agent.common.exception.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 风险等级策略仓储（L3）。
 *
 * <h3>关键设计：只有 UPDATE，没有 INSERT / DELETE</h3>
 * 四行记录由迁移脚本 v26 种下，与 {@link com.devops.agent.domain.tools.ToolRiskLevel}
 * 一一对应。<b>刻意不提供增删</b>：等级由 Java 枚举定义，引擎只会产出这四个值之一。
 * 允许新建第五个等级，它永远不会被任何动作命中——
 * 页面上看着有、实际是死配置，比没有更糟。
 *
 * <h3>更新用 CAS（版本号条件）</h3>
 * {@code WHERE risk_level = ? AND version = ?}，受影响 0 行即抛
 * {@link OptimisticLockException}。
 * 两个管理员同时编辑「高风险执行」的审批模式，一个改成 DUAL、一个改成 NONE，
 * 无锁时后写者静默覆盖——前者以为自己已经收紧了，实际系统是敞开的。
 * 这类「以为关掉了实际没关」正是自动化事故的典型成因。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@Slf4j
@Repository
public class RiskPolicyRepository {

    private final JdbcTemplate jdbcTemplate;

    public RiskPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<RiskPolicy> ROW_MAPPER = (rs, n) -> {
        RiskPolicy p = new RiskPolicy();
        p.setRiskLevel(rs.getString("risk_level"));
        p.setDisplayName(rs.getString("display_name"));
        p.setDescription(rs.getString("description"));
        // 用宽松解析而非 valueOf：库里存了脏值时回退到最严格档，
        // 而不是让整个列表查询因一行脏数据抛 IllegalArgumentException
        p.setApprovalMode(ApprovalMode.parseOrStrictest(rs.getString("approval_mode")));
        p.setApprovalTimeoutMinutes(rs.getInt("approval_timeout_minutes"));
        p.setAutoExecuteAllowed(rs.getBoolean("auto_execute_allowed"));
        p.setMaxBlastRadiusPercent(rs.getInt("max_blast_radius_percent"));
        p.setMaxBlastRadiusCount(rs.getInt("max_blast_radius_count"));
        p.setCooldownSeconds(rs.getInt("cooldown_seconds"));
        p.setMaxRetries(rs.getInt("max_retries"));
        p.setEscalateAfterMinutes(rs.getInt("escalate_after_minutes"));
        p.setEscalateTarget(EscalateTarget.parseOrDefault(rs.getString("escalate_target")));
        p.setAllowedEnvironments(rs.getString("allowed_environments"));
        p.setVersion(rs.getInt("version"));
        p.setUpdatedBy(rs.getString("updated_by"));
        if (rs.getTimestamp("create_time") != null) {
            p.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        }
        if (rs.getTimestamp("update_time") != null) {
            p.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        }
        return p;
    };

    /**
     * 查询全部策略。
     *
     * <p>排序刻意用 CASE 而非字母序：字母序会排成
     * CONTROLLED_WRITE / DRAFT / HIGH_RISK_EXECUTION / READ_ONLY，
     * 这个顺序对用户毫无意义。按<b>风险从低到高</b>排，
     * 页面自上而下就是一条「越往下越危险」的渐进线。</p>
     */
    public List<RiskPolicy> findAll() {
        return jdbcTemplate.query("""
            SELECT * FROM sys_risk_policy
             ORDER BY CASE risk_level
                        WHEN 'READ_ONLY'           THEN 1
                        WHEN 'DRAFT'               THEN 2
                        WHEN 'CONTROLLED_WRITE'    THEN 3
                        WHEN 'HIGH_RISK_EXECUTION' THEN 4
                        ELSE 99
                      END
            """, ROW_MAPPER);
    }

    public Optional<RiskPolicy> findByRiskLevel(String riskLevel) {
        List<RiskPolicy> rows = jdbcTemplate.query(
                "SELECT * FROM sys_risk_policy WHERE risk_level = ?", ROW_MAPPER, riskLevel);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 按版本号 CAS 更新。
     *
     * @param expectedVersion 客户端读到的版本号
     * @throws OptimisticLockException 版本不匹配（已被他人修改）或记录不存在
     */
    public void update(RiskPolicy policy, int expectedVersion, String operator) {
        int affected = jdbcTemplate.update("""
            UPDATE sys_risk_policy
               SET approval_mode            = ?,
                   approval_timeout_minutes = ?,
                   auto_execute_allowed     = ?,
                   max_blast_radius_percent = ?,
                   max_blast_radius_count   = ?,
                   cooldown_seconds         = ?,
                   max_retries              = ?,
                   escalate_after_minutes   = ?,
                   escalate_target          = ?,
                   allowed_environments     = ?,
                   version                  = version + 1,
                   updated_by               = ?,
                   update_time              = CURRENT_TIMESTAMP
             WHERE risk_level = ? AND version = ?
            """,
                policy.getApprovalMode().name(),
                policy.getApprovalTimeoutMinutes(),
                policy.isAutoExecuteAllowed(),
                policy.getMaxBlastRadiusPercent(),
                policy.getMaxBlastRadiusCount(),
                policy.getCooldownSeconds(),
                policy.getMaxRetries(),
                policy.getEscalateAfterMinutes(),
                policy.getEscalateTarget().name(),
                policy.getAllowedEnvironments(),
                operator,
                policy.getRiskLevel(),
                expectedVersion);

        if (affected == 0) {
            // 多查一次拿真实版本号。一般的 CAS 失败不值得多一次查询，
            // 但这里值得：安全策略冲突时用户最需要知道的是「对方改到第几版了」，
            // 好判断要不要去问对方改了什么，而不是盲目覆盖回自己的值
            Integer actual = currentVersion(policy.getRiskLevel());
            log.warn("⚠️ [RiskPolicy] CAS 更新失败 | level={} | expected={} | actual={} | operator={}",
                    policy.getRiskLevel(), expectedVersion, actual, operator);
            throw new OptimisticLockException(policy.getRiskLevel(), expectedVersion, actual);
        }
        // 用 warn 而非 info：安全边界变更是排查事故时必须能一眼看到的事件，
        // 淹没在 info 流水里会让「谁在故障前 10 分钟关掉了审批」难以定位
        log.warn("🔐 [RiskPolicy] 安全策略已变更 | level={} | approvalMode={} | autoExecute={} | operator={}",
                policy.getRiskLevel(), policy.getApprovalMode(),
                policy.isAutoExecuteAllowed(), operator);
    }

    /** 读当前版本号，仅用于冲突时补全提示。记录不存在返回 null */
    private Integer currentVersion(String riskLevel) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT version FROM sys_risk_policy WHERE risk_level = ?",
                    Integer.class, riskLevel);
        } catch (Exception e) {
            // 补全提示失败不应盖过原本的冲突错误——降级为无版本号的提示
            return null;
        }
    }
}
