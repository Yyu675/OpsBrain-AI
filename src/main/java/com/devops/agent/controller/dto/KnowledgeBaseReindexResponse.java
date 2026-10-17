package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "知识库批量重切索引响应")
public record KnowledgeBaseReindexResponse(
        @Schema(description = "知识库 ID")
        long kbId,

        @Schema(description = "知识库名称")
        String kbName,

        @Schema(description = "待重切文档总数")
        int total,

        @Schema(description = "成功切片入库数")
        int success,

        @Schema(description = "失败数")
        int failed,

        @Schema(description = "失败详情列表")
        List<Map<String, Object>> failures
) {
}
