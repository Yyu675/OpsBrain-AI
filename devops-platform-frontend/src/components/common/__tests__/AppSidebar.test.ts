/**
 * AppSidebar（左侧导航栏）测试。
 *
 * 由 AppNavbar 测试拆分而来（2026-09-27 侧栏布局壳改造）：
 * 导航/RBAC/角标归侧栏，通知/搜索/用户菜单归 TopBar。
 * 「下拉面板互斥」用例随之退役——通知与用户菜单分处两个屏幕区域，
 * 物理上不再可能重叠，互斥逻辑本身被结构消除了。
 *
 * 保护的行为：
 * - RBAC 菜单过滤（该显示不显示 = 管理员找不到审批入口；
 *   不该显示却显示 = 普通用户点进去吃 403）
 * - 当前页高亮（含子路由归属、未覆盖路径不误高亮首页）
 * - 待审角标只对管理员拉取（端点限 ADMIN，非管理员请求只收获 403）
 * - 侧栏折叠：点击切换图标轨 + 状态跨会话持久化
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

// 只桩网络层：角标是否发请求这件事本身就是被测点之一
const approvalApi = vi.hoisted(() => ({
  pendingCount: vi.fn(),
  listApprovals: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}))
vi.mock('@/api/approval', () => approvalApi)

type Role = 'admin' | 'operator' | 'viewer' | 'guest'

const appStore = vi.hoisted(() => ({
  isAuthenticated: true,
  currentUser: { name: '张明', role: 'operator' as Role, permissions: [] as string[] },
  settings: { notificationsEnabled: true, compactTable: false },
  hasAllPermissions: false,
  hasRole(roles?: Role[]) {
    if (!roles || roles.length === 0) return true
    if (this.hasAllPermissions) return true
    return roles.includes(this.currentUser.role)
  },
}))
vi.mock('@/stores/app', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/app')
  return { ...actual, useAppStore: () => appStore }
})

import AppSidebar from '../AppSidebar.vue'

let router: Router

const blank = defineComponent({ template: '<div/>' })

const mountSidebar = async (over: {
  role?: Role
  authed?: boolean
  pending?: number
} = {}) => {
  appStore.currentUser.role = over.role ?? 'operator'
  appStore.isAuthenticated = over.authed ?? true
  approvalApi.pendingCount.mockResolvedValue(over.pending ?? 0)

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: blank },
      { path: '/login', component: blank },
      { path: '/knowledge', component: blank },
      { path: '/tickets', component: blank },
      { path: '/action-items', component: blank },
      { path: '/help', component: blank },
      { path: '/settings', component: blank },
      { path: '/disposal', component: blank },
      { path: '/alerts/:id', component: blank },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(AppSidebar, {
    global: {
      plugins: [
        router,
        [VueQueryPlugin, {
          queryClient: new QueryClient({
            defaultOptions: { queries: { retry: false, staleTime: 0, gcTime: 0 } },
          }),
        }],
      ],
    },
  })
  await flushPromises()
  return wrapper
}

type Vm = {
  navItems: Array<{ key: string; label: string }>
  activeKey: string
  collapsed: boolean
  toggleCollapse: () => void
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  vi.clearAllMocks()
  appStore.hasAllPermissions = false
})

describe('按角色过滤菜单（不是安全边界，但错了会误导用户）', () => {
  it('普通运维看不到「处置中心」', async () => {
    const w = await mountSidebar({ role: 'operator' })

    expect(vmOf(w).navItems.map((i) => i.key)).not.toContain('disposal')
    expect(w.text()).not.toContain('处置中心')
  })

  it('管理员能在侧栏看到「处置中心」', async () => {
    // 反向验证不可省：只测「普通用户看不到」的话，
    // 把整项删掉也能通过——那时管理员也找不到入口，
    // 高危动作会卡在待审队列里没人处理
    const w = await mountSidebar({ role: 'admin' })

    expect(vmOf(w).navItems.map((i) => i.key)).toContain('disposal')
    expect(w.text()).toContain('处置中心')
  })

  it('持有通配权限（*）的账号同样可见', async () => {
    // hasRole 里 hasAllPermissions 优先于角色匹配
    appStore.hasAllPermissions = true
    const w = await mountSidebar({ role: 'viewer' })

    expect(vmOf(w).navItems.map((i) => i.key)).toContain('disposal')
  })

  it('无 roles 标注的菜单对所有角色可见', async () => {
    const w = await mountSidebar({ role: 'viewer' })
    const keys = vmOf(w).navItems.map((i) => i.key)

    for (const k of ['home', 'knowledge', 'tickets', 'alerts', 'monitoring', 'action-items', 'effectiveness']) {
      expect(keys, `${k} 应对所有角色可见`).toContain(k)
    }
  })

  it('迁入设置页/合并降级后的旧入口不再单列', async () => {
    // 配置类进 /settings 标签；数据概览升格首页；审批+自愈合并处置中心；帮助进用户菜单
    const w = await mountSidebar({ role: 'admin' })
    const keys = vmOf(w).navItems.map((i) => i.key)

    for (const k of ['integrations', 'saga-compensation', 'model-channels', 'audit-logs',
      'dashboard', 'approvals', 'healing-tasks', 'help']) {
      expect(keys, `${k} 已迁移/合并/降级，不该出现在侧栏`).not.toContain(k)
    }
  })
})

describe('待审角标', () => {
  it('有待审且是管理员时拉取并显示', async () => {
    const w = await mountSidebar({ role: 'admin', pending: 7 })
    await flushPromises()

    expect(approvalApi.pendingCount).toHaveBeenCalled()
    expect(w.find('.nav-badge').exists()).toBe(true)
  })

  it('非管理员不拉待审数（端点限 ADMIN，拉了只收获 403 噪音）', async () => {
    vi.clearAllMocks()
    const w = await mountSidebar({ role: 'operator', pending: 7 })
    await flushPromises()

    expect(approvalApi.pendingCount).not.toHaveBeenCalled()
    expect(w.find('.nav-badge').exists()).toBe(false)
  })
})

describe('当前页高亮', () => {
  it('子路由也能匹配到父级菜单', async () => {
    // 用户在 /tickets/TKT-001 时，「智能工单」必须仍是高亮态
    const w = await mountSidebar()

    await router.push('/tickets')
    await w.vm.$nextTick()
    expect(vmOf(w).activeKey).toBe('tickets')

    await router.push('/knowledge')
    await w.vm.$nextTick()
    expect(vmOf(w).activeKey).toBe('knowledge')
  })

  it('根路径高亮首页', async () => {
    const w = await mountSidebar()
    expect(vmOf(w).activeKey).toBe('home')
  })

  it('告警详情页归属「告警事件」菜单高亮', async () => {
    // /alerts/1 是告警模块的子路由，高亮必须落在「告警事件」上——
    // 兜底到 'home' 会让用户误以为自己不在告警模块里
    const w = await mountSidebar()
    await router.push('/alerts/1')
    await w.vm.$nextTick()

    expect(vmOf(w).activeKey).toBe('alerts')
  })

  it('自愈详情深链归属「处置中心」高亮', async () => {
    const w = await mountSidebar({ role: 'admin' })
    await router.push('/disposal')
    await w.vm.$nextTick()

    expect(vmOf(w).activeKey).toBe('disposal')
  })

  it('未覆盖路径（帮助页等）不高亮任何菜单', async () => {
    // 高亮「首页」会误导用户以为自己回到了首页——宁可哪个都不亮。
    // 帮助中心已从导航降级到用户菜单（不在侧栏），用它验证「未覆盖即不亮」。
    const w = await mountSidebar()
    await router.push('/help')
    await w.vm.$nextTick()

    expect(vmOf(w).activeKey).toBe('')
  })

  it('系统设置页高亮「系统设置」菜单（2026-09-28 起导航含设置项）', async () => {
    const w = await mountSidebar()
    await router.push('/settings')
    await w.vm.$nextTick()

    expect(vmOf(w).activeKey).toBe('settings')
  })
})

describe('侧栏折叠', () => {
  it('点击折叠按钮切换图标轨形态', async () => {
    const w = await mountSidebar()

    expect(vmOf(w).collapsed).toBe(false)
    expect(w.find('.sidebar.collapsed').exists()).toBe(false)

    await w.find('.collapse-btn').trigger('click')
    await w.vm.$nextTick()

    expect(vmOf(w).collapsed).toBe(true)
    expect(w.find('.sidebar.collapsed').exists()).toBe(true)
  })

  it('折叠偏好跨会话持久化——刷新后仍是图标轨', async () => {
    localStorage.clear()
    const w1 = await mountSidebar()
    await w1.find('.collapse-btn').trigger('click')
    await w1.vm.$nextTick()
    w1.unmount()

    // 第二实例（模拟刷新）应读到持久化的 collapsed=true
    const w2 = await mountSidebar()
    expect(vmOf(w2).collapsed).toBe(true)
    expect(w2.find('.sidebar.collapsed').exists()).toBe(true)

    localStorage.clear()
  })
})
