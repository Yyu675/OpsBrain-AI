package com.devops.agent.domain.ai;

import java.time.LocalDateTime;

/**
 * AI 渠道配置变更历史条目（V5：回滚依据）。
 *
 * <p>每次编辑/重置/回滚前把旧行整行快照进 {@code sys_ai_channel_history}。
 * 安全约束与 {@link AiChannel} 一致：{@code apiKeyEnc}/{@code fallbackApiKeyEnc}
 * 承载密文，<b>永不序列化出 API</b>——对前端只暴露脱敏串。</p>
 *
 * @param id                历史行号（回滚时按它定位）
 * @param channelKey        渠道键
 * @param baseUrl           端点
 * @param apiKeyEnc         key 密文（内部承载）
 * @param maskedKey         key 脱敏展示
 * @param turboModel        chat turbo 模型
 * @param reasonerModel     chat reasoner 模型
 * @param model             embedding/reranker 模型
 * @param dimension         向量维度
 * @param status            状态
 * @param fallbackBaseUrl   备用端点
 * @param fallbackModel     备用模型
 * @param fallbackApiKeyEnc 备用 key 密文
 * @param fallbackMaskedKey 备用 key 脱敏
 * @param changedAt         快照时间（= 这次变更发生前的最后状态时刻）
 * @param changedBy         操作人
 * @param changeNote        变更说明
 */
public record AiChannelHistory(
        Long id,
        String channelKey,
        String baseUrl,
        String apiKeyEnc,
        String maskedKey,
        String turboModel,
        String reasonerModel,
        String model,
        Integer dimension,
        String status,
        String fallbackBaseUrl,
        String fallbackModel,
        String fallbackApiKeyEnc,
        String fallbackMaskedKey,
        LocalDateTime changedAt,
        String changedBy,
        String changeNote
) {

    /** 从当前渠道行生成快照（变更前调用）。 */
    public static AiChannelHistory snapshotOf(AiChannel ch, String changedBy, String changeNote) {
        return new AiChannelHistory(
                null, ch.channelKey(), ch.baseUrl(), ch.apiKeyEnc(), ch.maskedKey(),
                ch.turboModel(), ch.reasonerModel(), ch.model(), ch.dimension(), ch.status(),
                ch.fallbackBaseUrl(), ch.fallbackModel(), ch.fallbackApiKeyEnc(), ch.fallbackMaskedKey(),
                null, changedBy, changeNote);
    }

    /** 转回渠道行（回滚用）。updatedAt 由 DB 重写。 */
    public AiChannel toChannel() {
        return new AiChannel(channelKey, baseUrl, apiKeyEnc, maskedKey,
                turboModel, reasonerModel, model, dimension, status, null,
                fallbackBaseUrl, fallbackModel, fallbackApiKeyEnc, fallbackMaskedKey);
    }
}
