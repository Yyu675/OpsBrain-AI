package com.devops.agent.domain.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link Retriever} <b>可插拔契约</b>测试。
 *
 * <h3>这个类守的不是某个实现，而是「换向量库不会出事」</h3>
 * 检索后端被设计成可插拔：RAG 上层（{@code DevOpsTools}、评测、健康检查）
 * 只依赖 {@link Retriever}，把 pgvector 换成 Milvus / Qdrant 时
 * 只需新增实现类 + 改配置。
 *
 * <p>但「可插拔」只有在<b>所有实现都遵守同一组约定</b>时才成立。
 * 尤其是本接口的第一条约定——「无结果」与「服务不可用」必须给出不同返回值——
 * 一旦某个实现把故障降级成空列表，故障现象会变成用户侧的
 * 「知识库暂无相关文档」，运维于是去补一份库里本来就有的文档，
 * 真正的存储层问题无人察觉。这正是本项目历史上真实发生过的事。</p>
 *
 * <h3>为什么用遍历式而不是逐个实现写一遍</h3>
 * 当前只有一个实现（HybridRetrieverService/pgvector）。若逐个写，
 * <b>下一个实现加进来时没人会想起补测试</b>——而那恰恰是契约最容易被破坏的时刻
 * （新后端刚接上，大家关心的是「能查出东西吗」，不会有人去验
 * 「查不动的时候返回的是 null 还是空列表」）。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("Retriever 可插拔契约（所有检索后端共同遵守）")
class RetrieverContractTest {

    /** 任意一个合法可见范围；契约用例本身不关心权限细节 */
    private static final KnowledgeScope ANY_SCOPE = KnowledgeScope.anonymous();

    /**
     * 一个待检查的实现，连同「把它逼进故障态」的两个开关。
     *
     * <p>契约里有两条只有在故障态下才能验证（向量化失败 / 后端查询失败），
     * 而制造故障的方式与实现绑定（pgvector 是让 JdbcTemplate 抛异常，
     * Milvus 实现会是别的方式）。因此由每个实现自己提供「故障态工厂」，
     * 契约用例只管断言<b>返回值语义</b>。</p>
     *
     * @param name             实现名，失败信息里用
     * @param healthy          正常态实例（后端可用、库里没有匹配数据）
     * @param embeddingBroken  向量化链路故障态实例
     * @param backendBroken    检索后端故障态实例
     */
    private record Candidate(
            String name,
            Supplier<Retriever> healthy,
            Supplier<Retriever> embeddingBroken,
            Supplier<Retriever> backendBroken) {
    }

    /**
     * 待检查的实现清单。
     *
     * <p>新增检索后端时在此登记一行——刻意不做 classpath 扫描：
     * 扫描会把测试里的匿名实现也卷进来，且失败信息里看不出是谁。
     * 「漏登记」由下面的 {@code allImplementationsRegistered} 用例兜住。</p>
     */
    private static List<Candidate> candidates() {
        List<Candidate> list = new ArrayList<>();
        list.add(new Candidate(
                "HybridRetrieverService",
                () -> pgvector(false, false),
                () -> pgvector(true, false),
                () -> pgvector(false, true)));
        return list;
    }

    /**
     * 造一个 pgvector 实现实例。
     *
     * <p>字段是 {@code @Value}/{@code @Autowired} 注入的，
     * 脱离 Spring 容器时必须手工塞值——否则 minScore 会是 0.0，
     * 而 0.0 恰好是个「看起来能跑」的值，测试会在错误的前提下通过。</p>
     */
    private static Retriever pgvector(boolean embeddingFails, boolean backendFails) {
        HybridRetrieverService svc = new HybridRetrieverService();

        EmbeddingModel em = mock(EmbeddingModel.class);
        if (embeddingFails) {
            when(em.embed(anyString()))
                    .thenThrow(new RuntimeException("embedding API 额度耗尽"));
        } else {
            // 维度无所谓：向量只会被拼成字面量交给 SQL，而 SQL 这里是 mock 的
            when(em.embed(anyString()))
                    .thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f, 0.3f})));
        }

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        if (backendFails) {
            when(jdbc.queryForList(anyString(), any(Object[].class)))
                    .thenThrow(new DataAccessResourceFailureException("pgvector 连接被拒绝"));
            when(jdbc.queryForObject(anyString(), any(Class.class)))
                    .thenThrow(new DataAccessResourceFailureException("pgvector 连接被拒绝"));
        } else {
            when(jdbc.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of());
            when(jdbc.queryForObject(anyString(), any(Class.class)))
                    .thenReturn(0L);
        }

        ReflectionTestUtils.setField(svc, "embeddingModel", em);
        ReflectionTestUtils.setField(svc, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(svc, "minScore", 0.73);
        ReflectionTestUtils.setField(svc, "vectorWeight", 0.65);
        ReflectionTestUtils.setField(svc, "keywordWeight", 0.35);
        ReflectionTestUtils.setField(svc, "hybridEnabled", false);
        return svc;
    }

    // ==================== 约定 1：无结果 ≠ 服务不可用 ====================

    @Test
    @DisplayName("查不到时返回空列表——而不是 null")
    void noMatchReturnsEmptyListNotNull() {
        for (Candidate c : candidates()) {
            List<RetrievedChunk> r = c.healthy().get()
                    .retrieveWithSource("一个库里肯定没有的词", 3, ANY_SCOPE);
            assertNotNull(r, c.name() + "：后端正常但无匹配时必须返回空列表，"
                    + "返回 null 会被上层当成链路故障而提示用户重试");
            assertTrue(r.isEmpty(), c.name() + "：无匹配时列表应为空");
        }
    }

    @Test
    @DisplayName("向量化失败时返回 null——而不是空列表")
    void embeddingFailureReturnsNull() {
        // ── 本类最重要的一条 ──────────────────────────────────
        // 若这里返回空列表，用户看到的是「知识库暂无相关文档」，
        // 于是去补一份库里本来就有的文档，而 API Key 失效无人察觉。
        // 空列表与 null 的区别在这里不是风格问题，是两种完全不同的处置动作
        for (Candidate c : candidates()) {
            List<RetrievedChunk> r = c.embeddingBroken().get()
                    .retrieveWithSource("K8s Pod 排查", 3, ANY_SCOPE);
            assertNull(r, c.name() + "：向量化链路故障必须返回 null，"
                    + "降级为空列表会把故障伪装成「知识库没这篇文档」");
        }
    }

    @Test
    @DisplayName("检索后端不可用时返回 null——而不是空列表")
    void backendFailureReturnsNull() {
        for (Candidate c : candidates()) {
            List<RetrievedChunk> r = c.backendBroken().get()
                    .retrieveWithSource("K8s Pod 排查", 3, ANY_SCOPE);
            assertNull(r, c.name() + "：检索后端故障必须返回 null，语义同上");
        }
    }

    // ==================== 约定 2：不得抛异常 ====================

    @Test
    @DisplayName("任何故障态都不抛异常——异常会触发付费 embedding 重试风暴")
    void failuresNeverThrow() {
        for (Candidate c : candidates()) {
            assertDoesNotThrow(
                    () -> c.embeddingBroken().get().retrieveWithSource("x", 3, ANY_SCOPE),
                    c.name() + "：向量化故障不得抛异常");
            assertDoesNotThrow(
                    () -> c.backendBroken().get().retrieveWithSource("x", 3, ANY_SCOPE),
                    c.name() + "：后端故障不得抛异常");
        }
    }

    @Test
    @DisplayName("空白/null 查询词安全返回空列表")
    void blankQueryIsSafe() {
        for (Candidate c : candidates()) {
            Retriever r = c.healthy().get();
            assertEquals(List.of(), r.retrieveWithSource(null, 3, ANY_SCOPE),
                    c.name() + "：null 查询词应返回空列表");
            assertEquals(List.of(), r.retrieveWithSource("   ", 3, ANY_SCOPE),
                    c.name() + "：空白查询词应返回空列表");
        }
    }

    @Test
    @DisplayName("countRetrievable 在后端不可用时返回 0 而非抛异常——健康检查不该被自己弄崩")
    void countNeverThrows() {
        for (Candidate c : candidates()) {
            Retriever broken = c.backendBroken().get();
            assertEquals(0L, assertDoesNotThrow(broken::countRetrievable),
                    c.name() + "：统计失败应返回 0");
        }
    }

    // ==================== 约定 3：scope 不可为 null ====================

    @Test
    @DisplayName("scope 为 null 必须抛错——静默放行全部就是无声越权")
    void nullScopeIsRejected() {
        // 与约定 2 不冲突：约定 2 说的是「运行时故障」不抛，
        // 而 scope 传 null 是<b>编码错误</b>，越早炸越好。
        // 若这里默默按「全部可见」处理，越权不会留下任何日志或报错
        for (Candidate c : candidates()) {
            assertThrows(IllegalArgumentException.class,
                    () -> c.healthy().get().retrieveWithSource("K8s", 3, null),
                    c.name() + "：scope 为 null 必须抛 IllegalArgumentException");
        }
    }

    // ==================== 便捷形式与接口纯度 ====================

    @Test
    @DisplayName("retrieve() 把服务不可用折叠成空列表——旧契约不接受 null")
    void retrieveFoldsNullToEmpty() {
        for (Candidate c : candidates()) {
            List<String> r = c.embeddingBroken().get().retrieve("K8s", 3, ANY_SCOPE);
            assertNotNull(r, c.name() + "：retrieve() 永不返回 null（调用方按 List 直接遍历）");
            assertTrue(r.isEmpty());
        }
    }

    @Test
    @DisplayName("每个实现都有非空的后端标识，且互不重复")
    void backendIdentifiersAreUniqueAndNonBlank() {
        // backend() 是灰度迁移期「这条结果来自哪套存储」的唯一依据。
        // 重复会让两套库的日志混在一起，看到结果差异也无从判断是哪边的问题
        List<String> seen = new ArrayList<>();
        for (Candidate c : candidates()) {
            String b = c.healthy().get().backend();
            assertNotNull(b, c.name() + " 的 backend() 不能为 null");
            assertFalse(b.isBlank(), c.name() + " 的 backend() 不能为空白");
            assertFalse(seen.contains(b), "后端标识重复: " + b);
            seen.add(b);
        }
    }

    @Test
    @DisplayName("接口签名不泄漏任何存储层细节——否则换后端时上层照样得改")
    void interfaceStaysStorageNeutral() {
        /*
         * 抽象只有在「它挡住的东西真的换得掉」时才有价值。
         * 一旦有人往接口上加 JdbcTemplate、Embedding、SQL 片段这类参数，
         * 上层就会顺手用起来，接口当场退化为一个多余的间接层——
         * 而这种退化在编译期、运行期都不会报错，只有换后端那天才发现。
         */
        List<String> forbidden = List.of(
                "jdbc", "sql", "pgvector", "postgres", "milvus", "qdrant",
                "elasticsearch", "embedding", "vector");

        Map<String, String> offenders = new LinkedHashMap<>();
        for (Method m : Retriever.class.getMethods()) {
            List<Class<?>> types = new ArrayList<>(List.of(m.getParameterTypes()));
            types.add(m.getReturnType());
            for (Class<?> t : types) {
                String n = t.getName().toLowerCase();
                for (String w : forbidden) {
                    if (n.contains(w)) {
                        offenders.put(m.getName(), t.getName());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "Retriever 接口出现存储层专有类型，可插拔性已被破坏: " + offenders);
    }

    @Test
    @DisplayName("实现清单完整——新增检索后端必须登记进本测试")
    void allImplementationsRegistered() {
        List<String> registered = candidates().stream().map(Candidate::name).toList();
        List<String> expected = List.of("HybridRetrieverService");

        assertEquals(expected.size(), registered.size(),
                "实现数量与登记不符，请同步更新 candidates() 与 expected");
        assertTrue(registered.containsAll(expected),
                "缺少已知实现: " + expected + "，实际: " + registered);
    }
}
