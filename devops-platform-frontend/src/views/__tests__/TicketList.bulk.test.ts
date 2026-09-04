/**
 * TicketList 批量操作的组件级测试。
 *
 * ── 为什么优先测这块 ──────────────────────────────────────────
 * TicketList 2597 行、此前零测试，而本轮排查在它里面发现了三类缺陷：
 * 状态机漂移、事件订阅失效、时间戳错误。这些都是靠人工排查发现的，
 * **没有任何测试能防住复发**。
 *
 * 批量操作是其中风险最高的部分——它一次影响几十张工单，
 * 而「哪些能改、哪些不能改」的判断此前完全绕过了状态机。
 *
 * ── mock 边界 ────────────────────────────────────────────────
 * 只 mock api 层与 UI 提示，store 与组件逻辑全部真实执行。
 * mock 掉 store 等于把被测对象也 mock 了。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent } from 'vue'

import type { FrontendTicket } from '@/api/types/ticket'

// ---- api 层 mock（组件与 store 之外的一切）----
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

const notifyMock = vi.hoisted(() => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))
vi.mock('@/utils/notify', () => notifyMock)

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('element-plus')
  return {
    ...actual,
    ElMessageBox: { confirm: vi.fn(() => Promise.resolve()), prompt: vi.fn() },
  }
})

import { useTicketsStore } from '@/stores/tickets'
import TicketList from '../TicketList.vue'

const makeTicket = (id: string, status: string): FrontendTicket =>
  ({
    id,
    title: `工单 ${id}`,
    description: '描述',
    status,
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
  }) as unknown as FrontendTicket

let router: Router

const mountList = async (tickets: FrontendTicket[]) => {
  api.fetchTickets.mockResolvedValue({
    tickets,
    total: tickets.length,
    totalPages: 1,
    currentPage: 1,
    pageSize: 10,
  })
  api.fetchTicketStats.mockResolvedValue({
    total: tickets.length, todayNew: 0, pending: 0,
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
      // 全局注册的 Element Plus 组件由 unplugin 自动引入，测试环境没有，
      // 逐个 stub 掉——否则满屏 "Failed to resolve component" 会淹没真正的报错
      stubs: {
        'el-table': true,
        'el-table-column': true,
        'el-tag': true,
        'el-tooltip': true,
        TicketFormDialog: true,
        RelativeTime: true,
        ServerPagination: true,
        DataStateBoundary: { template: '<div><slot /></div>' },
      },
      // v-permission 是全局指令（main.ts 注册），测试挂载时需补上。
      // 本文件不测权限，用空实现即可——真实行为由 permission.test.ts 覆盖
      directives: { permission: {} },
    },
  })
  await vi.waitFor(() => expect(api.fetchTickets).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  return wrapper
}

/** 批量选项是 computed，通过实例访问内部状态 */
const vmOf = (w: VueWrapper) =>
  w.vm as unknown as {
    selectedIds: string[]
    bulkStatusOptions: Array<{
      value: string; disabled: boolean; applicable: number; hint: string
    }>
    applyBulkStatus: (s: string) => Promise<void>
    applyBulkAssign: (name: string) => Promise<void>
    bulkAssignOpen: boolean
  }

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('TicketList 批量状态 — 可达性由状态机决定', () => {
  it('选中的都是 pending 时，「已关闭」被置灰（必须先标记解决）', async () => {
    const w = await mountList([makeTicket('T-1', 'pending'), makeTicket('T-2', 'pending')])
    const vm = vmOf(w)
    vm.selectedIds = ['T-1', 'T-2']
    await w.vm.$nextTick()

    const closed = vm.bulkStatusOptions.find(o => o.value === 'closed')!
    expect(closed.disabled).toBe(true)
    expect(closed.hint).toContain('不能变更为')
  })

  it('部分可达时给出准确的 N/M 计数', async () => {
    const w = await mountList([
      makeTicket('T-1', 'resolved'), // 可 → closed
      makeTicket('T-2', 'pending'),  // 不可
      makeTicket('T-3', 'resolved'), // 可
    ])
    const vm = vmOf(w)
    vm.selectedIds = ['T-1', 'T-2', 'T-3']
    await w.vm.$nextTick()

    const closed = vm.bulkStatusOptions.find(o => o.value === 'closed')!
    expect(closed.disabled).toBe(false)
    expect(closed.applicable).toBe(2)
    expect(closed.hint).toContain('2/3')
  })

  it('已是目标状态的不计入 —— 不发无意义的同态请求', async () => {
    const w = await mountList([
      makeTicket('T-1', 'processing'),
      makeTicket('T-2', 'processing'),
    ])
    const vm = vmOf(w)
    vm.selectedIds = ['T-1', 'T-2']
    await w.vm.$nextTick()

    const processing = vm.bulkStatusOptions.find(o => o.value === 'processing')!
    expect(processing.applicable).toBe(0)
    expect(processing.disabled).toBe(true)
  })

  it('void（已作废）是不可逆终态，任何目标都不可达', async () => {
    const w = await mountList([makeTicket('T-1', 'void')])
    const vm = vmOf(w)
    vm.selectedIds = ['T-1']
    await w.vm.$nextTick()

    expect(vm.bulkStatusOptions.every(o => o.disabled)).toBe(true)
  })
})

describe('TicketList 批量状态 — 执行时只对可达项发请求', () => {
  it('跳过状态机不允许的工单，只对可达项调后端', async () => {
    const w = await mountList([
      makeTicket('T-1', 'resolved'),
      makeTicket('T-2', 'pending'),
      makeTicket('T-3', 'resolved'),
    ])
    const store = useTicketsStore()
    const spy = vi.spyOn(store, 'bulkUpdateStatus').mockResolvedValue(2)

    const vm = vmOf(w)
    vm.selectedIds = ['T-1', 'T-2', 'T-3']
    await w.vm.$nextTick()
    await vm.applyBulkStatus('closed')

    // 只传可达的两张，pending 的 T-2 被挡在前端
    expect(spy).toHaveBeenCalledWith(['T-1', 'T-3'], 'closed')
  })

  it('全部不可达时不调后端，直接提示', async () => {
    const w = await mountList([makeTicket('T-1', 'pending')])
    const store = useTicketsStore()
    const spy = vi.spyOn(store, 'bulkUpdateStatus')

    const vm = vmOf(w)
    vm.selectedIds = ['T-1']
    await w.vm.$nextTick()
    await vm.applyBulkStatus('closed')

    expect(spy).not.toHaveBeenCalled()
    expect(notifyMock.notify.warning).toHaveBeenCalledWith(
      expect.stringContaining('都不能变更为')
    )
  })

  it('有跳过项时结果提示如实说明 —— 否则用户以为自己勾的没生效', async () => {
    const w = await mountList([
      makeTicket('T-1', 'resolved'),
      makeTicket('T-2', 'pending'),
    ])
    const store = useTicketsStore()
    vi.spyOn(store, 'bulkUpdateStatus').mockResolvedValue(1)

    const vm = vmOf(w)
    vm.selectedIds = ['T-1', 'T-2']
    await w.vm.$nextTick()
    await vm.applyBulkStatus('closed')

    const allMsgs = [
      ...notifyMock.notify.success.mock.calls,
      ...notifyMock.notify.warning.mock.calls,
    ].map(c => String(c[0])).join('|')
    expect(allMsgs).toContain('1 条因状态不允许已跳过')
  })
})

/**
 * 批量转派的**结果如实上报**。
 *
 * store 层的 bulkAssign 已有测试（tickets.write.test.ts 覆盖了「返回成功数量」），
 * 但那只保证数字对。这里守的是视图层：**那个数字有没有如实传达给用户**。
 *
 * 部分失败是这类操作最常见也最危险的结局——用户勾了 3 张、成功 1 张，
 * 若一律弹「已分配」，他会以为全办完了，剩下两张就此无人认领，
 * 而且不会有任何地方再提醒他。
 */
describe('批量转派 — 部分成功如实上报', () => {
  it('全部成功时提示总数与负责人', async () => {
    const w = await mountList([makeTicket('A', 'pending'), makeTicket('B', 'pending')])
    const vm = vmOf(w)
    api.transferTicket.mockResolvedValue({ id: 'x', version: 2 })

    vm.selectedIds = ['A', 'B']
    await vm.applyBulkAssign('王芳')

    expect(notifyMock.notify.success).toHaveBeenCalledWith(
      expect.stringContaining('2')
    )
    expect(notifyMock.notify.success).toHaveBeenCalledWith(
      expect.stringContaining('王芳')
    )
    expect(notifyMock.notify.warning).not.toHaveBeenCalled()
  })

  it('部分失败时给出 N/M 计数，而不是笼统的「已分配」', async () => {
    const w = await mountList([
      makeTicket('A', 'pending'), makeTicket('B', 'pending'), makeTicket('C', 'pending'),
    ])
    const vm = vmOf(w)
    // 只有 A 成功，B/C 失败
    api.transferTicket
      .mockResolvedValueOnce({ id: 'A', version: 2 })
      .mockRejectedValueOnce(new Error('冲突'))
      .mockRejectedValueOnce(new Error('冲突'))

    vm.selectedIds = ['A', 'B', 'C']
    await vm.applyBulkAssign('王芳')

    // 报「已分配 3 条」会让用户以为全办完，剩下两张就此无人认领
    expect(notifyMock.notify.success).not.toHaveBeenCalled()
    const msg = String(notifyMock.notify.warning.mock.calls.at(-1)?.[0] ?? '')
    expect(msg).toContain('1')
    expect(msg).toContain('3')
  })

  it('全部失败时同样走 warning，不谎报成功', async () => {
    const w = await mountList([makeTicket('A', 'pending')])
    const vm = vmOf(w)
    api.transferTicket.mockRejectedValue(new Error('后端不可用'))

    vm.selectedIds = ['A']
    await vm.applyBulkAssign('王芳')

    expect(notifyMock.notify.success).not.toHaveBeenCalled()
    expect(notifyMock.notify.warning).toHaveBeenCalled()
  })

  it('未选中任何工单时直接返回，不打后端也不提示', async () => {
    const w = await mountList([makeTicket('A', 'pending')])
    const vm = vmOf(w)

    vm.selectedIds = []
    await vm.applyBulkAssign('王芳')

    expect(api.transferTicket).not.toHaveBeenCalled()
    expect(notifyMock.notify.success).not.toHaveBeenCalled()
    expect(notifyMock.notify.warning).not.toHaveBeenCalled()
  })

  it('操作后清空选中并关闭下拉——否则用户会对着已处理的选区再点一次', async () => {
    const w = await mountList([makeTicket('A', 'pending')])
    const vm = vmOf(w)
    api.transferTicket.mockResolvedValue({ id: 'A', version: 2 })

    vm.selectedIds = ['A']
    vm.bulkAssignOpen = true
    await vm.applyBulkAssign('王芳')

    expect(vm.selectedIds).toEqual([])
    expect(vm.bulkAssignOpen).toBe(false)
  })

  it('转派后重新拉取列表——筛选含负责人时改完的工单应移出结果集', async () => {
    const w = await mountList([makeTicket('A', 'pending')])
    const vm = vmOf(w)
    api.transferTicket.mockResolvedValue({ id: 'A', version: 2 })
    api.fetchTickets.mockClear()

    vm.selectedIds = ['A']
    await vm.applyBulkAssign('王芳')

    // 不刷新还会导致 version 过期，后续编辑误报「数据已被他人修改」
    expect(api.fetchTickets).toHaveBeenCalled()
  })
})
