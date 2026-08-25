/**
 * Home（首页 / 落地页）—— **渲染冒烟测试**。
 *
 * ── 为什么补这一页 ────────────────────────────────────────────
 * 它是**公开路由**——未登录用户看到的第一个页面，597 行、此前零测试，
 * 且上一轮暗色模式修复动过它 4 处背景色，改完没有断言兜底。
 *
 * ── 真正的风险在「访客态」这条分支上 ─────────────────────────
 * 首页是公开的，但它展示的统计来自 `/dashboard/overview`，
 * 而那个端点在 `/api/**` 之下受 SaInterceptor 保护。
 *
 * 访客调用它 → 401 → http 层派发 `auth:unauthorized`
 * → 用户**被踢回登录页**。
 *
 * 也就是说：如果 `isGuest` 判断失效，"未登录也能看首页"这个需求
 * 会以最难排查的方式失效——用户打开首页，瞬间被弹到登录页，
 * 而控制台只有一条 401，看不出是首页自己把自己踢走的。
 *
 * 所以本文件的核心是：**访客态下一个请求都不许发出去。**
 *
 * ── 四态互斥 ──────────────────────────────────────────────────
 * 访客 / 加载中 / 加载失败 / 有数据 是四个平级分支，
 * 拆改时最容易漏搬其中一个，结果是两块同时显示或全都不显示。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

const api = vi.hoisted(() => ({
  getDashboardOverview: vi.fn(),
  getClosureMetrics: vi.fn(),
  getRootCauseStats: vi.fn(),
  getTrends: vi.fn(),
}))
vi.mock('@/api/dashboard', () => api)

const appStore = vi.hoisted(() => ({ isAuthenticated: false }))
vi.mock('@/stores/app', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/app')
  return { ...actual, useAppStore: () => appStore }
})

import Home from '../Home.vue'

/** 完整满足 DashboardOverview 契约——少一个字段会让整个分支渲染失败 */
const overview = (over: Record<string, unknown> = {}) => ({
  totalQueries: 12345,
  cacheHits: 5000,
  cacheHitRate: 40.5,
  avgCostRmb: 0.01234,
  totalTickets: 678,
  modelDistribution: [],
  costSavingsChart: [],
  ...over,
})

let router: Router

const mountHome = async (opts: { authed?: boolean; data?: unknown; error?: unknown } = {}) => {
  appStore.isAuthenticated = opts.authed ?? false

  if (opts.error) {
    api.getDashboardOverview.mockRejectedValue(opts.error)
  } else {
    api.getDashboardOverview.mockResolvedValue('data' in opts ? opts.data : overview())
  }

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Home },
      { path: '/login', component: defineComponent({ template: '<div/>' }) },
      { path: '/ai-chat', component: defineComponent({ template: '<div/>' }) },
      { path: '/tickets', component: defineComponent({ template: '<div/>' }) },
      { path: '/knowledge', component: defineComponent({ template: '<div/>' }) },
      { path: '/dashboard', component: defineComponent({ template: '<div/>' }) },
      { path: '/monitoring', component: defineComponent({ template: '<div/>' }) },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(Home, {
    global: {
      plugins: [router],
      stubs: {
        SafeImage: { name: 'SafeImage', template: '<img class="stub-img" />' },
        ApiErrorState: { name: 'ApiErrorState', template: '<div class="stub-error" />' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

type Vm = {
  isGuest: boolean
  loading: boolean
  loadError: unknown
  stats: { label: string; value: string }[]
  reload: () => Promise<void>
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('访客态：一个请求都不许发', () => {
  it('未登录时不调用受保护的统计接口', async () => {
    // ── 本文件最重要的一条 ──────────────────────────────────
    // 发出去就是 401 → auth:unauthorized → 用户被踢回登录页，
    // 「访客能看首页」这个需求当场失效，且现象极难归因
    await mountHome({ authed: false })

    expect(api.getDashboardOverview).not.toHaveBeenCalled()
  })

  it('访客态显示登录引导，而不是错误或空白', async () => {
    const w = await mountHome({ authed: false })

    expect(vmOf(w).isGuest).toBe(true)
    expect(w.find('.stats-guest').exists()).toBe(true)
    // 未登录不是错误状态，不该显示错误组件
    expect(w.findComponent({ name: 'ApiErrorState' }).exists()).toBe(false)
  })

  it('访客态不残留加载中或旧数据', async () => {
    const w = await mountHome({ authed: false })
    const vm = vmOf(w)

    expect(vm.loading).toBe(false)
    expect(vm.loadError).toBeNull()
    expect(vm.stats).toEqual([])
  })

  it('登录引导指向登录页', async () => {
    const w = await mountHome({ authed: false })
    expect(w.find('.stats-guest').attributes('href')).toContain('/login')
  })
})

describe('已登录：加载统计', () => {
  it('已登录时才发请求', async () => {
    await mountHome({ authed: true })
    expect(api.getDashboardOverview).toHaveBeenCalledTimes(1)
  })

  it('四项统计按固定顺序渲染，标签与值配对不串位', async () => {
    const w = await mountHome({ authed: true })

    expect(vmOf(w).stats).toEqual([
      { label: '智能问答', value: '12,345' },
      { label: '工单总数', value: '678' },
      { label: '缓存命中率', value: '40.5%' },
      { label: '平均成本', value: '0.0123 元' },
    ])
  })

  it('半分位取整遵循 toFixed 的实际行为，不假设"四舍五入"', async () => {
    // 夹具最初用 40.55 并期望 "40.6%"，实测得到 "40.5%"。
    // 原因是 40.55 的双精度表示是 40.549999999999997，toFixed 据此向下。
    //
    // 这是 JS 浮点的既有行为，不是产品缺陷——**没有去改产品代码**，
    // 而是把期望改成实际行为，并在这里记下来：
    // 将来若有人"修正"成 Math.round(x*10)/10，这条会失败并提醒他
    // 那是一次行为变更（展示值会变），需要有意识地做而不是顺手改。
    const w = await mountHome({ authed: true, data: overview({ cacheHitRate: 40.55 }) })
    expect(vmOf(w).stats[2].value).toBe('40.5%')
  })

  it('大数字带千分位——首页是展示面，12345 不如 12,345 好读', async () => {
    const w = await mountHome({ authed: true, data: overview({ totalQueries: 1234567 }) })
    expect(vmOf(w).stats[0].value).toBe('1,234,567')
  })

  it('成本保留 4 位小数，两位会全部显示成 0.01', async () => {
    const w = await mountHome({ authed: true, data: overview({ avgCostRmb: 0.00456 }) })
    expect(vmOf(w).stats[3].value).toBe('0.0046 元')
  })

  it('字段缺失时按 0 兜底，不显示 NaN 或 undefined', async () => {
    // 后端字段可选/未返回时，首页作为展示面显示 NaN 尤其难看
    const w = await mountHome({
      authed: true,
      data: { modelDistribution: [], costSavingsChart: [] },
    })
    const values = vmOf(w).stats.map((s) => s.value)

    expect(values).toEqual(['0', '0', '0.0%', '0.0000 元'])
    for (const v of values) {
      expect(v).not.toContain('NaN')
      expect(v).not.toContain('undefined')
    }
  })

  it('DOM 里确实渲染出统计项，不只是计算对了', async () => {
    const w = await mountHome({ authed: true })
    expect(w.findAll('.stat-item')).toHaveLength(4)
  })
})

describe('四态互斥', () => {
  it('加载失败：显示错误组件，不显示统计与访客引导', async () => {
    const w = await mountHome({ authed: true, error: new Error('boom') })

    expect(w.findComponent({ name: 'ApiErrorState' }).exists()).toBe(true)
    expect(w.findAll('.stat-item')).toHaveLength(0)
    expect(w.find('.stats-guest').exists()).toBe(false)
  })

  it('加载失败时清空**已有**旧数据，不让过期数字停在首页', async () => {
    // ── 这条是注入验证补出来的 ────────────────────────────────
    // 原写法直接以失败态挂载，此时 stats 本来就是空的 ——
    // 把 catch 里的 `stats.value = []` 删掉照样通过（假绿）。
    //
    // 「残留旧数据」只可能发生在**先成功、后失败**的序列里：
    // 用户看着 12,345 次问答，刷新失败了，页面若不清空，
    // 错误提示旁边还挂着一个可能已经过期很久的数字。
    const w = await mountHome({ authed: true })
    expect(vmOf(w).stats).toHaveLength(4)

    api.getDashboardOverview.mockRejectedValue(new Error('boom'))
    await vmOf(w).reload()
    await flushPromises()

    expect(vmOf(w).loadError).toBeTruthy()
    expect(vmOf(w).stats).toEqual([])
  })

  it('成功态不显示错误组件与访客引导', async () => {
    const w = await mountHome({ authed: true })

    expect(w.findComponent({ name: 'ApiErrorState' }).exists()).toBe(false)
    expect(w.find('.stats-guest').exists()).toBe(false)
    expect(w.findAll('.stat-item').length).toBeGreaterThan(0)
  })

  it('失败后重试成功能恢复正常态', async () => {
    const w = await mountHome({ authed: true, error: new Error('boom') })
    expect(vmOf(w).loadError).toBeTruthy()

    api.getDashboardOverview.mockResolvedValue(overview())
    await vmOf(w).reload()
    await flushPromises()

    // loadError 必须被清掉，否则错误态永远挂着
    expect(vmOf(w).loadError).toBeNull()
    expect(vmOf(w).stats).toHaveLength(4)
  })
})

describe('静态内容', () => {
  it('渲染主标题与三张能力卡片', async () => {
    // 落地页的核心内容。整块被删掉时上面那些 vm 断言照样通过
    const w = await mountHome({ authed: false })

    expect(w.find('.hero-title').exists()).toBe(true)
    expect(w.findAll('.feature-card')).toHaveLength(3)
  })

  it('每张卡片都有标题与描述，不留空壳', async () => {
    const w = await mountHome({ authed: false })

    for (const card of w.findAll('.feature-card')) {
      expect(card.find('.feature-title').text().length).toBeGreaterThan(0)
      expect(card.find('.feature-desc').text().length).toBeGreaterThan(0)
    }
  })

  it('访客态也渲染完整落地页——它是公开路由', async () => {
    const w = await mountHome({ authed: false })

    expect(w.find('.hero-section').exists()).toBe(true)
    expect(w.find('.features-section').exists()).toBe(true)
    expect(w.find('.cta-section').exists()).toBe(true)
  })
})
