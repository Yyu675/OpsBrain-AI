type CapabilityStage = 'L1' | 'L2' | 'L3' | 'L4' | 'L5'

interface NavigationItem {
  key: string
  label: string
  path: string
  stage: CapabilityStage
  visible: boolean
  description?: string
  /**
   * 可见角色白名单（方向 F RBAC）。为空=所有登录用户可见；
   * 指定则仅这些前端角色（admin/operator/viewer）在导航看到该入口。
   * 用 string[] 而非 import Role——避免 navigation ↔ stores/app 循环依赖。
   */
  roles?: string[]
}

/**
 * 全站主导航模型（顶栏 = 日常作业面）。
 *
 * ── 2026-09-26 信息架构整合 ───────────────────────────────────
 * 1. 实时监控 + 趋势分析合并为「监控中心」（/trends 重定向到 /monitoring）；
 * 2. 原「治理」下拉撤销：配置与审计类（模型渠道/自动化策略/动作白名单/
 *    风险等级/接入管理/审计日志）迁入设置页 /settings 标签（见 views/Settings.vue），
 *    不再出现在导航层；
 * 3. 审批中心、自愈中心是日常处置队列（非配置），升为顶栏独立入口，
 *    均限 admin（与各自路由 meta.roles 对齐——此前自愈任务对全员可见、
 *    点进去却是 403，属导航与路由的角色错配）。
 *
 * ── 2026-09-27 二次收敛 ───────────────────────────────────────
 * 4. 「数据概览」升格为首页（/dashboard → / 重定向）：值班首屏就是首页，
 *    不再占一个独立导航项；
 * 5. 审批中心 + 自愈中心合并为「处置中心」（/disposal，标签页承载），
 *    两者都是「自动化走到需要人」的 admin 队列，同一使用场景同一角色；
 * 6. 「帮助中心」降级到用户菜单——值班处置时没人看帮助，顶栏留给日常作业。
 */
/** @public knip 假阳存证：%s */
export const navigationItems: NavigationItem[] = [
  { key: 'home', label: '首页', path: '/', stage: 'L1', visible: true },
  { key: 'knowledge', label: '知识库', path: '/knowledge', stage: 'L1', visible: true },
  { key: 'tickets', label: '智能工单', path: '/tickets', stage: 'L1', visible: true },
  // 告警是值班人每天要用的入口
  { key: 'alerts', label: '告警事件', path: '/alerts', stage: 'L2', visible: true },
  // 监控中心 = 实时态势 + 趋势分析（合并后值班人只认这一个入口）
  { key: 'monitoring', label: '监控中心', path: '/monitoring', stage: 'L2', visible: true },
  { key: 'action-items', label: '改进项', path: '/action-items', stage: 'L1', visible: true },
  { key: 'effectiveness', label: '效能大盘', path: '/effectiveness', stage: 'L3', visible: true },
  // 处置中心 = 审批队列 + 自愈台账（标签页承载），限 admin；待审角标在 AppSidebar
  { key: 'disposal', label: '处置中心', path: '/disposal', stage: 'L3', visible: true, roles: ['admin'] },
]

export const primaryNavigationItems = navigationItems.filter(item => item.visible)
