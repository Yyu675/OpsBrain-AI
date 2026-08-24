/**
 * 告警 Query 封装测试。
 *
 * 锁定引入 TanStack Query 的核心收益：
 * - 筛选/页码进 queryKey → **参数变化自动重拉**（不需在每个 change 里手调 fetchList）
 * - 写操作 onSuccess → invalidateQueries → 相关查询自动失效
 *   （消除 6.17「写操作后忘记重拉」的成因）
 * - 相同参数命中缓存，不重复请求
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

import { useAlertDetailQuery, useAlertListQuery, useAlertMutations } from '../alerts.query'
import { createTestQueryClient, flushQuery, withQueryClient } from '@/test-utils/query'
import type { Alert, AlertStatus } from '@/api/types'

const mocks = vi.hoisted(() => ({
  fetchAlerts: vi.fn(),
  fetchAlertById: vi.fn(),
  acknowledgeAlert: vi.fn(),
  resolveAlert: vi.fn(),
}))

vi.mock('@/api/alerts', () => mocks)
vi.mock('@/utils/notify', () => ({ handleServerError: vi.fn() }))

function alert(id: number, status: AlertStatus = 'FIRING'): Alert {
  return { id, alertName: 'CPUHigh', level: 'P1', title: 'CPU 过高', status } as Alert
}

function listResponse(alerts: Alert[], total = alerts.length, totalPages = 1) {
  return { alerts, total, totalPages }
}

beforeEach(() => {
  Object.values(mocks).forEach(m => m.mockReset())
  mocks.fetchAlerts.mockResolvedValue(listResponse([]))
  mocks.fetchAlertById.mockResolvedValue(null)
})

describe('useAlertListQuery — 首次加载', () => {
  it('挂载即自动拉取，无需 onMounted', async () => {
    mocks.fetchAlerts.mockResolvedValue(listResponse([alert(1)]))

    const { result } = withQueryClient(() =>
      useAlertListQuery({
        page: ref(1), size: ref(10), status: ref(''), level: ref(''),
      })
    )
    await flushQuery()

    expect(mocks.fetchAlerts).toHaveBeenCalledTimes(1)
    expect(result.alerts.value).toHaveLength(1)
  })

  it('加载中 alerts 为空数组而非 undefined —— 模板无需到处判空', () => {
    const { result } = withQueryClient(() =>
      useAlertListQuery({
        page: ref(1), size: ref(10), status: ref(''), level: ref(''),
      })
    )

    expect(result.alerts.value).toEqual([])
    expect(result.total.value).toBe(0)
    expect(result.totalPages.value).toBe(0)
  })

  it('从响应中派生 total 与 totalPages', async () => {
    mocks.fetchAlerts.mockResolvedValue(listResponse([alert(1)], 42, 5))

    const { result } = withQueryClient(() =>
      useAlertListQuery({
        page: ref(1), size: ref(10), status: ref(''), level: ref(''),
      })
    )
    await flushQuery()

    expect(result.total.value).toBe(42)
    expect(result.totalPages.value).toBe(5)
  })
})

describe('useAlertListQuery — 空串筛选转 undefined', () => {
  it('未选筛选时不把空串传给后端 —— 后端可能把空串当作有效值而查不到数据', async () => {
    withQueryClient(() =>
      useAlertListQuery({
        page: ref(1), size: ref(10), status: ref(''), level: ref(''),
      })
    )
    await flushQuery()

    expect(mocks.fetchAlerts).toHaveBeenCalledWith({
      page: 1, size: 10, status: undefined, level: undefined,
    })
  })

  it('已选筛选时原样传递', async () => {
    withQueryClient(() =>
      useAlertListQuery({
        page: ref(1), size: ref(10), status: ref<AlertStatus | ''>('FIRING'), level: ref('P0'),
      })
    )
    await flushQuery()

    expect(mocks.fetchAlerts).toHaveBeenCalledWith({
      page: 1, size: 10, status: 'FIRING', level: 'P0',
    })
  })
})

describe('useAlertListQuery — 参数变化自动重拉（核心收益）', () => {
  it('页码变化触发重拉 —— 不需要在 goToPage 里手动调 fetchList', async () => {
    const page = ref(1)
    withQueryClient(() =>
      useAlertListQuery({ page, size: ref(10), status: ref(''), level: ref('') })
    )
    await flushQuery()
    expect(mocks.fetchAlerts).toHaveBeenCalledTimes(1)

    page.value = 2
    await flushQuery()

    expect(mocks.fetchAlerts).toHaveBeenCalledTimes(2)
    expect(mocks.fetchAlerts).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 2 })
    )
  })

  it('筛选变化触发重拉 —— 漏掉手动刷新会出现「改了筛选但列表没变」', async () => {
    const status = ref<AlertStatus | ''>('')
    withQueryClient(() =>
      useAlertListQuery({ page: ref(1), size: ref(10), status, level: ref('') })
    )
    await flushQuery()

    status.value = 'RESOLVED'
    await flushQuery()

    expect(mocks.fetchAlerts).toHaveBeenCalledTimes(2)
    expect(mocks.fetchAlerts).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'RESOLVED' })
    )
  })

  it('参数改回原值时命中缓存，不重复请求', async () => {
    const queryClient = createTestQueryClient()
    // 本用例需要缓存，覆盖测试客户端的 gcTime=0
    queryClient.setDefaultOptions({
      queries: { retry: false, staleTime: 60_000, gcTime: 60_000 },
    })

    const page = ref(1)
    withQueryClient(
      () => useAlertListQuery({ page, size: ref(10), status: ref(''), level: ref('') }),
      { queryClient }
    )
    await flushQuery()

    page.value = 2
    await flushQuery()
    page.value = 1
    await flushQuery()

    // 第 1 页的结果仍在缓存内（staleTime 未过），只发了 2 次请求
    expect(mocks.fetchAlerts).toHaveBeenCalledTimes(2)
  })
})

describe('useAlertListQuery — 错误处理', () => {
  it('拉取失败时 error 有值、alerts 保持空数组', async () => {
    const err = new Error('网络不通')
    mocks.fetchAlerts.mockRejectedValue(err)

    const { result } = withQueryClient(() =>
      useAlertListQuery({ page: ref(1), size: ref(10), status: ref(''), level: ref('') })
    )
    await flushQuery(5)

    expect(result.error.value).toBeTruthy()
    expect(result.alerts.value).toEqual([])
  })

  it('查询失败不外抛 —— Query 内部捕获存入 error，不会触发 unhandledrejection', async () => {
    mocks.fetchAlerts.mockRejectedValue(new Error('x'))

    expect(() => {
      withQueryClient(() =>
        useAlertListQuery({ page: ref(1), size: ref(10), status: ref(''), level: ref('') })
      )
    }).not.toThrow()
    await flushQuery(5)
  })
})

describe('useAlertDetailQuery', () => {
  it('拉取指定告警', async () => {
    mocks.fetchAlertById.mockResolvedValue(alert(7))

    const { result } = withQueryClient(() => useAlertDetailQuery(ref('7')))
    await flushQuery()

    expect(mocks.fetchAlertById).toHaveBeenCalledWith('7')
    expect(result.data.value).toMatchObject({ id: 7 })
  })

  it('id 为空时不发请求 —— 路由参数缺失时不该打后端', async () => {
    withQueryClient(() => useAlertDetailQuery(ref('')))
    await flushQuery()

    expect(mocks.fetchAlertById).not.toHaveBeenCalled()
  })

  it('告警不存在时 data 为 null 而非报错 —— 视图据此判 notFound（6.18）', async () => {
    mocks.fetchAlertById.mockResolvedValue(null)

    const { result } = withQueryClient(() => useAlertDetailQuery(ref('999')))
    await flushQuery()

    expect(result.data.value).toBeNull()
    expect(result.error.value).toBeNull()
  })

  it('切换 id 触发重拉', async () => {
    const id = ref('1')
    withQueryClient(() => useAlertDetailQuery(id))
    await flushQuery()

    id.value = '2'
    await flushQuery()

    expect(mocks.fetchAlertById).toHaveBeenCalledTimes(2)
    expect(mocks.fetchAlertById).toHaveBeenLastCalledWith('2')
  })
})

describe('useAlertMutations — 写操作后自动失效（核心收益）', () => {
  it('确认告警成功后列表自动重拉 —— 不需要手动 await fetchList', async () => {
    mocks.fetchAlerts.mockResolvedValue(listResponse([alert(1)]))
    mocks.acknowledgeAlert.mockResolvedValue(alert(1, 'ACKNOWLEDGED'))

    const queryClient = createTestQueryClient()
    const { result } = withQueryClient(
      () => ({
        list: useAlertListQuery({
          page: ref(1), size: ref(10), status: ref(''), level: ref(''),
        }),
        mutations: useAlertMutations(),
      }),
      { queryClient }
    )
    await flushQuery()
    const callsBefore = mocks.fetchAlerts.mock.calls.length

    await result.mutations.acknowledge.mutateAsync(1)
    await flushQuery(5)

    expect(mocks.fetchAlerts.mock.calls.length).toBeGreaterThan(callsBefore)
  })

  it('标记恢复成功后同样自动失效', async () => {
    mocks.fetchAlerts.mockResolvedValue(listResponse([alert(1)]))
    mocks.resolveAlert.mockResolvedValue(alert(1, 'RESOLVED'))

    const queryClient = createTestQueryClient()
    const { result } = withQueryClient(
      () => ({
        list: useAlertListQuery({
          page: ref(1), size: ref(10), status: ref(''), level: ref(''),
        }),
        mutations: useAlertMutations(),
      }),
      { queryClient }
    )
    await flushQuery()
    const callsBefore = mocks.fetchAlerts.mock.calls.length

    await result.mutations.resolve.mutateAsync(1)
    await flushQuery(5)

    expect(mocks.fetchAlerts.mock.calls.length).toBeGreaterThan(callsBefore)
  })

  it('写操作失败时不失效缓存 —— 失败不该触发无谓的重拉', async () => {
    mocks.fetchAlerts.mockResolvedValue(listResponse([alert(1)]))
    mocks.acknowledgeAlert.mockRejectedValue(new Error('403'))

    const queryClient = createTestQueryClient()
    const { result } = withQueryClient(
      () => ({
        list: useAlertListQuery({
          page: ref(1), size: ref(10), status: ref(''), level: ref(''),
        }),
        mutations: useAlertMutations(),
      }),
      { queryClient }
    )
    await flushQuery()
    const callsBefore = mocks.fetchAlerts.mock.calls.length

    await expect(result.mutations.acknowledge.mutateAsync(1)).rejects.toThrow()
    await flushQuery(3)

    expect(mocks.fetchAlerts.mock.calls.length).toBe(callsBefore)
  })

  it('写操作失败时 mutateAsync 抛错 —— 调用方可据此跳过成功提示', async () => {
    mocks.acknowledgeAlert.mockRejectedValue(new Error('403'))

    const { result } = withQueryClient(() => useAlertMutations())
    await expect(result.acknowledge.mutateAsync(1)).rejects.toThrow()
  })
})
