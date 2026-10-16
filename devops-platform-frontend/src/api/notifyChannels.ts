/**
 * 通知渠道配置 API（批89 · 方案1——钉钉/企微 webhook 页面热改 + 测试发送 + 明文查看）。
 *
 * 对应后端 {@code NotifyChannelController}（ADMIN-only + 审计 action 登记）。
 * 安全边界：列表永远打码；明文只经 reveal 端点（后端 INFO 日志留痕）；
 * 保存即热生效（每发送时 effective() 三层回落，零重启）。
 */

import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz } from '../utils/http'
import type {
  NotifyChannelList,
  NotifyChannelView,
  NotifySecretRevealed,
  NotifyTestResult,
  UpdateNotifyChannelPayload,
} from './types/notify'

/**
 * 拉取整屏视图（两渠道打码 + 告警接入 secret）。
 */
export async function fetchNotifyChannels(): Promise<NotifyChannelList> {
  const payload = await http.get<unknown>(API_ENDPOINTS.NOTIFY_CHANNELS)
  return unwrapBiz<NotifyChannelList>(payload, '查询通知渠道配置失败')
}

/**
 * 保存渠道（三态：null=不动 / ""=清空 / 非空=覆盖）。保存即 DB 权威、热生效。
 */
export async function updateNotifyChannel(
  channelKey: 'dingtalk' | 'wecom',
  patch: UpdateNotifyChannelPayload,
): Promise<NotifyChannelView> {
  const payload = await http.put<unknown>(
    `${API_ENDPOINTS.NOTIFY_CHANNELS}/${channelKey}`,
    patch,
  )
  return unwrapBiz<NotifyChannelView>(payload, '保存通知渠道失败')
}

/**
 * 测试发送：后端同步真发一条（NotifyPayloads 单源构造）并直返渠道回执。
 */
export async function sendNotifyTest(
  channelKey: 'dingtalk' | 'wecom',
): Promise<NotifyTestResult> {
  const payload = await http.post<unknown>(
    `${API_ENDPOINTS.NOTIFY_CHANNELS}/${channelKey}/test`,
    {},
  )
  return unwrapBiz<NotifyTestResult>(payload, '测试发送失败')
}

/**
 * 查看渠道明文（后端 INFO 日志留痕；仅 ADMIN 可调）。
 */
export async function revealNotifyChannel(
  channelKey: 'dingtalk' | 'wecom',
): Promise<NotifySecretRevealed> {
  const payload = await http.get<unknown>(
    `${API_ENDPOINTS.NOTIFY_CHANNELS}/${channelKey}/reveal`,
  )
  return unwrapBiz<NotifySecretRevealed>(payload, '查看明文失败')
}

/**
 * 告警接入 secret：默认打码视图；reveal=true 返回明文（同留痕语义）。
 */
export async function fetchIngestSecret(
  reveal = false,
): Promise<{ configured: boolean; source: string; secretMasked: string | null; hmacConfigured: boolean; hint: string } | NotifySecretRevealed> {
  const payload = await http.get<unknown>(
    `${API_ENDPOINTS.NOTIFY_CHANNELS}/ingest-secret?reveal=${reveal}`,
  )
  return unwrapBiz(payload, '获取告警接入 secret 失败')
}

/**
 * 保存告警接入 secret（holder 热生效——下一个 webhook 请求即用新值；
 * 强警告：必须同步 Alertmanager webhook_configs，否则告警静默断链）。
 */
export async function updateIngestSecret(secret: string): Promise<{
  configured: boolean
  source: string
  secretMasked: string | null
  hmacConfigured: boolean
  hint: string
}> {
  const payload = await http.put<unknown>(
    `${API_ENDPOINTS.NOTIFY_CHANNELS}/ingest-secret`,
    { secret },
  )
  return unwrapBiz(payload, '保存告警接入 secret 失败')
}
