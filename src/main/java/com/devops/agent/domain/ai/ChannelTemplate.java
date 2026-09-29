package com.devops.agent.domain.ai;

/**
 * AI 渠道配置模板（V13 多渠道一键切换）。
 * <p>
 * 预存常见供应商的完整配置（baseUrl/协议/模型名），渠道卡片一键「从模板切换」
 * 即把模板应用进对应渠道。模板不存 apiKey——密钥由渠道现有值保留或用户重填，
 * 密钥永不预置进模板库。
 * </p>
 *
 * @param id            模板 id
 * @param channelKey    适用渠道（chat/embedding/reranker）
 * @param templateName  模板名（供应商名）
 * @param provider      供应商展示名
 * @param baseUrl       端点地址
 * @param protocol      API 协议（OPENAI_COMPATIBLE/AZURE_OPENAI/ANTHROPIC/CUSTOM）
 * @param turboModel    chat 渠道 turbo 模型
 * @param reasonerModel chat 渠道 reasoner 模型
 * @param model         embedding/reranker 渠道模型名
 * @param dimension     向量维度（仅 embedding；铁律 1536）
 * @param description   模板说明
 * @param sortOrder     排序
 */
public record ChannelTemplate(
        Long id,
        String channelKey,
        String templateName,
        String provider,
        String baseUrl,
        String protocol,
        String turboModel,
        String reasonerModel,
        String model,
        Integer dimension,
        String description,
        Integer sortOrder
) {
}
