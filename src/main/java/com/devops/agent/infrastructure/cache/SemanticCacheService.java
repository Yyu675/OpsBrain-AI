package com.devops.agent.infrastructure.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.devops.agent.infrastructure.concurrent.ManagedExecutors;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * M4 语义缓存模块 - 基于向量相似度的智能缓存
 *
 * <p>核心逻辑：
 * <ul>
 *   <li>用户提问向量化后，与近期热点问题向量计算余弦相似度</li>
 *   <li>相似度 ≥ 阈值判定为同义问题，直接返回缓存答案</li>
 *   <li>缓存命中：耗时 &lt;50ms，0 生成 Token，0 生成成本</li>
 * </ul>
 *
 * <p><b>全部参数均来自配置</b>（{@code devops.ai.semantic-cache.*}）：
 * {@code enabled} / {@code similarity-threshold} / {@code ttl} /
 * {@code max-hot-queries}。此前这四项在代码里被硬编码（阈值 0.95、
 * TTL 24h），与配置文件的 0.85、3600s 不一致——改配置不生效且无从察觉。
 *
 * <p>Redis 键设计：
 * <ul>
 *   <li>答案文本：{@code devops:cache:ans:{sha256(query)}}
 *       —— 用摘要而非原文，见 {@link #answerKey}</li>
 *   <li>向量缓存：JVM 内 {@code LinkedHashMap(accessOrder=true)}，
 *       真 LRU 淘汰</li>
 * </ul>
 *
 * <p><b>一致性边界</b>：知识库更新后必须调 {@link #clearAllCache()}，
 * 否则旧答案会在 TTL 内继续命中——用户拿到的是基于旧文档的回答，
 * 而这种「答案陈旧」比「无答案」更难排查。该调用已接入
 * {@code KnowledgeManageController} 的摄取端点。
 *
 * @author OpsBrain AI Team
 * @version 1.0
 * @since 2026-07-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private final StringRedisTemplate redisTemplate;
    private final EmbeddingModel embeddingModel;

    /**
     * 缓存开关
     * <p>此前 {@code devops.ai.semantic-cache.enabled} 配置项存在但无人读取，
     * 关掉它没有任何效果。</p>
     */
    @Value("${devops.ai.semantic-cache.enabled:true}")
    private boolean cacheEnabled;

    /**
     * 缓存答案 TTL（秒）
     * <p>此前硬编码 24 小时，与配置项 {@code ttl: 3600}（1 小时）不一致，
     * 改配置无效。知识库更新后陈旧答案会多留 23 小时。</p>
     */
    @Value("${devops.ai.semantic-cache.ttl:3600}")
    private long cacheTtlSeconds;

    /**
     * 语义相似度阈值
     * <p>此前硬编码 0.95，而配置文件写的是 0.85。运维改了配置以为放宽了
     * 命中条件，实际仍按 0.95 执行——命中率远低于预期且无从察觉。</p>
     */
    @Value("${devops.ai.semantic-cache.similarity-threshold:0.85}")
    private double similarityThreshold;

    /**
     * 热点向量缓存容量上限
     */
    @Value("${devops.ai.semantic-cache.max-hot-queries:100}")
    private int maxHotQueries;

    /**
     * 缓存写入专用线程池
     * <p>P2-26：{@link #putCache} 含向量化远程调用（≈200ms），
     * 同步等待会阻塞调用方（对话主线程）。固定 2 线程避免无限制积压。</p>
     */
    private ExecutorService cacheExecutor;

    /**
     * Redis Key 前缀
     */
    private static final String ANSWER_KEY_PREFIX = "devops:cache:ans:";

    /**
     * 热点问题向量缓存（JVM 内存，避免频繁查 Redis）
     * <p>
     * 用 {@code LinkedHashMap(accessOrder=true)} + {@code removeEldestEntry}
     * 实现真正的 LRU。此前是 {@code ConcurrentHashMap} +
     * {@code keySet().stream().limit(20)} 淘汰——但 {@code ConcurrentHashMap}
     * 的迭代顺序由哈希桶决定，与插入和访问顺序<b>都无关</b>，
     * 所谓「FIFO 淘汰」实际是随机删除，高频热点问题可能刚写入就被淘汰。
     * </p>
     * <p>
     * 在 {@link #init()} 中创建而非字段初始化：{@code @Value} 注入发生在
     * 字段初始化<i>之后</i>，若在字段初始化时读 {@code maxHotQueries} 会拿到 0。
     * </p>
     */
    private Map<String, Embedding> hotQueryVectorCache;

    @jakarta.annotation.PostConstruct
    void init() {
        // 淘汰阈值 = 配置值，仅用 max(1,…) 兜住 0 或负数这种无意义配置。
        // 注意不要写成 max(16, …)：16 是下面 LinkedHashMap 构造器的
        // **初始哈希桶数**（性能提示），与「最多保留几条」是两个概念。
        // 混淆二者会让小于 16 的配置被静默抬高——配置写了但不生效，
        // 正是本轮在修的同类缺陷。
        final int capacity = Math.max(1, maxHotQueries);
        // synchronizedMap 包装：LinkedHashMap 非线程安全，
        // 而本服务被并发的 SSE 请求共享；且 accessOrder=true 的 get()
        // 会改写链表结构，并发读同样需要互斥
        hotQueryVectorCache = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Embedding> eldest) {
                        return size() > capacity;
                    }
                });
        // P2-26：缓存写入线程池。固定 2 线程（daemon），
        // 专供 putCache 的向量化远程调用（≈200ms），避免阻塞调用方。
        // 用后置写池而非调用方线程执行，关闭无语义（daemon 不阻止 JVM 退出）
        // F4：有界队列 + 丢弃告警。原 Executors.newFixedThreadPool 的队列
        // 是无界的，缓存写入若堆积会无声涨到 OOM。
        // 缓存写入属「丢了只影响命中率」，不该用 CallerRuns 拖慢对话主链路，
        // 故队列满时直接丢弃并告警（与审计池的取舍相反，见 ManagedExecutors 注释）。
        cacheExecutor = ManagedExecutors.forBestEffort("semantic-cache-writer", 2, 500);
        log.info("🧠 [SemanticCache] 初始化 | enabled={} | threshold={} | ttl={}s | 热点容量={}",
                cacheEnabled, similarityThreshold, cacheTtlSeconds, capacity);
    }

    /**
     * 尝试命中语义缓存
     *
     * <p>流程：
     * <ol>
     *   <li>将用户问题向量化</li>
     *   <li>与热点问题向量计算余弦相似度</li>
     *   <li>找到相似度 ≥ 0.95 的最相似问题</li>
     *   <li>从 Redis 读取该问题的缓存答案</li>
     * </ol>
     *
     * @param userQuery 用户提问
     * @return 缓存答案（命中）或 null（未命中）
     */
    /**
     * 便捷重载：按 PUBLIC 权限域查缓存。
     * <p>PUBLIC 是<b>最小权限</b>，所以这个默认值是安全的：
     * 它最多只能命中公开内容，不会越权。需要按用户权限命中受限内容的调用方
     * 必须显式传 scopeKey。</p>
     */
    public String tryHitCache(String userQuery) {
        return tryHitCache(userQuery, null);
    }

    /**
     * 便捷重载：按 PUBLIC 权限域写缓存。
     * <p>同上，写进 PUBLIC 域意味着该答案可被任何人命中，
     * 因此<b>只应用于确定不含受限内容的答案</b>。</p>
     */
    public void putCache(String userQuery, String answer) {
        putCache(userQuery, answer, null);
    }

    public String tryHitCache(String userQuery, String scopeKey) {
        if (!cacheEnabled) {
            return null;
        }
        if (userQuery == null || userQuery.isBlank()) {
            return null;
        }
        final String scope = normalizeScope(scopeKey);
        try {
            long startTime = System.currentTimeMillis();

            // 快速退出：热点缓存为空时无需向量化。
            // 向量化是一次远程 API 调用（有成本、有延迟），
            // 冷启动时对每个请求都做一次纯属浪费
            if (hotQueryVectorCache.isEmpty()) {
                log.debug("❌ 语义缓存未命中：热点缓存为空，跳过向量化");
                return null;
            }

            // 1. 将用户问题向量化
            Embedding queryEmbedding = embeddingModel.embed(userQuery).content();

            // 2. 遍历热点问题，计算余弦相似度。
            // 在 synchronized 块内快照，避免遍历时被并发写改动导致
            // ConcurrentModificationException
            Map<String, Embedding> snapshot;
            synchronized (hotQueryVectorCache) {
                snapshot = new LinkedHashMap<>(hotQueryVectorCache);
            }

            String bestMatchQuery = null;
            double bestSimilarity = 0.0;

            for (Map.Entry<String, Embedding> entry : snapshot.entrySet()) {
                // C2：只在**同一权限域**内比对。热点缓存的键是 "scope|query"，
                // 跨域的条目直接跳过——否则高权限用户问出的答案会被
                // 低权限用户用一个语义相近的问题命中，绕过全部权限检查。
                if (!sameScope(entry.getKey(), scope)) {
                    continue;
                }
                Embedding cached = entry.getValue();
                // 维度不一致直接跳过：换 Embedding 模型后旧向量残留在内存里，
                // 原实现会抛 IllegalArgumentException 导致整次缓存查询降级
                if (cached.vector().length != queryEmbedding.vector().length) {
                    // P2-20：仅跳过而不剔除会让不匹配向量永久占用容量。
                    // 换 Embedding 模型后旧向量不会再被匹配，应立即淘汰。
                    synchronized (hotQueryVectorCache) {
                        hotQueryVectorCache.remove(entry.getKey());
                    }
                    log.debug("🧹 维度不匹配，淘汰残留向量 | query=[{}]", preview(entry.getKey()));
                    continue;
                }
                double similarity = cosineSimilarity(queryEmbedding, cached);
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestMatchQuery = entry.getKey();
                }
            }

            // 3. 判断是否达到相似度阈值
            if (bestSimilarity >= similarityThreshold && bestMatchQuery != null) {
                // 4. 从 Redis 读取缓存答案
                String cachedAnswer = redisTemplate.opsForValue().get(answerKey(bestMatchQuery));

                if (cachedAnswer != null) {
                    long latency = System.currentTimeMillis() - startTime;
                    log.info("✅ 语义缓存命中！相似度={}, 耗时={}ms, 原问题=[{}], 新问题=[{}]",
                            String.format("%.4f", bestSimilarity), latency,
                            preview(bestMatchQuery), preview(userQuery));
                    return cachedAnswer;
                }
                // 向量还在内存但 Redis 答案已过期：清理该向量，
                // 否则它会持续参与相似度比较却永远取不到答案
                hotQueryVectorCache.remove(bestMatchQuery);
                log.debug("🧹 答案已过期，清理残留向量 | query=[{}]", preview(bestMatchQuery));
            }

            // 5. 未命中
            log.debug("❌ 语义缓存未命中，最高相似度={}", String.format("%.4f", bestSimilarity));
            return null;

        } catch (Exception e) {
            log.warn("⚠️ 语义缓存查询异常（降级为未命中）: {}", e.getMessage());
            return null; // 异常降级，不阻塞主流程
        }
    }

    /**
     * 写入语义缓存
     *
     * <p>异步调用，不阻塞主流程：向量化是远程 API 调用（≈200ms），
     * 若在调用方线程同步执行，对话主线程会被拖慢一个 RTT。
     * 真实实现在 {@link #cacheExecutor}（固定 2 线程）上执行，
     * P2-26 之前 javadoc 声称异步但体是同步的——与检索端
     * {@link #tryHitCache} 的同步向量化不同，写缓存没有命中延迟敏感，
     * 完全可以让出调用方线程。</p>
     *
     * @param userQuery 用户提问
     * @param answer    Agent 回答
     */
    public void putCache(String userQuery, String answer, String scopeKey) {
        if (!cacheEnabled) {
            return;
        }
        if (userQuery == null || userQuery.isBlank()) {
            return;
        }
        final String scoped = scopedQueryKey(userQuery, scopeKey);
        // 校验在调用方线程完成（便宜），仅向量化+写 Redis 下沉到写线程池
        try {
            CompletableFuture.runAsync(() -> doWriteCache(scoped, userQuery, answer), cacheExecutor);
        } catch (Exception e) {
            // runAsync 拒绝任务（线程池关闭）时不让异常逃出调用方
            log.warn("⚠️ 语义缓存写入任务提交失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 实际缓存写入（须在 {@link #cacheExecutor} 线程上执行）
     * <p>
     * 不缓存空答案与失败答案：否则用户重试同一问题会一直拿到
     * 缓存的错误提示，且缓存命中率虚高。
     * </p>
     */
    private void doWriteCache(String scopedKey, String userQuery, String answer) {
        if (answer == null || answer.isBlank()) {
            log.debug("⏭️ 跳过缓存写入：答案为空 | query=[{}]", preview(userQuery));
            return;
        }
        if (isFailureAnswer(answer)) {
            log.debug("⏭️ 跳过缓存写入：答案为失败态 | query=[{}]", preview(userQuery));
            return;
        }

        try {
            // 1. 先向量化。若失败则不写 Redis——
            // 否则答案进了 Redis 但向量没进内存，该条永远无法被命中，
            // 只是白占空间直到过期。
            // 注意向量化用**原始问题**，不能用带权限前缀的 key：
            // 前缀会污染语义，让相似度计算失真。
            Embedding queryEmbedding = embeddingModel.embed(userQuery).content();

            // 2. 存储答案到 Redis（TTL 由配置决定）。
            // C2：Redis key 由 scopedKey 派生，天然按权限域隔离。
            redisTemplate.opsForValue().set(
                    answerKey(scopedKey),
                    answer,
                    cacheTtlSeconds,
                    TimeUnit.SECONDS
            );

            // 3. 加入热点缓存。键含权限域前缀，比对时只在同域内进行。
            // 容量淘汰由 LinkedHashMap 的 removeEldestEntry 自动完成（真 LRU）
            hotQueryVectorCache.put(scopedKey, queryEmbedding);

            log.info("✅ 语义缓存写入成功: query=[{}], answerLength={}, ttl={}s",
                    preview(userQuery), answer.length(), cacheTtlSeconds);

        } catch (Exception e) {
            log.warn("⚠️ 语义缓存写入失败（不影响主流程）: {}", e.getMessage());
        }
    }

    // ==================== C2：权限域隔离 ====================

    /**
     * 权限域分隔符。
     * <p>用不可见控制字符 (U+0001) 而非 ':' 或 '|'：
     * 用户问题里完全可能出现后者，那样会让 {@link #sameScope} 的前缀判断
     * 把「问题中恰好含分隔符」误判为跨域，或反之。控制字符不会出现在正常提问中。</p>
     */
    private static final char SCOPE_SEP = '\u0001';

    /**
     * 规范化权限域标识。
     * <p>null / 空一律归为 {@code PUBLIC} —— 未标注权限域的调用按最小权限处理，
     * 而不是塞进某个"通用池"被所有人共享。</p>
     */
    private static String normalizeScope(String scopeKey) {
        return (scopeKey == null || scopeKey.isBlank()) ? "PUBLIC" : scopeKey;
    }

    /** 构造带权限域前缀的缓存键 */
    private static String scopedQueryKey(String userQuery, String scopeKey) {
        return normalizeScope(scopeKey) + SCOPE_SEP + userQuery;
    }

    /** 判断某个热点缓存键是否属于给定权限域 */
    private static boolean sameScope(String cacheKey, String scope) {
        int idx = cacheKey.indexOf(SCOPE_SEP);
        if (idx < 0) {
            // 无前缀的历史遗留条目：只允许 PUBLIC 域使用。
            // 升级后旧条目会在 TTL 到期后自然消失，期间不造成越权。
            return "PUBLIC".equals(scope);
        }
        return cacheKey.regionMatches(0, scope, 0, idx) && idx == scope.length();
    }

    /**
     * 关闭写线程池（F4）。
     * <p>daemon 线程不阻止 JVM 退出，但重新部署时给在途的缓存写入
     * 留一点时间，避免刚算好的向量白算。缓存不是关键数据，等 3 秒足够。</p>
     */
    @jakarta.annotation.PreDestroy
    public void shutdownExecutor() {
        ManagedExecutors.shutdownGracefully(cacheExecutor, "semantic-cache-writer", 3);
    }

    /**
     * 判定答案是否为失败态
     * <p>
     * 与 {@code DevOpsTools} 的兜底话术、{@code DevOpsAgentServiceImpl} 的
     * 错误提示保持一致。缓存失败答案会让用户重试时一直拿到同一个错误。
     * </p>
     */
    private boolean isFailureAnswer(String answer) {
        return answer.startsWith("❌")
                || answer.contains("服务内部异常")
                || answer.contains("Agent 执行失败")
                || answer.contains("已停止生成");
    }

    /**
     * 批量清空语义缓存（知识库更新时调用）
     *
     * <p>清空所有 devops:cache:ans:* 键，保证数据一致性
     */
    public void clearAllCache() {
        try {
            // 1. 清空 Redis 缓存。
            // 用 SCAN 而非 KEYS：KEYS 会阻塞 Redis 单线程遍历整个键空间，
            // 生产环境键量大时会造成全实例卡顿（毫秒级到秒级）。
            // SCAN 分批游标迭代，单次开销可控。
            long deleted = 0;
            var options = org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(ANSWER_KEY_PREFIX + "*")
                    .count(500)
                    .build();

            try (var cursor = redisTemplate.scan(options)) {
                java.util.List<String> batch = new java.util.ArrayList<>(500);
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= 500) {
                        Long n = redisTemplate.delete(batch);
                        deleted += (n != null ? n : 0);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    Long n = redisTemplate.delete(batch);
                    deleted += (n != null ? n : 0);
                }
            }

            // 2. 清空 JVM 向量缓存
            int vectors = hotQueryVectorCache.size();
            hotQueryVectorCache.clear();

            log.info("🗑️ 批量清空语义缓存: 删除 Redis {} 条、内存向量 {} 条", deleted, vectors);

        } catch (Exception e) {
            log.error("⚠️ 清空语义缓存失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 构造 Redis 键
     * <p>
     * 用 SHA-256 摘要而非原始问题文本作为键的原因：
     * <ul>
     *   <li><b>键长失控</b>：问题最长 1500 字符，中文 UTF-8 编码后近 4.5KB。
     *       Redis 键本身没有硬限制，但超长键会显著增加内存占用与网络开销</li>
     *   <li><b>特殊字符</b>：问题含换行、空格、冒号时，键的可读性与
     *       {@code SCAN MATCH} 的模式匹配都会受影响（冒号是本项目键的层级分隔符）</li>
     *   <li><b>可预测性</b>：摘要定长 64 字符，便于容量估算</li>
     * </ul>
     * 代价是无法从键反查原问题——但原问题已存在内存的热点缓存里，
     * 且审计日志有 {@code user_query} 字段，不依赖键做溯源。
     * </p>
     */
    private String answerKey(String userQuery) {
        return ANSWER_KEY_PREFIX + sha256(userQuery);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制支持的算法，正常不会走到这里。
            // 退化用 hashCode 保证功能可用而非直接抛异常中断缓存
            log.warn("⚠️ SHA-256 不可用，退化使用 hashCode: {}", e.getMessage());
            return String.valueOf(s.hashCode());
        }
    }

    /**
     * 日志用的问题预览
     * <p>问题最长 1500 字符，整条打进日志会淹没其他信息。</p>
     */
    private String preview(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("[\\r\\n]+", " ");
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }

    /**
     * 当前热点向量数（供看板与健康检查）
     */
    public int hotQueryCount() {
        return hotQueryVectorCache.size();
    }

    /**
     * 计算余弦相似度
     *
     * <p>公式: cos(θ) = (A · B) / (||A|| * ||B||)
     *
     * @param embedding1 向量1
     * @param embedding2 向量2
     * @return 相似度 [0, 1]
     */
    private double cosineSimilarity(Embedding embedding1, Embedding embedding2) {
        float[] vector1 = embedding1.vector();
        float[] vector2 = embedding2.vector();

        if (vector1.length != vector2.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        // 计算点积
        double dotProduct = 0.0;
        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
        }

        // 计算模长
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < vector1.length; i++) {
            norm1 += vector1[i] * vector1[i];
            norm2 += vector2[i] * vector2[i];
        }
        norm1 = Math.sqrt(norm1);
        norm2 = Math.sqrt(norm2);

        // 防止除零
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }

        return dotProduct / (norm1 * norm2);
    }
}
