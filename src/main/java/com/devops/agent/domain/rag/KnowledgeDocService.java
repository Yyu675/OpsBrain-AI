package com.devops.agent.domain.rag;

import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeChunkWriter;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeDocHistoryRepository;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeDocRepository;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeCategoryRepository;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeTagRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识文档服务：CRUD + 版本治理 + 去重 + 向量化编排
 * <p>
 * 完整生命周期语义见 {@link KnowledgeDocLifecycle}。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-10
 */
@Slf4j
@Service
public class KnowledgeDocService {

    private final KnowledgeDocRepository docRepo;
    private final KnowledgeDocHistoryRepository historyRepo;
    private final KnowledgeDocTagRepository tagRepo;
    private final ContentFingerprint fingerprint;
    private final DocumentIndexer indexer;
    private final SemanticCacheService semanticCache;
    private final KnowledgeContentCleaner contentCleaner;
    private final KnowledgeCategoryRepository categoryRepo;
    private final KnowledgeTagRepository tagCatalog;

    public KnowledgeDocService(KnowledgeDocRepository docRepo,
                               KnowledgeDocHistoryRepository historyRepo,
                               KnowledgeDocTagRepository tagRepo,
                               ContentFingerprint fingerprint,
                               DocumentIndexer indexer,
                               SemanticCacheService semanticCache,
                               KnowledgeContentCleaner contentCleaner,
                               KnowledgeCategoryRepository categoryRepo,
                               KnowledgeTagRepository tagCatalog) {
        this.docRepo = docRepo;
        this.historyRepo = historyRepo;
        this.tagRepo = tagRepo;
        this.fingerprint = fingerprint;
        this.indexer = indexer;
        this.semanticCache = semanticCache;
        this.contentCleaner = contentCleaner;
        this.categoryRepo = categoryRepo;
        this.tagCatalog = tagCatalog;
    }

    // ==================== 创建 ====================

    /**
     * 创建文档。去重两道关：
     * <ol>
     *   <li>精确（content_hash）：相同内容直接拒绝，并告知与哪篇重复</li>
     *   <li>近似（simhash）：汉明距离 ≤ 10 时警告但放行——近似不阻塞，交由用户判断</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public SaveResult create(KnowledgeDoc doc, List<String> tags, boolean publish, String operator) {
        resolveCategory(doc);
        validateForSave(doc);

        String hash = fingerprint.sha256(doc.getContent());
        long simhash = fingerprint.simhash(doc.getContent());

        // 关卡 1：精确去重
        KnowledgeDoc exact = docRepo.findByContentHash(hash);
        if (exact != null) {
            throw new DuplicateContentException(
                    String.format("内容与已有文档《%s》完全相同（ID %d），未创建",
                            exact.getTitle(), exact.getId()),
                    exact.getId(), exact.getTitle());
        }

        // 关卡 2：近似去重——警告不拦截
        List<NearDuplicate> nearDups = findNearDuplicates(simhash, doc.getCategory(), null);

        doc.setContentHash(hash);
        doc.setSimhash(simhash);
        doc.setVersion(1);
        doc.setStatus(publish ? KnowledgeDocLifecycle.STATUS_PUBLISHED
                              : KnowledgeDocLifecycle.STATUS_DRAFT);
        doc.setIndexStatus(publish ? KnowledgeDocLifecycle.INDEX_PENDING
                                   : KnowledgeDocLifecycle.INDEX_SKIPPED);
        if (doc.getSummary() == null || doc.getSummary().isBlank()) {
            doc.setSummary(autoSummary(doc.getContent()));
        }

        Long docId;
        try {
            docId = docRepo.insert(doc);
        } catch (DuplicateKeyException e) {
            // 并发窗口：INSERT 前另一请求插了同内容，唯一索引是最终防线
            throw new DuplicateContentException("内容重复（并发提交），未创建", null, null);
        }
        doc.setId(docId);

        tagCatalog.ensureNames(tags);
        tagRepo.replaceTags(docId, tags);
        historyRepo.archive(doc, KnowledgeDocLifecycle.CHANGE_CREATE, operator, "首次创建");

        IndexOutcome outcome = publish ? indexIfNeeded(doc) : IndexOutcome.skipped();
        return new SaveResult(docId, 1, nearDups, outcome);
    }

    // ==================== 更新 ====================

    /**
     * 更新文档。关键行为：
     * <ul>
     *   <li>先归档旧版本再更新——反序会归档新版本使历史链断裂</li>
     *   <li>内容未变则跳过向量化（content_hash 的成本控制关键）</li>
     *   <li>带 version CAS 防并发覆盖</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public SaveResult update(Long docId, KnowledgeDoc patch, List<String> tags,
                            Integer expectedVersion, String operator, String reason) {
        KnowledgeDoc existing = docRepo.findById(docId);
        if (existing == null) {
            throw new IllegalStateException("文档不存在: " + docId);
        }

        resolveCategory(patch);

        boolean restoring = KnowledgeDocLifecycle.STATUS_PUBLISHED.equals(patch.getStatus());
        boolean metadataOnly = patch.getTitle() == null && patch.getContent() == null
                && patch.getSummary() == null && patch.getAuthor() == null;
        if ((KnowledgeDocLifecycle.STATUS_DEPRECATED.equals(existing.getStatus())
                || KnowledgeDocLifecycle.STATUS_ARCHIVED.equals(existing.getStatus()))
                && !restoring && !metadataOnly) {
            throw new IllegalStateException("已废弃或已归档文档不能直接编辑，请先恢复文档");
        }

        if (expectedVersion != null && !expectedVersion.equals(existing.getVersion())) {
            throw new com.devops.agent.common.exception.OptimisticLockException(
                    "文档 " + docId, expectedVersion, existing.getVersion());
        }

        String newContent = patch.getContent() != null ? patch.getContent() : existing.getContent();
        if (patch.getContent() != null) {
            // 内容清洗（P1-3）：脏数据不进向量库。仅在实际更新内容时执行；
            // 元信息更新（patch 不含 content）直接沿用旧内容，不校验。
            KnowledgeContentCleaner.CleanResult cr = contentCleaner.clean(newContent);
            if (cr.isRejected()) {
                throw new IllegalArgumentException("文档内容清洗未通过：" + cr.rejectReason());
            }
            if (cr.cleaned()) {
                log.debug("🧹 [KnowledgeDoc] 更新内容已清洗 | docId={} | 清洗后长度={}", docId, cr.content().length());
            }
            if (cr.dupeWarning() != null) {
                log.warn("⚠️ [KnowledgeDoc] 更新内容含重复段落（仅告警）| docId={} | 占比={:.1%}", docId, cr.dupeWarning().ratio());
            }
            newContent = cr.content();
        }
        String newHash = fingerprint.sha256(newContent);
        boolean contentChanged = !newHash.equals(existing.getContentHash());

        List<NearDuplicate> nearDups = List.of();
        if (contentChanged) {
            long newSimhash = fingerprint.simhash(newContent);
            nearDups = findNearDuplicates(newSimhash,
                    patch.getCategory() != null ? patch.getCategory() : existing.getCategory(), docId);
            existing.setSimhash(newSimhash);
        }

        // 归档旧版本——必须在覆盖字段之前，此时 existing 还是旧内容
        historyRepo.archive(existing, KnowledgeDocLifecycle.CHANGE_UPDATE, operator,
                reason != null ? reason : (contentChanged ? "内容更新" : "元信息更新"));

        // 读取-合并-写回：仅覆盖非空字段
        if (patch.getTitle() != null) existing.setTitle(patch.getTitle());
        if (patch.getCategory() != null) {
            existing.setCategory(patch.getCategory().isBlank() ? null : patch.getCategory());
            if (patch.getCategory().isBlank()) existing.setCategoryId(null);
        }
        if (patch.getCategoryId() != null) existing.setCategoryId(patch.getCategoryId());
        if (patch.getAuthor() != null) existing.setAuthor(patch.getAuthor());
        if (patch.getSummary() != null) existing.setSummary(patch.getSummary());
        if (patch.getEffectiveAt() != null) existing.setEffectiveAt(patch.getEffectiveAt());
        if (patch.getExpiredAt() != null) existing.setExpiredAt(patch.getExpiredAt());
        if (patch.getKnowledgeSource() != null) existing.setKnowledgeSource(patch.getKnowledgeSource());
        if (patch.getStatus() != null) existing.setStatus(patch.getStatus());
        existing.setContent(newContent);
        existing.setContentHash(newHash);

        // 重新向量化的条件：目标状态需要索引，且「内容变化」或「当前并无有效索引」。
        // 只判 contentChanged 会漏掉「废弃 → 恢复」路径：deprecate 已 removeVectors，
        // 恢复时内容未变 contentChanged=false，跳过重建会残留 SKIPPED / 0 切片，
        // 文档显示已发布实则不可检索（6.22 联调发现的真实缺陷）。
        boolean shouldIndexNow = KnowledgeDocLifecycle.shouldBeIndexed(existing.getStatus());
        boolean currentlyIndexed = KnowledgeDocLifecycle.INDEX_INDEXED.equals(existing.getIndexStatus());
        boolean needReindex = shouldIndexNow && (contentChanged || !currentlyIndexed);
        if (needReindex) {
            existing.setIndexStatus(KnowledgeDocLifecycle.INDEX_PENDING);
        }

        int rows = docRepo.update(existing, expectedVersion);
        if (rows == 0) {
            KnowledgeDoc latest = docRepo.findById(docId);
            throw new com.devops.agent.common.exception.OptimisticLockException(
                    "文档 " + docId, expectedVersion,
                    latest != null ? latest.getVersion() : null);
        }

        // 库中已自增版本号：最终版本 = 旧版本 + 1
        int newVersion = existing.getVersion() + 1;

        if (tags != null) {
            tagCatalog.ensureNames(tags);
            tagRepo.replaceTags(docId, tags);
        }

        IndexOutcome outcome;
        if (needReindex) {
            outcome = indexIfNeeded(existing);
        } else {
            outcome = IndexOutcome.unchanged();
        }

        return new SaveResult(docId, newVersion, nearDups, outcome);
    }

    // ==================== 状态变更 ====================

    @Transactional(rollbackFor = Exception.class)
    public IndexOutcome publish(Long docId, String operator) {
        KnowledgeDoc doc = docRepo.findById(docId);
        if (doc == null) {
            throw new IllegalStateException("文档不存在: " + docId);
        }
        if (KnowledgeDocLifecycle.STATUS_PUBLISHED.equals(doc.getStatus())) {
            // 幂等分支（手册 E2 增量更新：保持全量重建，但内容未变则零 API 调用）。
            // 仅当「已发布但向量化 PENDING/FAILED」时才补建索引；
            // 已 INDEXED 说明当前向量是有效投影，直接 UNCHANGED——
            // 否则用户重复点发布会触发无条件全量重嵌（远程 embedding 调用）。
            if (KnowledgeDocLifecycle.INDEX_INDEXED.equals(doc.getIndexStatus())) {
                return IndexOutcome.unchanged();
            }
            return indexIfNeeded(doc);
        }

        if (KnowledgeDocLifecycle.STATUS_DEPRECATED.equals(doc.getStatus())) {
            throw new IllegalStateException("已废弃文档请使用恢复操作重新发布");
        }
        if (KnowledgeDocLifecycle.STATUS_ARCHIVED.equals(doc.getStatus())) {
            throw new IllegalStateException("已归档文档暂不支持直接发布");
        }

        docRepo.updateStatus(docId, KnowledgeDocLifecycle.STATUS_PUBLISHED,
                KnowledgeDocLifecycle.INDEX_PENDING);
        doc.setStatus(KnowledgeDocLifecycle.STATUS_PUBLISHED);
        return indexIfNeeded(doc);
    }

    /**
     * 废弃（默认的「删除」语义）。
     * <p>不做物理删除。留正文供历史查阅，删向量使其退出检索。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deprecate(Long docId, String operator, String reason) {
        KnowledgeDoc doc = docRepo.findById(docId);
        if (doc == null) {
            throw new IllegalStateException("文档不存在: " + docId);
        }

        historyRepo.archive(doc, KnowledgeDocLifecycle.CHANGE_DEPRECATE, operator,
                reason != null ? reason : "文档废弃");

        docRepo.updateStatus(docId, KnowledgeDocLifecycle.STATUS_DEPRECATED,
                KnowledgeDocLifecycle.INDEX_SKIPPED);

        indexer.removeVectors(docId);
        docRepo.updateIndexStatus(docId, KnowledgeDocLifecycle.INDEX_SKIPPED, null, 0);
        semanticCache.clearAllCache();

        log.warn("🗑 [KnowledgeDoc] 已废弃 | id={} | title={} | 原因={}",
                docId, doc.getTitle(), reason);
    }

    /**
     * 回滚到历史版本。
     * <p>实现为「把历史内容作为一次新的更新提交」，而非倒退版本号——
     * 倒退会破坏 CAS 语义且丢失「曾回滚过」这一事实。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public SaveResult restore(Long docId, int targetVersion, String operator) {
        KnowledgeDoc historical = historyRepo.findVersion(docId, targetVersion);
        if (historical == null) {
            throw new IllegalStateException(
                    "历史版本不存在: docId=" + docId + " version=" + targetVersion);
        }
        KnowledgeDoc current = docRepo.findById(docId);
        if (current == null) {
            throw new IllegalStateException("文档不存在: " + docId);
        }

        // 回滚预检：目标内容若已被<b>其他活跃文档</b>占用，会撞部分唯一索引。
        // 与其让用户看到 500，不如给出明确提示——
        // 「该内容与文档 X 相同」说明要么先废弃 X，要么放弃回滚
        KnowledgeDoc occupied = docRepo.findByContentHash(historical.getContentHash());
        if (occupied != null && !occupied.getId().equals(docId)
                && KnowledgeDocLifecycle.shouldBeIndexed(occupied.getStatus())) {
            throw new IllegalStateException(String.format(
                    "无法回滚：目标版本的内容与活跃文档《%s》（ID %d）完全相同。"
                            + "请先废弃该文档，或选择其他历史版本",
                    occupied.getTitle(), occupied.getId()));
        }

        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setContent(historical.getContent());
        patch.setTitle(historical.getTitle());
        patch.setCategory(historical.getCategory());
        // 回滚即重新发布：历史版本的内容是已发布过的，恢复后应重新可检索。
        // 若不设 status，回滚已废弃文档会停留在 DEPRECATED——
        // 而 update 的合并逻辑只覆盖非空字段，status 保持废弃态，
        // 用户以为回滚成功实则仍检索不到
        patch.setStatus(KnowledgeDocLifecycle.STATUS_PUBLISHED);

        return update(docId, patch, null, current.getVersion(), operator,
                "回滚至版本 " + targetVersion);
    }

    /**
     * 物理删除。仅限合规场景，必须提供 complianceReason（用于审计举证）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void purge(Long docId, String operator, String complianceReason) {
        if (complianceReason == null || complianceReason.isBlank()) {
            throw new IllegalArgumentException(
                    "物理删除必须提供合规理由（用于审计举证）。若只是下架，请用废弃");
        }

        KnowledgeDoc doc = docRepo.findById(docId);
        if (doc == null) {
            throw new IllegalStateException("文档不存在: " + docId);
        }

        log.warn("🔥 [KnowledgeDoc] 物理删除 | id={} | title={} | 操作人={} | 合规理由={}",
                docId, doc.getTitle(), operator, complianceReason);

        indexer.removeVectors(docId);
        tagRepo.deleteByDocId(docId);
        historyRepo.deleteByDocId(docId);
        docRepo.deleteById(docId);
        semanticCache.clearAllCache();
    }

    // ==================== 向量化编排 ====================

    /**
     * 按需向量化。同步执行 + 超时降级：用户点发布后期望立即生效，
     * 但必须有超时兜底（远程调用会抖），超时后降级为 PENDING 并明确告知不可检索。
     * <b>失败不回滚文档</b>——文档已保存是事实，向量化失败只影响可检索性。
     */
    private IndexOutcome indexIfNeeded(KnowledgeDoc doc) {
        if (!KnowledgeDocLifecycle.shouldBeIndexed(doc.getStatus())) {
            docRepo.updateIndexStatus(doc.getId(), KnowledgeDocLifecycle.INDEX_SKIPPED, null, 0);
            return IndexOutcome.skipped();
        }

        try {
            DocumentIndexer.IndexResult r = indexer.reindex(doc);
            docRepo.updateIndexStatus(doc.getId(),
                    KnowledgeDocLifecycle.INDEX_INDEXED, null, r.chunkCount());
            semanticCache.clearAllCache();
            return IndexOutcome.indexed(r.chunkCount(), r.dedupedCount());

        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            docRepo.updateIndexStatus(doc.getId(),
                    KnowledgeDocLifecycle.INDEX_FAILED, truncate(err, 500), 0);
            log.error("🚨 [KnowledgeDoc] 向量化失败，文档已保存但不可检索 | id={} | {}",
                    doc.getId(), err);
            return IndexOutcome.failed(err);
        }
    }

    // ==================== 去重 ====================

    private List<NearDuplicate> findNearDuplicates(long simhash, String category, Long excludeId) {
        if (simhash == 0L) {
            return List.of();
        }
        List<KnowledgeDoc> candidates = docRepo.findSimhashCandidates(category, excludeId, 200);
        List<NearDuplicate> result = new ArrayList<>();

        for (KnowledgeDoc c : candidates) {
            if (c.getSimhash() == null) continue;
            int distance = fingerprint.hammingDistance(simhash, c.getSimhash());
            if (distance <= ContentFingerprint.SIMHASH_THRESHOLD) {
                result.add(new NearDuplicate(c.getId(), c.getTitle(), distance));
            }
        }
        result.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        return result;
    }

    // ==================== 查询 ====================

    public KnowledgeDoc findById(Long docId, boolean withTags) {
        KnowledgeDoc doc = docRepo.findById(docId);
        if (doc != null && withTags) {
            doc.setTags(tagRepo.findByDocId(docId));
        }
        return doc;
    }

    public List<KnowledgeDoc> findPage(int page, int size, String status,
                                       String category, String keyword, String tag, String sort) {
        List<KnowledgeDoc> docs = docRepo.findPage(
                page, size, status, category, keyword, tag, sort);
        if (!docs.isEmpty()) {
            List<Long> ids = docs.stream().map(KnowledgeDoc::getId).toList();
            Map<Long, List<String>> tagMap = tagRepo.findByDocIds(ids);
            for (KnowledgeDoc d : docs) {
                d.setTags(tagMap.getOrDefault(d.getId(), List.of()));
            }
        }
        return docs;
    }

    public long countByQuery(String status, String category, String keyword, String tag) {
        return docRepo.countByQuery(status, category, keyword, tag);
    }

    /**
     * 按源工单反查已沉淀的文档（L1.5 来源回链）。
     * <p>工单详情页据此判断「此工单已沉淀为哪些知识」并展示徽标与跳转入口。
     * 批量装填标签以便前端展示。</p>
     */
    public List<KnowledgeDoc> findBySourceTicketId(Long sourceTicketId) {
        List<KnowledgeDoc> docs = docRepo.findBySourceTicketId(sourceTicketId);
        if (!docs.isEmpty()) {
            List<Long> ids = docs.stream().map(KnowledgeDoc::getId).toList();
            Map<Long, List<String>> tagMap = tagRepo.findByDocIds(ids);
            for (KnowledgeDoc d : docs) {
                d.setTags(tagMap.getOrDefault(d.getId(), List.of()));
            }
        }
        return docs;
    }

    /**
     * 扁平分类聚合（侧栏导航），全库跨页统计
     */
    public List<Map<String, Object>> findCategories() {
        return docRepo.findCategories();
    }

    /**
     * 热门标签（仅 PUBLISHED 文档计数），全库跨页聚合
     */
    public Map<String, Integer> findHotTags(int limit) {
        return tagRepo.findHotTags(limit);
    }

    /** 分类治理通过文档更新链路执行，保留版本历史并受 CAS 保护。 */
    @Transactional(rollbackFor = Exception.class)
    public void renameCategoryDocuments(Long categoryId, String oldName, String newName, String operator) {
        for (KnowledgeDoc doc : docRepo.findByCategory(categoryId, oldName)) {
            KnowledgeDoc patch = new KnowledgeDoc();
            patch.setCategory(newName);
            patch.setCategoryId(categoryId);
            update(doc.getId(), patch, null, doc.getVersion(), operator, "分类重命名");
        }
    }

    /** 文档移动同样走版本更新，避免编辑器中的旧分类覆盖移动结果。 */
    @Transactional(rollbackFor = Exception.class)
    public void moveCategory(Long docId, Long categoryId, String categoryName,
                             Integer expectedVersion, String operator) {
        KnowledgeDoc existing = docRepo.findById(docId);
        if (existing == null) throw new IllegalStateException("文档不存在: " + docId);
        if (expectedVersion != null && !expectedVersion.equals(existing.getVersion())) {
            throw new com.devops.agent.common.exception.OptimisticLockException(
                    "文档 " + docId, expectedVersion, existing.getVersion());
        }
        KnowledgeDoc snapshot = existing;
        historyRepo.archive(snapshot, KnowledgeDocLifecycle.CHANGE_UPDATE, operator, "移动文档分类");
        int rows = docRepo.updateCategory(docId, categoryName, categoryId, expectedVersion);
        if (rows == 0) {
            KnowledgeDoc latest = docRepo.findById(docId);
            throw new com.devops.agent.common.exception.OptimisticLockException(
                    "文档 " + docId, expectedVersion,
                    latest == null ? null : latest.getVersion());
        }
    }

    private void resolveCategory(KnowledgeDoc doc) {
        if (doc.getCategoryId() != null) {
            KnowledgeCategory category = categoryRepo.findById(doc.getCategoryId());
            if (category == null) throw new IllegalArgumentException("分类不存在");
            doc.setCategory(category.name());
        } else if (doc.getCategory() != null && !doc.getCategory().isBlank()) {
            KnowledgeCategory category = categoryRepo.findByName(doc.getCategory());
            if (category != null) doc.setCategoryId(category.id());
        }
    }

    public List<Map<String, Object>> listVersions(Long docId) {
        return historyRepo.listVersions(docId);
    }

    public KnowledgeDoc findVersion(Long docId, int version) {
        return historyRepo.findVersion(docId, version);
    }

    // ==================== 版本对比 ====================

    /**
     * 对比两个历史版本的行级差异。
     * <p>
     * 不做切片级 diff——文档中间插一句话，其后所有切片起止位置整体漂移，
     * 旧新切片无法对应（6.21 已论证），必须对原文逐行做文档级 LCS diff
     * （{@link KnowledgeDocDiff}）。版本相同则返回全 EQUAL 段。
     * </p>
     *
     * @see KnowledgeDocDiff
     */
    public VersionDiffData compareVersions(Long docId, int fromVersion, int toVersion) {
        KnowledgeDoc from = historyRepo.findVersion(docId, fromVersion);
        KnowledgeDoc to = historyRepo.findVersion(docId, toVersion);
        if (from == null) {
            throw new IllegalArgumentException(
                    "历史版本不存在: docId=" + docId + " version=" + fromVersion);
        }
        if (to == null) {
            throw new IllegalArgumentException(
                    "历史版本不存在: docId=" + docId + " version=" + toVersion);
        }

        List<KnowledgeDocDiff.DiffSegment> segments =
                KnowledgeDocDiff.diff(from.getContent(), to.getContent());

        return new VersionDiffData(from, to, segments);
    }

    /**
     * 版本对比数据（领域层，不含 DTO）。
     * Controller 负责映射为 {@code VersionDiffResult}，避免 domain 反向依赖 controller。
     */
    public record VersionDiffData(
            KnowledgeDoc from,
            KnowledgeDoc to,
            List<KnowledgeDocDiff.DiffSegment> segments
    ) {}

    /**
     * 补偿向量化失败的文档（定时任务）。
     * 没有这一步，一次网络抖动就会让文档永久不可检索。
     */
    public int retryFailedIndexing(int limit) {
        List<KnowledgeDoc> pending = docRepo.findNeedingIndex(limit);
        int succeeded = 0;
        for (KnowledgeDoc doc : pending) {
            IndexOutcome outcome = indexIfNeeded(doc);
            if (outcome.status() == IndexOutcome.Status.INDEXED) succeeded++;
        }
        if (!pending.isEmpty()) {
            log.info("🔄 [KnowledgeDoc] 补偿向量化 | 待处理={} | 成功={}", pending.size(), succeeded);
        }
        return succeeded;
    }

    // ==================== 辅助 ====================

    private void validateForSave(KnowledgeDoc doc) {
        if (doc.getTitle() == null || doc.getTitle().isBlank()) {
            throw new IllegalArgumentException("文档标题不能为空");
        }
        if (doc.getTitle().length() > 255) {
            throw new IllegalArgumentException("文档标题过长（上限 255 字符）");
        }
        if (doc.getContent() == null) {
            throw new IllegalArgumentException("文档内容不能为空");
        }

        // 内容清洗（P1-3）：脏数据不进向量库
        KnowledgeContentCleaner.CleanResult cr = contentCleaner.clean(doc.getContent());
        if (cr.isRejected()) {
            throw new IllegalArgumentException("文档内容清洗未通过：" + cr.rejectReason());
        }
        if (cr.cleaned()) {
            doc.setContent(cr.content());
            log.debug("🧹 [KnowledgeDoc] 内容已清洗 | id={} | title={} | 清洗后长度={}",
                    doc.getId(), doc.getTitle(), cr.content().length());
        }
        if (cr.dupeWarning() != null) {
            log.warn("⚠️ [KnowledgeDoc] 内容含重复段落（仅告警）| id={} | title={} | 占比={:.1%}",
                    doc.getId(), doc.getTitle(), cr.dupeWarning().ratio());
        }
    }

    private String autoSummary(String content) {
        if (content == null) return null;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("```")) continue;
            return truncate(t, 200);
        }
        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ==================== 返回类型 ====================

    public record SaveResult(
            Long docId,
            Integer version,
            List<NearDuplicate> nearDuplicates,
            IndexOutcome indexOutcome
    ) {}

    public record NearDuplicate(Long docId, String title, int distance) {
    }

    /**
     * 向量化结果。四种状态必须如实传给前端——只回成功/失败，
     * 用户无法区分「还没建索引」还是「建索引失败」。
     */
    public record IndexOutcome(Status status, int chunkCount, int dedupedCount, String error) {

        public enum Status {
            INDEXED,       // 已建索引，可检索
            SKIPPED,       // 无需索引（草稿/已废弃）
            UNCHANGED,     // 内容未变，沿用原索引（零 API 调用）
            FAILED         // 建索引失败，文档已保存但不可检索
        }

        public static IndexOutcome indexed(int chunkCount, int deduped) {
            return new IndexOutcome(Status.INDEXED, chunkCount, deduped, null);
        }

        public static IndexOutcome skipped() {
            return new IndexOutcome(Status.SKIPPED, 0, 0, null);
        }

        public static IndexOutcome unchanged() {
            return new IndexOutcome(Status.UNCHANGED, 0, 0, null);
        }

        public static IndexOutcome failed(String error) {
            return new IndexOutcome(Status.FAILED, 0, 0, error);
        }

        public boolean isRetrievable() {
            return status == Status.INDEXED || status == Status.UNCHANGED;
        }
    }

    /**
     * 内容重复异常。独立类型：前端需拿到重复文档 ID 以便提供「查看该文档」跳转。
     */
    public static class DuplicateContentException extends RuntimeException {
        private final Long duplicateDocId;
        private final String duplicateTitle;

        public DuplicateContentException(String message, Long docId, String title) {
            super(message);
            this.duplicateDocId = docId;
            this.duplicateTitle = title;
        }

        public Long getDuplicateDocId() { return duplicateDocId; }
        public String getDuplicateTitle() { return duplicateTitle; }
    }
}
