package com.devops.agent.infrastructure.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 语义缓存契约测试
 * <p>
 * 覆盖本轮修复的 6 个缺陷。这些缺陷的共同特征是<b>静默失效</b>——
 * 配置项写了但不生效、淘汰策略名不符实、失败答案被缓存，
 * 全都不报错，只是行为与预期不符，运维无从察觉。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
class SemanticCacheServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private EmbeddingModel embeddingModel;
    private SemanticCacheService service;

    /** 模拟的 Redis 存储：key → value */
    private Map<String, String> redisStore;

    /**
     * 用同步执行器替换生产环境的 2 线程池（P2-26）。
     * <p>putCache 改为 {@link java.util.concurrent.CompletableFuture#runAsync} 后，
     * 测试断言依赖写入已完成。将 cacheExecutor 替换为同步执行器后，
     * putCache 在调用方线程上同步执行，无需等待/排空。
     * 生产字段类型是 {@link java.util.concurrent.ExecutorService}，
     * 故此处继承 {@link AbstractExecutorService} 以满足类型（仅 execute 真正实现）。</p>
     */
    private static class SyncExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        embeddingModel = mock(EmbeddingModel.class);
        redisStore = new HashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // 模拟 Redis 读写
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        service = new SemanticCacheService(redisTemplate, embeddingModel);
        setConfig(true, 0.85, 3600L, 100);
        service.init();
        // P2-26：putCache 改为异步，测试用同步执行器替换，使 putCache 在调用方线程同步执行
        ReflectionTestUtils.setField(service, "cacheExecutor", new SyncExecutor());
    }

    /** 注入 @Value 字段（单测无 Spring 容器） */
    private void setConfig(boolean enabled, double threshold, long ttl, int maxHot) {
        ReflectionTestUtils.setField(service, "cacheEnabled", enabled);
        ReflectionTestUtils.setField(service, "similarityThreshold", threshold);
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", ttl);
        ReflectionTestUtils.setField(service, "maxHotQueries", maxHot);
    }

    /** 重新初始化（容量变更后）并替换回同步执行器 */
    private void reinitWithSyncExecutor() {
        service.init();
        ReflectionTestUtils.setField(service, "cacheExecutor", new SyncExecutor());
    }

    /** 构造指定维度的确定性向量 */
    private Embedding vec(int dim, float base) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = base + i * 0.0001f;
        }
        return Embedding.from(v);
    }

    private void mockEmbed(String text, Embedding e) {
        when(embeddingModel.embed(text)).thenReturn(Response.from(e));
    }

    // ==================== 缺陷 1：配置项不生效 ====================

    @Test
    @DisplayName("缺陷1-a：enabled=false 时应完全不走缓存")
    void disabledFlag_shouldSkipCacheEntirely() {
        setConfig(false, 0.85, 3600L, 100);

        assertNull(service.tryHitCache("任意问题"), "关闭时不应命中");
        service.putCache("任意问题", "答案");

        // 关键：不应调用 Embedding API（那是有成本的远程调用）
        verify(embeddingModel, never()).embed(anyString());
        assertEquals(0, service.hotQueryCount(), "关闭时不应写入热点缓存");
    }

    @Test
    @DisplayName("缺陷1-b：阈值应取配置值而非硬编码 0.95")
    void threshold_shouldComeFromConfig() {
        // 造两个相似度约 0.90 的向量：配置 0.85 应命中，硬编码 0.95 则不会
        Embedding v1 = Embedding.from(new float[]{1.0f, 0.0f, 0.0f});
        Embedding v2 = Embedding.from(new float[]{0.9f, 0.436f, 0.0f}); // cos≈0.90

        mockEmbed("原问题", v1);
        service.putCache("原问题", "这是缓存的答案");

        mockEmbed("相似问题", v2);
        String hit = service.tryHitCache("相似问题");

        assertNotNull(hit, "相似度 0.90 ≥ 配置阈值 0.85 应命中。"
                + "若为 null 说明仍在用硬编码的 0.95——配置改了但不生效");
        assertEquals("这是缓存的答案", hit);
    }

    @Test
    @DisplayName("缺陷1-c：TTL 应取配置值而非硬编码 24h")
    void ttl_shouldComeFromConfig() {
        setConfig(true, 0.85, 7200L, 100);
        mockEmbed("问题", vec(8, 0.5f));

        service.putCache("问题", "答案");

        // 断言传给 Redis 的 TTL 是配置值 7200 秒，而非硬编码的 24 小时
        verify(valueOps).set(anyString(), eq("答案"), eq(7200L), eq(TimeUnit.SECONDS));
    }

    // ==================== 缺陷 2：LRU 名不符实 ====================

    @Test
    @DisplayName("缺陷2：容量满时应淘汰最久未访问项，而非随机项")
    void eviction_shouldBeTrueLru() {
        setConfig(true, 0.85, 3600L, 3);   // 容量 3
        reinitWithSyncExecutor();

        for (int i = 1; i <= 3; i++) {
            mockEmbed("q" + i, vec(8, i * 0.1f));
            service.putCache("q" + i, "a" + i);
        }
        assertEquals(3, service.hotQueryCount());

        // 访问 q1，使其成为最近使用
        mockEmbed("q1", vec(8, 0.1f));
        service.tryHitCache("q1");

        // 写入 q4 触发淘汰：应淘汰 q2（最久未访问），而非 q1
        mockEmbed("q4", vec(8, 0.4f));
        service.putCache("q4", "a4");

        assertEquals(3, service.hotQueryCount(), "容量应恒定为 3");

        // q1 刚被访问过，必须还在。原实现用 ConcurrentHashMap 的哈希序
        // 淘汰，q1 可能被误删——热点问题刚命中就被踢出
        mockEmbed("q1", vec(8, 0.1f));
        assertNotNull(service.tryHitCache("q1"),
                "q1 是最近访问项，不应被淘汰。为 null 说明淘汰策略是随机的");
    }

    // ==================== 缺陷 3：失败答案被缓存 ====================

    @Test
    @DisplayName("缺陷3：失败答案不应入缓存，否则重试永远拿到同一错误")
    void failureAnswer_shouldNotBeCached() {
        mockEmbed("会失败的问题", vec(8, 0.5f));

        service.putCache("会失败的问题", "❌ 工单创建失败!\n错误原因: 数据库连接超时");

        assertEquals(0, service.hotQueryCount(),
                "失败答案不应写入。否则用户重试时会一直命中这条错误提示");
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("缺陷3-b：空答案不应入缓存")
    void blankAnswer_shouldNotBeCached() {
        mockEmbed("问题", vec(8, 0.5f));
        service.putCache("问题", "   ");
        assertEquals(0, service.hotQueryCount());
    }

    // ==================== 缺陷 4：冷启动无谓向量化 ====================

    @Test
    @DisplayName("缺陷4：热点缓存为空时不应调用 Embedding API")
    void emptyCache_shouldSkipEmbedding() {
        String result = service.tryHitCache("首个问题");

        assertNull(result);
        verify(embeddingModel, never()).embed(anyString());
    }

    // ==================== 缺陷 5：维度不一致导致整体降级 ====================

    @Test
    @DisplayName("缺陷5：换模型后残留的异维度向量应被跳过，而非中断整次查询")
    void dimensionMismatch_shouldSkipNotAbort() {
        // 先用 8 维模型写入
        mockEmbed("旧模型问题", vec(8, 0.5f));
        service.putCache("旧模型问题", "旧答案");

        // 再写入一条同维度、与查询高度相似的
        Embedding target = Embedding.from(new float[]{1.0f, 0.0f, 0.0f});
        mockEmbed("目标问题", target);
        service.putCache("目标问题", "目标答案");

        // 换成 3 维模型查询：8 维那条应被跳过，3 维那条应正常命中
        mockEmbed("新查询", Embedding.from(new float[]{0.99f, 0.1f, 0.0f}));
        String hit = service.tryHitCache("新查询");

        assertEquals("目标答案", hit,
                "异维度向量应被跳过。原实现会抛 IllegalArgumentException"
                        + "被外层 catch 吞掉，导致整次缓存查询降级为未命中");
    }

    // ==================== 缺陷 6：答案过期后向量残留 ====================

    @Test
    @DisplayName("缺陷6：Redis 答案过期后应清理残留向量")
    void expiredAnswer_shouldCleanupVector() {
        Embedding v = Embedding.from(new float[]{1.0f, 0.0f, 0.0f});
        mockEmbed("问题", v);
        service.putCache("问题", "答案");
        assertEquals(1, service.hotQueryCount());

        // 模拟 Redis TTL 到期：清空存储但内存向量还在
        redisStore.clear();

        mockEmbed("相似问题", Embedding.from(new float[]{0.999f, 0.01f, 0.0f}));
        assertNull(service.tryHitCache("相似问题"), "答案已过期应返回未命中");

        assertEquals(0, service.hotQueryCount(),
                "残留向量应被清理。否则它会持续参与相似度比较，"
                        + "每次都算到最高分却取不到答案，白耗 CPU");
    }

    // ==================== 键设计 ====================

    @Test
    @DisplayName("Redis 键应为定长摘要，不含原始问题文本")
    void redisKey_shouldBeHashed() {
        String longQuery = "很长的问题".repeat(200);   // 1000 字符
        mockEmbed(longQuery, vec(8, 0.5f));

        service.putCache(longQuery, "答案");

        String key = redisStore.keySet().iterator().next();
        assertFalse(key.contains("很长的问题"), "键不应含原始问题文本");
        assertTrue(key.startsWith("devops:cache:ans:"), "应保留前缀便于 SCAN");
        assertEquals("devops:cache:ans:".length() + 64, key.length(),
                "SHA-256 摘要应为定长 64 字符十六进制");
    }

    @Test
    @DisplayName("空问题不应触发任何操作")
    void blankQuery_shouldBeNoop() {
        assertNull(service.tryHitCache(""));
        assertNull(service.tryHitCache(null));
        service.putCache("", "答案");
        service.putCache(null, "答案");

        verify(embeddingModel, never()).embed(anyString());
        assertEquals(0, service.hotQueryCount());
    }
}
