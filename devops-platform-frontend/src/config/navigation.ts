export type CapabilityStage = 'L1' | 'L2' | 'L3' | 'L4' | 'L5'

export interface NavigationItem {
  key: string
  label: string
  path: string
  stage: CapabilityStage
  visible: boolean
  description?: string
}

/**
 * 全站导航模型。未来能力先注册但不进入主导航，确保地址可验收、信息架构可演进。
 */
export const navigationItems: NavigationItem[] = [
  { key: 'home', label: '首页', path: '/', stage: 'L1', visible: true },
  { key: 'knowledge', label: '知识库', path: '/knowledge', stage: 'L1', visible: true },
  { key: 'tickets', label: '智能工单', path: '/tickets', stage: 'L1', visible: true },
  { key: 'action-items', label: '改进项', path: '/action-items', stage: 'L1', visible: true },
  { key: 'dashboard', label: '数据概览', path: '/dashboard', stage: 'L1', visible: true },
  { key: 'help', label: '帮助中心', path: '/help', stage: 'L1', visible: true },
  { key: 'monitoring', label: '实时监控', path: '/monitoring', stage: 'L2', visible: false },
  { key: 'trends', label: '趋势分析', path: '/trends', stage: 'L2', visible: false },
  { key: 'alerts', label: '告警事件', path: '/alerts', stage: 'L2', visible: false },
  { key: 'integrations', label: '接入管理', path: '/integrations', stage: 'L2', visible: false },
  { key: 'approvals', label: '人机协同审批', path: '/approvals', stage: 'L3', visible: false },
  { key: 'automation-policies', label: '自动化策略', path: '/automation/policies', stage: 'L3', visible: false },
  { key: 'action-allowlist', label: '动作白名单', path: '/automation/action-allowlist', stage: 'L3', visible: false },
  { key: 'risk-levels', label: '风险等级', path: '/automation/risk-levels', stage: 'L3', visible: false },
  { key: 'healing-tasks', label: '自愈任务', path: '/self-healing/tasks', stage: 'L4', visible: false },
  { key: 'audit-logs', label: '审计日志', path: '/governance/audit-logs', stage: 'L4', visible: false },
  { key: 'saga-compensation', label: 'Saga 补偿', path: '/governance/saga-compensation', stage: 'L4', visible: false },
  { key: 'manual-intervention', label: '人工介入', path: '/governance/manual-intervention', stage: 'L4', visible: false }
]

export const primaryNavigationItems = navigationItems.filter(item => item.visible)

