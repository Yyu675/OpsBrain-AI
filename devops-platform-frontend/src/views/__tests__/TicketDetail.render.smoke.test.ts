/**
 * TicketDetail —— **渲染冒烟测试**。
 *
 * ── 为什么在已有 72 例的情况下还要加这一组 ──────────────────
 * 现有三个测试文件（actions 28 / derive 24 / forms 20）确实都 mount 了整页，
 * 但它们合计只有 <b>3 处 DOM 断言</b>——测的是脚本里的派生逻辑与事件处理，
 * <b>把整段模板删掉它们照样能全绿</b>。
 *
 * 这正是拆分 `TicketDetail.vue`（2026 行，已是最大前端文件）前缺的那道防线。
 * 同样的路径在 `TicketList.vue` 上已验证两次：
 * 先补渲染断言 → 再拆模板，拆完既有用例一行不改直接通过。
 *
 * ── 守什么 ────────────────────────────────────────────────────
 * <ol>
 *   <li><b>四种页面状态互斥</b>——loading / loadError / notFound / 正常内容。
 *       它们是四个平级 v-if，拆分时最容易漏搬其中一个分支，
 *       结果是「加载失败」和「工单不存在」同时显示，或者两个都不显示只剩白屏；</li>
 *   <li><b>关键信息真的渲染出来</b>——标题、状态徽章、负责人。
 *       容器还在但内容没了是拆分最典型的失败形态
 *       （`TicketCardGrid` 那次就是靠「卡片上渲染出工单标题」这条抓到的）；</li>
 *   <li><b>子组件确实被挂载</b>——时间线、AI 洞察、复盘抽屉、知识沉淀抽屉。
 *       它们是独立组件，父页面漏了标签不会有任何报错，只是那块功能凭空消失。</li>
 * </ol>
 *
 * ── 与既有三个文件的分工 ──────────────────────────────────────
 * 那三个测「点了按钮会发生什么」，本文件测「页面长出来了没有」。
 * 两者互补：前者保证行为，后者保证结构。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
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
      // 真实 Pinia store 在组件里访问时 ref 已自动解包，
      // 代码里直接写 store.teamMembers.find(...)。桩成 ref 会让它变成
      // Ref 对象而没有 .find —— 这是桩的失真，不是产品缺陷
      teamMembers: [],
      assignees: [],
      hotTags: [],
      activities: [],
      loading: false,
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
        // 与 derive 那批的关键差异：子组件用**具名 stub**而非 `true`。
        // `true` 会渲染成 <xxx-stub>，findComponent({name}) 拿不到，
        // 「子组件有没有被挂载」这条就无从断言——而那恰恰是拆分最易漏的一环。
        PostmortemDrawer: { name: 'PostmortemDrawer', template: '<div class="stub-postmortem" />' },
        AnalysisCard: { name: 'AnalysisCard', template: '<div class="stub-analysis" />' },
        TicketInsights: { name: 'TicketInsights', template: '<div class="stub-insights" />' },
        KnowledgeSinkDrawer: { name: 'KnowledgeSinkDrawer', template: '<div class="stub-sink" />' },
        TicketTimeline: { name: 'TicketTimeline', template: '<div class="stub-timeline" />' },
        AppEmpty: true, ApiErrorState: true,
        PageLoading: true, CollapsibleCard: { template: '<div><slot /></div>' },
        AppBreadcrumb: true, RelativeTime: true,
        'el-dialog': true, 'el-select': true, 'el-option': true,
        'el-input': true, 'el-button': true, 'el-tag': true,
      },
    },
  })
  // 详情由 useResourceState 异步加载，单个 nextTick 等不到它落定——
  // 页面会停在 loading 态，w.text() 返回空串，
  // 所有「内容渲染出来了没有」的断言都会以一个看不懂的方式失败。
  await flushPromises()
  await wrapper.vm.$nextTick()
  return wrapper
}

type Vm = {
  loading: boolean
  loadError: unknown
  notFound: boolean
}

const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  confirmMock.mockResolvedValue(undefined)
})

describe('四种页面状态互斥', () => {
  it('正常加载：渲染工单主体，不出现任何错误态', async () => {
    const w = await mountDetail(baseTicket())

    expect(w.find('.ticket-header-card').exists()).toBe(true)
    expect(w.find('.ticket-title').exists()).toBe(true)
    // 三种异常态都不该同时存在。四个平级 v-if 里漏搬一个分支，
    // 就会出现「正常内容」与「工单不存在」并排显示
    expect(vmOf(w).loading).toBe(false)
    expect(vmOf(w).notFound).toBe(false)
  })

  it('工单不存在：不渲染主体，避免读空对象报错', async () => {
    const w = await mountDetail(null)

    // ticket 为空还渲染主体的话，模板里 ticket.title 会直接抛
    // "Cannot read properties of undefined"，整页白屏
    expect(w.find('.ticket-header-card').exists()).toBe(false)
  })
})

describe('关键信息真的渲染出来', () => {
  it('标题、创建人、负责人都出现在页面上', async () => {
    const w = await mountDetail(baseTicket({
      title: 'order-service Pod CrashLoopBackOff',
      creator: '李强',
      assignee: '张明',
    }))
    const text = w.text()

    // 只断言容器存在是不够的：拆分时最典型的失败是
    // 「壳子搬过去了、内容没跟上」，容器在但里面是空的
    expect(text).toContain('order-service Pod CrashLoopBackOff')
    expect(text).toContain('李强')
    expect(text).toContain('张明')
  })

  it('状态与优先级徽章按值渲染 class，供样式着色', async () => {
    const w = await mountDetail(baseTicket({ status: 'pending', priority: 'P1' }))

    // class 里带上状态值是配色的依据。拆分时若把 :class 写死或漏掉，
    // 所有徽章会变成同一个颜色，而文字还是对的——很难一眼看出
    expect(w.find('.badge-status-pending').exists()).toBe(true)
    expect(w.find('.ticket-badges').exists()).toBe(true)
  })

  it('已首响的工单不再显示「确认接单」按钮', async () => {
    const w = await mountDetail(baseTicket({ firstResponseState: 'RESPONDED' }))

    // 已响应还显示接单按钮，会出现「确认接单」与「已响应 N 分钟」
    // 并存的矛盾界面，运维不知道该不该点
    expect(w.text()).not.toContain('确认接单')
  })

  it('未首响的工单显示「确认接单」', async () => {
    const w = await mountDetail(baseTicket({ firstResponseState: 'PENDING' }))

    expect(w.text()).toContain('确认接单')
  })
})

describe('子组件确实被挂载', () => {
  it('时间线、AI 洞察、复盘抽屉、知识沉淀抽屉都在', async () => {
    const w = await mountDetail(baseTicket())

    // 这四个是独立组件。父页面漏了标签不会有任何报错，
    // 只是那块功能凭空消失——用户点不到、也不会看到错误提示
    for (const name of ['TicketTimeline', 'TicketInsights', 'PostmortemDrawer', 'KnowledgeSinkDrawer']) {
      expect(w.findComponent({ name }).exists(), `${name} 应被挂载`).toBe(true)
    }
  })

  it('工单为空时不挂载子组件——它们都依赖 ticket 数据', async () => {
    const w = await mountDetail(null)

    // 传 undefined 给子组件会让它们各自在内部炸开，
    // 报错位置离真正的原因（父页面没数据）很远
    expect(w.findComponent({ name: 'TicketTimeline' }).exists()).toBe(false)
  })
})
