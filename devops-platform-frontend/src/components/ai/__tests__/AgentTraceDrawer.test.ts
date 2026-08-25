/**
 * Agent 执行轨迹抽屉测试。
 *
 * ── 这个组件最该守的一段：两种「空」不能长成一个样 ──────────────
 * `found=false`（会话已被清理 / traceId 有误）与 `found=true` 但零迁移
 * （流程真的卡在最开始）都会拿到空的 transitions 列表，含义却完全相反：
 * 前者该去「AI 调用日志」查落库记录，后者是**真实故障信号**。
 *
 * 若组件只判 `transitions.length` 就渲染统一的「暂无数据」，
 * 运维会把一次真实的卡死当成「记录过期了」放过去。
 * 所以这里逐个钉住两套文案。
 *
 * ── 另外两条 ────────────────────────────────────────────────
 * - 接口报错必须显示出来。吞成空轨迹会让人以为「流程没跑」，
 *   而实际是「没查到」——排查方向完全相反；
 * - 「最慢一段」只在两段以上时提示。单段轨迹里最慢的就是它自己，
 *   标出来没有信息量，反而像在报警。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({ fetchAgentTrace: vi.fn() }))
vi.mock('@/api/agentTrace', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/agentTrace')
  // 只替换请求函数，保留 formatDuration / agentStateColor 等纯函数的真实实现——
  // 那些正是展示逻辑的一部分，mock 掉就测不到「0 ms 不显示成 -」这类行为
  return { ...actual, fetchAgentTrace: api.fetchAgentTrace }
})

import AgentTraceDrawer from '../AgentTraceDrawer.vue'
import type { AgentTraceDetail, AgentTransitionItem } from '@/api/agentTrace'

/** el-drawer 未全局注册，stub 成透传容器以便断言内部内容 */
const ElDrawerStub = {
  name: 'ElDrawer',
  props: ['modelValue', 'title', 'size'],
  template: '<div class="el-drawer-stub"><slot /></div>',
}

const transition = (over: Partial<AgentTransitionItem> = {}): AgentTransitionItem => ({
  id: 't1',
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

const detail = (over: Partial<AgentTraceDetail> = {}): AgentTraceDetail => ({
  traceId: 'tr-1',
  found: true,
  currentState: 'SUCCESS',
  currentStateText: '成功',
  settled: true,
  terminal: true,
  transitions: [],
  transitionCount: 0,
  totalDurationMs: 0,
  ...over,
})

async function open(traceId: string | null = 'tr-1') {
  const wrapper = mount(AgentTraceDrawer, {
    props: { visible: true, traceId },
    global: { stubs: { ElDrawer: ElDrawerStub } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  api.fetchAgentTrace.mockReset()
})

describe('两种「空」必须区分', () => {
  it('会话不存在：提示可能已被清理，并指路 AI 调用日志', async () => {
    api.fetchAgentTrace.mockResolvedValue(
      detail({
        found: false,
        currentState: null,
        currentStateText: null,
        message: '会话不存在：可能已超过 30 分钟空闲期被清理，或 traceId 有误',
      })
    )

    const w = await open()
    const text = w.text()

    expect(text).toContain('查不到这条会话')
    expect(text).toContain('30 分钟')
    // 必须给出下一步去哪查，否则运维在这里就断了
    expect(text).toContain('AI 调用日志')
  })

  it('会话在但零迁移：明说「流程没有推进」，且带上卡住的状态', async () => {
    api.fetchAgentTrace.mockResolvedValue(
      detail({
        found: true,
        currentState: 'NEW',
        currentStateText: '新建',
        transitions: [],
        transitionCount: 0,
      })
    )

    const w = await open()
    const text = w.text()

    // 这是真实故障信号，措辞必须与「查不到」不同
    expect(text).toContain('流程没有推进')
    expect(text).toContain('新建')
    expect(text).not.toContain('查不到这条会话')
  })

  it('两种空状态的文案确实不同——防止将来被合并成一句', async () => {
    api.fetchAgentTrace.mockResolvedValue(detail({ found: false, message: 'x' }))
    const missing = (await open()).text()

    api.fetchAgentTrace.mockResolvedValue(
      detail({ found: true, currentState: 'NEW', currentStateText: '新建' })
    )
    const stuck = (await open()).text()

    expect(missing).not.toBe(stuck)
  })
})

describe('正常轨迹渲染', () => {
  it('概览显示当前状态、环节数与总耗时', async () => {
    api.fetchAgentTrace.mockResolvedValue(
      detail({
        transitions: [transition(), transition({ id: 't2', durationMs: 120 })],
        transitionCount: 2,
        totalDurationMs: 120,
      })
    )

    const text = (await open()).text()

    expect(text).toContain('成功')
    expect(text).toContain('2')
    expect(text).toContain('120 ms')
  })

  it('每个节点渲染目标状态中文名与触发详情', async () => {
    api.fetchAgentTrace.mockResolvedValue(
      detail({
        transitions: [
          transition({
            toStateText: '工具执行中',
            triggerDetail: '工具调用：createDevOpsTicket',
          }),
        ],
        transitionCount: 1,
      })
    )

    const text = (await open()).text()

    expect(text).toContain('工具执行中')
    expect(text).toContain('createDevOpsTicket')
  })

  it('首段耗时 0 显示「0 ms」而非「-」——0 是有效读数', async () => {
    api.fetchAgentTrace.mockResolvedValue(
      detail({ transitions: [transition({ durationMs: 0 })], transitionCount: 1 })
    )

    const text = (await open()).text()

    // 「瞬间完成」和「没采到数据」是两回事
    expect(text).toContain('0 ms')
  })

  it('失败态节点用 danger 色，成功态用 success 色', async () => {
    api.fetchAgentTrace.mockResolvedValue(
      detail({
        currentState: 'FAILED',
        currentStateText: '失败',
        transitions: [
          transition({ toState: 'SUCCESS' }),
          transition({ id: 't2', toState: 'MANUAL_ESCALATED' }),
        ],
        transitionCount: 2,
      })
    )

    const w = await open()

    expect(w.find('.trace-dot.is-success').exists()).toBe(true)
    // 补偿失败需人工清理，与 FAILED 同级标红
    expect(w.find('.trace-dot.is-danger').exists()).toBe(true)
  })
})

describe('最慢一段提示', () => {
  it('两段以上时标出最慢的那段', async () => {
    api.fetchAgentTrace.mockResolvedValue(
      detail({
        transitions: [
          transition({ id: 'a', durationMs: 10 }),
          transition({ id: 'b', toStateText: '证据就绪', durationMs: 1500 }),
        ],
        transitionCount: 2,
        totalDurationMs: 1510,
      })
    )

    const w = await open()

    expect(w.text()).toContain('最慢一段')
    expect(w.text()).toContain('证据就绪')
    expect(w.find('.trace-node.is-slowest').exists()).toBe(true)
  })

  it('只有一段时不提示——最慢的就是它自己，没有信息量', async () => {
    api.fetchAgentTrace.mockResolvedValue(
      detail({ transitions: [transition({ durationMs: 9999 })], transitionCount: 1 })
    )

    const w = await open()

    expect(w.text()).not.toContain('最慢一段')
    expect(w.find('.trace-node.is-slowest').exists()).toBe(false)
  })
})

describe('加载与错误', () => {
  it('接口失败时显示错误并提供重试，不伪装成空轨迹', async () => {
    api.fetchAgentTrace.mockRejectedValue(new Error('网络异常'))

    const w = await open()

    // 吞成「暂无数据」会让人以为流程没跑，而实际是没查到
    expect(w.text()).toContain('网络异常')
    expect(w.find('.trace-hint-error').exists()).toBe(true)
    expect(w.text()).toContain('重试')
  })

  it('点重试会重新发起请求', async () => {
    api.fetchAgentTrace.mockRejectedValueOnce(new Error('网络异常'))
    const w = await open()
    expect(api.fetchAgentTrace).toHaveBeenCalledTimes(1)

    api.fetchAgentTrace.mockResolvedValue(
      detail({ transitions: [transition()], transitionCount: 1 })
    )
    await w.find('.link-btn').trigger('click')
    await flushPromises()

    expect(api.fetchAgentTrace).toHaveBeenCalledTimes(2)
    expect(w.text()).toContain('安全检查通过')
  })

  it('traceId 为空时不发请求——避免打出一个必然 404 的调用', async () => {
    await open(null)

    expect(api.fetchAgentTrace).not.toHaveBeenCalled()
  })

  it('抽屉关闭状态下不加载', async () => {
    mount(AgentTraceDrawer, {
      props: { visible: false, traceId: 'tr-1' },
      global: { stubs: { ElDrawer: ElDrawerStub } },
    })
    await flushPromises()

    expect(api.fetchAgentTrace).not.toHaveBeenCalled()
  })

  it('traceId 变化时重新拉取——同一抽屉会被复用于不同会话', async () => {
    api.fetchAgentTrace.mockResolvedValue(detail())
    const w = await open('tr-1')
    expect(api.fetchAgentTrace).toHaveBeenCalledTimes(1)

    await w.setProps({ traceId: 'tr-2' })
    await flushPromises()

    expect(api.fetchAgentTrace).toHaveBeenCalledTimes(2)
    expect(api.fetchAgentTrace).toHaveBeenLastCalledWith('tr-2')
  })
})
