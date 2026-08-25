import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessageBox } from 'element-plus'

import { acknowledgeTicket, escalateTicket } from '@/api/tickets'
import {
  getPriorityLabel,
  getStatusLabel,
  UNASSIGNED,
  type Ticket,
  type TicketReply,
} from '@/stores/tickets'
import { useAsyncAction } from '@/composables/useAsyncAction'
import { handleServerError, notify } from '@/utils/notify'

/**
 * 工单状态流转类写操作。
 *
 * ── 抽出的核心目的：让「哪个动作有防重入」一眼可查 ────────────
 * 这些动作此前散在 `TicketDetail.vue` 的 200~470 行之间，中间还夹着
 * 派生计算与对话框状态。要确认「升级上报有没有防重入」得在 2500 行里翻。
 *
 * 集中后可以直接对照：**每个写活动流的动作都必须有进行中标记**。
 * 本项目为此付过代价——慢接口下双击「升级上报」会提交两次，
 * 活动流出现两条一模一样的记录，而工单时间线是事后复盘与追责的依据。
 *
 * ── 三种防重入写法及其适用场景 ────────────────────────────────
 * 1. `useAsyncAction` —— 首选。自带 pending + 错误提示 + 防重入，
 *    适合「点一下就走」的动作（提优先级、升级、开始处理）。
 * 2. 手写 `ref(false)` + try/finally —— 需要在成功/失败时做不同的
 *    局部状态处理时用（如 submitReply 要恢复草稿）。
 * 3. 无标记 —— 只允许用于**幂等且无副作用**的操作。本文件里没有。
 *
 * ── 为什么不把所有写操作都搬进来 ──────────────────────────────
 * B2/B3 的表单类动作（处置记录、根因确认、验证）与各自的对话框表单状态
 * 强耦合，搬过来要一并带走 6 个 ref，反而让本文件变成第二个大杂烩。
 * 分界线是「是否只依赖工单本身」——这里的动作都只需要 id 和当前用户。
 */

export interface UseTicketActionsOptions {
  /** 当前工单。用 Ref——切换路由时引用会变 */
  ticket: Ref<Ticket | undefined>
  /** 当前登录用户名 */
  getOperator: () => string
  /**
   * store 的写方法。以参数注入而非直接 import，便于单测替换。
   *
   * 类型用 store 的真实签名（TicketReply / Partial<Ticket>）而不是
   * `Record<string, unknown>`：后者看似"更宽松"，实际会让 store 的
   * 精确签名无法赋值进来（参数逆变），而且丢掉了字段拼写检查。
   */
  store: {
    getById: (id: string) => Ticket | undefined
    appendReply: (id: string, reply: TicketReply) => Promise<void>
    updateStatus: (id: string, status: Ticket['status']) => Promise<unknown>
    updateTicket: (id: string, patch: Partial<Ticket>) => Promise<unknown>
    transferTicket: (id: string, target: string) => Promise<unknown>
    loadActivities: (id: string) => Promise<unknown>
    loadTeamMembers: () => Promise<unknown>
    teamMembers: Array<{ name: string; activeTicketCount?: number }>
  }
}

/** 优先级由低到高。提升只走一级，跨级需要多点几次——刻意的摩擦 */
const PRIORITY_LADDER = ['low', 'medium', 'high', 'urgent'] as const
type Priority = (typeof PRIORITY_LADDER)[number]

export function useTicketActions(options: UseTicketActionsOptions) {
  const { ticket, getOperator, store } = options

  // ==================== 回复 ====================

  const replyContent = ref('')
  const submitting = ref(false)

  /**
   * 提交回复。
   *
   * 乐观清空输入框，**失败时必须把草稿放回去**——
   * 乐观清空 + 失败不恢复 = 用户白打一遍字。
   * 这是最容易被忽略的体验缺陷，因为正常路径下永远看不出来。
   */
  const submitReply = async () => {
    const cur = ticket.value
    if (!cur) return
    const text = replyContent.value.trim()
    if (!text) {
      notify.warning('请输入回复内容')
      return
    }
    if (submitting.value) return

    submitting.value = true
    const draft = text
    replyContent.value = ''

    try {
      await store.appendReply(cur.id, {
        role: 'agent' as const,
        author: getOperator() || cur.assignee,
        time: '',
        content: text,
      })
      notify.success('回复已发送')
    } catch {
      replyContent.value = draft
    } finally {
      submitting.value = false
    }
  }

  // ==================== 转派 ====================

  const transferDialogVisible = ref(false)
  const transferTarget = ref('')

  /** 负责人当前负载提示（如「张明（3 单在处理）」）。「待分配」不是人 */
  const workloadOf = (name: string): string => {
    if (name === UNASSIGNED) return ''
    const m = store.teamMembers.find(x => x.name === name)
    if (!m || !m.activeTicketCount) return ''
    return `（${m.activeTicketCount} 单在处理）`
  }

  const openTransferDialog = () => {
    const cur = ticket.value
    transferTarget.value = cur?.assignee && cur.assignee !== UNASSIGNED ? cur.assignee : ''
    transferDialogVisible.value = true
    // 名单来自后端 sys_team_member，按需加载（store 内已去重）
    void store.loadTeamMembers()
  }

  const doTransfer = async () => {
    const cur = ticket.value
    if (!cur || !transferTarget.value) return

    const t = store.getById(cur.id)
    // 转给当前负责人时短路：否则活动流会多一条「转派给张明」，
    // 而实际什么也没变
    if (!t || t.assignee === transferTarget.value) {
      transferDialogVisible.value = false
      return
    }

    try {
      await store.transferTicket(cur.id, transferTarget.value)
      notify.success(`已转派给 ${transferTarget.value}`)
      transferDialogVisible.value = false
    } catch {
      // store 已提示错误。**保持弹窗打开**——关掉会让用户以为成功了
    }
  }

  // ==================== 优先级 ====================

  const priorityAction = useAsyncAction(
    async (id: string, next: Priority) => {
      await store.updateTicket(id, { priority: next })
      notify.success(`已提升为「${getPriorityLabel(next)}」，SLA 时限已重算`)
    },
    { action: '提升优先级' }
  )

  /**
   * 提升优先级。
   *
   * 与「升级上报」是两件不同的事：
   * - 本动作改 priority，**会连带重算 SLA 时限**（deadline 由优先级派生）
   * - 升级上报只记录事实与原因，不动优先级
   *
   * 二者曾混在一个「升级」按钮里，用户点它其实是在改 SLA 计时基线却不知情。
   * 所以确认框必须写明这一点。
   */
  const raisePriority = async () => {
    const cur = ticket.value
    if (!cur) return

    const idx = PRIORITY_LADDER.indexOf(cur.priority as Priority)
    if (idx >= PRIORITY_LADDER.length - 1) {
      notify.warning('已经是最高优先级')
      return
    }
    const next = PRIORITY_LADDER[idx + 1]

    try {
      await ElMessageBox.confirm(
        `将优先级从「${getPriorityLabel(cur.priority)}」提升为「${getPriorityLabel(next)}」？\n`
        + '注意：SLA 首响与解决时限会按新优先级重算。',
        '提升优先级',
        { confirmButtonText: '确认提升', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return   // 用户取消
    }
    await priorityAction.run(cur.id, next)
  }

  // ==================== 首响 / 升级 ====================

  const acknowledging = ref(false)

  /**
   * 确认接单（显式首响）。
   *
   * 幂等：后端 SQL 带 `first_response_at IS NULL` 条件，重复点击不会把
   * 首响时刻推后。但前端仍要防重入——两次请求会写两条活动流。
   *
   * **用后端返回值校准派生字段**：首响状态、MTTA、版本号都由后端计算，
   * 前端自己推会与后端口径漂移。
   */
  const doAcknowledge = async () => {
    const cur = ticket.value
    if (!cur || acknowledging.value) return
    acknowledging.value = true
    try {
      const updated = await acknowledgeTicket(cur.id, getOperator() || '当前用户')
      const t = store.getById(cur.id)
      if (t) {
        t.firstResponseState = updated.firstResponseState
        t.firstResponseMinutes = updated.firstResponseMinutes
        t.firstResponder = updated.firstResponder
        t.status = updated.status
        t.version = updated.version
        t.updatedAt = updated.updatedAt
      }
      await store.loadActivities(cur.id)
      notify.success('已确认接单')
    } catch (e) {
      console.error('确认接单失败', e)
      handleServerError(e, { action: '确认接单' })
    } finally {
      // 忘写 finally 会让按钮永久禁用，比不加防护更糟
      acknowledging.value = false
    }
  }

  const escalateAction = useAsyncAction(
    async (id: string, reason: string) => {
      await escalateTicket(id, reason, getOperator() || '当前用户')
      await store.loadActivities(id)
    },
    { action: '升级上报', successMessage: '已提交升级，已记入活动流' }
  )

  /**
   * 升级上报。
   *
   * 只记录升级事实与原因，**不改优先级、不换负责人**——那属 L3 审批
   * 工作流范畴。原因必填，否则无法追溯也无法据此改进流程。
   */
  const doEscalate = async () => {
    const cur = ticket.value
    if (!cur) return
    let reason: string
    try {
      const r = await ElMessageBox.prompt(
        '请说明升级原因（例如：超出本级处理能力、需跨团队协同、影响面扩大）',
        '升级上报',
        {
          confirmButtonText: '提交升级',
          cancelButtonText: '取消',
          inputPlaceholder: '升级原因（必填）',
          inputValidator: (v: string) => (v && v.trim().length > 0) || '升级原因不能为空',
        }
      )
      reason = (r.value || '').trim()
    } catch {
      return   // 用户取消
    }
    await escalateAction.run(cur.id, reason)
  }

  // ==================== 状态流转 ====================

  const closeTicket = () => {
    const cur = ticket.value
    if (!cur) return
    ElMessageBox.confirm('确定关闭该工单？关闭后不能再回复。', '关闭工单', {
      confirmButtonText: '关闭',
      cancelButtonText: '取消',
      type: 'warning',
    })
      .then(async () => {
        try {
          await store.updateStatus(cur.id, 'closed')
          notify.success('工单已关闭')
        } catch {
          // store 已提示错误
        }
      })
      .catch(() => {})
  }

  /**
   * 「标记处理中」按钮的文案。
   *
   * 从 resolved / closed 转回 processing 在状态机里是合法的，语义是
   * **故障复发、重新打开**——运维最关键的场景之一（验证不通过、
   * 同一问题几天后又出现）。曾把这两个状态一并禁用，用户唯一的出路是
   * 新建工单，于是同一个故障的历史被拆成两张单，MTTR 与复盘的连续性都断了。
   *
   * 文案必须跟着变：对已解决的工单还显示「标记处理中」会让人以为是
   * 误操作入口，显示「重新打开」才说得清这次点击的后果。
   */
  const reopenLabel: ComputedRef<string> = computed(() => {
    const s = ticket.value?.status
    return s === 'resolved' || s === 'closed' ? '重新打开' : '标记处理中'
  })

  const processingAction = useAsyncAction(
    async (id: string) => {
      await store.updateStatus(id, 'processing')
    },
    { action: '开始处理', successMessage: '工单已标记为处理中' }
  )

  /**
   * 开始处理 / 重新打开。
   *
   * 只在**重开**路径上要二次确认：它会让已计完的 MTTR 重新开始走、
   * 把工单从已完成统计里拉回来，影响团队考核数据。
   * 而普通的「开始处理」是高频动作，加确认只会让人烦。
   * 两者共用同一个按钮位置，所以手滑的代价必须被拦住。
   */
  const startProcessing = async () => {
    const cur = ticket.value
    if (!cur) return

    if (cur.status === 'resolved' || cur.status === 'closed') {
      try {
        await ElMessageBox.confirm(
          `确定重新打开这张${getStatusLabel(cur.status)}的工单吗？\n`
          + '重开后 SLA 计时将继续，该工单会重新回到处理中队列。',
          '重新打开工单',
          { type: 'warning', confirmButtonText: '重新打开', cancelButtonText: '取消' }
        )
      } catch {
        return   // 用户取消
      }
    }

    await processingAction.run(cur.id)
  }

  return {
    // 回复
    replyContent, submitting, submitReply,
    // 转派
    transferDialogVisible, transferTarget, workloadOf, openTransferDialog, doTransfer,
    // 优先级
    priorityAction, raisePriority,
    // 首响 / 升级
    acknowledging, doAcknowledge, escalateAction, doEscalate,
    // 状态流转
    closeTicket, reopenLabel, processingAction, startProcessing,
  }
}
