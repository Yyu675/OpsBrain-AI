/**
 * DTO 转换器测试。
 *
 * 这些映射是历史缺陷高发区，测试锁定的是已修复缺陷的回归边界：
 * - 6.9 #3：service ↔ module 无法往返，「创建→读回」后下拉框选不中
 * - 6.9 #4：状态枚举缺 RESOLVED/VOID，resolved 被降级为 CLOSED 丢失信息
 * - 6.13：extractTagsFromModule 凭 module 编造标签（每张工单都贴「生产环境」）
 * - 6.15/6.38：派生字段（slaProgress / slaRemainingMinutes）降级值须可区分于真实值
 * - 6.43：优先级 urgent 与 high 双档塌缩成 HIGH，high 档事实上不存在
 */
import { describe, expect, it } from 'vitest'

import {
  convertBackendTicketToFrontend,
  mapBackendPriorityToFrontend,
  mapBackendStatusToFrontend,
  mapFrontendPriorityToBackend,
  mapFrontendStatusToBackend,
  mapModuleToService,
  mapServiceToModule,
} from '../dto-converter'
import { UNASSIGNED } from '../../../constants/ticket'
import type {
  BackendTicket,
  BackendTicketPriority,
  BackendTicketStatus,
  FrontendTicketPriority,
  FrontendTicketStatus,
} from '../../types/ticket'

/** 后端工单的最小骨架，各用例只覆盖关心的字段 */
function backendTicket(overrides: Partial<BackendTicket> = {}): BackendTicket {
  return {
    id: 'TKT-20260823-0001',
    title: '生产库连接池打满',
    description: '',
    status: 'PENDING',
    priority: 'P1',
    module: 'MYSQL',
    createTime: '2026-08-23T10:30:00',
    updateTime: '2026-08-23T10:30:00',
    ...overrides,
  } as BackendTicket
}

describe('状态映射', () => {
  const cases: Array<[BackendTicketStatus, FrontendTicketStatus]> = [
    ['PENDING', 'pending'],
    ['PROCESSING', 'processing'],
    ['RESOLVED', 'resolved'],
    ['CLOSED', 'closed'],
    ['VOID', 'void'],
  ]

  it.each(cases)('后端 %s → 前端 %s', (backend, frontend) => {
    expect(mapBackendStatusToFrontend(backend)).toBe(frontend)
  })

  it.each(cases)('前端 %s 往返回后端仍为 %s', (backend, frontend) => {
    expect(mapFrontendStatusToBackend(frontend)).toBe(backend)
  })

  it('resolved 不再被降级为 CLOSED —— 降级会丢失「已解决未关闭」这一状态', () => {
    expect(mapFrontendStatusToBackend('resolved')).toBe('RESOLVED')
  })

  it('VOID（Saga 补偿产生的作废态）有对应的前端状态', () => {
    expect(mapBackendStatusToFrontend('VOID')).toBe('void')
  })

  it('未知后端状态回落 pending 而非抛错', () => {
    expect(mapBackendStatusToFrontend('BOGUS' as BackendTicketStatus)).toBe('pending')
  })
})

describe('优先级映射（B0 四档）', () => {
  const cases: Array<[BackendTicketPriority, FrontendTicketPriority]> = [
    ['P0', 'urgent'],
    ['P1', 'high'],
    ['P2', 'medium'],
    ['P3', 'low'],
  ]

  it.each(cases)('后端 %s → 前端 %s', (backend, frontend) => {
    expect(mapBackendPriorityToFrontend(backend)).toBe(frontend)
  })

  it.each(cases)('前端 %s 往返回后端仍为 %s —— 四档一一对应无塌缩', (backend, frontend) => {
    expect(mapFrontendPriorityToBackend(frontend)).toBe(backend)
  })

  it('urgent 与 high 映射到不同后端值 —— B0 前二者都是 HIGH，high 档事实上不存在', () => {
    expect(mapFrontendPriorityToBackend('urgent')).not.toBe(
      mapFrontendPriorityToBackend('high')
    )
  })

  it('保存 high 后回读仍是 high —— 塌缩时会变成 urgent', () => {
    const saved = mapFrontendPriorityToBackend('high')
    expect(mapBackendPriorityToFrontend(saved)).toBe('high')
  })

  describe('历史数据兼容（迁移前的 HIGH/MEDIUM/LOW）', () => {
    it('HIGH → high（不是 urgent）—— 与后端 migration_v16 的 HIGH→P1 一致', () => {
      expect(mapBackendPriorityToFrontend('HIGH' as BackendTicketPriority)).toBe('high')
    })

    it('MEDIUM → medium', () => {
      expect(mapBackendPriorityToFrontend('MEDIUM' as BackendTicketPriority)).toBe('medium')
    })

    it('LOW → low', () => {
      expect(mapBackendPriorityToFrontend('LOW' as BackendTicketPriority)).toBe('low')
    })
  })

  it('未知优先级回落 medium', () => {
    expect(mapBackendPriorityToFrontend('BOGUS' as BackendTicketPriority)).toBe('medium')
  })
})

describe('module ↔ service 双向映射', () => {
  const pairs: Array<[string, string]> = [
    ['K8S', '生产集群-K8s'],
    ['MYSQL', '生产环境-MySQL'],
    ['ALIYUN_SLB', '生产环境-Nginx'],
    ['NETWORK', '网络'],
    ['OTHER', '未分类'],
  ]

  it.each(pairs)('module %s → service %s', (module, service) => {
    expect(mapModuleToService(module)).toBe(service)
  })

  it.each(pairs)('service %s 往返回 module 仍为 %s —— 不能往返会导致下拉框选不中', (module, service) => {
    expect(mapServiceToModule(service)).toBe(module)
  })

  it('module 小写输入也能命中（后端理论上给大写，但不应因大小写失配丢标签）', () => {
    expect(mapModuleToService('mysql')).toBe('生产环境-MySQL')
  })

  it('未知 module 原样返回 —— 回落到「未分类」会丢失后端新增的枚举值', () => {
    expect(mapModuleToService('REDIS_CLUSTER')).toBe('REDIS_CLUSTER')
  })

  it('未知 service 回落 OTHER —— 保证后端拿到合法枚举', () => {
    expect(mapServiceToModule('用户手输的服务名')).toBe('OTHER')
  })

  it('空值分别回落到「未分类」与 OTHER', () => {
    expect(mapModuleToService(undefined)).toBe('未分类')
    expect(mapModuleToService('')).toBe('未分类')
    expect(mapServiceToModule(undefined)).toBe('OTHER')
    expect(mapServiceToModule('')).toBe('OTHER')
  })
})

describe('convertBackendTicketToFrontend', () => {
  it('时间截断到分钟并把 T 换成空格', () => {
    const t = convertBackendTicketToFrontend(
      backendTicket({ createTime: '2026-08-23T10:30:45.123', updateTime: '2026-08-23T11:05:00' })
    )
    expect(t.createdAt).toBe('2026-08-23 10:30')
    expect(t.updatedAt).toBe('2026-08-23 11:05')
  })

  it('时间为空串时不产出 "undefined" 类字符串', () => {
    const t = convertBackendTicketToFrontend(backendTicket({ createTime: '', updateTime: '' }))
    expect(t.createdAt).toBe('')
    expect(t.updatedAt).toBe('')
  })

  it('assignee 缺失时用 UNASSIGNED 哨兵，不用空串', () => {
    const t = convertBackendTicketToFrontend(backendTicket({ assignee: undefined }))
    expect(t.assignee).toBe(UNASSIGNED)
  })

  describe('标签（6.13：此前凭 module 编造）', () => {
    it('后端返回的标签原样透传', () => {
      const t = convertBackendTicketToFrontend(
        backendTicket({ tags: ['主从延迟', '预发环境'] })
      )
      expect(t.tags).toEqual(['主从延迟', '预发环境'])
    })

    it('后端未返回标签时为空数组，不凭 module 编造', () => {
      const t = convertBackendTicketToFrontend(backendTicket({ module: 'MYSQL', tags: undefined }))
      expect(t.tags).toEqual([])
    })

    it('后端返回空数组表示「确实没有标签」，同样不编造', () => {
      const t = convertBackendTicketToFrontend(backendTicket({ module: 'K8S', tags: [] }))
      expect(t.tags).toEqual([])
    })
  })

  describe('SLA 派生字段（6.15/6.38：降级值须可区分于真实值）', () => {
    it('后端算出的进度与超时标记原样采用', () => {
      const t = convertBackendTicketToFrontend(
        backendTicket({ slaProgress: 85, slaBreached: false })
      )
      expect(t.slaProgress).toBe(85)
      expect(t.slaBreached).toBe(false)
    })

    it('slaProgress 缺失回落 0（进度未知时不显示虚假进度）', () => {
      const t = convertBackendTicketToFrontend(backendTicket({ slaProgress: undefined }))
      expect(t.slaProgress).toBe(0)
    })

    it('slaProgress 为 0 时保留 0，不被 ?? 误替换', () => {
      const t = convertBackendTicketToFrontend(backendTicket({ slaProgress: 0 }))
      expect(t.slaProgress).toBe(0)
    })

    it('slaRemainingMinutes 缺失用 null 表「无法计算」，不用 0 冒充', () => {
      const t = convertBackendTicketToFrontend(
        backendTicket({ slaRemainingMinutes: undefined })
      )
      expect(t.slaRemainingMinutes).toBeNull()
    })

    it('slaRemainingMinutes 为 0 表「刚好用完」，必须保留而非当作未知', () => {
      const t = convertBackendTicketToFrontend(backendTicket({ slaRemainingMinutes: 0 }))
      expect(t.slaRemainingMinutes).toBe(0)
    })

    it('已超时的负数剩余时间原样保留', () => {
      const t = convertBackendTicketToFrontend(
        backendTicket({ slaRemainingMinutes: -120, slaBreached: true })
      )
      expect(t.slaRemainingMinutes).toBe(-120)
      expect(t.slaBreached).toBe(true)
    })
  })

  describe('B1 首响字段', () => {
    it('firstResponseState 缺失回落 WAITING（未首响）', () => {
      const t = convertBackendTicketToFrontend(
        backendTicket({ firstResponseState: undefined })
      )
      expect(t.firstResponseState).toBe('WAITING')
    })

    it('firstResponseMinutes 缺失用 null —— 与「0 分钟（秒级响应）」区分', () => {
      const t = convertBackendTicketToFrontend(
        backendTicket({ firstResponseMinutes: undefined })
      )
      expect(t.firstResponseMinutes).toBeNull()
    })

    it('firstResponseMinutes 为 0 时保留 0', () => {
      const t = convertBackendTicketToFrontend(backendTicket({ firstResponseMinutes: 0 }))
      expect(t.firstResponseMinutes).toBe(0)
    })

    it('已超时状态与首响人原样透传', () => {
      const t = convertBackendTicketToFrontend(
        backendTicket({ firstResponseState: 'BREACHED', firstResponder: '王芳' })
      )
      expect(t.firstResponseState).toBe('BREACHED')
      expect(t.firstResponder).toBe('王芳')
    })
  })

  describe('乐观锁 version（6.11/6.16）', () => {
    it('后端 version 原样采用', () => {
      const t = convertBackendTicketToFrontend(backendTicket({ version: 3 }))
      expect(t.version).toBe(3)
    })

    it('version 为 0（新建工单）时保留 0，不被回落覆盖', () => {
      const t = convertBackendTicketToFrontend(backendTicket({ version: 0 }))
      expect(t.version).toBe(0)
    })

    it('version 缺失回落 0', () => {
      const t = convertBackendTicketToFrontend(backendTicket({ version: undefined }))
      expect(t.version).toBe(0)
    })
  })

  it('附件/回复/活动流恒为空数组 —— 它们需单独接口获取，此处不得臆造', () => {
    const t = convertBackendTicketToFrontend(backendTicket())
    expect(t.attachments).toEqual([])
    expect(t.replies).toEqual([])
    expect(t.activities).toEqual([])
  })

  it('module 转换为可读的 service 标签', () => {
    const t = convertBackendTicketToFrontend(backendTicket({ module: 'K8S' }))
    expect(t.service).toBe('生产集群-K8s')
  })
})
