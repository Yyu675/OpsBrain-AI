<script setup lang="ts">
import { notify, handleServerError } from '@/utils/notify'
import { ref, computed, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
// el-table 的排序与列上下文类型（组件本身已全局注册，无需按值引入）
import type { Sort, TableColumnCtx } from 'element-plus'
import TicketFormDialog from '@/components/ticket/TicketFormDialog.vue'
import TicketCardGrid from '@/components/ticket/TicketCardGrid.vue'
import { showUndoToast } from '@/utils/undoToast'
// 搜索防抖（列宽持久化已移入 useTicketColumns）
import { debounce } from '@/utils/persist'
import { ticketEvents } from '@/utils/ticketEvents'
import { nowAsBackendTime } from '@/utils/time'
import RelativeTime from '@/components/common/RelativeTime.vue'
import ServerPagination from '@/components/common/ServerPagination.vue'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import { useServerPaginationFrom } from '@/composables/useServerPagination'
import { useTicketFilters } from '@/composables/useTicketFilters'
import { useTicketColumns, CONFIGURABLE_COLUMNS } from '@/composables/useTicketColumns'
import { useTicketBulkActions } from '@/composables/useTicketBulkActions'
import {
  useUrlFilters, defineUrlFilter, enumParser, positiveIntParser, textParser
} from '@/composables/useUrlFilters'
import {
  Search, Sparkles, TrendingUp, Clock, AlertCircle,
  Trash2, Plus, X, Calendar,
  RefreshCw, ChevronDown, LayoutList, LayoutGrid, Settings2
} from 'lucide-vue-next'
import {
  useTicketsStore,
  getStatusLabel,
  getPriorityLabel,
  SERVICE_OPTIONS,
  CATEGORY_OPTIONS,
  type Ticket,
  type TicketStatus,
  type TicketPriority
} from '@/stores/tickets'
import { TICKET_STATUS_OPTIONS, TICKET_PRIORITY_OPTIONS } from '@/constants/ticket'
import { exportTicketsCsv } from '@/api/tickets'
import {
  firstResponseText,
  firstResponseTitle,
  slaRemainText,
  slaSeverity
} from '@/utils/sla'

const store = useTicketsStore()
const router = useRouter()

// 组件挂载时从后端加载数据与统计
// URL → 筛选状态的同步已由下方 useUrlFilters 在 setup 期完成（早于 onMounted），
// 故此处直接拉数据即可，不会出现「先显示全量再跳变」。
onMounted(async () => {
  // 恢复用户调整过的列宽与列可见性（含白名单/类型/范围校验，见 useTicketColumns）
  restoreColumnPrefs()

  /*
   * 事件订阅必须在拉数据**之前**、且在 try 之外注册。
   *
   * 原实现把 `ticketEvents.on` 放在 Promise.all 之后、try 之内：
   * 四个并行请求里任意一个失败（如统计接口 500），整个 try 就跳到 catch，
   * 订阅永远不会注册。此后用户新建的工单不会插进列表——
   * 表现为「创建成功提示弹了、列表里却没有」，用户以为工单丢了，
   * 而实际上只是列表没刷新。这类耦合失败极难排查。
   */
  ticketEvents.on('ticket-created', handleTicketCreated)

  /*
   * 四个请求用 allSettled 而非 all：它们互相独立，
   * 统计接口挂了不该让工单列表也没有（all 会在首个 reject 时短路，
   * 剩下三个的结果全被丢弃）。
   * 列表自身的错误由 fetchList 写入 listError → DataStateBoundary 渲染，
   * 这里只需对「非主数据」的失败做降级提示。
   */
  const [, statsR, tagsR, membersR] = await Promise.allSettled([
    fetchList(),        // 带筛选条件的首屏加载；错误已由 listError 承接
    store.loadStats(),  // 「今日新增」等 KPI 只能由后端全量统计得出
    store.loadHotTags(), // 标签筛选选项需跨全表聚合，不能只取当前页
    store.loadTeamMembers() // A2：负责人筛选与批量指派名单来自后端，不再硬编码
  ])

  // 辅助数据失败不阻塞主流程，但必须告诉用户「哪块降级了」——
  // 否则标签筛选空着、负责人下拉空着，用户会以为系统里真的没有这些数据
  const degraded: string[] = []
  if (statsR.status === 'rejected') degraded.push('统计卡片')
  if (tagsR.status === 'rejected') degraded.push('标签筛选')
  if (membersR.status === 'rejected') degraded.push('负责人名单')
  if (degraded.length) {
    notify.warning(`${degraded.join('、')}加载失败，工单列表不受影响`, {
      key: 'ticket-list-aux-degraded'
    })
  }
})

// KPI 统计卡片：对齐设计稿（待处理/处理中/已解决/今日新增）
const kpis = computed(() => [
  { label: '待处理', value: String(store.stats.pending), icon: AlertCircle, color: 'warning' },
  { label: '处理中', value: String(store.stats.processing), icon: Clock, color: 'info' },
  { label: '已解决', value: String(store.stats.resolved), icon: TrendingUp, color: 'success' },
  { label: '今日新增', value: String(store.stats.todayNew ?? 0), icon: AlertCircle, color: 'error' }
])

const advancedOpen = ref(false)
const viewMode = ref<'list' | 'card'>('list')

/**
 * 分页状态与页码计算统一由 useServerPagination 提供。
 *
 * 用「外部数据源」变体：total/totalPages 由 store 持有（后端全量统计），
 * composable 再存一份必然与 store 漂移。
 *
 * 此前本页与 AlertList 各写一份 pageNumbers/pageStart/pageEnd，算法相同
 * 却已在样式上漂移（chevron 36 vs 32px、圆角 md vs sm 等）。
 */
const pagination = useServerPaginationFrom(
  { total: () => store.total, totalPages: () => Math.max(1, store.totalPages) },
  { pageSize: 10 }
)
const {
  currentPage, pageSize, totalPages,
  pageNumbers, pageStart, pageEnd
} = pagination

/**
 * 筛选状态与派生逻辑统一由 useTicketFilters 提供。
 *
 * 抽出的理由是三处清单必须同步：hasFilters（清空按钮显不显示）、
 * activeFilterChips（渲染成可见 chip）、removeFilterChip（chip 的 × 能不能点掉）。
 * 散在本文件两千多行里时三者相隔数百行，漏改任何一处都不会报错，
 * 只会表现为「找不到清空按钮」「筛了但看不见」「chip 删不掉」这类静默错误。
 *
 * cancelSearch 传箭头函数而非 applySearch 本身：后者定义在下方
 * （它依赖 fetchList），直接引用会触发 TDZ。
 */
const {
  searchQuery, appliedQuery,
  statusFilter, priorityFilter, serviceFilter, categoryFilter, assigneeFilter,
  tagFilters, dateFrom, dateTo,
  hasFilters, activeFilterChips,
  removeFilterChip, clearFilters, toggleTagFilter: toggleTagFilterInner,
} = useTicketFilters({
  cancelSearch: () => applySearch.cancel(),
  currentPage,
  fetchList: () => fetchList(),
})
/** 匹配总数（后端按当前筛选统计） */
const totalCount = pagination.total


// ==================== el-table：列宽 / 排序 / 选择 ====================

/**
 * 列宽 / 列可见性偏好统一由 useTicketColumns 提供。
 *
 * 抽出的理由与 useTicketFilters 相同：默认值、用户覆盖、版本失效三段规则
 * 共享同一组 ref，且 localStorage 回填必须做白名单+类型+范围三重校验——
 * 列宽被写成 0 会让那一列直接消失，用户根本不知道去哪调回来。
 * 详见 composable 文件头注释。
 */
const {
  columnWidths, columnVisible, colSettingOpen,
  titleWidth, columnsResized,
  restoreColumnPrefs, toggleColumn, onHeaderDragend, resetColumnWidths,
} = useTicketColumns({ onReset: () => notify.success('已恢复默认列布局') })

/**
 * 排序状态
 * <p>
 * 排序由后端执行（见 fetchList）。前端只记录状态并触发重新拉取——
 * el-table 自带的本地排序只能排当前页，会让用户以为看到的是全量排序结果。
 * </p>
 */
/**
 * 拆成 sortBy / sortAsc 两个独立 ref，而非一个对象。
 *
 * 原因是 URL 同步：useUrlFilters 按「一个 ref ↔ 一个 query 参数」建模，
 * 对象 ref 需要自定义 parse/serialize 把两个字段揉进一个参数里，
 * 既难读也让 `?sortBy=priority` 这种手写链接无法工作。
 * 拆开后两个参数各自独立，与后端 API 的 sortBy/sortAsc 也是一一对应。
 */
const sortBy = ref<string>('createdAt')
const sortAsc = ref(false)

/** 聚合视图，供 el-table 与 fetchList 使用（保持原有读取方式不变） */
const sortState = computed<{ prop: string; order: 'ascending' | 'descending' }>(() => ({
  prop: sortBy.value,
  order: sortAsc.value ? 'ascending' : 'descending'
}))

/**
 * 传给 el-table 的初始排序
 * <p>
 * 类型标注为 Element Plus 的 Sort：其 order 只接受 'ascending' | 'descending'
 * （不含 null），故 sortState 也去掉了 null 分支——「取消排序」在本页
 * 语义上等于回到默认的「创建时间倒序」，而非无序。
 * </p>
 */
const tableSort = computed<Sort>(() => ({
  prop: sortState.value.prop,
  order: sortState.value.order
}))

/**
 * 可排序字段白名单，与后端 SORTABLE_COLUMNS 对齐（另加特殊处理的 priority）。
 *
 * 前端先挡一道的原因：非法字段会被后端静默降级为默认排序，
 * 而表头的排序箭头仍显示在用户点的那一列——
 * 「箭头指着 A 列、数据按创建时间排」这种错位比直接报错更难发现。
 */
const SORTABLE_PROPS = [
  'id', 'title', 'status', 'priority', 'assignee',
  'service', 'category', 'createdAt', 'updatedAt'
] as const

/**
 * 日期参数解析：只接受 `YYYY-MM-DD`。
 *
 * 不做宽松解析是刻意的——`<input type="date">` 只认这一种格式，
 * 若放行 `2026/8/1` 之类，URL 里的值填不回输入框，
 * 用户会看到「链接说筛了日期、输入框却是空的」这种自相矛盾的状态。
 */
const dateParser = (raw: string): string | undefined =>
  /^\d{4}-\d{2}-\d{2}$/.test(raw.trim()) ? raw.trim() : undefined

/**
 * 筛选 / 排序 / 页码与 URL 的**双向**同步。
 *
 * 此前本页只**读** URL（供 Dashboard 等页面带参跳转），不写回。后果是：
 * 用户在页面上调好的「P0 + 未分配 + 近 24h」既不能刷新保留、
 * 也不能复制链接甩给同事——而「把这个筛选结果发群里」恰恰是
 * 值守交接时最高频的动作之一。
 *
 * 统一到 useUrlFilters 后，读时校验、写时清理、默认值不入 URL 三条行为
 * 与 KnowledgeBase / AlertList 完全一致，用户能形成稳定预期。
 *
 * 关于 sortAsc：它只在 sortBy 非默认时有意义，故 serialize 里跟随判断——
 * 否则地址栏会出现 `?sortAsc=false` 这种脱离上下文的孤立参数。
 */
useUrlFilters([
  defineUrlFilter({
    ref: appliedQuery, key: 'keyword', defaultValue: '', parse: textParser(200)
  }),
  defineUrlFilter<'all' | TicketStatus>({
    ref: statusFilter, key: 'status', defaultValue: 'all',
    parse: enumParser(['all', ...TICKET_STATUS_OPTIONS.map(o => o.value)] as ('all' | TicketStatus)[])
  }),
  defineUrlFilter<'all' | TicketPriority>({
    ref: priorityFilter, key: 'priority', defaultValue: 'all',
    parse: enumParser(['all', ...TICKET_PRIORITY_OPTIONS.map(o => o.value)] as ('all' | TicketPriority)[])
  }),
  defineUrlFilter({
    ref: serviceFilter, key: 'service', defaultValue: 'all',
    parse: enumParser(['all', ...SERVICE_OPTIONS])
  }),
  defineUrlFilter({
    ref: categoryFilter, key: 'category', defaultValue: 'all',
    parse: enumParser(['all', ...CATEGORY_OPTIONS])
  }),
  // 负责人名单来自后端（store.assignees），挂载时才拿到，无法用枚举白名单校验。
  // 用文本解析器 + 长度上限即可：传了不存在的人后端返回空列表，不会出错。
  defineUrlFilter({
    ref: assigneeFilter, key: 'assignee', defaultValue: 'all', parse: textParser(64)
  }),
  defineUrlFilter({ ref: dateFrom, key: 'from', defaultValue: '', parse: dateParser }),
  defineUrlFilter({ ref: dateTo, key: 'to', defaultValue: '', parse: dateParser }),
  defineUrlFilter({
    ref: tagFilters, key: 'tags', defaultValue: [] as string[],
    parse: (raw) => {
      const list = raw.split(',').map(t => t.trim()).filter(Boolean).slice(0, 10)
      return list.length ? list : undefined
    },
    // 数组不能走默认的 String()——会产出 "a,b" 看似正确，但空数组变成 ""
    // 而 defaultValue 是 []，引用不等导致空数组也被写进 URL
    serialize: (v) => (v.length ? v.join(',') : undefined)
  }),
  defineUrlFilter({
    ref: sortBy, key: 'sortBy', defaultValue: 'createdAt', parse: enumParser(SORTABLE_PROPS)
  }),
  defineUrlFilter({
    ref: sortAsc, key: 'sortAsc', defaultValue: false,
    parse: (raw) => (raw === 'true' ? true : raw === 'false' ? false : undefined),
    serialize: (v) => (v ? 'true' : undefined)
  }),
  defineUrlFilter({
    ref: currentPage, key: 'page', defaultValue: 1, parse: positiveIntParser(10000)
  })
])

// URL 里带 keyword 时把它回填到搜索框。
// 只同步 appliedQuery（真正参与查询的值）是不够的——输入框会是空的，
// 用户看到「列表明明筛过、搜索框却没内容」，想清掉筛选都不知道从哪下手。
if (appliedQuery.value) searchQuery.value = appliedQuery.value

const onSortChange = async (data: {
  prop: string | null
  order: 'ascending' | 'descending' | null
}) => {
  // order 为 null 表示用户点到第三下取消排序 → 回落到默认排序
  if (data.order && data.prop) {
    sortBy.value = data.prop
    sortAsc.value = data.order === 'ascending'
  } else {
    sortBy.value = 'createdAt'
    sortAsc.value = false
  }
  // 排序变化等于换了一种数据视图，回到第 1 页；否则用户会停在
  // 「按新排序后本不该存在的第 5 页」
  currentPage.value = 1
  await fetchList()
}

/** 行样式：选中高亮沿用原有 .selected 语义 */
const rowClassName = ({ row }: { row: Ticket }) =>
  selectedIds.value.includes(row.id) ? 'selected' : ''


/**
 * 行点击进详情
 * <p>
 * 勾选列点击不跳转——否则用户想勾选却被带走。
 * 操作列的按钮已各自 @click.stop，不会冒泡到这里。
 * </p>
 */
const onRowClick = (row: Ticket, column: TableColumnCtx<Ticket> | null) => {
  if (column?.type === 'selection') return
  router?.push(`/tickets/${row.id}`)
}

/**
 * 首响状态展示（B1）
 *
 * 状态由后端计算（`firstResponseState`）——「即将超时」的阈值属业务规则，
 * 不应散落在各前端页面各写一遍。
 *
 * 文案逻辑统一在 utils/sla（SLA 风险面板同样消费），此处只保留
 * 页面私有的 CSS class 映射。
 */
const frClass = (row: { firstResponseState?: string }) => {
  switch (row.firstResponseState) {
    case 'RESPONDED': return 'fr-ok'
    case 'BREACHED': return 'fr-breached'
    case 'AT_RISK': return 'fr-risk'
    default: return 'fr-waiting'
  }
}

/** SLA 进度配色：超时红 / ≥70% 橙 / 其余正常 */
const slaClass = (row: { slaProgress?: number; slaBreached?: boolean }) => {
  const severity = slaSeverity(row)
  if (severity === 'breached') return 'sla-breached'
  if (severity === 'warning') return 'sla-warning'
  return 'sla-normal'
}

/** B2 处置阶段中文标签 */
const STAGE_LABELS: Record<string, string> = {
  TRIAGE: '排查中', MITIGATED: '已止损', FIXING: '修复中', VERIFYING: '验证中'
}
const stageLabel = (stage: string) => STAGE_LABELS[stage] || stage

/** B3 根因分类中文标签 */
const RC_LABELS: Record<string, string> = {
  CONFIG: '配置错误', CAPACITY: '容量不足', CODE: '代码缺陷',
  DEPENDENCY: '依赖故障', NETWORK: '网络问题', DATA: '数据异常',
  HUMAN: '人为操作', EXTERNAL: '外部服务', UNKNOWN: '未定位'
}
const rcLabel = (cat: string) => RC_LABELS[cat] || cat

// SLA 剩余时间文案（slaRemainText）与首响文案已统一到 utils/sla，
// SLA 风险面板同样消费——两处各写一遍必然在「已超时」措辞与降级口径上漂移。

const dialogVisible = ref(false)

/**
 * 当前页工单
 * <p>
 * 直接取 store 数据——筛选与分页已下沉到后端。
 * 此前是前端本地 filter + slice，只作用于已加载的 100 条，
 * 第 101 条起的工单对搜索静默不可见，分页数字也基于裁剪后的子集。
 * </p>
 */
const pagedTickets = computed(() => store.tickets)

/** 匹配总数与总页数均来自后端 */
/** 匹配总数与总页数均来自后端，见上方 useServerPaginationFrom */

/**
 * 拉取当前条件下的数据
 * <p>筛选值为 'all' 时不传该参数，后端视作不限制。</p>
 */
const listLoading = ref(false)
const listError = ref<unknown>(null)
const exporting = ref(false)

const handleExportCsv = async () => {
  exporting.value = true
  try {
    const asParam = (v: string) => (v === 'all' ? undefined : v)
    const csv = await exportTicketsCsv({
      keyword: searchQuery.value || undefined,
      status: asParam(statusFilter.value) as TicketStatus | undefined,
      priority: asParam(priorityFilter.value) as TicketPriority | undefined,
      service: asParam(serviceFilter.value) || undefined,
      category: asParam(categoryFilter.value) || undefined,
      assignee: asParam(assigneeFilter.value) || undefined,
      createdFrom: dateFrom.value || undefined,
      createdTo: dateTo.value || undefined,
      tags: tagFilters.value.length ? tagFilters.value : undefined
    })
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    // 用服务器时区的日期而非 UTC：北京时间 0:00-8:00 导出时，
    // toISOString 给的是前一天，文件名与数据口径对不上
    a.download = `工单列表_${nowAsBackendTime().slice(0, 10)}.csv`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    notify.success('导出成功')
  } catch (e) {
    // 走 handleServerError 而非裸 notify.error：后者丢掉业务码映射，
    // 导出配额超限（40005）与后端 500 会显示同一句「导出失败，请稍后重试」，
    // 前者用户等一会儿确实能好，后者等多久都没用
    handleServerError(e, { action: '导出工单' })
  } finally {
    exporting.value = false
  }
}

const fetchList = async () => {
  const asParam = (v: string) => (v === 'all' ? undefined : v)
  listLoading.value = true
  listError.value = null
  try {
    await store.loadTicketsFromBackend({
      page: currentPage.value,
      size: pageSize.value,
      keyword: appliedQuery.value.trim() || undefined,
      status: asParam(statusFilter.value),
      priority: asParam(priorityFilter.value),
      service: asParam(serviceFilter.value),
      category: asParam(categoryFilter.value),
      assignee: asParam(assigneeFilter.value),
      createdFrom: dateFrom.value || undefined,
      createdTo: dateTo.value || undefined,
      tags: tagFilters.value.length ? [...tagFilters.value] : undefined,
      // 排序下沉到后端：本地排序只能排当前页，
      // 「按优先级排序」会漏掉页外更高优先级的工单
      sortBy: sortState.value.prop || undefined,
      sortAsc: sortState.value.order === 'ascending'
    })
  } catch (e) {
    console.error('加载工单列表失败:', e)
    listError.value = e
  } finally {
    listLoading.value = false
  }
}

// 页码序列与区间计算见 useServerPagination（与 AlertList 共用同一实现）

const goToPage = async (p: number) => {
  // 返回 false 表示越界或未变化，此时不必重新拉取
  if (pagination.goToPage(p)) await fetchList()
}

/** 筛选变化：回到第 1 页并重新拉取 */
const resetPageOnFilterChange = async () => {
  pagination.resetPage()
  await fetchList()
}

/** 标签多选：切换后回到第 1 页重新拉取（实现在 useTicketFilters） */
const toggleTagFilter = (tag: string) => toggleTagFilterInner(tag, resetPageOnFilterChange)

// 搜索防抖 300ms：每次输入都打后端会造成无谓压力
const applySearch = debounce((v: string) => {
  appliedQuery.value = v
  currentPage.value = 1
  void fetchList()
}, 300)

const onSearchInput = () => applySearch(searchQuery.value)

onBeforeUnmount(() => {
  applySearch.flush()
  ticketEvents.off('ticket-created', handleTicketCreated)
})

const openCreateDialog = () => {
  dialogVisible.value = true
}

const openEditDialog = (ticket: Ticket) => {
  // 编辑功能待后续实现
  router.push(`/tickets/${ticket.id}`)
}

const deleteTicket = async (ticket: Ticket) => {
  await ElMessageBox.confirm(`确认删除工单「${ticket.title}」？删除后 5 秒内可撤销。`, '删除工单', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  const snap = await store.deleteTicket(ticket.id)
  if (snap) {
    await fetchList()
    showUndoToast({
      message: '工单已删除',
      duration: 5000,
      onUndo: async () => {
        const ok = await store.bulkRestore([snap])
        if (ok) await fetchList()
      }
    })
  }
}

/**
 * 工单表单提交完成（创建或编辑）
 * <p>
 * 重新拉取列表：分页与 total 由后端提供，
 * 仅靠 store 的本地 unshift 会让当前页多出一行且 total 失准。
 * 同时刷新热门标签——新工单可能带来新标签。
 * </p>
 */
const handleTicketFormSubmitted = async () => {
  await Promise.all([fetchList(), store.loadStats(), store.loadHotTags()])
}


// AI 创建工单成功回调
/**
 * AI 建单成功
 * <p>
 * 由本页按<b>当前筛选条件</b>重新拉取，而非依赖 AiChatView 页面内部的
 * {@code refreshTickets()}——后者不带参数调用，会把列表重置为
 * 无筛选第 1 页，导致筛选下拉框仍显示条件但数据已是全部工单。
 * </p>
 */
const handleTicketCreated = async (ticketId: string) => {
  console.log('AI 创建工单成功:', ticketId)
  await Promise.all([fetchList(), store.loadStats(), store.loadHotTags()])
}

// 批量操作
//
// 批量操作（选中集合 / 改状态 / 转派 / 删除撤销）统一由 useTicketBulkActions 提供。
// 抽出理由：这块一次影响几十张工单，而正确性不在「成功时对不对」，
// 而在「部分失败时说没说实话」——详见 composable 文件头注释。
// 它已有 13 例测试兜底，是当前拆分风险最低的一块。
const {
  selectedIds, bulkStatusOpen, bulkAssignOpen, bulkStatusOptions,
  toggleSelect, onSelectionChange, closeBulkMenus,
  bulkDelete, applyBulkStatus, applyBulkAssign,
} = useTicketBulkActions({
  store,
  currentPage,
  fetchList: () => fetchList(),
  // 列设置面板归 useTicketColumns 管，用回调传入避免两个 composable 互相引用
  onCloseOtherMenus: () => { colSettingOpen.value = false },
})

onMounted(() => {
  document.addEventListener('click', closeBulkMenus)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', closeBulkMenus)
})

const getStatusClass = (s: TicketStatus) => `status-${s}`
const getPriorityClass = (p: TicketPriority) => `priority-${p}`
</script>

<template>
  <div class="ticket-list">
    <main class="main-container">
      <!-- Page Header (white card) -->
      <div class="page-header-card">
        <div class="page-header">
          <div>
            <h1 class="page-title">智能工单</h1>
            <p class="page-subtitle">AI 驱动工单路由与自动化处理</p>
          </div>
          <div class="page-actions">
            <button class="btn-create" @click="openCreateDialog">
              <Plus :size="16" />
              创建工单
            </button>
          </div>
        </div>
      </div>

      <!-- KPI Cards -->
      <div class="kpi-grid">
        <div v-for="kpi in kpis" :key="kpi.label" class="kpi-card">
          <div class="kpi-icon" :class="`kpi-icon-${kpi.color}`">
            <component :is="kpi.icon" :size="20" />
          </div>
          <div class="kpi-content">
            <div class="kpi-value">{{ kpi.value }}</div>
            <div class="kpi-label">{{ kpi.label }}</div>
          </div>
        </div>
      </div>

      <!-- Filter Bar（紧凑布局：搜索占满 + 筛选按钮 + 视图切换） -->
      <div class="filter-bar">
        <div class="filter-search">
          <Search class="filter-search-icon" :size="18" />
          <input
            v-model="searchQuery"
            @input="onSearchInput"
            type="text"
            class="filter-search-input"
            placeholder="搜索工单号、标题、描述..."
          />
        </div>

        <div class="filter-controls">
          <!-- 筛选按钮（展开/收起高级筛选） -->
          <button class="filter-toggle-btn" :class="{ active: advancedOpen }" @click="advancedOpen = !advancedOpen">
            <Calendar :size="14" />
            筛选
            <ChevronDown :size="12" style="transition: transform 0.2s;" :style="{ transform: advancedOpen ? 'rotate(180deg)' : '' }" />
          </button>
          <!-- 清除筛选（仅有条件时显示） -->
          <button
            v-if="hasFilters"
            class="btn-clear-filters"
            type="button"
            @click="clearFilters"
          >
            <X :size="14" />
            清除
          </button>
          <button class="btn-export-csv" type="button" :disabled="exporting" @click="handleExportCsv">
            {{ exporting ? '导出中…' : '导出' }}
          </button>

          <!-- 列设置：用户自选可见列并持久化（仅列表视图有意义） -->
          <div v-if="viewMode === 'list'" class="col-setting-wrap">
            <button
              class="btn-col-setting"
              type="button"
              title="列设置"
              @click="colSettingOpen = !colSettingOpen"
            >
              <Settings2 :size="14" />
              列
            </button>
            <div v-if="colSettingOpen" class="col-setting-menu" @click.stop>
              <div class="col-setting-head">
                <span>显示列</span>
                <button class="link-btn" type="button" @click="resetColumnWidths">恢复默认</button>
              </div>
              <label v-for="col in CONFIGURABLE_COLUMNS" :key="col.key" class="col-setting-item">
                <input
                  type="checkbox"
                  :checked="columnVisible[col.key]"
                  @change="toggleColumn(col.key)"
                />
                <span>{{ col.label }}</span>
              </label>
              <p class="col-setting-hint">标题与操作列固定显示</p>
            </div>
          </div>

          <!-- 视图切换 -->
          <div class="view-toggle">
            <button
              class="view-btn"
              :class="{ active: viewMode === 'list' }"
              @click="viewMode = 'list'"
              title="列表视图"
            >
              <LayoutList :size="16" />
            </button>
            <button
              class="view-btn"
              :class="{ active: viewMode === 'card' }"
              @click="viewMode = 'card'"
              title="卡片视图"
            >
              <LayoutGrid :size="16" />
            </button>
          </div>
        </div>
      </div>

      <!-- 已选条件摘要：收起筛选面板后仍能看到当前筛选了什么，
           并可逐条删除。此前必须展开面板才知道条件，是 6.17「筛选被静默清空
           却看不出来」的温床 -->
      <div v-if="activeFilterChips.length" class="active-filter-bar">
        <span class="active-filter-label">已筛选</span>
        <button
          v-for="chip in activeFilterChips"
          :key="chip.key"
          type="button"
          class="active-filter-chip"
          :title="`移除：${chip.label}`"
          @click="removeFilterChip(chip)"
        >
          <span class="chip-text">{{ chip.label }}</span>
          <X :size="11" />
        </button>
        <button type="button" class="active-filter-clear" @click="clearFilters">全部清除</button>
      </div>

      <!-- 高级筛选面板 -->
      <div v-if="advancedOpen" class="advanced-filter-panel">
        <!--
          单一自适应网格容纳 5 个下拉 + 日期区间，不再拆成两行。
          原实现是「5 个定宽下拉左对齐」+「日期单独一行」+「标签单独一行」=3 行，
          宽屏下每行右侧都空掉数百 px；`auto-fit + minmax` 让列数随容器宽度自动增减，
          既填满可用宽度又不会把单个控件拉得过宽（上限 1fr 由列数分摊）。
        -->
        <div class="advanced-filter-grid">
          <select v-model="statusFilter" class="filter-select" @change="resetPageOnFilterChange">
            <option value="all">全部状态</option>
            <option v-for="opt in TICKET_STATUS_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
          <select v-model="priorityFilter" class="filter-select" @change="resetPageOnFilterChange">
            <option value="all">全部优先级</option>
            <option v-for="opt in TICKET_PRIORITY_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
          <select v-model="serviceFilter" class="filter-select" @change="resetPageOnFilterChange">
            <option value="all">全部服务</option>
            <option v-for="s in SERVICE_OPTIONS" :key="s" :value="s">{{ s }}</option>
          </select>
          <select v-model="categoryFilter" class="filter-select" @change="resetPageOnFilterChange">
            <option value="all">全部分类</option>
            <option v-for="c in CATEGORY_OPTIONS" :key="c" :value="c">{{ c }}</option>
          </select>
          <select v-model="assigneeFilter" class="filter-select" @change="resetPageOnFilterChange">
            <option value="all">全部负责人</option>
            <!-- 名单来自后端 sys_team_member（A2），不再硬编码编造姓名 -->
            <option v-for="a in store.assignees" :key="a" :value="a">{{ a }}</option>
          </select>
          <!-- 日期区间跨 2 列（两个 input + 分隔符比单个下拉宽） -->
          <div class="date-range-group">
            <span class="date-range-label">创建时间</span>
            <input type="date" v-model="dateFrom" class="filter-date-input" @change="resetPageOnFilterChange" />
            <span class="date-range-sep">–</span>
            <input type="date" v-model="dateTo" class="filter-date-input" @change="resetPageOnFilterChange" />
          </div>
        </div>
        <!-- 标签与「收起」同排：标签自身换行填充，收起按钮固定在行尾 -->
        <div v-if="store.hotTags.length" class="advanced-filter-tags">
          <span class="tag-filter-label">标签</span>
          <button
            v-for="tag in store.hotTags"
            :key="tag"
            type="button"
            class="tag-filter-chip"
            :class="{ active: tagFilters.includes(tag) }"
            @click="toggleTagFilter(tag)"
          >{{ tag }}</button>
          <button class="btn-clear-advanced" @click="advancedOpen = false">收起</button>
        </div>
        <!-- 无热门标签时仍需「收起」入口 -->
        <div v-else class="advanced-filter-tags advanced-filter-tags--empty">
          <button class="btn-clear-advanced" @click="advancedOpen = false">收起</button>
        </div>
      </div>

      <!-- Bulk Action Bar -->
      <div v-if="selectedIds.length" class="bulk-bar">
        <span class="bulk-text">已选 {{ selectedIds.length }} 条</span>
        <div class="bulk-actions">
          <div class="bulk-dropdown">
            <button class="bulk-btn" @click="bulkStatusOpen = !bulkStatusOpen">
              <RefreshCw :size="14" />
              修改状态
              <ChevronDown :size="12" />
            </button>
            <div v-if="bulkStatusOpen" class="bulk-menu" @click.stop>
              <button
                v-for="opt in bulkStatusOptions"
                :key="opt.value"
                :disabled="opt.disabled"
                :title="opt.hint"
                @click="applyBulkStatus(opt.value)"
              >
                {{ opt.label }}
                <span v-if="opt.applicable && opt.applicable < selectedIds.length" class="bulk-opt-count">
                  {{ opt.applicable }}/{{ selectedIds.length }}
                </span>
              </button>
            </div>
          </div>
          <div class="bulk-dropdown">
            <button class="bulk-btn" @click="bulkAssignOpen = !bulkAssignOpen">
              <Sparkles :size="14" />
              批量指派
              <ChevronDown :size="12" />
            </button>
            <div v-if="bulkAssignOpen" class="bulk-menu" @click.stop>
              <!-- 名单来自后端（A2）。名录不可用时只剩「待分配」，
                   总比把工单批量指派给不存在的人好 -->
              <button v-for="a in store.assignees" :key="a" @click="applyBulkAssign(a)">{{ a }}</button>
            </div>
          </div>
          <button v-permission.disable="{ roles: ['admin'] }" class="bulk-btn bulk-btn-danger" @click="bulkDelete">
            <Trash2 :size="14" />
            批量删除
          </button>
          <button class="bulk-btn-plain" @click="selectedIds = []">
            <X :size="14" />
            取消选择
          </button>
        </div>
      </div>

      <!--
        加载骨架 / 错误 / 空态 / 内容四态统一交给 DataStateBoundary。

        除了消除与 AlertList 等页面的重复实现，这里真正修掉的是：
        翻页与改筛选时**完全没有加载反馈**——listLoading 此前只用于
        首屏骨架的条件里，有数据后再请求时界面纹丝不动，慢接口下
        用户会以为点击没生效而反复点。Boundary 会在保留内容的同时
        在顶部走一条细进度条。
      -->
      <DataStateBoundary
        :loading="listLoading"
        :error="listError"
        :count="pagedTickets.length"
        :filtered="hasFilters"
        empty-description="暂无工单，点击「创建工单」开始"
        filtered-description="筛选无命中，试试调整条件"
        :skeleton-rows="6"
        @retry="fetchList"
      >
      <!-- 列表视图：el-table 提供原生列宽拖拉（border + resizable）
           列宽变化持久化到 localStorage，刷新后保持用户调整的布局 -->
      <div v-if="viewMode === 'list'" class="table-container">
        <el-table
          class="tickets-table"
          :data="pagedTickets"
          border
          stripe
          row-key="id"
          :row-class-name="rowClassName"
          :default-sort="tableSort"
          @row-click="onRowClick"
          @selection-change="onSelectionChange"
          @header-dragend="onHeaderDragend"
          @sort-change="onSortChange"
        >
          <el-table-column type="selection" :width="columnWidths.selection" :resizable="false" />

          <el-table-column
            v-if="columnVisible.id"
            prop="id"
            label="工单 ID"
            :width="columnWidths.id"
            :min-width="120"
            sortable="custom"
          >
            <template #default="{ row }">
              <RouterLink :to="`/tickets/${row.id}`" class="ticket-id" @click.stop>{{ row.id }}</RouterLink>
            </template>
          </el-table-column>

          <!-- 标题列：不设固定 width，由 min-width 弹性吸收剩余空间
               （此前所有列定宽导致右侧一大片空白 gutter）。
               悬浮改为结构化「速览卡」——原先 show-overflow-tooltip 会把整个单元格
               文本（含描述、标签）糊成一片，描述属长文本不该进 tooltip -->
          <el-table-column
            prop="title"
            label="标题"
            :width="titleWidth"
            :min-width="240"
          >
            <template #default="{ row }">
              <el-tooltip placement="top-start" :show-after="250" effect="light" popper-class="ticket-peek-popper">
                <template #content>
                  <div class="ticket-peek">
                    <div class="peek-title">{{ row.title }}</div>
                    <div class="peek-row">
                      <span class="peek-label">服务 · 分类</span>
                      <span class="peek-value">{{ row.service || '未分类' }} · {{ row.category || '其他' }}</span>
                    </div>
                    <div class="peek-row">
                      <span class="peek-label">SLA</span>
                      <span class="peek-value" :class="slaClass(row)">
                        {{ slaRemainText(row) }}
                        <template v-if="row.sla"> · 目标 {{ row.sla }}</template>
                      </span>
                    </div>
                    <div class="peek-row">
                      <span class="peek-label">首响</span>
                      <span class="peek-value" :class="frClass(row)">{{ firstResponseText(row) }}<template v-if="row.firstResponder"> · {{ row.firstResponder }}</template></span>
                    </div>
                    <div class="peek-row">
                      <span class="peek-label">负责人 · 状态</span>
                      <span class="peek-value">{{ row.assignee || '待分配' }} · {{ getStatusLabel(row.status) }}</span>
                    </div>
                    <div v-if="row.tags && row.tags.length" class="peek-row">
                      <span class="peek-label">标签</span>
                      <span class="peek-value">{{ row.tags.join('、') }}</span>
                    </div>
                    <div class="peek-row">
                      <span class="peek-label">创建</span>
                      <span class="peek-value">{{ row.creator || '未知' }} · {{ row.createdAt }}</span>
                    </div>
                    <div v-if="row.updatedAt" class="peek-row">
                      <span class="peek-label">更新</span>
                      <span class="peek-value">{{ row.updatedAt }}</span>
                    </div>
                  </div>
                </template>
                <div class="ticket-title-cell">
                  <RouterLink :to="`/tickets/${row.id}`" class="ticket-title" @click.stop>{{ row.title }}</RouterLink>
                  <div v-if="row.tags && row.tags.length" class="ticket-tags">
                    <span v-for="tag in row.tags.slice(0, 3)" :key="tag" class="ticket-tag">{{ tag }}</span>
                    <span v-if="row.tags.length > 3" class="ticket-tag-more">+{{ row.tags.length - 3 }}</span>
                  </div>
                </div>
              </el-tooltip>
            </template>
          </el-table-column>

          <!-- 服务：此前埋在标题副标题里，提为独立列可见可排序 -->
          <el-table-column
            v-if="columnVisible.service"
            prop="service"
            label="服务"
            :width="columnWidths.service"
            :min-width="110"
            sortable="custom"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="cell-muted">{{ row.service || '未分类' }}</span>
            </template>
          </el-table-column>

          <!-- 分类：后端一直有 category 数据，列表页此前从未展示 -->
          <el-table-column
            v-if="columnVisible.category"
            prop="category"
            label="分类"
            :width="columnWidths.category"
            :min-width="90"
            sortable="custom"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="cell-muted">{{ row.category || '其他' }}</span>
            </template>
          </el-table-column>

          <el-table-column
            v-if="columnVisible.priority"
            prop="priority"
            label="优先级"
            :width="columnWidths.priority"
            :min-width="90"
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="priority-badge" :class="getPriorityClass(row.priority)">{{ getPriorityLabel(row.priority) }}</span>
            </template>
          </el-table-column>

          <el-table-column
            v-if="columnVisible.status"
            prop="status"
            label="状态"
            :width="columnWidths.status"
            :min-width="90"
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="status-badge" :class="getStatusClass(row.status)">{{ getStatusLabel(row.status) }}</span>
            </template>
          </el-table-column>

          <!-- 首响状态（B1）：区分「已派单但无人理」与「已在处理」。
               此前只有 assignee，看不出是否真有人响应过 -->
          <el-table-column
            v-if="columnVisible.firstResponse"
            label="首响"
            :width="columnWidths.firstResponse"
            :min-width="96"
          >
            <template #default="{ row }">
              <span class="fr-badge" :class="frClass(row)" :title="firstResponseTitle(row)">
                {{ firstResponseText(row) }}
              </span>
            </template>
          </el-table-column>

          <!-- SLA 进度：后端计算的派生字段（6.15），此前列表页未展示，
               运维看不到哪些单快超时。超时标红、≥70% 标橙 -->
          <el-table-column v-if="columnVisible.sla" label="SLA" :width="columnWidths.sla" :min-width="120">
            <template #default="{ row }">
              <div class="sla-cell" :title="row.sla || 'SLA 未设置'">
                <div class="sla-bar">
                  <div
                    class="sla-bar-fill"
                    :class="slaClass(row)"
                    :style="{ width: Math.min(100, Math.max(0, row.slaProgress || 0)) + '%' }"
                  />
                </div>
                <span class="sla-text" :class="slaClass(row)">
                  {{ row.slaBreached ? '已超时' : (row.slaProgress ?? 0) + '%' }}
                </span>
              </div>
            </template>
          </el-table-column>

          <!-- B2 处置阶段（仅处理中时有值，其余为空） -->
          <el-table-column
            v-if="columnVisible.handlingStage"
            label="处置阶段"
            :width="columnWidths.handlingStage"
            :min-width="90"
          >
            <template #default="{ row }">
              <el-tooltip :content="'处置阶段: ' + row.handlingStage" placement="top" :show-after="300" :disabled="!row.handlingStage">
                <span v-if="row.handlingStage" class="stage-badge" :class="`stage-${(row.handlingStage || '').toLowerCase()}`">{{ stageLabel(row.handlingStage) }}</span>
              </el-tooltip>
            </template>
          </el-table-column>

          <el-table-column
            v-if="columnVisible.assignee"
            prop="assignee"
            label="负责人"
            :width="columnWidths.assignee"
            :min-width="110"
            sortable="custom"
          >
            <template #default="{ row }">
              <div class="assignee-cell">
                <span class="assignee-avatar">{{ (row.assignee || '?')[0] }}</span>
                <span class="assignee-name">{{ row.assignee }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column
            v-if="columnVisible.createdAt"
            prop="createdAt"
            label="创建时间"
            :width="columnWidths.createdAt"
            :min-width="100"
            sortable="custom"
          >
            <template #default="{ row }">
              <div class="timestamp"><RelativeTime :value="row.createdAt" /></div>
            </template>
          </el-table-column>

          <!-- B3 根因分类（仅确认根因后有值） -->
          <el-table-column
            v-if="columnVisible.rootCause"
            label="根因分类"
            :width="columnWidths.rootCause"
            :min-width="90"
          >
            <template #default="{ row }">
              <el-tooltip :content="'根因分类: ' + row.rootCauseCategory" placement="top" :show-after="300" :disabled="!row.rootCauseCategory">
                <span v-if="row.rootCauseCategory" class="rc-badge">{{ rcLabel(row.rootCauseCategory) }}</span>
              </el-tooltip>
            </template>
          </el-table-column>

          <!-- 更新时间：判断工单是否停滞（首响/处置进度）的关键信号 -->
          <el-table-column
            v-if="columnVisible.updatedAt"
            prop="updatedAt"
            label="更新时间"
            :width="columnWidths.updatedAt"
            :min-width="100"
            sortable="custom"
          >
            <template #default="{ row }">
              <div class="timestamp"><RelativeTime :value="row.updatedAt" /></div>
            </template>
          </el-table-column>

          <el-table-column label="操作" :width="columnWidths.actions" :min-width="90" fixed="right">
            <template #default="{ row }">
              <div class="actions" @click.stop>
                <button class="action-icon-btn" title="编辑" @click.stop="openEditDialog(row)">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                </button>
                <button
                  v-permission.disable="{ roles: ['admin'] }"
                  class="action-icon-btn action-icon-btn-danger"
                  title="删除"
                  @click.stop="deleteTicket(row)"
                >
                  <Trash2 :size="14" />
                </button>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="columnsResized" class="table-footnote">
          <span>列布局已按你的调整保存</span>
          <button class="link-btn" @click="resetColumnWidths">恢复默认列布局</button>
        </div>
      </div>

      <!-- 卡片视图 -->
      <TicketCardGrid
        v-else
        :tickets="pagedTickets"
        :selected-ids="selectedIds"
        :get-status-class="getStatusClass"
        :get-status-label="getStatusLabel"
        :get-priority-class="getPriorityClass"
        :get-priority-label="getPriorityLabel"
        @toggle-select="toggleSelect"
        @open-detail="(id: string) => router?.push(`/tickets/${id}`)"
        @edit="openEditDialog"
        @delete="deleteTicket"
      />
      </DataStateBoundary>

      <!-- Pagination (对齐设计稿) -->
      <ServerPagination
        :current-page="currentPage"
        :total-pages="totalPages"
        :total="totalCount"
        :page-start="pageStart"
        :page-end="pageEnd"
        :page-numbers="pageNumbers"
        @page-change="goToPage"
      />
    </main>

    <!--
      submit 后须重新拉取：store.createTicket 只在本地 unshift，
      而分页与 total 由后端提供。不刷新则当前页会多出一行、
      total 仍是旧值、第 2 页记录也不会正确下移。
    -->
    <TicketFormDialog
      :visible="dialogVisible"
      :ticket="null"
      @update:visible="dialogVisible = $event"
      @submit="handleTicketFormSubmitted"
    />
  </div>
</template>

<style scoped lang="scss">
.ticket-list {
  min-height: 100vh;
  background: var(--color-bg);
}

.main-container {
  max-width: 1440px;
  margin: 0 auto;
  padding: 24px;
}

/* Page Header - white card */
.page-header-card {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: 24px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.page-title {
  font-size: var(--text-2xl);
  font-weight: var(--weight-bold);
  color: var(--color-text-primary);
  margin: 0 0 4px 0;
}

.page-subtitle {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  margin: 0;
}

.page-actions {
  display: flex;
  gap: 8px;
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
  }
}

.btn-create {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover { background: var(--color-primary-light); }
}

/* KPI Grid - 对齐设计稿：4 卡片（待处理/处理中/已解决/今日新增） */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;

  @media (max-width: 1024px) { grid-template-columns: repeat(2, 1fr); }
}

.kpi-card {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow-sm);
  display: flex;
  gap: 16px;
}

.kpi-icon {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;

  &.kpi-icon-warning { background: var(--state-warning-bg); color: var(--state-warning); }
  &.kpi-icon-info { background: var(--state-info-bg); color: var(--state-info); }
  &.kpi-icon-success { background: var(--state-success-bg); color: var(--state-success); }
  &.kpi-icon-error { background: var(--state-error-bg); color: var(--state-error); }
}

.kpi-content { flex: 1; min-width: 0; }
.kpi-label { font-size: var(--text-sm); color: var(--color-text-tertiary); margin-bottom: 4px; }
.kpi-value { font-size: var(--text-2xl); font-weight: var(--weight-bold); color: var(--color-text-primary); line-height: 1.2; margin-bottom: 4px; }
.kpi-change { display: flex; align-items: center; gap: 4px; font-size: var(--text-xs); font-weight: var(--weight-medium); color: var(--color-text-tertiary); }

/* Filter Bar */
.filter-bar {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: 16px;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  box-shadow: var(--shadow-sm);

  @media (max-width: 1024px) { flex-direction: column; align-items: stretch; }
}

.filter-toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 8px 14px;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-md, 8px);
  background: var(--color-surface, #fff);
  color: var(--color-text-secondary, var(--text-2));
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}
.filter-toggle-btn:hover {
  border-color: var(--color-primary, var(--brand));
  color: var(--color-primary, var(--brand));
}
.filter-toggle-btn.active {
  border-color: var(--color-primary, var(--brand));
  background: var(--color-primary-lighter, var(--brand-subtle));
  color: var(--color-primary, var(--brand));
}

.filter-search { flex: 1; position: relative; min-width: 0; }
.filter-search-icon { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); color: var(--color-text-tertiary); pointer-events: none; }

.filter-search-input {
  width: 100%;
  padding: 8px 12px 8px 38px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  outline: none;
  transition: border-color 0.15s ease;
  box-sizing: border-box;

  &:focus { border-color: var(--color-primary); }
  &::placeholder { color: var(--color-text-tertiary); }
}

.filter-controls { display: flex; gap: 8px; flex-wrap: wrap; }

.filter-select {
  padding: 8px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  outline: none;
  transition: border-color 0.15s ease;

  &:focus { border-color: var(--color-primary); }
}

/* 日期范围（单跨度点击）- 对齐设计稿 */
.filter-date-range {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-tertiary);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;

  &:hover { border-color: var(--color-primary); color: var(--color-primary); }

  .date-range-text {
    font-size: var(--text-sm);
    color: var(--color-text-tertiary);
  }
}

/* 视图切换：列表/卡片 - 对齐设计稿 */
.view-toggle {
  display: flex;
  gap: 2px;
  background: var(--color-bg-sunken);
  border-radius: var(--radius-md);
  padding: 2px;
}

.view-btn {
  width: 34px;
  height: 34px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-surface);
    color: var(--color-primary);
  }

  &.active {
    background: var(--color-primary);
    color: white;
  }
}

.btn-filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;

  &:hover { border-color: var(--color-primary); color: var(--color-primary); }
}


/* Bulk Action Bar */
/* 高级筛选面板 */
.advanced-filter-panel {
  padding: 12px 16px;
  margin-bottom: 12px;
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-md, 8px);
}

/* ===== 已选条件 chip 摘要行 ===== */
.active-filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background: var(--color-bg-sunken, var(--surface-2));
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-md, 8px);
}
.active-filter-label {
  font-size: 0.75rem;
  color: var(--color-text-tertiary, var(--text-3));
  margin-right: 2px;
}
.active-filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 260px;
  padding: 3px 8px;
  border: 1px solid var(--color-primary-light, #7EA6E0);
  border-radius: 999px;
  background: var(--color-primary-lighter, var(--brand-subtle));
  color: var(--color-primary, #2C5AA0);
  font-size: 0.75rem;
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-primary, #2C5AA0);
    color: #fff;
    border-color: var(--color-primary, #2C5AA0);
  }
}
.chip-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.active-filter-clear {
  margin-left: auto;
  border: none;
  background: none;
  padding: 0 4px;
  font-size: 0.75rem;
  font-family: var(--font-body);
  color: var(--color-text-tertiary, var(--text-3));
  cursor: pointer;

  &:hover { color: var(--color-primary); text-decoration: underline; }
}

/* ===== 列设置面板 ===== */
.col-setting-wrap { position: relative; }
.btn-col-setting {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-md, 8px);
  background: var(--color-surface, #fff);
  font-size: 0.8125rem;
  font-family: var(--font-body);
  color: var(--color-text-secondary, var(--text-2));
  cursor: pointer;

  &:hover { border-color: var(--color-primary); color: var(--color-primary); }
}
.col-setting-menu {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  z-index: 20;
  min-width: 168px;
  padding: 8px;
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-md, 8px);
  box-shadow: var(--shadow-md, 0 4px 12px rgba(0,0,0,0.1));
}
.col-setting-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 6px 6px;
  border-bottom: 1px solid var(--color-border-light, var(--border-1));
  margin-bottom: 4px;
  font-size: 0.75rem;
  color: var(--color-text-tertiary, var(--text-3));
}
.col-setting-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 6px;
  border-radius: 4px;
  font-size: 0.8125rem;
  color: var(--color-text-primary);
  cursor: pointer;

  &:hover { background: var(--color-surface-hover, var(--surface-2)); }
  input { cursor: pointer; }
}
.col-setting-hint {
  margin: 4px 6px 0;
  font-size: 0.6875rem;
  color: var(--color-text-tertiary, var(--text-3));
}

/* ===== 悬浮「工单速览卡」 ===== */
/* 替代原先的 show-overflow-tooltip——它把整个单元格文本（含描述、标签）
   糊成一片；描述属长文本，tooltip 不能滚动/复制，不该放描述 */
.ticket-peek {
  max-width: 380px;
  font-size: 0.75rem;
  line-height: 1.6;
}
.peek-title {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--color-text-primary, var(--text-1));
  margin-bottom: 6px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--color-border-light, var(--border-1));
  white-space: normal;
  word-break: break-word;
}
.peek-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.peek-label {
  flex: 0 0 68px;
  color: var(--color-text-tertiary, var(--text-3));
}
.peek-value {
  flex: 1;
  color: var(--color-text-primary, var(--text-1));
  white-space: normal;
  word-break: break-word;

  &.sla-warning { color: var(--warning); font-weight: 500; }
  &.sla-breached { color: var(--danger); font-weight: 500; }
}

/* 服务/分类列的次要文本 */
.cell-muted {
  color: var(--color-text-secondary, var(--text-2));
  font-size: 0.8125rem;
}

/* 首响状态徽标（B1） */
.fr-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 0.75rem;
  white-space: nowrap;
  cursor: help;

  &.fr-ok {
    background: rgba(22, 163, 74, 0.1);
    color: var(--success);
  }
  &.fr-breached {
    background: rgba(239, 68, 68, 0.12);
    color: var(--danger);
    font-weight: var(--weight-medium);
  }
  &.fr-risk {
    background: rgba(245, 158, 11, 0.14);
    color: var(--warning);
    font-weight: var(--weight-medium);
  }
  &.fr-waiting {
    background: var(--color-bg-sunken, var(--surface-2));
    color: var(--color-text-tertiary, var(--text-3));
  }
}

/*
  自适应网格：列数随容器宽度自动增减，控件填满可用宽度但不被拉宽。

  此前两版都不理想：
  - `flex: 1` → 5 个下拉横向拉满整行，宽屏上每个宽达 300px+，扫读困难
  - 定宽 176px 左对齐 → 宽屏右侧空掉约 600px（截图 3 的问题）
  `auto-fit + minmax(160px, 1fr)` 是两者的解：容器能放几列就放几列，
  剩余宽度由这几列均分（单列不会超过 1fr 的分摊值），不留右侧空白也不过宽。
*/
.advanced-filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  align-items: center;
  gap: 8px 10px;
  margin-bottom: 8px;
}
.advanced-filter-grid:last-child { margin-bottom: 0; }

.advanced-filter-panel .filter-select {
  /* 宽度由 grid 列分配，此处不再定宽 */
  width: 100%;
  min-width: 0;
  padding: 6px 10px;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: 6px;
  font-size: 0.8125rem;
  background: var(--color-surface, #fff);
}

/*
  日期区间跨 2 列：内含两个 date input + 标签 + 分隔符，
  占单列会把两个 input 压到不可用的宽度。
  窄屏（单列布局）时 span 2 会溢出，故 640px 以下退回 1 列。
*/
.date-range-group {
  grid-column: span 2;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.date-range-label {
  font-size: 0.75rem;
  color: var(--color-text-tertiary, var(--text-3));
  white-space: nowrap;
}
.date-range-sep {
  color: var(--color-text-tertiary, var(--text-3));
  font-size: 0.75rem;
  flex-shrink: 0;
}
.advanced-filter-panel .filter-date-input {
  /* 两个 input 均分日期组的剩余宽度 */
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: 6px;
  font-size: 0.8125rem;
}

@media (max-width: 640px) {
  .date-range-group { grid-column: span 1; }
}

/* 「收起」固定在标签行尾——标签用 flex-wrap 填充，auto 把按钮推到行尾 */
.btn-clear-advanced {
  margin-left: auto;
  padding: 6px 14px;
  border: none;
  background: transparent;
  color: var(--color-text-tertiary, var(--text-3));
  cursor: pointer;
  font-size: 0.8125rem;
  flex-shrink: 0;
}
.btn-clear-advanced:hover { color: var(--color-primary, var(--brand)); }

.btn-clear-filters {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 0.8125rem;
  cursor: pointer;
}

.btn-clear-filters:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.advanced-filter-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}

/* 无热门标签时本行只承载「收起」按钮，去掉上边距避免出现空白带 */
.advanced-filter-tags--empty {
  margin-top: 0;
}

.tag-filter-label {
  font-size: 0.75rem;
  color: var(--color-text-tertiary);
}

.tag-filter-chip {
  padding: 2px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: 999px;
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 0.75rem;
  cursor: pointer;
}

.tag-filter-chip.active,
.tag-filter-chip:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background: var(--color-primary-lighter);
}

.bulk-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 16px;
  background: var(--color-primary-lighter);
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-lg);
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.bulk-text {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-primary);
}

.bulk-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.bulk-dropdown { position: relative; }

.bulk-btn,
.bulk-btn-plain {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-primary);
  font-size: var(--text-xs);
  font-family: var(--font-body);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover { background: var(--color-primary); color: white; }
}

.bulk-btn-danger {
  border-color: var(--state-error);
  color: var(--state-error);

  &:hover { background: var(--state-error); color: white; }
}

.bulk-btn-plain {
  border-color: var(--color-border-light);
  color: var(--color-text-secondary);
  background: transparent;

  &:hover {
    background: var(--color-surface);
    color: var(--color-text-primary);
    border-color: var(--color-text-secondary);
  }
}

.bulk-menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  min-width: 140px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  z-index: 10;
  padding: 4px;

  button {
    width: 100%;
    padding: 8px 12px;
    border: none;
    background: transparent;
    text-align: left;
    font-size: var(--text-sm);
    font-family: var(--font-body);
    color: var(--color-text-primary);
    border-radius: var(--radius-sm);
    cursor: pointer;

    &:hover:not(:disabled) { background: var(--color-primary-lighter); color: var(--color-primary); }

    /* 状态机不允许的目标状态：置灰且不可点，hover 无反馈 */
    &:disabled {
      color: var(--color-text-tertiary);
      cursor: not-allowed;
      opacity: 0.55;
    }

    /* 部分可执行时把「N/M」标在行尾，用户点之前就知道会影响多少张 */
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }
}

.bulk-opt-count {
  flex-shrink: 0;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

/* Table */
.table-container {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  margin-bottom: 16px;
}

/* ========== 卡片视图 ========== */
/* 卡片视图样式已随模板迁入 components/ticket/TicketCardGrid.vue */

/* el-table 主题对齐
   注意：el-table 渲染的是自己的内部 DOM，原先针对裸 thead/th/td 的选择器
   不再命中，需用 :deep() 穿透 scoped 作用域 */
.tickets-table {
  width: 100%;
  font-size: var(--text-sm);

  /* 表头：沿用原有的小号大写灰字风格 */
  :deep(.el-table__header th.el-table__cell) {
    background: var(--color-bg-sunken);
    padding: 10px 0;
    font-size: var(--text-xs);
    font-weight: var(--weight-medium);
    color: var(--text-3);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    border-bottom: 1px solid var(--color-border-light);
  }

  /* 列宽拖拉手柄：加宽命中区域并给出明确的 col-resize 光标，
     默认 1px 边框太窄，用户常拖不到 */
  :deep(.el-table__header th.el-table__cell > .cell) {
    padding-left: 16px;
    padding-right: 16px;
  }
  :deep(.el-table--border th.el-table__cell:not(:last-child))::after {
    content: '';
    position: absolute;
    right: -3px;
    top: 25%;
    height: 50%;
    width: 6px;
    cursor: col-resize;
  }

  :deep(.el-table__body td.el-table__cell) {
    padding: 12px 0;
    vertical-align: top;
  }
  :deep(.el-table__body td.el-table__cell > .cell) {
    padding-left: 16px;
    padding-right: 16px;
  }

  /* 行 hover / 选中：沿用主色浅底 */
  :deep(.el-table__body tr:hover > td.el-table__cell) {
    background: var(--color-primary-lighter);
  }
  :deep(.el-table__body tr.selected > td.el-table__cell) {
    background: var(--color-primary-lighter);
  }

  :deep(.el-table__row) { cursor: pointer; }
}

/* SLA 进度列：后端计算的 slaProgress / slaBreached（6.15），
   此前列表页完全没展示，运维看不到哪些工单即将超时 */
.sla-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sla-bar {
  flex: 1;
  height: 6px;
  min-width: 40px;
  border-radius: 3px;
  background: var(--color-border-light);
  overflow: hidden;
}

.sla-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.3s ease;

  &.sla-normal { background: var(--color-primary); }
  &.sla-warning { background: #F59E0B; }
  &.sla-breached { background: #EF4444; }
}

.sla-text {
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;

  &.sla-normal { color: var(--color-text-tertiary); }
  &.sla-warning { color: var(--warning); font-weight: var(--weight-medium); }
  &.sla-breached { color: var(--danger); font-weight: var(--weight-medium); }
}

/* 列宽已调整的提示与重置入口 */
.table-footnote {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 8px 16px;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  border-top: 1px solid var(--color-border-light);
}

.link-btn {
  border: none;
  background: none;
  padding: 0;
  font-size: var(--text-xs);
  font-family: var(--font-body);
  color: var(--color-primary);
  cursor: pointer;

  &:hover { text-decoration: underline; }
}

.ticket-id {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--color-primary-light);
  text-decoration: none;
  font-weight: var(--weight-medium);

  &:hover { text-decoration: underline; }
}

.ticket-title-cell { max-width: 400px; }

.ticket-title {
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);
  text-decoration: none;
  display: block;
  margin-bottom: 4px;

  &:hover { color: var(--color-primary); }
}

/* 原 .ticket-desc / .ticket-subtitle 已删除：
   描述与服务不再挤在标题单元格内——服务提为独立列，
   描述移入悬浮速览卡（描述是长文本，不适合塞进表格行） */

.ticket-tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.ticket-tag {
  padding: 2px 8px;
  font-size: 11px;
  color: var(--color-text-secondary);
  background: var(--color-bg-sunken);
  border-radius: var(--radius-full);
}

.ticket-tag-more {
  padding: 2px 8px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.category-cell {
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.status-badge {
  display: inline-block;
  padding: 4px 12px;
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  border-radius: var(--radius-full);
  white-space: nowrap;

  &.status-pending { background: var(--state-warning-bg); color: var(--state-warning); }
  &.status-processing { background: var(--color-primary-light); color: white; }
  &.status-resolved { background: var(--state-success); color: white; }
  &.status-closed { background: var(--surface-2); color: var(--text-2); }
  &.status-void { background: var(--surface-2); color: var(--text-3); text-decoration: line-through; }
}

.priority-badge {
  display: inline-block;
  padding: 4px 12px;
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  border-radius: var(--radius-full);
  white-space: nowrap;

  &.priority-urgent { background: var(--state-error); color: white; }
  &.priority-high { background: #EA580C; color: white; }
  &.priority-medium { background: var(--color-primary-lighter); color: var(--color-primary); }
  &.priority-low { background: var(--surface-2); color: var(--text-2); }
}

.assignee { color: var(--color-text-primary); font-weight: var(--weight-medium); }

/* 负责人头像 + 名字 - 对齐设计稿 */
.assignee-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.assignee-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--color-primary-lighter);
  color: var(--color-primary-light);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  flex-shrink: 0;
}

.assignee-name {
  font-size: var(--text-sm);
  color: var(--color-text-primary);
}
.timestamp { color: var(--color-text-tertiary); font-size: var(--text-xs); }

.actions {
  display: flex;
  gap: 4px;
  align-items: center;
}

.action-icon-btn {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  padding: 6px;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-primary-lighter);
    color: var(--color-primary);
  }
}

.action-icon-btn-danger:hover {
  background: rgba(220, 38, 38, 0.08);
  color: var(--state-error);
}

.action-link {
  font-size: var(--text-sm);
  color: var(--color-primary);
  text-decoration: none;
  font-weight: var(--weight-medium);

  &:hover { text-decoration: underline; }
}

.error-state-inline {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px;
}
.error-state-inline p {
  color: var(--color-text-tertiary);
  margin: 0;
}


/* 刷新按钮旋转动画 */
.spin-animation {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
