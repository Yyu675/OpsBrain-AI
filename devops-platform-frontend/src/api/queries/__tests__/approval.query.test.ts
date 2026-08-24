/**
 * 审批 Query 封装测试。
 *
 * 重点锁定本次迁移替掉的**手工事件通路**：
 * 此前审批决策后要 `ticketEvents.emit('approval-decided')`，导航栏订阅该事件
 * 再重新拉待审数量——发布方与订阅方分离在两个文件，漏一处角标就停在旧数字，
 * 且没有编译期保护。现在角标与列表共用 approvalKeys 前缀，
 * 决策后 invalidate 一次两者都更新。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

import {
  useApprovalListQuery,
  useApprovalMutations,
  usePendingApprovalCountQuery,
} from '../approval.query'
import { createTestQueryClient, flushQuery, withQueryClient } from '@/test-utils/query'
import type { ApprovalRequest } from '@/api/approval'

const mocks = vi.hoisted(() => ({
  listApprovals: vi.fn(),
  pendingCount: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}))

vi.mock('@/api/approval', () => mocks)
vi.mock('@/utils/notify', () => ({ handleServerError: vi.fn() }))

function approval(id: number, status = 'PENDING'): ApprovalRequest {
  return {
    id,
    status,
    summary: '创建高优工单',
    actionType: 'CREATE_TICKET',
    riskLevel: 'HIGH_RISK_EXECUTION',
    requester: 'ai-agent',
  } as ApprovalRequest
}

beforeEach(() => {
  Object.values(mocks).forEach(m => m.mockReset())
  mocks.listApprovals.mockResolvedValue({ items: [], total: 0 })
  mocks.pendingCount.mockResolvedValue(0)
})

describe('useApprovalListQuery', () => {
  it('挂载即拉取当前 tab 的列表', async () => {
    mocks.listApprovals.mockResolvedValue({ items: [approval(1)], total: 1 })

    const { result } = withQueryClient(() => useApprovalListQuery(ref('PENDING')))
    await flushQuery()

    expect(mocks.listApprovals).toHaveBeenCalledWith('PENDING', 1, 50)
    expect(result.items.value).toHaveLength(1)
    expect(result.total.value).toBe(1)
  })

  it('切 tab 自动重拉 —— 不需要在 switchTab 里手调 fetchList', async () => {
    const tab = ref('PENDING')
    withQueryClient(() => useApprovalListQuery(tab))
    await flushQuery()
    expect(mocks.listApprovals).toHaveBeenCalledTimes(1)

    tab.value = 'REJECTED'
    await flushQuery()

    expect(mocks.listApprovals).toHaveBeenCalledTimes(2)
    expect(mocks.listApprovals).toHaveBeenLastCalledWith('REJECTED', 1, 50)
  })

  it('加载中 items 为空数组而非 undefined', () => {
    const { result } = withQueryClient(() => useApprovalListQuery(ref('PENDING')))
    expect(result.items.value).toEqual([])
    expect(result.total.value).toBe(0)
  })

  it('拉取失败时 error 有值、items 保持空数组', async () => {
    mocks.listApprovals.mockRejectedValue(new Error('403'))

    const { result } = withQueryClient(() => useApprovalListQuery(ref('PENDING')))
    await flushQuery(5)

    expect(result.error.value).toBeTruthy()
    expect(result.items.value).toEqual([])
  })
})

describe('usePendingApprovalCountQuery — 按角色启用', () => {
  it('启用时拉取待审数量', async () => {
    mocks.pendingCount.mockResolvedValue(3)

    const { result } = withQueryClient(() => usePendingApprovalCountQuery(ref(true)))
    await flushQuery()

    expect(result.count.value).toBe(3)
  })

  it('未启用（非管理员）时不发请求 —— 该端点限 ADMIN，请求只会得到 403', async () => {
    withQueryClient(() => usePendingApprovalCountQuery(ref(false)))
    await flushQuery()

    expect(mocks.pendingCount).not.toHaveBeenCalled()
  })

  it('从未启用变为启用时开始拉取 —— 登录后或角色提升后角标应出现', async () => {
    const enabled = ref(false)
    withQueryClient(() => usePendingApprovalCountQuery(enabled))
    await flushQuery()
    expect(mocks.pendingCount).not.toHaveBeenCalled()

    enabled.value = true
    await flushQuery()

    expect(mocks.pendingCount).toHaveBeenCalledTimes(1)
  })

  it('拉取失败时降级为 0（不显示角标），不弹错误', async () => {
    mocks.pendingCount.mockRejectedValue(new Error('500'))

    const { result } = withQueryClient(() => usePendingApprovalCountQuery(ref(true)))
    await flushQuery(5)

    expect(result.count.value).toBe(0)
  })

  it('待审数为 0 时 count 为 0 —— 模板据此隐藏角标', async () => {
    mocks.pendingCount.mockResolvedValue(0)

    const { result } = withQueryClient(() => usePendingApprovalCountQuery(ref(true)))
    await flushQuery()

    expect(result.count.value).toBe(0)
  })
})

describe('useApprovalMutations — 决策后列表与角标同时失效（替代自定义事件）', () => {
  /** 同时挂载列表与角标，模拟真实页面 + 导航栏的组合 */
  function setupBoth() {
    const queryClient = createTestQueryClient()
    return withQueryClient(
      () => ({
        list: useApprovalListQuery(ref('PENDING')),
        badge: usePendingApprovalCountQuery(ref(true)),
        mutations: useApprovalMutations(),
      }),
      { queryClient }
    )
  }

  it('批准后列表重拉', async () => {
    mocks.listApprovals.mockResolvedValue({ items: [approval(1)], total: 1 })
    mocks.approveApproval.mockResolvedValue(approval(1, 'EXECUTED'))

    const { result } = setupBoth()
    await flushQuery()
    const before = mocks.listApprovals.mock.calls.length

    await result.mutations.approve.mutateAsync(1)
    await flushQuery(5)

    expect(mocks.listApprovals.mock.calls.length).toBeGreaterThan(before)
  })

  it('批准后待审角标也重拉 —— 这是此前靠 emit/订阅手工维护的部分', async () => {
    mocks.pendingCount.mockResolvedValue(3)
    mocks.approveApproval.mockResolvedValue(approval(1, 'EXECUTED'))

    const { result } = setupBoth()
    await flushQuery()
    const before = mocks.pendingCount.mock.calls.length

    await result.mutations.approve.mutateAsync(1)
    await flushQuery(5)

    expect(mocks.pendingCount.mock.calls.length).toBeGreaterThan(before)
  })

  it('驳回后列表与角标同样重拉', async () => {
    mocks.pendingCount.mockResolvedValue(2)
    mocks.rejectApproval.mockResolvedValue(approval(1, 'REJECTED'))

    const { result } = setupBoth()
    await flushQuery()
    const listBefore = mocks.listApprovals.mock.calls.length
    const badgeBefore = mocks.pendingCount.mock.calls.length

    await result.mutations.reject.mutateAsync({ id: 1, reason: '风险过高' })
    await flushQuery(5)

    expect(mocks.listApprovals.mock.calls.length).toBeGreaterThan(listBefore)
    expect(mocks.pendingCount.mock.calls.length).toBeGreaterThan(badgeBefore)
  })

  it('驳回时把理由传给后端 —— 理由必填且记入审计（6.57）', async () => {
    mocks.rejectApproval.mockResolvedValue(approval(1, 'REJECTED'))

    const { result } = withQueryClient(() => useApprovalMutations())
    await result.reject.mutateAsync({ id: 7, reason: '影响面过大' })

    expect(mocks.rejectApproval).toHaveBeenCalledWith(7, '影响面过大')
  })

  it('决策失败时不失效缓存 —— 失败不该触发无谓重拉', async () => {
    mocks.approveApproval.mockRejectedValue(new Error('403'))

    const { result } = setupBoth()
    await flushQuery()
    const before = mocks.listApprovals.mock.calls.length

    await expect(result.mutations.approve.mutateAsync(1)).rejects.toThrow()
    await flushQuery(3)

    expect(mocks.listApprovals.mock.calls.length).toBe(before)
  })

  it('批准返回 EXECUTE_FAILED 时 mutateAsync 仍算成功 —— 批准本身成功了，执行失败由调用方分级提示', async () => {
    mocks.approveApproval.mockResolvedValue(approval(1, 'EXECUTE_FAILED'))

    const { result } = withQueryClient(() => useApprovalMutations())
    const updated = await result.approve.mutateAsync(1)

    expect(updated.status).toBe('EXECUTE_FAILED')
  })
})
