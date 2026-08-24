import { computed, type Ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'

import {
  approveApproval,
  listApprovals,
  pendingCount,
  rejectApproval,
  type ApprovalPage,
  type ApprovalRequest,
} from '@/api/approval'
import { approvalKeys } from '@/config/queryKeys'
import { handleServerError } from '@/utils/notify'

/**
 * 审批中心的 Query 封装。
 *
 * 除了消除手写刷新，这里还替掉一条**手工事件通路**：
 * 此前审批决策后要 `ticketEvents.emit('approval-decided')`，
 * 导航栏订阅该事件再重新拉待审数量——发布方与订阅方分离在两个文件，
 * 漏发或漏订阅都会让角标停在旧数字，且没有编译期保护。
 * 现在角标与列表共用 approvalKeys 前缀，决策后 invalidate 一次两者都更新。
 */

export function useApprovalListQuery(status: Ref<string>, page = 1, size = 50) {
  const query = useQuery({
    // status 进 key：切 tab 自动重拉，不需要在 switchTab 里手调 fetchList
    queryKey: computed(() => approvalKeys.list(status.value, page, size)),
    queryFn: () => listApprovals(status.value, page, size),
  })

  return {
    ...query,
    items: computed<ApprovalRequest[]>(() => query.data.value?.items ?? []),
    total: computed(() => query.data.value?.total ?? 0),
  }
}

/**
 * 待审数量（导航栏角标）。
 *
 * @param enabled 是否启用。该端点限 ADMIN（后端 @SaCheckRole），
 *                非管理员请求会得到 403 —— 既无意义又污染控制台，
 *                故由调用方按角色决定是否启用。
 */
export function usePendingApprovalCountQuery(enabled: Ref<boolean>) {
  const query = useQuery({
    queryKey: approvalKeys.pendingCount(),
    queryFn: () => pendingCount(),
    enabled,
  })

  return {
    ...query,
    /**
     * 待审数量。失败或未启用时为 0（不显示角标）——
     * 角标是增值提示，拉取失败不该弹错误打扰用户（同 6.51 降级策略）。
     */
    count: computed(() => query.data.value ?? 0),
  }
}

/**
 * 审批决策（批准 / 驳回）。
 *
 * 成功后失效整个审批领域：列表与待审角标都会变。
 */
export function useApprovalMutations() {
  const queryClient = useQueryClient()

  const invalidateAll = () =>
    queryClient.invalidateQueries({ queryKey: approvalKeys.all })

  const approve = useMutation({
    mutationFn: (id: number) => approveApproval(id),
    onSuccess: invalidateAll,
    onError: (e) => handleServerError(e, { action: '批准审批' }),
  })

  const reject = useMutation({
    mutationFn: (payload: { id: number; reason: string }) =>
      rejectApproval(payload.id, payload.reason),
    onSuccess: invalidateAll,
    onError: (e) => handleServerError(e, { action: '驳回审批' }),
  })

  return { approve, reject, invalidateAll }
}

export type { ApprovalPage, ApprovalRequest }
