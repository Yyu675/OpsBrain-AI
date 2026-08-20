package com.devops.agent.domain.rag;

import com.devops.agent.infrastructure.persistence.entity.KnowledgeChunkEntity;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeChunkRepo;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentLoader;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 知识库摄取服务
 * <p>职责：
 * 1. 扫描 resources/knowledge/*.md 文档
 * 2. 调用父子切片器进行结构化切分
 * 3. 批量向量化（调用 EmbeddingModel）
 * 4. 持久化到 PostgreSQL + pgvector
 * 5. 支持全量重建与增量更新
 * <p>
 * <p>架构层级：Domain Layer - RAG
 * <p>依赖：ParentChildDocumentSplitter、EmbeddingModel、KnowledgeChunkRepo
 *
 * @author OpsBrain AI Team
 * @since 2026-07-15
 */
@Slf4j
@Service
public class KnowledgeIngestionService {

    @Autowired
    private ParentChildDocumentSplitter documentSplitter;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private KnowledgeChunkRepo knowledgeChunkRepo;

    /** 用于把切片元数据序列化为合法 JSON（chunk_meta 列类型为 JSONB） */
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 原生 SQL 写入器：处理 vector / jsonb 两个 JPA 不支持的列类型 */
    @Autowired
    private com.devops.agent.infrastructure.persistence.repo.KnowledgeChunkWriter chunkWriter;

    /**
     * 知识库文档路径（classpath 下）
     */
    private static final String KNOWLEDGE_BASE_PATH = "classpath:knowledge/*.md";

    /**
     * 批量向量化大小（避免单次调用过大导致超时）
     */
    private static final int EMBEDDING_BATCH_SIZE = 50;

    /**
     * 摄取所有本地知识库文档
     *
     * @param reload 是否全量重建（true=清空后重建，false=增量更新）
     * @return 摄取统计结果
     */
    @Transactional(rollbackFor = Exception.class)
    public IngestionResult ingestAllLocalDocuments(boolean reload) {
        long startTime = System.currentTimeMillis();

        log.info("========== 开始知识库摄取流程 ==========");
        log.info("摄取模式: {}", reload ? "全量重建" : "增量更新");

        // 1. 如果是全量重建，先清空知识库
        if (reload) {
            log.warn("全量重建模式：正在清空知识库...");
            int deleted = chunkWriter.truncateAll();
            log.info("知识库已清空，删除 {} 个旧切片", deleted);
        }

        // 2. 扫描知识库文档
        List<Document> documents = loadDocumentsFromClasspath();
        log.info("扫描到 {} 个文档", documents.size());

        if (documents.isEmpty()) {
            log.warn("未找到任何知识库文档，摄取流程结束");
            return IngestionResult.builder()
                    .documentsLoaded(0)
                    .chunksIngested(0)
                    .elapsedMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        // 3. 切片 + 向量化 + 入库
        int totalChunks = 0;
        for (Document doc : documents) {
            String docTitle = doc.metadata().getString("doc_title");

            // 增量模式：检查文档是否已存在，存在则跳过
            if (!reload && knowledgeChunkRepo.existsByDocTitle(docTitle)) {
                log.info("文档 [{}] 已存在，跳过摄取", docTitle);
                continue;
            }

            log.info("正在处理文档: {}", docTitle);

            // 3.1 父子切片
            List<TextSegment> segments = documentSplitter.splitWithParentChild(doc);
            log.debug("  切片完成：{} 个切片", segments.size());

            // 3.2 批量向量化
            List<Embedding> embeddings = embedSegmentsBatch(segments);
            log.debug("  向量化完成：{} 个向量", embeddings.size());

            // 3.3 持久化到数据库。
            // 用原生 SQL 写入器而非 JPA saveAll：本表的 embedding 是 pgvector
            // 的 vector(1536)、chunk_meta 是 jsonb，Hibernate 无法从 String
            // 隐式转换，saveAll 会直接报 "is of type vector but expression is
            // of type character varying"。写入器用 ?::vector / ?::jsonb 显式转型。
            List<KnowledgeChunkEntity> entities = buildChunkEntities(docTitle, segments, embeddings);
            int inserted = chunkWriter.batchInsert(entities);
            log.info("  入库完成：{} 个切片已保存", inserted);

            totalChunks += inserted;
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        log.info("========== 知识库摄取完成 ==========");
        log.info("文档数: {}, 切片数: {}, 耗时: {} ms", documents.size(), totalChunks, elapsedMs);

        return IngestionResult.builder()
                .documentsLoaded(documents.size())
                .chunksIngested(totalChunks)
                .elapsedMs(elapsedMs)
                .build();
    }

    /**
     * 从 classpath 加载所有 Markdown 文档
     *
     * @return 文档列表
     */
    private List<Document> loadDocumentsFromClasspath() {
        List<Document> documents = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources(KNOWLEDGE_BASE_PATH);
            log.debug("扫描路径 [{}] 找到 {} 个文件", KNOWLEDGE_BASE_PATH, resources.length);

            for (Resource resource : resources) {
                try {
                    String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    String filename = resource.getFilename();

                    Metadata metadata = new Metadata();
                    metadata.put("doc_title", filename);
                    metadata.put("source", "local");

                    Document doc = Document.from(content, metadata);
                    documents.add(doc);

                    log.debug("加载文档: {} (大小: {} 字符)", filename, content.length());
                } catch (IOException e) {
                    log.error("读取文档失败: {}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.error("扫描知识库路径失败: {}", KNOWLEDGE_BASE_PATH, e);
        }

        return documents;
    }

    /**
     * 批量向量化文本段落
     *
     * @param segments 文本段落列表
     * @return 向量列表
     */
    private List<Embedding> embedSegmentsBatch(List<TextSegment> segments) {
        List<Embedding> allEmbeddings = new ArrayList<>();

        // 分批调用 EmbeddingModel（避免单次请求过大）
        for (int i = 0; i < segments.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, segments.size());
            List<TextSegment> batch = segments.subList(i, end);

            try {
                // LangChain4j embedAll 方法批量向量化
                List<Embedding> batchEmbeddings = embeddingModel.embedAll(batch).content();
                allEmbeddings.addAll(batchEmbeddings);

                log.debug("批量向量化进度: {}/{}", end, segments.size());
            } catch (Exception e) {
                log.error("向量化失败，批次 [{}-{}]", i, end, e);
                throw new RuntimeException("向量化失败", e);
            }
        }

        return allEmbeddings;
    }

    /**
     * 构建数据库实体列表
     *
     * @param docTitle   文档标题
     * @param segments   文本段落
     * @param embeddings 向量
     * @return 实体列表
     */
    private List<KnowledgeChunkEntity> buildChunkEntities(String docTitle,
                                                           List<TextSegment> segments,
                                                           List<Embedding> embeddings) {
        List<KnowledgeChunkEntity> entities = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Embedding embedding = embeddings.get(i);

            Metadata meta = segment.metadata();

            // 从元数据获取知识治理字段，提供默认值
            Integer version = meta.getInteger("version") != null ? meta.getInteger("version") : 1;
            String status = meta.getString("status") != null ? meta.getString("status") : "ACTIVE";
            String knowledgeSource = meta.getString("knowledge_source") != null ? meta.getString("knowledge_source") : "UNKNOWN";

            // LocalDateTime 字段需要特殊处理（Metadata 存储为 String）
            LocalDateTime effectiveAt = null;
            LocalDateTime expiredAt = null;
            String effectiveAtStr = meta.getString("effective_at");
            String expiredAtStr = meta.getString("expired_at");
            if (effectiveAtStr != null && !effectiveAtStr.isEmpty()) {
                try { effectiveAt = LocalDateTime.parse(effectiveAtStr); } catch (Exception e) { log.warn("解析 effective_at 失败: {}", effectiveAtStr); }
            }
            if (expiredAtStr != null && !expiredAtStr.isEmpty()) {
                try { expiredAt = LocalDateTime.parse(expiredAtStr); } catch (Exception e) { log.warn("解析 expired_at 失败: {}", expiredAtStr); }
            }

            KnowledgeChunkEntity entity = KnowledgeChunkEntity.builder()
                    .docTitle(docTitle)
                    .sectionHeader(meta.getString("section_header"))
                    .content(segment.text())
                    .parentId(meta.getString("parent_id"))
                    .parentText(meta.getString("parent_text"))
                    .chunkMeta(toJson(meta))  // 必须是合法 JSON，列类型为 JSONB
                    .embedding(embeddingToString(embedding))  // 转换为 PostgreSQL VECTOR 格式
                    .version(version)
                    .status(status)
                    .knowledgeSource(knowledgeSource)
                    .effectiveAt(effectiveAt)
                    .expiredAt(expiredAt)
                    .build();

            entities.add(entity);
        }

        return entities;
    }

    /**
     * 将切片元数据序列化为合法 JSON
     * <p>
     * 不能用 {@code Metadata.toMap().toString()}——那产出的是 Java Map 的
     * {@code {k=v}} 格式，既非合法 JSON，也会被 JSONB 列拒绝。
     * </p>
     * <p>
     * 序列化失败时返回 {@code {}} 而非 null：元数据是辅助信息，
     * 不应因其序列化问题导致整个切片入库失败。
     * </p>
     */
    private String toJson(Metadata meta) {
        try {
            return objectMapper.writeValueAsString(meta.toMap());
        } catch (Exception e) {
            log.warn("切片元数据序列化失败，以空对象入库: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 将 LangChain4j Embedding 转换为 PostgreSQL VECTOR 字符串格式
     * 格式：[0.1, 0.2, 0.3, ...]
     *
     * @param embedding 向量对象
     * @return 向量字符串
     */
    private String embeddingToString(Embedding embedding) {
        float[] vector = embedding.vector();
        return Arrays.toString(vector);  // 生成 [0.1, 0.2, ...] 格式
    }

    /**
     * 摄取结果 DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class IngestionResult {
        /**
         * 加载的文档数
         */
        private int documentsLoaded;

        /**
         * 入库的切片数
         */
        private int chunksIngested;

        /**
         * 总耗时（毫秒）
         */
        private long elapsedMs;
    }
}
