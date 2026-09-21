package com.devops.agent.domain.rag;

import java.time.LocalDateTime;

/**
 * 知识库（顶层实体，切片参数的挂载点）。
 * <p>
 * 对应表 {@code sys_knowledge_base}。层级：
 * knowledge_base → knowledge_doc → knowledge_chunk。
 * </p>
 * <p>
 * 切片三参数（{@code parentChunkSize}/{@code childChunkSize}/{@code chunkOverlap}）
 * 为 null 表示跟随全局默认（{@link ChunkProfile#DEFAULT}），逐字段独立回落——
 * 允许只覆盖其中一个参数（如只把 FAQ 库的子段落调小）。
 * </p>
 * <p>
 * 参数变更<b>不自动重建</b>库内存量文档的切片（全库重嵌是付费 API 成本），
 * 只对之后的索引生效；要让存量生效须显式调重建入口
 * （{@code KnowledgeDocService.reindexByKnowledgeBase}）。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
public class KnowledgeBase {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    /** 默认知识库的业务编码：V2 迁移承接全部存量文档，应用层回落键。勿改。 */
    public static final String CODE_DEFAULT = "default";

    private Long id;
    private String name;
    private String code;
    private String description;

    private Integer parentChunkSize;
    private Integer childChunkSize;
    private Integer chunkOverlap;

    /** ACTIVE / DISABLED（DISABLED 的库不接受新文档，存量文档仍可检索） */
    private String status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getParentChunkSize() { return parentChunkSize; }
    public void setParentChunkSize(Integer parentChunkSize) { this.parentChunkSize = parentChunkSize; }

    public Integer getChildChunkSize() { return childChunkSize; }
    public void setChildChunkSize(Integer childChunkSize) { this.childChunkSize = childChunkSize; }

    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    /** 合并全局默认后的完整切片参数（校验也在此完成） */
    public ChunkProfile resolveProfile() {
        return ChunkProfile.ofNullable(parentChunkSize, childChunkSize, chunkOverlap);
    }
}
