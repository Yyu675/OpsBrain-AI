package com.devops.agent.controller;

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

    public KnowledgeCategoryController(KnowledgeCategoryService service) {
        this.service = service;
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
        try {
            return ApiResponse.success(service.create(
                    request.parentId(), request.name(), request.sortOrder()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("创建知识分类失败", e);
            return ApiResponse.error(50001, "创建分类失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Object> update(@PathVariable Long id, @RequestBody CategoryRequest request) {
        try {
            return ApiResponse.success(service.update(
                    id, request.parentId(), request.name(), request.sortOrder()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("更新知识分类失败 | id={}", id, e);
            return ApiResponse.error(50001, "更新分类失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Object> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ApiResponse.success(Map.of("id", id, "deleted", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("删除知识分类失败 | id={}", id, e);
            return ApiResponse.error(50001, "删除分类失败: " + e.getMessage());
        }
    }

    @PutMapping("/documents/{docId}")
    public ApiResponse<Object> moveDocument(
            @PathVariable Long docId, @RequestBody MoveDocumentRequest request) {
        service.moveDocument(docId, request.categoryId(), request.version());
        return ApiResponse.success(Map.of("id", docId, "moved", true)); catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("移动知识文档失败 | docId={}", docId, e);
            return ApiResponse.error(50001, "移动文档失败: " + e.getMessage());
        }
    }
}
