/**
 * 自愈台账状态 / 门裁决单一来源（批88 残影清扫——机械扫描「完全相同内容
 * 重复字典」唯一命中：HealingCenter 与 HealingExecutionDetail 两页逐字
 * 双份，批26「同内容双实现必删」判例收口）。
 *
 * 消费：两页以别名导入（`HEALING_STATUS_META as STATUS_META`），调用点零改。
 * 键域 = HealingExecution.Status 六态 + HealingGate 裁决四值；
 * 裁决四键与生成物 healingOutcomeDecision enum 同集（键集对账入批88档候选）。
 */

type HealingStatusMeta = {
  label: string
  type: 'success' | 'warning' | 'danger' | 'info' | 'primary'
}

export const HEALING_STATUS_META: Record<string, HealingStatusMeta> = {
  SUCCEEDED: { label: '已执行', type: 'success' },
  FAILED: { label: '执行失败', type: 'danger' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  REJECTED: { label: '已拒绝', type: 'info' },
  UNDONE: { label: '已撤销', type: 'primary' },
  UNDO_FAILED: { label: '撤销失败', type: 'danger' },
}

type HealingDecisionMeta = {
  label: string
  type: 'success' | 'warning' | 'danger' | 'info'
}

export const HEALING_DECISION_META: Record<string, HealingDecisionMeta> = {
  AUTO_EXECUTE: { label: '免审直执行', type: 'success' },
  REQUIRES_APPROVAL: { label: '需审批', type: 'warning' },
  DENIED: { label: '门拒绝', type: 'danger' },
  NO_EXECUTOR: { label: '无执行器', type: 'info' },
}
