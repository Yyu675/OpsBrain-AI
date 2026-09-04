/**
 * Saga 补偿中心 API 层测试。
 *
 * ── 覆盖重点是契约形状与 URL 构造，不是「调通了没」 ──────────────
 * 这层最危险的是**后端 toDetail 契约漂移**：SagaController 返回的
 * 是手拼 Map（非实体），字段名一旦与前端类型不一致，页面渲染出 undefined
 * 而请求本身 200 不报错——静默错误。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/utils/http', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/utils/http')
  return { ...actual, http: httpMock }
})

import { compensateSaga, fetchSagaAttention, fetchSagaSteps, type SagaStep } from '../saga'

const ok = (data: unknown) => ({ code: 0, message: 'ok', data })

const stepFixture: SagaStep = {
  id: 301, traceId: 'TRC-1', sessionId: null, sagaId: 'SAGA-1', stepSeq: 2,
  toolName: 'createDevOpsTicket', riskLevel: 'CONTROLLED_WRITE', riskLevelLabel: '受控写',
  state: 'PARTIAL_SUCCESS', stateLabel: '部分成功', needsAttention: true,
  failureType: 'PARTIAL_SUCCESS', failureHint: '必须触发 Saga 补偿',
  errorMessage: 'createTicket 成功但后续失败', compensable: true,
  compensationAction: 'voidTicket', businessKey: 'TKT-1', compensatedAt: null,
  compensationError: null, attemptCount: 1, durationMs: 840,
  createTime: '2026-08-12T03:50:00', updateTime: '2026-08-12T03:50:00'
}

beforeEach(() => {
  httpMock.get.mockReset()
  httpMock.post.mockReset()
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('fetchSagaAttention', () => {
  it('请求 /saga/attention 并带回 limit 参数', async () => {
    httpMock.get.mockResolvedValue(ok({ records: [stepFixture], count: 1 }))
    await fetchSagaAttention(100)

    const url = String(httpMock.get.mock.calls[0][0])
    expect(url).toContain('/saga/attention')
    expect(url).toContain('limit=100')
  })

  it('透传后端 records/count 结构（对齐 toDetail 字段）', async () => {
    httpMock.get.mockResolvedValue(ok({ records: [stepFixture], count: 1 }))
    const res = await fetchSagaAttention()
    expect(res.count).toBe(1)
    expect(res.records[0]).toMatchObject({
      sagaId: 'SAGA-1',
      businessKey: 'TKT-1',
      state: 'PARTIAL_SUCCESS',
      failureHint: '必须触发 Saga 补偿',
      compensable: true,
      compensationAction: 'voidTicket'
    })
  })
})

describe('fetchSagaSteps', () => {
  it('对 sagaId 做 URL 编码', async () => {
    httpMock.get.mockResolvedValue(ok({ sagaId: 'SAGA-1', steps: [stepFixture], stepCount: 1 }))
    await fetchSagaSteps('SAGA-1')
    const url = String(httpMock.get.mock.calls[0][0])
    expect(url).toContain('/saga/SAGA-1/steps')
  })

  it('返回步骤链路与 stepCount', async () => {
    httpMock.get.mockResolvedValue(ok({ sagaId: 'SAGA-1', steps: [stepFixture], stepCount: 1 }))
    const res = await fetchSagaSteps('SAGA-1')
    expect(res.sagaId).toBe('SAGA-1')
    expect(res.stepCount).toBe(1)
    expect(res.steps[0].stepSeq).toBe(2)
  })
})

describe('compensateSaga', () => {
  it('POST /saga/{id}/compensate 并返回补偿结果', async () => {
    httpMock.post.mockResolvedValue(ok({
      sagaId: 'SAGA-1', compensatedCount: 1, failedCount: 0,
      compensated: ['createDevOpsTicket'], failed: [], fullySucceeded: true
    }))
    const res = await compensateSaga('SAGA-1')
    const url = String(httpMock.post.mock.calls[0][0])
    expect(url).toContain('/saga/SAGA-1/compensate')
    expect(res.fullySucceeded).toBe(true)
    expect(res.compensatedCount).toBe(1)
  })
})
