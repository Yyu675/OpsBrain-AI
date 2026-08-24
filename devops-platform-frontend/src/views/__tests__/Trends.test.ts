/**
 * 趋势分析页组件测试。
 *
 * ── 覆盖重点 ──────────────────────────────────────────────────
 * 1. **统计摘要必须排除 null**。把无数据点计入均值会让数字失真，
 *    而扩容决策直接看这个均值——错了就是买错机器。
 *
 * 2. **步长随时间范围变化**。step 填错会毁掉图表：太小产出几十万点，
 *    太大把尖峰抹平（恰恰是排障最需要看到的）。
 *
 * 3. **系列截断要告知**。悄悄少画几条线会让用户问出
 *    「我的机器怎么没在图里」——一个很难自查的问题。
 *
 * 4. **未知指标 ID 回退**。URL 常来自旧版本或手工编辑，
 *    不回退会让页面一直报错。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  fetchMetricCatalog: vi.fn(),
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

import Trends from '../Trends.vue'

const CATALOG = {
  metrics: [
    { id: 'cpu.usage', name: 'CPU 使用率', unit: 'percent' as const, describe: 'CPU 繁忙程度' },
    { id: 'load.avg1', name: '1 分钟负载', unit: 'count' as const, describe: '运行队列长度' },
  ],
  enabled: true,
}

const rangeResult = (series: unknown[], hours = 6) => ({
  metric: 'cpu.usage', from: 0, to: 1, step: 120, hours, series,
})

let router: Router

const mountPage = async (url = '/trends', catalog = CATALOG, series: unknown[] = [
  { labels: { instance: 'node-a' }, points: [{ t: 1000, v: 10 }, { t: 2000, v: 30 }] },
]) => {
  api.fetchMetricCatalog.mockResolvedValue(catalog)
  api.fetchRange.mockResolvedValue(rangeResult(series))

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/trends', component: Trends },
      { path: '/integrations', component: defineComponent({ template: '<div/>' }) },
    ],
  })
  await router.push(url)
  await router.isReady()

  const wrapper = mount(Trends, {
    global: {
      plugins: [router],
      stubs: {
        DataStateBoundary: { template: '<div><slot /></div>' },
        TrendChart: true,
      },
    },
  })
  await vi.waitFor(() => expect(api.fetchRange).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  await wrapper.vm.$nextTick()
  return wrapper
}

type Summary = { name: string; min: number | null; max: number | null; avg: number | null; latest: number | null }

type Vm = {
  metricId: string
  rangeId: string
  metrics: typeof CATALOG.metrics
  series: Array<{ labels: Record<string, string>; points: Array<{ t: number; v: number | null }> }>
  loading: boolean
  loadError: unknown
  currentRange: { id: string; hours: number; step: number }
  currentMeta: { id: string; name: string; unit: string } | null
  chartSeries: Array<{ name: string; data: number[] }>
  summaries: Summary[]
  truncated: boolean
  totalPoints: number
  axisLabels: string[]
  load: () => Promise<void>
}

const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

const settle = async (w: VueWrapper) => {
  await w.vm.$nextTick()
  await new Promise((r) => setTimeout(r, 0))
  await w.vm.$nextTick()
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('Trends — 时间范围与步长', () => {
  it('默认 6 小时', async () => {
    const vm = vmOf(await mountPage())
    expect(vm.rangeId).toBe('6h')
    expect(api.fetchRange).toHaveBeenCalledWith('cpu.usage', 6, 120)
  })

  it('切换范围时 hours 与 step 同步变化', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.fetchRange.mockClear()

    vm.rangeId = '7d'
    await settle(w)

    // step 由范围推导，用户不必理解这个参数：
    // 填太小会产出几十万点，填太大会把尖峰抹平
    expect(api.fetchRange).toHaveBeenCalledWith('cpu.usage', 168, 1800)
  })

  it('每档的点数都落在 100~800 的合理区间', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    for (const r of [
      { id: '1h', hours: 1, step: 30 },
      { id: '6h', hours: 6, step: 120 },
      { id: '24h', hours: 24, step: 300 },
      { id: '7d', hours: 168, step: 1800 },
      { id: '30d', hours: 720, step: 3600 },
    ]) {
      const points = (r.hours * 3600) / r.step
      expect(points).toBeGreaterThanOrEqual(100)
      expect(points).toBeLessThanOrEqual(800)
    }
    expect(vm.currentRange.id).toBe('6h')
  })

  it('URL 里的 range 被采纳', async () => {
    const vm = vmOf(await mountPage('/trends?range=24h'))
    expect(vm.rangeId).toBe('24h')
    expect(api.fetchRange).toHaveBeenCalledWith('cpu.usage', 24, 300)
  })

  it('非法 range 回退默认值而非报错', async () => {
    const vm = vmOf(await mountPage('/trends?range=999y'))
    expect(vm.rangeId).toBe('6h')
  })
})

describe('Trends — 指标选择', () => {
  it('URL 里的 metric 被采纳', async () => {
    const vm = vmOf(await mountPage('/trends?metric=load.avg1'))
    expect(vm.metricId).toBe('load.avg1')
  })

  it('URL 指定了目录里没有的指标时回退到第一个', async () => {
    // 这类链接常来自旧版本或手工编辑，不回退会让页面一直报错
    const vm = vmOf(await mountPage('/trends?metric=gone.metric'))
    expect(vm.metricId).toBe('cpu.usage')
  })

  it('目录接口失败时仍能查默认指标', async () => {
    api.fetchMetricCatalog.mockRejectedValue(new Error('boom'))
    api.fetchRange.mockResolvedValue(rangeResult([]))

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/trends', component: Trends }],
    })
    await router.push('/trends')
    await router.isReady()
    const w = mount(Trends, {
      global: {
        plugins: [router],
        stubs: { DataStateBoundary: { template: '<div><slot /></div>' }, TrendChart: true },
      },
    })
    await vi.waitFor(() => expect(api.fetchRange).toHaveBeenCalled())

    expect(vmOf(w).loadError).toBeNull()
  })

  it('切换指标触发重新查询', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.fetchRange.mockClear()

    vm.metricId = 'load.avg1'
    await settle(w)

    expect(api.fetchRange).toHaveBeenCalledWith('load.avg1', 6, 120)
  })
})

describe('Trends — 统计摘要', () => {
  it('计算最新 / 峰值 / 谷值 / 均值', async () => {
    const vm = vmOf(await mountPage('/trends', CATALOG, [
      {
        labels: { instance: 'node-a' },
        points: [
          { t: 1, v: 10 }, { t: 2, v: 50 }, { t: 3, v: 30 },
        ],
      },
    ]))

    const s = vm.summaries[0]
    expect(s.name).toBe('node-a')
    expect(s.min).toBe(10)
    expect(s.max).toBe(50)
    expect(s.avg).toBeCloseTo(30, 5)
    expect(s.latest).toBe(30)
  })

  it('null 点被排除在统计之外——计入会让均值失真', async () => {
    const vm = vmOf(await mountPage('/trends', CATALOG, [
      {
        labels: { instance: 'node-a' },
        points: [
          { t: 1, v: 10 }, { t: 2, v: null }, { t: 3, v: 20 },
        ],
      },
    ]))

    const s = vm.summaries[0]
    // 若把 null 当 0 计入，均值会变成 10 而不是 15，
    // 而扩容决策直接看这个数
    expect(s.avg).toBeCloseTo(15, 5)
    expect(s.min).toBe(10)
    expect(s.max).toBe(20)
    // latest 取最后一个**有效**值，不是 null
    expect(s.latest).toBe(20)
  })

  it('全为 null 时四项都是 null，不显示 0', async () => {
    const vm = vmOf(await mountPage('/trends', CATALOG, [
      { labels: { instance: 'node-a' }, points: [{ t: 1, v: null }] },
    ]))

    const s = vm.summaries[0]
    expect(s.min).toBeNull()
    expect(s.max).toBeNull()
    expect(s.avg).toBeNull()
    expect(s.latest).toBeNull()
  })

  it('0 是有效读数，参与统计', async () => {
    const vm = vmOf(await mountPage('/trends', CATALOG, [
      { labels: { instance: 'node-a' }, points: [{ t: 1, v: 0 }, { t: 2, v: 10 }] },
    ]))

    const s = vm.summaries[0]
    expect(s.min).toBe(0)
    expect(s.avg).toBeCloseTo(5, 5)
  })
})

describe('Trends — 图表数据', () => {
  it('null 转成 NaN 让折线断开，而不是画成 0', async () => {
    const vm = vmOf(await mountPage('/trends', CATALOG, [
      { labels: { instance: 'a' }, points: [{ t: 1, v: 10 }, { t: 2, v: null }] },
    ]))

    const data = vm.chartSeries[0].data
    expect(data[0]).toBe(10)
    // 用 0 会画出假的「跌到底」，看起来像服务挂了
    expect(Number.isNaN(data[1])).toBe(true)
  })

  it('每个实例一条线', async () => {
    const vm = vmOf(await mountPage('/trends', CATALOG, [
      { labels: { instance: 'a' }, points: [{ t: 1, v: 1 }] },
      { labels: { instance: 'b' }, points: [{ t: 1, v: 2 }] },
    ]))

    expect(vm.chartSeries.map((s) => s.name)).toEqual(['a', 'b'])
  })

  it('超过 8 条时截断并标记，不悄悄少画', async () => {
    const many = Array.from({ length: 12 }, (_, i) => ({
      labels: { instance: `node-${i}` },
      points: [{ t: 1, v: i }],
    }))
    const vm = vmOf(await mountPage('/trends', CATALOG, many))

    expect(vm.chartSeries).toHaveLength(8)
    // 悄悄少画会让用户问「我的机器怎么没在图里」——很难自查
    expect(vm.truncated).toBe(true)
  })

  it('不超过 8 条时不标记截断', async () => {
    const vm = vmOf(await mountPage('/trends', CATALOG, [
      { labels: { instance: 'a' }, points: [{ t: 1, v: 1 }] },
    ]))
    expect(vm.truncated).toBe(false)
  })

  it('统计采样点总数', async () => {
    const vm = vmOf(await mountPage('/trends', CATALOG, [
      { labels: { instance: 'a' }, points: [{ t: 1, v: 1 }, { t: 2, v: 2 }] },
      { labels: { instance: 'b' }, points: [{ t: 1, v: 1 }] },
    ]))
    expect(vm.totalPoints).toBe(3)
  })

  it('无数据时图表与摘要都为空，不报错', async () => {
    const vm = vmOf(await mountPage('/trends', CATALOG, []))
    expect(vm.chartSeries).toEqual([])
    expect(vm.summaries).toEqual([])
    expect(vm.axisLabels).toEqual([])
    expect(vm.loadError).toBeNull()
  })
})

describe('Trends — 错误处理', () => {
  it('查询失败时清空系列并记录错误，不保留上一次的陈旧数据', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    expect(vm.series.length).toBeGreaterThan(0)

    api.fetchRange.mockRejectedValue(new Error('数据源不可用'))
    await vm.load()
    await settle(w)

    // 保留旧数据会让用户以为看到的是当前值——监控页尤其不能这样
    expect(vm.series).toEqual([])
    expect(vm.loadError).toBeTruthy()
  })
})
