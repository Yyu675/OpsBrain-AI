/**
 * 实时监控页组件测试。
 *
 * ── 覆盖重点 ──────────────────────────────────────────────────
 * 1. **单卡失败不拖垮整页**。某个 exporter 没起时那张卡显示错误，
 *    其余照常——这比整页空白有用得多。
 *
 * 2. **多实例取最大值**。监控关心的是「最糟的那台」；取平均会让
 *    「一台 100% + 三台 0%」显示成 25%，把真正的故障抹平。
 *
 * 3. **null 不能被当成 0**。无数据（实例刚重启）画成 0 会在趋势图上
 *    产生一个假的「跌到底」，看起来像服务挂了。
 *
 * 4. **暂停与卸载**。排障时用户要盯住某一刻的数值，页面自己刷掉很干扰；
 *    而卸载不清定时器会在登出后继续打接口。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  fetchOverview: vi.fn(),
  fetchRange: vi.fn(),
}))
vi.mock('@/api/metrics', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/metrics')
  return { ...actual, ...api }
})

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

import Monitoring from '../Monitoring.vue'

const card = (over: Record<string, unknown> = {}) => ({
  name: 'CPU 使用率',
  unit: 'percent' as const,
  describe: '主机 CPU 繁忙程度',
  ok: true,
  samples: [{ labels: { instance: 'node:9100' }, value: 42, timestamp: 1000 }],
  ...over,
})

const OVERVIEW = {
  cards: {
    'cpu.usage': card(),
    'memory.usage': card({ name: '内存使用率' }),
    'disk.usage': card({ name: '磁盘使用率' }),
    'load.avg1': card({ name: '1 分钟负载', unit: 'count' as const }),
    'target.up': card({
      name: '抓取目标存活',
      unit: 'count' as const,
      samples: [{ labels: { instance: 'node:9100' }, value: 1, timestamp: 1000 }],
    }),
  },
  timestamp: 1_700_000_000_000,
}

let router: Router

const mountPage = async (overview = OVERVIEW) => {
  api.fetchOverview.mockResolvedValue(overview)
  api.fetchRange.mockResolvedValue({
    metric: 'cpu.usage', from: 0, to: 1, step: 120, hours: 1,
    series: [{ labels: {}, points: [{ t: 1000, v: 10 }, { t: 2000, v: 20 }] }],
  })

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/monitoring', component: Monitoring },
      { path: '/integrations', component: defineComponent({ template: '<div/>' }) },
    ],
  })
  await router.push('/monitoring')
  await router.isReady()

  const wrapper = mount(Monitoring, {
    global: {
      plugins: [router],
      stubs: {
        DataStateBoundary: { template: '<div><slot /></div>' },
        TrendChart: true,
      },
    },
  })
  await vi.waitFor(() => expect(api.fetchOverview).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  await wrapper.vm.$nextTick()
  return wrapper
}

type Vm = {
  cards: Record<string, ReturnType<typeof card>>
  sparklines: Record<string, { labels: string[]; values: number[] }>
  loading: boolean
  loadError: unknown
  paused: boolean
  lastUpdated: number | null
  orderedCards: Array<{ id: string; card: ReturnType<typeof card> }>
  downTargets: number | null
  primaryValue: (c: ReturnType<typeof card>) => number | null
  alertingCount: (c: ReturnType<typeof card>) => number
  cardSeverity: (c: ReturnType<typeof card>) => string
  togglePause: () => void
  loadAll: () => Promise<void>
}

const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.useRealTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Monitoring — 加载与卡片顺序', () => {
  it('按固定顺序展示卡片，避免每次刷新跳位', async () => {
    const vm = vmOf(await mountPage())
    expect(vm.orderedCards.map((c) => c.id)).toEqual([
      'cpu.usage', 'memory.usage', 'disk.usage', 'load.avg1', 'target.up',
    ])
  })

  it('后端未返回的卡片被跳过，不渲染空壳', async () => {
    const vm = vmOf(await mountPage({
      cards: { 'cpu.usage': card() },
      timestamp: 1,
    }))
    expect(vm.orderedCards).toHaveLength(1)
  })

  it('记录更新时间，让用户知道数据有多新', async () => {
    const vm = vmOf(await mountPage())
    expect(vm.lastUpdated).toBe(1_700_000_000_000)
  })
})

describe('Monitoring — 单卡失败隔离', () => {
  it('某张卡 ok:false 时其余仍正常渲染', async () => {
    const vm = vmOf(await mountPage({
      cards: {
        'cpu.usage': card({ ok: false, error: 'exporter 未启动', samples: [] }),
        'memory.usage': card({ name: '内存使用率' }),
      },
      timestamp: 1,
    }))

    // 一个 exporter 没起不该让整页空白
    expect(vm.orderedCards).toHaveLength(2)
    expect(vm.cards['cpu.usage'].ok).toBe(false)
    expect(vm.loadError).toBeNull()
  })
})

describe('Monitoring — 多实例取值', () => {
  it('取最大值而非平均——监控关心「最糟的那台」', async () => {
    const c = card({
      samples: [
        { labels: { instance: 'a' }, value: 100, timestamp: 1 },
        { labels: { instance: 'b' }, value: 0, timestamp: 1 },
        { labels: { instance: 'c' }, value: 0, timestamp: 1 },
        { labels: { instance: 'd' }, value: 0, timestamp: 1 },
      ],
    })
    const vm = vmOf(await mountPage())

    // 取平均会显示 25%，把「一台已经打满」这个事实抹平
    expect(vm.primaryValue(c)).toBe(100)
  })

  it('全为 null 时返回 null，不返回 0', async () => {
    const c = card({
      samples: [{ labels: { instance: 'a' }, value: null, timestamp: 1 }],
    })
    const vm = vmOf(await mountPage())
    // 返回 0 会显示成「CPU 0%」，与「取不到数据」是完全不同的两件事
    expect(vm.primaryValue(c)).toBeNull()
  })

  it('混合 null 与数字时只统计有效值', async () => {
    const c = card({
      samples: [
        { labels: { instance: 'a' }, value: null, timestamp: 1 },
        { labels: { instance: 'b' }, value: 55, timestamp: 1 },
      ],
    })
    expect(vmOf(await mountPage()).primaryValue(c)).toBe(55)
  })

  it('0 是有效读数，不被当成空值', async () => {
    const c = card({
      samples: [{ labels: { instance: 'a' }, value: 0, timestamp: 1 }],
    })
    expect(vmOf(await mountPage()).primaryValue(c)).toBe(0)
  })
})

describe('Monitoring — 告警计数与档位', () => {
  it('统计超阈值的实例数', async () => {
    const c = card({
      samples: [
        { labels: { instance: 'a' }, value: 95, timestamp: 1 },
        { labels: { instance: 'b' }, value: 80, timestamp: 1 },
        { labels: { instance: 'c' }, value: 10, timestamp: 1 },
      ],
    })
    // 95 danger + 80 warn = 2 个非 normal
    expect(vmOf(await mountPage()).alertingCount(c)).toBe(2)
  })

  it('卡片档位由最大值决定', async () => {
    const c = card({
      samples: [
        { labels: { instance: 'a' }, value: 95, timestamp: 1 },
        { labels: { instance: 'b' }, value: 5, timestamp: 1 },
      ],
    })
    expect(vmOf(await mountPage()).cardSeverity(c)).toBe('danger')
  })

  it('count 类指标不按百分比阈值判危', async () => {
    const c = card({
      unit: 'count' as const,
      samples: [{ labels: { instance: 'a' }, value: 99, timestamp: 1 }],
    })
    // 「负载 99」在超大机器上也许正常，用 percent 阈值会误报
    expect(vmOf(await mountPage()).cardSeverity(c)).toBe('normal')
  })
})

describe('Monitoring — 掉线目标提醒', () => {
  it('统计 value=0 的抓取目标', async () => {
    const vm = vmOf(await mountPage({
      cards: {
        'target.up': card({
          name: '抓取目标存活',
          unit: 'count' as const,
          samples: [
            { labels: { instance: 'a' }, value: 1, timestamp: 1 },
            { labels: { instance: 'b' }, value: 0, timestamp: 1 },
            { labels: { instance: 'c' }, value: 0, timestamp: 1 },
          ],
        }),
      },
      timestamp: 1,
    }))
    // 抓取目标掉线意味着那些指标已停止更新，页面数值可能是陈旧的，
    // 必须最先告诉用户
    expect(vm.downTargets).toBe(2)
  })

  it('target.up 查询失败时返回 null，不谎报「全部正常」', async () => {
    const vm = vmOf(await mountPage({
      cards: { 'target.up': card({ ok: false, error: 'x', samples: [] }) },
      timestamp: 1,
    }))
    expect(vm.downTargets).toBeNull()
  })
})

describe('Monitoring — 迷你趋势', () => {
  it('只对 percent 类指标拉趋势，少给 Prometheus 压力', async () => {
    await mountPage()
    await vi.waitFor(() => expect(api.fetchRange).toHaveBeenCalled())

    const requested = api.fetchRange.mock.calls.map((c) => c[0])
    expect(requested).toEqual(['cpu.usage', 'memory.usage', 'disk.usage'])
    // 负载要和核数比、存活是 0/1 阶跃，画折线都没有可读性
    expect(requested).not.toContain('load.avg1')
    expect(requested).not.toContain('target.up')
  })

  it('null 点转成 NaN 让图表断线，而不是画成 0', async () => {
    api.fetchOverview.mockResolvedValue(OVERVIEW)
    api.fetchRange.mockResolvedValue({
      metric: 'cpu.usage', from: 0, to: 1, step: 120, hours: 1,
      series: [{ labels: {}, points: [{ t: 1000, v: 10 }, { t: 2000, v: null }] }],
    })

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/monitoring', component: Monitoring }],
    })
    await router.push('/monitoring')
    await router.isReady()
    const w = mount(Monitoring, {
      global: {
        plugins: [router],
        stubs: { DataStateBoundary: { template: '<div><slot /></div>' }, TrendChart: true },
      },
    })
    await vi.waitFor(() =>
      expect(Object.keys(vmOf(w).sparklines).length).toBeGreaterThan(0)
    )

    const values = vmOf(w).sparklines['cpu.usage'].values
    // 用 0 会画出假的「跌到底」，看起来像服务挂了
    expect(values[0]).toBe(10)
    expect(Number.isNaN(values[1])).toBe(true)
  })

  it('趋势请求失败不影响主数据', async () => {
    api.fetchOverview.mockResolvedValue(OVERVIEW)
    api.fetchRange.mockRejectedValue(new Error('boom'))

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/monitoring', component: Monitoring }],
    })
    await router.push('/monitoring')
    await router.isReady()
    const w = mount(Monitoring, {
      global: {
        plugins: [router],
        stubs: { DataStateBoundary: { template: '<div><slot /></div>' }, TrendChart: true },
      },
    })
    await vi.waitFor(() => expect(api.fetchOverview).toHaveBeenCalled())
    await w.vm.$nextTick()

    expect(vmOf(w).loadError).toBeNull()
    expect(vmOf(w).orderedCards.length).toBeGreaterThan(0)
  })
})

describe('Monitoring — 自动刷新', () => {
  it('暂停后不再自动刷新——排障时数值跳动很干扰', async () => {
    vi.useFakeTimers()
    api.fetchOverview.mockResolvedValue(OVERVIEW)
    api.fetchRange.mockResolvedValue({
      metric: 'x', from: 0, to: 1, step: 120, hours: 1, series: [],
    })

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/monitoring', component: Monitoring }],
    })
    await router.push('/monitoring')
    await router.isReady()
    const w = mount(Monitoring, {
      global: {
        plugins: [router],
        stubs: { DataStateBoundary: { template: '<div><slot /></div>' }, TrendChart: true },
      },
    })
    await vi.advanceTimersByTimeAsync(0)

    vmOf(w).togglePause()
    expect(vmOf(w).paused).toBe(true)

    const before = api.fetchOverview.mock.calls.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.fetchOverview.mock.calls.length).toBe(before)
  })

  it('卸载后停止轮询', async () => {
    vi.useFakeTimers()
    api.fetchOverview.mockResolvedValue(OVERVIEW)
    api.fetchRange.mockResolvedValue({
      metric: 'x', from: 0, to: 1, step: 120, hours: 1, series: [],
    })

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/monitoring', component: Monitoring }],
    })
    await router.push('/monitoring')
    await router.isReady()
    const w = mount(Monitoring, {
      global: {
        plugins: [router],
        stubs: { DataStateBoundary: { template: '<div><slot /></div>' }, TrendChart: true },
      },
    })
    await vi.advanceTimersByTimeAsync(0)

    const before = api.fetchOverview.mock.calls.length
    w.unmount()
    await vi.advanceTimersByTimeAsync(120_000)

    // 不清定时器会在登出后继续打接口
    expect(api.fetchOverview.mock.calls.length).toBe(before)
  })
})
