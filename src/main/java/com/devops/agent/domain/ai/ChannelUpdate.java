package com.devops.agent.domain.ai;

/**
 * 渠道配置编辑请求（P1：DB 权威源；方案 A：备用模型降级）。
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li>所有字段 <b>null = 不修改</b>，仅 baseUrl 与各渠道的模型名必填；</li>
 *   <li>apiKey 为空时保留库内既有密钥——改模型名不该把 key 一起抹掉；</li>
 *   <li>dimension 只能给 devops.ai.vector.dimension 的同值——
 *       维度铁律（AGENTS §3.3）要求基线 VECTOR(n)、配置、索引三处联动，
 *       在 UI 上改维度只会让三者静默漂移，故此字段拒绝新值，只允许原样回传；</li>
 *   <li>fallback* 仅 chat 渠道接受：fallbackBaseUrl 与 fallbackModel
 *       必须同时出现（半套备用配置在主模型熔断时才会暴露，属于延迟爆炸）；
 *       clearFallback=true 时整组清空（含 key）。</li>
 * </ul>
 *
 * @param channelKey      渠道键（chat/embedding/reranker）
 * @param baseUrl         端点地址
 * @param turboModel      chat：turbo 模型
 * @param reasonerModel   chat：reasoner 模型
 * @param model           embedding/reranker：模型名
 * @param dimension       向量维度（仅 embedding；必须等于配置值）
 * @param apiKey          新 API Key（可选；空 = 保留既有）
 * @param status          ACTIVE / DISABLED（空 = 不改）
 * @param fallbackBaseUrl 备用模型端点（仅 chat；与 fallbackModel 同存）
 * @param fallbackModel   备用模型名（仅 chat）
 * @param fallbackApiKey  备用模型 Key（可选；空 = 保留既有备用 key）
 * @param clearFallback   true = 清除整组备用配置
 * @param protocol        API 协议（V12 显式可配置，null = 不改）：
 *                        OPENAI_COMPATIBLE / AZURE_OPENAI / ANTHROPIC / CUSTOM
 * @param provider        供应商名称（可显式编辑；null = 不改，展示层从 baseUrl 推断兜底）
 */
public record ChannelUpdate(
        String channelKey,
        String baseUrl,
        String turboModel,
        String reasonerModel,
        String model,
        Integer dimension,
        String apiKey,
        String status,
        String fallbackBaseUrl,
        String fallbackModel,
        String fallbackApiKey,
        Boolean clearFallback,
        String protocol,
        String provider
) {

    /** 无 protocol/provider 的 12 参便捷构造（存量调用点兼容）。 */
    public ChannelUpdate(String channelKey, String baseUrl, String turboModel,
                         String reasonerModel, String model, Integer dimension,
                         String apiKey, String status,
                         String fallbackBaseUrl, String fallbackModel, String fallbackApiKey,
                         Boolean clearFallback) {
        this(channelKey, baseUrl, turboModel, reasonerModel, model, dimension,
                apiKey, status, fallbackBaseUrl, fallbackModel, fallbackApiKey, clearFallback,
                null, null);
    }

    /** 无 fallback 字段的便捷构造（既有调用点不受影响）。 */
    public ChannelUpdate(String channelKey, String baseUrl, String turboModel,
                         String reasonerModel, String model, Integer dimension,
                         String apiKey, String status) {
        this(channelKey, baseUrl, turboModel, reasonerModel, model, dimension,
                apiKey, status, null, null, null, null);
    }
}
