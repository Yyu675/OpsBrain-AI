/**
 * L3 自动化治理 API 层测试。
 *
 * 覆盖重点有两块，都不是「调通了没」这类形式化断言：
 *
 * 1. **查询参数拼接** —— `enabled: false`（只看停用的动作）是一个有效筛选，
 *    若被当成空值丢掉，用户勾了「已停用」却看到全部条目，
 *    而且完全看不出哪里错了。
 *
 * 2. **写请求的 body 完整性** —— 尤其是 `version` 必须原样带上。
 *    漏传 version 后端会拒绝（这是刻意的，见 requireVersion 的注释），
 *    而 `requiresApproval: null`（跟随策略）不能被序列化成 undefined 丢掉，
 *    否则语义从「跟随策略」变成「不改这一项」。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}))
vi.mock('@/utils/http', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/utils/http')
  return { ...actual, http: httpMock }
})

import {
  createAction,
  evaluateAction,
  fetchActionStats,
  fetchActions,
  fetchRiskPolicies,
  toggleAction,
  updateAction,
  updateRiskPolicy,
  type ActionPayload,
  type RiskPolicyPayload,
} from '../governance'

const ok = (data: unknown) => ({ code: 0, message: 'ok', data })

const emptyPage = ok({ items: [], total: 0, page: 1, size: 20, totalPages: 1 })

beforeEach(() => {
  httpMock.get.mockReset()
  httpMock.post.mockReset()
  httpMock.put.mockReset()
})

afterEach(() => {
  vi.clearAllMocks()
})

const lastGetQuery = (): URLSearchParams => {
  const url = String(httpMock.get.mock.calls[0][0])
  const qs = url.includes('?') ? url.slice(url.indexOf('?') + 1) : ''
  return new URLSearchParams(qs)
}

describe('fetchActions — 查询参数', () => {
  it('无参数时不拼接空 query', async () => {
    httpMock.get.mockResolvedValue(emptyPage)
    await fetchActions()
    expect(String(httpMock.get.mock.calls[0][0])).not.toContain('?')
  })

  it('空字符串不进 URL——否则后端收到空串而非「不限」', async () => {
    httpMock.get.mockResolvedValue(emptyPage)
    await fetchActions({ keyword: '', category: '', riskLevel: '', page: 2 })

    const q = lastGetQuery()
    expect(q.has('keyword')).toBe(false)
    expect(q.has('category')).toBe(false)
    expect(q.has('riskLevel')).toBe(false)
    expect(q.get('page')).toBe('2')
  })

  it('enabled: false 必须保留——「只看已停用」是有效筛选，不能被当空值丢掉', async () => {
    httpMock.get.mockResolvedValue(emptyPage)
    await fetchActions({ enabled: false })

    expect(lastGetQuery().get('enabled')).toBe('false')
  })

  it('enabled: true 正常传递', async () => {
    httpMock.get.mockResolvedValue(emptyPage)
    await fetchActions({ enabled: true })

    expect(lastGetQuery().get('enabled')).toBe('true')
  })

  it('多条件组合全部带上', async () => {
    httpMock.get.mockResolvedValue(emptyPage)
    await fetchActions({
      keyword: 'restart',
      category: 'k8s',
      riskLevel: 'HIGH_RISK_EXECUTION',
      enabled: true,
      page: 3,
      size: 50,
    })

    const q = lastGetQuery()
    expect(q.get('keyword')).toBe('restart')
    expect(q.get('category')).toBe('k8s')
    expect(q.get('riskLevel')).toBe('HIGH_RISK_EXECUTION')
    expect(q.get('enabled')).toBe('true')
    expect(q.get('page')).toBe('3')
    expect(q.get('size')).toBe('50')
  })
})

describe('风险策略', () => {
  it('列表端点正确，且返回词表原样透出', async () => {
    httpMock.get.mockResolvedValue(
      ok({
        items: [{ riskLevel: 'READ_ONLY' }],
        approvalModes: [{ value: 'NONE', label: '免审批', requiredApprovers: 0 }],
        escalateTargets: [{ value: 'TICKET', label: '自动开工单' }],
      })
    )

    const page = await fetchRiskPolicies()
    expect(String(httpMock.get.mock.calls[0][0])).toContain('/governance/risk-policies')
    expect(page.items).toHaveLength(1)
    // 词表由后端下发，前端不维护枚举镜像——镜像必然漂移
    expect(page.approvalModes[0].value).toBe('NONE')
    expect(page.escalateTargets[0].label).toBe('自动开工单')
  })

  it('更新时 URL 带上等级、body 带上 version', async () => {
    httpMock.put.mockResolvedValue(ok({ riskLevel: 'HIGH_RISK_EXECUTION', version: 4 }))

    const payload: RiskPolicyPayload = {
      approvalMode: 'DUAL',
      approvalTimeoutMinutes: 15,
      autoExecuteAllowed: false,
      maxBlastRadiusPercent: 5,
      maxBlastRadiusCount: 1,
      cooldownSeconds: 300,
      maxRetries: 0,
      escalateAfterMinutes: 10,
      escalateTarget: 'ONCALL',
      allowedEnvironments: 'dev',
      version: 3,
    }
    await updateRiskPolicy('HIGH_RISK_EXECUTION', payload)

    const [url, body] = httpMock.put.mock.calls[0]
    expect(String(url)).toContain('/governance/risk-policies/HIGH_RISK_EXECUTION')
    // version 漏传后端会拒绝——这是刻意的，缺版本号等于关掉乐观锁
    expect((body as RiskPolicyPayload).version).toBe(3)
    expect((body as RiskPolicyPayload).approvalMode).toBe('DUAL')
  })

  it('autoExecuteAllowed: false 不能被丢掉——它是 body 而非 query，false 必须显式提交', async () => {
    httpMock.put.mockResolvedValue(ok({}))
    await updateRiskPolicy('DRAFT', {
      approvalMode: 'NONE',
      approvalTimeoutMinutes: 30,
      autoExecuteAllowed: false,
      maxBlastRadiusPercent: 100,
      maxBlastRadiusCount: 9999,
      cooldownSeconds: 0,
      maxRetries: 2,
      escalateAfterMinutes: 0,
      escalateTarget: 'NONE',
      allowedEnvironments: 'prod,staging,dev',
      version: 0,
    })

    const body = httpMock.put.mock.calls[0][1] as RiskPolicyPayload
    expect(body).toHaveProperty('autoExecuteAllowed', false)
  })
})

describe('动作白名单写操作', () => {
  const payload: ActionPayload = {
    actionKey: 'k8s.pod.restart',
    displayName: '优雅重启 Pod',
    description: null,
    category: 'k8s',
    riskLevel: 'CONTROLLED_WRITE',
    targetPattern: 'ns:staging/*',
    environments: 'staging,dev',
    paramSchema: null,
    requiresApproval: null,
    maxBlastRadiusCount: null,
    enabled: false,
    version: 0,
  }

  it('创建走 POST /governance/actions，payload 原样提交', async () => {
    httpMock.post.mockResolvedValue(ok({ id: 1 }))
    await createAction(payload)

    const [url, body] = httpMock.post.mock.calls[0]
    expect(String(url)).toMatch(/\/governance\/actions$/)
    expect(body).toMatchObject({ actionKey: 'k8s.pod.restart', enabled: false })
  })

  it('requiresApproval: null 必须保留——它表示「跟随策略」，不是「没填」', async () => {
    httpMock.post.mockResolvedValue(ok({ id: 1 }))
    await createAction(payload)

    const body = httpMock.post.mock.calls[0][1] as ActionPayload
    expect(body).toHaveProperty('requiresApproval', null)
    expect(body).toHaveProperty('maxBlastRadiusCount', null)
  })

  it('更新走 PUT /governance/actions/{id}', async () => {
    httpMock.put.mockResolvedValue(ok({ id: 7 }))
    await updateAction(7, { ...payload, version: 2 })

    const [url, body] = httpMock.put.mock.calls[0]
    expect(String(url)).toMatch(/\/governance\/actions\/7$/)
    expect((body as ActionPayload).version).toBe(2)
  })

  it('启停是独立端点，只提交 enabled 与 version', async () => {
    httpMock.post.mockResolvedValue(ok({ id: 7, enabled: true }))
    await toggleAction(7, true, 5)

    const [url, body] = httpMock.post.mock.calls[0]
    expect(String(url)).toMatch(/\/governance\/actions\/7\/toggle$/)
    // 只带这两个字段是刻意的：走全量更新需要前端回填所有字段，
    // 任何一项读漏都会被静默重置成默认值
    expect(body).toEqual({ enabled: true, version: 5 })
  })

  it('停用同样只提交这两个字段，enabled: false 不被丢掉', async () => {
    httpMock.post.mockResolvedValue(ok({ id: 7, enabled: false }))
    await toggleAction(7, false, 5)

    expect(httpMock.post.mock.calls[0][1]).toEqual({ enabled: false, version: 5 })
  })
})

describe('统计与模拟校验', () => {
  it('统计端点独立于列表——切筛选时风险敞口数字不应跟着变', async () => {
    httpMock.get.mockResolvedValue(
      ok({ total: 9, enabledCount: 2, highRiskEnabled: 0, prodEnabled: 0 })
    )

    const stats = await fetchActionStats()
    expect(String(httpMock.get.mock.calls[0][0])).toMatch(/\/governance\/actions\/stats$/)
    expect(stats.highRiskEnabled).toBe(0)
  })

  it('模拟校验提交 actionKey 与 environment，并透出拒绝原因', async () => {
    httpMock.post.mockResolvedValue(
      ok({
        actionKey: 'k8s.pod.restart',
        environment: 'prod',
        allowed: false,
        reason: '该动作未在 prod 环境开放（当前开放：staging,dev）',
      })
    )

    const result = await evaluateAction('k8s.pod.restart', 'prod')

    const [url, body] = httpMock.post.mock.calls[0]
    expect(String(url)).toMatch(/\/governance\/evaluate$/)
    expect(body).toEqual({ actionKey: 'k8s.pod.restart', environment: 'prod' })
    // reason 是这个接口的核心价值：拒绝时必须说清为什么
    expect(result.allowed).toBe(false)
    expect(result.reason).toContain('prod')
  })

  it('允许时透出生效后的约束，供页面直接展示', async () => {
    httpMock.post.mockResolvedValue(
      ok({
        actionKey: 'host.log.rotate',
        environment: 'staging',
        allowed: true,
        reason: '允许自动执行',
        requiresApproval: true,
        approvalMode: 'SINGLE',
        blastRadiusCount: 2,
        cooldownSeconds: 60,
      })
    )

    const result = await evaluateAction('host.log.rotate', 'staging')
    expect(result.allowed).toBe(true)
    expect(result.requiresApproval).toBe(true)
    expect(result.blastRadiusCount).toBe(2)
  })
})

describe('业务错误透传', () => {
  it('后端 40009（版本冲突）会抛出，不被静默吞掉', async () => {
    httpMock.put.mockResolvedValue({
      code: 40009,
      message: '该记录已被他人修改，请刷新后重试',
      data: null,
    })

    // 安全策略被静默覆盖正是「以为关掉了实际没关」的成因，
    // 这里必须抛而不是返回一个看起来成功的空对象
    await expect(
      updateRiskPolicy('CONTROLLED_WRITE', {
        approvalMode: 'SINGLE',
        approvalTimeoutMinutes: 30,
        autoExecuteAllowed: false,
        maxBlastRadiusPercent: 20,
        maxBlastRadiusCount: 5,
        cooldownSeconds: 60,
        maxRetries: 1,
        escalateAfterMinutes: 15,
        escalateTarget: 'TICKET',
        allowedEnvironments: 'staging,dev',
        version: 1,
      })
    ).rejects.toThrow()
  })

  it('后端 40001（校验失败）同样抛出', async () => {
    httpMock.post.mockResolvedValue({
      code: 40001,
      message: '高风险执行不允许配置为免审批',
      data: null,
    })

    await expect(
      createAction({
        actionKey: 'db.drop.table',
        displayName: '删表',
        category: 'database',
        riskLevel: 'HIGH_RISK_EXECUTION',
        environments: 'prod',
        requiresApproval: false,
      })
    ).rejects.toThrow()
  })
})
