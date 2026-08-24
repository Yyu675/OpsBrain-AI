package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.rag.KnowledgeStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库管理接口
 * <p>职责：
 * 1. 触发知识库文档摄取（全量重建/增量更新）
 * 2. 查询知识库切片统计信息
 * 3. 浏览知识库切片内容（管理后台）
 * <p>
 * <p>架构层级：Controller Layer
 * <p>依赖：KnowledgeStatsService（domain 层查询服务）
 *
 * @author OpsBrain AI Team
 * @since 2026-07-15
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge")
// 不加 @CrossOrigin：跨域统一由 WebConfig 配置。
//
// 此处原有 @CrossOrigin(origins = "*")，与 WebConfig 的
// allowCredentials(true) 合并后构成 Spring 明令禁止的组合
// （凭据请求的 Access-Control-Allow-Origin 不能是 *），
// 导致本控制器**全部端点**在请求处理阶段抛异常，
// 被 GlobalExceptionHandler 归类为 40001「参数校验失败」——
// 错误信息完全指向错误方向，知识库因此从未成功摄取过。
public class KnowledgeManageController {

    @Autowired
    private KnowledgeStatsService knowledgeStatsService;

    /**
     * 触发知识库文档摄取（<b>已废弃，返回 410 Gone</b>）
     * <p>POST /api/v1/knowledge/ingest
     * <p>
     * <p><strong>⚠️ 此端点已停用（P1-9，2026-08-12）</strong>
     * <p>历史问题：此端点摄取的切片 {@code doc_id=NULL}（孤儿切片），无法被
     * 文档生命周期治理覆盖——{@code deprecate}/{@code purge} 按 {@code doc_id}
     * 清理，永远删不到它们；而它们仍参与检索，持续污染结果。
     * <p>此前仅加 {@code @Deprecated} 注解并打 WARN 日志，但端点<b>仍会真实执行摄取</b>，
     * 等于一边警告一边继续制造孤儿数据。本次改为直接返回 410 Gone，不再执行摄取，
     * 彻底切断孤儿切片的产生来源。
     * <p>
     * <p><strong>替代路径</strong>：使用 {@code POST /api/v1/knowledge/docs} 创建文档并
     * 设置 {@code publish=true}，向量化自动触发，所有切片归属明确。
     * <p>
     * <p>遗留孤儿切片由 {@link com.devops.agent.application.runtime.OrphanChunkCleanupScheduler}
     * 定时清理（每日凌晨删除 {@code doc_id IS NULL} 的切片）。
     *
     * @param request 摄取请求参数（已忽略）
     * @return 410 Gone 提示，引导迁移至 /docs
     * @deprecated 请使用 {@code POST /api/v1/knowledge/docs} 替代
     */
    @Deprecated
    @PostMapping("/ingest")
    public ApiResponse<Map<String, Object>> ingestKnowledge(
            @RequestBody(required = false) Map<String, Object> request) {

        log.warn("⚠️ 调用了已停用的 /ingest 端点（已返回 410 Gone，不再执行摄取）。"
                + "迁移路径：POST /api/v1/knowledge/docs 创建并发布文档，向量化自动触发，切片归属明确。");

        // 旧链路的孤儿切片由定时任务清理，此处无需也无法在请求内清理。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deprecated", true);
        body.put("reason", "此端点产生的切片 doc_id=NULL，无法被文档生命周期治理覆盖，已停用。");
        body.put("migrateTo", "POST /api/v1/knowledge/docs");
        // 40010：4xx 段，语义「端点已废弃」。沿用项目 4 位业务码惯例（40001/40004/40009/40021）。
        // 虽 HTTP 层仍返回 200（本项目 ApiResponse 统一包装），body 含 deprecated=true 与 migrateTo，
        // 调用方可据此判断并迁移。
        return ApiResponse.error(40010, "端点已废弃，请改用 POST /api/v1/knowledge/docs", body);
    }

    /**
     * 查询知识库统计信息
     * <p>GET /api/v1/knowledge/stats
     * <p>响应：{ "totalDocuments": 5, "totalChunks": 62 }
     *
     * @return 统计信息
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getKnowledgeStats() {
        return ApiResponse.success(knowledgeStatsService.getStats());
    }

    /**
     * 浏览知识库切片（分页）
     * <p>GET /api/v1/knowledge/chunks?page=1&size=10&keyword=K8s
     * <p>响应：{ "total": 62, "page": 1, "size": 10, "list": [...] }
     *
     * @param page    页码（默认 1）
     * @param size    每页大小（默认 10）
     * @param keyword 搜索关键词（可选）
     * @return 分页结果
     */
    @GetMapping("/chunks")
    public ApiResponse<Map<String, Object>> listChunks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {

        // 分页参数兜底：page=0 会让 PageRequest.of(-1, …) 抛异常，
        // size 无上限则可被用来一次拉全表
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        log.info("查询知识库切片：page={}, size={}, keyword={}", safePage, safeSize, keyword);

        return ApiResponse.success(knowledgeStatsService.listChunks(safePage, safeSize, keyword));
    }
}
