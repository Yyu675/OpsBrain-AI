/**
 * TicketDetail —— 写操作测试（第二批）。
 *
 * ── 为什么这批比派生逻辑更要紧 ────────────────────────────────
 * 这些动作全都**写活动流**，而工单时间线是事后复盘与追责的依据。
 * 防重入失效的代价不是「不好看」：慢接口下双击「升级上报」会提交两次，
 * 活动流里出现两条一模一样的记录，复盘时无法判断到底升级了几次。
 *
 * 本项目已经因为这个问题修过一轮（`useAsyncAction` 的引入原因就是它），
 * 但拆分组件时这类保护最容易被搬丢——它不在业务主线上，
 * 少一个 `if (busy) return` 编译器不会有任何反应。
 *
 * ── 覆盖清单 ──────────────────────────────────────────────────
 *   submitReply       乐观清空 + 失败恢复草稿 + 防重入
 *   doAcknowledge     防重入 + 用后端返回值校准派生字段
 *   raisePriority     已最高时拒绝 + 二次确认 + 取消不提交
 *   doEscalate        原因必填（prompt 取消不提交）
 *   doTransfer        同人转派短路 + 空目标不提交
 *   addTag/removeTag  重复标签拒绝 + 失败不清输入
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
vi.mock('@/utils/notify', () => ({
  notify: notifyMock,
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
    postmortem: ref(null), postmortemLoading: ref(false), drawerOpen: ref(false),
    openDrawer: vi.fn(), loadPostmortem: vi.fn(), savePostmortem: vi.fn(),
  }),
}))

type Ticket = Record<string, unknown>

const baseTicket = (over: Ticket = {}): Ticket => ({
  id: 'TKT-20260825-0001',
  title: 'order-service Pod CrashLoopBackOff',
  status: 'processing',
  priority: 'high',
  category: '故障',
  service: 'order-service',
  sla: '4h',
  assignee: '张明',
  creator: '李强',
  description: '描述',
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
  ...over,
})

const storeStub = vi.hoisted(() => ({
  current: null as Ticket | null,
  updateStatus: vi.fn(),
  updateTicket: vi.fn(),
  updateTags: vi.fn(),
  transferTicket: vi.fn(),
  appendReply: vi.fn(),
  loadTicketDetail: vi.fn(),
  loadActivities: vi.fn(),
  loadTeamMembers: vi.fn(),
  addTicket: vi.fn(),
}))

vi.mock('@/stores/tickets', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/tickets')
  return {
    ...actual,
    useTicketsStore: () => ({
      ...storeStub,
      getById: () => storeStub.current,
      // 真实 Pinia store 在组件里访问时 ref 已自动解包，
      // 代码里直接写 store.teamMembers.find(...)。桩成 ref 会让它变成
      // Ref 对象而没有 .find —— 这是桩的失真，不是产品缺陷
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
  replyContent: string
  submitting: boolean
  acknowledging: boolean
  tagRemoving: boolean
  newTagInput: string
  transferTarget: string
  transferDialogVisible: boolean
  submitReply: () => Promise<void>
  doAcknowledge: () => Promise<void>
  raisePriority: () => Promise<void>
  doEscalate: () => Promise<void>
  doTransfer: () => Promise<void>
  addTag: () => Promise<void>
  removeTag: (t: string) => Promise<void>
  addTagFromSuggestion: (t: string) => Promise<void>
  workloadOf: (n: string) => string
}

const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

/** 造一个可控完成时机的 Promise，用于测「进行中」窗口的重入 */
const deferred = <T,>() => {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
  storeStub.current = null
})

// ==================================================================

describe('TicketDetail — 提交回复 submitReply', () => {
  it('乐观清空输入框，成功后不恢复', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    storeStub.appendReply.mockResolvedValue(undefined)

    vm.replyContent = '已重启 Pod'
    await vm.submitReply()

    expect(storeStub.appendReply).toHaveBeenCalled()
    expect(vm.replyContent).toBe('')
  })

  it('失败时把草稿恢复回输入框——用户敲的字不能凭空消失', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    storeStub.appendReply.mockRejectedValue(new Error('网络错误'))

    vm.replyContent = '一段很长的排查记录'
    await vm.submitReply()

    // 乐观清空 + 失败不恢复 = 用户白打一遍字，这是最容易被忽略的体验缺陷
    expect(vm.replyContent).toBe('一段很长的排查记录')
  })

  it('空内容不提交，只提示', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)

    vm.replyContent = '   '
    await vm.submitReply()

    expect(storeStub.appendReply).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalled()
  })

  it('提交中再次调用被忽略——双击不会发两条回复', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    const d = deferred<void>()
    storeStub.appendReply.mockReturnValue(d.promise)

    vm.replyContent = '内容'
    const first = vm.submitReply()
    await w.vm.$nextTick()

    // 第一次尚未完成时的第二次点击
    vm.replyContent = '内容'
    await vm.submitReply()

    d.resolve()
    await first

    expect(storeStub.appendReply).toHaveBeenCalledTimes(1)
  })

  it('工单为空时安全返回', async () => {
    const w = await mountDetail(null)
    const vm = vmOf(w)
    vm.replyContent = '内容'
    await vm.submitReply()
    expect(storeStub.appendReply).not.toHaveBeenCalled()
  })
})

describe('TicketDetail — 确认接单 doAcknowledge', () => {
  it('用后端返回值校准派生字段，而不是前端自己算', async () => {
    const t = baseTicket({ status: 'pending' })
    const w = await mountDetail(t)
    ticketsApi.acknowledgeTicket.mockResolvedValue({
      firstResponseState: 'RESPONDED',
      firstResponseMinutes: 7,
      firstResponder: '王五',
      status: 'processing',
      version: 3,
      updatedAt: '2026-08-25 10:07:00',
    })

    await vmOf(w).doAcknowledge()

    // 首响状态/MTTA/版本号都由后端计算——前端自己推会与后端口径漂移
    expect(t.firstResponseState).toBe('RESPONDED')
    expect(t.firstResponseMinutes).toBe(7)
    expect(t.version).toBe(3)
    expect(storeStub.loadActivities).toHaveBeenCalled()
  })

  it('进行中再次调用被忽略', async () => {
    const w = await mountDetail(baseTicket({ status: 'pending' }))
    const vm = vmOf(w)
    const d = deferred<Record<string, unknown>>()
    ticketsApi.acknowledgeTicket.mockReturnValue(d.promise)

    const first = vm.doAcknowledge()
    await w.vm.$nextTick()
    await vm.doAcknowledge()

    d.resolve({ firstResponseState: 'RESPONDED', status: 'processing' })
    await first

    expect(ticketsApi.acknowledgeTicket).toHaveBeenCalledTimes(1)
  })

  it('失败后解除进行中标记，允许重试', async () => {
    const w = await mountDetail(baseTicket({ status: 'pending' }))
    const vm = vmOf(w)
    ticketsApi.acknowledgeTicket.mockRejectedValue(new Error('boom'))

    await vm.doAcknowledge()

    // 忘写 finally 会让按钮永久禁用，比不加防护更糟
    expect(vm.acknowledging).toBe(false)
  })
})

describe('TicketDetail — 提升优先级 raisePriority', () => {
  it('已是最高优先级时拒绝，不弹确认也不提交', async () => {
    const w = await mountDetail(baseTicket({ priority: 'urgent' }))
    await vmOf(w).raisePriority()

    expect(notifyMock.warning).toHaveBeenCalled()
    expect(confirmMock).not.toHaveBeenCalled()
    expect(storeStub.updateTicket).not.toHaveBeenCalled()
  })

  it('确认后按序提升一级', async () => {
    const w = await mountDetail(baseTicket({ priority: 'medium' }))
    storeStub.updateTicket.mockResolvedValue(undefined)

    await vmOf(w).raisePriority()

    expect(storeStub.updateTicket).toHaveBeenCalledWith(
      'TKT-20260825-0001', { priority: 'high' }
    )
  })

  it('确认框提示 SLA 会重算——用户必须知道这次点击的后果', async () => {
    const w = await mountDetail(baseTicket({ priority: 'low' }))
    storeStub.updateTicket.mockResolvedValue(undefined)

    await vmOf(w).raisePriority()

    // 改优先级会连带重算 SLA 时限，不说明的话用户是在无意中改计时基线
    expect(String(confirmMock.mock.calls[0][0])).toContain('SLA')
  })

  it('用户取消时不提交', async () => {
    confirmMock.mockRejectedValue('cancel')
    const w = await mountDetail(baseTicket({ priority: 'medium' }))

    await vmOf(w).raisePriority()

    expect(storeStub.updateTicket).not.toHaveBeenCalled()
  })
})

describe('TicketDetail — 升级上报 doEscalate', () => {
  it('原因必填：prompt 返回后带上原因提交', async () => {
    promptMock.mockResolvedValue({ value: '  需跨团队协同  ' })
    const w = await mountDetail(baseTicket())
    ticketsApi.escalateTicket.mockResolvedValue(undefined)

    await vmOf(w).doEscalate()

    // 原因要 trim——首尾空格会让「原因必填」形同虚设
    expect(ticketsApi.escalateTicket).toHaveBeenCalledWith(
      'TKT-20260825-0001', '需跨团队协同', expect.any(String)
    )
  })

  it('用户取消 prompt 时不提交', async () => {
    promptMock.mockRejectedValue('cancel')
    const w = await mountDetail(baseTicket())

    await vmOf(w).doEscalate()

    expect(ticketsApi.escalateTicket).not.toHaveBeenCalled()
  })

  it('升级不改优先级——它与「提升优先级」是两件事', async () => {
    promptMock.mockResolvedValue({ value: '影响面扩大' })
    const w = await mountDetail(baseTicket({ priority: 'medium' }))
    ticketsApi.escalateTicket.mockResolvedValue(undefined)

    await vmOf(w).doEscalate()

    // 二者曾混在一个「升级」按钮里，用户点它其实在改 SLA 计时基线却不知情
    expect(storeStub.updateTicket).not.toHaveBeenCalled()
  })

  it('成功后刷新活动流', async () => {
    promptMock.mockResolvedValue({ value: '原因' })
    const w = await mountDetail(baseTicket())
    ticketsApi.escalateTicket.mockResolvedValue(undefined)

    await vmOf(w).doEscalate()

    expect(storeStub.loadActivities).toHaveBeenCalled()
  })
})

describe('TicketDetail — 转派 doTransfer', () => {
  it('目标为空时不提交', async () => {
    const w = await mountDetail(baseTicket())
    const vm = vmOf(w)
    vm.transferTarget = ''

    await vm.doTransfer()

    expect(storeStub.transferTicket).not.toHaveBeenCalled()
  })

  it('转给当前负责人时短路，只关弹窗不发请求', async () => {
    const w = await mountDetail(baseTicket({ assignee: '张明' }))
    const vm = vmOf(w)
    vm.transferTarget = '张明'
    vm.transferDialogVisible = true

    await vm.doTransfer()

    // 无谓请求会在活动流里留下一条「转派给张明」，而实际什么也没变
    expect(storeStub.transferTicket).not.toHaveBeenCalled()
    expect(vm.transferDialogVisible).toBe(false)
  })

  it('转给他人时提交并关闭弹窗', async () => {
    const w = await mountDetail(baseTicket({ assignee: '张明' }))
    const vm = vmOf(w)
    storeStub.transferTicket.mockResolvedValue(undefined)
    vm.transferTarget = '李强'
    vm.transferDialogVisible = true

    await vm.doTransfer()

    expect(storeStub.transferTicket).toHaveBeenCalledWith('TKT-20260825-0001', '李强')
    expect(vm.transferDialogVisible).toBe(false)
  })

  it('失败时保持弹窗打开，让用户能改目标重试', async () => {
    const w = await mountDetail(baseTicket({ assignee: '张明' }))
    const vm = vmOf(w)
    storeStub.transferTicket.mockRejectedValue(new Error('boom'))
    vm.transferTarget = '李强'
    vm.transferDialogVisible = true

    await vm.doTransfer()

    // 失败还关弹窗会让用户以为成功了
    expect(vm.transferDialogVisible).toBe(true)
  })
})

describe('TicketDetail — 标签增删', () => {
  it('重复标签被拒绝，不发请求', async () => {
    const w = await mountDetail(baseTicket({ tags: ['k8s'] }))
    const vm = vmOf(w)
    vm.newTagInput = 'k8s'

    await vm.addTag()

    expect(storeStub.updateTags).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalled()
  })

  it('新增标签提交合并后的完整列表', async () => {
    const w = await mountDetail(baseTicket({ tags: ['k8s'] }))
    const vm = vmOf(w)
    storeStub.updateTags.mockResolvedValue(undefined)
    vm.newTagInput = '  数据库  '

    await vm.addTag()

    // 后端要的是全量列表而非增量，且必须 trim
    expect(storeStub.updateTags).toHaveBeenCalledWith(
      'TKT-20260825-0001', ['k8s', '数据库']
    )
    expect(vm.newTagInput).toBe('')
  })

  it('新增失败时保留输入内容，便于重试', async () => {
    const w = await mountDetail(baseTicket({ tags: [] }))
    const vm = vmOf(w)
    storeStub.updateTags.mockRejectedValue(new Error('boom'))
    vm.newTagInput = '数据库'

    await vm.addTag()

    expect(vm.newTagInput).toBe('数据库')
    expect(vm.tagRemoving).toBe(false)
  })

  it('移除标签提交剩余列表', async () => {
    const w = await mountDetail(baseTicket({ tags: ['k8s', '数据库'] }))
    storeStub.updateTags.mockResolvedValue(undefined)

    await vmOf(w).removeTag('k8s')

    expect(storeStub.updateTags).toHaveBeenCalledWith(
      'TKT-20260825-0001', ['数据库']
    )
  })

  it('从建议添加时跳过已有标签', async () => {
    const w = await mountDetail(baseTicket({ tags: ['k8s'] }))

    await vmOf(w).addTagFromSuggestion('k8s')

    expect(storeStub.updateTags).not.toHaveBeenCalled()
  })

  it('失败后解除进行中标记，不永久禁用按钮', async () => {
    const w = await mountDetail(baseTicket({ tags: ['k8s'] }))
    const vm = vmOf(w)
    storeStub.updateTags.mockRejectedValue(new Error('boom'))

    await vm.removeTag('k8s')

    expect(vm.tagRemoving).toBe(false)
  })
})

describe('TicketDetail — 负载提示 workloadOf', () => {
  it('「待分配」不是人，无负载可言', async () => {
    const w = await mountDetail(baseTicket())
    expect(vmOf(w).workloadOf('待分配')).toBe('')
  })

  it('名单里没有的人返回空串，不显示「（undefined 单）」', async () => {
    const w = await mountDetail(baseTicket())
    expect(vmOf(w).workloadOf('查无此人')).toBe('')
  })
})
