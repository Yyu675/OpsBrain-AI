package com.devops.agent.controller;

import com.devops.agent.common.guard.KnowledgeWriteGuard;
import com.devops.agent.common.dto.ApiCode;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.rag.KnowledgeTag;
import com.devops.agent.domain.rag.KnowledgeTagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge/tags")
public class KnowledgeTagController {
    private final KnowledgeTagService tagService;
    /** 知识库写权限守卫（F-5）：可逆操作 ADMIN+OPS，不可逆操作仅 ADMIN */
    private final KnowledgeWriteGuard writeGuard;

    public KnowledgeTagController(KnowledgeTagService tagService,
                                  KnowledgeWriteGuard writeGuard) {
        this.tagService = tagService;
        this.writeGuard = writeGuard;
    }

    public record TagRequest(String name, String description, String color) {}
    public record MergeRequest(Long targetId) {}
    public record DeleteRequest(Long replacementId) {}

    @GetMapping
    public ApiResponse<List<KnowledgeTag>> list() {
        return ApiResponse.success(tagService.findAll());
    }

    @PostMapping
    public ApiResponse<Object> create(@RequestBody TagRequest request) {
        writeGuard.requireEdit();
        return ApiResponse.success(tagService.create(request.name(), request.description(), request.color()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Object> rename(@PathVariable long id, @RequestBody TagRequest request) {
        writeGuard.requireEdit();
        try {
            return ApiResponse.success(tagService.rename(id, request.name(), request.description(), request.color()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("更新知识标签失败 | id={}", id, e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "更新标签失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/merge")
    public ApiResponse<Object> merge(@PathVariable long id, @RequestBody MergeRequest request) {
        writeGuard.requireEdit();
        try {
            return ApiResponse.success(tagService.merge(id, request.targetId()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Object> delete(@PathVariable long id, @RequestBody(required = false) DeleteRequest request) {
        writeGuard.requireDestructive();
        try {
            tagService.delete(id, request == null ? null : request.replacementId());
            return ApiResponse.success(Map.of("id", id, "deleted", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        }
    }
}
