/**
 * AutomationPolicies —— **渲染冒烟测试**。
 *
 * ── 为什么在已有 27 例的情况下还要加这一组 ──────────────────
 * `AutomationPolicies.test.ts` 那 27 例覆盖得很扎实：匹配条件校验、
 * 演练模式二次确认、动作下拉过滤……但它们**全部打在 `vm.*` 上**，
 * 整个文件里 `find(` / `.text()` 出现 <b>0 次</b>。
 *
 * 也就是说：把 `<template>` 整段删掉，那 27 例照样全绿。
 * 这是本仓第四大文件（1663 行）、也是最后一个「零渲染断言」的大页面。
 *
 * ── 这一页画错的代价比别处大 ──────────────────────────────────
 * 它是**自动化的控制面**。别的页面画错顶多看不到信息，
 * 这一页画错会让人对着错误的状态做危险决定：
 *
 * <ul>
 *   <li><b>演练 / 真实执行标识画反</b>——用户以为策略还在「只记录」，
 *       实际它已经在真删 Pod。这是全站单点后果最重的一个标签；</li>
 *   <li><b>「已启用但不生效」提示不渲染</b>——界面说启用了、
 *       引擎永远不会执行它。用户会一直等一个不会发生的自动处置；</li>
 *   <li><b>顺序列错</b>——列表顺序即引擎求值顺序，这是回答
 *       「为什么是这条策略生效」的唯一依据；</li>
 *   <li><b>高风险徽章漏画</b>——`HIGH_RISK_EXECUTION` 的动作在列表上
 *       与普通动作长得一模一样。</li>
 * </ul>
 *
 * ── 分工 ──────────────────────────────────────────────────────
 * 那 27 例测「点了会发生什么」，本文件测「页面把状态画对了没有」。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  fetchPolicies: vi.fn(),
  fetchPolicyStats: vi.fn(),
  fetchActions: vi.fn(),
  fetchActionFilterOptions: vi.fn(),
  createPolicy: vi.fn(),
  updatePolicy: vi.fn(),
  togglePolicy: vi.fn(),
  togglePolicyDryRun: vi.fn(),
  deletePolicy: vi.fn(),
  simulatePolicies: vi.fn(),
}))
vi.mock('@/api/governance', () => api)

const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock },
  ElMessage: Object.assign(vi.fn(), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
}))

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

import AutomationPolicies from '../AutomationPolicies.vue'

type Row = {
  id: number
  name: string
  description: string | null
  matchAlertLevels: string | null
  matchModule: string | null
  matchServicePattern: string | null
  matchAlertNamePattern: string | null
  actionKey: string
  actionParams: string | null
  environment: string
  priority: number
  stopOnMatch: boolean
  cooldownMinutes: number | null
  maxExecutionsPerDay: number | null
  dryRun: boolean
  enabled: boolean
  version: number
  updatedBy: string | null
  updateTime: string | null
  actionDisplayName: string | null
  actionRiskLevel: string | null
  actionEnabled: boolean
  effective: boolean
  ineffectiveReason: string | null
}

const row = (over: Partial<Row> = {}): Row => ({
  id: 1,
  name: 'P3 Pod 崩溃自动重启',
  description: null,
  matchAlertLevels: 'P3',
  matchModule: 'K8S',
  matchServicePattern: '*',
  matchAlertNamePattern: 'PodCrashLoopBackOff',
  actionKey: 'k8s.pod.restart',
  actionParams: null,
  environment: 'staging',
  priority: 20,
  stopOnMatch: true,
  cooldownMinutes: 30,
  maxExecutionsPerDay: 10,
  dryRun: true,
  enabled: true,
  version: 0,
  updatedBy: null,
  updateTime: null,
  actionDisplayName: '优雅重启 Pod',
  actionRiskLevel: 'CONTROLLED_WRITE',
  actionEnabled: true,
  effective: true,
  ineffectiveReason: null,
  ...over,
})

const ACTION = {
  id: 1,
  actionKey: 'k8s.pod.restart',
  displayName: '优雅重启 Pod',
  description: null,
  category: 'k8s',
  riskLevel: 'CONTROLLED_WRITE' as const,
  targetPattern: 'ns:staging/*',
  environments: 'staging,dev',
  paramSchema: null,
  requiresApproval: null,
  maxBlastRadiusCount: null,
  enabled: true,
  version: 0,
  updatedBy: null,
  updateTime: null,
  effectiveRequiresApproval: true,
  effectiveBlastRadiusCount: 5,
}

let router: Router

const STATS = {
  total: 1, enabledCount: 1, dryRunCount: 1, liveCount: 0, prodLiveCount: 0,
}

const mountPage = async (
  rows: Row[] = [row()],
  /** 传 null 模拟「概览接口没返回」——注意不能在用例里单独打桩，
      会被本函数内的 mockResolvedValue 覆盖掉（第一版就踩了这个） */
  stats: Partial<typeof STATS> | null = {},
) => {
  api.fetchPolicies.mockResolvedValue({
    items: rows, total: rows.length, page: 1, size: 20, totalPages: 1,
  })
  api.fetchPolicyStats.mockResolvedValue(
    stats === null ? null : { ...STATS, total: rows.length, ...stats }
  )
  api.fetchActions.mockResolvedValue({
    items: [ACTION], total: 1, page: 1, size: 200, totalPages: 1,
  })
  api.fetchActionFilterOptions.mockResolvedValue({
    categories: ['k8s'],
    riskLevels: [{ value: 'CONTROLLED_WRITE', label: '受控写操作', description: '' }],
    environments: ['prod', 'staging', 'dev'],
    knownCategories: ['k8s'],
  })

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/automation/policies', component: AutomationPolicies },
    ],
  })
  await router.push('/automation/policies')
  await router.isReady()

  const wrapper = mount(AutomationPolicies, {
    global: {
      plugins: [router],
      stubs: {
        // 表单/预演弹窗都用 el-dialog 包着，不透传插槽就断言不到里面的内容
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
        ServerPagination: true,
        // DataStateBoundary 必须透传默认插槽——表格整个在它里面。
        // 用 `true` 会把表格吞掉，所有行断言都会以「找不到元素」失败，
        // 而真实原因与产品代码无关
        DataStateBoundary: { template: '<div><slot /></div>' },
      },
    },
  })
  // 策略列表与概览是两个独立请求。只等 fetchPolicies 会让概览断言
  // 在 stats 尚未落定时执行——表现为「概览条渲染了但数字还是旧的」，
  // 失败信息指向断言本身，与真实原因隔得很远
  await vi.waitFor(() => expect(api.fetchPolicies).toHaveBeenCalled())
  await vi.waitFor(() => expect(api.fetchPolicyStats).toHaveBeenCalled())
  await flushPromises()
  return wrapper
}

const rowsOf = (w: VueWrapper) => w.findAll('.policy-table tbody tr')

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
})

describe('页面骨架', () => {
  it('标题、概览条、筛选栏、求值顺序说明都在', async () => {
    const w = await mountPage()

    expect(w.find('.page-title').text()).toBe('自动化策略')
    expect(w.find('.stat-strip').exists()).toBe(true)
    expect(w.find('.filter-bar').exists()).toBe(true)
    // 这句不是装饰：它是用户理解「为什么是这条策略生效」的唯一说明
    expect(w.find('.order-hint').text()).toContain('列表顺序即引擎求值顺序')
  })

  it('概览四项各自渲染自己的数字，不串位', async () => {
    // 断具体配对而非「页面上有这四个数」——四个 stat-badge 顺序写错时，
    // 用户会把「生产环境生效 3」读成「总策略 3」，是危险方向的误读
    const w = await mountPage([row()], {
      total: 12, dryRunCount: 7, liveCount: 5, prodLiveCount: 2,
    })
    const badges = w.findAll('.stat-badge')

    expect(badges).toHaveLength(4)
    const pairs = badges.map((b) => [b.find('.stat-label').text(), b.find('.stat-value').text()])
    expect(pairs).toEqual([
      ['总策略', '12'],
      ['演练中', '7'],
      ['真实执行', '5'],
      ['生产环境生效', '2'],
    ])
  })

  it('有真实执行 / 生产生效的策略时，对应概览项标红', async () => {
    const safe = await mountPage([row()], { liveCount: 0, prodLiveCount: 0 })
    expect(safe.findAll('.stat-badge').filter((b) => b.classes().includes('is-danger'))).toHaveLength(0)

    const risky = await mountPage([row()], { liveCount: 3, prodLiveCount: 1 })
    const danger = risky.findAll('.stat-badge').filter((b) => b.classes().includes('is-danger'))
    expect(danger.map((b) => b.find('.stat-label').text())).toEqual(['真实执行', '生产环境生效'])
  })

  it('stats 未返回时整条概览不渲染，不显示一排 0 误导用户', async () => {
    const w = await mountPage([row()], null)
    expect(w.find('.stat-strip').exists()).toBe(false)
  })
})

describe('列表：每一行把策略状态画对', () => {
  it('顺序、名称、环境、动作都渲染出来', async () => {
    const w = await mountPage()
    const tr = rowsOf(w)[0]

    expect(tr.find('.pri-num').text()).toBe('20')
    expect(tr.find('.policy-name').text()).toBe('P3 Pod 崩溃自动重启')
    expect(tr.find('.env-chip').text()).toBe('staging')
    expect(tr.find('.action-name').text()).toBe('优雅重启 Pod')
    // 展示名之外还要给出 actionKey：排查时人看的是 key，不是中文名
    expect(tr.find('.action-key').text()).toBe('k8s.pod.restart')
  })

  it('多行按后端给的顺序原样渲染——列表顺序即求值顺序', async () => {
    // 前端若擅自排序（比如按 id 或名称），用户就无法回答
    // 「为什么是这条策略生效」。这里故意让 priority 与数组顺序一致，
    // 但用一个 id 倒序的数组，能抓到「按 id 排」这类改动
    const w = await mountPage([
      row({ id: 9, priority: 10, name: '先求值' }),
      row({ id: 3, priority: 20, name: '后求值' }),
      row({ id: 7, priority: 30, name: '最后求值' }),
    ])

    expect(rowsOf(w).map((r) => r.find('.policy-name').text()))
      .toEqual(['先求值', '后求值', '最后求值'])
    expect(rowsOf(w).map((r) => r.find('.pri-num').text()))
      .toEqual(['10', '20', '30'])
  })

  it('演练 / 真实执行标识与 dryRun 严格对应', async () => {
    // 全站单点后果最重的一个标签：画反了，用户以为策略只在记录，
    // 实际它已经在真删 Pod
    const w = await mountPage([
      row({ id: 1, dryRun: true }),
      row({ id: 2, dryRun: false }),
    ])
    const tags = rowsOf(w).map((r) => r.find('.mode-tag'))

    expect(tags[0].text()).toBe('演练')
    expect(tags[0].classes()).toContain('is-dry')
    expect(tags[1].text()).toBe('真实执行')
    expect(tags[1].classes()).toContain('is-live')
  })

  it('启用 / 停用状态点与文案对应，停用行整行置灰', async () => {
    const w = await mountPage([
      row({ id: 1, enabled: true }),
      row({ id: 2, enabled: false }),
    ])
    const trs = rowsOf(w)

    expect(trs[0].find('.dot-tag').text()).toBe('已启用')
    expect(trs[0].find('.dot-tag').classes()).toContain('is-on')
    expect(trs[0].classes()).not.toContain('is-disabled-row')

    expect(trs[1].find('.dot-tag').text()).toBe('已停用')
    expect(trs[1].find('.dot-tag').classes()).toContain('is-off')
    expect(trs[1].classes()).toContain('is-disabled-row')
  })

  it('「已启用但不生效」显式提示，并把原因挂在 title 上', async () => {
    // 界面说启用了、引擎永远不会执行它——不提示的话，
    // 用户会一直等一个不会发生的自动处置
    const w = await mountPage([
      row({ id: 1, enabled: true, effective: false, ineffectiveReason: '引用的动作已停用' }),
    ])
    const hint = rowsOf(w)[0].find('.ineffective')

    expect(hint.exists()).toBe(true)
    expect(hint.text()).toContain('不生效')
    expect(hint.attributes('title')).toBe('引用的动作已停用')
  })

  it('已停用的策略不重复提示「不生效」——那是废话', async () => {
    // 停用本来就不会执行，再挂一个「不生效」只会稀释真正需要注意的那种
    const w = await mountPage([
      row({ id: 1, enabled: false, effective: false, ineffectiveReason: '动作已停用' }),
    ])
    expect(rowsOf(w)[0].find('.ineffective').exists()).toBe(false)
  })

  it('高风险动作挂徽章，普通动作不挂', async () => {
    const w = await mountPage([
      row({ id: 1, actionRiskLevel: 'HIGH_RISK_EXECUTION' }),
      row({ id: 2, actionRiskLevel: 'CONTROLLED_WRITE' }),
      row({ id: 3, actionRiskLevel: null }),
    ])

    expect(rowsOf(w).map((r) => r.find('.risk-badge').exists()))
      .toEqual([true, false, false])
  })

  it('「命中即停」只标在 stopOnMatch 的行上', async () => {
    const w = await mountPage([
      row({ id: 1, stopOnMatch: true }),
      row({ id: 2, stopOnMatch: false }),
    ])
    expect(rowsOf(w).map((r) => r.find('.stop-tag').exists())).toEqual([true, false])
  })

  it('prod 环境的 chip 额外标红——生产是最需要一眼认出的环境', async () => {
    const w = await mountPage([
      row({ id: 1, environment: 'prod' }),
      row({ id: 2, environment: 'staging' }),
    ])
    expect(rowsOf(w).map((r) => r.find('.env-chip').classes().includes('is-prod')))
      .toEqual([true, false])
  })

  it('告警级别逐个渲染成 chip，P0 / P1 额外标危', async () => {
    const w = await mountPage([row({ matchAlertLevels: 'P0,P2,P1' })])
    const chips = rowsOf(w)[0].findAll('.level-chip')

    expect(chips.map((c) => c.text())).toEqual(['P0', 'P2', 'P1'])
    expect(chips.map((c) => c.classes().includes('is-critical')))
      .toEqual([true, false, true])
  })

  it('没有级别限制时整块 chip 区不渲染', async () => {
    const w = await mountPage([row({ matchAlertLevels: null })])
    expect(rowsOf(w)[0].find('.level-row').exists()).toBe(false)
  })

  it('匹配条件汇成一句人话；全通配时明说「无限制」', async () => {
    const w = await mountPage([
      row({
        id: 1,
        matchAlertLevels: 'P3', matchModule: 'K8S',
        matchServicePattern: 'order-*', matchAlertNamePattern: '*',
      }),
      row({
        id: 2,
        matchAlertLevels: null, matchModule: null,
        matchServicePattern: '*', matchAlertNamePattern: '*',
      }),
    ])
    const texts = rowsOf(w).map((r) => r.find('.match-text').text())

    expect(texts[0]).toBe('P3 · K8S · 服务 order-*')
    // `*` 等价于无限制——这一行会对所有告警执行动作，必须说清楚
    expect(texts[1]).toBe('（无限制）')
  })

  it('操作按钮文案随行状态翻转：停用/启用、上线/转演练', async () => {
    const w = await mountPage([
      row({ id: 1, enabled: true, dryRun: true }),
      row({ id: 2, enabled: false, dryRun: false }),
    ])
    const btnText = (i: number, j: number) =>
      rowsOf(w)[i].findAll('.ops button')[j].text()

    expect(btnText(0, 0)).toBe('停用')
    expect(btnText(0, 1)).toBe('上线')
    expect(btnText(1, 0)).toBe('启用')
    expect(btnText(1, 1)).toBe('转演练')
  })

  it('演练按钮的 title 说清后果——「上线」二字本身不含警示', async () => {
    const w = await mountPage([
      row({ id: 1, dryRun: true }),
      row({ id: 2, dryRun: false }),
    ])
    const titleOf = (i: number) => rowsOf(w)[i].findAll('.ops button')[1].attributes('title')

    expect(titleOf(0)).toBe('关闭演练，策略将真实执行')
    expect(titleOf(1)).toBe('切回演练模式')
  })
})

describe('空列表', () => {
  it('没有策略时不渲染任何数据行', async () => {
    const w = await mountPage([])
    expect(rowsOf(w)).toHaveLength(0)
  })
})
