package com.devops.agent.infrastructure.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 向量库（pgvector）可达性健康指示器（P2 自身可观测性）。
 *
 * <p>为什么需要：RAG 检索是问答/分析/诊断的前提。向量库不可达时，
 * 检索熔断会让 AI 全部回答「知识库无相关文档」——系统表面正常，能力已废。
 * 用一次轻量 SQL（SELECT 1）验证 pgvector 连接与 extension 可用性。</p>
 */
@Component
public class VectorStoreHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            boolean pgOk = one != null && one == 1;

            // pgvector extension 是否可用（知识库向量检索的前提）
            Integer vecOk = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);

            return (pgOk && vecOk != null && vecOk > 0)
                    ? Health.up().withDetail("pgvector", "available").build()
                    : Health.down().withDetail("reason", "pgvector extension 未安装").build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("reason", "向量库连接失败: " + e.getClass().getSimpleName())
                    .withDetail("message", e.getMessage() == null ? "" : e.getMessage())
                    .build();
        }
    }
}
