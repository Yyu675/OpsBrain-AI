import { computed } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'

import { fetchHotTags, fetchTickets } from '@/api/tickets'
import { ticketKeys } from '@/config/queryKeys'
import type {
  FrontendTicketPriority,
  FrontendTicketStatus,
  TicketsRequest,
} from '@/api/types/ticket'

/**
 * 工单数据 Query 封装。
 *
 * 迁移背景：tickets store 889 行，列表/统计/热门标签/负责人是「读」，
 * 12 个写方法是「读 + 乐观回滚」。这里先把纯读的部分抽到 Query——
 * 参数进 queryKey 自动重拉、写操作后 invalidate 声明式失效，
 * 消除「写操作后忘了刷新哪几处」的手工编排（6.17 缺陷根源）。
 *
 * 写方法暂仍留在 store（乐观回滚语义复杂，且已有 46 个测试作行为基准）。
 */

/** 列表 Query 参数：store 的筛选状态 → http 参数 */
interface ListParamsToRequest {
  page?: number
  size?: number
  keyword?: string
  status?: string
  priority?: string
  service?: string
  category?: string
  assignee?: string
  createdFrom?: string
  createdTo?: string
  tags?: string[]
  sortBy?: string
  sortAsc?: boolean
}

const toRequest = (p: ListParamsToRequest): TicketsRequest => ({
  page: p.page,
  size: p.size,
  keyword: p.keyword?.trim() || undefined,
  // 与 TicketList 的筛选约定一致：'all' 表示不筛选
  status: (p.status && p.status !== 'all' ? p.status : undefined) as
    | FrontendTicketStatus
    | undefined,
  priority: (p.priority && p.priority !== 'all' ? p.priority : undefined) as
    | FrontendTicketPriority
    | undefined,
  service: p.service && p.service !== 'all' ? p.service : undefined,
  category: p.category && p.category !== 'all' ? p.category : undefined,
  assignee: p.assignee && p.assignee !== 'all' ? p.assignee : undefined,
  createdFrom: p.createdFrom || undefined,
  createdTo: p.createdTo || undefined,
  tags: p.tags && p.tags.length ? [...p.tags] : undefined,
  sortBy: p.sortBy || undefined,
  sortAsc: p.sortAsc,
})

export function useTicketListQuery(params: () => ListParamsToRequest) {
  const queryParams = computed(() => params())
  return useQuery({
    queryKey: computed(() => ticketKeys.list(queryParams.value)),
    queryFn: () => fetchTickets(toRequest(queryParams.value)),
    staleTime: 10_000, // 10 秒缓存：工单列表变化较频繁，但仍可短暂缓存
    gcTime: 5 * 60_000,
  })
}

/** 热门标签（跨全表聚合，与具体列表无关）。缓存 60 秒（标签聚合变化缓慢）。 */
export function useTicketHotTagsQuery() {
  return useQuery({
    queryKey: computed(() => ticketKeys.hotTags()),
    queryFn: () => fetchHotTags(),
    staleTime: 60_000, // 60 秒缓存：标签分布变化缓慢
    gcTime: 10 * 60_000,
  })
}

/**
 * 写操作后的统一失效入口。
 *
 * 设计给「工单页迁移到 TanStack Query」时使用：当前工单列表走 Pinia
 * store 直连 + 显式 fetchList（TicketList.vue），不经本模块，故暂无
 * 调用方——有意保留的公共 API + 测试契约（批 88 A 阶段核查确认，非死代码）。
 * 迁移后失效 `ticketKeys.all` 会连带失效列表/详情/统计/热门标签/SLA 风险，
 * 无需在每个写方法里记住要刷几处（6.17 缺陷根源即写后漏刷）。
 */
export function useTicketInvalidate() {
  const queryClient = useQueryClient()
  return {
    invalidateAll: () => queryClient.invalidateQueries({ queryKey: ticketKeys.all }),
    invalidateList: () => queryClient.invalidateQueries({ queryKey: ticketKeys.lists() }),
  }
}