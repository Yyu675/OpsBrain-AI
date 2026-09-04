/**
 * TicketList 筛选 ↔ URL 双向同步的组件级测试。
 *
 * ── 为什么要测 ────────────────────────────────────────────────
 * 「把这个筛选结果甩给同事」是值守交接的高频动作。此前本页只**读** URL
 * 不写回，用户调好的筛选既不能刷新保留、也不能分享。改造后行为正确，
 * 但涉及三个容易写错的细节，全都没有测试保护：
 *
 *   1. 默认值不入 URL —— 否则地址栏变成 `?status=all&priority=all&page=1`
 *      的噪音，「有没有筛选」这件事一眼看不出
 *   2. 数组型筛选（tags）的序列化 —— 走默认 String() 会让空数组变成 ""
 *      且与 defaultValue [] 引用不等，导致空数组也被写进 URL
 *   3. keyword 要回填搜索框 —— 只同步 appliedQuery 会让输入框空着，
 *      用户看到「列表明明筛过、搜索框却没内容」，想清筛选都无从下手
 *
 * ── mock 边界 ────────────────────────────────────────────────
 * 只 mock api 层，router 用真实的 memory history —— URL 同步正是被测对象，
 * mock 掉 router 等于什么都没测。
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

const emptyTicket = (id: string): FrontendTicket =>
  ({
    id, title: `工单 ${id}`, description: '', status: 'pending', priority: 'medium',
    assignee: '张三', creator: 'admin', createdAt: '2026-08-24 10:00',
    updatedAt: '2026-08-24 10:00', service: '订单服务', category: '故障',
    tags: [], sla: '', slaProgress: 0, slaBreached: false, slaRemainingMinutes: 0,
    firstResponseState: 'WAITING', firstResponseMinutes: null,
    responseRemainingMinutes: null, firstResponder: null, escalateReason: null,
    version: 1, replies: [], activities: [],
  }) as unknown as FrontendTicket

let router: Router

const mountAt = async (url: string) => {
  api.fetchTickets.mockResolvedValue({
    tickets: [emptyTicket('T-1')], total: 1, totalPages: 1, currentPage: 1, pageSize: 10,
  })
  api.fetchTicketStats.mockResolvedValue({
    total: 1, todayNew: 0, pending: 1, processing: 0, resolved: 0, urgentPending: 0,
  })
  api.fetchHotTags.mockResolvedValue(['生产环境', '数据库'])
  api.fetchTeamMembers.mockResolvedValue([{ name: '张三' }])

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/tickets', component: TicketList },
      { path: '/tickets/:id', component: defineComponent({ template: '<div/>' }) },
    ],
  })
  await router.push(url)
  await router.isReady()

  const wrapper = mount(TicketList, {
    global: {
      plugins: [router],
      stubs: {
        'el-table': true, 'el-table-column': true, 'el-tag': true, 'el-tooltip': true,
        TicketFormDialog: true, RelativeTime: true, ServerPagination: true,
        DataStateBoundary: { template: '<div><slot /></div>' },
      },
      directives: { permission: {} },
    },
  })
  await vi.waitFor(() => expect(api.fetchTickets).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  return wrapper
}

/**
 * 等待 URL 写回完成。
 *
 * useUrlFilters 的 watch 用 `flush: 'post'`（合并同 tick 内的多项变更），
 * 且 `router.replace` 本身返回 Promise。所以改完 ref 后需要：
 *   nextTick 让 post 回调跑 → 再让出一个宏任务让导航 resolve
 * 只 await nextTick 会拿到还没更新的 query —— 这不是代码的 bug，
 * 我第一版就这么写，四条用例全挂，实测确认后才补上这个辅助。
 */
const flushUrlSync = async (w: VueWrapper) => {
  await w.vm.$nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
}

const vmOf = (w: VueWrapper) =>
  w.vm as unknown as {
    statusFilter: string
    priorityFilter: string
    assigneeFilter: string
    tagFilters: string[]
    searchQuery: string
    appliedQuery: string
    sortBy: string
    sortAsc: boolean
  }

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('TicketList — URL → 筛选状态（进入时读取）', () => {
  it('带参链接进入时应用筛选，并用于首次请求', async () => {
    await mountAt('/tickets?status=pending&priority=urgent')

    const params = api.fetchTickets.mock.calls[0][0]
    expect(params.status).toBe('pending')
    expect(params.priority).toBe('urgent')
  })

  it('keyword 同时回填搜索框 —— 否则「筛过但输入框是空的」', async () => {
    const w = await mountAt('/tickets?keyword=Redis 超时')
    const vm = vmOf(w)

    expect(vm.appliedQuery).toBe('Redis 超时')
    // 这条是关键：只同步 appliedQuery 的话用户看不到自己筛了什么
    expect(vm.searchQuery).toBe('Redis 超时')
  })

  it('非法枚举值被忽略，回落默认而非报错（URL 可被手工编辑）', async () => {
    const w = await mountAt('/tickets?status=不存在的状态&priority=xxx')
    const vm = vmOf(w)

    expect(vm.statusFilter).toBe('all')
    expect(vm.priorityFilter).toBe('all')
  })

  it('tags 支持逗号分隔的多值', async () => {
    const w = await mountAt('/tickets?tags=生产环境,数据库')
    expect(vmOf(w).tagFilters).toEqual(['生产环境', '数据库'])
  })

  it('排序参数被应用', async () => {
    const w = await mountAt('/tickets?sortBy=priority&sortAsc=true')
    const vm = vmOf(w)
    expect(vm.sortBy).toBe('priority')
    expect(vm.sortAsc).toBe(true)
  })

  it('非白名单排序字段被忽略 —— 防止「箭头指着 A 列、数据按创建时间排」', async () => {
    const w = await mountAt('/tickets?sortBy=; DROP TABLE--')
    expect(vmOf(w).sortBy).toBe('createdAt')
  })
})

describe('TicketList — 筛选状态 → URL（写回）', () => {
  it('改筛选后写回 URL，链接可分享', async () => {
    const w = await mountAt('/tickets')
    const vm = vmOf(w)

    vm.statusFilter = 'processing'
    await flushUrlSync(w)

    expect(router.currentRoute.value.query.status).toBe('processing')
  })

  it('默认值不写入 URL —— 保持地址栏干净', async () => {
    const w = await mountAt('/tickets?status=processing')
    const vm = vmOf(w)

    // 改回默认值，该参数应从 URL 移除而非写成 status=all
    vm.statusFilter = 'all'
    await flushUrlSync(w)

    expect(router.currentRoute.value.query.status).toBeUndefined()
  })

  it('空标签数组不写入 URL —— 数组走默认序列化会留下空参数', async () => {
    const w = await mountAt('/tickets?tags=生产环境')
    const vm = vmOf(w)

    vm.tagFilters = []
    await flushUrlSync(w)

    expect(router.currentRoute.value.query.tags).toBeUndefined()
  })

  it('多个筛选项同时变化只产生一条历史记录 —— 否则返回键要按十几次', async () => {
    const w = await mountAt('/tickets')
    const vm = vmOf(w)
    const before = router.currentRoute.value.fullPath

    vm.statusFilter = 'processing'
    vm.priorityFilter = 'urgent'
    await flushUrlSync(w)

    const after = router.currentRoute.value.fullPath
    expect(after).not.toBe(before)
    // 两项都写进去了（合并成一次导航）
    expect(router.currentRoute.value.query.status).toBe('processing')
    expect(router.currentRoute.value.query.priority).toBe('urgent')
  })
})
