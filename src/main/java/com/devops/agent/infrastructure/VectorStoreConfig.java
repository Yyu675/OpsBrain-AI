package com.devops.agent.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置类（Infrastructure 层）
 * <p>
 * 职责：声明向量维度铁律配置（全链路唯一来源）。
 * <p>
 * <b>注意</b>：检索已改用 JdbcTemplate 直查（{@link com.devops.agent.domain.rag.HybridRetrieverService}），
 * 不再通过 LangChain4j 的 {@code EmbeddingStore} 接口，故 pgVectorEmbeddingStore /
 * mockEmbeddingStore 两个 Bean 已移除（P2-19）。
 * </p>
 * <p>
 * 架构约束：
 * - 本类属于 Infrastructure 层，不得 import Application / Domain 层的类
 * - 向量维度铁律：dimension = 1536，必须与 init.sql VECTOR(1536) 和 EmbeddingModel 输出一致
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    /**
     * 向量维度（全链路唯一来源）
     * 铁律：必须与 init.sql VECTOR(1536) 和 EmbeddingModel 输出一致
     */
    @Value("${devops.ai.vector.dimension}")
    private Integer dimension;

    /**
     * pgvector 表名（对应 init.sql 的 sys_knowledge_chunk）
     */
    private static final String TABLE_NAME = "sys_knowledge_chunk";
}
