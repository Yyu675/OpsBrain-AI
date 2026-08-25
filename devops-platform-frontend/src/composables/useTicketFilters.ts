/**
 * 工单列表筛选状态与派生逻辑。
 *
 * 从 `TicketList.vue`（2679 行）抽出。这几段是天然的内聚单元——
 * 它们共享同一组 ref，且彼此有**必须成对维护**的约束：
 *
 * <h3>为什么值得单独成文件：三处清单必须同步</h3>
 * 每加一个筛选维度，要同时改三个地方：
 * 1. `hasFilters` —— 决定「清空筛选」按钮显不显示；
 * 2. `activeFilterChips` —— 把它渲染成一枚可见的 chip；
 * 3. `removeFilterChip` —— 让那枚 chip 的 × 真的能点掉。
 *
 * 散在 2679 行里时，这三处相隔数百行，漏改任何一处都**不会报错**：
 * - 漏 1 → 明明筛着，「清空」按钮却不出现，用户找不到出口；
 * - 漏 2 → 筛选生效了但界面上看不见，用户不知道为什么列表少了一半；
 * - 漏 3 → chip 显示出来却删不掉，是个点了没反应的死按钮。
 *
 * 收拢到同一个文件、上下相邻，是让这条约束「肉眼可查」的最低成本做法；
 * 配套的对称性测试则把它变成「机器可查」。
 *
 * <h3>关于 reset 的顺序</h3>
 * 清空类操作必须先 `cancel()` 掉在途的搜索防抖，再改 ref。
 * 反过来的话，300ms 内那个待触发的回调会把 `appliedQuery` 又写回去——
 * chip 消失了、筛选却还在，是最难排查的一类「幽灵状态」。
 * 因此本模块把防抖控制器作为依赖注入，而不是让调用方自己记得去 cancel。
 */
import { computed, ref, type Ref } from 'vue'
import {
  getPriorityLabel,
  getStatusLabel,
  type TicketPriority,
  type TicketStatus,
} from '@/stores/tickets'

/** 一枚可见、可独立移除的筛选条件 */
export interface FilterChip {
  key: string
  label: string
  /** 供 removeFilterChip 定位要重置哪个筛选项 */
  kind: string
  /** 仅多选维度（标签）使用：标明移除的是哪一个值 */
  value?: string
}

export interface UseTicketFiltersOptions {
  /**
   * 取消在途的防抖搜索。
   *
   * 传函数而非 debounce 对象本身，是为了避开 TDZ：调用方的
   * `applySearch` 常在本 composable 之后才定义（它依赖 fetchList），
   * 直接传对象会在初始化时就求值而报 "Cannot access before initialization"。
   * 包一层箭头函数则推迟到真正调用时才解析。
   */
  cancelSearch: () => void
  /** 页码 ref：任何筛选变化都要回到第 1 页 */
  currentPage: Ref<number>
  /** 重新拉取列表 */
  fetchList: () => void | Promise<void>
}

export function useTicketFilters(options: UseTicketFiltersOptions) {
  const { cancelSearch, currentPage, fetchList } = options

  // ==================== 状态 ====================

  /** 搜索框里的即时值（受防抖控制，不直接参与查询） */
  const searchQuery = ref('')
  /** 真正参与查询的关键词（防抖落定后才更新） */
  const appliedQuery = ref('')
  const statusFilter = ref<'all' | TicketStatus>('all')
  const priorityFilter = ref<'all' | TicketPriority>('all')
  const serviceFilter = ref<string>('all')
  const categoryFilter = ref<string>('all')
  const assigneeFilter = ref<string>('all')
  const tagFilters = ref<string[]>([])
  const dateFrom = ref('')
  const dateTo = ref('')

  // ==================== 派生 ====================

  /**
   * 当前是否存在任何筛选条件。
   *
   * 注意这里用 `appliedQuery` 而非 `searchQuery`：用户刚敲了两个字、
   * 防抖还没落定时，列表尚未被筛过，此时就亮出「清空筛选」是误导。
   */
  const hasFilters = computed(() =>
    !!appliedQuery.value ||
    statusFilter.value !== 'all' ||
    priorityFilter.value !== 'all' ||
    serviceFilter.value !== 'all' ||
    categoryFilter.value !== 'all' ||
    assigneeFilter.value !== 'all' ||
    tagFilters.value.length > 0 ||
    !!dateFrom.value ||
    !!dateTo.value
  )

  /**
   * 已选筛选条件摘要。
   *
   * 收起高级筛选面板后仍能看到当前筛了什么，且可逐条移除。
   */
  const activeFilterChips = computed<FilterChip[]>(() => {
    const chips: FilterChip[] = []
    if (appliedQuery.value) {
      chips.push({ key: 'kw', label: `关键词：${appliedQuery.value}`, kind: 'keyword' })
    }
    if (statusFilter.value !== 'all') {
      chips.push({ key: 'st', label: `状态：${getStatusLabel(statusFilter.value)}`, kind: 'status' })
    }
    if (priorityFilter.value !== 'all') {
      chips.push({ key: 'pr', label: `优先级：${getPriorityLabel(priorityFilter.value)}`, kind: 'priority' })
    }
    if (serviceFilter.value !== 'all') {
      chips.push({ key: 'sv', label: `服务：${serviceFilter.value}`, kind: 'service' })
    }
    if (categoryFilter.value !== 'all') {
      chips.push({ key: 'ca', label: `分类：${categoryFilter.value}`, kind: 'category' })
    }
    if (assigneeFilter.value !== 'all') {
      chips.push({ key: 'as', label: `负责人：${assigneeFilter.value}`, kind: 'assignee' })
    }
    if (dateFrom.value) {
      chips.push({ key: 'df', label: `起：${dateFrom.value}`, kind: 'dateFrom' })
    }
    if (dateTo.value) {
      chips.push({ key: 'dt', label: `止：${dateTo.value}`, kind: 'dateTo' })
    }
    // 标签是多选：每个标签一枚可独立移除的 chip
    for (const tag of tagFilters.value) {
      chips.push({ key: `tag-${tag}`, label: `标签：${tag}`, kind: 'tag', value: tag })
    }
    return chips
  })

  // ==================== 操作 ====================

  /** 清掉关键词（含在途防抖），供移除 chip 与一键清空复用 */
  function resetKeyword() {
    // 顺序不能反：先 cancel 再改 ref。
    // 否则在途回调会在 300ms 后把 appliedQuery 写回去
    cancelSearch()
    searchQuery.value = ''
    appliedQuery.value = ''
  }

  /**
   * 移除单个筛选条件并重新拉取。
   *
   * ⚠️ 这里的 switch 必须覆盖 `activeFilterChips` 产出的每一种 kind。
   * 漏一个就是点了没反应的死按钮，且不会有任何报错。
   */
  function removeFilterChip(chip: FilterChip) {
    switch (chip.kind) {
      case 'keyword': resetKeyword(); break
      case 'status': statusFilter.value = 'all'; break
      case 'priority': priorityFilter.value = 'all'; break
      case 'service': serviceFilter.value = 'all'; break
      case 'category': categoryFilter.value = 'all'; break
      case 'assignee': assigneeFilter.value = 'all'; break
      case 'dateFrom': dateFrom.value = ''; break
      case 'dateTo': dateTo.value = ''; break
      case 'tag':
        if (chip.value) tagFilters.value = tagFilters.value.filter(t => t !== chip.value)
        break
    }
    // 筛选变了等于换了一个结果集，停在原页码会落到「筛完后本不该存在的第 5 页」，
    // 用户看到一片空白却以为是没有数据
    currentPage.value = 1
    void fetchList()
  }

  /** 一键清空全部筛选 */
  function clearFilters() {
    resetKeyword()
    statusFilter.value = 'all'
    priorityFilter.value = 'all'
    serviceFilter.value = 'all'
    categoryFilter.value = 'all'
    assigneeFilter.value = 'all'
    // 标签是数组，容易在「清空标量筛选」时被漏掉
    tagFilters.value = []
    dateFrom.value = ''
    dateTo.value = ''
    currentPage.value = 1
    void fetchList()
  }

  /** 切换某个标签的选中态（多选） */
  function toggleTagFilter(tag: string, onChanged: () => void | Promise<void>) {
    const idx = tagFilters.value.indexOf(tag)
    if (idx >= 0) tagFilters.value.splice(idx, 1)
    else tagFilters.value.push(tag)
    void onChanged()
  }

  return {
    // 状态
    searchQuery,
    appliedQuery,
    statusFilter,
    priorityFilter,
    serviceFilter,
    categoryFilter,
    assigneeFilter,
    tagFilters,
    dateFrom,
    dateTo,
    // 派生
    hasFilters,
    activeFilterChips,
    // 操作
    resetKeyword,
    removeFilterChip,
    clearFilters,
    toggleTagFilter,
  }
}
