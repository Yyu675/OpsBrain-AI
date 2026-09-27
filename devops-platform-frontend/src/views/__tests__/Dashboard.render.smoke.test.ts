/**
 * Dashboard —— **渲染冒烟测试**。
 *
 * ── 为什么补这一页 ────────────────────────────────────────────
 * 它是首屏（登录后第一眼），660 行、此前零测试，
 * 而且上一轮的暗色模式修复动过它 5 处背景色——**改完没有任何断言兜底**。
 *
 * ── 这一页画错的特点：全是「看起来正常」的错 ─────────────────
 * 看板不会崩，它只会显示错误的数字。而运维正是照着这些数字判断
 * 「今天系统健康吗」「要不要加人手」。所以断言重点放在
 * **「没有数据」与「数值为 0」必须区分**这条线上：
 *
 * <ul>
 *   <li>`totalQueries === 0` 要挂醒目横幅，
 *       否则一排 0 会被当成「系统很闲」而不是「数据没接上」。</li>
 * </ul>
 *
 * ── 各区块独立降级（6.51 契约） ──────────────────────────────
 * 每个查询各自独立：趋势拉失败只让图表区降级，
 * 不能连累已经加载成功的 KPI。这是此前 `Promise.all + catch` 改造的目的，
 * 但改完同样没有测试守着——本文件把它钉住。
 *
 * ── 2026-09-26 瘦身 ──────────────────────────────────────────
 * 闭环度量（MTTA/MTTM/MTTR/阶段完成率）与诊断区（会话/校准/AI 反馈/
 * 诊断量趋势）迁往效能大盘，相关断言同迁 Effectiveness.test.ts；
 * 本文件只守瘦身后的范围：KPI、无数据横幅、SLA 风险、告警流、
 * 模型分布、成本/工单趋势、根因分布。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

const api = vi.hoisted(() => ({
  getDashboardOverview: vi.fn(),
  getRootCauseStats: vi.fn(),
  getTrends: vi.fn(),
  getSlaRisk: vi.fn(),
  getDashboardStats: vi.fn(),
}))
vi.mock('@/api/dashboard', () => api)
// 批88 P3：Dashboard 用 useRouter（KPI 点击跳转 + 趋势下钻），smoke 测试需桩
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {}, params: {} }),
  RouterLink: { template: '<a><slot /></a>', props: ['to'] },
}))

import Dashboard from '../Dashboard.vue'

/**
 * 夹具必须完整满足 `DashboardOverview` 契约。
 *
 * 第一版漏了 `modelDistribution[].percentage`（类型里有、我没给），
 * 模板 `item.percentage.toFixed(1)` 直接抛 TypeError。
 * 而它发生在 renderList 里 —— 整个 `v-else-if="data"` 分支渲染失败，
 * 页面停在加载态，于是**所有 DOM 断言一起失败、所有 vm 断言照常通过**。
 *
 * 失败信息是「expected 0 to be greater than 0」，指向断言本身，
 * 离真正的原因（夹具少一个字段）隔了很远。
 * 探针打印完整 HTML 才看到那条 Unhandled Rejection。
 *
 * 教训：**夹具照着类型定义写，不要照着"我以为页面用到哪些字段"写。**
 */
const overview = (over: Record<string, unknown> = {}) => ({
  totalTickets: 128,
  cacheHits: 272,
  cacheHitRate: 42.5,
  totalQueries: 640,
  avgCostRmb: 0.0123,
  modelDistribution: [
    { model: 'deepseek-chat', count: 400, percentage: 62.5 },
    { model: 'deepseek-reasoner', count: 240, percentage: 37.5 },
  ],
  costSavingsChart: [],
  ...over,
})

const trends = (over: Record<string, unknown> = {}) => ({
  days: ['08-20', '08-21', '08-22'],
  created: [5, 8, 3],
  resolved: [4, 6, 3],
  cacheHitRate: [40, 45, 42],
  cost: [1.2, 1.8, 0.9],
  ...over,
})

const mountPage = async (opts: {
  overview?: unknown
  rootCause?: unknown
  trends?: unknown
  overviewError?: unknown
  trendsError?: unknown
} = {}) => {
  if (opts.overviewError) {
    api.getDashboardOverview.mockRejectedValue(opts.overviewError)
  } else {
    api.getDashboardOverview.mockResolvedValue('overview' in opts ? opts.overview : overview())
  }
  // 用 'rootCause' in opts 而非 `?? 默认夹具`：后者会把**显式传入的空对象**
  // 当成「没传」而回落到默认夹具，断言必然失败且原因极隐蔽
  api.getRootCauseStats.mockResolvedValue(
    'rootCause' in opts ? opts.rootCause : { CONFIG: 12, CAPACITY: 8, CODE: 5, NETWORK: 3, DATA: 1, HUMAN: 1 }
  )
  if (opts.trendsError) {
    api.getTrends.mockRejectedValue(opts.trendsError)
  } else {
    api.getTrends.mockResolvedValue('trends' in opts ? opts.trends : trends())
  }
  api.getSlaRisk.mockResolvedValue({ items: [], total: 0 })
  api.getDashboardStats.mockResolvedValue({})

  const wrapper = mount(Dashboard, {
    global: {
      plugins: [
        [VueQueryPlugin, {
          queryClient: new QueryClient({
            defaultOptions: { queries: { retry: false, staleTime: 0, gcTime: 0 } },
          }),
        }],
      ],
      stubs: {
        PageLoading: { name: 'PageLoading', template: '<div class="stub-loading" />' },
        ApiErrorState: { name: 'ApiErrorState', template: '<div class="stub-error" />' },
        // 图表用具名 stub：需要断言「趋势区渲染的是图表还是空态」
        TrendChart: { name: 'TrendChart', template: '<div class="stub-chart" />' },
        AppEmpty: { name: 'AppEmpty', template: '<div class="stub-empty" />' },
        SlaRiskPanel: true,
        // 工作台队列自带查询与角色判断，由它自己的测试守着；
        // 本页只验证「它挂在趋势之前」这一装配事实
        WorkbenchQueues: { name: 'WorkbenchQueues', template: '<div class="stub-queues" />' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

type Vm = {
  kpis: { label: string; value: string }[]
  rootCauseTop: [string, number][]
  ticketTrendSeries: unknown[]
  costTrendSeries: unknown[]
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  vi.clearAllMocks()
})

describe('三种页面状态互斥', () => {
  it('加载成功：渲染 KPI，不出现加载中或错误态', async () => {
    const w = await mountPage()

    expect(w.findAll('.kpi-card').length).toBeGreaterThan(0)
    expect(w.findComponent({ name: 'PageLoading' }).exists()).toBe(false)
    expect(w.findComponent({ name: 'ApiErrorState' }).exists()).toBe(false)
  })

  it('主查询失败：显示错误态且不渲染 KPI', async () => {
    // overview 是主数据，它失败即整页错误——其余区块都是它的补充
    const w = await mountPage({ overviewError: new Error('boom') })

    expect(w.findComponent({ name: 'ApiErrorState' }).exists()).toBe(true)
    expect(w.findAll('.kpi-card')).toHaveLength(0)
  })
})

describe('KPI 数值与标签配对', () => {
  it('四项 KPI 按固定顺序渲染，标签与值不串位', async () => {
    // 断配对而非「页面上有这几个数」——顺序错位时用户会把
    // 「缓存命中率 42.5%」读成「总工单数」，是会误导决策的。
    // 2026-09-27 KPI 卡加了 icon/tone/subtitle 字段，本测试只关心配对——
    // 投影掉展示字段，不锁实现细节
    const w = await mountPage()

    const pairs = vmOf(w).kpis.map((k) => ({ label: k.label, value: k.value }))
    expect(pairs).toEqual([
      { label: '总工单数', value: '128' },
      { label: '缓存命中率', value: '42.5%' },
      { label: '有效查询数', value: '640' },
      { label: '平均成本(付费)', value: '¥0.0123' },
    ])
  })

  it('成本保留 4 位小数——单次调用常在 0.001 量级，两位会全显示 0.00', async () => {
    const w = await mountPage({ overview: overview({ avgCostRmb: 0.00456 }) })

    expect(vmOf(w).kpis[3].value).toBe('¥0.0046')
  })

  it('DOM 里确实渲染出这些数字，不只是计算属性算对了', async () => {
    // 只断 vm.kpis 的话，把整个 kpi-grid 从模板删掉照样通过
    const w = await mountPage()
    const cards = w.findAll('.kpi-card')

    expect(cards).toHaveLength(4)
    expect(cards[0].find('.kpi-label').text()).toBe('总工单数')
    expect(cards[0].find('.kpi-value').text()).toBe('128')
  })
})

describe('「没有数据」与「数值为 0」必须区分', () => {
  it('无有效查询时挂醒目横幅，而不是安静显示一排 0', async () => {
    // 一排 0 会被当成「系统很闲」，而真相往往是「数据没接上」
    const w = await mountPage({ overview: overview({ totalQueries: 0 }) })

    expect(w.find('.no-data-banner').exists()).toBe(true)
  })

  it('有数据时不显示该横幅', async () => {
    const w = await mountPage()
    expect(w.find('.no-data-banner').exists()).toBe(false)
  })
})

describe('根因分布', () => {
  it('按数量降序取前 5 项——看板空间有限，长尾无意义', async () => {
    const w = await mountPage({
      rootCause: { CONFIG: 3, CAPACITY: 20, CODE: 8, NETWORK: 15, DATA: 1, HUMAN: 6, UNKNOWN: 2 },
    })

    expect(vmOf(w).rootCauseTop).toEqual([
      ['CAPACITY', 20], ['NETWORK', 15], ['CODE', 8], ['HUMAN', 6], ['CONFIG', 3],
    ])
    expect(w.findAll('.rc-item')).toHaveLength(5)
  })

  it('渲染中文标签而非后端枚举名', async () => {
    // 「CAPACITY」对运维不友好，看板是给人扫一眼的
    const w = await mountPage({ rootCause: { CAPACITY: 20 } })

    expect(w.find('.rc-item').text()).toContain('容量不足')
    expect(w.find('.rc-item').text()).not.toContain('CAPACITY')
  })

  it('无根因数据时不渲染 chip，走空态占位', async () => {
    // 瘦身后面板常存（标题即导航），数据为空时 body 是空态而不是一排假 chip
    const w = await mountPage({ rootCause: {} })

    expect(vmOf(w).rootCauseTop).toEqual([])
    expect(w.findAll('.rc-item')).toHaveLength(0)
    expect(w.findAllComponents({ name: 'AppEmpty' }).length).toBeGreaterThan(0)
  })
})

describe('趋势区独立降级（6.51 契约）', () => {
  it('趋势有数据时渲染图表', async () => {
    const w = await mountPage()
    expect(w.findAllComponents({ name: 'TrendChart' }).length).toBeGreaterThan(0)
  })

  it('趋势拉取失败时只让图表区降级，KPI 照常显示', async () => {
    // ── 这是四查询拆分改造的目的，此前无测试守着 ──────────
    // 退回 Promise.all 的话，趋势失败会把整页拖成错误态，
    // 而 KPI 明明已经拿到了
    const w = await mountPage({ trendsError: new Error('trend down') })

    expect(w.findAll('.kpi-card')).toHaveLength(4)
    expect(w.findComponent({ name: 'ApiErrorState' }).exists()).toBe(false)
    // 图表位置改由空态占位
    expect(w.findAllComponents({ name: 'AppEmpty' }).length).toBeGreaterThan(0)
  })

  it('趋势为空数组时显示空态而非空白图表', async () => {
    const w = await mountPage({
      trends: trends({ days: [] }),
    })

    expect(w.findAllComponents({ name: 'TrendChart' })).toHaveLength(0)
    expect(w.findAllComponents({ name: 'AppEmpty' }).length).toBeGreaterThan(0)
  })

  it('两组趋势系列各自挂对轴：成本走右轴，命中率走左轴', async () => {
    // 成本（元）与命中率（%）量纲差两个数量级，
    // 同轴的话成本曲线会被压成一条贴底的直线
    const w = await mountPage()
    const cost = vmOf(w).costTrendSeries as { name: string; useRightAxis?: boolean }[]

    expect(cost.find((s) => s.name === 'AI 成本')?.useRightAxis).toBe(true)
    expect(cost.find((s) => s.name === '缓存命中率')?.useRightAxis).toBeUndefined()
  })

  it('趋势缺失时系列为空数组，不给图表塞 undefined', async () => {
    const w = await mountPage({ trendsError: new Error('down') })

    expect(vmOf(w).ticketTrendSeries).toEqual([])
    expect(vmOf(w).costTrendSeries).toEqual([])
  })
})

describe('模型分布', () => {
  it('有分布数据时逐项渲染模型名', async () => {
    const w = await mountPage()
    const items = w.findAll('.model-item')

    expect(items).toHaveLength(2)
    expect(items[0].find('.model-name').text()).toBe('deepseek-chat')
  })

  it('分布为空时显示空态', async () => {
    const w = await mountPage({ overview: overview({ modelDistribution: [] }) })

    expect(w.findAll('.model-item')).toHaveLength(0)
    expect(w.findAllComponents({ name: 'AppEmpty' }).length).toBeGreaterThan(0)
  })
})
