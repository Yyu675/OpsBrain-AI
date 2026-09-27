package com.devops.agent.domain.biz.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务 → 值班负责人路由仓储（2026-09-25，工单待分配积压治理）。
 * <p>
 * 背景：告警自动建单恒传 {@code assignee=null}，真实库 27/28 张工单停在
 * 「待分配」——不是没人接单，是系统根本没把单子递到任何人手上。
 * 本表是路由的唯一事实来源：告警建单时按服务名查负责人。
 * </p>
 * <p>
 * 服务名匹配按 {@code lower()} 归一：各告警源的服务名写法不一
 * （order-service / Order-Service），库里建表时的小写唯一索引同款口径。
 * </p>
 */
@Repository
public class ServiceOwnerRepository {

    private static final Logger log = LoggerFactory.getLogger(ServiceOwnerRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public ServiceOwnerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按服务名查值班负责人（仅启用行）。
     *
     * @return 命中返回负责人姓名；未配置返回 empty——调用方保持「待分配」原行为
     */
    public Optional<String> findOwnerByService(String service) {
        if (service == null || service.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                SELECT owner FROM sys_service_owner
                 WHERE lower(service) = lower(?) AND enabled = TRUE
                 LIMIT 1
                """;
        List<String> rows = jdbcTemplate.queryForList(sql, String.class, service.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 全量路由清单（管理接口用），按服务名排序。 */
    public List<Map<String, Object>> listAll() {
        return jdbcTemplate.queryForList(
                "SELECT id, service, owner, enabled, create_time, update_time "
                        + "FROM sys_service_owner ORDER BY lower(service)");
    }

    /**
     * 新增或重建一条路由。同名服务的启用行被唯一索引挡住，故先停用旧行再插新行——
     * 换负责人是「重建路由」而非「原地改」，历史归属在停用行里仍可查。
     *
     * @return 新路由行 id
     */
    public Long upsert(String service, String owner) {
        jdbcTemplate.update(
                "UPDATE sys_service_owner SET enabled = FALSE, update_time = CURRENT_TIMESTAMP "
                        + "WHERE lower(service) = lower(?) AND enabled = TRUE",
                service.trim());
        return jdbcTemplate.queryForObject(
                "INSERT INTO sys_service_owner (service, owner) VALUES (?, ?) RETURNING id",
                Long.class, service.trim(), owner.trim());
    }

    /** 停用某服务的路由（不物理删除，保留审计痕迹）。 */
    public int disable(String service) {
        return jdbcTemplate.update(
                "UPDATE sys_service_owner SET enabled = FALSE, update_time = CURRENT_TIMESTAMP "
                        + "WHERE lower(service) = lower(?) AND enabled = TRUE",
                service.trim());
    }
}
