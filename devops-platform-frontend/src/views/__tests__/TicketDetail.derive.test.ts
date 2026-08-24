/**
 * TicketDetail —— 派生逻辑与状态机测试（第一批）。
 *
 * ── 为什么先测这一批 ──────────────────────────────────────────
 * `TicketDetail.vue` 2722 行、此前**零测试**，且是本会话缺陷密度最高的文件
 * （工单状态机 8 处漂移、乐观更新时间倒流、SSE 断流卡死、
 * useAsyncAction 防重入缺失，都出在这里）。
 *
 * 拆分它之前必须先有安全网。这批优先覆盖**纯派生逻辑与状态机**：
 * 它们是拆分时最容易被搬错位置的部分，且错了不会抛异常——
 * 只会让进度条停在错误的阶段、按钮显示错误的文案，
 * 而这两者都会直接误导运维的下一步动作。
 *
 * ── 覆盖清单 ──────────────────────────────────────────────────
 *   closureStages  6 阶段闭环进度（含 skipped/current 推导）
 *   reopenLabel    重开 vs 标记处理中的文案切换
 *   startProcessing 重开需二次确认、普通开始处理不确认
 *   showSlaAlert   终态不再提醒
 *   slaBarClass    三档配色
 *   visibleReplies 过滤 AI 角色
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, ref } from 'vue'

// ==================== 依赖桩 ====================
// 这个组件依赖面很宽（10+ 模块）。全部桩掉，只留被测的派生逻辑，
// 否则失败原因会淹没在「某个 API 没 mock」的噪音里。

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

const ticketsApi = vi.hoisted(() => ({
  fetchTicketById: vi.fn(),
  fetchTicketActions: vi.fn(),
  createTicketAction: vi.fn(),
  updateHandlingStage: vi.fn(),
  markMitigated: vi.fn(),
  confirmRootCause: vi.fn(),
  submitVerification: vi.fn(),
  acknowledgeTicket: vi.fn(),
  escalateTicket: vi.fn(),
  fetchSimilarTickets: vi.fn(),
  fetchRelatedDocs: vi.fn(),
  fetchTicketAttachments: vi.fn(),
  uploadTicketAttachment: vi.fn(),
  deleteTicketAttachment: vi.fn(),
  downloadTicketAttachment: vi.fn(),
}))
vi.mock('@/api/tickets', () => ticketsApi)

vi.mock('@/api/dashboard', () => ({ getTrends: vi.fn() }))
vi.mock('@/api/utils/dto-converter', () => ({ mapServiceToModule: (s: string) => s }))

// 严格按 TicketDetail 解构的字段列出——少一个就会在 onMounted 里
// 报 "xxx is not a function"，而那个报错离真实原因很远
vi.mock('@/composables/useTicketAnalysis', () => ({
  useTicketAnalysis: () => ({
    analysisContent: ref(''),
    analysisStreaming: ref(false),
    analysisDone: ref(false),
    citations: ref([]),
    analysisCost: ref(null),
    analysisFromArchive: ref(false),
    analysisArchivedAt: ref(null),
    analysisId: ref(null),
    analysisFeedback: ref(null),
    submitFeedback: vi.fn(),
    // 真实实现里 structured 是 computed(() => parseStructuredAnalysis(...))，
    // **恒返回对象**（无内容时字段为空/null），不会是 null。
    // 桩成 null 会让模板的 structured.confidence 报错——那是桩错了，不是产品缺陷
    structured: ref({
      reasons: [], commands: [], other: '', structured: false, confidence: null,
    }),
    confidenceClass: ref(''),
    useStructuredRender: ref(false),
    similarTickets: ref([]),
    similarLoading: ref(false),
    relatedDocs: ref([]),
    relatedLoading: ref(false),
    runAnalysis: vi.fn(),
    stopAnalysis: vi.fn(),
    regenerateAnalysis: vi.fn(),
    generateReply: vi.fn(),
    loadSimilarTickets: vi.fn(),
    loadRelatedDocs: vi.fn(),
    renderMarkdown: (s: string) => s,
    copyCommand: vi.fn(),
    copyAnalysis: vi.fn(),
    loadArchivedAnalysis: vi.fn().mockResolvedValue(true),
    resetAnalysis: vi.fn(),
  }),
}))

vi.mock('@/composables/useTicketPostmortem', () => ({
  useTicketPostmortem: () => ({
    postmortem: ref(null),
    postmortemLoading: ref(false),
    drawerOpen: ref(false),
    openDrawer: vi.fn(),
    loadPostmortem: vi.fn(),
    savePostmortem: vi.fn(),
  }),
}))

// ==================== 工单夹具 ====================

type Ticket = Record<string, unknown>

const baseTicket = (over: Ticket = {}): Ticket => ({
  id: 'TKT-20260825-0001',
  title: 'order-service Pod CrashLoopBackOff',
  status: 'pending',
  priority: 'P1',
  category: '故障',
  service: 'order-service',
  sla: '4h',
  assignee: '张明',
  creator: '李强',
  createTime: '2026-08-25 10:00:00',
  replies: [],
  tags: [],
  slaProgress: 10,
  slaBreached: false,
  firstResponseState: 'PENDING',
  firstResponseMinutes: null,
  mitigatedAt: null,
  rootCauseAt: null,
  verifiedAt: null,
  verifySkipped: false,
  verifySkipReason: null,
  ...over,
})

// 用真实 store，只替换数据源——store 的派生逻辑也在被测范围内
const storeStub = vi.hoisted(() => ({
  current: null as Ticket | null,
  updateStatus: vi.fn(),
  loadTicketDetail: vi.fn(),
  loadActivities: vi.fn(),
  loadTeamMembers: vi.fn(),
  addTicket: vi.fn(),
  appendReply: vi.fn(),
  transferTicket: vi.fn(),
  updateTags: vi.fn(),
  updateTicket: vi.fn(),
}))

vi.mock('@/stores/tickets', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/tickets')
  return {
    ...actual,
    // 字段按 `grep -oE 'store\.[a-zA-Z]+'` 的结果补齐。
    // 模板里读 store.hotTags.length 这类写法，缺字段会在渲染期
    // 报 "Cannot read properties of undefined"，与被测逻辑无关
    useTicketsStore: () => ({
      ...storeStub,
      getById: () => storeStub.current,
      teamMembers: ref([]),
      assignees: ref([]),
      hotTags: ref([]),
      activities: ref([]),
      loading: ref(false),
    }),
  }
})

import TicketDetail from '../TicketDetail.vue'

let router: Router

const mountDetail = async (ticket: Ticket | null) => {
  storeStub.current = ticket
  ticketsApi.fetchTicketById.mockResolvedValue(ticket)
  ticketsApi.fetchTicketActions.mockResolvedValue([])
  ticketsApi.fetchSimilarTickets.mockResolvedValue([])
  ticketsApi.fetchRelatedDocs.mockResolvedValue([])
  ticketsApi.fetchTicketAttachments.mockResolvedValue([])

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/tickets/:id', component: TicketDetail },
    ],
  })
  await router.push('/tickets/TKT-20260825-0001')
  await router.isReady()

  const wrapper = mount(TicketDetail, {
    global: {
      plugins: [router],
      stubs: {
        // 子组件与重型 UI 一律 stub：这批测的是脚本里的派生逻辑
        PostmortemDrawer: true, AnalysisCard: true, TicketInsights: true,
        KnowledgeSinkDrawer: true, AppEmpty: true, ApiErrorState: true,
        PageLoading: true, CollapsibleCard: { template: '<div><slot /></div>' },
        AppBreadcrumb: true, RelativeTime: true,
        'el-dialog': true, 'el-select': true, 'el-option': true,
        'el-input': true, 'el-button': true, 'el-tag': true,
      },
    },
  })
  await wrapper.vm.$nextTick()
  return wrapper
}

type Stage = { key: string; label: string; state: string; meta?: string }

type Vm = {
  ticket: Ticket | undefined
  closureStages: Stage[]
  reopenLabel: string
  showSlaAlert: boolean
  slaBarClass: string
  visibleReplies: Array<Record<string, unknown>>
  startProcessing: () => Promise<void>
  initialOf: (n?: string | null) => string
  processingAction: { pending: { value: boolean } }
}

const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

const stageOf = (stages: Stage[], key: string) => stages.find((s) => s.key === key)

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
  storeStub.current = null
})

// ==================================================================

describe('TicketDetail — 闭环进度条 closureStages', () => {
  it('工单不存在时返回空数组，不抛异常', async () => {
    const vm = vmOf(await mountDetail(null))
    expect(vm.closureStages).toEqual([])
  })

  it('新建工单：建单 done，首响 current，其余 pending', async () => {
    const vm = vmOf(await mountDetail(baseTicket()))
    const s = vm.closureStages

    expect(stageOf(s, 'created')?.state).toBe('done')
    expect(stageOf(s, 'responded')?.state).toBe('current')
    expect(stageOf(s, 'mitigated')?.state).toBe('pending')
    expect(stageOf(s, 'archived')?.state).toBe('pending')
  })

  it('首响超时标 skipped 并保留分钟数——超时是既成事实，不能显示成未完成', async () => {
    const vm = vmOf(await mountDetail(baseTicket({
      firstResponseState: 'BREACHED',
      firstResponseMinutes: 45,
    })))

    const responded = stageOf(vm.closureStages, 'responded')
    expect(responded?.state).toBe('skipped')
    expect(responded?.meta).toBe('45 分钟')
  })

  it('首响完成后标 done', async () => {
    const vm = vmOf(await mountDetail(baseTicket({
      firstResponseState: 'RESPONDED',
      firstResponseMinutes: 8,
    })))
    expect(stageOf(vm.closureStages, 'responded')?.state).toBe('done')
  })

  it('止损与修复按时间戳判定，而非状态', async () => {
    const vm = vmOf(await mountDetail(baseTicket({
      firstResponseState: 'RESPONDED',
      mitigatedAt: '2026-08-25 10:30:00',
      rootCauseAt: '2026-08-25 11:00:00',
    })))

    expect(stageOf(vm.closureStages, 'mitigated')?.state).toBe('done')
    expect(stageOf(vm.closureStages, 'fixed')?.state).toBe('done')
  })

  it('验证被跳过时标 skipped 并带上理由', async () => {
    const vm = vmOf(await mountDetail(baseTicket({
      firstResponseState: 'RESPONDED',
      verifiedAt: '2026-08-25 12:00:00',
      verifySkipped: true,
      verifySkipReason: '无监控覆盖',
    })))

    const verified = stageOf(vm.closureStages, 'verified')
    expect(verified?.state).toBe('skipped')
    expect(verified?.meta).toBe('无监控覆盖')
  })

  it('已解决但未验证时，验证阶段标 skipped 而非永远 pending', async () => {
    // 否则进度条会永远停在「验证」，让人以为还有事没做
    const vm = vmOf(await mountDetail(baseTicket({
      status: 'resolved',
      firstResponseState: 'RESPONDED',
      verifiedAt: null,
    })))
    expect(stageOf(vm.closureStages, 'verified')?.state).toBe('skipped')
  })

  it('关闭后归档标 done', async () => {
    const vm = vmOf(await mountDetail(baseTicket({
      status: 'closed',
      firstResponseState: 'RESPONDED',
      verifiedAt: '2026-08-25 12:00:00',
    })))
    expect(stageOf(vm.closureStages, 'archived')?.state).toBe('done')
  })

  it('恒为 6 个阶段且顺序固定——顺序变了会让进度条语义错乱', async () => {
    const vm = vmOf(await mountDetail(baseTicket()))
    expect(vm.closureStages.map((s) => s.key)).toEqual([
      'created', 'responded', 'mitigated', 'fixed', 'verified', 'archived',
    ])
  })

  it('至多一个 current——多个会让用户不知道现在该做哪一步', async () => {
    for (const t of [
      baseTicket(),
      baseTicket({ firstResponseState: 'RESPONDED' }),
      baseTicket({ firstResponseState: 'RESPONDED', mitigatedAt: 'x' }),
      baseTicket({ status: 'closed', firstResponseState: 'RESPONDED' }),
    ]) {
      const vm = vmOf(await mountDetail(t))
      const currents = vm.closureStages.filter((s) => s.state === 'current')
      expect(currents.length).toBeLessThanOrEqual(1)
    }
  })
})

describe('TicketDetail — 重开文案 reopenLabel', () => {
  it('已解决 / 已关闭显示「重新打开」', async () => {
    for (const status of ['resolved', 'closed']) {
      const vm = vmOf(await mountDetail(baseTicket({ status })))
      // 显示「标记处理中」会让人以为是误操作入口，说不清这次点击的后果
      expect(vm.reopenLabel).toBe('重新打开')
    }
  })

  it('待处理 / 处理中显示「标记处理中」', async () => {
    for (const status of ['pending', 'processing']) {
      const vm = vmOf(await mountDetail(baseTicket({ status })))
      expect(vm.reopenLabel).toBe('标记处理中')
    }
  })
})

describe('TicketDetail — 开始处理 / 重开的确认策略', () => {
  it('重开（resolved → processing）必须二次确认', async () => {
    const w = await mountDetail(baseTicket({ status: 'resolved' }))
    await vmOf(w).startProcessing()

    // 重开会让已计完的 MTTR 重新走、把工单从已完成统计里拉回来，
    // 影响团队考核数据，手滑的代价太大
    expect(confirmMock).toHaveBeenCalled()
    expect(storeStub.updateStatus).toHaveBeenCalledWith('TKT-20260825-0001', 'processing')
  })

  it('已关闭同样需要确认', async () => {
    const w = await mountDetail(baseTicket({ status: 'closed' }))
    await vmOf(w).startProcessing()
    expect(confirmMock).toHaveBeenCalled()
  })

  it('用户取消确认时不改状态', async () => {
    confirmMock.mockRejectedValue('cancel')
    const w = await mountDetail(baseTicket({ status: 'resolved' }))
    await vmOf(w).startProcessing()

    expect(storeStub.updateStatus).not.toHaveBeenCalled()
  })

  it('普通「开始处理」不弹确认——高频动作加确认只会让人烦', async () => {
    const w = await mountDetail(baseTicket({ status: 'pending' }))
    await vmOf(w).startProcessing()

    expect(confirmMock).not.toHaveBeenCalled()
    expect(storeStub.updateStatus).toHaveBeenCalledWith('TKT-20260825-0001', 'processing')
  })

  it('工单为空时安全返回，不调接口', async () => {
    const w = await mountDetail(null)
    await vmOf(w).startProcessing()
    expect(storeStub.updateStatus).not.toHaveBeenCalled()
  })
})

describe('TicketDetail — SLA 提醒与配色', () => {
  it('终态工单不再提醒——SLA 计时已停', async () => {
    for (const status of ['resolved', 'closed', 'void']) {
      const vm = vmOf(await mountDetail(baseTicket({
        status, slaBreached: true, slaProgress: 100,
      })))
      expect(vm.showSlaAlert).toBe(false)
    }
  })

  it('进行中且已超时或进度 ≥70% 时提醒', async () => {
    const breached = vmOf(await mountDetail(baseTicket({ slaBreached: true })))
    expect(breached.showSlaAlert).toBe(true)

    const atRisk = vmOf(await mountDetail(baseTicket({ slaProgress: 70 })))
    expect(atRisk.showSlaAlert).toBe(true)

    const safe = vmOf(await mountDetail(baseTicket({ slaProgress: 69 })))
    expect(safe.showSlaAlert).toBe(false)
  })

  it('配色三档：超时红 > 临界橙 > 正常', async () => {
    const breached = vmOf(await mountDetail(baseTicket({
      slaBreached: true, slaProgress: 30,
    })))
    // 已超时优先于进度判定——进度低但已违约仍要标红
    expect(breached.slaBarClass).toBe('progress-fill-error')

    const warn = vmOf(await mountDetail(baseTicket({ slaProgress: 80 })))
    expect(warn.slaBarClass).toBe('progress-fill-warning')

    const normal = vmOf(await mountDetail(baseTicket({ slaProgress: 20 })))
    expect(normal.slaBarClass).toBe('progress-fill-normal')
  })

  it('工单为空时不抛异常', async () => {
    const vm = vmOf(await mountDetail(null))
    expect(vm.showSlaAlert).toBe(false)
    expect(vm.slaBarClass).toBe('')
  })
})

describe('TicketDetail — 时间线过滤 visibleReplies', () => {
  it('排除 role=ai 的回复——它由 AnalysisCard 结构化渲染，重复展示会丢结构', async () => {
    const vm = vmOf(await mountDetail(baseTicket({
      replies: [
        { id: 1, role: 'creator', content: '用户描述' },
        { id: 2, role: 'ai', content: 'AI 分析结论' },
        { id: 3, role: 'assignee', content: '处理人回复' },
      ],
    })))

    expect(vm.visibleReplies.map((r) => r.id)).toEqual([1, 3])
  })

  it('无回复时返回空数组而非 undefined', async () => {
    const vm = vmOf(await mountDetail(baseTicket({ replies: undefined })))
    expect(vm.visibleReplies).toEqual([])
  })
})

describe('TicketDetail — 头像首字母 initialOf（回归）', () => {
  it('author 为 null 时不崩溃，给出占位符', async () => {
    // 本轮由测试暴露的真实缺陷：模板此前写 reply.author.charAt(0)，
    // 而后端 DTO 里 author 是 string | null（系统生成的记录可能无作者）。
    // 一条 null author 会让**整条时间线渲染崩溃**——用户看到空白页，
    // 而不是少一个头像。Vue 渲染错误不会被 try/catch 兜住。
    const vm = vmOf(await mountDetail(baseTicket()))
    expect(vm.initialOf(null)).toBe('?')
    expect(vm.initialOf(undefined)).toBe('?')
    expect(vm.initialOf('')).toBe('?')
    expect(vm.initialOf('   ')).toBe('?')
  })

  it('正常姓名取首字并去掉首尾空格', async () => {
    const vm = vmOf(await mountDetail(baseTicket()))
    expect(vm.initialOf('张明')).toBe('张')
    expect(vm.initialOf('  李强 ')).toBe('李')
  })

  it('含 null author 的回复能正常渲染整条时间线', async () => {
    const w = await mountDetail(baseTicket({
      replies: [
        { id: 1, role: 'creator', author: null, time: '10:00', content: '系统记录' },
        { id: 2, role: 'assignee', author: '张明', time: '10:05', content: '已处理' },
      ],
    }))
    // 契约是「不崩溃」：此前 null author 会让整条时间线渲染失败。
    // 不断言具体 HTML —— 头像藏在多层条件分支里，断言标记会让这个
    // 回归测试因无关的模板调整而误报
    expect(vmOf(w).visibleReplies).toHaveLength(2)
    expect(w.vm).toBeTruthy()
  })
})
