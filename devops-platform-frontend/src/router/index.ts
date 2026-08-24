import { createRouter, createWebHistory, type RouteComponent } from 'vue-router'
import { defineAsyncComponent, h, defineComponent, type Component } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppSkeleton from '@/components/common/AppSkeleton.vue'
import NotFound from '@/views/NotFound.vue'
import { useAppStore, type Role } from '@/stores/app'
import { getAuthToken } from '@/utils/http'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    requiresAuth?: boolean
    roles?: Role[]
    permissions?: string[]
    stage?: 'L1' | 'L2' | 'L3' | 'L4' | 'L5'
    hiddenFromNavigation?: boolean
    description?: string
    capabilities?: string[]
    /**
     * 公开路由（无需登录即可访问）——鉴权守卫据此放行。
     *
     * 当前公开集：首页 `/`、登录页 `/login`、无权访问 `/403`、404 兜底。
     * 首页公开的前提是它不得调用受保护 API（见 Home.vue 访客态分支），
     * 否则访客访问会 401 → 派发 auth:unauthorized → 被踢回登录页，公开形同虚设。
     */
    public?: boolean
  }
}

type SkeletonVariant = 'list' | 'detail' | 'dashboard'

const makeSkeleton = (variant: SkeletonVariant, rows = 6): Component => ({
  name: `AppSkeleton_${variant}`,
  render: () => h(AppSkeleton, { variant, rows })
})

/**
 * 构造路由级懒加载组件。
 *
 * Vue Router 4 要求路由 record 的 component 为 `() => import(...)` 形式，
 * 直接使用 `defineAsyncComponent` 会触发 Router 警告。
 *
 * 这里返回一个同步包装组件，内部用 `defineAsyncComponent` 渲染异步子组件：
 * - 路由 record 看到的是同步 Component，不触发 Router 警告
 * - `defineAsyncComponent` 的 loadingComponent / errorComponent / retry 正常工作
 * - 不需要 `<Suspense>`，不会阻塞子组件的 `onMounted`
 */
const lazy = (
  loader: () => Promise<RouteComponent>,
  name: string,
  variant: SkeletonVariant = 'list',
  retries = 2
): Component => {
  const asyncChild = defineAsyncComponent({
    loader: async () => {
      let lastErr: unknown = null
      for (let i = 0; i <= retries; i++) {
        try {
          return await loader() as never
        } catch (e) {
          lastErr = e
          if (i < retries) {
            await new Promise(r => setTimeout(r, 300 * (i + 1)))
          }
        }
      }
      console.error(`[router] failed to load "${name}" after ${retries + 1} tries:`, lastErr)
      throw lastErr instanceof Error ? lastErr : new Error(String(lastErr))
    },
    loadingComponent: makeSkeleton(variant),
    errorComponent: NotFound,
    delay: 120,
    timeout: 20000
  })

  return defineComponent({
    name: `Lazy_${name}`,
    render() {
      return h(asyncChild)
    }
  })
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'home', component: lazy(() => import('../views/Home.vue'), 'Home', 'dashboard'), meta: { title: '首页', public: true } },
    { path: '/knowledge', name: 'knowledge', component: lazy(() => import('../views/KnowledgeBase.vue'), 'KnowledgeBase', 'list'), meta: { title: '知识库' } },
    { path: '/knowledge/editor/:id', name: 'knowledge-editor', component: lazy(() => import('../views/KnowledgeEditor.vue'), 'KnowledgeEditor', 'detail'), meta: { title: '编辑文章' } },
    { path: '/knowledge/:id', name: 'knowledge-detail', component: lazy(() => import('../views/KnowledgeDetail.vue'), 'KnowledgeDetail', 'detail'), meta: { title: '文章详情' } },
    { path: '/tickets', name: 'tickets', component: lazy(() => import('../views/TicketList.vue'), 'TicketList', 'list'), meta: { title: '工单列表' } },
    { path: '/tickets/:id', name: 'ticket-detail', component: lazy(() => import('../views/TicketDetail.vue'), 'TicketDetail', 'detail'), meta: { title: '工单详情' } },
    { path: '/action-items', name: 'action-items', component: lazy(() => import('../views/ActionItemBoard.vue'), 'ActionItemBoard', 'list'), meta: { title: '改进项看板' } },
    { path: '/ai-chat', name: 'ai-chat', component: lazy(() => import('../views/ai/AiChatView.vue'), 'AiChatView', 'dashboard'), meta: { title: 'AI 对话', hiddenFromNavigation: true } },
    { path: '/dashboard', name: 'dashboard', component: lazy(() => import('../views/Dashboard.vue'), 'Dashboard', 'dashboard'), meta: { title: '运维大屏' } },
    { path: '/help', name: 'help', component: lazy(() => import('../views/HelpCenter.vue'), 'HelpCenter', 'list'), meta: { title: '帮助中心' } },
    {
      path: '/monitoring', name: 'monitoring', component: lazy(() => import('../views/FutureCapability.vue'), 'Monitoring', 'dashboard'),
      meta: { title: '实时监控', stage: 'L2', hiddenFromNavigation: true, description: '汇聚告警、指标与服务健康状态，形成面向运维值守的实时态势视图。', capabilities: ['实时指标总览', '服务健康拓扑', '告警流转状态', '值守大盘'] }
    },
    {
      path: '/trends', name: 'trends', component: lazy(() => import('../views/FutureCapability.vue'), 'Trends', 'dashboard'),
      meta: { title: '趋势分析', stage: 'L2', hiddenFromNavigation: true, description: '基于历史指标与事件数据识别趋势、异常模式和容量风险。', capabilities: ['趋势对比', '异常检测', '容量预测', '影响分析'] }
    },
    {
      path: '/alerts', name: 'alerts', component: lazy(() => import('../views/AlertList.vue'), 'AlertList', 'list'),
      meta: { title: '告警事件', stage: 'L2', hiddenFromNavigation: true, description: '统一查看告警事件、聚合结果、关联工单与处置进度。', capabilities: ['事件聚合与去重', '级别与状态筛选', '关联工单', 'SLA 计时'] }
    },
    {
      path: '/alerts/:id', name: 'alert-detail', component: lazy(() => import('../views/AlertDetail.vue'), 'AlertDetail', 'detail'),
      meta: { title: '告警事件详情', stage: 'L2', hiddenFromNavigation: true, description: '呈现单个告警的时间线、影响范围、证据与处置上下文。', capabilities: ['事件时间线', '指标与日志证据', '影响范围', '处置记录'] }
    },
    {
      path: '/integrations', name: 'integrations', component: lazy(() => import('../views/FutureCapability.vue'), 'Integrations', 'dashboard'),
      meta: { title: '接入管理', stage: 'L2', hiddenFromNavigation: true, description: '集中管理监控、容器与云平台数据源的连接和健康状态。', capabilities: ['Prometheus 接入', 'Kubernetes 集群', '云平台账号', '连接健康检查'] }
    },
    {
      path: '/approvals', name: 'approvals', component: lazy(() => import('../views/ApprovalCenter.vue'), 'ApprovalCenter', 'dashboard'),
      meta: { title: '人机协同审批中心', stage: 'L3', roles: ['admin'], description: '对中高风险自动化建议执行分级审批并保留完整决策依据。', capabilities: ['待审批队列', 'AI 决策依据', '风险校验', '审批记录'] }
    },
    {
      path: '/automation/policies', name: 'automation-policies', component: lazy(() => import('../views/FutureCapability.vue'), 'AutomationPolicies', 'dashboard'),
      meta: { title: '自动化策略', stage: 'L3', hiddenFromNavigation: true, description: '配置告警到动作的匹配条件、审批要求与执行边界。', capabilities: ['触发条件', '动作编排', '审批规则', '生效范围'] }
    },
    {
      path: '/automation/action-allowlist', name: 'action-allowlist', component: lazy(() => import('../views/FutureCapability.vue'), 'ActionAllowlist', 'dashboard'),
      meta: { title: '动作白名单', stage: 'L3', hiddenFromNavigation: true, description: '维护自动化引擎允许调用的动作及参数约束。', capabilities: ['动作注册', '参数约束', '环境范围', '版本管理'] }
    },
    {
      path: '/automation/risk-levels', name: 'risk-levels', component: lazy(() => import('../views/FutureCapability.vue'), 'RiskLevels', 'dashboard'),
      meta: { title: '风险等级配置', stage: 'L3', hiddenFromNavigation: true, description: '统一定义风险分级、审批门槛、执行限制和升级路径。', capabilities: ['风险矩阵', '审批门槛', '执行限制', '升级策略'] }
    },
    {
      path: '/self-healing/tasks', name: 'healing-tasks', component: lazy(() => import('../views/FutureCapability.vue'), 'HealingTasks', 'dashboard'),
      meta: { title: '自愈任务', stage: 'L4', hiddenFromNavigation: true, description: '追踪自动化自愈任务的计划、执行、验证与回滚状态。', capabilities: ['任务队列', '执行状态', '风险标识', '关联告警'] }
    },
    {
      path: '/self-healing/tasks/:id', name: 'healing-task-detail', component: lazy(() => import('../views/FutureCapability.vue'), 'HealingTaskDetail', 'detail'),
      meta: { title: '自愈任务详情', stage: 'L4', hiddenFromNavigation: true, description: '查看任务决策依据、执行编排与全链路状态。', capabilities: ['决策上下文', '执行编排', '状态时间线', '人工控制'] }
    },
    {
      path: '/self-healing/tasks/:taskId/steps/:stepId', name: 'healing-step-detail', component: lazy(() => import('../views/FutureCapability.vue'), 'HealingStepDetail', 'detail'),
      meta: { title: '执行步骤详情', stage: 'L4', hiddenFromNavigation: true, description: '检查单步动作的输入、输出、日志、耗时与异常。', capabilities: ['输入参数', '实时日志', '执行结果', '异常诊断'] }
    },
    {
      path: '/self-healing/tasks/:id/verification', name: 'healing-verification', component: lazy(() => import('../views/FutureCapability.vue'), 'HealingVerification', 'detail'),
      meta: { title: '自愈验证详情', stage: 'L4', hiddenFromNavigation: true, description: '通过指标、探针与业务检查确认自愈效果是否达标。', capabilities: ['验证规则', '前后指标对比', '探针结果', '验收结论'] }
    },
    {
      path: '/self-healing/tasks/:id/rollback', name: 'healing-rollback', component: lazy(() => import('../views/FutureCapability.vue'), 'HealingRollback', 'detail'),
      meta: { title: '回滚详情', stage: 'L4', hiddenFromNavigation: true, description: '展示回滚计划、执行步骤、恢复点与最终状态。', capabilities: ['回滚计划', '恢复点', '执行日志', '结果确认'] }
    },
    {
      path: '/governance/audit-logs', name: 'audit-logs', component: lazy(() => import('../views/FutureCapability.vue'), 'AuditLogs', 'dashboard'),
      meta: { title: '审计日志', stage: 'L4', hiddenFromNavigation: true, description: '记录自动化决策、审批、执行和配置变更的完整证据链。', capabilities: ['操作审计', '决策审计', '配置变更', '证据导出'] }
    },
    {
      path: '/governance/saga-compensation', name: 'saga-compensation', component: lazy(() => import('../views/FutureCapability.vue'), 'SagaCompensation', 'dashboard'),
      meta: { title: 'Saga 补偿中心', stage: 'L4', hiddenFromNavigation: true, description: '管理跨步骤自动化流程的失败补偿与一致性恢复。', capabilities: ['失败事务', '补偿步骤', '重试策略', '一致性状态'] }
    },
    {
      path: '/governance/manual-intervention', name: 'manual-intervention', component: lazy(() => import('../views/FutureCapability.vue'), 'ManualIntervention', 'dashboard'),
      meta: { title: '人工介入中心', stage: 'L4', hiddenFromNavigation: true, description: '集中处理自动化无法安全闭环的异常任务与升级请求。', capabilities: ['介入队列', '上下文快照', '接管操作', '恢复自动化'] }
    },
    { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { title: '登录', public: true } },
    {
      // 设计系统展示页：四个主题轴的可视化验收入口。
      // public 是刻意的——它不含任何业务数据，且需要能在未登录时演示。
      path: '/design-system', name: 'design-system',
      component: lazy(() => import('../views/DesignSystem.vue'), 'DesignSystem', 'detail'),
      meta: { title: '设计系统', public: true, hiddenFromNavigation: true }
    },
    { path: '/403', name: 'forbidden', component: () => import('../views/Forbidden.vue'), meta: { title: '无权访问', public: true } },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFound, meta: { title: '页面未找到', public: true } }
  ],
  scrollBehavior(to, _from, saved) {
    if (saved) return saved
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  }
})

/**
 * 登录提示弹窗是否已打开。
 *
 * 连续导航（如快速点击两个受保护菜单）会触发两次守卫，
 * 不加此标记会弹出堆叠的两个弹窗，用户要点两次才能关掉。
 */
let loginPromptOpen = false

/**
 * 访客访问受保护路由时的登录提示。
 *
 * 返回 true 表示用户选择前往登录，false 表示留在原处。
 *
 * 注册说明：后端 AuthController 仅提供 login/me/logout，无注册端点，
 * 账号由管理员在 sys_user 开通。故文案为「联系管理员开通」而非提供注册入口——
 * 不做点了没反应的假注册（项目契约：半成品不提供交互）。
 */
const promptLogin = async (targetTitle: string): Promise<boolean> => {
  if (loginPromptOpen) return false
  loginPromptOpen = true
  try {
    await ElMessageBox.confirm(
      `访问「${targetTitle}」需要先登录。若还没有账号，请联系管理员开通。`,
      '请先登录',
      {
        type: 'info',
        confirmButtonText: '前往登录',
        cancelButtonText: '留在当前页',
        closeOnClickModal: false
      }
    )
    return true
  } catch {
    // 取消 / 关闭 / Esc 均视为不登录
    return false
  } finally {
    loginPromptOpen = false
  }
}

router.beforeEach(async (to, from) => {
  const app = useAppStore()
  const meta = to.meta || {}

  // 公开路由（首页、登录页、错误页）直接放行——访客默认落在首页而非登录页
  if (meta.public) {
    return true
  }

  /*
   * 未登录判定：不能只看内存里的 isAuthenticated。
   *
   * 内存态为 false 但本地仍有 token 的情形是真实存在的：
   * - 启动竞态：restoreSession() 尚未 resolve 时用户已触发导航
   * - 开发期 HMR：store 被热替换重建，内存态回到初值 false 而 token 仍在
   * - restoreSession 因服务暂时不可达而未能确立登录态
   *
   * 这些情形下弹「请先登录」是误判——用户明明持有有效凭证。
   * 故此处先向服务端确认一次，只有服务端明确说凭证无效才提示登录。
   * 已登录（内存态 true）时不会走到这里，无额外请求开销。
   */
  if (!app.isAuthenticated && getAuthToken()) {
    await app.restoreSession()
  }

  // 访客访问受保护模块：弹窗提示，由用户决定是否前往登录
  if (!app.isAuthenticated) {
    const goLogin = await promptLogin((meta.title as string) || '该功能')
    if (goLogin) {
      return {
        name: 'login',
        query: { redirect: to.fullPath }
      }
    }
    // 选择留在当前页：初始导航（直接输地址/刷新）时没有"当前页"可留，
    // 中止导航会白屏，故回落首页。
    return from.name ? false : { name: 'home' }
  }

  // 已登录：再校验角色 / 权限
  if (meta.roles && !app.hasRole(meta.roles)) {
    return {
      name: 'forbidden',
      query: { from: to.fullPath, reason: 'role' }
    }
  }

  if (meta.permissions && !app.hasPermission(meta.permissions)) {
    return {
      name: 'forbidden',
      query: { from: to.fullPath, reason: 'permission' }
    }
  }

  return true
})

const BASE_TITLE = '企业级智能运维平台'
router.afterEach((to) => {
  const t = (to.meta?.title as string | undefined)
  document.title = t ? `${t} · ${BASE_TITLE}` : BASE_TITLE
})

let reloadInFlight = false
router.onError((err, to) => {
  console.error('[router] navigation error:', err, 'to:', to.fullPath)
  const msg = String((err as Error)?.message || err || '')
  if (
    msg.includes('Failed to fetch dynamically imported module') ||
    msg.includes('Loading chunk') ||
    msg.includes('Importing a module script failed')
  ) {
    if (reloadInFlight) return
    reloadInFlight = true
    ElMessage.warning('资源加载失败，正在重新加载...')
    setTimeout(() => window.location.assign(to.fullPath), 400)
  }
})

export default router
