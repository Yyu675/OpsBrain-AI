/**
 * Saga 补偿中心测试。
 *
 * ── 为什么这一页优先级最高 ────────────────────────────────────
 * 它是全站**破坏力最强的写操作入口**：点一次「补偿」就会执行
 * <b>逆向回滚已经落库的写操作</b>（如作废已创建的工单）。
 *
 * 后端侧我上一轮已补过写端点角色边界（OPS 触发补偿得 403），
 * 但前端「按钮什么时候能点、点下去弹什么、并发点会怎样」此前**零覆盖**——
 * 而这页恰恰是本次合入的 4 个提交里新增的，属于最新、最没被验证过的代码。
 *
 * ── 守四件事 ──────────────────────────────────────────────────
 * <ol>
 *   <li><b>串行化</b>——一次只允许一个补偿在跑。逆向补偿并发下发时，
 *       若两个 Saga 触及同一条业务数据，回滚顺序不可预测；</li>
 *   <li><b>二次确认必须先于请求</b>——且确认框里要出现 sagaId 与
 *       businessKey，否则用户是在对一个看不见的对象做不可逆操作；</li>
 *   <li><b>部分成功不能报成功</b>——`fullySucceeded=false` 时仍有脏数据残留，
 *       报「补偿完成」会让人以为收敛了，实际还需人工介入；</li>
 *   <li><b>补偿后必须重新拉取</b>——列表状态变了，不刷新等于让用户
 *       对着过期数据继续操作。</li>
 * </ol>
 *
 * ── 一个刻意的断言口径 ────────────────────────────────────────
 * 「串行化」不只断言第二次请求没发出去，还断言**弹窗都没弹**——
 * 弹了框再拒绝，用户会以为这次操作被受理了。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock, prompt: vi.fn() },
  ElMessage: Object.assign(vi.fn(), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
}))

const notifyMock = vi.hoisted(() => ({
  success: vi.fn(), warning: vi.fn(), error: vi.fn(),
  info: vi.fn(), clearCooldown: vi.fn(),
}))
vi.mock('@/utils/notify', () => ({
  notify: notifyMock,
  handleServerError: vi.fn(),
}))

// 只桩网络层，派生逻辑（分组筛选/状态映射/串行化）全部留在被测范围内
const api = vi.hoisted(() => ({
  fetchSagaAttention: vi.fn(),
  fetchSagaSteps: vi.fn(),
  compensateSaga: vi.fn(),
}))
vi.mock('@/api/saga', () => api)

import SagaCompensation from '../SagaCompensation.vue'

type Step = {
  id: number
  traceId: string
  sessionId: string | null
  sagaId: string
  stepSeq: number
  toolName: string
  riskLevel: string | null
  riskLevelLabel: string | null
  state: string | null
  stateLabel: string | null
  needsAttention: boolean
  failureType: string | null
  failureHint: string | null
  errorMessage: string | null
  compensable: boolean
  compensationAction: string | null
  businessKey: string | null
  compensatedAt: string | null
  compensationError: string | null
  attemptCount: number
  durationMs: number | null
  createTime: string
  updateTime: string
}

const step = (over: Partial<Step> = {}): Step => ({
  id: 1,
  traceId: 'trace-001',
  sessionId: null,
  sagaId: 'saga-001',
  stepSeq: 1,
  toolName: 'createDevOpsTicket',
  riskLevel: 'CONTROLLED_WRITE',
  riskLevelLabel: '受控写操作',
  state: 'PARTIAL_SUCCESS',
  stateLabel: '部分成功',
  needsAttention: true,
  failureType: 'PARTIAL_SUCCESS',
  failureHint: '检查下游服务是否已恢复后重试补偿',
  errorMessage: '工单已创建但通知发送失败',
  compensable: true,
  compensationAction: 'voidTicket',
  businessKey: 'TKT-20260826-0001',
  compensatedAt: null,
  compensationError: null,
  attemptCount: 1,
  durationMs: 1200,
  createTime: '2026-08-26 09:00:00',
  updateTime: '2026-08-26 09:00:05',
  ...over,
})

const mountPage = async (records: Step[] = [step()], opts: { listError?: unknown } = {}) => {
  if (opts.listError) {
    api.fetchSagaAttention.mockRejectedValue(opts.listError)
  } else {
    api.fetchSagaAttention.mockResolvedValue({ records, count: records.length })
  }
  const wrapper = mount(SagaCompensation, {
    global: {
      stubs: { RelativeTime: true },
    },
  })
  await flushPromises()
  return wrapper
}

type Vm = {
  activeTab: string
  records: Step[]
  filtered: Step[]
  loading: boolean
  loadError: string
  compensatingId: string | null
  expandedSteps: Record<string, Step[]>
  load: () => Promise<void>
  toggleSteps: (s: Step) => Promise<void>
  onCompensate: (s: Step) => Promise<void>
  stateLabel: (s: string | null) => string
  stateClass: (s: string | null) => string
  fmtTime: (t: string | null) => string
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
})

describe('列表加载与分组', () => {
  it('挂载即拉取待介入记录', async () => {
    await mountPage()
    expect(api.fetchSagaAttention).toHaveBeenCalledTimes(1)
  })

  it('渲染记录卡片与业务主键——用户真正关心「哪条数据坏了」', async () => {
    const w = await mountPage([step({ businessKey: 'TKT-20260826-0001' })])

    expect(w.findAll('.record-card')).toHaveLength(1)
    expect(w.text()).toContain('TKT-20260826-0001')
  })

  it('三个分组各自只显示对应状态', async () => {
    const w = await mountPage([
      step({ id: 1, sagaId: 's1', state: 'PARTIAL_SUCCESS' }),
      step({ id: 2, sagaId: 's2', state: 'COMPENSATION_FAILED' }),
      step({ id: 3, sagaId: 's3', state: 'MANUAL_INTERVENTION_REQUIRED' }),
    ])
    const vm = vmOf(w)

    expect(vm.filtered).toHaveLength(3)

    vm.activeTab = 'PARTIAL'
    await w.vm.$nextTick()
    expect(vm.filtered.map((r) => r.sagaId)).toEqual(['s1'])

    vm.activeTab = 'FAILED'
    await w.vm.$nextTick()
    expect(vm.filtered.map((r) => r.sagaId)).toEqual(['s2'])

    vm.activeTab = 'MANUAL'
    await w.vm.$nextTick()
    expect(vm.filtered.map((r) => r.sagaId)).toEqual(['s3'])
  })

  it('加载失败显示错误态并提供重试，不静默吞掉', async () => {
    // 这页是「系统哪里坏了」的清单。它自己加载失败却不出声，
    // 用户会以为「没有待处理事务」——最危险的一种误读
    const w = await mountPage([], { listError: new Error('boom') })

    expect(vmOf(w).loadError).toBeTruthy()
    expect(w.find('.retry-link').exists()).toBe(true)
    expect(notifyMock.error).toHaveBeenCalled()
  })

  it('空列表显示空态而非空白', async () => {
    const w = await mountPage([])
    expect(w.find('.state-box').exists()).toBe(true)
    expect(w.findAll('.record-card')).toHaveLength(0)
  })

  it('错误信息与处理建议都渲染出来', async () => {
    // failureHint 来自后端 failureType.getHandlingHint()，
    // 是运维决定「能不能直接重试」的依据
    const w = await mountPage([step({
      errorMessage: '工单已创建但通知发送失败',
      failureHint: '检查下游服务是否已恢复后重试补偿',
    })])

    expect(w.find('.err-msg').text()).toContain('通知发送失败')
    expect(w.find('.err-hint').text()).toContain('检查下游服务')
  })
})

describe('执行链路展开', () => {
  it('展开时按 stepSeq 升序拉取并渲染——顺序即执行顺序', async () => {
    // 后端不保证返回顺序。乱序展示会让人把因果读反：
    // 「先补偿后创建」这种时间线是无法排查的
    api.fetchSagaSteps.mockResolvedValue({
      sagaId: 'saga-001',
      steps: [
        step({ id: 30, stepSeq: 3, toolName: 'notify' }),
        step({ id: 10, stepSeq: 1, toolName: 'createTicket' }),
        step({ id: 20, stepSeq: 2, toolName: 'assign' }),
      ],
      stepCount: 3,
    })
    const w = await mountPage()

    await vmOf(w).toggleSteps(step({ sagaId: 'saga-001' }))
    await flushPromises()

    expect(vmOf(w).expandedSteps['saga-001'].map((s) => s.stepSeq)).toEqual([1, 2, 3])
  })

  it('再次点击折叠，且不重复请求', async () => {
    api.fetchSagaSteps.mockResolvedValue({ sagaId: 'saga-001', steps: [], stepCount: 0 })
    const w = await mountPage()
    const vm = vmOf(w)

    await vm.toggleSteps(step({ sagaId: 'saga-001' }))
    await flushPromises()
    expect(vm.expandedSteps['saga-001']).toBeDefined()

    await vm.toggleSteps(step({ sagaId: 'saga-001' }))
    await flushPromises()

    expect(vm.expandedSteps['saga-001']).toBeUndefined()
    // 折叠是纯本地操作，不该再打一次接口
    expect(api.fetchSagaSteps).toHaveBeenCalledTimes(1)
  })

  it('步骤加载失败只提示，不影响主列表', async () => {
    api.fetchSagaSteps.mockRejectedValue(new Error('steps down'))
    const w = await mountPage()

    await vmOf(w).toggleSteps(step({ sagaId: 'saga-001' }))
    await flushPromises()

    expect(notifyMock.error).toHaveBeenCalled()
    // 主列表仍在，不能因为子请求失败把整页打空
    expect(w.findAll('.record-card')).toHaveLength(1)
  })
})

describe('人工补偿：不可逆操作的三道闸', () => {
  it('先二次确认，取消则不发请求', async () => {
    confirmMock.mockRejectedValue('cancel')
    const w = await mountPage()

    await vmOf(w).onCompensate(step())

    expect(api.compensateSaga).not.toHaveBeenCalled()
  })

  it('确认框必须写明 sagaId 与业务主键', async () => {
    // 不写明的话，用户是在对一个看不见的对象做不可逆回滚
    const w = await mountPage()

    await vmOf(w).onCompensate(step({ sagaId: 'saga-777', businessKey: 'TKT-9527' }))

    const msg = String(confirmMock.mock.calls[0][0])
    expect(msg).toContain('saga-777')
    expect(msg).toContain('TKT-9527')
    // 也要说清后果
    expect(msg).toContain('回滚')
  })

  it('确认后触发补偿并按 sagaId 调用', async () => {
    api.compensateSaga.mockResolvedValue({
      sagaId: 'saga-001', compensatedCount: 2, failedCount: 0,
      compensated: ['a', 'b'], failed: [], fullySucceeded: true,
    })
    const w = await mountPage()

    await vmOf(w).onCompensate(step({ sagaId: 'saga-001' }))
    await flushPromises()

    expect(api.compensateSaga).toHaveBeenCalledWith('saga-001')
    expect(notifyMock.success).toHaveBeenCalledWith(expect.stringContaining('2'))
  })

  it('部分成功用 warning 如实区分，不报「补偿完成」', async () => {
    // fullySucceeded=false 意味着仍有脏数据残留。
    // 报 success 会让人以为事务已收敛，实际还需人工介入
    api.compensateSaga.mockResolvedValue({
      sagaId: 'saga-001', compensatedCount: 1, failedCount: 2,
      compensated: ['a'], failed: ['b', 'c'], fullySucceeded: false,
    })
    const w = await mountPage()

    await vmOf(w).onCompensate(step())
    await flushPromises()

    expect(notifyMock.success).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('仍需人工介入'))
  })

  it('补偿成功后重新拉取列表——状态已变，不能让用户对着过期数据操作', async () => {
    api.compensateSaga.mockResolvedValue({
      sagaId: 'saga-001', compensatedCount: 1, failedCount: 0,
      compensated: ['a'], failed: [], fullySucceeded: true,
    })
    const w = await mountPage()
    expect(api.fetchSagaAttention).toHaveBeenCalledTimes(1)

    await vmOf(w).onCompensate(step())
    await flushPromises()

    expect(api.fetchSagaAttention).toHaveBeenCalledTimes(2)
  })

  it('补偿失败后解锁，不把界面永久锁死', async () => {
    // finally 里漏了复位的话，一次网络失败会让整页按钮再也点不动，
    // 而这是处理脏数据的页面，锁死意味着脏数据没人能清
    api.compensateSaga.mockRejectedValue(new Error('network'))
    const w = await mountPage()

    await vmOf(w).onCompensate(step())
    await flushPromises()

    expect(vmOf(w).compensatingId).toBeNull()
    expect(notifyMock.error).toHaveBeenCalled()
  })
})

describe('串行化：一次只允许一个补偿在跑', () => {
  it('已有补偿在执行时，第二次点击既不弹框也不发请求', async () => {
    // ── 本文件最重要的一条 ──────────────────────────────────
    // 逆向补偿并发下发时，若两个 Saga 触及同一条业务数据，
    // 回滚顺序不可预测。
    //
    // 断言「弹窗都没弹」而不只是「请求没发」：弹了框再拒绝，
    // 用户会以为这次操作已被受理
    let resolveIt: (v: unknown) => void = () => {}
    api.compensateSaga.mockReturnValue(new Promise((r) => { resolveIt = r }))

    const w = await mountPage([
      step({ id: 1, sagaId: 'saga-A' }),
      step({ id: 2, sagaId: 'saga-B' }),
    ])
    const vm = vmOf(w)

    const p1 = vm.onCompensate(step({ sagaId: 'saga-A' }))
    await flushPromises()
    expect(vm.compensatingId).toBe('saga-A')

    confirmMock.mockClear()
    await vm.onCompensate(step({ sagaId: 'saga-B' }))

    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.compensateSaga).toHaveBeenCalledTimes(1)
    expect(api.compensateSaga).toHaveBeenCalledWith('saga-A')
    // 要明确告知用户为什么没反应
    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('已有补偿任务'))

    resolveIt({
      sagaId: 'saga-A', compensatedCount: 1, failedCount: 0,
      compensated: ['a'], failed: [], fullySucceeded: true,
    })
    await p1
  })

  it('补偿进行中所有按钮禁用', async () => {
    let resolveIt: (v: unknown) => void = () => {}
    api.compensateSaga.mockReturnValue(new Promise((r) => { resolveIt = r }))

    const w = await mountPage([
      step({ id: 1, sagaId: 'saga-A' }),
      step({ id: 2, sagaId: 'saga-B' }),
    ])

    const p1 = vmOf(w).onCompensate(step({ sagaId: 'saga-A' }))
    await flushPromises()

    const btns = w.findAll('.act-compensate')
    expect(btns.length).toBeGreaterThan(0)
    for (const b of btns) {
      expect(b.attributes('disabled')).toBeDefined()
    }

    resolveIt({
      sagaId: 'saga-A', compensatedCount: 1, failedCount: 0,
      compensated: ['a'], failed: [], fullySucceeded: true,
    })
    await p1
  })

  it('完成后恢复可操作', async () => {
    api.compensateSaga.mockResolvedValue({
      sagaId: 'saga-A', compensatedCount: 1, failedCount: 0,
      compensated: ['a'], failed: [], fullySucceeded: true,
    })
    const w = await mountPage()
    const vm = vmOf(w)

    await vm.onCompensate(step({ sagaId: 'saga-A' }))
    await flushPromises()
    expect(vm.compensatingId).toBeNull()

    await vm.onCompensate(step({ sagaId: 'saga-A' }))
    await flushPromises()
    expect(api.compensateSaga).toHaveBeenCalledTimes(2)
  })
})

describe('派生展示', () => {
  it('状态映射到中文标签，未知值不崩', async () => {
    const w = await mountPage()
    const f = vmOf(w).stateLabel

    expect(f('PARTIAL_SUCCESS')).toBe('部分成功')
    expect(f('COMPENSATION_FAILED')).toBe('补偿失败')
    expect(f('MANUAL_INTERVENTION_REQUIRED')).toBe('需人工介入')
    expect(f(null)).toBe('—')
    // 后端新增枚举时前端不该崩，原样透出即可
    expect(f('BRAND_NEW_STATE' as never)).toBe('BRAND_NEW_STATE')
  })

  it('三类需介入状态各有独立着色，不与正常态混同', async () => {
    const w = await mountPage()
    const f = vmOf(w).stateClass

    expect(f('PARTIAL_SUCCESS')).toBe('st-partial')
    expect(f('COMPENSATION_FAILED')).toBe('st-failed')
    expect(f('MANUAL_INTERVENTION_REQUIRED')).toBe('st-manual')
    expect(f('COMPENSATED')).toBe('st-done')
    expect(f('SUCCESS')).toBe('st-normal')
  })

  it('时间为空显示占位符而非 Invalid Date', async () => {
    const w = await mountPage()
    expect(vmOf(w).fmtTime(null)).toBe('—')
  })
})
