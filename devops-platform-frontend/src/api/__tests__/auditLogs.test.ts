/**
 * 审计日志 API 层测试。
 *
 * 重点是**查询参数拼接**——这层最容易出的问题是把空值也拼进 URL：
 * `?actorId=&action=&targetType=` 会让后端收到空字符串而非「不限」，
 * 若后端用 `notBlank` 判断还好，一旦某处改成判 `!= null` 就会筛出零结果，
 * 而用户完全看不出哪里错了。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/utils/http', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/utils/http')
  return { ...actual, http: httpMock }
})

import {
  fetchAiCallLogs,
  fetchAuditFilterOptions,
  fetchOperationAudit,
  fetchTraceDetail,
} from '../auditLogs'

const ok = (data: unknown) => ({ code: 0, message: 'ok', data })

beforeEach(() => {
  httpMock.get.mockReset()
})

afterEach(() => {
  vi.clearAllMocks()
})

/** 取出本次请求的 URL 中的 query 部分 */
const lastQuery = (): URLSearchParams => {
  const url = String(httpMock.get.mock.calls[0][0])
  const qs = url.includes('?') ? url.slice(url.indexOf('?') + 1) : ''
  return new URLSearchParams(qs)
}

describe('fetchAiCallLogs — 查询参数', () => {
  it('无参数时不拼接空 query', async () => {
    httpMock.get.mockResolvedValue(ok({ items: [], total: 0, page: 1, size: 20, totalPages: 0, stats: {} }))
    await fetchAiCallLogs()
    expect(String(httpMock.get.mock.calls[0][0])).not.toContain('?')
  })

  it('空字符串与 undefined 都不进 URL —— 否则后端收到空串而非「不限」', async () => {
    httpMock.get.mockResolvedValue(ok({ items: [], total: 0, page: 1, size: 20, totalPages: 0, stats: {} }))
    await fetchAiCallLogs({ modelName: '', operationType: undefined, page: 1 })

    const q = lastQuery()
    expect(q.has('modelName')).toBe(false)
    expect(q.has('operationType')).toBe(false)
    expect(q.get('page')).toBe('1')
  })

  it('布尔 false 必须保留 —— 「仅未命中缓存」是有效筛选，不能被当成空值丢掉', async () => {
    httpMock.get.mockResolvedValue(ok({ items: [], total: 0, page: 1, size: 20, totalPages: 0, stats: {} }))
    await fetchAiCallLogs({ cached: false })

    expect(lastQuery().get('cached')).toBe('false')
  })

  it('数字 0 不被当成空值丢掉', async () => {
    httpMock.get.mockResolvedValue(ok({ items: [], total: 0, page: 1, size: 20, totalPages: 0, stats: {} }))
    await fetchAiCallLogs({ minLatencyMs: 0 })

    expect(lastQuery().get('minLatencyMs')).toBe('0')
  })

  it('返回体含 stats，与列表同源', async () => {
    httpMock.get.mockResolvedValue(ok({
      items: [], total: 0, page: 1, size: 20, totalPages: 0,
      stats: { totalCalls: 10, cacheHits: 3, cacheHitRate: 30, totalCost: 1.23, avgLatencyMs: 800 },
    }))
    const res = await fetchAiCallLogs()
    expect(res.stats.cacheHitRate).toBe(30)
  })
})

describe('fetchOperationAudit — 查询参数', () => {
  it('success=false 保留（筛失败操作是核心用法）', async () => {
    httpMock.get.mockResolvedValue(ok({ items: [], total: 0, page: 1, size: 20, totalPages: 0 }))
    await fetchOperationAudit({ success: false })
    expect(lastQuery().get('success')).toBe('false')
  })

  it('时间范围原样传递', async () => {
    httpMock.get.mockResolvedValue(ok({ items: [], total: 0, page: 1, size: 20, totalPages: 0 }))
    await fetchOperationAudit({ from: '2026-08-01T00:00:00', to: '2026-08-24T23:59:59' })

    const q = lastQuery()
    expect(q.get('from')).toBe('2026-08-01T00:00:00')
    expect(q.get('to')).toBe('2026-08-24T23:59:59')
  })
})

describe('fetchTraceDetail', () => {
  it('traceId 经 URL 编码 —— 防止特殊字符破坏路径', async () => {
    httpMock.get.mockResolvedValue(ok({ traceId: 'a/b', aiCall: null, operations: [] }))
    await fetchTraceDetail('a/b')

    expect(String(httpMock.get.mock.calls[0][0])).toContain('a%2Fb')
  })

  it('后端返回 40004 时抛错，供调用方展示「已过保留期」而非空白面板', async () => {
    httpMock.get.mockResolvedValue({ code: 40004, message: '该链路无记录', data: null })
    await expect(fetchTraceDetail('missing')).rejects.toThrow()
  })
})

describe('fetchAuditFilterOptions', () => {
  it('解包后直接返回四组候选值', async () => {
    httpMock.get.mockResolvedValue(ok({
      models: ['qwen-max'], operationTypes: ['CHAT'],
      actions: ['ticket.create'], targetTypes: ['TICKET'],
    }))
    const res = await fetchAuditFilterOptions()
    expect(res.models).toEqual(['qwen-max'])
    expect(res.actions).toEqual(['ticket.create'])
  })
})
