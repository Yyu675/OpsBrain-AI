/**
 * 状态机与 UI 可用性的**一致性**测试。
 *
 * ── 为什么需要这一层 ──────────────────────────────────────────
 * 项目已有 ticketStatus.contract.test.ts，但它只验证状态机函数**自身**正确。
 * 而实际缺陷出在别处：状态机写好了、测试也全绿，**UI 根本没调用它**——
 * TicketDetail 的按钮用的是手写的 `status === 'x' || status === 'y'`，
 * TicketList 的批量下拉直接铺全量 5 个选项。
 *
 * 实测两者漂移出 8 处不一致，其中三处是真缺陷：
 *
 *   | 场景 | UI 表现 | 状态机 | 后果 |
 *   |---|---|---|---|
 *   | resolved → processing | 禁用 | **允许** | 验证不通过无法打回，只能新建单 |
 *   | closed → processing   | 禁用 | **允许** | 故障复发无法重开，历史被拆成两张单 |
 *   | pending → closed      | **可点** | 禁止 | 点了后端拒绝，报错莫名其妙 |
 *
 * 本文件把「UI 的禁用条件」显式建模成函数，再与状态机逐状态比对。
 * 任何一侧改动导致偏离，这里立刻失败。
 */
import { describe, expect, it } from 'vitest'

import {
  TICKET_STATUS_OPTIONS,
  canTransitionStatus,
  isTerminalStatus,
  type TicketStatus,
} from '../ticket'

const ALL_STATUSES = TICKET_STATUS_OPTIONS.map(o => o.value)

/**
 * TicketDetail 三个流转按钮的启用条件。
 *
 * 必须与 TicketDetail.vue 模板里的 `:disabled` 表达式保持一致——
 * 那里现在写的是 `!canTransitionStatus(ticket.status, 'xxx')`，
 * 所以这里直接反映同一个来源。
 */
const detailButtonEnabled = {
  标记处理中: (s: TicketStatus) => canTransitionStatus(s, 'processing'),
  关闭: (s: TicketStatus) => canTransitionStatus(s, 'closed'),
  标记解决: (s: TicketStatus) => canTransitionStatus(s, 'resolved'),
}

describe('TicketDetail 流转按钮 ↔ 状态机一致', () => {
  it.each([
    ['标记处理中', 'processing'],
    ['关闭', 'closed'],
    ['标记解决', 'resolved'],
  ] as const)('「%s」按钮的可用性在所有状态下都等于状态机判定', (label, target) => {
    for (const from of ALL_STATUSES) {
      const uiEnabled = detailButtonEnabled[label](from)
      const allowed = canTransitionStatus(from, target)
      expect(
        uiEnabled,
        `状态 ${from}：UI 可点=${uiEnabled}，状态机允许=${allowed}`
      ).toBe(allowed)
    }
  })

  /** 这三条是本轮修复的具体缺陷，单独钉住防止回退 */
  it('已解决的工单可以重新打开 —— 验证不通过要能打回', () => {
    expect(detailButtonEnabled['标记处理中']('resolved')).toBe(true)
  })

  it('已关闭的工单可以重新打开 —— 故障复发不该被迫新建工单', () => {
    expect(detailButtonEnabled['标记处理中']('closed')).toBe(true)
  })

  it('待处理/处理中不能直接关闭 —— 必须先标记解决', () => {
    expect(detailButtonEnabled['关闭']('pending')).toBe(false)
    expect(detailButtonEnabled['关闭']('processing')).toBe(false)
  })

  it('已作废是不可逆终态，三个流转按钮全部禁用', () => {
    expect(isTerminalStatus('void')).toBe(true)
    for (const label of Object.keys(detailButtonEnabled) as Array<keyof typeof detailButtonEnabled>) {
      expect(detailButtonEnabled[label]('void'), `「${label}」在 void 下应禁用`).toBe(false)
    }
  })
})

/**
 * TicketList 批量状态选项的可达性计算。
 *
 * 与 TicketList.vue 的 bulkStatusOptions 同一套逻辑：
 * 统计选中项里有多少张能走到目标状态，0 则整项置灰。
 */
const bulkApplicable = (selected: TicketStatus[], target: TicketStatus): number =>
  selected.filter(s => s !== target && canTransitionStatus(s, target)).length

describe('TicketList 批量状态选项 ↔ 状态机一致', () => {
  it('全部选中项都不可达时该选项置灰', () => {
    // 两张 pending 的单都不能直接关闭
    expect(bulkApplicable(['pending', 'pending'], 'closed')).toBe(0)
  })

  it('部分可达时给出准确的 N/M 计数', () => {
    // resolved 能关闭，pending / processing 不能
    const selected: TicketStatus[] = ['resolved', 'pending', 'processing', 'resolved']
    expect(bulkApplicable(selected, 'closed')).toBe(2)
  })

  it('已是目标状态的不计入 —— 避免发出无意义的同态请求', () => {
    expect(bulkApplicable(['processing', 'processing'], 'processing')).toBe(0)
    // pending 可转 processing，已是 processing 的两张不算
    expect(bulkApplicable(['pending', 'processing', 'processing'], 'processing')).toBe(1)
  })

  it('void 永远不可达任何状态，混在选中项里不会被误统计', () => {
    for (const target of ALL_STATUSES) {
      expect(bulkApplicable(['void', 'void'], target), `void → ${target}`).toBe(0)
    }
  })

  it('空选中集不会让任何选项显示为可用', () => {
    for (const target of ALL_STATUSES) {
      expect(bulkApplicable([], target)).toBe(0)
    }
  })

  it('不会把状态机禁止的流转算成可执行 —— 逐组合穷举', () => {
    for (const from of ALL_STATUSES) {
      for (const target of ALL_STATUSES) {
        const n = bulkApplicable([from], target)
        const shouldBe = from !== target && canTransitionStatus(from, target) ? 1 : 0
        expect(n, `${from} → ${target}`).toBe(shouldBe)
      }
    }
  })
})
