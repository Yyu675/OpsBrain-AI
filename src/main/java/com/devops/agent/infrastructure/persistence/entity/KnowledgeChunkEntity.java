package com.devops.agent.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 知识库切片实体（对应 sys_knowledge_chunk 表）
 * <p>职责：映射 PostgreSQL + pgvector 存储的知识库切片数据
 * <p>字段说明：
 * - embedding: 1536 维向量（方案 A 云 API Embedding），存储为 PostgreSQL VECTOR 类型
 * - content_tsv: 全文检索向量（tsvector），由 init.sql 的 trg_chunk_tsv_update 触发器自动维护
 * - parent_id/parent_text: 父子切片结构，子切片引用父段落完整文本
 * <p>
 * MVP-5 知识治理增强：新增 version、effective_at、expired_at、status、knowledge_source 字段
 * </p>
 *
 * @author OpsBrain AI Team
 * @since 2026-07-15
 */
@Entity
@Table(name = "sys_knowledge_chunk",
        indexes = {
                @Index(name = "idx_chunk_parent", columnList = "parent_id"),
                @Index(name = "idx_chunk_version_status", columnList = "version, status"),
                @Index(name = "idx_chunk_effective_expired", columnList = "effective_at, expired_at"),
                @Index(name = "idx_chunk_source", columnList = "knowledge_source")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunkEntity {

    /**
     * 主键，自增
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属文档 ID（关联 sys_knowledge_doc）
     * <p>
     * 全量重建时按此删除旧切片。此前仅有 doc_title 字符串关联，
     * 改标题即断链，旧切片无法清理会残留污染检索结果。
     * </p>
     */
    @Column(name = "doc_id")
    private Long docId;

    /**
     * 切片内容 SHA-256
     * <p>同文档内切片级去重：完全相同切片通常来自重复粘贴的段落，
     * 在向量化之前去掉可直接省下对应 API 成本。</p>
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /**
     * 文档标题（如："K8s 故障排查手册.md"）
     */
    @Column(name = "doc_title", nullable = false, length = 255)
    private String docTitle;

    /**
     * 章节标题（如："## Pod FailedMount 问题排查"）
     */
    @Column(name = "section_header", length = 512)
    private String sectionHeader;

    /**
     * 切片内容文本
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 父切片 ID（子切片专用，父切片此字段为 null）
     */
    @Column(name = "parent_id", length = 128)
    private String parentId;

    /**
     * 父切片完整文本（子切片专用，用于检索命中后返回完整上下文）
     */
    @Column(name = "parent_text", columnDefinition = "TEXT")
    private String parentText;

    /**
     * 扩展元数据（JSON 格式，存储文档来源、作者、版本等信息）
     * <p>
     * 列类型为 JSONB。Hibernate 不会把 String 隐式转为 JSONB，
     * 需用 {@code @JdbcTypeCode(SqlTypes.JSON)} 显式声明，
     * 否则报 {@code column "chunk_meta" is of type jsonb but
     * expression is of type character varying}。
     * </p>
     * <p>
     * 写入方须保证是<b>合法 JSON</b>。此前用
     * {@code Metadata.toMap().toString()} 产出的是 Java Map 的
     * {@code {k=v}} 格式，既非合法 JSON 也无法被 JSONB 接受。
     * </p>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chunk_meta", columnDefinition = "JSONB")
    private String chunkMeta;

    /**
     * 全文检索向量（PostgreSQL tsvector 类型）
     * <p>
     * <b>P2-21 修复</b>：此字段由 {@code init.sql} 中的
     * {@code trg_chunk_tsv_update} 触发器维护
     * （{@code tsvector_update_trigger(content_tsv, 'pg_catalog.simple', content)}），
     * Java 代码不直接读写。此前触发器缺失，本列恒为 NULL，
     * 且 `insertable/updatable = false` 让任何 Java 写入都被静默丢弃——
     * 混合检索（hybridEnabled=true）的 {@code ts_rank} 部分因此恒为 0。
     * </p>
     * <p>旧注释「由数据库触发器自动维护」声称存在而实际不存在的触发器，
     * 属于注释与实现脱节；触发器已补齐，声明方为真实。</p>
     */
    @Column(name = "content_tsv", columnDefinition = "TSVECTOR", insertable = false, updatable = false)
    private String contentTsv;

    /**
     * 向量 Embedding（1536 维，float[] 映射到 PostgreSQL VECTOR 类型）
     * 注意：JPA 原生不支持 VECTOR 类型，需通过 @Column(columnDefinition) 映射
     */
    @Column(name = "embedding", nullable = false, columnDefinition = "VECTOR(1536)")
    private String embedding;  // 存储为 JSON 数组字符串，如 "[0.1, 0.2, ...]"

    // ==================== MVP-5 知识治理字段 ====================

    /**
     * 文档版本号（从 1 开始递增，用于乐观锁与版本对比）
     */
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    /**
     * 生效时间（文档开始生效的时间，支持定时发布）
     */
    @Column(name = "effective_at")
    private LocalDateTime effectiveAt;

    /**
     * 过期时间（文档失效时间，过期后不参与检索）
     */
    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    /**
     * 文档状态（ACTIVE/DEPRECATED/ARCHIVED）
     * - ACTIVE: 正常参与检索
     * - DEPRECATED: 已废弃，不参与检索，保留历史
     * - ARCHIVED: 已归档，冷存储，不参与检索
     */
    @Builder.Default
    @Column(name = "status", length = 16, nullable = false)
    private String status = "ACTIVE";

    /**
     * 知识来源分类（OFFICIAL/SOP/TICKET/BLOG/UNKNOWN）
     * 用于检索时按权威性加权排序
     */
    @Builder.Default
    @Column(name = "knowledge_source", length = 32, nullable = false)
    private String knowledgeSource = "UNKNOWN";

    /**
     * 可见性（C1）：PUBLIC / INTERNAL / RESTRICTED。
     * <p>
     * <b>冗余自所属文档</b>。之所以在切片上再存一份，是因为检索走
     * {@code sys_knowledge_chunk} 的 HNSW 向量索引——若权限字段只在文档表，
     * 检索 SQL 必须 JOIN 才能过滤，而带 JOIN 的
     * {@code ORDER BY embedding <=> ?} 会让 PG 放弃 HNSW 走全表扫描。
     * </p>
     * <p>代价是写入需同步：文档权限变更后必须重刷其切片，
     * 否则会出现「文档已设为受限但切片仍可被检索到」的越权。</p>
     */
    @Builder.Default
    @Column(name = "visibility", length = 16, nullable = false)
    private String visibility = "PUBLIC";

    /**
     * 归属部门（C1）：仅在 {@code visibility=RESTRICTED} 时用于判定可见性。
     * 同样冗余自所属文档。
     */
    @Column(name = "owner_dept", length = 64)
    private String ownerDept;

    /**
     * 创建时间
     */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /**
     * 持久化前自动设置创建时间
     */
    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (updateTime == null) {
            updateTime = LocalDateTime.now();
        }
        if (version == null) {
            version = 1;
        }
        if (status == null) {
            status = "ACTIVE";
        }
        if (knowledgeSource == null) {
            knowledgeSource = "UNKNOWN";
        }
    }

    /**
     * 更新前自动设置更新时间
     */
    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
