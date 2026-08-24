/**
 * 服务端分页 composable 测试。
 *
 * 页码窗口算法的边界（何时出现省略号、当前页贴近首/末页时的形态）
 * 是最容易写错的部分，且此前 TicketList 与 AlertList 各有一份实现——
 * 测试同时充当「两份实现合并后行为未变」的回归基线。
 *
 * 另保护 6.17 契约：末页删空后须退页，否则用户停在空白页。
 */
import { describe, expect, it } from 'vitest'

import { useServerPagination } from '../useServerPagination'

/** 便捷构造：设定总条数与每页条数后返回实例 */
function paged(total: number, pageSize = 10, windowSize?: number) {
  const p = useServerPagination({ pageSize, windowSize })
  p.setMeta({ total, totalPages: Math.ceil(total / pageSize) })
  return p
}

describe('初始状态', () => {
  it('默认第 1 页、每页 10 条', () => {
    const p = useServerPagination()
    expect(p.currentPage.value).toBe(1)
    expect(p.pageSize.value).toBe(10)
  })

  it('可自定义每页条数', () => {
    expect(useServerPagination({ pageSize: 20 }).pageSize.value).toBe(20)
  })

  it('未设置 meta 时总数为 0', () => {
    const p = useServerPagination()
    expect(p.total.value).toBe(0)
    expect(p.totalPages.value).toBe(0)
  })
})

describe('pageNumbers — 无省略号（总页数少）', () => {
  it('单页只显示 1', () => {
    expect(paged(5).pageNumbers.value).toEqual([1])
  })

  it('总页数等于展开阈值时全部展开', () => {
    // windowSize=1 → 阈值 5
    expect(paged(50).pageNumbers.value).toEqual([1, 2, 3, 4, 5])
  })

  it('无数据时仍显示第 1 页，不返回空数组', () => {
    expect(paged(0).pageNumbers.value).toEqual([1])
  })
})

describe('pageNumbers — 含省略号', () => {
  it('当前页在首部时只有右省略号', () => {
    const p = paged(200) // 20 页
    p.goToPage(1)
    expect(p.pageNumbers.value).toEqual([1, 2, 'ellipsis', 20])
  })

  it('当前页在中部时两侧都有省略号', () => {
    const p = paged(200)
    p.goToPage(10)
    expect(p.pageNumbers.value).toEqual([1, 'ellipsis', 9, 10, 11, 'ellipsis', 20])
  })

  it('当前页在末部时只有左省略号', () => {
    const p = paged(200)
    p.goToPage(20)
    expect(p.pageNumbers.value).toEqual([1, 'ellipsis', 19, 20])
  })

  it('当前页第 3 页时左侧不出现省略号 —— 省略号后紧跟第 2 页毫无意义', () => {
    const p = paged(200)
    p.goToPage(3)
    expect(p.pageNumbers.value).toEqual([1, 2, 3, 4, 'ellipsis', 20])
  })

  it('当前页第 4 页时左侧才出现省略号', () => {
    const p = paged(200)
    p.goToPage(4)
    expect(p.pageNumbers.value).toEqual([1, 'ellipsis', 3, 4, 5, 'ellipsis', 20])
  })

  it('页码序列不含重复项', () => {
    const p = paged(200)
    for (let page = 1; page <= 20; page++) {
      p.goToPage(page)
      const nums = p.pageNumbers.value.filter((x): x is number => x !== 'ellipsis')
      expect(new Set(nums).size).toBe(nums.length)
    }
  })

  it('页码序列始终升序', () => {
    const p = paged(200)
    for (let page = 1; page <= 20; page++) {
      p.goToPage(page)
      const nums = p.pageNumbers.value.filter((x): x is number => x !== 'ellipsis')
      expect(nums).toEqual([...nums].sort((a, b) => a - b))
    }
  })

  it('页码序列必然包含当前页 —— 否则用户看不到自己在第几页', () => {
    const p = paged(500) // 50 页
    for (const page of [1, 2, 5, 25, 48, 49, 50]) {
      p.goToPage(page)
      expect(p.pageNumbers.value).toContain(page)
    }
  })

  it('首末页始终可直达', () => {
    const p = paged(500)
    p.goToPage(25)
    expect(p.pageNumbers.value[0]).toBe(1)
    expect(p.pageNumbers.value.at(-1)).toBe(50)
  })
})

describe('pageNumbers — 自定义窗口', () => {
  it('windowSize=2 时当前页两侧各显示 2 页', () => {
    const p = paged(200, 10, 2)
    p.goToPage(10)
    expect(p.pageNumbers.value).toEqual([1, 'ellipsis', 8, 9, 10, 11, 12, 'ellipsis', 20])
  })

  it('windowSize=0 时只显示当前页与首末页', () => {
    const p = paged(200, 10, 0)
    p.goToPage(10)
    expect(p.pageNumbers.value).toEqual([1, 'ellipsis', 10, 'ellipsis', 20])
  })

  it('windowSize 越大展开阈值越高', () => {
    // windowSize=3 → 阈值 9，9 页应全部展开
    const p = paged(90, 10, 3)
    expect(p.pageNumbers.value).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9])
  })
})

describe('pageStart / pageEnd（「显示 X-Y 共 N 条」）', () => {
  it('第 1 页区间从 1 开始', () => {
    const p = paged(95)
    expect(p.pageStart.value).toBe(1)
    expect(p.pageEnd.value).toBe(10)
  })

  it('中间页区间正确', () => {
    const p = paged(95)
    p.goToPage(3)
    expect(p.pageStart.value).toBe(21)
    expect(p.pageEnd.value).toBe(30)
  })

  it('末页不足一页时 pageEnd 取总数而非页码乘积', () => {
    const p = paged(95)
    p.goToPage(10)
    expect(p.pageStart.value).toBe(91)
    expect(p.pageEnd.value).toBe(95)
  })

  it('无数据时区间为 0-0，不显示「1-0」这种矛盾区间', () => {
    const p = paged(0)
    expect(p.pageStart.value).toBe(0)
    expect(p.pageEnd.value).toBe(0)
  })

  it('恰好整页时末页区间无余数', () => {
    const p = paged(100)
    p.goToPage(10)
    expect(p.pageStart.value).toBe(91)
    expect(p.pageEnd.value).toBe(100)
  })
})

describe('hasPrev / hasNext', () => {
  it('首页无上一页', () => {
    const p = paged(95)
    expect(p.hasPrev.value).toBe(false)
    expect(p.hasNext.value).toBe(true)
  })

  it('末页无下一页', () => {
    const p = paged(95)
    p.goToPage(10)
    expect(p.hasPrev.value).toBe(true)
    expect(p.hasNext.value).toBe(false)
  })

  it('单页时两侧都不可翻', () => {
    const p = paged(5)
    expect(p.hasPrev.value).toBe(false)
    expect(p.hasNext.value).toBe(false)
  })

  it('无数据时不可翻页', () => {
    const p = paged(0)
    expect(p.hasNext.value).toBe(false)
  })
})

describe('goToPage', () => {
  it('合法翻页返回 true 并更新页码', () => {
    const p = paged(95)
    expect(p.goToPage(3)).toBe(true)
    expect(p.currentPage.value).toBe(3)
  })

  it('页码未变化时返回 false —— 调用方据此跳过无谓的重新拉取', () => {
    const p = paged(95)
    expect(p.goToPage(1)).toBe(false)
  })

  it('小于 1 的页码被拒绝', () => {
    const p = paged(95)
    expect(p.goToPage(0)).toBe(false)
    expect(p.goToPage(-5)).toBe(false)
    expect(p.currentPage.value).toBe(1)
  })

  it('超出总页数的页码被拒绝 —— 否则会请求一个空页', () => {
    const p = paged(95)
    expect(p.goToPage(11)).toBe(false)
    expect(p.currentPage.value).toBe(1)
  })

  it('无数据时任何翻页都被拒绝', () => {
    const p = paged(0)
    expect(p.goToPage(1)).toBe(false)
    expect(p.goToPage(2)).toBe(false)
  })
})

describe('resetPage（筛选/排序变化后必须调用）', () => {
  it('回到第 1 页', () => {
    const p = paged(95)
    p.goToPage(5)
    p.resetPage()
    expect(p.currentPage.value).toBe(1)
  })

  it('已在第 1 页时无副作用', () => {
    const p = paged(95)
    p.resetPage()
    expect(p.currentPage.value).toBe(1)
  })
})

describe('retreatIfEmptied（6.17 末页删空退页）', () => {
  it('末页被删空时退到新末页并返回 true', () => {
    const p = paged(95)
    p.goToPage(10)
    // 删除后总数变 90（9 页），当前第 10 页已无数据
    p.setMeta({ total: 90, totalPages: 9 })

    expect(p.retreatIfEmptied(0)).toBe(true)
    expect(p.currentPage.value).toBe(9)
  })

  it('当前页仍有数据时不退页', () => {
    const p = paged(95)
    p.goToPage(5)
    expect(p.retreatIfEmptied(10)).toBe(false)
    expect(p.currentPage.value).toBe(5)
  })

  it('已在第 1 页时不退页 —— 没有更前的页可退', () => {
    const p = paged(0)
    expect(p.retreatIfEmptied(0)).toBe(false)
    expect(p.currentPage.value).toBe(1)
  })

  it('总数为 0 时不退页 —— 这是「筛选无结果」而非「删空了当前页」', () => {
    const p = paged(95)
    p.goToPage(5)
    p.setMeta({ total: 0, totalPages: 0 })

    expect(p.retreatIfEmptied(0)).toBe(false)
    expect(p.currentPage.value).toBe(5)
  })

  it('连续删空多页时每次退一页，不会退到 0 页', () => {
    const p = paged(30)
    p.goToPage(3)
    p.setMeta({ total: 10, totalPages: 1 })

    expect(p.retreatIfEmptied(0)).toBe(true)
    expect(p.currentPage.value).toBe(1)
  })
})

describe('setMeta', () => {
  it('写入总数与总页数', () => {
    const p = useServerPagination()
    p.setMeta({ total: 42, totalPages: 5 })
    expect(p.total.value).toBe(42)
    expect(p.totalPages.value).toBe(5)
  })

  it('不改动当前页 —— 翻页与元信息更新是两件事', () => {
    const p = paged(95)
    p.goToPage(3)
    p.setMeta({ total: 100, totalPages: 10 })
    expect(p.currentPage.value).toBe(3)
  })
})
