import type { AiChannelHistoryView, AiChannelView } from '@/api/types'

/**
 * 渠道「当前 vs 历史版本」逐字段对比（批89 自 ModelChannels.vue 抽出）。
 *
 * 抽出动机：ModelChannels.vue 超过 300 行红线，加 reveal 功能前先把纯
 * 展示逻辑外移（前端硬约束：改超限文件不得净增行）。此处零状态、
 * 纯函数，字段定义与渲染解耦——历史面板样式类名仍归宿主模板管。
 */

/** 参与对比的字段定义：[标签, 取当前值的访问器, 取历史值的访问器] */
const DIFF_FIELDS: Array<[string, (c: AiChannelView) => string | null, (h: AiChannelHistoryView) => string | null]> = [
  ['Base URL', c => c.baseUrl, h => h.baseUrl],
  ['API Key', c => c.maskedKey, h => h.maskedKey],
  ['Turbo 模型', c => c.turboModel, h => h.turboModel],
  ['Reasoner 模型', c => c.reasonerModel, h => h.reasonerModel],
  ['模型', c => c.model, h => h.model],
  ['向量维度', c => c.dimension?.toString() ?? null, h => h.dimension?.toString() ?? null],
  ['状态', c => c.status, h => h.status],
  ['备用 Base URL', c => c.fallbackBaseUrl ?? null, h => h.fallbackBaseUrl],
  ['备用模型', c => c.fallbackModel ?? null, h => h.fallbackModel],
  ['备用 Key', c => c.fallbackMaskedKey ?? null, h => h.fallbackMaskedKey],
]

interface DiffRow { label: string; current: string; target: string; changed: boolean }

/** 逐字段对比当前配置与选中历史版本；只列两边至少一边有值的字段。 */
export function diffRows(cur: AiChannelView | null, h: AiChannelHistoryView): DiffRow[] {
  if (!cur) return []
  return DIFF_FIELDS
    .map(([label, getCur, getHis]) => {
      const current = getCur(cur) ?? '—'
      const target = getHis(h) ?? '—'
      return { label, current, target, changed: current !== target }
    })
    .filter(r => r.current !== '—' || r.target !== '—')
}
