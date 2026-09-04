package com.devops.agent.domain.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 检索链路集成测试
 * <p>
 * 目的：验证「摄取写入的数据能否被检索读到」。
 * </p>
 * <p>
 * 背景：摄取用 JPA/原生 SQL 写 {@code sys_knowledge_chunk}（列为
 * {@code content}/{@code parent_text}/{@code chunk_meta}），
 * 而检索走 LangChain4j {@code PgVectorEmbeddingStore.search()}，
 * 后者的 SQL 硬编码了自己的一套列名（{@code embedding_id}/{@code text}/
 * {@code metadata}）。若两套 schema 不兼容，检索会恒返回 0 条或直接抛异常，
 * 而 {@code DevOpsTools} 会把这种情况呈现为「知识库无相关文档（相似度<0.73）」——
 * 把配置错误伪装成内容缺失，排查者会去补文档而非查存储层。
 * </p>
 * <p>
 * 本测试用 MOCK 模式：MockEmbeddingModel 产生确定性向量，
 * 无需真实 API Key，可在 CI 中稳定运行。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK",
        // MockEmbeddingModel 产生确定性哈希向量，不同文本间余弦相似度≈0，
        // 调低 0 让所有切片通过 minScore 过滤，以验证「摄取→检索」管道完整性。
        // 语义检索质量由 REAL 模式 EmbeddingModel 保证，不在本测试范围内。
        "devops.ai.hallucination.min-similarity-score=0"
})
@DisplayName("检索链路：摄取写入的数据能否被检索读到")
class HybridRetrieverIntegrationTest {

    @Autowired
    private KnowledgeIngestionService ingestionService;

    @Autowired
    private HybridRetrieverService retrieverService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("摄取后表内应有切片——验证写入路径")
    void ingestionShouldPersistChunks() {
        var result = ingestionService.ingestAllLocalDocuments(true);

        assertTrue(result.getDocumentsLoaded() > 0, "应扫描到知识库文档");
        assertTrue(result.getChunksIngested() > 0,
                "应有切片入库。为 0 说明切片器死循环（已修）或写入类型不匹配（vector/jsonb）");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_knowledge_chunk", Integer.class);
        assertNotNull(count);
        assertEquals(result.getChunksIngested(), count,
                "返回的入库数应与表内实际行数一致");
    }

    @Test
    @DisplayName("向量维度必须是 1536——全链路铁律")
    void embeddingDimensionMustBe1536() {
        ingestionService.ingestAllLocalDocuments(true);

        List<Integer> dims = jdbcTemplate.queryForList(
                "SELECT DISTINCT vector_dims(embedding) FROM sys_knowledge_chunk", Integer.class);

        assertEquals(1, dims.size(), "所有切片维度应一致");
        assertEquals(1536, dims.get(0),
                "维度必须为 1536，与 init.sql 的 VECTOR(1536) 及 EmbeddingModel 输出一致");
    }

    @Test
    @DisplayName("chunk_meta 必须是合法 JSON——列类型为 JSONB")
    void chunkMetaMustBeValidJson() {
        ingestionService.ingestAllLocalDocuments(true);

        // 能查出来说明 PostgreSQL 接受了它作为 jsonb。
        // 此前用 Map.toString() 产出 {k=v} 会在写入时即报错
        Integer withMeta = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_knowledge_chunk WHERE chunk_meta ? 'doc_title'",
                Integer.class);
        assertNotNull(withMeta);
        assertTrue(withMeta > 0, "元数据应含 doc_title 键，且能被 jsonb 操作符查询");
    }

    @Test
    @DisplayName("每个切片都应有 parent_text——检索依赖它返回完整上下文")
    void everyChunkShouldHaveParentText() {
        ingestionService.ingestAllLocalDocuments(true);

        Integer missing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_knowledge_chunk WHERE parent_text IS NULL OR parent_text = ''",
                Integer.class);
        assertNotNull(missing);
        assertEquals(0, missing,
                "缺 parent_text 的切片会让 HybridRetrieverService 退化为返回子片段，丢失上下文");
    }

    @Test
    @DisplayName("核心验证：检索能否读到摄取写入的数据")
    void retrievalShouldFindIngestedContent() {
        var ingested = ingestionService.ingestAllLocalDocuments(true);
        assertTrue(ingested.getChunksIngested() > 0, "前置条件：需先有数据入库");

        // 用知识库文档中确实存在的主题检索
        // C1：检索now需显式传可见范围。系统范围 = 可见全部，
        // 保持本测试原有语义（验证存储管道连通性，与权限无关）。
        List<String> results = retrieverService.retrieve(
                "Pod CrashLoopBackOff 排查", 3, KnowledgeScope.admin("TEST", null));

        assertNotNull(results, "检索不应返回 null");

        // 这是本测试的核心断言。
        // 若为空，说明摄取与检索走了两套互不兼容的存储：
        //   写入 → sys_knowledge_chunk(content, parent_text, chunk_meta)
        //   读取 → PgVectorEmbeddingStore 期望 (embedding_id, text, metadata)
        // 该情况下 DevOpsTools 会返回「相似度 < 0.73」的兜底话术，
        // 把存储层配置错误伪装成「知识库没有相关文档」
        assertFalse(results.isEmpty(),
                "检索返回 0 条。表内有 " + ingested.getChunksIngested() + " 个切片却检索不到，"
                        + "说明摄取与检索使用了不兼容的存储 schema——"
                        + "写入走 sys_knowledge_chunk 自定义列，读取走 PgVectorEmbeddingStore 期望的 "
                        + "embedding_id/text/metadata 列");
    }
}