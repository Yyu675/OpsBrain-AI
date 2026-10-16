/**
 * 动作风险类型单一来源（2026-10-06 人话化批）。
 *
 * 同一套词此前在 ApprovalCenter / ActionAllowlist 各写一份且措辞已漂移
 * （「受控写」vs「受控写操作」），首页工作台与自动化策略页甚至直接裸渲染
 * 码值。凡面向人的风险等级展示一律走 riskLabel()。
 *
 * 值域 = ActionAllowlistEntry.riskLevel（后端动作风险类型族：
 * READ_ONLY / DRAFT / CONTROLLED_WRITE / HIGH_RISK_EXECUTION）。
 * 与「风险等级策略」（RiskLevels 页的 HIGH/MEDIUM/LOW 策略键）不是一回事。
 */
type ActionRiskLevel =
  | 'READ_ONLY'
  | 'DRAFT'
  | 'CONTROLLED_WRITE'
  | 'HIGH_RISK_EXECUTION'

const RISK_LABELS: Record<ActionRiskLevel, string> = {
  READ_ONLY: '只读查询',
  DRAFT: '草稿生成',
  CONTROLLED_WRITE: '受控写操作',
  HIGH_RISK_EXECUTION: '高风险执行'
}

/** 取风险类型中文标签：未知码原样透出不吞 */
export const riskLabel = (level: string | null | undefined): string => {
  if (!level) return '—'
  return RISK_LABELS[level as ActionRiskLevel] ?? level
}

/** 风险类型 → 徽章色调 class（高风险红 / 受控写橙 / 只读蓝 / 草稿灰 / 未知灰） */
export const riskToneClass = (level: string | null | undefined): string => {
  switch (level) {
    case 'HIGH_RISK_EXECUTION': return 'tone-danger'
    case 'CONTROLLED_WRITE': return 'tone-warning'
    case 'READ_ONLY': return 'tone-info'
    case 'DRAFT': return 'tone-muted'
    default: return 'tone-muted'
  }
}
