package com.devops.agent.controller;

import com.devops.agent.common.guard.KnowledgeWriteGuard;
import com.devops.agent.common.dto.ApiCode;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.controller.dto.KnowledgeDocDto;
import com.devops.agent.domain.rag.KnowledgeDoc;
import com.devops.agent.domain.rag.KnowledgeDocService;
import com.devops.agent.domain.biz.repository.KnowledgeBoostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
    private final com.devops.agent.domain.rag.KnowledgeUploadService uploadService;
    /** 知识库写权限守卫（F-5）：可逆操作 ADMIN+OPS，不可逆操作仅 ADMIN */
    private final KnowledgeWriteGuard writeGuard;
    /** 引用反馈回流（P0）：citation → chunk 反查 + boost 落账 */
    private final KnowledgeBoostRepository boostRepository;

    public KnowledgeDocController(KnowledgeDocService docService,
                                  com.devops.agent.domain.rag.KnowledgeUploadService uploadService,
                                  KnowledgeWriteGuard writeGuard,
                                  KnowledgeBoostRepository boostRepository) {
        this.docService = docService;
        this.uploadService = uploadService;
        this.writeGuard = writeGuard;
        this.boostRepository = boostRepository;
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
            doc.setKbId(req.kbId());
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
     * 批量导入文档（批88 P1：知识库冷启动通道）
     *
     * <p>POST /api/v1/knowledge/docs/batch-import</p>
     *
     * <p>一次性提交 N 篇文档，每篇独立调用 create（去重/SimHash/向量化全生效），
     * 单条失败不阻断其余——返回每条的成功/失败明细，供调用方复核。</p>
     *
     * <p>知识库冷启动场景：从 Confluence/Wiki/旧系统导出 Markdown 后
     * 一次性灌入，无需逐篇手动创建。</p>
     */
    @PostMapping("/batch-import")
    public ApiResponse<Object> batchImport(@RequestBody KnowledgeDocDto.BatchImportRequest req) {
        writeGuard.requireEdit();
        if (req == null || req.items() == null || req.items().isEmpty()) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, "批量导入条目不能为空");
        }

        int successCount = 0;
        int failCount = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        for (int i = 0; i < req.items().size(); i++) {
            KnowledgeDocDto.CreateRequest item = req.items().get(i);
            try {
                KnowledgeDoc doc = new KnowledgeDoc();
                doc.setTitle(item.title());
                doc.setCategory(item.category());
                doc.setCategoryId(item.categoryId());
                doc.setAuthor(item.author());
                doc.setContent(item.content());
                doc.setSummary(item.summary());
                doc.setKnowledgeSource(item.knowledgeSource());
                doc.setSourceTicketId(item.sourceTicketId());
                doc.setSourceType(item.sourceType() != null ? item.sourceType() : "IMPORT");
                doc.setKbId(item.kbId());
                doc.setEffectiveAt(item.effectiveAt());
                doc.setExpiredAt(item.expiredAt());

                KnowledgeDocService.SaveResult r
                        = docService.create(doc, item.tags(), req.publish(), "BATCH_IMPORT");

                Map<String, Object> itemResult = new LinkedHashMap<>();
                itemResult.put("index", i);
                itemResult.put("title", item.title());
                itemResult.put("status", "SUCCESS");
                itemResult.put("docId", r.docId());
                itemResult.put("indexStatus", r.indexOutcome().status());
                itemResult.put("retrievable", r.indexOutcome().isRetrievable());
                results.add(itemResult);
                successCount++;

            } catch (KnowledgeDocService.DuplicateContentException e) {
                Map<String, Object> itemResult = new LinkedHashMap<>();
                itemResult.put("index", i);
                itemResult.put("title", item.title());
                itemResult.put("status", "SKIPPED_DUPLICATE");
                itemResult.put("duplicateDocId", e.getDuplicateDocId());
                itemResult.put("duplicateTitle", e.getDuplicateTitle());
                results.add(itemResult);
                failCount++;

            } catch (Exception e) {
                // 单条失败不阻断整批，但必须留下日志——否则运维只能看到
                // 结果里的 FAILED 计数，没有任何指向失败原因的现场证据
                log.warn("⚠️ [KnowledgeDoc] 批量导入单条失败 | index={} | title={} | {}",
                        i, item.title(), e.getMessage());
                Map<String, Object> itemResult = new LinkedHashMap<>();
                itemResult.put("index", i);
                itemResult.put("title", item.title());
                itemResult.put("status", "FAILED");
                itemResult.put("error", e.getMessage());
                results.add(itemResult);
                failCount++;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", req.items().size());
        data.put("success", successCount);
        data.put("failed", failCount);
        data.put("results", results);
        return ApiResponse.success(data);
    }

    /**
     * 上传文件入库（V2：二进制文档解析通道）
     *
     * <p>POST /api/v1/knowledge/docs/upload（multipart/form-data）</p>
     *
     * <p>PDF/Word/Excel/PPT/TXT/Markdown 经 Tika 解析出纯文本后，
     * 走与手工新建完全相同的链路（清洗/去重/SimHash/向量化）；
     * 原件留存 MinIO 供审计与重解析。同步处理：发布即索引，
     * 超时或失败由既有 {@code index_status} 状态机 + 重试端点兜底。</p>
     *
     * @param file    上传文件（必填，≤20MB，类型白名单见 KnowledgeUploadService）
     * @param title   文档标题（可选，默认取文件名去扩展名）
     * @param publish true=发布并向量化；false=存草稿
     */
    @PostMapping("/upload")
    public ApiResponse<Object> upload(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "true") boolean publish,
            @RequestParam(required = false) String knowledgeSource) {
        writeGuard.requireEdit();
        try {
            com.devops.agent.domain.rag.KnowledgeUploadService.UploadResult r =
                    uploadService.upload(file, title, kbId, categoryId, category,
                            author, tags, publish, knowledgeSource, "UPLOAD");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", r.docId());
            data.put("title", r.title());
            data.put("version", r.version());
            data.put("status", publish ? "PUBLISHED" : "DRAFT");
            data.put("indexStatus", r.indexOutcome().status());
            data.put("retrievable", r.indexOutcome().isRetrievable());
            data.put("parsedLength", r.parsedLength());
            data.put("originalStored", r.objectKey() != null);
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
            log.error("上传文件入库失败", e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "上传文件入库失败: " + e.getMessage());
        }
    }

    /**
     * 下载上传文档的原件（预签名 URL，V2）
     *
     * <p>GET /api/v1/knowledge/docs/{id}/original-url</p>
     *
     * <p>仅对 sourceType=UPLOAD 且原件留存成功的文档有意义：
     * 手工录入或上传时对象存储降级（originalStored=false）的文档
     * 没有原件，如实返回 40400 而非伪造一个空文件。</p>
     */
    @GetMapping("/{id}/original-url")
    public ApiResponse<Object> originalUrl(@PathVariable Long id) {
        try {
            com.devops.agent.domain.rag.KnowledgeUploadService.OriginalDownload download =
                    uploadService.presignOriginalUrl(id);
            if (download == null) {
                return ApiResponse.error(ApiCode.NOT_FOUND, "文档不存在");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", download.url());
            data.put("filename", download.filename());
            data.put("expiresInSeconds", download.expiresInSeconds());
            return ApiResponse.success(data);
        } catch (IllegalStateException e) {
            // 无留存原件 / 签名失败——消息可直接展示
            return ApiResponse.error(ApiCode.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("获取原件下载链接失败 | docId={}", id, e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "获取原件下载链接失败: " + e.getMessage());
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
        // 换库：非 null 即视为显式变更（会触发重建索引）；null=保持原归属
        patch.setKbId(req.kbId());

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
                                       @RequestBody(required = false) Map<String, Object> body) {
        writeGuard.requireEdit();
        // 回滚目标版本是必填项：不传/非数字直接 400 参数错误而非 50001——
        // NPE 堆栈对排障无益，调用方拿到明确提示才能自纠
        Object raw = body == null ? null : body.get("version");
        if (!(raw instanceof Number version)) {
            throw new IllegalArgumentException("version 是必填字段（要回滚到的历史版本号）");
        }
        KnowledgeDocService.SaveResult r = docService.restore(id, version.intValue(), "SYSTEM");
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
            @RequestParam(required = false) Long kbId,
            @RequestParam(defaultValue = "UPDATED_DESC") String sort) {

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        List<KnowledgeDoc> docs = docService.findPage(
                safePage, safeSize, status, category, keyword, tag, sort, kbId);
        long total = docService.countByQuery(status, category, keyword, tag, kbId);

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
     * <p>供工单详情页展示「已沉淀为知识」徽标与跳转入口。
     * 工单号是字符串流水号（TKT-…），路径变量不能按 Long 解析。</p>
     */
    @GetMapping("/by-source-ticket/{ticketId}")
    public ApiResponse<Object> bySourceTicket(@PathVariable String ticketId) {
        List<KnowledgeDoc> docs = docService.findBySourceTicketId(ticketId);
        List<KnowledgeDocDto.ListItem> items = docs.stream()
                .map(KnowledgeDocDto.ListItem::from).toList();
        return ApiResponse.success(items);
    }

    /**
     * 聊天答案引用反馈（P0 2026-09-24）：用户给带引用的答案点「有用/没用」时，
     * 后端把引用 citation 反查成被引 chunk 并回流检索权重（boost）。
     * <p>这是反馈回流「后端自取引用」的第二个落点（第一个在工单 AI 分析反馈）——
     * 前端只传用户在界面上看到的 citation 字符串，不传 chunk id（它也不知道）。</p>
     * <p>不套知识库写权限（writeGuard）：评价答案是任何登录用户的阅读反馈，
     * 不是内容编辑；boost 是检索权重遥测，不是知识内容。</p>
     */
    @PostMapping("/feedback/citations")
    public ApiResponse<Map<String, Object>> citationsFeedback(@RequestBody CitationsFeedbackRequest req) {
        String verdict = normalizeCitationVerdict(req.verdict());
        if (verdict == null) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, "verdict 必须是 HELPFUL / WRONG / UNHELPFUL 之一");
        }
        List<String> citations = req.citations() == null ? List.of() : req.citations();
        int boosted = 0;
        for (Long chunkId : boostRepository.resolveChunkIds(citations)) {
            boostRepository.recordFeedback(chunkId, verdict);
            boosted++;
        }
        return ApiResponse.success(Map.of("boosted", boosted));
    }

    /** 请求体（record 字段即 JSON 键名，零转换）。 */
    public record CitationsFeedbackRequest(List<String> citations, String verdict) {}

    private static String normalizeCitationVerdict(String v) {
        if (v == null) return null;
        String upper = v.trim().toUpperCase();
        return switch (upper) {
            case "HELPFUL", "WRONG", "UNHELPFUL" -> upper;
            default -> null;
        };
    }

    /**
     * 按源工单聚合反馈计数（「已沉淀为知识」徽标旁展示命中/反馈数）。
     * <p>返回该工单沉淀的每篇文档：{docId, title, helpfulCount, wrongCount}——
     * doc → 其全部 chunk 的 boost 票数之和；无 boost 记录时两计数为 0。</p>
     */
    @GetMapping("/by-source-ticket/{ticketId}/feedback-stats")
    public ApiResponse<List<KnowledgeDocService.FeedbackStat>> feedbackStatsBySourceTicket(
            @PathVariable String ticketId) {
        return ApiResponse.success(docService.feedbackStatsBySourceTicket(ticketId));
    }

    /**
     * 文档级反馈健康度（效能大盘的知识治理出口）。
     * <p>按净反馈（点踩 - 点赞）降序，最该复核的文档排最前；
     * 前端据此把「负资产文档」浮出来。</p>
     *
     * @return [{docId, title, helpful, wrong, net}]，仅含有过反馈的文档
     */
    @GetMapping("/health")
    public ApiResponse<List<Map<String, Object>>> docHealth() {
        return ApiResponse.success(boostRepository.docHealthReport());
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
