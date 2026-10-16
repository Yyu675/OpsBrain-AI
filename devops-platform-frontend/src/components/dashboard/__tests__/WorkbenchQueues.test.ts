/**
 * WorkbenchQueues（首页工作台行动队列区）测试。
 *
 * 保护的行为：
 * - 队列语义：待处理工单按优先级排序拉取（行动顺序错 = 值班先处理低优先单）
 * - 待审批是 admin 专属：非管理员连请求都不该发（端点限 ADMIN，发了只收获 403）
 * - 空态诚实：没有工单/审批时显示「没有」，而不是渲染空列表让人以为加载失败
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

const routerPush = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush }),
  useRoute: () => ({ query: {}, params: {} }),
  RouterLink: { template: '<a><slot /></a>', props: ['to'] },
}))

const api = vi.hoisted(() => ({
  fetchTickets: vi.fn(),
  listApprovals: vi.fn(),
}))
vi.mock('@/api/tickets', () => ({ fetchTickets: api.fetchTickets }))
vi.mock('@/api/approval', () => ({ listApprovals: api.listApprovals }))

const appStore = vi.hoisted(() => ({
  isAuthenticated: true,
  isAdmin: true,
  hasRole(roles?: string[]) {
    if (!roles || roles.length === 0) return true
    return roles.includes('admin') && this.isAdmin
  },
}))
vi.mock('@/stores/app', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/app')
  return { ...actual, useAppStore: () => appStore }
})

// stores/tickets 提供 getStatusLabel/getPriorityLabel——轻量纯函数，用真实实现
import WorkbenchQueues from '@/components/dashboard/WorkbenchQueues.vue'

const ticket = (id: string, priority: string) => ({
  id, title: `工单 ${id}`, status: 'pending', priority,
  assignee: '张明', creator: 'alert-bot', createdAt: '2026-09-27 09:00:00',
  updatedAt: '2026-09-27 09:00:00', service: 'order-service', category: '其他',
  tags: [], sla: '4h/8h', slaProgress: 10, slaBreached: false,
  slaRemainingMinutes: 200, firstResponseState: 'WAITING',
  firstResponseMinutes: null, responseRemainingMinutes: 30,
  firstResponder: null, escalateReason: null,
})

const mountQueues = async (opts: { admin?: boolean; tickets?: unknown[] } = {}) => {
  appStore.isAdmin = opts.admin ?? true
  const tickets = (opts.tickets ?? [ticket('TKT-1', 'P0'), ticket('TKT-2', 'P2')]) as ReturnType<typeof ticket>[]
  api.fetchTickets.mockResolvedValue({
    tickets,
    total: tickets.length, page: 1, size: 6, totalPages: tickets.length ? 1 : 0,
  })
  api.listApprovals.mockResolvedValue({
    items: [{
      id: 9, actionType: 'healing', toolName: null, riskLevel: 'HIGH',
      summary: '重启 order-api', payload: null, requester: 'alert-bot',
      traceId: null, sessionId: null, status: 'PENDING',
      approver: null, decidedAt: null, decisionReason: null,
      expiresAt: null, executedAt: null, executeResult: null,
      createTime: '2026-09-27 09:00:00', updateTime: '2026-09-27 09:00:00',
    }],
    total: 1, page: 1, size: 5, totalPages: 1,
  })

  const wrapper = mount(WorkbenchQueues, {
    global: {
      plugins: [
        [VueQueryPlugin, {
          queryClient: new QueryClient({
            defaultOptions: { queries: { retry: false, staleTime: 0, gcTime: 0 } },
          }),
        }],
      ],
      stubs: { RelativeTime: { template: '<span class="stub-time" />', props: ['value'] } },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('待处理工单队列', () => {
  it('按优先级升序拉取待处理工单（P0 在前）', async () => {
    await mountQueues()

    expect(api.fetchTickets).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'pending', sortBy: 'priority', sortAsc: true })
    )
  })

  it('渲染工单号、标题与优先级徽章', async () => {
    const w = await mountQueues()

    expect(w.text()).toContain('TKT-1')
    expect(w.text()).toContain('工单 TKT-2')
    expect(w.findAll('.queue-row')).toHaveLength(3) // 2 工单 + 1 审批
  })

  it('点行跳转工单详情', async () => {
    const w = await mountQueues()

    await w.findAll('.queue-row')[0].trigger('click')
    expect(routerPush).toHaveBeenCalledWith('/tickets/TKT-1')
  })

  it('无待处理工单时显示诚实空态而非空列表', async () => {
    const w = await mountQueues({ tickets: [] })

    expect(w.text()).toContain('当前无待处理工单')
  })
})

describe('待审批动作（admin 专属）', () => {
  it('管理员可见且拉取 PENDING 队列', async () => {
    const w = await mountQueues({ admin: true })

    expect(api.listApprovals).toHaveBeenCalledWith('PENDING', 1, 5)
    expect(w.text()).toContain('重启 order-api')
  })

  it('非管理员不渲染审批卡且不发请求', async () => {
    const w = await mountQueues({ admin: false })

    expect(api.listApprovals).not.toHaveBeenCalled()
    expect(w.text()).not.toContain('待审批动作')
  })
})
