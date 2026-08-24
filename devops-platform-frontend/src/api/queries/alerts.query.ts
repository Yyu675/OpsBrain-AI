import { computed, type Ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'

import { acknowledgeAlert, fetchAlertById, fetchAlerts, resolveAlert } from '@/api/alerts'
import type { Alert, AlertStatus, AlertsResponse } from '@/api/types'
import { alertKeys } from '@/config/queryKeys'
import { handleServerError } from '@/utils/notify'

/**
 * 告警数据的 Query 封装。
 *
 * 相比原先的手写 `fetchList`：
 * - 筛选/分页参数进 queryKey，**参数变化自动重拉**，不需要在每个
 *   筛选控件的 change 里手动 `await fetchList()`（漏一个就出现
 *   「改了筛选但列表没变」）
 * - 写操作（确认/恢复）成功后 `invalidateQueries` 声明式失效，
 *   不需要记住「这个操作要刷新哪几处」（6.17 的缺陷根源）
 * - loading/error 由 Query 维护，与三态渲染直接对接
 */

export interface AlertListFilters {
  page: Ref<number>
  size: Ref<number>
  status: Ref<AlertStatus | ''>
  level: Ref<string | ''>
}

/** 空串转 undefined：后端把空串当作有效筛选值会导致查不到数据 */
const asParam = (v: string) => (v === '' ? undefined : v)

export function useAlertListQuery(filters: AlertListFilters) {
  const params = computed(() => ({
    page: filters.page.value,
    size: filters.size.value,
    status: asParam(filters.status.value),
    level: asParam(filters.level.value),
  }))

  const query = useQuery({
    // params 进 key：任一筛选或页码变化都会自动触发重拉
    queryKey: computed(() => alertKeys.list(params.value)),
    queryFn: () => fetchAlerts(params.value),
  })

  return {
    ...query,
    /** 告警行。加载中或失败时为空数组，避免模板到处判空 */
    alerts: computed<Alert[]>(() => query.data.value?.alerts ?? []),
    total: computed(() => query.data.value?.total ?? 0),
    totalPages: computed(() => query.data.value?.totalPages ?? 0),
  }
}

export function useAlertDetailQuery(id: Ref<string>) {
  const query = useQuery({
    queryKey: computed(() => alertKeys.detail(id.value)),
    // fetchAlertById 对 40004 返回 null（不抛），Query 把它当作正常结果——
        // 视图据 data===null 判定 notFound，与 6.18 三态契约一致
    queryFn: () => fetchAlertById(id.value),
    // id 为空（路由参数缺失）时不发请求
    enabled: computed(() => !!id.value),
  })

  return query
}

/**
 * 告警处置（确认 / 标记恢复）。
 *
 * 成功后失效整个告警领域：列表的 total、状态分布、详情都会变，
 * 逐个列举要失效什么反而容易漏（同 6.17 教训）。
 */
export function useAlertMutations() {
  const queryClient = useQueryClient()

  const invalidateAll = () =>
    queryClient.invalidateQueries({ queryKey: alertKeys.all })

  const acknowledge = useMutation({
    mutationFn: (id: number) => acknowledgeAlert(id),
    onSuccess: invalidateAll,
    onError: (e) => handleServerError(e, { action: '确认告警' }),
  })

  const resolve = useMutation({
    mutationFn: (id: number) => resolveAlert(id),
    onSuccess: invalidateAll,
    onError: (e) => handleServerError(e, { action: '标记恢复' }),
  })

  return { acknowledge, resolve, invalidateAll }
}

export type { AlertsResponse }
