package com.devops.agent.domain.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M5 - 混合检索服务（L4 幻觉防护）
 * <p>
 * 职责：向量检索 + 知识治理过滤 + 阈值熔断（Score &lt; 0.73 过滤）
 * </p>
 *
 * <h3>为何不用 LangChain4j 的 EmbeddingStore</h3>
 * <p>
 * 原实现走 {@code PgVectorEmbeddingStore.search()}，但该类的 SQL 硬编码了
 * 它自己的一套列名（{@code embedding_id} / {@code text} / {@code metadata}），
 * 而本项目的 {@code sys_knowledge_chunk} 用的是
 * {@code id} / {@code content} / {@code chunk_meta}。两套 schema 不兼容，
 * 结果是<b>表里有 29 个切片却检索恒返回 0 条</b>——而
 * {@code DevOpsTools} 会把这种情况呈现为「知识库无相关文档（相似度 &lt; 0.73）」，
 * 把存储层配置错误伪装成内容缺失，排查者会去补文档而非查存储。
 * 该缺陷由 {@code HybridRetrieverIntegrationTest} 实测确认。
 * </p>
 * <p>
 * 改用 JdbcTemplate 直查 pgvector 的额外收益：
 * <ul>
 *   <li>MVP-5 知识治理字段（status/effective_at/expired_at）能在同一条
 *       SQL 的 WHERE 里生效——放在内存里过滤会破坏 topK 语义
 *       （取 10 条过滤掉 8 条就只剩 2 条）</li>
 *   <li>关键词检索可与向量检索在同一查询内加权融合</li>
 * </ul>
 *
 * <h3>它是 {@link Retriever} 的 pgvector 实现</h3>
 * <p>
 * 上层（{@code DevOpsTools} 等）只依赖 {@link Retriever} 接口，
 * 因此把向量库换成 Milvus / Qdrant 时只需新增一个实现类 + 改配置，
 * RAG 上层一行不动。本类内部的 SQL、余弦距离算子、JdbcTemplate
 * 都是 pgvector 专有细节，<b>不得泄漏到接口签名上</b>。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Slf4j
@Service
public class HybridRetrieverService implements Retriever {

    /**
     * L4 相似度阈值（架构契约冻结）
     * 低于此分数的片段将被过滤，防止低质量文本污染模型
     */
    @Value("${devops.ai.hallucination.min-similarity-score:0.73}")
    private double minScore;

    /** 向量检索权重（混合检索时） */
    @Value("${devops.ai.retrieval.vector-weight:0.65}")
    private double vectorWeight;

    /** 关键词检索权重（混合检索时） */
    @Value("${devops.ai.retrieval.keyword-weight:0.35}")
    private double keywordWeight;

    /** 是否启用关键词融合。中文分词依赖 PG 配置，默认关闭以免误伤 */
    @Value("${devops.ai.retrieval.hybrid-enabled:false}")
    private boolean hybridEnabled;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 后端标识：本实现直查 pgvector。
     *
     * <p>灰度迁移到别的向量库时，两套实现会同时在线，
     * 日志里没有这个标识就分不清一条结果来自哪套存储。</p>
     */
    @Override
    public String backend() {
        return "pgvector";
    }

    /**
     * 检索入口
     *
     * @param query 用户查询
     * @param topK  返回 Top-K 个片段
     * @return 去重后的父段落文本列表，无匹配返回空列表。
     *         <b>检索服务不可用时同样返回空列表</b>——旧契约是
     *         {@code List<String>}，调用方（如集成测试）只断言非空，
     *         不能引入 null。需区分「无文档」与「服务不可用」的调用方
     *         请使用 {@link #retrieveWithSource}
     */
    @Override
    public List<String> retrieve(String query, int topK, KnowledgeScope scope) {
        List<RetrievedChunk> chunks = retrieveWithSource(query, topK, scope);
        if (chunks == null) {
            return List.of();
        }
        return chunks.stream()
                .map(RetrievedChunk::text)
                .toList();
    }

    /**
     * 检索并保留出处
     * <p>
     * 与 {@link #retrieve} 的区别是<b>不丢弃</b> {@code doc_title} 与
     * {@code section_header}。这两个字段 SQL 一直在查，此前却在返回时被扔掉，
     * 导致模型无法满足 System Prompt 的强制溯源要求，进而误答
     * 「知识库暂无相关文档」——详见 {@link RetrievedChunk} 类注释。
     * </p>
     *
     * @param query 用户查询
     * @param topK  返回 Top-K 个片段
     * @return 带出处的片段列表，无匹配返回空列表；
     *         <b>检索服务不可用（向量化/检索链路故障）返回 {@code null}</b>
     *         ——调用方必须区分两种语义：「无相关文档」引导用户补文档或换关键词，
     *         「服务不可用」应如实告知链路故障。返回 null 而非抛异常是因为
     *         {@code DevOpsTools} 经 {@code ToolRuntimeManager} 执行，
     *         异常会触发重试（每次重试都是付费 embedding 调用）
     */
    @Override
    public List<RetrievedChunk> retrieveWithSource(String query, int topK, KnowledgeScope scope) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // C1：scope 不可为 null。宁可抛错也不默默按「全部可见」处理——
        // 权限过滤一旦静默失效，越权是无声的，没有任何日志或报错能提示。
        // 调用方若确实是系统内部任务，应显式传 KnowledgeScopeResolver.systemScope()。
        if (scope == null) {
            throw new IllegalArgumentException(
                    "KnowledgeScope 不能为 null：检索必须携带明确的可见范围（系统任务请显式传 systemScope()）");
        }
        log.info("[HybridRetriever] 开始检索, query={}, topK={}, hybrid={}, scope={}",
                query, topK, hybridEnabled, scope.describe());

        // 1. 向量化用户查询
        Embedding queryEmbedding;
        try {
            queryEmbedding = embeddingModel.embed(query).content();
        } catch (Exception e) {
            // 向量化失败（API Key 失效、额度耗尽、网络故障）不应抛给上层——
            // 否则经 ToolRuntimeManager 会触发重试风暴，且每次重试都是付费
            // embedding 调用。也不应降级为空列表——那会被上层伪装成
            // 「知识库无相关文档」，误导用户去补文档。返回 null 作为显式
            // 「服务不可用」信号，由调用方区分两种语义。
            log.error("[HybridRetriever] 查询向量化失败，返回服务不可用信号: {}", e.getMessage());
            return null;
        }

        String vectorLiteral = toVectorLiteral(queryEmbedding);

        // 2. 向量检索 + 治理过滤 + L4 熔断（全部在 SQL 内完成）
        List<Map<String, Object>> rows;
        try {
            rows = hybridEnabled
                    ? searchHybrid(vectorLiteral, query, topK * 2, scope)
                    : searchByVector(vectorLiteral, topK * 2, scope);
        } catch (Exception e) {
            // 与向量化失败同理：返回 null 显式标记链路故障，不得伪装成无文档
            log.error("[HybridRetriever] 检索执行失败，返回服务不可用信号: {}", e.getMessage());
            return null;
        }

        log.info("[HybridRetriever] 检索返回 {} 个匹配(minScore={})", rows.size(), minScore);

        if (rows.isEmpty()) {
            log.warn("[HybridRetriever] L4 熔断触发: 无匹配片段(Score < {})", minScore);
            return List.of();
        }

        // 3. 提取父段落并去重（多个子段落可能属于同一父段落）
        //
        // 去重键 = content_hash + doc_title（P2-17）：content_hash 是切片
        // 正文的 SHA-256，同文档内完全重复的切片哈希相同被合并；而跨文档
        // 的相同段落（如两篇手册都抄了同一段规范）哈希也相同，若仅按哈希
        // 去重会丢掉第二个出处，导致模型无从溯源。键中带上 doc_title 即
        // 保留两篇文档的各自出处。
        // 首次出现的出处优先保留——SQL 已按相似度排序，
        // 先出现的那条与查询更相关，其章节标题更贴切。
        Map<String, RetrievedChunk> uniqueParents = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String contentHash = (String) row.get("content_hash");
            String parentText = (String) row.get("parent_text");
            String content = (String) row.get("content");

            // 有父段落则用父段落（上下文更完整），否则退化用子段落
            String text = (parentText != null && !parentText.isBlank()) ? parentText : content;
            if (text != null && !text.isBlank()) {
                // 同一文档内同哈希（完全重复切片）合并；跨文档同内容保留两出处。
                // content_hash 为 NULL 时退化为按正文去重（不影响正确性）
                String docTitle = (String) row.get("doc_title");
                String dedupKey = (contentHash != null ? contentHash : text) + "|" + docTitle;
                if (!uniqueParents.containsKey(dedupKey)) {
                    uniqueParents.put(dedupKey, new RetrievedChunk(
                            docTitle,
                            (String) row.get("section_header"),
                            text,
                            toDouble(row.get("score"))
                    ));
                }
            }

            if (log.isDebugEnabled()) {
                String preview = content != null
                        ? content.substring(0, Math.min(50, content.length())) : "";
                log.debug("[HybridRetriever] 匹配片段: score={}, doc={}, text={}",
                        row.get("score"), row.get("doc_title"), preview);
            }

            if (uniqueParents.size() >= topK) {
                break;
            }
        }

        List<RetrievedChunk> result = new ArrayList<>(uniqueParents.values());
        log.info("[HybridRetriever] 返回 {} 个去重父段落（含出处）", result.size());
        return result;
    }

    /**
     * score 列的类型转换
     * <p>
     * pgvector 的表达式结果可能是 {@code Double}/{@code Float}/{@code BigDecimal}，
     * 直接强转会在部分驱动版本下抛 ClassCastException。
     * </p>
     */
    private double toDouble(Object v) {
        return (v instanceof Number n) ? n.doubleValue() : 0.0;
    }

    /**
     * 纯向量检索
     * <p>
     * 余弦距离转相似度：pgvector 的 {@code <=>} 返回余弦距离（0~2），
     * {@code 1 - distance} 即相似度（-1~1）。
     * </p>
     * <p>
     * 治理过滤在 WHERE 中完成，使 MVP-5 的 status/effective_at/expired_at
     * 真正生效——废弃或过期的文档不会进入检索结果。
     * </p>
     */
    private List<Map<String, Object>> searchByVector(String vectorLiteral, int limit, KnowledgeScope scope) {
        // C1：权限谓词直接拼进 WHERE（谓词本身是常量串，部门名走占位参数，
        // 不存在注入面）。之所以不 JOIN sys_knowledge_doc 取 visibility，
        // 是因为带 JOIN 的 ORDER BY embedding <=> ? 会让 PG 放弃 HNSW 索引
        // 走全表扫描——切片上量后从毫秒退化到秒级。故 chunk 表冗余了这两列。
        String sql = """
            SELECT id, content, parent_text, doc_title, section_header,
                   content_hash,
                   1 - (embedding <=> ?::vector) AS score
              FROM sys_knowledge_chunk
             WHERE status = 'ACTIVE'
               AND (effective_at IS NULL OR effective_at <= CURRENT_TIMESTAMP)
               AND (expired_at   IS NULL OR expired_at   >  CURRENT_TIMESTAMP)
               AND %s
               AND 1 - (embedding <=> ?::vector) >= ?
             ORDER BY embedding <=> ?::vector
             LIMIT ?
            """.formatted(scope.toSqlPredicate());

        // 参数顺序必须与 SQL 中占位符出现顺序严格一致：
        // [1] SELECT 里的向量  → [2] 权限谓词的 dept(可能没有) → [3] 阈值比较的向量
        // → [4] minScore → [5] ORDER BY 的向量 → [6] limit
        List<Object> args = new ArrayList<>();
        args.add(vectorLiteral);
        args.addAll(java.util.Arrays.asList(scope.sqlParams()));
        args.add(vectorLiteral);
        args.add(minScore);
        args.add(vectorLiteral);
        args.add(limit);
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    /**
     * 混合检索：向量 + 关键词加权融合
     * <p>
     * {@code finalScore = 0.65 * 向量相似度 + 0.35 * 关键词 rank}
     * </p>
     * <p>
     * 默认关闭：{@code content_tsv} 需由触发器维护且中文分词依赖 PG 配置
     * （{@code zhparser}/{@code pg_jieba} 等扩展），未装扩展时
     * {@code to_tsvector('simple', ...)} 对中文几乎无效，
     * 贸然启用会让 35% 的权重变成噪音。
     * </p>
     */
    private List<Map<String, Object>> searchHybrid(String vectorLiteral, String query, int limit,
                                                   KnowledgeScope scope) {
        String sql = """
            SELECT id, content, parent_text, doc_title, section_header,
                   content_hash,
                   (? * (1 - (embedding <=> ?::vector))
                    + ? * COALESCE(ts_rank(content_tsv, plainto_tsquery('simple', ?)), 0)
                   ) AS score
              FROM sys_knowledge_chunk
             WHERE status = 'ACTIVE'
               AND (effective_at IS NULL OR effective_at <= CURRENT_TIMESTAMP)
               AND (expired_at   IS NULL OR expired_at   >  CURRENT_TIMESTAMP)
               AND %s
               AND 1 - (embedding <=> ?::vector) >= ?
             ORDER BY score DESC
             LIMIT ?
            """.formatted(scope.toSqlPredicate());

        List<Object> args = new ArrayList<>();
        args.add(vectorWeight);
        args.add(vectorLiteral);
        args.add(keywordWeight);
        args.add(query);
        args.addAll(java.util.Arrays.asList(scope.sqlParams()));
        args.add(vectorLiteral);
        args.add(minScore);
        args.add(limit);
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    /**
     * 将 Embedding 转为 pgvector 字面量
     * <p>格式：{@code [0.1,0.2,0.3]}</p>
     */
    private String toVectorLiteral(Embedding embedding) {
        float[] v = embedding.vector();
        StringBuilder sb = new StringBuilder(v.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 统计当前可检索的切片数
     * <p>供健康检查与看板使用：区分「知识库为空」与「检索链路故障」。</p>
     */
    @Override
    public long countRetrievable() {
        try {
            Long n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_knowledge_chunk
                 WHERE status = 'ACTIVE'
                   AND (effective_at IS NULL OR effective_at <= CURRENT_TIMESTAMP)
                   AND (expired_at   IS NULL OR expired_at   >  CURRENT_TIMESTAMP)
                """, Long.class);
            return n != null ? n : 0L;
        } catch (Exception e) {
            log.warn("[HybridRetriever] 统计可检索切片失败: {}", e.getMessage());
            return 0L;
        }
    }
}
