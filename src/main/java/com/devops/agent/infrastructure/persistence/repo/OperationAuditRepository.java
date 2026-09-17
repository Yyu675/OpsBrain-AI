package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.common.audit.OperationAuditRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * 操作审计写入（C5）。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
@Repository
public class OperationAuditRepository {

    private static final String INSERT = """
            INSERT INTO sys_operation_audit
                (trace_id, actor_id, actor_name, action,
                 target_type, target_id,
                 http_method, http_path, status_code,
                 success, biz_code, request_digest, error_message,
                 client_ip, user_agent, duration_ms, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** 定时清理：删除 N 天前的操作审计记录（默认 180 天） */
    private static final String DELETE_OLDER_THAN = """
            DELETE FROM sys_operation_audit WHERE create_time < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public OperationAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入一条审计记录。
     *
     * <p><b>失败只告警，不抛出</b>：审计写入失败不应导致用户的业务操作失败。
     * 一次审计丢失是可接受的降级；因为审计表故障而让用户改不了工单，
     * 是把辅助设施变成了单点。丢失会留 ERROR 日志供事后追查。</p>
     */
    public void save(OperationAuditRecord r) {
        try {
            jdbcTemplate.update(INSERT,
                    r.traceId(), r.actorId(), r.actorName(), r.action(),
                    r.targetType(), r.targetId(),
                    r.httpMethod(), r.httpPath(), r.statusCode(),
                    r.success(), r.bizCode(), r.requestDigest(), r.errorMessage(),
                    r.clientIp(), r.userAgent(), r.durationMs(),
                    Timestamp.valueOf(r.createTime()));
        } catch (Exception e) {
            log.error("❌ [Audit] 审计写入失败（业务不受影响）| action={} | trace={} | err={}",
                    r.action(), r.traceId(), e.getMessage());
        }
    }

    /**
     * 清理指定天数之前的审计日志。
     *
     * @param retentionDays 保留天数（如 180），早于此时间的行会被删除
     * @return 删除的行数（-1 表示异常，不阻塞定时任务）
     */
    public int deleteOlderThan(int retentionDays) {
        try {
            Timestamp cutoff = Timestamp.valueOf(
                    java.time.LocalDateTime.now().minusDays(retentionDays));
            int deleted = jdbcTemplate.update(DELETE_OLDER_THAN, cutoff);
            if (deleted > 0) {
                log.info("🧹 [Audit] 清理 {} 天前的审计日志 | 删除 {} 条 | 截止 {}",
                        retentionDays, deleted, cutoff);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("⚠️ [Audit] 审计清理失败（不阻塞定时链）| {}", e.getMessage());
            return -1;
        }
    }
}
