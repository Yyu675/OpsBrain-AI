import { computed, ref, type ComputedRef, type Ref } from 'vue'

/**
 * 服务端分页状态与页码计算。
 *
 * 建立动因：TicketList 与 AlertList 各写了一份 `pageNumbers` / `pageStart` /
 * `pageEnd`，算法逐字符相同（省略号策略、5 页阈值、边界处理）。
 * 两份实现意味着改省略号策略要改两处，漏一处就出现两个列表页翻页行为不一致。
 *
 * 只管分页状态与派生计算，**不管数据拉取**——各页的查询参数差异大
 * （工单有 9 个筛选维度、告警只有 2 个），强行统一 loader 会把差异塞进
 * 一堆可选参数里，反而比各自写清楚更难维护。
 */

export interface UseServerPaginationOptions {
  /** 每页条数，默认 10 */
  pageSize?: number
  /**
   * 页码窗口：当前页两侧各显示多少页。默认 1（即 `1 … 4 [5] 6 … 20`）。
   * 总页数不超过 `windowSize * 2 + 3` 时全部展开，不出现省略号。
   */
  windowSize?: number
}

export type PageItem = number | 'ellipsis'

export interface UseServerPaginationReturn {
  currentPage: Ref<number>
  pageSize: Ref<number>
  /**
   * 匹配总条数（后端按当前筛选统计，非全库）。
   *
   * 声明为只读：外部数据源变体下它由 store 的 computed 派生，
   * 写它不会影响 store，只会造成「改了没生效」的困惑。
   * 写入统一走 setMeta。
   */
  total: Readonly<Ref<number>>
  totalPages: Readonly<Ref<number>>

  /** 页码按钮序列，含省略号占位 */
  pageNumbers: ComputedRef<PageItem[]>
  /** 当前页区间起始（1-based，供「显示 X-Y 共 N 条」） */
  pageStart: ComputedRef<number>
  /** 当前页区间结束 */
  pageEnd: ComputedRef<number>

  hasPrev: ComputedRef<boolean>
  hasNext: ComputedRef<boolean>

  /**
   * 从后端响应写入分页元信息。
   *
   * 外部数据源变体（useServerPaginationFrom）下为空操作——
   * 元信息由 store 的加载动作写入。
   */
  setMeta: (meta: { total: number; totalPages: number }) => void

  /**
   * 跳到指定页。越界或未变化时返回 false（调用方据此决定是否重新拉取）。
   */
  goToPage: (page: number) => boolean

  /** 回到第 1 页。筛选或排序变化后必须调用——否则用户会停在「按新条件本不该存在的第 5 页」 */
  resetPage: () => void

  /**
   * 末页被删空后退一页。
   *
   * 场景：末页仅剩 1 条，删除后该页变空白。返回 true 表示页码已调整、
   * 调用方需要重新拉取。
   */
  retreatIfEmptied: (currentRowCount: number) => boolean
}

export function useServerPagination(
  options: UseServerPaginationOptions = {}
): UseServerPaginationReturn {
  const currentPage = ref(1)
  const pageSize = ref(options.pageSize ?? 10)
  const total = ref(0)
  const totalPages = ref(0)

  return buildPagination({
    currentPage,
    pageSize,
    total,
    totalPages,
    windowSize: options.windowSize,
    setMeta: (meta) => {
      total.value = meta.total
      totalPages.value = meta.totalPages
    },
  })
}

/**
 * 分页元信息来自外部（如 Pinia store 的 computed）时的变体。
 *
 * 适用于 total/totalPages 已由 store 持有的场景——composable 再存一份
 * 必然与 store 漂移（同 useExternalResourceState 的取舍）。
 * 此时 `setMeta` 为空操作：写入责任在 store 的加载动作里。
 */
export function useServerPaginationFrom(
  source: { total: () => number; totalPages: () => number },
  options: UseServerPaginationOptions = {}
): UseServerPaginationReturn {
  const currentPage = ref(1)
  const pageSize = ref(options.pageSize ?? 10)

  return buildPagination({
    currentPage,
    pageSize,
    total: computed(source.total),
    totalPages: computed(source.totalPages),
    windowSize: options.windowSize,
    // 元信息由 store 写入，此处不接管
    setMeta: () => {},
  })
}

/** 两个变体共用的派生计算与页码操作 */
function buildPagination(ctx: {
  currentPage: Ref<number>
  pageSize: Ref<number>
  total: Readonly<Ref<number>>
  totalPages: Readonly<Ref<number>>
  windowSize?: number
  setMeta: (meta: { total: number; totalPages: number }) => void
}): UseServerPaginationReturn {
  const { currentPage, pageSize, total, totalPages } = ctx

  const windowSize = Math.max(0, ctx.windowSize ?? 1)
  // 全部展开的阈值：首页 + 末页 + 当前页 + 两侧窗口 + 两个省略号位
  const expandThreshold = windowSize * 2 + 3

  const pageNumbers = computed<PageItem[]>(() => {
    const totalP = Math.max(1, totalPages.value)
    const cur = currentPage.value
    const pages: PageItem[] = []

    if (totalP <= expandThreshold) {
      for (let i = 1; i <= totalP; i++) pages.push(i)
      return pages
    }

    pages.push(1)
    // 当前页离首页足够远才需要左省略号
    if (cur > windowSize + 2) pages.push('ellipsis')

    const start = Math.max(2, cur - windowSize)
    const end = Math.min(totalP - 1, cur + windowSize)
    for (let i = start; i <= end; i++) pages.push(i)

    if (cur < totalP - windowSize - 1) pages.push('ellipsis')
    pages.push(totalP)
    return pages
  })

  const pageStart = computed(() =>
    total.value === 0 ? 0 : (currentPage.value - 1) * pageSize.value + 1
  )
  const pageEnd = computed(() =>
    Math.min(currentPage.value * pageSize.value, total.value)
  )

  const hasPrev = computed(() => currentPage.value > 1)
  const hasNext = computed(() => currentPage.value < totalPages.value)

  const goToPage = (page: number): boolean => {
    if (page < 1 || page > totalPages.value || page === currentPage.value) {
      return false
    }
    currentPage.value = page
    return true
  }

  const resetPage = () => {
    currentPage.value = 1
  }

  const retreatIfEmptied = (currentRowCount: number): boolean => {
    if (currentRowCount > 0) return false
    if (currentPage.value <= 1) return false
    // total 为 0 说明筛选条件下确实没有数据，不是「删空了当前页」
    if (total.value <= 0) return false
    currentPage.value = Math.max(1, totalPages.value)
    return true
  }

  return {
    currentPage,
    pageSize,
    total,
    totalPages,
    pageNumbers,
    pageStart,
    pageEnd,
    hasPrev,
    hasNext,
    setMeta: ctx.setMeta,
    goToPage,
    resetPage,
    retreatIfEmptied,
  }
}
