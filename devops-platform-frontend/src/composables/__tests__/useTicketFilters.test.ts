/**
 * 工单筛选 composable 测试。
 *
 * ── 从「源码解析」升级为「真实行为」────────────────────────
 * 上一轮这些不变式是靠**解析 TicketList.vue 源码**守住的——那时逻辑埋在
 * 2679 行单文件里，mount 的成本远高于收益，只能退而求其次比对字面量。
 *
 * 现在逻辑抽成了 composable，可以直接调用真函数、断言真状态：
 * 不再是「switch 里有没有这个 case」，而是「调用之后 ref 到底变没变」。
 * 前者可能被写成有 case 却没生效的空分支，后者不会。
 *
 * ── 守的三条 ──────────────────────────────────────────────
 * 1. **三处清单同步**（hasFilters / activeFilterChips / removeFilterChip）。
 *    每加一个筛选维度都要同时改这三处，漏任何一处都不报错：
 *    漏 1 → 找不到「清空」按钮；漏 2 → 筛了但看不见；漏 3 → chip 删不掉。
 * 2. **清关键词必须先取消在途防抖**。反了的话 300ms 后那个回调会把
 *    appliedQuery 又写回去——chip 没了筛选还在，最难排查的幽灵状态。
 * 3. **任何筛选变化都回第 1 页**。否则会落到「筛完后本不该存在的第 5 页」，
 *    用户看到空白却以为是没数据。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useTicketFilters, type FilterChip } from '../useTicketFilters'

function setup() {
  const cancelSearch = vi.fn()
  const fetchList = vi.fn()
  const currentPage = ref(1)
  const f = useTicketFilters({ cancelSearch, currentPage, fetchList })
  return { ...f, cancelSearch, fetchList, currentPage }
}

/** 把全部筛选维度都设上值 */
function fillAll(f: ReturnType<typeof setup>) {
  f.appliedQuery.value = 'redis'
  f.searchQuery.value = 'redis'
  f.statusFilter.value = 'pending'
  f.priorityFilter.value = 'urgent'
  f.serviceFilter.value = '订单服务'
  f.categoryFilter.value = '数据库'
  f.assigneeFilter.value = '张明'
  f.dateFrom.value = '2026-08-01'
  f.dateTo.value = '2026-08-25'
  f.tagFilters.value = ['线上', '紧急']
}

let f: ReturnType<typeof setup>
beforeEach(() => {
  f = setup()
})

describe('hasFilters', () => {
  it('无筛选时为 false', () => {
    expect(f.hasFilters.value).toBe(false)
  })

  it('每一个筛选维度都能单独触发 hasFilters', () => {
    // 逐个验证而非只测一个：漏掉某维度时，用户筛了却看不到
    // 「清空筛选」按钮，找不到退出筛选的出口。
    // 每个 case 用全新实例，避免上一个维度的残留把结果染绿
    const cases: Array<[string, (x: ReturnType<typeof setup>) => void]> = [
      ['关键词', x => { x.appliedQuery.value = 'redis' }],
      ['状态', x => { x.statusFilter.value = 'pending' }],
      ['优先级', x => { x.priorityFilter.value = 'urgent' }],
      ['服务', x => { x.serviceFilter.value = '订单服务' }],
      ['分类', x => { x.categoryFilter.value = '数据库' }],
      ['负责人', x => { x.assigneeFilter.value = '张明' }],
      ['起始日期', x => { x.dateFrom.value = '2026-08-01' }],
      ['截止日期', x => { x.dateTo.value = '2026-08-25' }],
      ['标签', x => { x.tagFilters.value = ['线上'] }],
    ]

    for (const [name, apply] of cases) {
      const fresh = setup()
      expect(fresh.hasFilters.value, `${name}：初始应为 false`).toBe(false)
      apply(fresh)
      expect(fresh.hasFilters.value, `${name} 应触发 hasFilters`).toBe(true)
    }
  })

  it('searchQuery 未落定时不算已筛选——防抖期间不该亮出清空按钮', () => {
    // 用户刚敲两个字、防抖还没触发，列表尚未被筛过。
    // 此时显示「清空筛选」是误导：清什么？
    f.searchQuery.value = 'red'
    expect(f.hasFilters.value).toBe(false)

    f.appliedQuery.value = 'red'
    expect(f.hasFilters.value).toBe(true)
  })
})

describe('activeFilterChips 与 removeFilterChip 的对称性', () => {
  it('每一种被造出来的 chip 都能真正被移除', () => {
    fillAll(f)
    const chips = f.activeFilterChips.value
    expect(chips.length).toBeGreaterThanOrEqual(9)

    // 逐个移除，每次都必须真的让它从列表里消失。
    // 这比「switch 里有没有 case」强：空分支也能骗过源码检查，
    // 但骗不过「移除后 chip 还在不在」
    for (const chip of chips) {
      const before = f.activeFilterChips.value.length
      f.removeFilterChip(chip)
      const after = f.activeFilterChips.value.length
      expect(
        after,
        `移除 chip「${chip.label}」(kind=${chip.kind}) 后数量应减少，` +
          `否则它是个点了没反应的死按钮`
      ).toBeLessThan(before)
    }

    expect(f.activeFilterChips.value).toHaveLength(0)
    expect(f.hasFilters.value).toBe(false)
  })

  it('覆盖全部筛选维度', () => {
    fillAll(f)
    const kinds = new Set(f.activeFilterChips.value.map(c => c.kind))

    for (const kind of [
      'keyword', 'status', 'priority', 'service',
      'category', 'assignee', 'dateFrom', 'dateTo', 'tag',
    ]) {
      expect(kinds.has(kind), `筛选维度 ${kind} 应产出可移除的 chip`).toBe(true)
    }
  })

  it('多选标签逐个移除，互不影响', () => {
    f.tagFilters.value = ['线上', '紧急', '数据库']

    const tagChip = (v: string) =>
      f.activeFilterChips.value.find(c => c.kind === 'tag' && c.value === v) as FilterChip

    f.removeFilterChip(tagChip('紧急'))

    // 只该掉一个：若实现写成 tagFilters.value = [] 就会全清，
    // 用户点掉一个标签结果三个全没了
    expect(f.tagFilters.value).toEqual(['线上', '数据库'])
  })

  it('chip 的 label 带上人类可读的值，而非机器码', () => {
    f.statusFilter.value = 'pending'
    f.priorityFilter.value = 'urgent'
    const labels = f.activeFilterChips.value.map(c => c.label)

    // 显示 status:pending 而不是「状态：待处理」，等于让用户去认后端枚举
    expect(labels.some(l => l.startsWith('状态：') && !l.includes('pending'))).toBe(true)
    expect(labels.some(l => l.startsWith('优先级：') && !l.includes('urgent'))).toBe(true)
  })

  it('未知 kind 不抛异常，只是无操作', () => {
    fillAll(f)
    const before = f.activeFilterChips.value.length

    // 防御性：将来若有人传了拼错的 kind，不该让整个页面崩掉
    expect(() =>
      f.removeFilterChip({ key: 'x', label: 'x', kind: 'nonexistent' })
    ).not.toThrow()
    expect(f.activeFilterChips.value).toHaveLength(before)
  })
})

describe('清关键词与防抖的配合', () => {
  it('移除关键词 chip 时取消在途防抖', () => {
    f.appliedQuery.value = 'redis'
    f.searchQuery.value = 'redis'

    f.removeFilterChip({ key: 'kw', label: '关键词：redis', kind: 'keyword' })

    // 不 cancel 的话，300ms 后那个待触发的回调会把 appliedQuery 写回去，
    // 表现为「chip 点掉了，过一会儿筛选又自己回来了」
    expect(f.cancelSearch).toHaveBeenCalled()
    expect(f.appliedQuery.value).toBe('')
    // 输入框也要清空，否则用户看到框里有字、列表却没筛
    expect(f.searchQuery.value).toBe('')
  })

  it('clearFilters 同样取消防抖', () => {
    fillAll(f)
    f.clearFilters()

    expect(f.cancelSearch).toHaveBeenCalled()
  })
})

describe('clearFilters', () => {
  it('一键清空所有维度，包括标签数组', () => {
    fillAll(f)
    f.clearFilters()

    expect(f.hasFilters.value).toBe(false)
    expect(f.activeFilterChips.value).toHaveLength(0)
    // 标签是数组，最容易在「清空标量筛选」时被漏掉
    expect(f.tagFilters.value).toEqual([])
    expect(f.searchQuery.value).toBe('')
    expect(f.statusFilter.value).toBe('all')
    expect(f.dateFrom.value).toBe('')
  })

  it('回到第 1 页并重新拉取', () => {
    f.currentPage.value = 5
    fillAll(f)
    f.clearFilters()

    expect(f.currentPage.value).toBe(1)
    expect(f.fetchList).toHaveBeenCalled()
  })
})

describe('分页归位', () => {
  it('移除任一 chip 都回到第 1 页并重新拉取', () => {
    fillAll(f)
    f.currentPage.value = 5

    f.removeFilterChip({ key: 'st', label: '状态', kind: 'status' })

    // 筛选变了等于换了结果集，停在第 5 页会看到一片空白，
    // 而用户会以为是「没有数据」
    expect(f.currentPage.value).toBe(1)
    expect(f.fetchList).toHaveBeenCalled()
  })
})

describe('toggleTagFilter', () => {
  it('未选中则加入，已选中则移除', () => {
    const onChanged = vi.fn()

    f.toggleTagFilter('线上', onChanged)
    expect(f.tagFilters.value).toEqual(['线上'])

    f.toggleTagFilter('线上', onChanged)
    expect(f.tagFilters.value).toEqual([])

    expect(onChanged).toHaveBeenCalledTimes(2)
  })

  it('多个标签可同时选中', () => {
    const onChanged = vi.fn()

    f.toggleTagFilter('线上', onChanged)
    f.toggleTagFilter('紧急', onChanged)

    expect(f.tagFilters.value).toEqual(['线上', '紧急'])
    expect(f.activeFilterChips.value.filter(c => c.kind === 'tag')).toHaveLength(2)
  })
})
