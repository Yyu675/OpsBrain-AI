package com.devops.agent.controller.dto;

import com.devops.agent.domain.rag.KnowledgeDoc;
import com.devops.agent.domain.rag.KnowledgeDocLifecycle;
import com.devops.agent.domain.rag.KnowledgeDocService.IndexOutcome;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文档请求/响应 DTO
 * <p>
 * 与实体解耦：前端只需关注业务字段，不必接触
 * {@code content_hash}/{@code simhash}/{@code index_status} 等内部实现细节。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-10
 */
public class KnowledgeDocDto {

    // ==================== 请求 DTO ====================

    /**
     * 创建文档请求
     *
     * @param publish true=发布（立即向量化，可检索）；false=存草稿
     */
    public record CreateRequest(
            String title,
            String category,
            Long categoryId,
            String author,
            String content,
            String summary,
            List<String> tags,
            boolean publish,
            String knowledgeSource,
            LocalDateTime effectiveAt,
            LocalDateTime expiredAt,
            /** L1.5 来源回链：由工单沉淀时传源工单 ID */
            Long sourceTicketId,
            /** 来源类型：TICKET / MANUAL / IMPORT 等 */
            String sourceType
    ) {}

    /**
     * 更新文档请求
     * <p>
     * null 字段不更新（读取-合并-写回）。

     * @param version 乐观锁版本号，须传入读取时的值；null 放弃并发保护
     * @param tags null=不改标签；空数组=清空
     */
    public record UpdateRequest(
            String title,
            String category,
            Long categoryId,
            String author,
            String content,
            String summary,
            List<String> tags,
            Integer version,
            String changeReason
    ) {}

    // ==================== 响应 DTO ====================

    /**
     * 文档精简视图（列表页用，不含 content 正文）
     */
    public record ListItem(
            Long id,
            String title,
            String category,
            Long categoryId,
            String author,
            String summary,
            Integer version,
            String status,
            String indexStatus,
            Integer chunkCount,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            List<String> tags,
            /** L1.5 来源回链：源工单 ID，非工单沉淀为 null */
            Long sourceTicketId,
            String sourceType
    ) {
        public static ListItem from(KnowledgeDoc d) {
            return new ListItem(
                    d.getId(),
                    d.getTitle(),
                    d.getCategory(),
                    d.getCategoryId(),
                    d.getAuthor(),
                    d.getSummary(),
                    d.getVersion(),
                    d.getStatus(),
                    d.getIndexStatus(),
                    d.getChunkCount(),
                    d.getCreateTime(),
                    d.getUpdateTime(),
                    d.getTags() != null ? d.getTags() : List.of(),
                    d.getSourceTicketId(),
                    d.getSourceType());
        }
    }

    /**
     * 文档详情视图（含正文）
     */
    public record Detail(
            Long id,
            String title,
            String category,
            Long categoryId,
            String author,
            String content,
            String summary,
            Integer version,
            String status,
            String indexStatus,
            String indexError,
            Integer chunkCount,
            LocalDateTime indexedAt,
            LocalDateTime effectiveAt,
            LocalDateTime expiredAt,
            String knowledgeSource,
            /** L1.5 来源回链：源工单 ID，非工单沉淀时为 null */
            Long sourceTicketId,
            /** 来源类型：TICKET / MANUAL / IMPORT 等 */
            String sourceType,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            List<String> tags,
            /** 是否可检索：status=PUBLISHED 且 index=INDEXED */
            boolean retrievable
    ) {
        public static Detail from(KnowledgeDoc d) {
            return new Detail(
                    d.getId(), d.getTitle(), d.getCategory(), d.getCategoryId(), d.getAuthor(),
                    d.getContent(), d.getSummary(), d.getVersion(),
                    d.getStatus(), d.getIndexStatus(), d.getIndexError(),
                    d.getChunkCount(), d.getIndexedAt(),
                    d.getEffectiveAt(), d.getExpiredAt(),
                    d.getKnowledgeSource(),
                    d.getSourceTicketId(), d.getSourceType(),
                    d.getCreateTime(), d.getUpdateTime(),
                    d.getTags() != null ? d.getTags() : List.of(),
                    d.isRetrievable());
        }
    }

    /**
     * 保存结果（创建/更新通用）
     */
    public record SaveResult(
            Long id,
            Integer version,
            String status,
            String indexStatus,
            boolean retrievable,
            /** 近似重复告警（不阻断创建） */
            List<NearDuplicate> nearDuplicates,
            /** 向量化结果 */
            IndexOutcome indexOutcome
    ) {
        public static SaveResult from(Long id, Integer version, KnowledgeDoc d,
                                      List<NearDuplicate> dups,
                                      com.devops.agent.domain.rag.KnowledgeDocService.IndexOutcome outcome) {
            return new SaveResult(
                    id, version,
                    d != null ? d.getStatus() : null,
                    outcome != null ? outcome.status().name() : null,
                    d != null && d.isRetrievable(),
                    dups,
                    outcome != null ? IndexOutcome.from(outcome) : null);
        }
    }

    public record NearDuplicate(Long docId, String title, int distance) {
        public static NearDuplicate from(com.devops.agent.domain.rag.KnowledgeDocService.NearDuplicate n) {
            return new NearDuplicate(n.docId(), n.title(), n.distance());
        }
    }

    public record IndexOutcome(String status, int chunkCount, int dedupedCount, String error) {
        public static IndexOutcome from(com.devops.agent.domain.rag.KnowledgeDocService.IndexOutcome o) {
            return new IndexOutcome(o.status().name(), o.chunkCount(), o.dedupedCount(), o.error());
        }
    }

    /**
     * 历史版本项（不含正文）
     */
    public record HistoryItem(
            Long docId,
            Integer version,
            String title,
            String category,
            String author,
            int contentLength,
            String changeType,
            String changedBy,
            String changeReason,
            LocalDateTime createTime
    ) {}

    // ==================== 版本对比 DTO ====================

    /**
     * 版本对比结果
     *
     * @param fromVersion 旧版本号
     * @param toVersion   新版本号
     * @param fromTitle   旧版本标题
     * @param toTitle     新版本标题
     * @param segments    差异段列表
     */
    public record VersionDiffResult(
            int fromVersion,
            int toVersion,
            String fromTitle,
            String toTitle,
            List<DiffSegmentDto> segments
    ) {}

    /**
     * 差异段
     *
     * @param type  "EQUAL" | "DELETE" | "INSERT"
     * @param lines 该段包含的行
     */
    public record DiffSegmentDto(
            String type,
            List<String> lines
    ) {}

    // ==================== 工厂（含状态文案） ====================

    /**
     * 状态中文文案，供前端直接展示，避免各处重复 mapping
     */
    public static String statusLabel(String status) {
        if (status == null) return "";
        return switch (status) {
            case KnowledgeDocLifecycle.STATUS_DRAFT -> "草稿";
            case KnowledgeDocLifecycle.STATUS_PUBLISHED -> "已发布";
            case KnowledgeDocLifecycle.STATUS_DEPRECATED -> "已废弃";
            case KnowledgeDocLifecycle.STATUS_ARCHIVED -> "已归档";
            default -> status;
        };
    }

    /**
     * 索引状态中文文案
     */
    public static String indexStatusLabel(String indexStatus) {
        if (indexStatus == null) return "";
        return switch (indexStatus) {
            case KnowledgeDocLifecycle.INDEX_PENDING -> "待向量化";
            case KnowledgeDocLifecycle.INDEX_INDEXED -> "已建索引";
            case KnowledgeDocLifecycle.INDEX_FAILED -> "向量化失败";
            case KnowledgeDocLifecycle.INDEX_SKIPPED -> "无需索引";
            default -> indexStatus;
        };
    }

    // ==================== 分页响应 DTO（P0-2 第二步）====================

    /**
     * 知识文档分页列表响应。
     *
     * <p>对应 {@code GET /api/v1/knowledge/docs}。</p>
     *
     * <h3>字段名与工单列表不同，这是刻意保留的</h3>
     * <p>
     * 本响应用 {@code content}/{@code totalElements}/{@code currentPage}/{@code pageSize}，
     * 而工单列表用 {@code tickets}/{@code total}/{@code page}/{@code size}。
     * 两套命名不一致<b>看起来该统一，但不能改</b>——
     * 前端 {@code stores/knowledge.ts} 逐字段读取这四个键
     * （{@code result.content} / {@code result.totalElements} …），
     * 改名不会有任何编译错误，只会让知识库列表<b>渲染成空</b>。
     * </p>
     * <p>
     * 统一命名要前后端一起改并同时发版，属于独立的破坏性变更，
     * 不应混在「Map 换 record」这类零行为改动里做。
     * </p>
     *
     * @param content       当前页文档（已转为 {@link ListItem}，不含正文）
     * @param totalElements 符合筛选条件的总数
     * @param totalPages    总页数，由 {@code totalElements} 与 {@code pageSize} 推导
     * @param currentPage   生效页码（已钳制）
     * @param pageSize      生效每页大小（已钳制，上限 200）
     */
    public record DocPage(
            List<ListItem> content,
            long totalElements,
            int totalPages,
            int currentPage,
            int pageSize
    ) {
        /**
         * 由查询结果构造，总页数在此统一推导。
         *
         * <p>与 {@code TicketDto.TicketPage.of} 同一口径：
         * {@code Math.ceil} 的整数除法陷阱只在一处写对。</p>
         */
        public static DocPage of(List<ListItem> content, long totalElements,
                                 int currentPage, int pageSize) {
            // pageSize 由调用方钳制为 ≥1，此处不兜底：
            // 真为 0 应当暴露为除零异常，而不是静默给出错误页数
            int totalPages = (int) Math.ceil((double) totalElements / pageSize);
            return new DocPage(content, totalElements, totalPages, currentPage, pageSize);
        }
    }
}
