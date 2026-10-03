import { notify } from '@/utils/notify'
import { createRouter, createWebHistory, type RouteComponent } from 'vue-router'
import { defineAsyncComponent, h, defineComponent, type Component } from 'vue'
import { ElMessageBox } from 'element-plus'
import AppSkeleton from '@/components/common/AppSkeleton.vue'
import NotFound from '@/views/NotFound.vue'
import { useAppStore, type Role } from '@/stores/app'
import { getAuthToken } from '@/utils/http'
import { safeInternalPath } from '@/utils/safeRedirect'

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
    /**
     * 无壳页面：不渲染侧栏与 TopBar（登录页这类全屏场景）。
     * 与 public 正交——/design-system 是公开但仍要壳（演示需要导航）。
     */
    bare?: boolean
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
    { path: '/ai-chat', name: 'ai-chat', component: lazy(() => import('../views/AiChatView.vue'), 'AiChatView', 'list'), meta: { title: '智能问答' } },
    { path: '/knowledge', name: 'knowledge', component: lazy(() => import('../views/KnowledgeBase.vue'), 'KnowledgeBase', 'list'), meta: { title: '知识库' } },
    { path: '/knowledge-ops', name: 'knowledge-ops', component: lazy(() => import('../views/KnowledgeOpsDashboard.vue'), 'KnowledgeOpsDashboard', 'list'), meta: { title: '知识运营看板' } },
    { path: '/knowledge/editor/:id', name: 'knowledge-editor', component: lazy(() => import('../views/KnowledgeEditor.vue'), 'KnowledgeEditor', 'detail'), meta: { title: '编辑文章' } },
    { path: '/knowledge/:id', name: 'knowledge-detail', component: lazy(() => import('../views/KnowledgeDetail.vue'), 'KnowledgeDetail', 'detail'), meta: { title: '文章详情' } },
    { path: '/tickets', name: 'tickets', component: lazy(() => import('../views/TicketList.vue'), 'TicketList', 'list'), meta: { title: '工单列表' } },
    { path: '/tickets/:id', name: 'ticket-detail', component: lazy(() => import('../views/TicketDetail.vue'), 'TicketDetail', 'detail'), meta: { title: '工单详情' } },
    { path: '/action-items', name: 'action-items', component: lazy(() => import('../views/ActionItemBoard.vue'), 'ActionItemBoard', 'list'), meta: { title: '改进项看板' } },
    {
      // 2026-09-27：数据概览（值班工作台内容）已升格为首页 `/`，旧链接保活重定向。
      // 首页是公开路由：访客看到登录引导页（Home 访客分支），登录用户看到工作台。
      path: '/dashboard',
      redirect: '/'
    },
    { path: '/help', name: 'help', component: lazy(() => import('../views/HelpCenter.vue'), 'HelpCenter', 'list'), meta: { title: '帮助中心' } },
    {
      // 监控中心（2026-09-26 整合）：原「实时监控」+「趋势分析」两页合并为
      // 单页分区看板——上半区实时卡片（本路由组件），下半区趋势探索器
      // （TrendExplorer，自管 range/metric URL 筛选）。数据来自后端代理的 Prometheus 查询。
      path: '/monitoring', name: 'monitoring',
      component: lazy(() => import('../views/Monitoring.vue'), 'Monitoring', 'dashboard'),
      meta: {
        title: '监控中心', stage: 'L2',
        description: '主机资源与抓取目标的实时态势与历史趋势，数据直接来自 Prometheus。',
        capabilities: ['实时指标总览', '多实例明细', '迷你趋势', '多时间范围趋势', '区间极值与均值', '掉线目标提醒']
      }
    },
    {
      // 旧「趋势分析」整页已并入监控中心下半区。重定向保留 range/metric 参数，
      // 此前分享出去的 ?range=7d&metric=... 链接落点语义不变。
      path: '/trends',
      redirect: (to) => ({ path: '/monitoring', query: to.query })
    },
    {
      // 设置页（2026-09-26 整合）：个人偏好 + 治理配置的标签式入口。
      // 各标签按角色过滤（体验层；后端 @SaCheckRole 仍是真正的安全边界）。
      path: '/settings', name: 'settings',
      component: lazy(() => import('../views/Settings.vue'), 'Settings', 'list'),
      meta: {
        title: '设置',
        description: '个人偏好与平台治理配置：模型渠道、自动化策略、动作白名单、风险等级、接入管理、审计日志。',
        capabilities: ['常规偏好', '标签隔离', '角色过滤', '可直链标签']
      }
    },
    // ===== 治理类旧路由 → 设置页标签（2026-09-26 整合，旧链接全部保活） =====
    { path: '/integrations', redirect: { path: '/settings', query: { tab: 'integrations' } } },
    { path: '/model-channels', redirect: { path: '/settings', query: { tab: 'model-channels' } } },
    { path: '/automation/policies', redirect: { path: '/settings', query: { tab: 'automation-policies' } } },
    { path: '/automation/action-allowlist', redirect: { path: '/settings', query: { tab: 'action-allowlist' } } },
    { path: '/automation/risk-levels', redirect: { path: '/settings', query: { tab: 'risk-levels' } } },
    { path: '/governance/audit-logs', redirect: { path: '/settings', query: { tab: 'audit-logs' } } },
    {
      // 效能大盘（PRD FR-7）：处置体系的运转状况——压缩比/时效/飞轮/自愈/管道心跳
      path: '/effectiveness', name: 'effectiveness',
      component: lazy(() => import('../views/Effectiveness.vue'), 'Effectiveness', 'dashboard'),
      meta: {
        title: '效能大盘', stage: 'L3',
        description: '处置效能与工作流健康度：告警压缩比、MTTA/MTTR、知识命中率、自动闭环率。',
        capabilities: ['处置时效指标', '飞轮指标', '自愈台账指标', '管道心跳状态']
      }
    },
    {
      path: '/alerts', name: 'alerts', component: lazy(() => import('../views/AlertList.vue'), 'AlertList', 'list'),
      meta: { title: '告警事件', stage: 'L2', description: '统一查看告警事件、聚合结果、关联工单与处置进度。', capabilities: ['事件聚合与去重', '级别与状态筛选', '关联工单', 'SLA 计时'] }
    },
    {
      path: '/alerts/:id', name: 'alert-detail', component: lazy(() => import('../views/AlertDetail.vue'), 'AlertDetail', 'detail'),
      meta: { title: '告警事件详情', stage: 'L2', hiddenFromNavigation: true, description: '呈现单个告警的时间线、影响范围、证据与处置上下文。', capabilities: ['事件时间线', '指标与日志证据', '影响范围', '处置记录'] }
    },
    {
      path: '/diagnosis/:traceId', name: 'diagnosis-detail', component: lazy(() => import('../views/DiagnosisDetail.vue'), 'DiagnosisDetail', 'detail'),
      meta: { title: '诊断详情', stage: 'L3', hiddenFromNavigation: true, description: '按 traceId 回放诊断链：证据、假设、置信度与反馈。', capabilities: ['证据列表', '假设与置信度', '假设反馈', '会话摘要'] }
    },
    {
      // 处置中心（2026-09-27 合并）：审批队列 + 自愈台账两个 admin 队列
      // 收进同一标签页——它们都是「自动化走到需要人」的地方。
      path: '/disposal', name: 'disposal',
      component: lazy(() => import('../views/DisposalCenter.vue'), 'DisposalCenter', 'list'),
      meta: {
        title: '处置中心', stage: 'L3', roles: ['admin'],
        description: '待审批队列与自愈执行台账：人机协同的统一处置入口。',
        capabilities: ['待审批队列', 'AI 决策依据', '自愈执行台账', '治理门触发', '快照撤销']
      }
    },
    // 旧入口重定向保活（2026-09-27 合并）
    { path: '/approvals', redirect: { path: '/disposal', query: { tab: 'approvals' } } },
    { path: '/self-healing/tasks', redirect: { path: '/disposal', query: { tab: 'healing' } } },
    {
      // S3-1 批次 5：占位页已替换为真实实现 —— 执行详情（可直达链接，供审批/告警页跳转）
      path: '/self-healing/tasks/:id', name: 'healing-task-detail', component: lazy(() => import('../views/HealingExecutionDetail.vue'), 'HealingExecutionDetail', 'detail'),
      meta: { title: '自愈执行详情', stage: 'L4', roles: ['admin'], hiddenFromNavigation: true, description: '单次自愈执行的全要素回放：门裁决、演算、输出、快照、撤销。', capabilities: ['决策上下文', '执行回放', '快照查看', '人工撤销'] }
    },
    {
      // S3-5 批次 5：占位页已替换为真实实现 —— 步骤回放并入执行详情页（同一时间线，无需拆页）
      path: '/self-healing/tasks/:taskId/steps/:stepId', name: 'healing-step-detail', component: lazy(() => import('../views/HealingExecutionDetail.vue'), 'HealingExecutionDetail', 'detail'),
      meta: { title: '执行步骤回放', stage: 'L4', roles: ['admin'], hiddenFromNavigation: true, description: '检查单步动作的输入、输出、日志、耗时与异常。', capabilities: ['输入参数', '实时日志', '执行结果', '异常诊断'] }
    },
    {
      // S3-5 批次 5：占位页已替换为真实实现 —— 验证回放入口指向详情页验证区块
      path: '/self-healing/tasks/:id/verification', name: 'healing-verification', component: lazy(() => import('../views/HealingExecutionDetail.vue'), 'HealingExecutionDetail', 'detail'),
      meta: { title: '自愈验证回放', stage: 'L4', roles: ['admin'], hiddenFromNavigation: true, description: '通过指标、探针与业务检查确认自愈效果是否达标。', capabilities: ['验证规则', '前后指标对比', '探针结果', '验收结论'] }
    },
    {
      // S3-5 批次 5：占位页已替换为真实实现 —— 回滚回放入口指向详情页撤销区块（含 UNDO 步骤）
      path: '/self-healing/tasks/:id/rollback', name: 'healing-rollback', component: lazy(() => import('../views/HealingExecutionDetail.vue'), 'HealingExecutionDetail', 'detail'),
      meta: { title: '回滚回放', stage: 'L4', roles: ['admin'], hiddenFromNavigation: true, description: '展示回滚计划、执行步骤、恢复点与最终状态。', capabilities: ['回滚计划', '恢复点', '执行日志', '结果确认'] }
    },
    { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { title: '登录', public: true, bare: true } },
    {
      // 设计系统展示页：四个主题轴的可视化验收入口。
      // public 是刻意的——它不含任何业务数据，且需要能在未登录时演示。
      path: '/design-system', name: 'design-system',
      component: lazy(() => import('../views/DesignSystem.vue'), 'DesignSystem', 'detail'),
      meta: { title: '设计系统', public: true, hiddenFromNavigation: true }
    },
    /*
     * 仅开发环境：审计日志页的视觉预览入口。
     *
     * 真实入口已迁入设置页 /settings?tab=audit-logs（2026-09-26 整合），
     * 需要 admin 且依赖后端接口；前端做视觉走查时后端未必在跑、
     * 也不一定有管理员账号。这条 public 路由只在 DEV 下注册——
     * `import.meta.env.DEV` 在生产构建时为字面量 false，
     * 整个数组项会被 Vite 静态移除，不会出现在产物里。
     *
     * 它指向同一个组件，靠 ?demo=1 走内置演示数据，不碰任何真实接口。
     */
    ...(import.meta.env.DEV
      ? [{
          path: '/preview/audit-logs',
          name: 'audit-logs-preview',
          component: lazy(() => import('../views/AuditLogs.vue'), 'AuditLogsPreview', 'list'),
          meta: { title: '使用日志（预览）', public: true, hiddenFromNavigation: true }
        }]
      : []),
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

  // ====================================================================
  // ⚠️ 临时开发开关（2026-08-26）：UI 预览跳过登录鉴权（VITE_SKIP_AUTH=1，
  // 由 dev server 启动环境变量注入）。对外交付前移除该开关恢复登录拦截。
  // ====================================================================
  if (import.meta.env.DEV && import.meta.env.VITE_SKIP_AUTH === '1') {
    return true
  }

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
    notify.warning('资源加载失败，正在重新加载...')
    // 必须经 safeInternalPath：这是全站唯一一处真实的浏览器导航
    // （vue-router 走 pushState 受同源限制，location.assign 不受）。
    // to.fullPath 源自用户可控的地址栏，直接 assign 等于把兜底重载
    // 变成开放重定向的出口。
    const safePath = safeInternalPath(to.fullPath)
    setTimeout(() => window.location.assign(safePath), 400)
  }
})

export default router
