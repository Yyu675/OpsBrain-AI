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
import type { AiChannelListResponse, AiChannelUpdatePayload, AiChannelView, ConnectivityResult } from './types'

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
