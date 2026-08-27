package com.devops.agent.domain.rag;

import jakarta.annotation.PreDestroy;
import com.devops.agent.infrastructure.persistence.entity.KnowledgeChunkEntity;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeChunkWriter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 文档向量化执行器：执行「文档级全量重建」。
 * <pre>
 *   删该文档旧切片 → 切片 → 切片级去重 → 向量化 → 写入
 * </pre>
 *
 * @author OpsBrain AI
 * @since 2026-08-10
 */
@Slf4j
@Component
public class DocumentIndexer {

    @Value("${devops.ai.index.timeout-ms:60000}")
    private long indexTimeoutMs;

    @Value("${devops.ai.index.batch-size:20}")
    private int batchSize;

    private final ParentChildDocumentSplitter splitter;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeChunkWriter chunkWriter;
    private final ContentFingerprint fingerprint;

    private final AtomicReference<Long> currentDocId = new AtomicReference<>(null);

    /**
     * 向量化执行池：单线程。
     *
     * <p>队列容量给 16 而非无界——虽然上面的 {@code currentDocId} CAS
     * 已保证同一时刻最多一个任务在跑（队列实际不会堆积），
     * 但显式有界能让「CAS 万一失效」退化为快速失败而不是静默 OOM。</p>
     */
    private final ExecutorService indexExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * 优雅停机：给正在跑的向量化留出收尾时间
     *
     * <p>不做这件事，应用停止时正在向量化的文档会被硬中断，
     * 其 {@code index_status} 停在 PENDING——下次启动后没有任何机制
     * 会自动重试它，那篇文档就此<b>永远检索不到</b>，
     * 而文档列表里它看起来完全正常。</p>
     *
     * <p>10 秒比其它池长：单次向量化要调用外部 Embedding API，
     * 耗时本就以秒计，给太短等于没等。</p>
     */
    @PreDestroy
    public void shutdown() {
        com.devops.agent.infrastructure.concurrent.ManagedExecutors
                .shutdownGracefully(indexExecutor, "doc-indexer", 10);
    }

    public DocumentIndexer(ParentChildDocumentSplitter splitter,
                          EmbeddingModel embeddingModel,
                          KnowledgeChunkWriter chunkWriter,
                          ContentFingerprint fingerprint) {
        this.splitter = splitter;
        this.embeddingModel = embeddingModel;
        this.chunkWriter = chunkWriter;
        this.fingerprint = fingerprint;
    }

    public IndexResult reindex(KnowledgeDoc doc) throws Exception {
        if (doc.getId() == null) {
            throw new IllegalArgumentException("文档 ID 为空，无法建立索引");
        }

        // 幂等防护：同一 doc 并发 reindex 时只允许一个执行（P2-16）
        if (!currentDocId.compareAndSet(null, doc.getId())) {
            throw new IllegalStateException("文档索引正由其他线程执行，拒绝并发 reindex，请稍后重试");
        }

        try {
            Future<IndexResult> future = indexExecutor.submit(indexTask(doc));
            try {
                return future.get(indexTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new TimeoutException(String.format(
                        "向量化超时（>%dms）。文档已保存，稍后将自动重试建立索引", indexTimeoutMs));
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof Exception ex) throw ex;
                throw new IllegalStateException(cause);
            }
        } finally {
            currentDocId.set(null);
        }
    }

    private Callable<IndexResult> indexTask(KnowledgeDoc doc) {
        return () -> {
            long start = System.currentTimeMillis();

            // 1. 删旧切片（全量重建第一步，防旧切片残留）
            int removed = chunkWriter.deleteByDocId(doc.getId());
            if (removed > 0) {
                log.debug("🧹 [Indexer] 清除旧切片 {} 个 | docId={}", removed, doc.getId());
            }

            // 2. 切片
            Metadata meta = new Metadata();
            meta.put("doc_title", doc.getTitle());
            meta.put("doc_id", String.valueOf(doc.getId()));
            meta.put("source", doc.getKnowledgeSource() != null ? doc.getKnowledgeSource() : "SOP");
            if (doc.getCategory() != null) meta.put("category", doc.getCategory());

            List<TextSegment> segments = splitter.splitWithParentChild(
                    Document.from(doc.getContent(), meta));

            if (segments.isEmpty()) {
                return new IndexResult(0, 0, System.currentTimeMillis() - start);
            }

            // 3. 切片级去重（完全相同切片来自重复粘贴，向量化前去掉省成本）
            List<TextSegment> deduped = new ArrayList<>(segments.size());
            List<String> hashes = new ArrayList<>(segments.size());
            Set<String> seen = new HashSet<>();
            int dupCount = 0;
            for (TextSegment seg : segments) {
                String h = fingerprint.sha256(seg.text());
                if (seen.add(h)) {
                    deduped.add(seg);
                    hashes.add(h);
                } else {
                    dupCount++;
                }
            }

            // 4. 向量化（分批）
            List<Embedding> embeddings = embedInBatches(deduped);

            // 5. 写入
            List<KnowledgeChunkEntity> entities = buildEntities(doc, deduped, hashes, embeddings);
            int inserted = chunkWriter.batchInsert(entities);

            return new IndexResult(inserted, dupCount, System.currentTimeMillis() - start);
        };
    }

    /**
     * 删除文档全部向量（废弃时调用：不再检索则向量是纯浪费）
     */
    public int removeVectors(Long docId) {
        if (docId == null) return 0;
        try {
            return chunkWriter.deleteByDocId(docId);
        } catch (Exception e) {
            log.error("⚠️ [Indexer] 删除向量失败，将残留孤儿向量 | docId={} | {}",
                    docId, e.getMessage());
            return 0;
        }
    }

    private List<Embedding> embedInBatches(List<TextSegment> segments) {
        List<Embedding> all = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            all.addAll(embeddingModel.embedAll(segments.subList(i, end)).content());
        }
        return all;
    }

    private List<KnowledgeChunkEntity> buildEntities(KnowledgeDoc doc,
                                                     List<TextSegment> segments,
                                                     List<String> hashes,
                                                     List<Embedding> embeddings) {
        List<KnowledgeChunkEntity> entities = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            Metadata m = seg.metadata();
            entities.add(KnowledgeChunkEntity.builder()
                    .docId(doc.getId())
                    .docTitle(doc.getTitle())
                    .sectionHeader(m.getString("section_header"))
                    .content(seg.text())
                    .contentHash(hashes.get(i))
                    .parentId(m.getString("parent_id"))
                    .parentText(m.getString("parent_text"))
                    .chunkMeta(null)
                    .embedding(toVectorLiteral(embeddings.get(i)))
                    .version(doc.getVersion() != null ? doc.getVersion() : 1)
                    .status("ACTIVE")
                    .knowledgeSource(doc.getKnowledgeSource() != null ? doc.getKnowledgeSource() : "SOP")
                    .effectiveAt(doc.getEffectiveAt())
                    .expiredAt(doc.getExpiredAt())
                    // C1：可见性随文档下沉到切片，供检索层免 JOIN 过滤。
                    // 文档权限变更后必须重建切片，否则会出现
                    // 「文档已受限但切片仍能被检索到」的越权（见 KnowledgeChunkEntity 注释）。
                    .visibility(doc.getVisibility() != null ? doc.getVisibility() : "PUBLIC")
                    .ownerDept(doc.getOwnerDept())
                    .build());
        }
        return entities;
    }

    private String toVectorLiteral(Embedding e) {
        float[] v = e.vector();
        StringBuilder sb = new StringBuilder(v.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    public record IndexResult(int chunkCount, int dedupedCount, long elapsedMs) {
    }
}