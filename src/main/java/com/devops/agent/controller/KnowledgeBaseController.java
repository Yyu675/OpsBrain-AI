package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiCode;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.common.guard.KnowledgeWriteGuard;
import com.devops.agent.controller.dto.KnowledgeBaseDto;
import com.devops.agent.domain.rag.KnowledgeBase;
import com.devops.agent.domain.rag.KnowledgeBaseService;
import com.devops.agent.domain.rag.KnowledgeDocService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理 API（顶层实体）。
 * <p>
 * 知识库是切片参数的挂载点（见 {@code docs/09-decisions/} 对标决策）：
 * 不同业务域的文档（SOP 长文 vs 故障 FAQ）可按库定制切片粒度。
 * </p>
 * <p>
 * 权限：查询不限角色；新建/编辑走 {@code requireEdit}（ADMIN+OPS）；
 * 全库重建索引走 {@code requireDestructive}（仅 ADMIN）——它会打满
 * embedding 配额，与既有「全量重建」同级。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge/bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final KnowledgeDocService docService;
    private final KnowledgeWriteGuard writeGuard;

    public KnowledgeBaseController(KnowledgeBaseService kbService,
                                   KnowledgeDocService docService,
                                   KnowledgeWriteGuard writeGuard) {
        this.kbService = kbService;
        this.docService = docService;
        this.writeGuard = writeGuard;
    }

    /**
     * 知识库列表（含文档计数、索引健康度与生效切片参数）。
     * <p>数量级为个位数~几十，不分页；前端侧栏选择器与管理页共用。</p>
     */
    @GetMapping
    public ApiResponse<List<KnowledgeBaseDto.Item>> list() {
        // 索引健康度一次 GROUP BY 取全量，避免逐库 COUNT 的 N+1
        var stats = kbService.docStatsByBase();
        List<KnowledgeBaseDto.Item> items = kbService.findAll().stream()
                .map(kb -> {
                    var s = stats.get(kb.getId());
                    return KnowledgeBaseDto.Item.from(kb,
                            s != null ? s.total() : 0,
                            s != null ? s.indexed() : 0,
                            s != null ? s.failed() : 0);
                })
                .toList();
        return ApiResponse.success(items);
    }

    /**
     * 新建知识库
     */
    @PostMapping
    public ApiResponse<Object> create(@RequestBody KnowledgeBaseDto.CreateRequest req) {
        writeGuard.requireEdit();
        try {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setName(req.name());
            kb.setCode(req.code());
            kb.setDescription(req.description());
            kb.setParentChunkSize(req.parentChunkSize());
            kb.setChildChunkSize(req.childChunkSize());
            kb.setChunkOverlap(req.chunkOverlap());

            KnowledgeBase saved = kbService.create(kb);
            return ApiResponse.success(KnowledgeBaseDto.Item.from(saved, 0));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("创建知识库失败", e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "创建知识库失败: " + e.getMessage());
        }
    }

    /**
     * 更新知识库（null 字段不修改；clearChunkParams=true 清空切片参数回全局默认）
     * <p>
     * 注意：切片参数变更只影响之后的索引。要让存量文档按新参数重切，
     * 须显式调用 {@code POST /{id}/reindex-all}。
     * </p>
     */
    @PutMapping("/{id}")
    public ApiResponse<Object> update(@PathVariable Long id,
                                      @RequestBody KnowledgeBaseDto.UpdateRequest req) {
        writeGuard.requireEdit();
        try {
            if (req.clearChunkParams()
                    && (req.parentChunkSize() != null || req.childChunkSize() != null
                        || req.chunkOverlap() != null)) {
                return ApiResponse.error(ApiCode.BAD_REQUEST,
                        "clearChunkParams 与切片参数互斥：要么清空回默认，要么显式设置");
            }
            KnowledgeBase patch = new KnowledgeBase();
            patch.setName(req.name());
            patch.setCode(req.code());
            patch.setDescription(req.description());
            patch.setParentChunkSize(req.parentChunkSize());
            patch.setChildChunkSize(req.childChunkSize());
            patch.setChunkOverlap(req.chunkOverlap());
            patch.setStatus(req.status());

            KnowledgeBase saved = kbService.update(id, patch, req.clearChunkParams());
            return ApiResponse.success(KnowledgeBaseDto.Item.from(saved, kbService.countDocs(id)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(ApiCode.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("更新知识库失败 | id={}", id, e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "更新知识库失败: " + e.getMessage());
        }
    }

    /**
     * 重建库内全部已发布文档的索引（让新切片参数对存量生效）。
     * <p>
     * 逐文档执行「删旧切片 → 按库参数重切 → 向量化 → 写入」，单篇失败
     * 不阻断其余（失败文档落 index_status=FAILED，可经
     * {@code /docs/reindex/pending} 补偿）。返回成功/失败明细。
     * </p>
     * <p><b>成本提醒</b>：每篇文档至少一次 embedding 远程调用，
     * 大库执行前请确认配额。</p>
     */
    @PostMapping("/{id}/reindex-all")
    public ApiResponse<Object> reindexAll(@PathVariable Long id) {
        writeGuard.requireDestructive();
        KnowledgeBase kb = kbService.findById(id);
        if (kb == null) {
            return ApiResponse.error(ApiCode.NOT_FOUND, "知识库不存在: " + id);
        }
        Map<String, Object> result = docService.reindexByKnowledgeBase(id);
        result.put("kbId", id);
        result.put("kbName", kb.getName());
        return ApiResponse.success(result);
    }
}
