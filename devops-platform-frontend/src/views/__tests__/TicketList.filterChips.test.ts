/**
 * TicketList 筛选 chip 的**增删对称性**测试。
 *
 * ── 为什么先测这个（而不是先拆文件）──────────────────────────
 * `TicketList.vue` 2679 行、零测试，是前端最大的存量风险。但直接拆分很危险：
 * 没有测试兜底的重构，等于把现有缺陷原样搬进新文件，还额外引入搬运错误。
 * 所以顺序必须是「先补测试，再拆分」。
 *
 * 这里挑的第一个不变式是 chip 的增删对称：
 * `activeFilterChips` 负责**造出** chip（每个带一个 `kind`），
 * `removeFilterChip` 用 switch 按 `kind` **消掉**它。
 * 两处是各写各的——一旦有人加了新筛选项，
 * 只在 computed 里 push 而忘了在 switch 里加 case，
 * 那个 chip 就变成**点了没反应的死按钮**：
 * 用户看得见「标签：数据库」挂在那儿，点 × 却怎么也删不掉，
 * 而且不会有任何报错，控制台干干净净。
 *
 * ── 为什么用源码解析而不是 mount ────────────────────────────
 * 这个页面要挂起来得 stub 掉 store、router、element-plus 表格、
 * 十几个子组件与 SLA/持久化工具——mount 的搭建成本极高，
 * 且大部分断言会耗在「有没有 stub 全」而不是业务逻辑上。
 *
 * 而本不变式的两端**都是静态可见的**：chip 的 kind 是字面量，
 * switch 的 case 也是字面量。直接从源码里把两个集合抽出来对比，
 * 反而比 mount 更直接、更不易碎——它不关心渲染，只关心
 * 「造出来的每一种 chip 都有人认领」。
 *
 * 这类「两处清单必须一致」的守法在本项目已有先例
 * （业务码词表契约测试、HTML 净化白名单一致性测试）。
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// 用 cwd 相对路径而非 import.meta.url：测试跑在 jsdom 环境下，
// import.meta.url 不是 file: scheme，fileURLToPath 会抛
// 「The URL must be of scheme file」。vitest 的 cwd 固定为前端项目根目录。
const source = readFileSync(
  resolve(process.cwd(), 'src/views/TicketList.vue'),
  'utf-8'
)

/** 取出 activeFilterChips computed 的函数体 */
function chipsBlock(): string {
  const start = source.indexOf('const activeFilterChips')
  expect(start, 'activeFilterChips 应存在——它若被改名，本测试需同步更新').toBeGreaterThan(-1)
  const end = source.indexOf('const removeFilterChip', start)
  expect(end).toBeGreaterThan(start)
  return source.slice(start, end)
}

/** 取出 removeFilterChip 的 switch 块 */
function removeBlock(): string {
  const start = source.indexOf('const removeFilterChip')
  expect(start).toBeGreaterThan(-1)
  const end = source.indexOf('const clearFilters', start)
  expect(end).toBeGreaterThan(start)
  return source.slice(start, end)
}

/** activeFilterChips 里所有 `kind: 'xxx'` 字面量 */
function producedKinds(): string[] {
  return [...chipsBlock().matchAll(/kind:\s*'([^']+)'/g)].map(m => m[1])
}

/** removeFilterChip 的 switch 里所有 `case 'xxx':` */
function handledKinds(): string[] {
  return [...removeBlock().matchAll(/case\s+'([^']+)'\s*:/g)].map(m => m[1])
}

describe('筛选 chip 的增删对称性', () => {
  it('能造出 chip（防止解析逻辑本身失效导致空集合假通过）', () => {
    // 若正则或锚点失配，两个集合都会是空的，
    // 「集合相等」就变成了 [] === []，一个永远绿的假测试。
    // 这条断言是前面几条的前提。
    expect(producedKinds().length).toBeGreaterThanOrEqual(8)
    expect(handledKinds().length).toBeGreaterThanOrEqual(8)
  })

  it('每一种被造出来的 chip 都能被移除——否则是点不掉的死按钮', () => {
    const handled = new Set(handledKinds())
    const missing = [...new Set(producedKinds())].filter(k => !handled.has(k))

    expect(
      missing,
      `这些 chip 会被渲染出来但 removeFilterChip 里没有对应 case，` +
        `用户点 × 不会有任何反应，也不会报错：${missing.join(', ')}`
    ).toEqual([])
  })

  it('没有多余的 case——处理了却永远不会出现的 kind 是死代码', () => {
    const produced = new Set(producedKinds())
    const orphan = [...new Set(handledKinds())].filter(k => !produced.has(k))

    // 反向也要查：留着处理不存在 kind 的分支，会让后来人以为
    // 存在这种筛选项，照着它的样子去改别处
    expect(
      orphan,
      `removeFilterChip 处理了这些 kind，但 activeFilterChips 从不产出它们：${orphan.join(', ')}`
    ).toEqual([])
  })

  it('覆盖全部筛选维度：关键词/状态/优先级/服务/分类/负责人/起止日期/标签', () => {
    const produced = new Set(producedKinds())

    // 逐个列出而非只比数量：漏掉某一维度时，
    // 错误信息要能直接说出漏的是哪个
    for (const kind of [
      'keyword', 'status', 'priority', 'service',
      'category', 'assignee', 'dateFrom', 'dateTo', 'tag',
    ]) {
      expect(produced.has(kind), `筛选维度 ${kind} 应产出可移除的 chip`).toBe(true)
    }
  })
})

describe('筛选变更后的分页归位', () => {
  it('removeFilterChip 会把页码重置回第 1 页', () => {
    // 筛选条件变了等于换了一个结果集，停在原页码会落到
    // 「筛完之后本不该存在的第 5 页」，用户看到一片空白
    // 却以为是没有数据
    expect(removeBlock()).toMatch(/currentPage\.value\s*=\s*1/)
  })

  it('removeFilterChip 移除关键词时会取消待触发的防抖搜索', () => {
    const block = removeBlock()
    const keywordCase = block.slice(block.indexOf("case 'keyword'"))

    // 搜索有 300ms 防抖。若不 cancel，用户刚点掉关键词 chip，
    // 那个在途的防抖回调就会把 appliedQuery 又写回去——
    // chip 消失了、筛选却还在，是最难排查的一类「幽灵状态」
    expect(keywordCase).toMatch(/applySearch\.cancel\(\)/)
  })

  it('clearFilters 同样取消防抖并复位页码', () => {
    const start = source.indexOf('const clearFilters')
    const block = source.slice(start, source.indexOf('const applySearch', start))

    expect(block).toMatch(/applySearch\.cancel\(\)/)
    expect(block).toMatch(/currentPage\.value\s*=\s*1/)
    // 一键清空必须把标签数组也清掉，只清标量筛选是常见疏漏
    expect(block).toMatch(/tagFilters\.value\s*=\s*\[\]/)
  })
})

describe('搜索防抖的卸载处理', () => {
  it('组件卸载前 flush 未决的搜索，避免回调打在已销毁的组件上', () => {
    // 不处理的话，用户输入后立刻切页，300ms 后回调仍会执行
    // fetchList 并写 ref——轻则无谓请求，重则内存泄漏告警
    expect(source).toMatch(/onBeforeUnmount\([\s\S]{0,120}applySearch\.flush\(\)/)
  })
})
