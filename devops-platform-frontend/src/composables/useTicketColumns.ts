/**
 * 工单表格的列宽 / 列可见性偏好。
 *
 * 从 `TicketList.vue` 抽出。这一组同样是天然的内聚单元——
 * 它们共享同一套「默认值 + 用户覆盖 + 版本失效」的三段式规则。
 *
 * <h3>为什么这块的持久化必须校验，而不能直接 JSON.parse 回填</h3>
 * 数据来自 `localStorage`，那是**用户可改、且跨版本残留**的存储：
 * <ul>
 *   <li>列宽写成 0 或负数 → 那一列直接不可见，用户以为「数据丢了」，
 *       却完全不知道该去哪把它调回来；</li>
 *   <li>列名是上个版本的 → 表里多出一个渲染不出来的幽灵键；</li>
 *   <li>值不是数字/布尔 → 传给 el-table 后样式计算出 NaN，整行错位。</li>
 * </ul>
 * 因此回填走白名单 + 类型 + 范围三重校验，任何不合法的项<b>静默丢弃并回落默认</b>——
 * 宁可让用户的自定义丢一次，也不能让页面变成一个他自己修不好的坏状态。
 *
 * <h3>版本号的作用</h3>
 * 列集合一旦增删（如 B1 新增「首响」列），旧布局的键位就对不上了。
 * `COL_*_VERSION` 递增会让 `loadPersisted` 判定旧数据失效并丢弃，
 * 避免出现「新列没宽度、旧列宽度错位」的混合状态。
 * <b>改列集合时必须同步递增版本号</b>，这条约束由测试守住。
 */
import { computed, ref } from 'vue'
import { clearPersisted, debounce, loadPersisted, savePersisted } from '@/utils/persist'

/** 列宽持久化键与版本（版本变更时旧布局作废，避免列增删后错位） */
export const COL_WIDTH_KEY = 'ticket-table-col-width'
// v3：B1 新增「首响」列，列集合再次变化，版本号递增使旧布局作废
export const COL_WIDTH_VERSION = 3
export const COL_VISIBLE_KEY = 'ticket-table-col-visible'
export const COL_VISIBLE_VERSION = 3

/** 列宽合法区间（px）。超出即视为被篡改或旧版本残留 */
const MIN_COL_WIDTH = 40
const MAX_COL_WIDTH = 800

/**
 * 默认列宽（px）
 *
 * 注意：**标题列不在此表中**——它只设 min-width 由 el-table 弹性吸收剩余空间。
 * 此前给所有列都设了固定 width，el-table 在全部列定宽时不会拉伸填满容器，
 * 剩余空间变成右侧一大片空白 gutter（6.37 引入的回归）。
 */
export const DEFAULT_COL_WIDTHS: Record<string, number> = {
  selection: 48,
  id: 150,
  service: 130,
  category: 104,
  priority: 92,
  status: 92,
  firstResponse: 96,
  handlingStage: 100,
  sla: 128,
  assignee: 116,
  rootCause: 100,
  createdAt: 108,
  updatedAt: 108,
  actions: 92,
}

/** 可配置列定义（顺序即展示顺序；selection/title/actions 为固定列不可隐藏） */
export const CONFIGURABLE_COLUMNS: { key: string; label: string }[] = [
  { key: 'id', label: '工单 ID' },
  { key: 'service', label: '服务' },
  { key: 'category', label: '分类' },
  { key: 'priority', label: '优先级' },
  { key: 'status', label: '状态' },
  { key: 'firstResponse', label: '首响' },
  { key: 'handlingStage', label: '处置阶段' },
  { key: 'sla', label: 'SLA' },
  { key: 'assignee', label: '负责人' },
  { key: 'rootCause', label: '根因分类' },
  { key: 'createdAt', label: '创建时间' },
  { key: 'updatedAt', label: '更新时间' },
]

/** 默认可见列。更新时间默认隐藏（创建时间已在列表，避免首屏过密），可由用户开启 */
export const DEFAULT_COL_VISIBLE: Record<string, boolean> = {
  id: true,
  service: true,
  category: true,
  priority: true,
  status: true,
  firstResponse: true,
  handlingStage: true,
  sla: true,
  assignee: true,
  rootCause: true,
  createdAt: true,
  updatedAt: false,
}

/**
 * 列 label → 键 映射
 *
 * el-table 的 header-dragend 回调只给 column 对象，其 property 对自定义列
 * （SLA / 操作）为 undefined，故用 label 兜底定位是哪一列。
 */
export const LABEL_TO_KEY: Record<string, string> = {
  '工单 ID': 'id',
  '标题': 'title',
  '服务': 'service',
  '分类': 'category',
  '优先级': 'priority',
  '状态': 'status',
  '首响': 'firstResponse',
  '处置阶段': 'handlingStage',
  'SLA': 'sla',
  '负责人': 'assignee',
  '根因分类': 'rootCause',
  '创建时间': 'createdAt',
  '更新时间': 'updatedAt',
  '操作': 'actions',
}

export interface UseTicketColumnsOptions {
  /** 恢复默认后的提示（由调用方注入，composable 不直接依赖 UI 框架） */
  onReset?: () => void
}

export function useTicketColumns(options: UseTicketColumnsOptions = {}) {
  /** 当前列宽（用户拖拉后写入并持久化） */
  const columnWidths = ref<Record<string, number>>({ ...DEFAULT_COL_WIDTHS })

  /** 列可见性（用户自选并持久化——「灵活动态」而非静态固定） */
  const columnVisible = ref<Record<string, boolean>>({ ...DEFAULT_COL_VISIBLE })

  /**
   * 用户显式拖拉过的列
   *
   * 标题列默认不设 width（弹性填充）；一旦用户手动拖过，就改为尊重其固定宽度。
   */
  const userResized = ref<Record<string, boolean>>({})

  /** 列设置面板开关 */
  const colSettingOpen = ref(false)

  /** 标题列宽度：仅在用户拖拉过后才固定，否则交给 min-width 弹性吸收 */
  const titleWidth = computed(() =>
    userResized.value.title ? columnWidths.value.title : undefined
  )

  /** 是否存在用户调整过的列宽或列可见性（决定是否显示「恢复默认」） */
  const columnsResized = computed(() => {
    const widthChanged =
      Object.keys(DEFAULT_COL_WIDTHS).some(
        k => columnWidths.value[k] !== DEFAULT_COL_WIDTHS[k]
      ) || !!userResized.value.title
    const visibleChanged = Object.keys(DEFAULT_COL_VISIBLE).some(
      k => columnVisible.value[k] !== DEFAULT_COL_VISIBLE[k]
    )
    return widthChanged || visibleChanged
  })

  const persistColumnWidths = debounce(() => {
    savePersisted(
      COL_WIDTH_KEY,
      { widths: columnWidths.value, resized: userResized.value },
      COL_WIDTH_VERSION
    )
  }, 300)

  const persistColumnVisible = debounce(() => {
    savePersisted(COL_VISIBLE_KEY, columnVisible.value, COL_VISIBLE_VERSION)
  }, 300)

  /**
   * 从 localStorage 恢复列偏好。
   *
   * 只接受已知列名与合理数值——旧版本或被篡改的数据一律丢弃回落默认，
   * 详见文件头注释：宁可丢一次自定义，也不能让页面变成用户自己修不好的坏状态。
   */
  function restoreColumnPrefs() {
    const saved = loadPersisted<{
      widths?: Record<string, number>
      resized?: Record<string, boolean>
    }>(COL_WIDTH_KEY, COL_WIDTH_VERSION)

    if (saved && typeof saved === 'object' && saved.widths) {
      const merged = { ...DEFAULT_COL_WIDTHS }
      const resized: Record<string, boolean> = {}
      for (const [k, v] of Object.entries(saved.widths)) {
        // title 不在 DEFAULT_COL_WIDTHS（默认弹性），但用户拖过时需要恢复
        const known = k in DEFAULT_COL_WIDTHS || k === 'title'
        if (known && typeof v === 'number' && v >= MIN_COL_WIDTH && v <= MAX_COL_WIDTH) {
          merged[k] = Math.round(v)
          if (saved.resized?.[k]) resized[k] = true
        }
      }
      columnWidths.value = merged
      userResized.value = resized
    }

    const savedVisible = loadPersisted<Record<string, boolean>>(
      COL_VISIBLE_KEY,
      COL_VISIBLE_VERSION
    )
    if (savedVisible && typeof savedVisible === 'object') {
      const merged = { ...DEFAULT_COL_VISIBLE }
      for (const [k, v] of Object.entries(savedVisible)) {
        if (k in DEFAULT_COL_VISIBLE && typeof v === 'boolean') merged[k] = v
      }
      columnVisible.value = merged
    }
  }

  /** 切换某列显示/隐藏 */
  function toggleColumn(key: string) {
    columnVisible.value = { ...columnVisible.value, [key]: !columnVisible.value[key] }
    persistColumnVisible()
  }

  /** 拖拉列宽后记录并持久化 */
  function onHeaderDragend(
    newWidth: number,
    _oldWidth: number,
    column: { property?: string; label?: string }
  ) {
    const key = column?.property || (column?.label ? LABEL_TO_KEY[column.label] : undefined)
    if (!key) return
    // 标题列不在 DEFAULT_COL_WIDTHS 中（默认弹性），拖过之后才固定其宽度
    if (key !== 'title' && !(key in DEFAULT_COL_WIDTHS)) return
    columnWidths.value = { ...columnWidths.value, [key]: Math.round(newWidth) }
    userResized.value = { ...userResized.value, [key]: true }
    persistColumnWidths()
  }

  /** 恢复默认列宽与列可见性 */
  function resetColumnWidths() {
    columnWidths.value = { ...DEFAULT_COL_WIDTHS }
    columnVisible.value = { ...DEFAULT_COL_VISIBLE }
    userResized.value = {}
    // 同时清掉存储：只重置内存而不清存储的话，刷新后旧布局又回来了
    clearPersisted(COL_WIDTH_KEY)
    clearPersisted(COL_VISIBLE_KEY)
    options.onReset?.()
  }

  return {
    columnWidths,
    columnVisible,
    userResized,
    colSettingOpen,
    titleWidth,
    columnsResized,
    restoreColumnPrefs,
    toggleColumn,
    onHeaderDragend,
    resetColumnWidths,
  }
}
