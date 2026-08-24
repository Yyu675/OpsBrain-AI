import { computed } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'

import { fetchHotTags, fetchTicketById, fetchTicketStats, fetchTickets } from '@/api/tickets'
import { fetchTeamMembers } from '@/api/users'
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
export interface ListParamsToRequest {
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
  })
}

/** 工单详情（后端 40004 返回 null，Query 把 null 作正常结果，视图据此判 notFound） */
export function useTicketDetailQuery(id: () => string) {
  const key = computed(() => id())
  return useQuery({
    queryKey: computed(() => ticketKeys.detail(key.value)),
    queryFn: () => fetchTicketById(key.value),
    enabled: computed(() => !!key.value),
  })
}

/** 后端全量统计（KPI。与列表分开，避免翻页重拉） */
export function useTicketStatsQuery() {
  return useQuery({
    queryKey: ticketKeys.stats(),
    queryFn: () => fetchTicketStats(),
  })
}

/** 热门标签（跨全表聚合，与具体列表无关） */
export function useTicketHotTagsQuery() {
  return useQuery({
    queryKey: computed(() => ticketKeys.hotTags()),
    queryFn: () => fetchHotTags(),
  })
}

/** 负责人名录（后端下发 + 负载） */
export function useTeamMembersQuery() {
  return useQuery({
    queryKey: ticketKeys.all,
    queryFn: () => fetchTeamMembers(),
  })
}

/**
 * 写操作后的统一失效入口。
 *
 * store 的写方法在落库成功后调用 `invalidate`，把「该刷新哪些缓存」的
 * 决定权交给 queryKey 前缀本身——失效 `ticketKeys.all` 会连带失效
 * 列表、详情、统计、热门标签、SLA 风险。没有遗漏，
 * 也无需在每个写方法里记住要刷几处（6.17 缺陷根源）。
 */
export function useTicketInvalidate() {
  const queryClient = useQueryClient()
  return {
    invalidateAll: () => queryClient.invalidateQueries({ queryKey: ticketKeys.all }),
    invalidateList: () => queryClient.invalidateQueries({ queryKey: ticketKeys.lists() }),
  }
}