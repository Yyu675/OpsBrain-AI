/**
 * 工单表格列偏好 composable 测试。
 *
 * ── 这块最该守的是「坏数据不能让页面变成用户修不好的状态」──────
 * 列偏好存在 localStorage，那是**用户可改、且跨版本残留**的存储。
 * 回填时若不校验：
 * - 列宽写成 0 / 负数 → 那一列直接看不见。用户以为「数据丢了」，
 *   而列宽调节入口本身就在那一列的表头上——**他没有任何办法把它调回来**；
 * - 值不是数字 → 传给 el-table 后样式算出 NaN，整行错位；
 * - 列名是上个版本的 → 表里多出渲染不出来的幽灵键。
 *
 * 所以这里逐条验证三重校验（白名单 / 类型 / 范围）都真的生效，
 * 且任何不合法项都**静默丢弃回落默认**而不是让页面崩掉。
 *
 * ── 用真实 localStorage 而非 mock ────────────────────────────
 * jsdom 提供了可用的 localStorage，而这块逻辑的价值恰恰在于
 * 「面对存储里的脏数据怎么办」——mock 掉存储就等于把被测对象换成了假的。
 * 每个用例前 clear()，保持隔离。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  COL_VISIBLE_KEY,
  COL_VISIBLE_VERSION,
  COL_WIDTH_KEY,
  COL_WIDTH_VERSION,
  CONFIGURABLE_COLUMNS,
  DEFAULT_COL_VISIBLE,
  DEFAULT_COL_WIDTHS,
  LABEL_TO_KEY,
  useTicketColumns,
} from '../useTicketColumns'

/**
 * persist 给所有键加了 `__store__:` 前缀。
 * 测试里必须带上，否则写进去的种子数据 loadPersisted 根本读不到——
 * 那样「校验是否生效」的用例会因为「压根没读到数据」而假通过。
 */
const PREFIX = '__store__:'

/** 按 persist 的载荷格式直接写入，模拟「上次会话留下的数据」 */
function seed(key: string, value: unknown, version: number) {
  localStorage.setItem(PREFIX + key, JSON.stringify({ version, value, savedAt: Date.now() }))
}

beforeEach(() => {
  localStorage.clear()
  vi.useRealTimers()
})

describe('默认值', () => {
  it('初始为默认列宽与默认可见性，且未标记为已调整', () => {
    const c = useTicketColumns()

    expect(c.columnWidths.value).toEqual(DEFAULT_COL_WIDTHS)
    expect(c.columnVisible.value).toEqual(DEFAULT_COL_VISIBLE)
    // 未调整过就显示「恢复默认」按钮，是个永远点不出效果的按钮
    expect(c.columnsResized.value).toBe(false)
  })

  it('标题列默认不设宽度——交给 min-width 弹性吸收剩余空间', () => {
    const c = useTicketColumns()

    // 给所有列都定宽时 el-table 不会拉伸填满容器，
    // 右侧会留一大片空白 gutter
    expect(c.titleWidth.value).toBeUndefined()
    expect(DEFAULT_COL_WIDTHS.title).toBeUndefined()
  })
})

describe('恢复持久化偏好', () => {
  it('无存储时保持默认', () => {
    const c = useTicketColumns()
    c.restoreColumnPrefs()

    expect(c.columnWidths.value).toEqual(DEFAULT_COL_WIDTHS)
  })

  it('恢复合法的列宽', () => {
    seed(COL_WIDTH_KEY, { widths: { id: 200, service: 160 }, resized: { id: true } }, COL_WIDTH_VERSION)
    const c = useTicketColumns()
    c.restoreColumnPrefs()

    expect(c.columnWidths.value.id).toBe(200)
    expect(c.columnWidths.value.service).toBe(160)
    // 未出现在存储里的列回落默认，而不是变成 undefined
    expect(c.columnWidths.value.status).toBe(DEFAULT_COL_WIDTHS.status)
    expect(c.columnsResized.value).toBe(true)
  })

  it('丢弃 0 / 负数宽度——否则那一列消失且用户无法调回', () => {
    seed(COL_WIDTH_KEY, { widths: { id: 0, service: -50 } }, COL_WIDTH_VERSION)
    const c = useTicketColumns()
    c.restoreColumnPrefs()

    // 列宽调节入口就在该列表头上，宽度 0 意味着入口本身也没了
    expect(c.columnWidths.value.id).toBe(DEFAULT_COL_WIDTHS.id)
    expect(c.columnWidths.value.service).toBe(DEFAULT_COL_WIDTHS.service)
  })

  it('丢弃超出上限的宽度——单列撑爆表格同样让人无从下手', () => {
    seed(COL_WIDTH_KEY, { widths: { id: 99999 } }, COL_WIDTH_VERSION)
    const c = useTicketColumns()
    c.restoreColumnPrefs()

    expect(c.columnWidths.value.id).toBe(DEFAULT_COL_WIDTHS.id)
  })

  it('丢弃非数字宽度——传给 el-table 会算出 NaN 使整行错位', () => {
    seed(COL_WIDTH_KEY, { widths: { id: '200', service: null, status: NaN } }, COL_WIDTH_VERSION)
    const c = useTicketColumns()
    c.restoreColumnPrefs()

    // '200' 看着像宽度，但字符串参与布局计算的结果不可控
    expect(c.columnWidths.value.id).toBe(DEFAULT_COL_WIDTHS.id)
    expect(c.columnWidths.value.service).toBe(DEFAULT_COL_WIDTHS.service)
    expect(c.columnWidths.value.status).toBe(DEFAULT_COL_WIDTHS.status)
  })

  it('丢弃未知列名——旧版本残留的键不该混进来', () => {
    seed(COL_WIDTH_KEY, { widths: { ghostColumn: 120, id: 200 } }, COL_WIDTH_VERSION)
    const c = useTicketColumns()
    c.restoreColumnPrefs()

    expect(c.columnWidths.value.ghostColumn).toBeUndefined()
    // 合法项不受牵连，仍然恢复
    expect(c.columnWidths.value.id).toBe(200)
  })

  it('title 是白名单外的例外——它不在默认表里但允许恢复', () => {
    seed(COL_WIDTH_KEY, { widths: { title: 300 }, resized: { title: true } }, COL_WIDTH_VERSION)
    const c = useTicketColumns()
    c.restoreColumnPrefs()

    // 用户拖过标题列后，它就从「弹性」变成「固定」，这个意图要能被保留
    expect(c.titleWidth.value).toBe(300)
  })

  it('版本不匹配时整份丢弃——列集合变了，旧键位对不上', () => {
    seed(COL_WIDTH_KEY, { widths: { id: 200 } }, COL_WIDTH_VERSION - 1)
    const c = useTicketColumns()
    c.restoreColumnPrefs()

    // 混合状态（新列没宽度、旧列错位）比直接回落默认更难排查
    expect(c.columnWidths.value.id).toBe(DEFAULT_COL_WIDTHS.id)
  })

  it('存储内容损坏时不抛异常，回落默认', () => {
    localStorage.setItem(PREFIX + COL_WIDTH_KEY, '{ 这不是 JSON')
    const c = useTicketColumns()

    // 一个坏掉的偏好值不该让整个工单列表页白屏
    expect(() => c.restoreColumnPrefs()).not.toThrow()
    expect(c.columnWidths.value).toEqual(DEFAULT_COL_WIDTHS)
  })

  it('恢复列可见性，并丢弃非布尔与未知列', () => {
    seed(
      COL_VISIBLE_KEY,
      { updatedAt: true, id: false, ghost: true, service: 'yes' },
      COL_VISIBLE_VERSION
    )
    const c = useTicketColumns()
    c.restoreColumnPrefs()

    expect(c.columnVisible.value.updatedAt).toBe(true)
    expect(c.columnVisible.value.id).toBe(false)
    expect(c.columnVisible.value.ghost).toBeUndefined()
    // 'yes' 是字符串，不是布尔——回落默认
    expect(c.columnVisible.value.service).toBe(DEFAULT_COL_VISIBLE.service)
  })
})

describe('toggleColumn', () => {
  it('切换可见性并写入存储', () => {
    const c = useTicketColumns()

    c.toggleColumn('updatedAt')
    expect(c.columnVisible.value.updatedAt).toBe(true)

    c.toggleColumn('updatedAt')
    expect(c.columnVisible.value.updatedAt).toBe(false)
  })

  it('切换后 columnsResized 为 true，「恢复默认」按钮才该出现', () => {
    const c = useTicketColumns()
    expect(c.columnsResized.value).toBe(false)

    c.toggleColumn('id')
    expect(c.columnsResized.value).toBe(true)
  })
})

describe('onHeaderDragend', () => {
  it('按 property 定位列并记录宽度', () => {
    const c = useTicketColumns()

    c.onHeaderDragend(222, 150, { property: 'id' })

    expect(c.columnWidths.value.id).toBe(222)
    expect(c.userResized.value.id).toBe(true)
  })

  it('property 缺失时用 label 兜底——SLA/操作等自定义列没有 property', () => {
    const c = useTicketColumns()

    c.onHeaderDragend(180, 128, { label: 'SLA' })

    // 不做 label 兜底的话，这些列拖了没反应，且不会报错
    expect(c.columnWidths.value.sla).toBe(180)
  })

  it('宽度取整——小数宽度会让相邻列出现 1px 缝隙', () => {
    const c = useTicketColumns()

    c.onHeaderDragend(199.6, 150, { property: 'id' })

    expect(c.columnWidths.value.id).toBe(200)
  })

  it('无法定位列时安静忽略，不写入垃圾键', () => {
    const c = useTicketColumns()
    const before = { ...c.columnWidths.value }

    c.onHeaderDragend(200, 150, {})
    c.onHeaderDragend(200, 150, { label: '不存在的列' })

    expect(c.columnWidths.value).toEqual(before)
  })

  it('拖动 title 列会固定其宽度（它不在默认表里但允许）', () => {
    const c = useTicketColumns()

    c.onHeaderDragend(320, 0, { property: 'title' })

    expect(c.titleWidth.value).toBe(320)
    expect(c.columnsResized.value).toBe(true)
  })
})

describe('resetColumnWidths', () => {
  it('恢复默认并清空存储——只重置内存的话刷新后旧布局又回来了', () => {
    const c = useTicketColumns()
    c.onHeaderDragend(300, 150, { property: 'id' })
    c.toggleColumn('updatedAt')
    // 等待 debounce 落盘
    return new Promise<void>(resolve => {
      setTimeout(() => {
        c.resetColumnWidths()

        expect(c.columnWidths.value).toEqual(DEFAULT_COL_WIDTHS)
        expect(c.columnVisible.value).toEqual(DEFAULT_COL_VISIBLE)
        expect(c.userResized.value).toEqual({})
        expect(c.columnsResized.value).toBe(false)
        expect(localStorage.getItem(PREFIX + COL_WIDTH_KEY)).toBeNull()
        expect(localStorage.getItem(PREFIX + COL_VISIBLE_KEY)).toBeNull()
        resolve()
      }, 350)
    })
  })

  it('触发 onReset 回调，让调用方决定怎么提示', () => {
    const onReset = vi.fn()
    const c = useTicketColumns({ onReset })

    c.resetColumnWidths()

    // composable 不直接依赖 UI 框架，提示由调用方注入
    expect(onReset).toHaveBeenCalledTimes(1)
  })
})

describe('列定义一致性', () => {
  it('每个可配置列都有默认宽度与默认可见性', () => {
    for (const col of CONFIGURABLE_COLUMNS) {
      expect(DEFAULT_COL_WIDTHS[col.key], `列 ${col.key} 缺默认宽度`).toBeTypeOf('number')
      expect(DEFAULT_COL_VISIBLE[col.key], `列 ${col.key} 缺默认可见性`).toBeTypeOf('boolean')
    }
  })

  it('每个可配置列的 label 都能反查回自身的 key', () => {
    // LABEL_TO_KEY 是 header-dragend 的唯一兜底路径。
    // 漏一条就表现为「那一列拖了没反应」，且不会报错
    for (const col of CONFIGURABLE_COLUMNS) {
      expect(LABEL_TO_KEY[col.label], `列「${col.label}」缺 LABEL_TO_KEY 映射`).toBe(col.key)
    }
  })

  it('默认可见性表不含可配置列以外的键', () => {
    const configurable = new Set(CONFIGURABLE_COLUMNS.map(c => c.key))
    const orphan = Object.keys(DEFAULT_COL_VISIBLE).filter(k => !configurable.has(k))

    // 有默认可见性却不在列设置面板里 = 用户永远切不了它
    expect(orphan, `这些列有可见性配置但不在设置面板中：${orphan.join(', ')}`).toEqual([])
  })
})
