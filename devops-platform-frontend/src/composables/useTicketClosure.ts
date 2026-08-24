import { computed, type ComputedRef, type Ref } from 'vue'

import { getPriorityLabel, getStatusLabel, type Ticket } from '@/stores/tickets'

/**
 * 工单闭环进度、SLA 展示与属性栏的派生计算。
 *
 * ── 为什么把这一块单独抽出来 ──────────────────────────────────
 * 它们是 `TicketDetail.vue` 里**唯一完全无副作用**的部分：只读工单、
 * 只算展示值，不碰接口、不改状态。这类逻辑最适合独立成 composable——
 * 既能单测，也不会因为搬出去而改变任何行为。
 *
 * 更重要的是：这些计算错了**不会抛异常**，只会让进度条停在错误的阶段、
 * SLA 条显示错误的颜色。而运维正是照着进度条判断「这单还差什么」，
 * 静默的错误比崩溃更危险。所以它们值得有自己的测试文件与明确边界。
 *
 * ── 与 `useTicketActions` 的分工 ──────────────────────────────
 * 本文件只「读」，那边只「写」。分界线是有没有副作用，
 * 而不是按 UI 区块划分——按区块划分会让同一个动作的校验、请求、
 * 状态同步散落在三个文件里。
 */

/** 闭环阶段的状态。skipped 与 pending 的区别是「明确不做」与「还没做」 */
export type ClosureState = 'done' | 'current' | 'skipped' | 'pending'

export interface ClosureStage {
  key: string
  label: string
  state: ClosureState
  /** 附加说明，如首响耗时、跳过理由 */
  meta?: string
}

export interface TicketProperty {
  label: string
  value: string | undefined
  mono?: boolean
  type?: string
}

export interface UseTicketClosureReturn {
  closureStages: ComputedRef<ClosureStage[]>
  properties: ComputedRef<TicketProperty[]>
  showSlaAlert: ComputedRef<boolean>
  slaBarClass: ComputedRef<string>
}

/**
 * @param ticket 当前工单。用 Ref 而非值——工单来自 store，
 *               切换路由参数时引用会变，传值会让派生结果冻在第一张单上
 */
export function useTicketClosure(
  ticket: Ref<Ticket | undefined>
): UseTicketClosureReturn {

  /**
   * 闭环进度条：建单 → 首响 → 止损 → 修复 → 验证 → 归档。
   *
   * 几个刻意的判定口径：
   *
   * - **止损/修复按时间戳判定，不按状态**。工单状态是粗粒度的
   *   （processing 涵盖了排查、止损、修复全过程），只有 `mitigatedAt`
   *   / `rootCauseAt` 这样的时刻字段才能说清「这一步做完了没」。
   *
   * - **首响超时标 skipped 而不是 pending**。超时是既成事实，
   *   显示成「未完成」会让人以为还能补救；标 skipped（灰）并保留耗时，
   *   如实呈现「这一步走过了，但没达标」。
   *
   * - **已解决但没验证时，验证标 skipped**。否则进度条会永远停在「验证」，
   *   让人以为还有事没做——而工单其实已经关了。
   */
  const closureStages = computed<ClosureStage[]>(() => {
    const t = ticket.value
    if (!t) return []

    const stages: ClosureStage[] = [
      { key: 'created', label: '建单', state: 'done' },
      {
        key: 'responded',
        label: '首响',
        state: t.firstResponseState === 'RESPONDED' ? 'done'
          : t.firstResponseState === 'BREACHED' ? 'skipped'
            : 'current',
        meta: t.firstResponseMinutes != null ? `${t.firstResponseMinutes} 分钟` : undefined,
      },
      {
        key: 'mitigated',
        label: '止损',
        state: t.mitigatedAt ? 'done' : 'pending',
      },
      {
        key: 'fixed',
        label: '修复',
        // 有根因确认说明修复已完成（根因分析紧接修复之后）
        state: t.rootCauseAt ? 'done' : 'pending',
      },
      {
        key: 'verified',
        label: '验证',
        state: t.verifiedAt
          ? (t.verifySkipped ? 'skipped' : 'done')
          : (t.status === 'resolved' || t.status === 'closed' ? 'skipped' : 'pending'),
        meta: t.verifySkipped ? (t.verifySkipReason || '已跳过') : undefined,
      },
      {
        key: 'archived',
        label: '归档',
        state: t.status === 'closed' ? 'done' : 'pending',
      },
    ]

    // 把第一个 pending 提升为 current——至多一个 current，
    // 多个会让用户不知道现在该做哪一步
    const firstPending = stages.findIndex(s => s.state === 'pending')
    if (firstPending >= 0 && !stages.some(s => s.state === 'current')) {
      stages[firstPending].state = 'current'
    }
    return stages
  })

  /** 右栏属性列表 */
  const properties = computed<TicketProperty[]>(() => {
    const t = ticket.value
    if (!t) return []
    return [
      { label: '工单编号', value: t.id, mono: true },
      { label: '优先级', value: getPriorityLabel(t.priority), type: `priority-${t.priority}` },
      { label: '状态', value: getStatusLabel(t.status), type: 'status' },
      { label: '分类', value: t.category },
      { label: '服务', value: t.service },
      { label: 'SLA', value: t.sla },
    ]
  })

  /**
   * 是否展示 SLA 提醒。
   *
   * 终态工单 SLA 计时已停，再提醒只是噪音——用户对一张已关闭的单
   * 做不了任何补救，红色横幅只会分散他对活跃工单的注意力。
   */
  const showSlaAlert = computed(() => {
    const t = ticket.value
    if (!t) return false
    if (t.status === 'resolved' || t.status === 'closed' || t.status === 'void') return false
    return t.slaBreached || t.slaProgress >= 70
  })

  /**
   * SLA 进度条配色。
   *
   * 已超时优先于进度判定：进度低但已违约（如临时改了优先级导致时限缩短）
   * 仍要标红，否则用户会以为还有余量。
   */
  const slaBarClass = computed(() => {
    const t = ticket.value
    if (!t) return ''
    if (t.slaBreached) return 'progress-fill-error'
    if (t.slaProgress >= 70) return 'progress-fill-warning'
    return 'progress-fill-normal'
  })

  return { closureStages, properties, showSlaAlert, slaBarClass }
}

/**
 * 头像首字母。
 *
 * 独立导出而非藏在 composable 里：它是纯函数，模板里直接用，
 * 也便于其它展示工单回复的地方复用。
 *
 * `author` 在后端 DTO 里是 `string | null`（系统生成的记录可能无作者）。
 * 曾经模板里直接写 `reply.author.charAt(0)`，一条 null author 会让
 * **整条时间线渲染崩溃**——Vue 的渲染错误不会被 try/catch 兜住，
 * 用户看到的是空白页而不是少一个头像。
 */
export function initialOf(name?: string | null): string {
  const trimmed = (name ?? '').trim()
  return trimmed ? trimmed.charAt(0) : '?'
}
