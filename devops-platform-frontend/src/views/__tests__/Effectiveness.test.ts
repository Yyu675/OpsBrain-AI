/**
 * 效能大盘 —— 渲染冒烟测试。
 *
 * 这一页的数全部影响「处置体系健不健康」的判断，画错不会崩、只会误导。
 * 断言重点：
 *   告警压缩比由两个独立来源（告警总数 ÷ 告警建单数）算出，分子分母缺一显示「—」；
 *   管道静默时状态条必须变红——那是「告警可能正在丢失」的信号；
 *   知识命中率来自证据方向统计，取不到方向行时显示「—」而不是 0%。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

const dashboardApi = vi.hoisted(() => ({
  getClosureMetrics: vi.fn(),
  getDiagnosisBoard: vi.fn(),
  getDocHealth: vi.fn(),
  getEffectivenessTrend: vi.fn(),
}))
vi.mock('@/api/dashboard', () => dashboardApi)

// AI 根因反馈统计（承接自数据概览诊断区）：query hook 经 dashboard.query 间接 import 此模块
const aiAnalysisApi = vi.hoisted(() => ({ fetchAiAnalysisStats: vi.fn() }))
vi.mock('@/api/ticketAiAnalysis', () => aiAnalysisApi)

const healingApi = vi.hoisted(() => ({ getHealingStats: vi.fn() }))
vi.mock('@/api/healing', () => healingApi)

const alertsApi = vi.hoisted(() => ({
  fetchAlerts: vi.fn(),
  fetchPipelineHeartbeat: vi.fn(),
  fetchLogsHeartbeat: vi.fn(),
  // 自愈观察窗面板（ObservationStatsPanel）：默认关闭态，不干扰大盘其余断言
  fetchObservationStats: vi.fn(),
}))
vi.mock('@/api/alerts', () => alertsApi)

vi.mock('@/api/knowledge', () => ({ deprecateKnowledgeDoc: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) } }))

vi.mock('@/utils/notify', () => ({
  notify: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(), clearCooldown: vi.fn() },
  handleServerError: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {}, params: {} }),
  RouterLink: { template: '<a><slot /></a>', props: ['to'] },
}))

import Effectiveness from '../Effectiveness.vue'

type Vm = { windowDays: number | null }
const vmOf = (w: { vm: unknown }) => w.vm as unknown as Vm

const mountPage = async () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const w = mount(Effectiveness, {
    global: {
      plugins: [VueQueryPlugin, qc],
      stubs: {
        // 诊断量趋势有数据时会渲染 TrendChart；jsdom 没有 canvas 实现，
        // 真渲染会让 echarts 抛错，桩掉——本文件守的是数据口径不是图形
        TrendChart: { name: 'TrendChart', template: '<div class="stub-chart" />' },
      },
    },
  })
  await flushPromises()
  return w
}

beforeEach(() => {
  vi.clearAllMocks()
  dashboardApi.getClosureMetrics.mockResolvedValue({
    total: 30, firstResponded: 10, mitigated: 5, rootCauseConfirmed: 4, verified: 3,
    verifySkipped: 0, mttaMinutes: 12.5, mttmMinutes: 40, mttrMinutes: 180, skipRate: 0,
    alertSourced: 10,
  })
  healingApi.getHealingStats.mockResolvedValue({
    total: 3, succeeded: 2, failed: 0, rejected: 1, pendingApproval: 0,
    undone: 0, undoFailed: 0, autoTotal: 2, autoClosedLoop: 2,
    verifyPass: 2, verifyFail: 0, verifyUnknown: 0, verifySkipped: 0, verifyPending: 0,
    autoClosedLoopRate: 1.0, verifyFailRate: 0.0,
  })
  alertsApi.fetchAlerts.mockResolvedValue({ alerts: [], total: 130, page: 1, size: 1, totalPages: 130 })
  alertsApi.fetchPipelineHeartbeat.mockResolvedValue({
    lastSeenAt: '2026-09-25T06:00:00', silent: false, silenceMinutes: 3,
  })
  alertsApi.fetchLogsHeartbeat.mockResolvedValue({
    freshestLogAt: '2026-09-25T06:01:00', silent: false, silenceMinutes: 10, probed: true, lokiEnabled: true,
  })
  // 观察窗面板默认开启态（本文件的断言面在别处；面板的细节行为由它自己的测试守着）
  alertsApi.fetchObservationStats.mockResolvedValue({
    enabled: true, windowMinutes: 10, levels: ['P2', 'P3'],
    observingNow: 2, selfHealed30d: 12, escalated30d: 4,
  })
  dashboardApi.getDiagnosisBoard.mockResolvedValue({
    windowDays: 7,
    sessions: { total: 10, byStatus: [], avgDurationSeconds: null, avgCostRmb: null,
      sufficiency: [{ sufficiency: 'SUFFICIENT', count: 4 }] },
    sessionTrend: { days: [], created: [], completed: [] },
    evidenceDirections: [
      { type: 'metrics', total: 10, success: 8, noData: 2, failed: 0, unavailable: 0, successRate: 0.8 },
      { type: 'knowledge', total: 10, success: 5, noData: 5, failed: 0, unavailable: 0, successRate: 0.5 },
    ],
    attentionTypes: [],
  })
  dashboardApi.getDocHealth.mockResolvedValue([])
  dashboardApi.getEffectivenessTrend.mockResolvedValue([])
  aiAnalysisApi.fetchAiAnalysisStats.mockResolvedValue({
    total: 120, rated: 60, helpful: 51, unhelpful: 9, helpfulRate: 0.85,
  })
})

describe('效能大盘', () => {
  it('告警压缩比 = 告警总数 ÷ 告警建单数，两位数源都算进来', async () => {
    const w = await mountPage()
    // 130 告警 / 10 告警建单 = 13.0:1
    expect(w.text()).toContain('13.0:1')
    expect(w.text()).toContain('告警压缩比')
  })

  it('管道静默时状态条变红并说明告警可能在丢', async () => {
    alertsApi.fetchPipelineHeartbeat.mockResolvedValue({ lastSeenAt: null, silent: true, silenceMinutes: 3 })
    const w = await mountPage()
    const card = w.find('.pipeline-card')
    expect(card.classes()).toContain('pipeline-silent')
    expect(card.text()).toContain('静默')
  })

  it('日志管道断流时单独标红，不误伤告警管道的状态', async () => {
    alertsApi.fetchLogsHeartbeat.mockResolvedValue({
      freshestLogAt: '2026-09-25T04:00:00', silent: true, silenceMinutes: 10, probed: true, lokiEnabled: true,
    })
    const w = await mountPage()
    const cards = w.findAll('.pipeline-card')
    expect(cards).toHaveLength(2)
    expect(cards[1].classes()).toContain('pipeline-silent')
    expect(cards[1].text()).toContain('日志管道')
    // 告警管道保持正常——两条管道独立呈现
    expect(cards[0].classes()).toContain('pipeline-ok')
  })

  it('日志源未启用时显示「未启用」，不误报成断流', async () => {
    alertsApi.fetchLogsHeartbeat.mockResolvedValue({
      freshestLogAt: null, silent: false, silenceMinutes: 10, probed: false, lokiEnabled: false,
    })
    const w = await mountPage()
    const cards = w.findAll('.pipeline-card')
    expect(cards[1].text()).toContain('未启用')
    expect(cards[1].classes()).not.toContain('pipeline-silent')
  })

  it('管道静默时给出排查手册入口，正常时隐藏——静默才需要人修', async () => {
    const w = await mountPage()
    // 正常态：不显示手册链接
    expect(w.find('.pipeline-handbook').exists()).toBe(false)
    // 静默态：链接出现
    alertsApi.fetchPipelineHeartbeat.mockResolvedValue({ lastSeenAt: null, silent: true, silenceMinutes: 3 })
    const w2 = await mountPage()
    expect(w2.find('.pipeline-handbook').exists()).toBe(true)
  })

  it('知识命中率取 evidenceDirections 的 knowledge 行', async () => {
    const w = await mountPage()
    expect(w.text()).toContain('50.0%')
  })

  it('拿不到数据时显示「—」而不是编一个 0', async () => {
    dashboardApi.getClosureMetrics.mockResolvedValue({
      total: 0, firstResponded: 0, mitigated: 0, rootCauseConfirmed: 0, verified: 0,
      verifySkipped: 0, mttaMinutes: null, mttmMinutes: null, mttrMinutes: null, skipRate: null,
      alertSourced: 0,
    })
    const w = await mountPage()
    // alertSourced=0 时压缩比无法计算，不得显示 0:1 这种假数字
    expect(w.text()).not.toContain('0:1')
  })

  it('复盘完成率：分母为 0 显示「—」，有数时显示百分比', async () => {
    dashboardApi.getClosureMetrics.mockResolvedValue({
      total: 30, firstResponded: 10, mitigated: 5, rootCauseConfirmed: 4, verified: 3,
      verifySkipped: 0, mttaMinutes: 12.5, mttmMinutes: 40, mttrMinutes: 180, skipRate: 0,
      alertSourced: 10, postmortemTotal: 6, finishedTotal: 20, postmortemRate: 30.0,
    })
    const w = await mountPage()
    expect(w.text()).toContain('复盘完成率')
    expect(w.text()).toContain('30.0%')
  })

  it('目标值标注随达标状态变化：达标标绿、未达标标警示', async () => {
    // 压缩比 60/10=6 < 10 → 未达标；知识命中率 50% ≥ 50% → 达标
    alertsApi.fetchAlerts.mockResolvedValue({ alerts: [], total: 60, page: 1, size: 1, totalPages: 60 })
    const w = await mountPage()
    expect(w.text()).toContain('目标 ≥10:1')
    expect(w.text()).toContain('目标 ≥50%')
    expect(w.find('.kpi-target.target-missed').exists()).toBe(true)
    expect(w.find('.kpi-target.target-met').exists()).toBe(true)
  })

  it('选时间窗后压缩比走同窗口径，不再用全时段告警总数', async () => {
    dashboardApi.getClosureMetrics.mockResolvedValue({
      total: 30, firstResponded: 10, mitigated: 5, rootCauseConfirmed: 4, verified: 3,
      verifySkipped: 0, mttaMinutes: 12.5, mttmMinutes: 40, mttrMinutes: 180, skipRate: 0,
      alertSourced: 10,
      // 近 7 天同窗：告警 20 / 建单 4 = 5.0:1（与全时段 130/10=13:1 不同）
      windowDays: 7, alertsInWindow: 20, alertSourcedInWindow: 4,
    })
    const w = await mountPage()
    vmOf(w).windowDays = 7
    await flushPromises()
    expect(w.text()).toContain('5.0:1')
  })
})

/** 按标签取 KPI 卡值——卡多了以后按位置取会随插入顺序漂移 */
const kpiValue = (w: Awaited<ReturnType<typeof mountPage>>, label: string) =>
  w.findAll('.kpi-card').find((c) => c.find('.kpi-label').text().includes(label))
    ?.find('.kpi-value').text()

/** 按标签取迷你 KPI 值（诊断会话与 AI 效果区） */
const miniKpiValue = (w: Awaited<ReturnType<typeof mountPage>>, label: string) =>
  w.findAll('.mini-kpi').find((c) => c.find('.mini-kpi-label').text().includes(label))
    ?.find('.mini-kpi-value').text()

/**
 * 以下两组守护 2026-09-26 从数据概览迁来的读数——
 * 迁移的语义是「换家不换口径」：null ≠ 0 的纪律、判定集空显示「—」
 * 这些约束一条都不能在搬运中丢失。
 */
describe('闭环阶段与 MTTA（承接自数据概览）', () => {
  it('按 total 折算百分比，四个阶段都渲染', async () => {
    // 夹具：total 30，已首响 10 → 33%
    const w = await mountPage()
    const rows = w.findAll('.stage-row')

    expect(rows).toHaveLength(4)
    expect(rows[0].text()).toContain('已首响')
    expect(rows[0].text()).toContain('10 / 30')
    expect(rows[0].text()).toContain('33%')
  })

  it('total 为 0 时整块不渲染，避免除零得出 NaN%', async () => {
    dashboardApi.getClosureMetrics.mockResolvedValue({
      total: 0, firstResponded: 0, mitigated: 0, rootCauseConfirmed: 0, verified: 0,
      verifySkipped: 0, mttaMinutes: null, mttmMinutes: null, mttrMinutes: null, skipRate: null,
      alertSourced: 0,
    })
    const w = await mountPage()

    expect(w.find('.stage-progress').exists()).toBe(false)
  })

  it('MTTA 为 null 显示「—」，绝不能显示 0m', async () => {
    // null = 还没有任何工单被首响过；0m = 秒级响应。
    // 两者含义完全相反，显示错了会让人以为系统表现极好
    dashboardApi.getClosureMetrics.mockResolvedValue({
      total: 30, firstResponded: 0, mitigated: 0, rootCauseConfirmed: 0, verified: 0,
      verifySkipped: 0, mttaMinutes: null, mttmMinutes: null, mttrMinutes: null, skipRate: null,
      alertSourced: 10,
    })
    const w = await mountPage()

    expect(kpiValue(w, 'MTTA 首响')).toBe('—')
  })

  it('MTTA 真的是 0 时显示 0m，不被当成缺失', async () => {
    // 反向验证：不能用 `if (!m)` 判空，那会把 0 一起吞掉
    dashboardApi.getClosureMetrics.mockResolvedValue({
      total: 30, firstResponded: 10, mitigated: 5, rootCauseConfirmed: 4, verified: 3,
      verifySkipped: 0, mttaMinutes: 0, mttmMinutes: 40, mttrMinutes: 180, skipRate: 0,
      alertSourced: 10,
    })
    const w = await mountPage()

    expect(kpiValue(w, 'MTTA 首响')).toBe('0m')
  })
})

describe('诊断会话与 AI 效果（承接自数据概览诊断区）', () => {
  it('渲染会话 KPI 与根因准确率（51/60 → 85.0%）', async () => {
    const w = await mountPage()

    expect(w.text()).toContain('诊断会话与 AI 效果')
    expect(miniKpiValue(w, '诊断会话')).toBe('10')
    expect(miniKpiValue(w, '根因准确率')).toBe('85.0%')
    // 均价/耗时为 null 时显示「—」而非 0
    expect(miniKpiValue(w, '平均耗时')).toBe('—')
    expect(miniKpiValue(w, '单次均价')).toBe('—')
  })

  it('rated=0 时准确率显示「—」+「暂无反馈」——「还没人评过分」不是「准确率 0%」', async () => {
    aiAnalysisApi.fetchAiAnalysisStats.mockResolvedValue({
      total: 120, rated: 0, helpful: 0, unhelpful: 0, helpfulRate: 0,
    })
    const w = await mountPage()

    expect(miniKpiValue(w, '根因准确率')).toBe('—')
    expect(miniKpiValue(w, '反馈 有用/已评分')).toBe('暂无反馈')
  })

  it('校准读数：ECE 与经验正确率按判定集显示（0.125 → 12.5%）', async () => {
    dashboardApi.getDiagnosisBoard.mockResolvedValue({
      windowDays: 7,
      sessions: { total: 10, byStatus: [], avgDurationSeconds: null, avgCostRmb: null, sufficiency: [] },
      sessionTrend: { days: [], created: [], completed: [] },
      evidenceDirections: [],
      attentionTypes: [],
      calibration: {
        ratedTotal: 4, helpful: 3, wrong: 1,
        excludedPartial: 2, excludedUnknown: 0, excludedInvalid: 0,
        ece: 0.125, empiricalAccuracy: 0.75, meanConfidence: 0.8, buckets: [],
      },
    })
    const w = await mountPage()

    expect(miniKpiValue(w, '校准误差 ECE')).toBe('12.5%')
    expect(miniKpiValue(w, '经验正确率')).toBe('75.0%')
  })

  it('判定集为空（ece=null）时校准读数显示「—」——「还没人反馈」不是「误差 0」', async () => {
    const w = await mountPage() // 默认夹具无 calibration 键

    expect(miniKpiValue(w, '校准误差 ECE')).toBe('—')
    expect(miniKpiValue(w, '经验正确率')).toBe('—')
  })

  it('诊断量趋势：days 非空渲染图表，为空收起不画空框', async () => {
    // 默认夹具 sessionTrend.days 为空 → 不渲染
    const w = await mountPage()
    expect(w.findAllComponents({ name: 'TrendChart' })).toHaveLength(0)

    dashboardApi.getDiagnosisBoard.mockResolvedValue({
      windowDays: 7,
      sessions: { total: 10, byStatus: [], avgDurationSeconds: null, avgCostRmb: null, sufficiency: [] },
      sessionTrend: { days: ['09-06', '09-07'], created: [3, 1], completed: [2, 1] },
      evidenceDirections: [],
      attentionTypes: [],
    })
    const w2 = await mountPage()
    expect(w2.findAllComponents({ name: 'TrendChart' }).length).toBeGreaterThan(0)
  })
})

