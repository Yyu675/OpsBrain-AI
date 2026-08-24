/**
 * queryKey 集中定义。
 *
 * 为何必须集中：queryKey 是 Query 缓存与失效的唯一标识。
 * 若各处手写字符串数组，写操作后 `invalidateQueries` 的 key 与查询用的 key
 * 稍有出入（多一层、少一个参数、拼写不同）就会**静默失效不了**——
 * 表现为「改完数据列表没更新」，与项目 6.17 修过的「写操作后忘记重拉」
 * 是同一类症状，但更难排查（没有明显的漏调用点）。
 *
 * 层级约定：`[领域, 子资源, 参数]`。
 * 前缀失效天然生效——`invalidateQueries({ queryKey: ticketKeys.all })`
 * 会一并失效列表、详情、统计。
 */

/** 工单列表查询参数（与 store 的 TicketQueryParams 对齐） */
export interface TicketListParams {
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

export const ticketKeys = {
  /** 工单领域全部数据。写操作后失效它 = 列表/详情/统计全部重拉 */
  all: ['tickets'] as const,

  lists: () => [...ticketKeys.all, 'list'] as const,
  /** 具体一次列表查询。参数进 key，不同筛选组合各自缓存 */
  list: (params: TicketListParams) => [...ticketKeys.lists(), params] as const,

  details: () => [...ticketKeys.all, 'detail'] as const,
  detail: (id: string) => [...ticketKeys.details(), id] as const,

  /** 后端全量统计（KPI）。与列表分开——列表翻页不该让 KPI 重拉 */
  stats: () => [...ticketKeys.all, 'stats'] as const,

  replies: (id: string) => [...ticketKeys.detail(id), 'replies'] as const,
  activities: (id: string) => [...ticketKeys.detail(id), 'activities'] as const,
  attachments: (id: string) => [...ticketKeys.detail(id), 'attachments'] as const,
  actions: (id: string) => [...ticketKeys.detail(id), 'actions'] as const,

  /** 热门标签（跨全表聚合，与具体列表无关） */
  hotTags: () => [...ticketKeys.all, 'hot-tags'] as const,

  /** SLA 风险清单。窗口分钟数进 key */
  slaAtRisk: (withinMinutes: number, size: number) =>
    [...ticketKeys.all, 'sla-at-risk', withinMinutes, size] as const,
  firstResponseStats: () => [...ticketKeys.all, 'first-response-stats'] as const,
}

export interface AlertListParams {
  page?: number
  size?: number
  status?: string
  level?: string
}

export const alertKeys = {
  all: ['alerts'] as const,
  lists: () => [...alertKeys.all, 'list'] as const,
  list: (params: AlertListParams) => [...alertKeys.lists(), params] as const,
  details: () => [...alertKeys.all, 'detail'] as const,
  detail: (id: string) => [...alertKeys.details(), id] as const,
}

export interface KnowledgeListParams {
  page?: number
  size?: number
  keyword?: string
  category?: string
  tag?: string
  status?: string
  sort?: string
}

export const knowledgeKeys = {
  all: ['knowledge'] as const,
  lists: () => [...knowledgeKeys.all, 'list'] as const,
  list: (params: KnowledgeListParams) => [...knowledgeKeys.lists(), params] as const,
  details: () => [...knowledgeKeys.all, 'detail'] as const,
  detail: (id: number) => [...knowledgeKeys.details(), id] as const,
  versions: (id: number) => [...knowledgeKeys.detail(id), 'versions'] as const,
  categories: () => [...knowledgeKeys.all, 'categories'] as const,
  hotTags: () => [...knowledgeKeys.all, 'hot-tags'] as const,
}

export const dashboardKeys = {
  all: ['dashboard'] as const,
  overview: () => [...dashboardKeys.all, 'overview'] as const,
  closureMetrics: () => [...dashboardKeys.all, 'closure-metrics'] as const,
  rootCauseStats: () => [...dashboardKeys.all, 'root-cause-stats'] as const,
  /** 趋势。天数与服务下钻维度进 key */
  trends: (days: number, module?: string) =>
    [...dashboardKeys.all, 'trends', days, module ?? null] as const,
}

export const approvalKeys = {
  all: ['approvals'] as const,
  lists: () => [...approvalKeys.all, 'list'] as const,
  list: (status: string, page: number, size: number) =>
    [...approvalKeys.lists(), status, page, size] as const,
  pendingCount: () => [...approvalKeys.all, 'pending-count'] as const,
}

export const userKeys = {
  all: ['users'] as const,
  teamMembers: () => [...userKeys.all, 'team-members'] as const,
}
