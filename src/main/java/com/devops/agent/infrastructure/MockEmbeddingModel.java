package com.devops.agent.infrastructure;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mock Embedding 模型（开发期替身）
 * <p>
 * 用途：devops.ai.mode = MOCK 时注册，返回假向量（1536 维确定性向量）
 * </p>
 * <p>
 * <b>P2-13 修复</b>：原实现返回 1536 维<b>零向量</b>，所有文本余弦相似度恒为 0.0，
 * 始终低于语义缓存阈值 0.85 —— MOCK 模式下语义缓存<b>永不命中</b>，缓存、
 * 命中统计、成本优化验证全部落空。现改为基于文本内容哈希的确定性非零向量：
 * <ul>
 *   <li><b>确定性</b>：同一文本每次向量化结果一致（哈希种子），缓存可命中</li>
 *   <li><b>区分性</b>：不同文本大概率产生不同向量；相同/相似文本向量更接近
 *       （前 8 维取 4 个哈希分量的余弦叠加，后续维度取内容哈希）</li>
 *   <li><b>非零</b>：零点不会出现在向量中，避免相似度退化为 0</li>
 * </ul>
 * 注意：Mock 向量不能替代真实 Embedding 做精确语义检索，仅用于让缓存命中逻辑
 * 在 MOCK 模式可观测、可验证。
 * </p>
 */
@Slf4j
public class MockEmbeddingModel implements EmbeddingModel {

    /**
     * 向量维度铁律：与 VectorStoreConfig.dimension() 和 init.sql 保持一致
     */
    private static final int DIMENSION = 1536;

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        log.debug("[MockEmbeddingModel] 收到 {} 条文本向量化请求（返回 {} 维确定性向量）",
                textSegments.size(), DIMENSION);

        List<Embedding> embeddings = textSegments.stream()
                .map(segment -> Embedding.from(vectorFor(segment.text())))
                .collect(Collectors.toList());

        return Response.from(embeddings);
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return embed(textSegment.text());
    }

    @Override
    public Response<Embedding> embed(String text) {
        log.debug("[MockEmbeddingModel] 单条文本向量化: {} 字符（返回 {} 维确定性向量）",
                text.length(), DIMENSION);

        return Response.from(Embedding.from(vectorFor(text)));
    }

    /**
     * 由文本内容派生确定性非零向量（P2-13）
     * <p>
     * 方案：4 个 FNV-1a 哈希（不同种子）作为前 4 维的余弦系数，
     * 再用一个内容哈希作为后续维度的确定性伪随机种子。
     * 相同文本 → 相同向量；不同文本 → 向量不同且大概率非零。
     * </p>
     */
    private float[] vectorFor(String text) {
        long h1 = fnv1a(text, 0x01000193L);
        long h2 = fnv1a(text, 0x811C9DC5L);
        long h3 = fnv1a(text, 0x9E3779B9L);
        long h4 = fnv1a(text, 0x85EBCA6BL);

        float[] vector = new float[DIMENSION];
        // 前 4 维：4 个独立哈希的归一化余弦系数（保证整体非零）
        vector[0] = (float) ((h1 & 0xFFFF) / 65535.0) * 2.0f - 1.0f;
        vector[1] = (float) ((h2 & 0xFFFF) / 65535.0) * 2.0f - 1.0f;
        vector[2] = (float) ((h3 & 0xFFFF) / 65535.0) * 2.0f - 1.0f;
        vector[3] = (float) ((h4 & 0xFFFF) / 65535.0) * 2.0f - 1.0f;

        // 其余维度：用内容哈希作种子生成确定性伪随机值（异或洗牌）
        long seed = h1 ^ (h2 << 7) ^ (h3 << 15) ^ (h4 << 23);
        for (int i = 4; i < DIMENSION; i++) {
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            vector[i] = (float) ((seed & 0xFFFF) / 65535.0) * 2.0f - 1.0f;
        }
        return vector;
    }

    /**
     * FNV-1a 64 位哈希，不同种子产生不同哈希流
     */
    private long fnv1a(String text, long seed) {
        long hash = seed;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }
}
