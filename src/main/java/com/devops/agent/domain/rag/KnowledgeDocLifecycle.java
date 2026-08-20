package com.devops.agent.domain.rag;

/**
 * RAG 知识库生命周期语义
 * <p>
 * 本文件是文档形式的设计决策记录。RAG 的「增删改」远比普通 CRUD 复杂，
 * 把语义写清楚能避免后来者按直觉实现出错误行为。
 * </p>
 * @author OpsBrain AI
 * @since 2026-08-10
 */
public final class KnowledgeDocLifecycle {

    private KnowledgeDocLifecycle() {
    }

    // ==================== 文档状态 ====================

    /** 草稿：不向量化、不检索 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 已发布：参与检索 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 已废弃：不检索，保留正文供历史查阅，向量已删 */
    public static final String STATUS_DEPRECATED = "DEPRECATED";
    /** 已归档：正文转对象存储 */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    // ==================== 向量化状态 ====================

    /** 待向量化 */
    public static final String INDEX_PENDING = "PENDING";
    /** 已建索引，可检索 */
    public static final String INDEX_INDEXED = "INDEXED";
    /** 向量化失败，不可检索——须能与「文档未发布」区分 */
    public static final String INDEX_FAILED = "FAILED";
    /** 无需索引（草稿/已废弃） */
    public static final String INDEX_SKIPPED = "SKIPPED";

    // ==================== 变更类型 ====================

    public static final String CHANGE_CREATE = "CREATE";
    public static final String CHANGE_UPDATE = "UPDATE";
    public static final String CHANGE_DEPRECATE = "DEPRECATE";
    public static final String CHANGE_RESTORE = "RESTORE";

    /**
     * 该状态是否应参与检索
     */
    public static boolean shouldBeIndexed(String status) {
        return STATUS_PUBLISHED.equals(status);
    }
}