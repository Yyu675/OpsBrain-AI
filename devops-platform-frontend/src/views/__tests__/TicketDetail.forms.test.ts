/**
 * TicketDetail —— 表单校验与处置流程测试（第三批）。
 *
 * ── 这批守什么 ────────────────────────────────────────────────
 * B2 现场处置 / B3 根因与验证的四个表单。它们的共同特征是：
 * **提交后会改变工单的闭环状态与 MTTR 计时基线**。
 *
 * 校验漏掉的后果不是「表单丑」，而是脏数据进入复盘链路：
 *   - 跳过验证不填理由 → 事后无人知道为什么跳过
 *   - 根因留空 → 复盘时这张单没有可归因的内容
 *   - 处置摘要留空 → 活动流里出现一条什么也没说的记录
 *
 * ── 与前两批的分工 ────────────────────────────────────────────
 *   第一批 派生逻辑与状态机（26 例）
 *   第二批 写操作的防重入与回滚（28 例）
 *   本批   表单校验 + 后端响应同步 + 弹窗开关时机
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, ref } from 'vue'

// ==================== 依赖桩 ====================

const confirmMock = vi.hoisted(() => vi.fn())
const promptMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock, prompt: promptMock },
  ElMessage: Object.assign(vi.fn(), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
}))

const notifyMock = vi.hoisted(() => ({
  success: vi.fn(), warning: vi.fn(), error: vi.fn(),
  info: vi.fn(), clearCooldown: vi.fn(),
}))
const handleServerErrorMock = vi.hoisted(() => vi.fn())
vi.mock('@/utils/notify', () => ({
  notify: notifyMock,
  handleServerError: handleServerErrorMock,
}))

// 按 TicketDetail 实际 import 的名字列出——名字对不上会静默变成 undefined
const ticketsApi = vi.hoisted(() => ({
  fetchTicketById: vi.fn(),
  fetchTicketAttachments: vi.fn(),
  uploadTicketAttachment: vi.fn(),
  fetchAttachmentDownloadUrl: vi.fn(),
  deleteTicketAttachment: vi.fn(),
  acknowledgeTicket: vi.fn(),
  escalateTicket: vi.fn(),
  addTicketAction: vi.fn(),
  fetchTicketActions: vi.fn(),
  updateTicketStage: vi.fn(),
  markTicketMitigated: vi.fn(),
  confirmRootCause: vi.fn(),
  submitVerification: vi.fn(),
  skipVerification: vi.fn(),
}))
vi.mock('@/api/tickets', () => ticketsApi)

vi.mock('@/api/dashboard', () => ({ getTrends: vi.fn() }))
vi.mock('@/api/utils/dto-converter', () => ({ mapServiceToModule: (s: string) => s }))

vi.mock('@/composables/useTicketAnalysis', () => ({
  useTicketAnalysis: () => ({
    analysisContent: ref(''), analysisStreaming: ref(false), analysisDone: ref(false),
    citations: ref([]), analysisCost: ref(null), analysisFromArchive: ref(false),
    analysisArchivedAt: ref(null), analysisId: ref(null), analysisFeedback: ref(null),
    submitFeedback: vi.fn(),
    structured: ref({ reasons: [], commands: [], other: '', structured: false, confidence: null }),
    confidenceClass: ref(''), useStructuredRender: ref(false),
    similarTickets: ref([]), similarLoading: ref(false),
    relatedDocs: ref([]), relatedLoading: ref(false),
    runAnalysis: vi.fn(), stopAnalysis: vi.fn(), regenerateAnalysis: vi.fn(),
    generateReply: vi.fn(), loadSimilarTickets: vi.fn(), loadRelatedDocs: vi.fn(),
    renderMarkdown: (s: string) => s, copyCommand: vi.fn(), copyAnalysis: vi.fn(),
    loadArchivedAnalysis: vi.fn().mockResolvedValue(true), resetAnalysis: vi.fn(),
  }),
}))

vi.mock('@/composables/useTicketPostmortem', () => ({
  useTicketPostmortem: () => ({
    drawerVisible: ref(false), form: ref({}), newActionItem: ref({}),
    actionItems: ref([]), postmortem: ref(null), saving: ref(false),
    open: vi.fn(), generateDraft: vi.fn(), save: vi.fn(),
    addItem: vi.fn(), updateItemStatus: vi.fn(),
  }),
}))

type Ticket = Record<string, unknown>

const baseTicket = (over: Ticket = {}): Ticket => ({
  id: 'TKT-20260825-0001',
  title: 'order-service Pod CrashLoopBackOff',
  status: 'processing', priority: 'high', category: '故障',
  service: 'order-service', sla: '4h', assignee: '张明', creator: '李强',
  description: '描述', createTime: '2026-08-25 10:00:00',
  replies: [], tags: [], slaProgress: 10, slaBreached: false,
  firstResponseState: 'RESPONDED', firstResponseMinutes: 5,
  mitigatedAt: null, rootCauseAt: null, verifiedAt: null, verifySkipped: false,
  handlingStage: 'TRIAGE', version: 1,
  ...over,
})

const storeStub = vi.hoisted(() => ({
  current: null as Ticket | null,
  updateStatus: vi.fn(), updateTicket: vi.fn(), updateTags: vi.fn(),
  transferTicket: vi.fn(), appendReply: vi.fn(),
  loadTicketDetail: vi.fn(), loadActivities: vi.fn(),
  loadTeamMembers: vi.fn(), addTicket: vi.fn(),
}))

vi.mock('@/stores/tickets', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/tickets')
  return {
    ...actual,
    useTicketsStore: () => ({
      ...storeStub,
      getById: () => storeStub.current,
      // Pinia 在组件里已解包 ref，桩成普通值
      teamMembers: [], assignees: [], hotTags: [],
      activities: [], loading: false,
    }),
  }
})

import TicketDetail from '../TicketDetail.vue'

let router: Router

const mountDetail = async (ticket: Ticket | null) => {
  storeStub.current = ticket
  ticketsApi.fetchTicketById.mockResolvedValue(ticket)
  ticketsApi.fetchTicketActions.mockResolvedValue([])
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
        PostmortemDrawer: true, AnalysisCard: true, TicketInsights: true,
        KnowledgeSinkDrawer: true, AppEmpty: true, ApiErrorState: true,
        PageLoading: true, CollapsibleCard: { template: '<div><slot /></div>' },
        AppBreadcrumb: true, RelativeTime: true,
        'el-dialog': true, 'el-select': true, 'el-option': true,
        'el-input': true, 'el-button': true, 'el-tag': true, 'el-tooltip': true,
      },
    },
  })
  await wrapper.vm.$nextTick()
  return wrapper
}

type Vm = {
  verifyForm: { method: string; conclusion: string; skip: boolean; skipReason: string }
  verifySubmitting: boolean
  verifyDialogVisible: boolean
  rootCauseForm: { rootCause: string; category: string }
  rootCauseSubmitting: boolean
  rootCauseDialogVisible: boolean
  actionForm: { actionType: string; summary: string; detail: string; effective: boolean | null }
  actionSubmitting: boolean
  actionDialogVisible: boolean
  doSubmitVerification: () => Promise<void>
  doConfirmRootCause: () => Promise<void>
  doAddAction: () => Promise<void>
  doUpdateStage: (s: string) => Promise<void>
  doMarkMitigated: () => Promise<void>
}

const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
  storeStub.current = null
})

// ==================================================================

describe('TicketDetail — 修复验证 doSubmitVerification', () => {
  it('跳过验证但不填理由被拒绝', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    vm.verifyForm.skip = true
    vm.verifyForm.skipReason = '   '

    await vm.doSubmitVerification()

    // 不填理由就跳过 = 事后无人知道为什么跳过，复盘时这段是断的
    expect(ticketsApi.skipVerification).not.toHaveBeenCalled()
    expect(ticketsApi.submitVerification).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalled()
  })

  it('跳过验证带理由时走 skipVerification 并 trim', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    ticketsApi.skipVerification.mockResolvedValue({
      status: 'resolved', verifiedAt: '2026-08-25 12:00:00',
      verifyMethod: null, verifySkipped: true, version: 2,
      updatedAt: '2026-08-25 12:00:00',
    })
    vm.verifyForm.skip = true
    vm.verifyForm.skipReason = '  无监控覆盖  '

    await vm.doSubmitVerification()

    expect(ticketsApi.skipVerification).toHaveBeenCalledWith(
      'TKT-20260825-0001', '无监控覆盖', expect.any(String)
    )
  })

  it('正常验证走 submitVerification', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    ticketsApi.submitVerification.mockResolvedValue({
      status: 'resolved', verifiedAt: 'x', verifyMethod: 'MONITOR',
      verifySkipped: false, version: 2, updatedAt: 'x',
    })
    vm.verifyForm.skip = false
    vm.verifyForm.method = 'MONITOR'
    vm.verifyForm.conclusion = '  指标已恢复  '

    await vm.doSubmitVerification()

    expect(ticketsApi.submitVerification).toHaveBeenCalledWith(
      'TKT-20260825-0001', 'MONITOR', '指标已恢复', expect.any(String)
    )
  })

  it('用后端返回值同步本地状态——MTTR 终点由后端定', async () => {
    const t = baseTicket()
    const w = await mountDetail(t)
    ticketsApi.submitVerification.mockResolvedValue({
      status: 'resolved', verifiedAt: '2026-08-25 12:00:00',
      verifyMethod: 'MONITOR', verifySkipped: false, version: 9,
      updatedAt: '2026-08-25 12:00:00',
    })
    vmOf(w).verifyForm.conclusion = '已恢复'

    await vmOf(w).doSubmitVerification()

    expect(t.status).toBe('resolved')
    expect(t.verifiedAt).toBe('2026-08-25 12:00:00')
    expect(t.version).toBe(9)
  })

  it('成功后关闭弹窗并刷新活动流', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    ticketsApi.submitVerification.mockResolvedValue({
      status: 'resolved', verifiedAt: 'x', verifyMethod: 'M',
      verifySkipped: false, version: 2, updatedAt: 'x',
    })
    vm.verifyDialogVisible = true
    vm.verifyForm.conclusion = '已恢复'

    await vm.doSubmitVerification()

    expect(vm.verifyDialogVisible).toBe(false)
    expect(storeStub.loadActivities).toHaveBeenCalled()
  })

  it('失败时保持弹窗打开，且解除提交中标记', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    ticketsApi.submitVerification.mockRejectedValue(new Error('boom'))
    vm.verifyDialogVisible = true
    vm.verifyForm.conclusion = '已恢复'

    await vm.doSubmitVerification()

    // 失败还关弹窗会让用户以为提交成功了
    expect(vm.verifyDialogVisible).toBe(true)
    expect(vm.verifySubmitting).toBe(false)
    expect(handleServerErrorMock).toHaveBeenCalled()
  })

  it('提交中再次调用被忽略', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    let resolveFn!: (v: unknown) => void
    ticketsApi.submitVerification.mockReturnValue(
      new Promise((res) => { resolveFn = res })
    )
    vm.verifyForm.conclusion = '已恢复'

    const first = vm.doSubmitVerification()
    await w.vm.$nextTick()
    await vm.doSubmitVerification()

    resolveFn({ status: 'resolved', version: 2 })
    await first

    expect(ticketsApi.submitVerification).toHaveBeenCalledTimes(1)
  })
})

describe('TicketDetail — 根因确认 doConfirmRootCause', () => {
  it('根因留空被拒绝——复盘时这张单会没有可归因的内容', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    vm.rootCauseForm.rootCause = '   '

    await vm.doConfirmRootCause()

    expect(ticketsApi.confirmRootCause).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalled()
  })

  it('提交时 trim 根因文本', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    ticketsApi.confirmRootCause.mockResolvedValue({
      rootCauseCategory: 'CONFIG', version: 3, updatedAt: 'x',
    })
    vm.rootCauseForm.rootCause = '  配置项写错  '
    vm.rootCauseForm.category = 'CONFIG'

    await vm.doConfirmRootCause()

    expect(ticketsApi.confirmRootCause).toHaveBeenCalledWith(
      'TKT-20260825-0001', '配置项写错', 'CONFIG', expect.any(String)
    )
  })

  it('成功后同步分类与版本号并关弹窗', async () => {
    const t = baseTicket()
    const w = await mountDetail(t)
    const vm = vmOf(w)
    ticketsApi.confirmRootCause.mockResolvedValue({
      rootCauseCategory: 'CODE', version: 4, updatedAt: 'y',
    })
    vm.rootCauseDialogVisible = true
    vm.rootCauseForm.rootCause = '代码缺陷'

    await vm.doConfirmRootCause()

    expect(t.rootCauseCategory).toBe('CODE')
    expect(t.version).toBe(4)
    expect(vm.rootCauseDialogVisible).toBe(false)
  })

  it('失败时解除提交中标记，允许重试', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    ticketsApi.confirmRootCause.mockRejectedValue(new Error('boom'))
    vm.rootCauseForm.rootCause = '原因'

    await vm.doConfirmRootCause()

    expect(vm.rootCauseSubmitting).toBe(false)
  })
})

describe('TicketDetail — 处置动作 doAddAction', () => {
  it('摘要留空被拒绝——否则活动流里会出现一条什么也没说的记录', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    vm.actionForm.summary = '  '

    await vm.doAddAction()

    expect(ticketsApi.addTicketAction).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalled()
  })

  it('detail 为空时传 undefined 而非空串', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    ticketsApi.addTicketAction.mockResolvedValue(undefined)
    vm.actionForm.summary = '重启了 Pod'
    vm.actionForm.detail = '   '

    await vm.doAddAction()

    // 空串会在详情页渲染成一个空白区块，undefined 才会被正确跳过
    expect(ticketsApi.addTicketAction).toHaveBeenCalledWith(
      'TKT-20260825-0001',
      expect.objectContaining({ summary: '重启了 Pod', detail: undefined })
    )
  })

  it('成功后刷新处置列表与活动流并关弹窗', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    ticketsApi.addTicketAction.mockResolvedValue(undefined)
    vm.actionDialogVisible = true
    vm.actionForm.summary = '排查中'

    await vm.doAddAction()

    expect(ticketsApi.fetchTicketActions).toHaveBeenCalled()
    expect(storeStub.loadActivities).toHaveBeenCalled()
    expect(vm.actionDialogVisible).toBe(false)
  })

  it('提交中再次调用被忽略', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    let resolveFn!: (v: unknown) => void
    ticketsApi.addTicketAction.mockReturnValue(
      new Promise((res) => { resolveFn = res })
    )
    vm.actionForm.summary = '内容'

    const first = vm.doAddAction()
    await w.vm.$nextTick()
    await vm.doAddAction()

    resolveFn(undefined)
    await first

    expect(ticketsApi.addTicketAction).toHaveBeenCalledTimes(1)
  })

  it('失败时保持弹窗打开并解除标记', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    ticketsApi.addTicketAction.mockRejectedValue(new Error('boom'))
    vm.actionDialogVisible = true
    vm.actionForm.summary = '内容'

    await vm.doAddAction()

    expect(vm.actionDialogVisible).toBe(true)
    expect(vm.actionSubmitting).toBe(false)
  })
})

describe('TicketDetail — 阶段切换与止损', () => {
  it('切换阶段后用后端响应同步 handlingStage 与版本号', async () => {
    const t = baseTicket({ handlingStage: 'TRIAGE' })
    const w = await mountDetail(t)
    ticketsApi.updateTicketStage.mockResolvedValue({
      handlingStage: 'FIXING', status: 'processing', version: 5, updatedAt: 'z',
    })

    await vmOf(w).doUpdateStage('FIXING')

    expect(t.handlingStage).toBe('FIXING')
    expect(t.version).toBe(5)
    expect(storeStub.loadActivities).toHaveBeenCalled()
  })

  it('阶段切换失败时交给统一错误处理，不静默', async () => {
    const w = await mountDetail(baseTicket())
    ticketsApi.updateTicketStage.mockRejectedValue(new Error('boom'))

    await vmOf(w).doUpdateStage('FIXING')

    expect(handleServerErrorMock).toHaveBeenCalled()
  })

  it('标记止损写入 mitigatedAt——闭环进度条据此判定', async () => {
    const t = baseTicket({ mitigatedAt: null })
    const w = await mountDetail(t)
    ticketsApi.markTicketMitigated.mockResolvedValue({
      handlingStage: 'MITIGATED', mitigatedAt: '2026-08-25 11:00:00',
      status: 'processing', version: 6, updatedAt: 'z',
    })

    await vmOf(w).doMarkMitigated()

    // closureStages 的「止损」阶段按 mitigatedAt 判定，不是按状态
    expect(t.mitigatedAt).toBe('2026-08-25 11:00:00')
    expect(t.handlingStage).toBe('MITIGATED')
  })

  it('工单为空时两个动作都安全返回', async () => {
    const w = await mountDetail(null)
    await vmOf(w).doUpdateStage('FIXING')
    await vmOf(w).doMarkMitigated()

    expect(ticketsApi.updateTicketStage).not.toHaveBeenCalled()
    expect(ticketsApi.markTicketMitigated).not.toHaveBeenCalled()
  })
})
