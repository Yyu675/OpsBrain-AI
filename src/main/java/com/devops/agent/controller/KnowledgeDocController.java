package com.devops.agent.controller;

import com.devops.agent.common.guard.KnowledgeWriteGuard;
import com.devops.agent.common.dto.ApiCode;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.controller.dto.KnowledgeDocDto;
import com.devops.agent.domain.rag.KnowledgeDoc;
import com.devops.agent.domain.rag.KnowledgeDocService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识文档管理 API
 * <p>
 * 覆盖知识库的完整生命周期：草稿/发布/废弃/归档/回滚/物理删除 + 版本历史。
 * 生命周期语义见 {@code KnowledgeDocLifecycle}——尤其「删除」必须是废弃而非物理删，
 * 物理删仅限合规场景且强制理由。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-10
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge/docs")
public class KnowledgeDocController {

    private final KnowledgeDocService docService;
    /** 知识库写权限守卫（F-5）：可逆操作 ADMIN+OPS，不可逆操作仅 ADMIN */
    private final KnowledgeWriteGuard writeGuard;

    public KnowledgeDocController(KnowledgeDocService docService,
                                  KnowledgeWriteGuard writeGuard) {
        this.docService = docService;
        this.writeGuard = writeGuard;
    }

    // ==================== 创建 / 更新 ====================

    /**
     * 创建文档
     * <p>
     * 去重两道关（精确拒绝 + 近似告警）：
     * <ul>
     *   <li>内容完全相同 → 40021 拒绝，并附重复文档 ID 供跳转</li>
     *   <li>SimHash 近似（抄录检测）→ code 0 但带 nearDuplicates 告警</li>
     * </ul>
     */
    @PostMapping
    public ApiResponse<Object> create(@RequestBody KnowledgeDocDto.CreateRequest req) {
        writeGuard.requireEdit();
        try {
            KnowledgeDoc doc = new KnowledgeDoc();
            doc.setTitle(req.title());
            doc.setCategory(req.category());
            doc.setCategoryId(req.categoryId());
            doc.setAuthor(req.author());
            doc.setContent(req.content());
            doc.setSummary(req.summary());
            doc.setKnowledgeSource(req.knowledgeSource());
            // L1.5 来源回链：由工单沉淀时记录源工单，非工单沉淀时为 null
            doc.setSourceTicketId(req.sourceTicketId());
            doc.setSourceType(req.sourceType());
            doc.setEffectiveAt(req.effectiveAt());
            doc.setExpiredAt(req.expiredAt());

            KnowledgeDocService.SaveResult r
                    = docService.create(doc, req.tags(), req.publish(), "SYSTEM");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", r.docId());
            data.put("version", r.version());
            // status 是文档生命周期状态（发布/草稿），indexStatus 才是向量化状态，
            // 二者不能混用，否则前端「已发布」会被误读为「可检索」（见 6.21 状态机分离决策）
            data.put("status", req.publish() ? "PUBLISHED" : "DRAFT");
            data.put("indexStatus", r.indexOutcome().status());
            data.put("retrievable", r.indexOutcome().isRetrievable());
            data.put("nearDuplicates", r.nearDuplicates().stream()
                    .map(KnowledgeDocDto.NearDuplicate::from).toList());
            if (r.indexOutcome().status() == KnowledgeDocService.IndexOutcome.Status.FAILED) {
                data.put("indexError", r.indexOutcome().error());
            }
            return ApiResponse.success(data);

        } catch (KnowledgeDocService.DuplicateContentException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("duplicateDocId", e.getDuplicateDocId());
            data.put("duplicateTitle", e.getDuplicateTitle());
            return ApiResponse.<Object>error(ApiCode.DUPLICATE_CONTENT, e.getMessage(), data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("创建文档失败", e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "创建文档失败: " + e.getMessage());
        }
    }

    /**
     * 更新文档（带乐观锁）
     */
    @PutMapping("/{id}")
    public ApiResponse<Object> update(
            @PathVariable Long id,
            @RequestBody KnowledgeDocDto.UpdateRequest req) {
        writeGuard.requireEdit();

        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setTitle(req.title());
        patch.setCategory(req.category());
        patch.setCategoryId(req.categoryId());
        patch.setAuthor(req.author());
        patch.setContent(req.content());
        patch.setSummary(req.summary());

        KnowledgeDocService.SaveResult r = docService.update(
                id, patch, req.tags(), req.version(), "SYSTEM", req.changeReason());

        KnowledgeDoc doc = docService.findById(id, false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("version", r.version());
        data.put("status", doc != null ? doc.getStatus() : null);
        data.put("retrievable", doc != null && doc.isRetrievable());
        data.put("indexStatus", r.indexOutcome().status());
        data.put("nearDuplicates", r.nearDuplicates().stream()
                .map(KnowledgeDocDto.NearDuplicate::from).toList());
        return ApiResponse.success(data);
    }

    // ==================== 生命周期 ====================

    /**
     * 发布（草稿 → 已发布）+ 触发向量化
     */
    @PostMapping("/{id}/publish")
    public ApiResponse<Object> publish(@PathVariable Long id) {
        writeGuard.requireEdit();
        KnowledgeDocService.IndexOutcome o = docService.publish(id, "SYSTEM");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("indexStatus", o.status());
        data.put("retrievable", o.isRetrievable());
        if (o.status() == KnowledgeDocService.IndexOutcome.Status.FAILED) {
            data.put("indexError", o.error());
        }
        return ApiResponse.success(data);
    }

    /**
     * 废弃（默认「删除」语义）
     * <p>不物理删除：保留正文供历史查阅，删向量使其退出检索。</p>
     */
    @PostMapping("/{id}/deprecate")
    public ApiResponse<Object> deprecate(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, String> body) {
        writeGuard.requireDestructive();
        String reason = body != null ? body.get("reason") : null;
        docService.deprecate(id, "SYSTEM", reason);
        return ApiResponse.success(Map.of("id", id, "status", "DEPRECATED"));
    }

    /**
     * 回滚到历史版本
     */
    @PostMapping("/{id}/restore")
    public ApiResponse<Object> restore(@PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        writeGuard.requireEdit();
        int version = ((Number) body.get("version")).intValue();
        KnowledgeDocService.SaveResult r = docService.restore(id, version, "SYSTEM");
        return ApiResponse.success(Map.of(
                "id", id,
                "version", r.version(),
                "retrievable", r.indexOutcome().isRetrievable()));
    }

    /**
     * 物理删除
     * <p><b>仅限合规场景</b>，必须提供 complianceReason，否则拒绝。
     * 默认下架请用 /deprecate。</p>
     */
    @DeleteMapping("/{id}/purge")
    @cn.dev33.satoken.annotation.SaCheckRole("ADMIN")   // 方向 F：物理删除不可逆，限管理员
    public ApiResponse<Object> purge(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("complianceReason") : null;
        docService.purge(id, "SYSTEM", reason);
        return ApiResponse.success(Map.of("id", id, "deleted", true));
    }

    // ==================== 查询 ====================

    /**
     * 分页查询
     */
    @GetMapping
    public ApiResponse<KnowledgeDocDto.DocPage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "UPDATED_DESC") String sort) {

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        List<KnowledgeDoc> docs = docService.findPage(
                safePage, safeSize, status, category, keyword, tag, sort);
        long total = docService.countByQuery(status, category, keyword, tag);

        // 用 record 而非 Map（P0-2 第二步）：Map 让 OpenAPI 只能生成
        // additionalProperties:true，前端拿不到类型；且 data.put("totalElements", ...)
        // 改个键名不会有编译信号，只会让列表悄悄渲染成空。
        return ApiResponse.success(KnowledgeDocDto.DocPage.of(
                docs.stream().map(KnowledgeDocDto.ListItem::from).toList(),
                total, safePage, safeSize));
    }

    /**
     * 扁平分类聚合（侧栏导航，全库跨页）
     */
    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> categories() {
        return ApiResponse.success(docService.findCategories());
    }

    /**
     * 热门标签（仅 PUBLISHED 文档计数，全库跨页）
     */
    @GetMapping("/tags/hot")
    public ApiResponse<List<Map<String, Object>>> hotTags(
            @RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.min(Math.max(1, limit), 100);
        List<Map<String, Object>> rows = docService.findHotTags(safeLimit)
                .entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tag", e.getKey());
                    row.put("count", e.getValue());
                    return row;
                }).toList();
        return ApiResponse.success(rows);
    }

    /**
     * 文档详情（含正文）
     */
    @GetMapping("/{id}")
    public ApiResponse<Object> detail(@PathVariable Long id) {
        KnowledgeDoc doc = docService.findById(id, true);
        if (doc == null) {
            return ApiResponse.error(ApiCode.NOT_FOUND, "文档不存在");
        }
        return ApiResponse.success(KnowledgeDocDto.Detail.from(doc));
    }

    /**
     * 按源工单反查已沉淀的文档（L1.5 来源回链）
     * <p>供工单详情页展示「已沉淀为知识」徽标与跳转入口。</p>
     */
    @GetMapping("/by-source-ticket/{ticketId}")
    public ApiResponse<Object> bySourceTicket(@PathVariable Long ticketId) {
        List<KnowledgeDoc> docs = docService.findBySourceTicketId(ticketId);
        List<KnowledgeDocDto.ListItem> items = docs.stream()
                .map(KnowledgeDocDto.ListItem::from).toList();
        return ApiResponse.success(items);
    }

    /**
     * 版本历史列表（不含正文，需正文用 /{id}/versions/{version}）
     */
    @GetMapping("/{id}/versions")
    public ApiResponse<Object> versions(@PathVariable Long id) {
        List<Map<String, Object>> list = docService.listVersions(id);
        return ApiResponse.success(Map.of("docId", id, "versions", list));
    }

    /**
     * 版本对比（行级差异）
     * <p>
     * 对照两个历史版本原文，返回三段式差异（EQUAL/DELETE/INSERT），
     * 前端 diff 视图渲染用。参数宽松：fromV ≥ toV 时自动交换。切片级 diff
     * 不可行（6.21 已论证），故对原文逐行做文档级 LCS diff。
     * </p>
     *
     * @param fromV 旧版本号
     * @param toV   新版本号
     */
    @GetMapping("/{id}/compare")
    public ApiResponse<Object> compare(
            @PathVariable Long id,
            @RequestParam int fromV,
            @RequestParam int toV) {

        // 参数归一化：容忍 fromV >= toV、负值，统一交换为标准顺序
        int from = Math.min(fromV, toV);
        int to = Math.max(fromV, toV);
        if (from < 1) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, "版本号必须为正整数");
        }

        try {
            KnowledgeDocService.VersionDiffData data = docService.compareVersions(id, from, to);

            List<KnowledgeDocDto.DiffSegmentDto> segments = data.segments().stream()
                    .map(s -> new KnowledgeDocDto.DiffSegmentDto(
                            s.type().name(), s.lines()))
                    .toList();

            KnowledgeDocDto.VersionDiffResult result = new KnowledgeDocDto.VersionDiffResult(
                    from, to,
                    data.from().getTitle(), data.to().getTitle(),
                    segments);

            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ApiCode.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("版本对比失败 | id={} | from={} | to={}", id, from, to, e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "对比失败: " + e.getMessage());
        }
    }

    /**
     * 取指定历史版本全文
     */
    @GetMapping("/{id}/versions/{version}")
    public ApiResponse<Object> version(
            @PathVariable Long id, @PathVariable int version) {
        KnowledgeDoc doc = docService.findVersion(id, version);
        if (doc == null) {
            return ApiResponse.error(ApiCode.NOT_FOUND, "历史版本不存在");
        }
        return ApiResponse.success(KnowledgeDocDto.Detail.from(doc));
    }

    /**
     * 手动触发向量化重试（针对 index_status=FAILED/PENDING 的文档）
     */
    @PostMapping("/reindex/pending")
    public ApiResponse<Object> retryIndexing(@RequestParam(defaultValue = "20") int limit) {
        writeGuard.requireDestructive();
        int succeeded = docService.retryFailedIndexing(limit);
        return ApiResponse.success(Map.of("retried", succeeded));
    }
}
