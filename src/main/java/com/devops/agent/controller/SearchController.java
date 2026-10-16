package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 全局搜索控制器（批88 P1：跨模块搜索）
 *
 * <p>GET /api/v1/search?q=关键词 —— 同时搜索工单、知识库、告警三张主表，
 * 返回最多 8 条匹配结果（按相关度排序），供导航栏搜索框自动补全与跳转。</p>
 *
 * <p>为什么是独立端点而非拆成三个：一次 HTTP 往返、统一排序、避免前端三次请求的并发管理。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final JdbcTemplate jdbc;

    public SearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> search(
            @RequestParam("q") @NotBlank @Size(min = 1, max = 100) String q) {

        String like = "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%";
        List<Map<String, Object>> results = new ArrayList<>(8);

        // 1) 工单（按 ID 完全匹配优先，其次标题模糊匹配）
        List<Map<String, Object>> tickets = jdbc.queryForList("""
            SELECT id, title, 'ticket' AS kind, status AS sub
              FROM sys_devops_ticket
             WHERE id ILIKE ? OR title ILIKE ?
             ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, id DESC
             LIMIT 4
            """, like, like, q.trim());
        results.addAll(tickets);

        // 2) 知识库文档（按标题匹配）
        List<Map<String, Object>> docs = jdbc.queryForList("""
            SELECT id::text AS id, title, 'knowledge' AS kind, status AS sub
              FROM sys_knowledge_doc
             WHERE title ILIKE ? AND status IN ('PUBLISHED','DRAFT')
             ORDER BY update_time DESC
             LIMIT 3
            """, like);
        results.addAll(docs);

        // 3) 告警（按告警名匹配）
        List<Map<String, Object>> alerts = jdbc.queryForList("""
            SELECT id::text AS id, alert_name AS title, 'alert' AS kind,
                   COALESCE(ticket_id, '') AS sub
              FROM sys_alert
             WHERE alert_name ILIKE ?
             ORDER BY id DESC
             LIMIT 3
            """, like);
        results.addAll(alerts);

        // 总结果上限 8 条：优先保证工单完整，知识库和告警按 LIMIT 各自收束后合并
        if (results.size() > 8) {
            results = results.subList(0, 8);
        }

        log.debug("🔍 [Search] q='{}' → {} results", q, results.size());
        return ApiResponse.success(results);
    }
}