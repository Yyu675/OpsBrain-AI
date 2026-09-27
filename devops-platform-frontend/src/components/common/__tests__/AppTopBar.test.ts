/**
 * AppTopBar（内容区顶部窄条）测试。
 *
 * 由 AppNavbar 测试拆分而来（2026-09-27 侧栏布局壳改造）：
 * 通知/搜索/访客登录入口归 TopBar，导航/用户菜单归 AppSidebar。
 *
 * 保护的行为：
 * - 访客态：显示登录入口、不显示通知（通知需受保护 API，访客调用必 401）
 * - 通知开关的诚实性：关闭时未读角标必须归零（关开关等于没关是最蠢的坏法）
 * - 通知点击的已读 + 跳转语义
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

vi.mock('@/utils/notify', () => ({
  notify: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(), clearCooldown: vi.fn() },
  handleServerError: vi.fn(),
}))

const appStore = vi.hoisted(() => ({
  isAuthenticated: true,
  settings: { notificationsEnabled: true },
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

import AppTopBar from '../AppTopBar.vue'

let router: Router
const blank = defineComponent({ template: '<div/>' })

const mountTopBar = async (over: {
  authed?: boolean
  notificationsEnabled?: boolean
  unread?: number
  items?: typeof notifStore.items
} = {}) => {
  appStore.isAuthenticated = over.authed ?? true
  appStore.settings.notificationsEnabled = over.notificationsEnabled ?? true
  notifStore.unreadCount = over.unread ?? 0
  notifStore.items = over.items ?? []

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: blank },
      { path: '/login', component: blank },
      { path: '/settings', component: blank },
      { path: '/alerts/:id', component: blank },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(AppTopBar, {
    global: {
      plugins: [router],
      stubs: {
        // 全局搜索有自己的测试；这里只验证它「在不在场」，不牵进它的行为
        GlobalSearchBar: { template: '<div class="stub-search" />' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

type Vm = {
  unreadCount: number
  showNotifications: boolean
  toggleNotifications: () => void
  readNotification: (n: { id: string; linkTo?: string }) => void
  markAllRead: () => void
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  vi.clearAllMocks()
})

describe('访客态', () => {
  it('显示登录入口，不显示通知铃与全局搜索', async () => {
    const w = await mountTopBar({ authed: false })

    expect(w.find('.login-btn').exists()).toBe(true)
    expect(w.find('.notification-btn').exists()).toBe(false)
    expect(w.find('.stub-search').exists()).toBe(false)
  })
})

describe('已登录', () => {
  it('显示通知铃与全局搜索，不显示登录入口', async () => {
    const w = await mountTopBar({ authed: true })

    expect(w.find('.login-btn').exists()).toBe(false)
    expect(w.find('.notification-btn').exists()).toBe(true)
    expect(w.find('.stub-search').exists()).toBe(true)
  })
})

describe('通知', () => {
  it('关闭通知开关后未读数归零——设置必须真的生效', async () => {
    // store 里仍有未读，但用户已关掉通知。
    // 这里若直接读 store.unreadCount，关开关等于没关
    const w = await mountTopBar({ notificationsEnabled: false, unread: 5 })
    expect(vmOf(w).unreadCount).toBe(0)
    expect(w.find('.notification-badge').exists()).toBe(false)
  })

  it('开启时正常显示未读数', async () => {
    const w = await mountTopBar({ notificationsEnabled: true, unread: 5 })
    expect(vmOf(w).unreadCount).toBe(5)
  })

  it('点通知标记已读并跳转', async () => {
    const w = await mountTopBar({
      items: [{ id: 'n1', title: '告警', read: false, linkTo: '/alerts/1' }],
    })

    vmOf(w).readNotification({ id: 'n1', linkTo: '/alerts/1' })
    await flushPromises()

    expect(notifStore.markRead).toHaveBeenCalledWith('n1')
    expect(router.currentRoute.value.path).toBe('/alerts/1')
  })

  it('无跳转链接的通知只标已读，不导航', async () => {
    const w = await mountTopBar({ items: [{ id: 'n2', title: '提示', read: false }] })
    const before = router.currentRoute.value.path

    vmOf(w).readNotification({ id: 'n2' })
    await flushPromises()

    expect(notifStore.markRead).toHaveBeenCalledWith('n2')
    expect(router.currentRoute.value.path).toBe(before)
  })

  it('全部已读给出反馈', async () => {
    const w = await mountTopBar()
    vmOf(w).markAllRead()

    expect(notifStore.markAllRead).toHaveBeenCalled()
  })

  it('点击面板外部关闭通知下拉', async () => {
    const w = await mountTopBar()
    const vm = vmOf(w)
    vm.showNotifications = true

    document.body.click()
    await w.vm.$nextTick()

    expect(vm.showNotifications).toBe(false)
  })
})
