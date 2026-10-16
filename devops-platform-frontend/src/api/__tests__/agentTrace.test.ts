/**
 * Agent 执行轨迹 API 层测试。
 *
 * 这层的价值不在「能不能取到数据」，而在**几个容易被写坏的判空语义**：
 *
 * 1. `found=false`（会话已被清理）与 `found=true` 且零迁移（流程卡在最开始）
 *    都表现为空列表，含义却完全相反。若前端只看 `transitions.length`，
 *    会把「真实故障」显示成「已过期」，把人引向错误的排查方向；
 * 2. `durationMs` 的 0 是**有效读数**（首段迁移耗时本就是 0），
 *    用 `if (!ms)` 判空会把「瞬间完成」显示成「-」，看起来像没采到数据；
 * 3. `slowestTransition` 空数组必须返回 null 而非零值对象，
 *    否则调用方会以为存在一段耗时 0 的迁移。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/utils/http', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/utils/http')
  return { ...actual, http: httpMock }
})

import {
  agentStateColor,
  fetchAgentTrace,
  fetchAgentTraceStats,
  formatDuration,
  slowestTransition,
  type AgentTransitionItem,
} from '../agentTrace'

const ok = (data: unknown) => ({ code: 0, message: 'ok', data })

const transition = (over: Partial<AgentTransitionItem> = {}): AgentTransitionItem => ({
  id: 'a1',
  sessionId: 's1',
  fromState: 'NEW',
  fromStateText: '新建',
  toState: 'CONTEXT_PREPARED',
  toStateText: '上下文就绪',
  triggerType: 'SECURITY_PASSED',
  triggerDetail: '安全检查通过',
  operator: 'SYSTEM',
  timestamp: '2026-08-25T10:00:00',
  durationMs: 0,
  metadata: null,
  ...over,
})

beforeEach(() => {
  httpMock.get.mockReset()
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('fetchAgentTrace', () => {
  it('请求正确的端点并解包 data', async () => {
    httpMock.get.mockResolvedValue(
      ok({ traceId: 'tr-1', found: true, transitions: [], transitionCount: 0 })
    )

    const res = await fetchAgentTrace('tr-1')

    expect(String(httpMock.get.mock.calls[0][0])).toContain('/agent/traces/tr-1')
    expect(res.traceId).toBe('tr-1')
  })

  it('traceId 做 URL 编码——含斜杠或空格时不能拼坏路径', async () => {
    httpMock.get.mockResolvedValue(ok({ traceId: 'x', found: false, transitions: [] }))

    await fetchAgentTrace('a/b c')

    const url = String(httpMock.get.mock.calls[0][0])
    // 不编码的话 `a/b` 会被当成两级路径，请求打到一个不存在的端点上
    expect(url).toContain('a%2Fb%20c')
    expect(url).not.toContain('a/b c')
  })

  it('会话不存在：found=false 且带说明，不抛异常', async () => {
    httpMock.get.mockResolvedValue(
      ok({
        traceId: 'ghost',
        found: false,
        currentState: null,
        transitions: [],
        message: '会话不存在：可能已超过 30 分钟空闲期被清理，或 traceId 有误',
      })
    )

    const res = await fetchAgentTrace('ghost')

    // 后端对「查不到」不报错，调用方读 found 即可，不该在这层兜 404
    expect(res.found).toBe(false)
    expect(res.message).toContain('会话不存在')
  })

  it('会话存在但零迁移：found=true，与「查不到」区分开', async () => {
    httpMock.get.mockResolvedValue(
      ok({
        traceId: 'tr-2',
        found: true,
        currentState: 'NEW',
        transitions: [],
        transitionCount: 0,
        message: '会话已创建但尚未发生任何状态迁移',
      })
    )

    const res = await fetchAgentTrace('tr-2')

    // 这是真实故障信号（流程卡在最开始），不能和「已被清理」混为一谈
    expect(res.found).toBe(true)
    expect(res.currentState).toBe('NEW')
    expect(res.transitions).toHaveLength(0)
  })

  it('正常轨迹：迁移列表按顺序解出，机器码与中文名都在', async () => {
    httpMock.get.mockResolvedValue(
      ok({
        traceId: 'tr-3',
        found: true,
        currentState: 'SUCCESS',
        currentStateText: '成功',
        terminal: true,
        settled: true,
        transitionCount: 2,
        totalDurationMs: 120,
        transitions: [
          transition(),
          transition({
            id: 'a2',
            fromState: 'CONTEXT_PREPARED',
            toState: 'SUCCESS',
            toStateText: '成功',
            triggerType: 'CACHE_HIT',
            triggerDetail: '语义缓存命中',
            durationMs: 120,
          }),
        ],
      })
    )

    const res = await fetchAgentTrace('tr-3')

    expect(res.transitions).toHaveLength(2)
    expect(res.transitions[1].triggerType).toBe('CACHE_HIT')
    expect(res.transitions[1].toStateText).toBe('成功')
    expect(res.terminal).toBe(true)
  })

  it('业务错误码原样抛出，不吞成空轨迹', async () => {
    httpMock.get.mockResolvedValue({ code: 50001, message: '服务内部异常', data: null })

    // 吞掉错误会让页面显示「无轨迹」，用户以为流程没跑，实际是接口挂了
    await expect(fetchAgentTrace('tr-4')).rejects.toBeTruthy()
  })
})

describe('fetchAgentTraceStats', () => {
  it('返回驻留会话数与空闲超时配置', async () => {
    httpMock.get.mockResolvedValue(ok({ activeSessions: 42, idleTimeoutMinutes: 30 }))

    const res = await fetchAgentTraceStats()

    expect(String(httpMock.get.mock.calls[0][0])).toContain('/agent/traces/stats')
    expect(res.activeSessions).toBe(42)
  })

  it('零会话是有效读数，不被当成缺失', async () => {
    httpMock.get.mockResolvedValue(ok({ activeSessions: 0, idleTimeoutMinutes: 30 }))

    const res = await fetchAgentTraceStats()

    // 0 和 undefined 是两回事：前者说明清理正常工作，后者说明取数失败
    expect(res.activeSessions).toBe(0)
  })
})

describe('agentStateColor', () => {
  it('补偿失败需人工介入标红——比单纯失败更需要被看见', () => {
    // 这个状态意味着有脏数据残留、自动化收不了尾，是全系统最紧急的信号
    expect(agentStateColor('MANUAL_ESCALATED')).toBe('danger')
    expect(agentStateColor('FAILED')).toBe('danger')
  })

  it('已脱离自动流程但尚未出事的状态标黄', () => {
    expect(agentStateColor('COMPENSATING')).toBe('warning')
    expect(agentStateColor('WAITING_APPROVAL')).toBe('warning')
  })

  it('成功绿、归档灰、进行中蓝', () => {
    expect(agentStateColor('SUCCESS')).toBe('success')
    expect(agentStateColor('CLOSED')).toBe('gray')
    expect(agentStateColor('TOOLS_RUNNING')).toBe('primary')
  })

  it('null 不崩，回落为 gray', () => {
    expect(agentStateColor(null)).toBe('gray')
  })

  it('未知状态回落为 primary 而非报错——后端新增状态时前端不该白屏', () => {
    // 后端加了新状态而前端没同步是常态，此时应保守显示而不是崩溃
    expect(agentStateColor('SOME_NEW_STATE')).toBe('primary')
  })
})

describe('formatDuration', () => {
  it('0 是有效读数，显示「0 ms」而不是「-」', () => {
    // 首段迁移耗时本就是 0。用 if (!ms) 判空会把「瞬间完成」
    // 显示成「-」，看起来像是没采到数据
    expect(formatDuration(0)).toBe('0 ms')
  })

  it('null / undefined 才显示「-」', () => {
    expect(formatDuration(null)).toBe('-')
    expect(formatDuration(undefined)).toBe('-')
  })

  it('毫秒 / 秒 / 分秒 三档切换', () => {
    expect(formatDuration(999)).toBe('999 ms')
    expect(formatDuration(1500)).toBe('1.5 s')
    expect(formatDuration(65_000)).toBe('1 分 5 秒')
  })
})

describe('slowestTransition', () => {
  it('找出耗时最长的一段', () => {
    const list = [
      transition({ id: 'a', durationMs: 10 }),
      transition({ id: 'b', durationMs: 1500 }),
      transition({ id: 'c', durationMs: 200 }),
    ]

    expect(slowestTransition(list)?.id).toBe('b')
  })

  it('空数组返回 null，而不是零值对象', () => {
    // 返回零值对象会让调用方以为存在一段耗时 0 的迁移
    expect(slowestTransition([])).toBeNull()
  })

  it('全为 0 时仍返回一条，不因「假值」判空而返回 null', () => {
    const list = [transition({ id: 'a', durationMs: 0 }), transition({ id: 'b', durationMs: 0 })]

    expect(slowestTransition(list)).not.toBeNull()
  })
})
