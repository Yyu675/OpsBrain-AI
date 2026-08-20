package com.devops.agent.domain.rag;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文档（当前版本）
 * <p>
 * 对应表 {@code sys_knowledge_doc}。历史版本在
 * {@code sys_knowledge_doc_history}，只存原文不存向量——
 * 理由见 {@link KnowledgeDocLifecycle} 第二节。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-10
 */
public class KnowledgeDoc {

    private Long id;

    private String title;
    private String category;
    /** 分类真实关联 ID；category 保留为兼容展示字段。 */
    private Long categoryId;
    private String author;

    /** Markdown 原文 */
    private String content;

    /** 摘要，列表页展示 */
    private String summary;

    // ==================== 版本与指纹 ====================

    private Integer version;

    /**
     * SHA-256(归一化后的 content)
     * <p>未变则跳过向量化——文档级全量重建策略的成本控制关键。</p>
     */
    private String contentHash;

    /**
     * SimHash 64 位指纹
     * <p>检测跨文档近似重复（汉明距离 ≤ 10）。</p>
     */
    private Long simhash;

    // ==================== 生命周期 ====================

    /** DRAFT / PUBLISHED / DEPRECATED / ARCHIVED */
    private String status;

    /**
     * 向量化状态：PENDING / INDEXED / FAILED / SKIPPED
     */
    private String indexStatus;

    private String indexError;

    private LocalDateTime indexedAt;

    /** 当前版本切片数 */
    private Integer chunkCount;

    // ==================== 治理字段 ====================

    private LocalDateTime effectiveAt;
    private LocalDateTime expiredAt;

    /** OFFICIAL / SOP / TICKET / BLOG */
    private String knowledgeSource;

    /**
     * 源工单 ID（L1.5 来源回链）
     * <p>
     * 由工单复盘沉淀的文档记录源工单，便于反查「此工单已沉淀为哪些知识」。
     * 非工单沉淀时为 null。
     * </p>
     */
    private Long sourceTicketId;

    /**
     * 来源类型：TICKET / MANUAL / IMPORT 等
     * <p>与 {@link #knowledgeSource} 区别：knowledgeSource 描述权威分级（SOP/官方），
     * sourceType 描述录入渠道（由工单沉淀 / 手动新建 / 批量导入）。</p>
     */
    private String sourceType;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 标签（存于关联表，按需装填） */
    private List<String> tags;

    /**
     * 是否可被检索
     * <p>状态为已发布 <b>且</b> 向量化成功。缺一不可。</p>
     */
    public boolean isRetrievable() {
        return KnowledgeDocLifecycle.shouldBeIndexed(status)
                && KnowledgeDocLifecycle.INDEX_INDEXED.equals(indexStatus);
    }

    /** 向量化是否失败（前端需醒目提示） */
    public boolean isIndexFailed() {
        return KnowledgeDocLifecycle.INDEX_FAILED.equals(indexStatus);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public Long getSimhash() { return simhash; }
    public void setSimhash(Long simhash) { this.simhash = simhash; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }

    public String getIndexError() { return indexError; }
    public void setIndexError(String indexError) { this.indexError = indexError; }

    public LocalDateTime getIndexedAt() { return indexedAt; }
    public void setIndexedAt(LocalDateTime indexedAt) { this.indexedAt = indexedAt; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }

    public LocalDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(LocalDateTime effectiveAt) { this.effectiveAt = effectiveAt; }

    public LocalDateTime getExpiredAt() { return expiredAt; }
    public void setExpiredAt(LocalDateTime expiredAt) { this.expiredAt = expiredAt; }

    public String getKnowledgeSource() { return knowledgeSource; }
    public void setKnowledgeSource(String knowledgeSource) { this.knowledgeSource = knowledgeSource; }

    public Long getSourceTicketId() { return sourceTicketId; }
    public void setSourceTicketId(Long sourceTicketId) { this.sourceTicketId = sourceTicketId; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
