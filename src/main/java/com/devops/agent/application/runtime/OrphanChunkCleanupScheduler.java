package com.devops.agent.application.runtime;

import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeChunkRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 孤儿切片定时清理（P1-9）。
 * <p>
 * <b>背景</b>：已废弃的 {@code POST /api/v1/knowledge/ingest} 端点（6.20 链路修复前）
 * 摄取的切片 {@code doc_id IS NULL}，逃脱文档生命周期治理——
 * <ul>
 *   <li>{@code deprecate}/{@code purge} 按 {@code doc_id} 清理，{@code NULL} 永远删不到</li>
 *   <li>这些切片仍参与向量检索，{@code doc_title} 修改后旧切片无法被定位清理，持续污染结果</li>
 *   <li>对账时既不在任何文档名下，也无 content_hash 归属，是数据卫生的盲区</li>
 * </ul>
 * 6.21 文档 CRUD + 生命周期治理上线后，新切片均带 {@code doc_id}；
 * 6.20 已把 {@code /ingest} 端点改为返回 410 Gone，切断新孤儿来源。
 * 但<b>历史遗留的孤儿切片</b>仍在库里，需定时清理。
 * </p>
 *
 * <p><b>设计决策</b>：
 * <ul>
 *   <li><b>清理时机</b>：每日凌晨 03:17。避开整点，与全局任务错峰，避免与
 *       其它定时任务（如 6.23 规划的废弃归档）争抢 DB 连接。</li>
 *   <li><b>默认开关</b>：{@code devops.ai.knowledge.cleanup-orphan-enabled=true}。
 *       允许运维在数据迁移期间临时停用，避免误删由外部脚本补录但暂未归档的切片。</li>
 *   <li><b>事务边界</b>：单事务内批量 DELETE，PoC 环境（10486 上下文）孤儿量小，
 *       无需分批。若未来孤儿量增大到万级，再改为分批 + 限速。</li>
 *   <li><b>缓存失效</b>：清理后清空语义缓存。孤儿切片的旧答案若已进缓存，
 *       不失效则会继续被命中，与"清理后不可再被检索"的语义矛盾。</li>
 *   <li><b>不补 {@code doc_id}</b>：不尝试按 {@code doc_title} 反查归属回填。
 *       历史孤儿可能对应已被删除/重建的文档，反查会回填到错误文档，
 *       比直接删除更危险。直接删除是确定性的，文档可通过 /docs 重建。</li>
 * </ul>
 * </p>
 *
 * <p><b>幂等性</b>：DELETE 本身幂等，无孤儿时返回 0，不报错。
 * 任务可通过 {@code POST /api/v1/saga/...} 组件无直接触发入口——
 * 运维需立即清理时手工执行 SQL，定时任务仅作常态化保障。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-12
 */
@Component
public class OrphanChunkCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrphanChunkCleanupScheduler.class);

    private final KnowledgeChunkRepo knowledgeChunkRepo;
    private final SemanticCacheService semanticCacheService;

    /**
     * 孤儿切片清理开关。默认开启；数据迁移期间可临时关闭。
     */
    @Value("${devops.ai.knowledge.cleanup-orphan-enabled:true}")
    private boolean cleanupEnabled;

    @Autowired
    public OrphanChunkCleanupScheduler(KnowledgeChunkRepo knowledgeChunkRepo,
                                        SemanticCacheService semanticCacheService) {
        this.knowledgeChunkRepo = knowledgeChunkRepo;
        this.semanticCacheService = semanticCacheService;
    }

    /**
     * 每日凌晨 03:17 清理 {@code doc_id IS NULL} 的孤儿切片。
     * <p>
     * cron 表达式 {@code "0 17 3 * * *"}：秒 0、分 17、时 3，避开整点。
     * 固定写死而非配置化：清理时机无业务可调诉求，配置项仅增运维负担。
     * </p>
     */
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void cleanupOrphanChunks() {
        if (!cleanupEnabled) {
            log.debug("🧹 [OrphanCleanup] 开关关闭，跳过本次孤儿切片清理");
            return;
        }

        long orphansBefore = knowledgeChunkRepo.countOrphanChunks();
        if (orphansBefore == 0) {
            log.debug("🧹 [OrphanCleanup] 无孤儿切片，本次无需清理");
            return;
        }

        log.info("🧹 [OrphanCleanup] 开始清理孤儿切片 | 预计 {} 条 (doc_id IS NULL)", orphansBefore);

        int deleted = knowledgeChunkRepo.deleteOrphanChunks();

        // 清理后清空语义缓存：孤儿切片的旧答案若已进缓存，不失效会继续被命中。
        // 这与 /ingest 历史实现、/docs 发布路径的缓存失效保持同一口径。
        try {
            semanticCacheService.clearAllCache();
        } catch (Exception cacheEx) {
            // 缓存清理失败不影响主清理事务，仅告警：
            // 切片已删除，缓存最多在 TTL 内命中一次旧答案，下次自然过期。
            log.warn("⚠️ [OrphanCleanup] 语义缓存清理失败，旧答案可能在 TTL 内继续命中 | {}",
                    cacheEx.getMessage());
        }

        log.info("✅ [OrphanCleanup] 孤儿切片清理完成 | 删除 {} 条 | 缓存已{}",
                deleted, "清空");
    }
}
