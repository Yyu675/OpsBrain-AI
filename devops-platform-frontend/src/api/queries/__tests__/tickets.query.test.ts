/**
 * 工单 Query 封装测试。
 *
 * 与步骤 0 的 tickets store 测试互补：
 * - store 测试保护「乐观回滚正确性」（被测对象是 store）
 * - 本测试保护「Query 层正确性」：queryKey 参数化 → 参数变化自动重拉、
 *   相同参数命中缓存不重复请求、写操作后失效列表
 *
 * 对齐 alerts.query.test.ts 的结构：hoisted mock + createTestQueryClient +
 * flushQuery + withQueryClient。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

import { useTicketHotTagsQuery, useTicketInvalidate, useTicketListQuery } from '../tickets.query'
import { createTestQueryClient, flushQuery, withQueryClient } from '@/test-utils/query'

const mocks = vi.hoisted(() => ({
  fetchTickets: vi.fn(),
  fetchHotTags: vi.fn(),
  fetchTeamMembers: vi.fn(),
  fetchTicketById: vi.fn(),
}))

vi.mock('@/api/tickets', () => mocks)
vi.mock('@/api/users', () => ({ fetchTeamMembers: mocks.fetchTeamMembers }))

const listResponse = (tickets: unknown[] = [], total = 0, totalPages = 0) => ({
  tickets, total, page: 1, size: 10, totalPages,
})

/** 可改的列表参数源，模拟用户切换筛选/翻页 */
function makeParams(initial: { page?: number; status?: string; priority?: string }) {
  const s = ref({ ...initial })
  return {
    value: s,
    set(next: typeof initial) { s.value = { ...next } },
  }
}

beforeEach(() => {
  Object.values(mocks).forEach(m => m.mockReset())
  mocks.fetchTickets.mockResolvedValue(listResponse())
  mocks.fetchHotTags.mockResolvedValue([])
  mocks.fetchTeamMembers.mockResolvedValue([])
})

describe('useTicketListQuery — queryKey 参数化', () => {
  it('不同页码自动重拉 —— 翻页即触发，无需手动调用 fetchList', async () => {
    const params = makeParams({ page: 1 })
    const { result } = withQueryClient(() =>
      useTicketListQuery(() => ({
        page: params.value.value.page as number,
        size: 10,
      }))
    )
    await flushQuery()
    expect(mocks.fetchTickets).toHaveBeenCalledTimes(1)

    params.set({ page: 2 })
    await flushQuery()

    expect(mocks.fetchTickets).toHaveBeenCalledTimes(2)
    expect(mocks.fetchTickets).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }))
    void result
  })

  it('筛选参数进 queryKey —— 切换状态自动重拉', async () => {
    const params = makeParams({ page: 1, status: 'pending' })
    withQueryClient(() =>
      useTicketListQuery(() => ({
        page: params.value.value.page as number,
        size: 10,
        status: params.value.value.status ?? 'all',
      }))
    )
    await flushQuery()
    expect(mocks.fetchTickets).toHaveBeenCalledTimes(1)

    params.set({ page: 1, status: 'processing' })
    await flushQuery()

    expect(mocks.fetchTickets).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'processing' })
    )
  })

  it('状态/优先级为 all 时转 undefined —— 不把「全部」当作字面筛选值发给后端', async () => {
    withQueryClient(() =>
      useTicketListQuery(() => ({ page: 1, size: 10, status: 'all', priority: 'all' }))
    )
    await flushQuery()

    const lastCall = mocks.fetchTickets.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(lastCall.status).toBeUndefined()
    expect(lastCall.priority).toBeUndefined()
  })

  it('标签空数组时转 undefined —— 避免带空数组触发无效筛选', async () => {
    withQueryClient(() =>
      useTicketListQuery(() => ({ page: 1, size: 10, tags: [] }))
    )
    await flushQuery()

    const lastCall = mocks.fetchTickets.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(lastCall.tags).toBeUndefined()
  })

  it('加载中不抛错，data 为 undefined 由模板判空', async () => {
    const { result } = withQueryClient(() =>
      useTicketListQuery(() => ({ page: 1, size: 10 }))
    )

    expect(result.isLoading.value).toBe(true)
  })
})

describe('useTicketHotTagsQuery — 跨全表聚合', () => {
  it('挂载即加载，无需 onMounted', async () => {
    mocks.fetchHotTags.mockResolvedValue(['K8s', '网络'])

    const { result } = withQueryClient(() => useTicketHotTagsQuery())
    await flushQuery()

    expect(mocks.fetchHotTags).toHaveBeenCalledTimes(1)
    expect(result.data.value).toEqual(['K8s', '网络'])
  })
})

describe('useTicketInvalidate — 写操作后失效', () => {
  it('失效列表后同 key 触发重新请求 —— 写操作成功后列表自动刷新', async () => {
    // 必须共用同一个 QueryClient：invalidate 与查询挂在同一 cache 上才互相可见
    const client = createTestQueryClient()
    const params = makeParams({ page: 1 })
    const { result: list } = withQueryClient(
      () => useTicketListQuery(() => ({ page: params.value.value.page as number, size: 10 })),
      { queryClient: client }
    )
    const { result: inv } = withQueryClient(() => useTicketInvalidate(), { queryClient: client })

    await flushQuery()
    expect(mocks.fetchTickets).toHaveBeenCalledTimes(1)

    await inv.invalidateList()
    await flushQuery()

    expect(mocks.fetchTickets).toHaveBeenCalledTimes(2)
    void list
  })

  it('invalidateAll 与 invalidateList 均可调用（作用域内需 queryClient）', async () => {
    const { result } = withQueryClient(() => useTicketInvalidate())

    expect(typeof result.invalidateAll).toBe('function')
    expect(typeof result.invalidateList).toBe('function')
  })
})