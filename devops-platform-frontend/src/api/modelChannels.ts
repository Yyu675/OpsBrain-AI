/**
 * AI 模型渠道配置 API（P0~P3：只读展示 → 编辑 → 热更新即时生效）。
 *
 * 对应后端 {@code ModelChannelController}（ADMIN-only）。
 * 安全边界：后端只回脱敏 key（maskedKey），明文/密文 key 永不流出——
 * 前端不存在任何「解密/还原 key」的能力。编辑时可提交新 key（后端加密落库）。
 * P3-1 热更新：保存成功后模型 Bean 即时原子替换生效（response.restartRequired=false）；
 * 仅热更新失败时回退为「重启后端生效」（restartRequired=true，保存动作始终落地）。
 */

import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz } from '../utils/http'
import type { AiChannelHistoryListResponse, AiChannelListResponse, AiChannelUpdatePayload, AiChannelView, ChannelProbeResult, ChannelTemplate, ConnectivityResult } from './types'

/**
 * 拉取全部生效的模型渠道配置（chat/embedding/reranker）。
 */
export async function fetchModelChannels(): Promise<AiChannelListResponse> {
  const payload = await http.get<unknown>(API_ENDPOINTS.MODEL_CHANNELS)
  return unwrapBiz<AiChannelListResponse>(payload, '查询模型渠道配置失败')
}

/**
 * 编辑单条渠道配置（P1：DB 权威源）。空串/未传字段 = 不修改。
 * P3-1：保存成功即热更新（Chat/Embedding Bean 原子替换），响应 restartRequired=false；
 * 热更新失败时降级为 restartRequired=true（重启后端才生效）。
 */
export async function updateModelChannel(
  channelKey: 'chat' | 'embedding' | 'reranker',
  patch: AiChannelUpdatePayload,
): Promise<AiChannelView> {
  const payload = await http.put<unknown>(
    `${API_ENDPOINTS.MODEL_CHANNELS}/${channelKey}`,
    patch,
  )
  return unwrapBiz<AiChannelView>(payload, '保存渠道配置失败')
}

/**
 * 重置渠道为 yml 默认值（P2：恢复出厂设置）。
 */
export async function resetModelChannel(
  channelKey: 'chat' | 'embedding' | 'reranker',
): Promise<AiChannelView> {
  const payload = await http.post<unknown>(
    `${API_ENDPOINTS.MODEL_CHANNELS}/${channelKey}/reset`,
    {},
  )
  return unwrapBiz<AiChannelView>(payload, '重置渠道配置失败')
}

/**
 * 连通性测试（P2-3）：用当前渠道配置实测一次 API 调用。
 */
export async function testModelChannelConnectivity(
  channelKey: 'chat' | 'embedding',
): Promise<ConnectivityResult> {
  const payload = await http.post<unknown>(
    `${API_ENDPOINTS.MODEL_CHANNELS}/${channelKey}/test-connectivity`,
    {},
  )
  return unwrapBiz<ConnectivityResult>(payload, '连通性测试失败')
}

/**
 * 一键获取上游可用模型列表（P2-4）。
 * 调用 OpenAI 兼容的 GET /v1/models，失败时返回空数组——此端点非硬依赖。
 */
export async function fetchAvailableModels(
  channelKey: 'chat' | 'embedding' | 'reranker',
): Promise<string[]> {
  const payload = await http.post<unknown>(
    `${API_ENDPOINTS.MODEL_CHANNELS}/${channelKey}/available-models`,
    {},
  )
  const r = unwrapBiz<{ data?: string[] }>(payload, '获取模型列表失败')
  return r.data ?? []
}

// ==================== V5：变更历史 + 回滚 ====================

/**
 * 拉渠道变更历史（新→旧）。历史行承载变更前整行快照（脱敏视图）。
 * limit 后端 clamp 到 1~100，默认 20 足够「改错了回去看一眼」。
 */
export async function fetchChannelHistory(
  channelKey: 'chat' | 'embedding' | 'reranker',
  limit = 20,
): Promise<AiChannelHistoryListResponse> {
  const payload = await http.get<unknown>(
    `${API_ENDPOINTS.MODEL_CHANNELS}/${channelKey}/history?limit=${limit}`,
  )
  return unwrapBiz<AiChannelHistoryListResponse>(payload, '查询渠道变更历史失败')
}

/**
 * 回滚到指定历史版本。回滚也是变更：后端会先快照当前态再写回，
 * 滚错了还能再滚回来。响应带 restartRequired（同编辑的热更新契约）。
 */
export async function rollbackModelChannel(
  channelKey: 'chat' | 'embedding' | 'reranker',
  historyId: number,
): Promise<AiChannelView> {
  const payload = await http.post<unknown>(
    `${API_ENDPOINTS.MODEL_CHANNELS}/${channelKey}/rollback/${historyId}`,
    {},
  )
  return unwrapBiz<AiChannelView>(payload, '回滚渠道配置失败')
}

// ==================== V5：能力探测（P4） ====================

/**
 * 触发一轮能力实测（真实 API 调用，计费 + 秒级延迟，故为用户手动触发）。
 * 结果落库复用。三态语义：SUPPORTED/UNSUPPORTED/UNKNOWN，
 * UNKNOWN 是探测本身失败（超时/限流），不代表不支持。
 */
export async function probeChannelCapabilities(
  channelKey: 'chat' | 'embedding' | 'reranker',
): Promise<ChannelProbeResult> {
  const payload = await http.post<unknown>(
    `${API_ENDPOINTS.MODEL_CHANNELS}/${channelKey}/capability-probe`,
    {},
  )
  return unwrapBiz<ChannelProbeResult>(payload, '能力探测失败')
}

/**
 * 读已落库的最近探测结果（不重新实测，页面加载用）。
 * 从未探测过时后端 data 为 null → 前端返回 null（渲染「未实测」态）。
 */
export async function fetchChannelCapabilities(
  channelKey: 'chat' | 'embedding' | 'reranker',
): Promise<ChannelProbeResult | null> {
  const payload = await http.get<unknown>(
    `${API_ENDPOINTS.MODEL_CHANNELS}/${channelKey}/capabilities`,
  )
  return unwrapBiz<ChannelProbeResult | null>(payload, '查询能力探测结果失败')
}

/**
 * 列出渠道配置模板（V13 多渠道一键切换）。channelKey 可选——传入则只列该渠道适用模板。
 */
export async function fetchChannelTemplates(
  channelKey?: 'chat' | 'embedding' | 'reranker',
): Promise<ChannelTemplate[]> {
  const url = channelKey
    ? `${API_ENDPOINTS.MODEL_CHANNELS}/templates?channelKey=${channelKey}`
    : `${API_ENDPOINTS.MODEL_CHANNELS}/templates`
  const payload = await http.get<unknown>(url)
  return unwrapBiz<ChannelTemplate[]>(payload, '获取渠道模板失败')
}

/**
 * 应用模板到渠道（一键切换供应商）。apiKey 不被模板覆盖——保留渠道现有密钥。
 */
export async function applyChannelTemplate(
  channelKey: 'chat' | 'embedding' | 'reranker',
  templateId: number,
): Promise<AiChannelView> {
  const payload = await http.post<unknown>(
    `${API_ENDPOINTS.MODEL_CHANNELS}/${channelKey}/apply-template`,
    { templateId },
  )
  return unwrapBiz<AiChannelView>(payload, '应用模板失败')
}
