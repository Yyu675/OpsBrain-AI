package com.devops.agent.domain.ai;

import java.time.LocalDateTime;

/**
 * AI 模型渠道配置实体（阶段A-P0 模型配置可视化）。
 * <p>
 * 对应表 {@code sys_ai_channel}。channel_key 为 chat / embedding / reranker。
 * 安全约束：{@code apiKeyEnc} 承载加密 key，<b>永不序列化出 API</b>——
 * 对前端只暴露 {@link #maskedKey()}（脱敏 key），明文/密文 key 只存 DB。
 * 接口返回经 {@code ModelChannelController} 的 DTO 过滤，绝不透出 apiKeyEnc。
 * </p>
 *
 * <h3>备用模型（方案 A：模型池降级，V4）</h3>
 * <p>仅 chat 渠道有意义。fallbackBaseUrl 与 fallbackModel 必须同存同空；
 * embedding 渠道禁用备用——维度铁律 1536 维，换 embedding 模型等于
 * 向量语义空间不兼容（{@code ModelFingerprintGuard} 拦截的就是这种静默混用）。</p>
 *
 * @param channelKey        渠道键（chat/embedding/reranker）
 * @param baseUrl           端点地址
 * @param apiKeyEnc         加密后的 key（内部承载，表字段 api_key_enc）
 * @param maskedKey         脱敏展示 key（表字段 key_masked）
 * @param turboModel        chat 渠道 turbo 模型
 * @param reasonerModel     chat 渠道 reasoner 模型
 * @param model             embedding/reranker 渠道模型名
 * @param dimension         向量维度（仅 embedding 有值）
 * @param status            状态
 * @param updatedAt         更新时间
 * @param fallbackBaseUrl   备用模型端点（仅 chat；与 fallbackModel 同存同空）
 * @param fallbackModel     备用模型名（主模型熔断时自动切换）
 * @param fallbackApiKeyEnc 备用 key 密文（规则同 apiKeyEnc，永不外泄）
 * @param fallbackMaskedKey 备用 key 脱敏展示
 */
public record AiChannel(
        String channelKey,
        String baseUrl,
        String apiKeyEnc,
        String maskedKey,
        String turboModel,
        String reasonerModel,
        String model,
        Integer dimension,
        String status,
        LocalDateTime updatedAt,
        String fallbackBaseUrl,
        String fallbackModel,
        String fallbackApiKeyEnc,
        String fallbackMaskedKey
) {

    public static final String KEY_CHAT = "chat";
    public static final String KEY_EMBEDDING = "embedding";
    public static final String KEY_RERANKER = "reranker";

    /** 无备用模型的便捷构造（绝大多数调用点不关心 fallback）。 */
    public AiChannel(String channelKey, String baseUrl, String apiKeyEnc, String maskedKey,
                     String turboModel, String reasonerModel, String model,
                     Integer dimension, String status, LocalDateTime updatedAt) {
        this(channelKey, baseUrl, apiKeyEnc, maskedKey, turboModel, reasonerModel,
                model, dimension, status, updatedAt, null, null, null, null);
    }

    /** 备用模型是否已配置（baseUrl 与 model 同存才算数）。 */
    public boolean hasFallback() {
        return fallbackBaseUrl != null && !fallbackBaseUrl.isBlank()
                && fallbackModel != null && !fallbackModel.isBlank();
    }
}
