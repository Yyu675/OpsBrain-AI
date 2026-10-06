/**
 * 告警级别 / 状态展示单一来源。
 *
 * L2 告警列表页、告警流模式、通知中心共用，避免级别归类与状态标签在各处漂移。
 * 级别语义由后端下发（P0~P4 字符串），颜色归类是前端展示职责（后端不硬编码颜色）。
 */

type AlertLevel = 'P0' | 'P1' | 'P2' | 'P3' | 'P4'
/** @public knip 假阳存证：%s */
export type AlertStatus = 'FIRING' | 'ACKNOWLEDGED' | 'RESOLVED'

/**
 * 级别分组：用于颜色标识
 * P0/P1 → high（红 danger），P2/P3 → medium（橙 warning），其他 → low（蓝 info）
 */
export function levelGroup(level: string | null | undefined): 'high' | 'medium' | 'low' {
  if (!level) return 'low'
  const u = level.toUpperCase()
  if (u === 'P0' || u === 'P1') return 'high'
  if (u === 'P2' || u === 'P3') return 'medium'
  return 'low'
}

/** 级别对应 el-tag type */
export function levelTagType(level: string | null | undefined): 'danger' | 'warning' | 'info' {
  const g = levelGroup(level)
  if (g === 'high') return 'danger'
  if (g === 'medium') return 'warning'
  return 'info'
}

export const ALERT_STATUS_LABELS: Record<AlertStatus, string> = {
  FIRING: '触发中',
  ACKNOWLEDGED: '已确认',
  RESOLVED: '已恢复'
}

/**
 * 级别中文标签（人话化，2026-10-06）。
 *
 * P0~P4 是内部归一化码：对值班同事它不传达任何含义，展示层一律走本表。
 * 码值仍留在「筛选 value / API 参数 / 颜色归类」——中文只换展示，不动契约。
 */
const ALERT_LEVEL_LABELS: Record<AlertLevel, string> = {
  P0: '紧急',
  P1: '严重',
  P2: '警告',
  P3: '一般',
  P4: '提示'
}

/** 取级别中文标签：未知码原样透出不吞（后端新增档位时先在页面上可见，再回来补表） */
export const getAlertLevelLabel = (level: string | null | undefined): string => {
  if (!level) return '—'
  return ALERT_LEVEL_LABELS[level.toUpperCase() as AlertLevel] ?? level
}

export const ALERT_STATUS_OPTIONS: { value: AlertStatus | ''; label: string }[] = [
  { value: '', label: '全部状态' },
  ...(Object.entries(ALERT_STATUS_LABELS) as [AlertStatus, string][])
    .map(([value, label]) => ({ value, label }))
]

export const ALERT_LEVEL_OPTIONS: { value: AlertLevel | ''; label: string }[] = [
  { value: '', label: '全部级别' },
  ...(Object.entries(ALERT_LEVEL_LABELS) as [AlertLevel, string][])
    .map(([value, label]) => ({ value, label }))
]

export const getAlertStatusLabel = (s: string | null | undefined): string => {
  if (!s) return '—'
  return ALERT_STATUS_LABELS[s as AlertStatus] || s
}

/** 状态对应 el-tag type（触发中红 / 已确认橙 / 已恢复绿） */
export function statusTagType(status: string | null | undefined): 'danger' | 'warning' | 'success' | 'info' {
  switch (status) {
    case 'FIRING': return 'danger'
    case 'ACKNOWLEDGED': return 'warning'
    case 'RESOLVED': return 'success'
    default: return 'info'
  }
}
