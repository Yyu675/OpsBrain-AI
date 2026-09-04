/**
 * TicketList 渲染冒烟测试。
 *
 * ── 这个文件存在的唯一目的：为「按视图拆子组件」兜底 ──────────
 * `TicketList.vue` 剩余约 1500 行是**模板**。把它按列表视图 / 卡片视图
 * 拆成子组件，动的是 DOM 结构与样式作用域——而现有测试
 * （filters / bulk / columns）全是逻辑层的，**模板整段删掉它们照样全绿**。
 *
 * 没有渲染层断言就拆模板，等于闭着眼睛做手术。所以先有这个文件，
 * 再谈拆分。它守的是「拆完之后，该出现的东西还在不在」：
 *
 * <ul>
 *   <li>两种视图各自渲染出自己的容器与行/卡片；</li>
 *   <li>切换视图时列表内容不丢失（同一份数据换个渲染方式）；</li>
 *   <li>列可见性真的影响 DOM（隐藏的列不该还在表头里）；</li>
 *   <li>空态与数据态是两套不同的 DOM，不能都渲染成空白。</li>
 * </ul>
 *
 * ── 为什么用 stub 而不是完整渲染 el-table ──────────────────
 * el-table 的真实渲染依赖大量布局计算，jsdom 下拿不到宽高，
 * 断言会退化成「它有没有抛错」。这里 stub 掉 el-table 本身，
 * 但**保留其外层容器与我们自己写的 DOM**——拆分要动的正是后者。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent } from 'vue'

import type { FrontendTicket } from '@/api/types/ticket'

const api = vi.hoisted(() => ({
  fetchTickets: vi.fn(),
  fetchTicketStats: vi.fn(),
  fetchHotTags: vi.fn(),
  fetchTeamMembers: vi.fn(),
  updateTicketStatus: vi.fn(),
  deleteTicket: vi.fn(),
  transferTicket: vi.fn(),
  exportTicketsCsv: vi.fn(),
  createTicket: vi.fn(),
  updateTicket: vi.fn(),
  fetchTicketById: vi.fn(),
  fetchTicketReplies: vi.fn(),
  fetchTicketActivities: vi.fn(),
  addTicketReply: vi.fn(),
  replaceTicketTags: vi.fn(),
  listActionItems: vi.fn(),
  updateActionItem: vi.fn(),
}))
vi.mock('@/api/tickets', () => api)

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('element-plus')
  return {
    ...actual,
    ElMessageBox: { confirm: vi.fn(() => Promise.resolve()), prompt: vi.fn() },
  }
})

import TicketList from '../TicketList.vue'

const makeTicket = (id: string, over: Partial<FrontendTicket> = {}): FrontendTicket =>
  ({
    id,
    title: `工单标题 ${id}`,
    description: '描述',
    status: 'pending',
    priority: 'medium',
    assignee: '张三',
    creator: 'admin',
    createdAt: '2026-08-24 10:00',
    updatedAt: '2026-08-24 10:00',
    service: '订单服务',
    category: '故障',
    tags: [],
    sla: '8h 响应 / 24h 解决',
    slaProgress: 10,
    slaBreached: false,
    slaRemainingMinutes: 100,
    firstResponseState: 'WAITING',
    firstResponseMinutes: null,
    responseRemainingMinutes: null,
    firstResponder: null,
    escalateReason: null,
    version: 1,
    replies: [],
    activities: [],
    ...over,
  }) as unknown as FrontendTicket

let router: Router

async function mountList(tickets: FrontendTicket[]) {
  api.fetchTickets.mockResolvedValue({
    tickets,
    total: tickets.length,
    totalPages: 1,
    currentPage: 1,
    pageSize: 10,
  })
  api.fetchTicketStats.mockResolvedValue({
    total: tickets.length, todayNew: 2, pending: 1,
    processing: 0, resolved: 0, urgentPending: 0,
  })
  api.fetchHotTags.mockResolvedValue([])
  api.fetchTeamMembers.mockResolvedValue([{ name: '张三' }])

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/tickets', component: TicketList },
      { path: '/tickets/:id', component: defineComponent({ template: '<div/>' }) },
    ],
  })
  await router.push('/tickets')
  await router.isReady()

  const wrapper = mount(TicketList, {
    global: {
      plugins: [router],
      stubs: {
        // el-table 的布局计算在 jsdom 下拿不到宽高，必须 stub；
        // 但**要渲染它的默认插槽**——列定义全在插槽里，
        // 用 `true` 的全量 stub 会把子列整段吞掉，
        // 「隐藏一列后 DOM 少一列」这类断言就永远拿到 0，测不到东西。
        'el-table': { template: '<div class="el-table-stub"><slot /></div>' },
        // el-table-column **不渲染插槽**：列内容是作用域插槽，
        // 依赖 el-table 真实传入的 row，在 stub 环境下 row 是 undefined，
        // 渲染它会抛 "Cannot read properties of undefined (reading 'id')"。
        // 这里只需要「列壳子存不存在」，不需要单元格内容。
        'el-table-column': { template: '<div class="el-table-column-stub" />' },
        'el-tag': true,
        'el-tooltip': true,
        TicketFormDialog: true,
        RelativeTime: true,
        ServerPagination: true,
        DataStateBoundary: { template: '<div class="dsb"><slot /></div>' },
      },
      directives: { permission: {} },
    },
  })
  await vi.waitFor(() => expect(api.fetchTickets).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  return wrapper
}

type ListVm = {
  viewMode: 'list' | 'card'
  columnVisible: Record<string, boolean>
  toggleColumn: (k: string) => void
}
const vmOf = (w: VueWrapper) => w.vm as unknown as ListVm

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('两种视图各自渲染', () => {
  it('默认列表视图：渲染表格容器', async () => {
    const w = await mountList([makeTicket('T-1'), makeTicket('T-2')])

    expect(vmOf(w).viewMode).toBe('list')
    expect(w.find('.table-container').exists()).toBe(true)
    // 卡片容器此时不该存在——两个视图是互斥的 v-if/v-else
    expect(w.find('.card-grid').exists()).toBe(false)
  })

  it('切到卡片视图：渲染卡片网格，且每条工单一张卡', async () => {
    const w = await mountList([makeTicket('T-1'), makeTicket('T-2'), makeTicket('T-3')])

    vmOf(w).viewMode = 'card'
    await w.vm.$nextTick()

    expect(w.find('.card-grid').exists()).toBe(true)
    expect(w.findAll('.ticket-card')).toHaveLength(3)
    expect(w.find('.table-container').exists()).toBe(false)
  })

  it('切换视图不丢数据——同一份工单换个渲染方式而已', async () => {
    const w = await mountList([makeTicket('T-1'), makeTicket('T-2')])

    vmOf(w).viewMode = 'card'
    await w.vm.$nextTick()
    expect(w.findAll('.ticket-card')).toHaveLength(2)

    vmOf(w).viewMode = 'list'
    await w.vm.$nextTick()
    // 切回来表格容器还在。若拆分时把数据源绑到了子组件内部 state，
    // 这里会变成 0 或报错
    expect(w.find('.table-container').exists()).toBe(true)
  })

  it('卡片上渲染出工单标题——拆分后最容易漏掉的是内容而非容器', async () => {
    const w = await mountList([makeTicket('T-77')])

    vmOf(w).viewMode = 'card'
    await w.vm.$nextTick()

    // 只断言容器存在是不够的：拆出去的子组件可能渲染了一个空壳
    expect(w.find('.ticket-card').text()).toContain('工单标题 T-77')
  })
})

describe('列设置只在列表视图出现', () => {
  it('列表视图有列设置入口', async () => {
    const w = await mountList([makeTicket('T-1')])

    expect(w.find('.col-setting-wrap').exists()).toBe(true)
  })

  it('卡片视图没有列设置——卡片没有「列」的概念', async () => {
    const w = await mountList([makeTicket('T-1')])

    vmOf(w).viewMode = 'card'
    await w.vm.$nextTick()

    // 留着它会让用户点开一个改不了任何东西的面板
    expect(w.find('.col-setting-wrap').exists()).toBe(false)
  })
})

describe('列可见性真的影响 DOM（拆列表视图前必须守住）', () => {
  /** 统计当前渲染出的 el-table-column 数量 */
  const columnCount = (w: VueWrapper) => w.findAll('.el-table-column-stub').length

  it('隐藏一列后表格里真的少一列', async () => {
    const w = await mountList([makeTicket('T-1')])
    const before = columnCount(w)
    // 至少要有若干列，否则下面的比较没有意义（stub 名字若变了会退化成 0-0）
    expect(before).toBeGreaterThan(5)

    vmOf(w).toggleColumn('service')
    await w.vm.$nextTick()

    // 只改 columnVisible 而模板没跟着走（比如拆分时漏搬 v-if），
    // 用户在列设置里取消勾选后表格纹丝不动——而且不会报错
    expect(columnCount(w)).toBe(before - 1)
  })

  it('再次开启后列回来——切换是可逆的', async () => {
    const w = await mountList([makeTicket('T-1')])
    const before = columnCount(w)

    vmOf(w).toggleColumn('service')
    await w.vm.$nextTick()
    vmOf(w).toggleColumn('service')
    await w.vm.$nextTick()

    expect(columnCount(w)).toBe(before)
  })

  it('默认隐藏的列（updatedAt）初始不渲染，开启后出现', async () => {
    const w = await mountList([makeTicket('T-1')])
    // updatedAt 默认 false：创建时间已在列表里，再加一列会让首屏过密
    expect(vmOf(w).columnVisible.updatedAt).toBe(false)
    const before = columnCount(w)

    vmOf(w).toggleColumn('updatedAt')
    await w.vm.$nextTick()

    expect(columnCount(w)).toBe(before + 1)
  })

  it('隐藏多列时逐列生效，不是只认第一次', async () => {
    const w = await mountList([makeTicket('T-1')])
    const before = columnCount(w)

    vmOf(w).toggleColumn('service')
    vmOf(w).toggleColumn('category')
    vmOf(w).toggleColumn('priority')
    await w.vm.$nextTick()

    expect(columnCount(w)).toBe(before - 3)
  })

  it('卡片视图不受列可见性影响——卡片没有「列」的概念', async () => {
    const w = await mountList([makeTicket('T-1'), makeTicket('T-2')])
    vmOf(w).viewMode = 'card'
    await w.vm.$nextTick()

    vmOf(w).toggleColumn('service')
    await w.vm.$nextTick()

    // 卡片数量与列设置无关。若拆分后把列过滤逻辑误用到卡片上，这里会掉数
    expect(w.findAll('.ticket-card')).toHaveLength(2)
  })
})

describe('KPI 与页面骨架', () => {
  it('渲染 KPI 卡片区', async () => {
    const w = await mountList([makeTicket('T-1')])

    // KPI 数值来自 store.stats，拆分时若把它留在父组件而模板搬走了，
    // 会渲染成空白但不报错
    expect(w.text()).toContain('待处理')
    expect(w.text()).toContain('今日新增')
  })

  it('工单为空时不渲染任何卡片/行，但页面骨架仍在', async () => {
    const w = await mountList([])

    vmOf(w).viewMode = 'card'
    await w.vm.$nextTick()

    expect(w.findAll('.ticket-card')).toHaveLength(0)
    // 空态不等于白屏：筛选栏与 KPI 仍应可见，否则用户没法调整筛选条件
    expect(w.text()).toContain('待处理')
  })
})
