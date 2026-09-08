/**
 * 全局导航栏测试。
 *
 * ── 为什么补这一个 ────────────────────────────────────────────
 * 769 行，是当前最大的零测试组件之一，且它是**每个页面都会渲染**的
 * 全局组件——这里出问题，影响面是全站而不是某一页。
 *
 * ── 重点一：按角色过滤菜单 ────────────────────────────────────
 * 「审批中心」标了 `roles: ['admin']`。需要说清它的定位：
 *
 * <b>这不是安全边界。</b>真正的边界在后端（`@SaCheckRole` +
 * 已有的 `GovernanceRoleGuardIntegrationTest`）——非管理员就算手输
 * URL 进去，接口也会返回 403。
 *
 * 但菜单错乱仍是真问题：
 * <ul>
 *   <li><b>该显示不显示</b>——管理员找不到审批入口，
 *       高危动作卡在待审队列里没人处理；</li>
 *   <li><b>不该显示却显示</b>——普通用户点进去吃一脸 403，
 *       他不知道是自己没权限还是系统坏了。</li>
 * </ul>
 *
 * ── 重点二：待审角标只对管理员拉取 ────────────────────────────
 * 该端点限 ADMIN。非管理员若也发请求，会得到 403——既无意义，
 * 又会在控制台刷出错误，掩盖真正的问题。
 *
 * ── 重点三：全局点击监听必须解绑 ──────────────────────────────
 * 导航栏在 `document` 上挂了 click 监听（点外部关闭下拉）。
 * 不解绑就是内存泄漏，且回调里引用的是已卸载组件的闭包。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock, prompt: vi.fn() },
  ElMessage: Object.assign(vi.fn(), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
}))

const notifyMock = vi.hoisted(() => ({
  success: vi.fn(), warning: vi.fn(), error: vi.fn(),
  info: vi.fn(), clearCooldown: vi.fn(),
}))
vi.mock('@/utils/notify', () => ({
  notify: notifyMock,
  handleServerError: vi.fn(),
}))

// 只桩网络层：角标是否发请求这件事本身就是被测点之一，
// 桩掉 usePendingApprovalCountQuery 会把它整个绕过
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
  signOut: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/stores/app', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/app')
  return { ...actual, useAppStore: () => appStore }
})

const notifStore = vi.hoisted(() => ({
  items: [] as Array<{ id: string; title: string; read: boolean; linkTo?: string; time?: string }>,
  unreadCount: 0,
  markRead: vi.fn(),
  markAllRead: vi.fn(),
}))
vi.mock('@/stores/notifications', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/notifications')
  return { ...actual, useNotificationsStore: () => notifStore }
})

import AppNavbar from '../AppNavbar.vue'

let router: Router

const blank = defineComponent({ template: '<div/>' })

const mountNavbar = async (over: {
  role?: Role
  authed?: boolean
  pending?: number
  notificationsEnabled?: boolean
  unread?: number
  items?: typeof notifStore.items
} = {}) => {
  appStore.currentUser.role = over.role ?? 'operator'
  appStore.isAuthenticated = over.authed ?? true
  appStore.settings.notificationsEnabled = over.notificationsEnabled ?? true
  notifStore.unreadCount = over.unread ?? 0
  notifStore.items = over.items ?? []
  approvalApi.pendingCount.mockResolvedValue(over.pending ?? 0)

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: blank },
      { path: '/login', component: blank },
      { path: '/approvals', component: blank },
      { path: '/knowledge', component: blank },
      { path: '/tickets', component: blank },
      { path: '/action-items', component: blank },
      { path: '/dashboard', component: blank },
      { path: '/help', component: blank },
      { path: '/alerts/:id', component: blank },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(AppNavbar, {
    global: {
      plugins: [
        router,
        [VueQueryPlugin, {
          queryClient: new QueryClient({
            defaultOptions: { queries: { retry: false, staleTime: 0, gcTime: 0 } },
          }),
        }],
      ],
      stubs: {
        ProfileDialog: true,
        SettingsDialog: true,
        AvatarFallback: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

type Vm = {
  navItems: Array<{ key: string; label: string }>
  activeKey: string
  unreadCount: number
  showNotifications: boolean
  showUserMenu: boolean
  toggleNotifications: () => void
  toggleUserMenu: () => void
  doLogout: () => Promise<void>
  markAllRead: () => void
  readNotification: (n: { id: string; linkTo?: string }) => void
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
  appStore.hasAllPermissions = false
})

describe('按角色过滤菜单（不是安全边界，但错了会误导用户）', () => {
  it('普通运维看不到「审批中心」', async () => {
    const w = await mountNavbar({ role: 'operator' })

    expect(vmOf(w).navItems.map((i) => i.key)).not.toContain('approvals')
    expect(w.text()).not.toContain('审批中心')
  })

  it('管理员能看到「审批中心」', async () => {
    // 反向验证不可省：只测「普通用户看不到」的话，
    // 把整项删掉也能通过——那时管理员也找不到入口，
    // 高危动作会卡在待审队列里没人处理
    const w = await mountNavbar({ role: 'admin' })

    expect(vmOf(w).navItems.map((i) => i.key)).toContain('approvals')
    expect(w.text()).toContain('审批中心')
  })

  it('持有通配权限（*）的账号同样可见', async () => {
    // hasRole 里 hasAllPermissions 优先于角色匹配。
    // 超管账号角色可能不是 admin，但权限是 *
    appStore.hasAllPermissions = true
    const w = await mountNavbar({ role: 'viewer' })

    expect(vmOf(w).navItems.map((i) => i.key)).toContain('approvals')
  })

  it('无 roles 标注的菜单对所有角色可见', async () => {
    const w = await mountNavbar({ role: 'viewer' })
    const keys = vmOf(w).navItems.map((i) => i.key)

    for (const k of ['home', 'knowledge', 'tickets', 'dashboard', 'help']) {
      expect(keys, `${k} 应对所有角色可见`).toContain(k)
    }
  })

  it('未启用的菜单（visible=false）一律不出现', async () => {
    // navigationItems 里有 12 项 visible:false（L2/L4 规划中能力）。
    // 漏过滤会让用户点进未实现的页面
    const w = await mountNavbar({ role: 'admin' })
    const keys = vmOf(w).navItems.map((i) => i.key)

    for (const k of ['monitoring', 'trends', 'healing-tasks', 'saga-compensation']) {
      expect(keys, `${k} 尚未启用，不该出现在导航`).not.toContain(k)
    }
  })
})

describe('待审角标', () => {
  it('管理员才拉取待审数量——非管理员请求必然 403', async () => {
    await mountNavbar({ role: 'operator' })
    expect(approvalApi.pendingCount).not.toHaveBeenCalled()

    vi.clearAllMocks()
    approvalApi.pendingCount.mockResolvedValue(3)
    await mountNavbar({ role: 'admin' })
    expect(approvalApi.pendingCount).toHaveBeenCalled()
  })

  it('未登录不拉取', async () => {
    await mountNavbar({ authed: false, role: 'admin' })
    expect(approvalApi.pendingCount).not.toHaveBeenCalled()
  })

  it('有待审时显示数字', async () => {
    const w = await mountNavbar({ role: 'admin', pending: 7 })
    expect(w.find('.nav-badge').text()).toBe('7')
  })

  it('为 0 时不显示角标——空徽标是视觉噪音', async () => {
    const w = await mountNavbar({ role: 'admin', pending: 0 })
    expect(w.find('.nav-badge').exists()).toBe(false)
  })

  it('超过 99 显示 99+，不撑破布局', async () => {
    const w = await mountNavbar({ role: 'admin', pending: 128 })
    expect(w.find('.nav-badge').text()).toBe('99+')
  })

  it('拉取失败降级为 0，不弹错误打扰用户', async () => {
    // 角标是增值提示。为它弹一个红色错误框，
    // 会让用户以为系统出了大问题（同 6.51 降级策略）
    approvalApi.pendingCount.mockRejectedValue(new Error('boom'))
    const w = await mountNavbar({ role: 'admin' })

    expect(w.find('.nav-badge').exists()).toBe(false)
    expect(notifyMock.error).not.toHaveBeenCalled()
  })
})

describe('未登录态', () => {
  it('显示登录入口，不显示用户菜单', async () => {
    const w = await mountNavbar({ authed: false })

    expect(w.find('.login-btn').exists()).toBe(true)
    expect(w.find('.notification-btn').exists()).toBe(false)
  })

  it('已登录显示通知与用户菜单，不显示登录入口', async () => {
    const w = await mountNavbar({ authed: true })

    expect(w.find('.login-btn').exists()).toBe(false)
    expect(w.find('.notification-btn').exists()).toBe(true)
  })
})

describe('当前页高亮', () => {
  it('子路由也能匹配到父级菜单', async () => {
    // 用户在 /tickets/TKT-001 时，「智能工单」必须仍是高亮态，
    // 否则他会以为自己不在这个模块里
    const w = await mountNavbar()

    await router.push('/tickets')
    await w.vm.$nextTick()
    expect(vmOf(w).activeKey).toBe('tickets')

    await router.push('/knowledge')
    await w.vm.$nextTick()
    expect(vmOf(w).activeKey).toBe('knowledge')
  })

  it('根路径高亮首页', async () => {
    const w = await mountNavbar()
    expect(vmOf(w).activeKey).toBe('home')
  })

  it('未知路径回落到首页，不出现「哪个都不亮」', async () => {
    const w = await mountNavbar()
    await router.push('/alerts/1')
    await w.vm.$nextTick()

    expect(vmOf(w).activeKey).toBe('home')
  })
})

describe('通知', () => {
  it('关闭通知开关后未读数归零——设置必须真的生效', async () => {
    // store 里仍有未读，但用户已关掉通知。
    // 这里若直接读 store.unreadCount，关开关等于没关
    const w = await mountNavbar({ notificationsEnabled: false, unread: 5 })
    expect(vmOf(w).unreadCount).toBe(0)
    expect(w.find('.notification-badge').exists()).toBe(false)
  })

  it('开启时正常显示未读数', async () => {
    const w = await mountNavbar({ notificationsEnabled: true, unread: 5 })
    expect(vmOf(w).unreadCount).toBe(5)
  })

  it('点通知标记已读并跳转', async () => {
    const w = await mountNavbar({
      items: [{ id: 'n1', title: '告警', read: false, linkTo: '/alerts/1' }],
    })

    vmOf(w).readNotification({ id: 'n1', linkTo: '/alerts/1' })
    await flushPromises()

    expect(notifStore.markRead).toHaveBeenCalledWith('n1')
    expect(router.currentRoute.value.path).toBe('/alerts/1')
  })

  it('无跳转链接的通知只标已读，不导航', async () => {
    const w = await mountNavbar({ items: [{ id: 'n2', title: '提示', read: false }] })
    const before = router.currentRoute.value.path

    vmOf(w).readNotification({ id: 'n2' })
    await flushPromises()

    expect(notifStore.markRead).toHaveBeenCalledWith('n2')
    expect(router.currentRoute.value.path).toBe(before)
  })

  it('全部已读给出反馈', async () => {
    const w = await mountNavbar()
    vmOf(w).markAllRead()

    expect(notifStore.markAllRead).toHaveBeenCalled()
    expect(notifyMock.success).toHaveBeenCalled()
  })
})

describe('下拉面板互斥', () => {
  it('打开通知会关掉用户菜单，反之亦然', async () => {
    // 两个面板同时展开会重叠遮挡，且点击区域互相干扰
    const w = await mountNavbar()
    const vm = vmOf(w)

    vm.toggleUserMenu()
    expect(vm.showUserMenu).toBe(true)

    vm.toggleNotifications()
    expect(vm.showNotifications).toBe(true)
    expect(vm.showUserMenu).toBe(false)

    vm.toggleUserMenu()
    expect(vm.showUserMenu).toBe(true)
    expect(vm.showNotifications).toBe(false)
  })

  it('点击面板外部关闭下拉', async () => {
    const w = await mountNavbar()
    const vm = vmOf(w)
    vm.showNotifications = true
    vm.showUserMenu = true

    document.body.click()
    await w.vm.$nextTick()

    expect(vm.showNotifications).toBe(false)
    expect(vm.showUserMenu).toBe(false)
  })
})

describe('退出登录', () => {
  it('先二次确认，取消则不登出', async () => {
    confirmMock.mockRejectedValue('cancel')
    const w = await mountNavbar()

    await vmOf(w).doLogout()

    expect(appStore.signOut).not.toHaveBeenCalled()
  })

  it('确认后清登录态并跳登录页', async () => {
    const w = await mountNavbar()

    await vmOf(w).doLogout()
    await flushPromises()

    expect(appStore.signOut).toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('await signOut 之后才跳转——否则可能带着未清的 token 进登录页', async () => {
    // signOut 含后端 logout 调用。不 await 就跳转，
    // 登录页可能读到尚未清除的 token 而误判为已登录
    let resolveSignOut: () => void = () => {}
    appStore.signOut.mockReturnValue(new Promise<void>((r) => { resolveSignOut = r }))
    const w = await mountNavbar()

    const p = vmOf(w).doLogout()
    await flushPromises()
    expect(router.currentRoute.value.path).not.toBe('/login')

    resolveSignOut()
    await p
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/login')
  })
})

describe('卸载清理', () => {
  it('卸载后全局 click 监听已解绑', async () => {
    // 不解绑是内存泄漏，且回调引用的是已卸载组件的闭包
    const spy = vi.spyOn(document, 'removeEventListener')
    const w = await mountNavbar()

    w.unmount()

    expect(spy).toHaveBeenCalledWith('click', expect.any(Function))
    spy.mockRestore()
  })
})
