/**
 * useTicketClosure —— 抽出后的直接单测。
 *
 * ── 与 TicketDetail.derive.test.ts 的关系 ─────────────────────
 * 那边通过挂载整个组件间接测这些派生值，作用是**证明抽取没有改变行为**
 * （拆分前后同一批断言都必须过）。
 *
 * 本文件是抽出后的直接测试：不挂组件、不需要 10 个 mock，
 * 跑一次不到 10ms。同样的边界在这里补得更密，
 * 将来改闭环口径时先看这里的失败，定位比组件测试快得多。
 *
 * 两者不是重复——组件测试守「集成没断」，这里守「逻辑本身对」。
 */
import { describe, expect, it } from 'vitest'
import { ref } from 'vue'

import { initialOf, useTicketClosure } from '../useTicketClosure'
import type { Ticket } from '@/stores/tickets'

const ticketOf = (over: Record<string, unknown> = {}): Ticket => ({
  id: 'TKT-20260825-0001',
  title: 't',
  status: 'processing',
  priority: 'high',
  category: '故障',
  service: 'order-service',
  sla: '4h',
  slaProgress: 10,
  slaBreached: false,
  firstResponseState: 'PENDING',
  firstResponseMinutes: null,
  mitigatedAt: null,
  rootCauseAt: null,
  verifiedAt: null,
  verifySkipped: false,
  verifySkipReason: null,
  ...over,
} as unknown as Ticket)

const setup = (t: Ticket | undefined) => useTicketClosure(ref(t))
const stage = (stages: Array<{ key: string; state: string; meta?: string }>, key: string) =>
  stages.find((s) => s.key === key)

describe('useTicketClosure — 闭环阶段', () => {
  it('工单为空时返回空数组', () => {
    expect(setup(undefined).closureStages.value).toEqual([])
  })

  it('恒为 6 个阶段且顺序固定', () => {
    const { closureStages } = setup(ticketOf())
    expect(closureStages.value.map((s) => s.key)).toEqual([
      'created', 'responded', 'mitigated', 'fixed', 'verified', 'archived',
    ])
  })

  it('首响三态：RESPONDED→done / BREACHED→skipped / 其余→current', () => {
    expect(stage(setup(ticketOf({ firstResponseState: 'RESPONDED' })).closureStages.value, 'responded')?.state)
      .toBe('done')
    // 超时是既成事实，标 pending 会让人以为还能补救
    expect(stage(setup(ticketOf({ firstResponseState: 'BREACHED' })).closureStages.value, 'responded')?.state)
      .toBe('skipped')
    expect(stage(setup(ticketOf({ firstResponseState: 'AT_RISK' })).closureStages.value, 'responded')?.state)
      .toBe('current')
    expect(stage(setup(ticketOf({ firstResponseState: 'PENDING' })).closureStages.value, 'responded')?.state)
      .toBe('current')
  })

  it('首响耗时进 meta，未响应时不带 meta', () => {
    expect(stage(setup(ticketOf({ firstResponseMinutes: 12 })).closureStages.value, 'responded')?.meta)
      .toBe('12 分钟')
    expect(stage(setup(ticketOf({ firstResponseMinutes: null })).closureStages.value, 'responded')?.meta)
      .toBeUndefined()
  })

  it('首响 0 分钟也要显示——它是有效读数，不能被当成空值', () => {
    // 若用 `if (minutes)` 判断，0 会被跳过，「秒级响应」反而不显示
    expect(stage(setup(ticketOf({ firstResponseMinutes: 0 })).closureStages.value, 'responded')?.meta)
      .toBe('0 分钟')
  })

  it('止损与修复按时间戳判定，不按工单状态', () => {
    // 状态是粗粒度的（processing 涵盖排查/止损/修复全过程），
    // 只有时刻字段能说清「这一步做完了没」
    const s = setup(ticketOf({
      status: 'processing', mitigatedAt: '2026-08-25 11:00:00', rootCauseAt: '2026-08-25 11:30:00',
    })).closureStages.value
    expect(stage(s, 'mitigated')?.state).toBe('done')
    expect(stage(s, 'fixed')?.state).toBe('done')
  })

  it('验证完成 → done；跳过 → skipped 且带理由', () => {
    expect(stage(setup(ticketOf({ verifiedAt: 'x' })).closureStages.value, 'verified')?.state)
      .toBe('done')

    const skipped = stage(setup(ticketOf({
      verifiedAt: 'x', verifySkipped: true, verifySkipReason: '无监控覆盖',
    })).closureStages.value, 'verified')
    expect(skipped?.state).toBe('skipped')
    expect(skipped?.meta).toBe('无监控覆盖')
  })

  it('跳过但没填理由时给兜底文案，不显示空白', () => {
    const s = stage(setup(ticketOf({
      verifiedAt: 'x', verifySkipped: true, verifySkipReason: '',
    })).closureStages.value, 'verified')
    expect(s?.meta).toBe('已跳过')
  })

  it('已解决/已关闭但未验证时标 skipped，避免进度条永远停在验证', () => {
    for (const status of ['resolved', 'closed']) {
      expect(stage(setup(ticketOf({ status, verifiedAt: null })).closureStages.value, 'verified')?.state)
        .toBe('skipped')
    }
  })

  it('归档只在 closed 时 done——resolved 还没归档', () => {
    expect(stage(setup(ticketOf({ status: 'closed' })).closureStages.value, 'archived')?.state)
      .toBe('done')
    expect(stage(setup(ticketOf({ status: 'resolved' })).closureStages.value, 'archived')?.state)
      .not.toBe('done')
  })

  it('至多一个 current——多个会让用户不知道现在该做哪一步', () => {
    const cases = [
      ticketOf(),
      ticketOf({ firstResponseState: 'RESPONDED' }),
      ticketOf({ firstResponseState: 'RESPONDED', mitigatedAt: 'x' }),
      ticketOf({ firstResponseState: 'RESPONDED', mitigatedAt: 'x', rootCauseAt: 'y' }),
      ticketOf({ firstResponseState: 'BREACHED' }),
      ticketOf({ status: 'closed', firstResponseState: 'RESPONDED', verifiedAt: 'x' }),
    ]
    for (const t of cases) {
      const currents = setup(t).closureStages.value.filter((s) => s.state === 'current')
      expect(currents.length).toBeLessThanOrEqual(1)
    }
  })

  it('全部完成时没有 current', () => {
    const s = setup(ticketOf({
      status: 'closed', firstResponseState: 'RESPONDED',
      mitigatedAt: 'a', rootCauseAt: 'b', verifiedAt: 'c',
    })).closureStages.value
    expect(s.every((x) => x.state === 'done')).toBe(true)
  })

  it('首响已 skipped 时，current 落到下一个 pending 而非消失', () => {
    // BREACHED 让首响是 skipped（非 current），此时止损应被提升为 current，
    // 否则进度条上没有任何一步被标为「当前」
    const s = setup(ticketOf({ firstResponseState: 'BREACHED' })).closureStages.value
    expect(stage(s, 'mitigated')?.state).toBe('current')
  })
})

describe('useTicketClosure — SLA 展示', () => {
  it('终态一律不提醒——用户对已关闭的单做不了补救', () => {
    for (const status of ['resolved', 'closed', 'void']) {
      expect(setup(ticketOf({ status, slaBreached: true, slaProgress: 100 })).showSlaAlert.value)
        .toBe(false)
    }
  })

  it('进行中且超时或进度 ≥70% 时提醒', () => {
    expect(setup(ticketOf({ slaBreached: true, slaProgress: 5 })).showSlaAlert.value).toBe(true)
    expect(setup(ticketOf({ slaProgress: 70 })).showSlaAlert.value).toBe(true)
    expect(setup(ticketOf({ slaProgress: 69.9 })).showSlaAlert.value).toBe(false)
  })

  it('配色：已超时优先于进度', () => {
    // 临时改优先级会让时限缩短，出现「进度低但已违约」，此时必须标红
    expect(setup(ticketOf({ slaBreached: true, slaProgress: 5 })).slaBarClass.value)
      .toBe('progress-fill-error')
    expect(setup(ticketOf({ slaProgress: 85 })).slaBarClass.value)
      .toBe('progress-fill-warning')
    expect(setup(ticketOf({ slaProgress: 10 })).slaBarClass.value)
      .toBe('progress-fill-normal')
  })

  it('工单为空时不抛异常', () => {
    const { showSlaAlert, slaBarClass } = setup(undefined)
    expect(showSlaAlert.value).toBe(false)
    expect(slaBarClass.value).toBe('')
  })
})

describe('useTicketClosure — 属性栏', () => {
  it('工单为空时返回空数组', () => {
    expect(setup(undefined).properties.value).toEqual([])
  })

  it('包含六项且工单编号用等宽字体', () => {
    const props = setup(ticketOf()).properties.value
    expect(props).toHaveLength(6)
    expect(props[0]).toMatchObject({ label: '工单编号', mono: true })
  })

  it('优先级带上 type，供 UI 着色', () => {
    const props = setup(ticketOf({ priority: 'urgent' })).properties.value
    expect(props.find((p) => p.label === '优先级')?.type).toBe('priority-urgent')
  })
})

describe('initialOf — 头像首字母', () => {
  it('null / undefined / 空串 / 纯空格都给 ?', () => {
    // 曾因 reply.author.charAt(0) 让整条时间线渲染崩溃
    expect(initialOf(null)).toBe('?')
    expect(initialOf(undefined)).toBe('?')
    expect(initialOf('')).toBe('?')
    expect(initialOf('   ')).toBe('?')
  })

  it('正常姓名取首字并去首尾空格', () => {
    expect(initialOf('张明')).toBe('张')
    expect(initialOf('  李强 ')).toBe('李')
    expect(initialOf('Alice')).toBe('A')
  })
})

/**
 * 响应式验证：composable 接的是 Ref，工单换了派生值要跟着变。
 *
 * 若哪天有人把参数改成传值（`ticket.value`），下面这条会失败——
 * 那种写法会让派生结果冻在第一张工单上，而 `/tickets/:id` 是同一路由，
 * 切换工单时组件实例会被复用，症状是「点了相似工单，进度条还是上一张的」。
 */
describe('useTicketClosure — 响应式', () => {
  it('工单引用变化时派生值同步更新', () => {
    const t = ref<Ticket | undefined>(ticketOf({ status: 'processing' }))
    const { closureStages, slaBarClass } = useTicketClosure(t)

    expect(stage(closureStages.value, 'archived')?.state).toBe('pending')
    expect(slaBarClass.value).toBe('progress-fill-normal')

    t.value = ticketOf({ status: 'closed', slaBreached: true, firstResponseState: 'RESPONDED' })

    expect(stage(closureStages.value, 'archived')?.state).toBe('done')
    expect(slaBarClass.value).toBe('progress-fill-error')
  })

  it('工单变为 undefined 时安全降级', () => {
    const t = ref<Ticket | undefined>(ticketOf())
    const { closureStages } = useTicketClosure(t)
    expect(closureStages.value.length).toBe(6)

    t.value = undefined
    expect(closureStages.value).toEqual([])
  })
})
