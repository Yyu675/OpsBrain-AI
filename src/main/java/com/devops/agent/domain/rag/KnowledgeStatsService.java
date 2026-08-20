package com.devops.agent.domain.rag;

import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.devops.agent.infrastructure.persistence.entity.KnowledgeChunkEntity;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeChunkRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库统计与浏览查询服务
 * <p>
 * 职责：封装 {@code KnowledgeChunkRepo} 的统计与分页浏览查询，
 * 为 Controller 提供 domain 层接口，使 Controller 不直接依赖 infrastructure 层。
 * </p>
 * <p>
 * 六层架构要求依赖方向单向：controller → application → domain → infrastructure。
 * 此前 {@code KnowledgeManageController} 直接注入 {@code KnowledgeChunkRepo}（infrastructure），
 * 违反了该约束。本类补齐 domain 层查询服务，Controller 改为依赖本类。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
@Slf4j
@Service
public class KnowledgeStatsService {

    @Autowired
    private KnowledgeChunkRepo knowledgeChunkRepo;

    /**
     * 语义缓存：知识库变更后需清空，否则旧答案继续被命中
     */
    @Autowired
    private SemanticCacheService semanticCacheService;

    /**
     * 查询知识库统计信息
     *
     * @return 统计信息 Map（totalDocuments / totalChunks / activeChunks / filteredOut / bySource / lastUpdateTime / hotQueryCount）
     */
    public Map<String, Object> getStats() {
        LocalDateTime now = LocalDateTime.now();

        long totalChunks = knowledgeChunkRepo.countTotalChunks();
        long totalDocuments = knowledgeChunkRepo.countDistinctDocuments();
        long activeChunks = knowledgeChunkRepo.countActiveChunks(now);
        LocalDateTime lastUpdate = knowledgeChunkRepo.findLastUpdateTime();

        Map<String, Long> bySource = new LinkedHashMap<>();
        for (Object[] row : knowledgeChunkRepo.countDocumentsBySource()) {
            if (row.length >= 2 && row[0] != null) {
                bySource.put(row[0].toString(), ((Number) row[1]).longValue());
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDocuments", totalDocuments);
        stats.put("totalChunks", totalChunks);
        stats.put("activeChunks", activeChunks);
        stats.put("filteredOut", totalChunks - activeChunks);
        stats.put("bySource", bySource);
        stats.put("lastUpdateTime", lastUpdate);
        stats.put("hotQueryCount", semanticCacheService.hotQueryCount());

        return stats;
    }

    /**
     * 分页浏览知识库切片
     *
     * @param page    页码（已归一化为 ≥ 1）
     * @param size    每页大小（已归一化为 1~200）
     * @param keyword 搜索关键词（可选，null 或空表示不筛选）
     * @return 分页结果 Map（content / totalElements / totalPages / currentPage / pageSize）
     */
    public Map<String, Object> listChunks(int page, int size, String keyword) {
        LocalDateTime now = LocalDateTime.now();
        Pageable pageable = PageRequest.of(page - 1, size);

        Page<KnowledgeChunkEntity> pageResult;
        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + escapeLike(keyword.trim().toLowerCase()) + "%";
            pageResult = knowledgeChunkRepo.searchActivePage(now, kw, pageable);
        } else {
            pageResult = knowledgeChunkRepo.findActivePage(now, pageable);
        }

        java.util.List<KnowledgeChunkView> list = pageResult.getContent().stream()
                .map(this::toView)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", list);
        result.put("totalElements", pageResult.getTotalElements());
        result.put("totalPages", pageResult.getTotalPages());
        result.put("currentPage", page);
        result.put("pageSize", size);

        return result;
    }

    /**
     * 转义 LIKE 元字符
     * <p>与 {@code DevOpsTicketRepository.escapeLike} 同一意图：
     * 用户输入的 {@code %} 与 {@code _} 不应成为通配符。</p>
     */
    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private KnowledgeChunkView toView(KnowledgeChunkEntity e) {
        return new KnowledgeChunkView(
                e.getId(),
                e.getDocTitle(),
                e.getSectionHeader(),
                truncate(e.getContent(), 200),
                e.getContent() != null ? e.getContent().length() : 0,
                e.getParentId(),
                e.getParentText() != null && !e.getParentText().isBlank(),
                e.getVersion(),
                e.getStatus(),
                e.getKnowledgeSource(),
                e.getEffectiveAt(),
                e.getExpiredAt(),
                e.getCreateTime(),
                e.getUpdateTime()
        );
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
