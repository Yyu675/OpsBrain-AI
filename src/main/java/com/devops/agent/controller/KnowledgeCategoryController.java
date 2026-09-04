package com.devops.agent.controller;

import com.devops.agent.common.guard.KnowledgeWriteGuard;
import com.devops.agent.common.dto.ApiCode;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.rag.KnowledgeCategory;
import com.devops.agent.domain.rag.KnowledgeCategoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge/categories")
public class KnowledgeCategoryController {

    private final KnowledgeCategoryService service;
    /** 知识库写权限守卫（F-5）：可逆操作 ADMIN+OPS，不可逆操作仅 ADMIN */
    private final KnowledgeWriteGuard writeGuard;

    public KnowledgeCategoryController(KnowledgeCategoryService service,
                                       KnowledgeWriteGuard writeGuard) {
        this.service = service;
        this.writeGuard = writeGuard;
    }

    public record CategoryRequest(Long parentId, String name, Integer sortOrder) {}
    public record MoveDocumentRequest(Long categoryId, Integer version) {}

    @GetMapping
    public ApiResponse<List<KnowledgeCategory>> list() {
        return ApiResponse.success(service.findAll());
    }

    @GetMapping("/tree")
    public ApiResponse<Map<String, Object>> tree() {
        return ApiResponse.success(service.tree());
    }

    @PostMapping
    public ApiResponse<Object> create(@RequestBody CategoryRequest request) {
        writeGuard.requireEdit();
        try {
            return ApiResponse.success(service.create(
                    request.parentId(), request.name(), request.sortOrder()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("创建知识分类失败", e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "创建分类失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Object> update(@PathVariable Long id, @RequestBody CategoryRequest request) {
        writeGuard.requireEdit();
        try {
            return ApiResponse.success(service.update(
                    id, request.parentId(), request.name(), request.sortOrder()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("更新知识分类失败 | id={}", id, e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "更新分类失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Object> delete(@PathVariable Long id) {
        writeGuard.requireDestructive();
        try {
            service.delete(id);
            return ApiResponse.success(Map.of("id", id, "deleted", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("删除知识分类失败 | id={}", id, e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "删除分类失败: " + e.getMessage());
        }
    }

    @PutMapping("/documents/{docId}")
    public ApiResponse<Object> moveDocument(
            @PathVariable Long docId, @RequestBody MoveDocumentRequest request) {
        writeGuard.requireEdit();
        // 不再自己 catch：异常统一由 GlobalExceptionHandler 映射
        // （OptimisticLockException -> 40009、IllegalArgument/State -> 40001/40004）。
        // 这正是 F2 重构的目的；此前那次改动删了 try 却漏删 catch 链，
        // 留下了无法编译的孤儿 catch。
        service.moveDocument(docId, request.categoryId(), request.version());
        return ApiResponse.success(Map.of("id", docId, "moved", true));
    }
}
