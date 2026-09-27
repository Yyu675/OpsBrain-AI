/**
 * 工单列表行展示的纯映射（2026-09-24 从 TicketList.vue 抽出）。
 *
 * 这些函数/常量只有一个职责：把行数据映射成展示形态（排序字段名、
 * 首响/SLA 配色 class、处置阶段与根因分类中文标签、日期参数解析）。
 * 全部是纯函数（无组件状态），抽到 utils 后 TicketList 只保留
 * 数据获取与交互编排，展示映射可被 SLA 面板等其它消费面复用而
 * 不重复造一份。
 */
import { slaSeverity } from './sla'

/** el-table 可排序列（前端先拦非法字段，避免后端静默降级造成「箭头指向错列」的错位）。 */
export const SORTABLE_PROPS = [
  'id', 'title', 'status', 'priority', 'assignee',
  'service', 'category', 'createdAt', 'updatedAt'
] as const

/**
 * 日期参数解析：只接受 `YYYY-MM-DD`。
 * 不做宽松解析是刻意的——`<input type="date">` 只认这一种格式。
 */
export const dateParser = (raw: string): string | undefined =>
  /^\d{4}-\d{2}-\d{2}$/.test(raw.trim()) ? raw.trim() : undefined

/**
 * 首响状态配色 class（B1）。
 * 状态由后端计算，文案在 utils/sla 统一；此处只保留页面私有的 class 映射。
 */
export const frClass = (row: { firstResponseState?: string }) => {
  switch (row.firstResponseState) {
    case 'RESPONDED': return 'fr-ok'
    case 'BREACHED': return 'fr-breached'
    case 'AT_RISK': return 'fr-risk'
    default: return 'fr-waiting'
  }
}

/** SLA 进度配色：超时红 / ≥70% 橙 / 其余正常 */
export const slaClass = (row: { slaProgress?: number; slaBreached?: boolean }) => {
  const severity = slaSeverity(row)
  if (severity === 'breached') return 'sla-breached'
  if (severity === 'warning') return 'sla-warning'
  return 'sla-normal'
}

/** B2 处置阶段中文标签 */
export const STAGE_LABELS: Record<string, string> = {
  TRIAGE: '排查中', MITIGATED: '已止损', FIXING: '修复中', VERIFYING: '验证中'
}
export const stageLabel = (stage: string) => STAGE_LABELS[stage] || stage

/** B3 根因分类中文标签 */
export const RC_LABELS: Record<string, string> = {
  CONFIG: '配置错误', CAPACITY: '容量不足', CODE: '代码缺陷',
  DEPENDENCY: '依赖故障', NETWORK: '网络问题', DATA: '数据异常',
  HUMAN: '人为操作', EXTERNAL: '外部服务', UNKNOWN: '未定位'
}
export const rcLabel = (cat: string) => RC_LABELS[cat] || cat