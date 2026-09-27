/**
 * Home（首页）—— **渲染冒烟测试**。
 *
 * ── 2026-09-27 去营销化后的定位 ───────────────────────────────
 * Home 不再是营销落地页，而是**分发器**：
 * - 登录用户 → 直接渲染值班工作台（Dashboard 组件），同一组件
 *   单一事实源，不复制看板内容；
 * - 访客 → 登录引导页（一句话说明 + 能力清单），**一个请求都不许发**。
 *
 * ── 为什么「访客不发请求」是必须钉住的 ────────────────────────
 * 工作台数据来自 `/dashboard/overview`，该端点受 SaInterceptor 保护。
 * 访客调用它 → 401 → http 层派发 `auth:unauthorized` →
 * 用户被踢回登录页，「访客默认看首页」的需求当场失效，
 * 且现象（打开首页瞬间跳登录页）极难归因。
 * 对分发器来说，这条契约的结构化表述就是：
 * **访客态下 Dashboard 组件根本不能被挂载。**
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const appStore = vi.hoisted(() => ({ isAuthenticated: false }))
vi.mock('@/stores/app', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/app')
  return { ...actual, useAppStore: () => appStore }
})

import Home from '../Home.vue'

/** 桩掉工作台：Home 的契约是「分发」，Dashboard 自身行为由它自己的测试守着 */
const DashboardStub = defineComponent({
  name: 'Dashboard',
  template: '<div class="stub-dashboard" />',
})

let router: Router

const mountHome = async (opts: { authed?: boolean } = {}) => {
  appStore.isAuthenticated = opts.authed ?? false

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Home },
      { path: '/login', component: defineComponent({ template: '<div/>' }) },
      { path: '/tickets', component: defineComponent({ template: '<div/>' }) },
      { path: '/knowledge', component: defineComponent({ template: '<div/>' }) },
      { path: '/alerts', component: defineComponent({ template: '<div/>' }) },
      { path: '/monitoring', component: defineComponent({ template: '<div/>' }) },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(Home, {
    global: {
      plugins: [router],
      stubs: { Dashboard: DashboardStub },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('访客态：登录引导页，一个请求都不许发', () => {
  it('访客不挂载 Dashboard —— 挂载即触发受保护请求，访客会被 401 踢去登录页', async () => {
    // ── 本文件最重要的一条 ──────────────────────────────────
    const w = await mountHome({ authed: false })

    expect(w.find('.stub-dashboard').exists()).toBe(false)
    expect(w.find('.guest-home').exists()).toBe(true)
  })

  it('登录入口指向 /login', async () => {
    const w = await mountHome({ authed: false })

    const login = w.find('.guest-login')
    expect(login.exists()).toBe(true)
    expect(login.attributes('href')).toContain('/login')
  })

  it('能力清单是诚实预告：每张卡有名称、说明与可达链接', async () => {
    const w = await mountHome({ authed: false })

    const cards = w.findAll('.guest-cap')
    expect(cards).toHaveLength(4)
    for (const card of cards) {
      expect(card.find('.guest-cap-name').text().length).toBeGreaterThan(0)
      expect(card.find('.guest-cap-desc').text().length).toBeGreaterThan(0)
      // 链接受保护模块——点击后由路由守卫引导登录，不是死按钮
      expect(card.attributes('href')).toBeTruthy()
    }
  })

  it('营销内容已退役：不再有 hero 图、免费试用与「数百家企业」话术', async () => {
    const w = await mountHome({ authed: false })
    const html = w.html()

    expect(html).not.toContain('免费试用')
    expect(html).not.toContain('数百家企业')
    expect(w.find('.hero-image').exists()).toBe(false)
  })
})

describe('已登录：直接渲染值班工作台', () => {
  it('挂载 Dashboard 组件，不显示访客引导', async () => {
    const w = await mountHome({ authed: true })

    expect(w.find('.stub-dashboard').exists()).toBe(true)
    expect(w.find('.guest-home').exists()).toBe(false)
  })

  it('工作台内容与首页同址——不再要求值班人先读营销页再点进看板', async () => {
    // 结构断言：/ 路由渲染的就是 Home，Home 对登录用户渲染 Dashboard
    const w = await mountHome({ authed: true })
    expect(router.currentRoute.value.path).toBe('/')
    expect(w.findComponent(DashboardStub).exists()).toBe(true)
  })
})
