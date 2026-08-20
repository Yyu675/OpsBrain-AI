package com.devops.agent.application.runtime;

import com.devops.agent.domain.rag.KnowledgeDoc;
import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeChunkRepo;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeDocRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文档保留期管理定时任务（R3-①）。
 * <p>
 * <b>背景</b>：知识文档废弃（{@code DEPRECATED}）后正文保留 {@code deprecated-days} 天
 * 供审计窗口与撤销，超期后应转入归档（{@code ARCHIVED}，正文转对象存储），
 * 再经 {@code archived-days} 后物理删除。本调度器负责定时扫描超期文档并执行
 * 对应的生命周期推进。
 * </p>
 *
 * <p><b>三任务分工</b>：
 * <ol>
 *   <li><b>废弃超期扫描</b>（{@link #scanDeprecatedOverdue}，每日 03:30）：
 *       查找 {@code status='DEPRECATED' AND update_time < now - deprecated_days} 的文档。
 *       P1 阶段 {@code archive-enabled=false}，仅统计并 WARN 日志，不执行实际归档。
 *       P2-4 MinIO 归档落地且 {@code migration_v10} 补 {@code archived_at}/{@code archive_path} 列后，
 *       改为 {@code archive-enabled=true} 执行正文转存储 + 状态置 {@code ARCHIVED}。</li>
 *   <li><b>归档超期清理</b>（{@link #purgeArchivedExpired}，每日 04:00）：
 *       P2 占位方法。查找 {@code status='ARCHIVED' AND archived_at < now - archived_days} 的文档，
 *       级联删除历史/标签/切片。当前无 {@code archived_at} 列，仅日志占位。
 *       依赖 {@code migration_v10 DDL} + P2-4 MinIO 归档。</li>
 *   <li><b>孤儿向量对账</b>：由 {@link OrphanChunkCleanupScheduler} 每日 03:17 独立处理，
 *       本调度器不重复。参见该类的 javadoc 了解设计决策。</li>
 * </ol>
 * </p>
 *
 * <p><b>设计决策</b>：
 * <ul>
 *   <li><b>扫描时机</b>：废弃扫描 03:30、归档清理 04:00。与孤儿清理 03:17 错峰，
 *       避免多个定时任务同时争抢 DB 连接。</li>
 *   <li><b>P1 仅统计</b>：{@code archive-enabled=false} 时只计数+WARN 日志，不执行任何写操作。
 *       避免存储未落地就归档产生「正文去了哪」的中间态。</li>
 *   <li><b>批次限量</b>：{@code batch-size=500}，单次事务不处理过多记录，防长事务锁表。</li>
 *   <li><b>幂等性</b>：扫描按 {@code update_time < cutoff} 判定，每批处理完旧文档的
 *       {@code update_time} 刷新为 CURRENT_TIMESTAMP（状态变更时），
 *       因此同一文档不会在下一轮重复命中。归档清理的 DELETE 本身幂等。</li>
 *   <li><b>总开关</b>：{@code enabled=false} 时全部任务跳过，仅 debug 日志。</li>
 * </ul>
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-12
 */
@Component
public class KnowledgeRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetentionScheduler.class);

    private final KnowledgeDocRepository knowledgeDocRepository;
    private final KnowledgeChunkRepo knowledgeChunkRepo;
    private final SemanticCacheService semanticCacheService;

    @Value("${devops.ai.knowledge.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${devops.ai.knowledge.cleanup.batch-size:500}")
    private int batchSize;

    @Value("${devops.ai.knowledge.cleanup.archive-enabled:false}")
    private boolean archiveEnabled;

    @Value("${devops.ai.knowledge.retention.deprecated-days:90}")
    private int deprecatedDays;

    @Value("${devops.ai.knowledge.retention.archived-days:365}")
    private int archivedDays;

    @Autowired
    public KnowledgeRetentionScheduler(KnowledgeDocRepository knowledgeDocRepository,
                                       KnowledgeChunkRepo knowledgeChunkRepo,
                                       SemanticCacheService semanticCacheService) {
        this.knowledgeDocRepository = knowledgeDocRepository;
        this.knowledgeChunkRepo = knowledgeChunkRepo;
        this.semanticCacheService = semanticCacheService;
    }

    /**
     * 每日 03:30 扫描超保留期的已废弃文档。
     * <p>
     * cron 表达式 {@code "0 30 3 * * *"}：秒 0、分 30、时 3，避开整点。
     * 与孤儿清理 03:17 错峰 13 分钟，减少 DB 争抢。
     * 固定写死而非配置化：清理时机无业务可调诉求，配置项仅增运维负担。
     * </p>
     * <p>
     * <b>P1 行为</b>（{@code archive-enabled=false}，默认）：仅统计超期文档数，
     * 以 WARN 日志报告「N 篇废弃超保留期待归档」，不执行任何写操作。
     * 用户可见此日志后知悉有文档待归档，但归档动作不会自动发生。
     * </p>
     * <p>
     * <b>P2-4 行为</b>（{@code archive-enabled=true}，前提：
     * {@code migration_v10} 已执行补 {@code archived_at}/{@code archive_path} 列，
     * MinIO 归档存储已就绪）：逐篇执行正文转对象存储 → 写 {@code archive_path}/{@code archived_at}
     * → 状态置 {@code ARCHIVED}。每批独立事务，单篇失败不中断批次。
     * </p>
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void scanDeprecatedOverdue() {
        if (!cleanupEnabled) {
            log.debug("📋 [Retention] 开关关闭，跳过废弃超期扫描");
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(deprecatedDays);
        long overdueCount = knowledgeDocRepository.countDeprecatedBefore(cutoff);

        if (overdueCount == 0) {
            log.debug("📋 [Retention] 无废弃超期待归档文档 (deprecated-days={}d)", deprecatedDays);
            return;
        }

        // P1 阶段：仅统计告警，不执行归档
        if (!archiveEnabled) {
            log.warn("⚠️ [Retention] {} 篇废弃文档超保留期 ({}d) 待归档，但 archive-enabled=false，" +
                     "归档动作已跳过。如需开启，请先执行 migration_v10 补 archived_at/archive_path 列，" +
                     "并确认 MinIO 归档存储就绪后置 archive-enabled=true",
                     overdueCount, deprecatedDays);
            return;
        }

        // ==================== P2-4 路径：archive-enabled=true ====================
        // 以下代码在 migration_v10 未执行时会因缺少列而失败，
        // 因此由 archive-enabled 开关保护，默认 false。
        // 当前为占位框架，归档逻辑待 P2-4 落地时实现。
        log.info("📋 [Retention] 废弃超期归档开始 | 预计 {} 篇 | 截止时间 {}",
                 overdueCount, cutoff);

        List<KnowledgeDoc> docs = knowledgeDocRepository.findDeprecatedBefore(cutoff, batchSize);

        if (docs.isEmpty()) {
            log.info("📋 [Retention] 无待归档文档（并发已清理）");
            return;
        }

        log.warn("📋 [Retention] 归档占位：查得 {} 篇待归档，归档逻辑待 P2-4 实现 | " +
                 "首篇 docId={} title='{}'",
                 docs.size(), docs.get(0).getId(), docs.get(0).getTitle());

        // 清理语义缓存：归档后旧文档不再被检索，缓存中的旧答案应失效。
        // 即使当前为占位也保留缓存清理逻辑，以便 P2-4 实现后不遗漏。
        try {
            semanticCacheService.clearAllCache();
        } catch (Exception cacheEx) {
            log.warn("⚠️ [Retention] 语义缓存清理失败 | {}", cacheEx.getMessage());
        }

        log.info("✅ [Retention] 废弃超期归档扫描完成 | 处理 {} 篇",
                 docs.size());
    }

    /**
     * 每日 04:00 清理超保留期的已归档文档（P2 占位）。
     * <p>
     * cron 表达式 {@code "0 0 4 * * *"}：每日 04:00，与废弃扫描 03:30 错峰 30 分钟。
     * 固定写死而非配置化：理由同 {@link #scanDeprecatedOverdue}。
     * </p>
     * <p>
     * <b>当前状态</b>：仅日志占位，不执行任何 DB 写操作。
     * 原因：{@code sys_knowledge_doc} 表尚未包含 {@code archived_at}/{@code archive_path} 列，
     * 需要先执行 {@code migration_v10} 补列。且归档动作本身依赖 P2-4 MinIO 存储落地，
     * 在无存储的前提下「归档后清理」无意义。
     * </p>
     * <p>
     * <b>P2-4 实现后行为</b>：查找 {@code status='ARCHIVED' AND archived_at < now - archived_days}
     * 的文档，级联物理删除（历史/标签/切片/附件），记审计日志。
     * 不可逆操作，需 {@code archive-enabled} 双开关确认 + 审计留痕。
     * </p>
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void purgeArchivedExpired() {
        if (!cleanupEnabled) {
            log.debug("📋 [Retention] 开关关闭，跳过归档清理");
            return;
        }

        // 查询当前 ARCHIVED 文档数。当前无归档写入路径，此查询恒返回 0——
        // 但保留占位框架，防止 P2-4 实现后忘记添加此清理任务。
        long archivedCount;
        try {
            archivedCount = knowledgeDocRepository.countByQuery(
                    com.devops.agent.domain.rag.KnowledgeDocLifecycle.STATUS_ARCHIVED,
                    null, null, null);
        } catch (Exception e) {
            log.warn("📋 [Retention] 归档文档查询跳过（ARCHIVED 状态尚未启用）| {}", e.getMessage());
            return;
        }

        if (archivedCount == 0) {
            log.debug("📋 [Retention] 无归档文档需清理");
            return;
        }

        // 占位日志：提醒运维此任务已就绪但受限于 P2-4 前提
        log.warn("⚠️ [Retention] 归档清理占位 | 当前 {} 篇 ARCHIVED 文档 | " +
                 "清理需 P2-4 MinIO 归档 + migration_v10 (archived_at/archive_path) 前置",
                 archivedCount);
    }
}