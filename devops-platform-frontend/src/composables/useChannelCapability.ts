import type { CapabilityState, ChannelProbeResult } from '@/api/types'
import { parseDate } from '@/utils/time'

/**
 * 渠道能力探测的纯展示逻辑（批89 自 ModelChannels.vue 抽出，偿行数红线）。
 * 三态语义的权威在后端 ChannelCapabilityProbe，这里只做呈现映射。
 */

/** 三态 → Element Plus tag type。UNKNOWN 是探测失败而非不支持——灰，不是红。 */
export const capabilityTagType = (state: CapabilityState | undefined) =>
  state === 'SUPPORTED' ? 'success' : state === 'UNSUPPORTED' ? 'danger' : 'info'

export const capabilityTagText = (state: CapabilityState | undefined) =>
  state === 'SUPPORTED' ? '支持' : state === 'UNSUPPORTED' ? '不支持' : '未测成'

/** 渠道卡片上的三态摘要徽标 */
export const capabilitySummaryOf = (r: ChannelProbeResult | null | undefined) => {
  if (!r) return { text: '未实测', type: 'info' as const }
  const items = Object.values(r.capabilities)
  const supported = items.filter((i) => i.state === 'SUPPORTED').length
  const unsupported = items.filter((i) => i.state === 'UNSUPPORTED').length
  if (unsupported > 0) return { text: `${supported}/${items.length} 项支持`, type: 'danger' as const }
  const unknown = items.length - supported
  if (unknown > 0) return { text: `${supported}/${items.length} 项支持`, type: 'warning' as const }
  return { text: `全部 ${items.length} 项支持`, type: 'success' as const }
}

/** 探测结果保鲜期：7 天。超期不代表结果错，但上游模型可能已变化。 */
const PROBE_STALE_DAYS = 7

/**
 * 探测结果是否可能过期。两种情形：
 * 1. probedAt 超过 7 天——上游模型能力可能已变化（如版本升级新增 function calling）；
 * 2. 渠道 updatedAt 晚于 probedAt——配置改过但没重探，旧结果描述的是旧配置。
 */
export const staleReasonOf = (
  r: ChannelProbeResult | null | undefined,
  channelUpdatedAt: string | null | undefined,
): string | null => {
  if (!r?.probedAt) return null
  // parseDate：后端 LocalDateTime 无时区后缀，new Date 会按浏览器时区解析（跨时区差 12h）
  const probedAtDate = parseDate(r.probedAt)
  if (!probedAtDate) return null
  const probedAt = probedAtDate.getTime()
  if (channelUpdatedAt) {
    const updatedAtDate = parseDate(channelUpdatedAt)
    if (updatedAtDate && updatedAtDate.getTime() > probedAt) {
      return '配置在探测后已变更，结果描述的是旧配置'
    }
  }
  const ageDays = (Date.now() - probedAt) / 86_400_000
  if (ageDays > PROBE_STALE_DAYS) {
    return `探测已过 ${Math.floor(ageDays)} 天，上游模型能力可能已变化`
  }
  return null
}
