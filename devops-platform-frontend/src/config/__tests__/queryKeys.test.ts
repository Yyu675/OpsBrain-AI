/**
 * queryKey 工厂测试。
 *
 * queryKey 是 Query 缓存与失效的唯一标识。若查询用的 key 与
 * invalidateQueries 用的 key 不匹配，失效会**静默不生效**——
 * 表现为「改完数据列表没更新」，且没有明显的漏调用点可查。
 *
 * 这里锁定两条契约：
 * - 前缀包含关系：失效 all 必须能命中 list/detail/stats
 * - 参数进 key：不同筛选组合必须是不同的缓存条目
 */
import { describe, expect, it } from 'vitest'

import {
  alertKeys,
  approvalKeys,
  dashboardKeys,
  knowledgeKeys,
  ticketKeys,
  userKeys,
} from '../queryKeys'

/** 模拟 TanStack 的前缀匹配：目标 key 是否以给定前缀开头 */
function matchesPrefix(target: readonly unknown[], prefix: readonly unknown[]): boolean {
  if (prefix.length > target.length) return false
  return prefix.every((seg, i) => JSON.stringify(seg) === JSON.stringify(target[i]))
}

describe('ticketKeys — 前缀包含关系', () => {
  it('失效 all 能命中列表 —— 建单后列表必须重拉', () => {
    expect(matchesPrefix(ticketKeys.list({ page: 1 }), ticketKeys.all)).toBe(true)
  })

  it('失效 all 能命中详情', () => {
    expect(matchesPrefix(ticketKeys.detail('TKT-A'), ticketKeys.all)).toBe(true)
  })

  it('失效 all 能命中统计 —— 建单会改变 KPI', () => {
    expect(matchesPrefix(ticketKeys.stats(), ticketKeys.all)).toBe(true)
  })

  it('失效 lists 只命中列表，不波及详情 —— 翻页不该让详情重拉', () => {
    expect(matchesPrefix(ticketKeys.list({ page: 2 }), ticketKeys.lists())).toBe(true)
    expect(matchesPrefix(ticketKeys.detail('TKT-A'), ticketKeys.lists())).toBe(false)
  })

  it('失效单条详情不波及其他工单的详情', () => {
    expect(matchesPrefix(ticketKeys.detail('TKT-B'), ticketKeys.detail('TKT-A'))).toBe(false)
  })

  it('子资源挂在详情之下 —— 失效某工单详情会连带其回复/活动流', () => {
    for (const key of [
      ticketKeys.replies('TKT-A'),
      ticketKeys.activities('TKT-A'),
      ticketKeys.attachments('TKT-A'),
      ticketKeys.actions('TKT-A'),
    ]) {
      expect(matchesPrefix(key, ticketKeys.detail('TKT-A'))).toBe(true)
    }
  })

  it('统计与列表互不包含 —— 翻页不该重拉 KPI', () => {
    expect(matchesPrefix(ticketKeys.stats(), ticketKeys.lists())).toBe(false)
    expect(matchesPrefix(ticketKeys.list({ page: 1 }), ticketKeys.stats())).toBe(false)
  })
})

describe('ticketKeys — 参数进 key', () => {
  it('不同页码是不同的缓存条目', () => {
    expect(ticketKeys.list({ page: 1 })).not.toEqual(ticketKeys.list({ page: 2 }))
  })

  it('不同筛选条件是不同的缓存条目 —— 否则切筛选会拿到上次的数据', () => {
    expect(ticketKeys.list({ status: 'pending' }))
      .not.toEqual(ticketKeys.list({ status: 'processing' }))
  })

  it('相同参数产生相同 key —— 否则缓存永不命中', () => {
    expect(ticketKeys.list({ page: 1, status: 'pending' }))
      .toEqual(ticketKeys.list({ page: 1, status: 'pending' }))
  })

  it('SLA 风险清单的窗口参数进 key', () => {
    expect(ticketKeys.slaAtRisk(30, 20)).not.toEqual(ticketKeys.slaAtRisk(120, 20))
  })
})

describe('alertKeys', () => {
  it('失效 all 命中列表与详情', () => {
    expect(matchesPrefix(alertKeys.list({ page: 1 }), alertKeys.all)).toBe(true)
    expect(matchesPrefix(alertKeys.detail('1'), alertKeys.all)).toBe(true)
  })

  it('不同筛选是不同缓存条目', () => {
    expect(alertKeys.list({ status: 'FIRING' }))
      .not.toEqual(alertKeys.list({ status: 'RESOLVED' }))
  })

  it('与工单领域不冲突 —— 失效工单不该波及告警', () => {
    expect(matchesPrefix(alertKeys.list({}), ticketKeys.all)).toBe(false)
    expect(matchesPrefix(ticketKeys.lists(), alertKeys.all)).toBe(false)
  })
})

describe('knowledgeKeys', () => {
  it('版本历史挂在详情之下', () => {
    expect(matchesPrefix(knowledgeKeys.versions(1), knowledgeKeys.detail(1))).toBe(true)
  })

  it('分类与热门标签属领域级，不随列表筛选变化', () => {
    expect(matchesPrefix(knowledgeKeys.categories(), knowledgeKeys.lists())).toBe(false)
    expect(matchesPrefix(knowledgeKeys.hotTags(), knowledgeKeys.all)).toBe(true)
  })

  it('文档 id 为数字类型，与字符串 id 不混淆', () => {
    expect(knowledgeKeys.detail(1)).not.toEqual(knowledgeKeys.detail(2))
  })
})

describe('dashboardKeys', () => {
  it('趋势的天数与下钻维度都进 key', () => {
    expect(dashboardKeys.trends(7)).not.toEqual(dashboardKeys.trends(30))
    expect(dashboardKeys.trends(7)).not.toEqual(dashboardKeys.trends(7, 'K8S'))
    expect(dashboardKeys.trends(7, 'K8S')).not.toEqual(dashboardKeys.trends(7, 'MYSQL'))
  })

  it('无下钻时用 null 占位 —— undefined 在序列化后可能与缺省混淆', () => {
    expect(dashboardKeys.trends(7)).toEqual([...dashboardKeys.all, 'trends', 7, null])
  })

  it('概览与趋势互不包含', () => {
    expect(matchesPrefix(dashboardKeys.trends(7), dashboardKeys.overview())).toBe(false)
  })
})

describe('approvalKeys', () => {
  it('待审数量与列表都在 all 之下 —— 审批后两者都要刷新', () => {
    expect(matchesPrefix(approvalKeys.pendingCount(), approvalKeys.all)).toBe(true)
    expect(matchesPrefix(approvalKeys.list('PENDING', 1, 20), approvalKeys.all)).toBe(true)
  })

  it('不同状态 tab 是不同缓存条目', () => {
    expect(approvalKeys.list('PENDING', 1, 20))
      .not.toEqual(approvalKeys.list('APPROVED', 1, 20))
  })
})

describe('各领域根 key 互不重叠', () => {
  it('六个领域的根 key 两两不同 —— 否则跨领域误失效', () => {
    const roots = [
      ticketKeys.all,
      alertKeys.all,
      knowledgeKeys.all,
      dashboardKeys.all,
      approvalKeys.all,
      userKeys.all,
    ]
    const serialized = roots.map(r => JSON.stringify(r))
    expect(new Set(serialized).size).toBe(roots.length)
  })
})
