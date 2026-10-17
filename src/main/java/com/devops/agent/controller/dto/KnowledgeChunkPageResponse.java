package com.devops.agent.controller.dto;

import com.devops.agent.domain.rag.KnowledgeChunkView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "知识切片分页响应")
public record KnowledgeChunkPageResponse(
        @Schema(description = "切片列表")
        List<KnowledgeChunkView> list,
        @Schema(description = "切片总记录数")
        long total,
        @Schema(description = "总页数")
        int totalPages,
        @Schema(description = "当前页码")
        int page,
        @Schema(description = "每页记录数")
        int size
) {
}
