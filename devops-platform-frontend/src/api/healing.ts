/**
 * 自愈中心 API（S3-1：L4 受控自愈）
 *
 * 全部端点后端限 ADMIN 角色（HealingController @SaCheckRole("ADMIN")）。
 * 审计视角：台账列表/详情只读；触发与撤销是仅有的两个写动作。
 */

import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz } from '../utils/http'

/** 执行台账行（对齐后端 HealingExecution record，字段名即 JSON 键名） */
export interface HealingExecution {
  id: number
  actionKey: string
  environment: string
  target: string | null
  paramsJson: string | null
  alertId: number | null
  requestedBy: string | null
  /** AUTO_EXECUTE / REQUIRES_APPROVAL / DENIED / NO_EXECUTOR */
  gateDecision: string
  approvalId: number | null
  executorKey: string | null
  /** PENDING_APPROVAL / SUCCEEDED / FAILED / REJECTED / UNDONE / UNDO_FAILED */
  status: string
  dryRunPlan: string | null
  output: string | null
  error: string | null
  preSnapshotJson: string | null
  undoToken: string | null
  createdAt: string | null
  finishedAt: string | null
  /** 执行后验证（S3-3）：PASS / FAIL / UNKNOWN / SKIPPED；null=尚未验证 */
  verifyStatus: string | null
  /** 验证结论 JSON（含 before/after 指标组） */
  verifyResultJson: string | null
  verifiedAt: string | null
}

/** 编排终局视图（触发/撤销接口的返回体） */
interface HealingOutcome {
  executionId: number
  decision: string
  status: string
  message: string
  approvalId: number | null
  dryRunPlan: string | null
  result: unknown
}

/** 手工触发入参（requestedBy 由后端取登录态，前端不传） */
interface HealingTrigger {
  actionKey: string
  environment: string
  target?: string
  params?: Record<string, unknown>
  alertId?: number
}

export async function listHealingExecutions(limit = 50): Promise<HealingExecution[]> {
  const payload = await http.get(`${API_ENDPOINTS.HEALING}/executions?limit=${limit}`)
  return unwrapBiz<HealingExecution[]>(payload, '查询执行台账失败')
}

/** 步骤时间节点（S3-5：V10 steps_json 的解析形态，后端已代为 parse） */
export interface HealingStep {
  name: string
  status: string
  detail: string
  at: string
}

/** 台账详情（S3-5 起为双载荷：行本体 + 步骤时间线） */
/** @public knip 假阳存证：cross=4 处真实引用（5.88.1 对 re-export/barrel 的解析盲区，报告 128 §三）——版本收敛后删行复查 */
export interface HealingExecutionDetail {
  execution: HealingExecution
  steps: HealingStep[]
}

export async function getHealingExecution(id: number): Promise<HealingExecutionDetail> {
  const payload = await http.get(`${API_ENDPOINTS.HEALING}/executions/${id}`)
  return unwrapBiz<HealingExecutionDetail>(payload, '查询台账详情失败')
}

/** 手工触发自愈动作：走治理门——直执行 / 建审批单 / 拒绝，结果立即返回 */
export async function triggerHealing(req: HealingTrigger): Promise<HealingOutcome> {
  const payload = await http.post(`${API_ENDPOINTS.HEALING}/executions`, req)
  return unwrapBiz<HealingOutcome>(payload, '触发自愈失败')
}

/** 撤销一次已成功且有撤销凭据的执行 */
export async function undoHealing(id: number): Promise<HealingOutcome> {
  const payload = await http.post(`${API_ENDPOINTS.HEALING}/executions/${id}/undo`)
  return unwrapBiz<HealingOutcome>(payload, '撤销失败')
}

/** 已注册执行器清单（当前系统有几只「手」） */
export async function listHealingExecutors(): Promise<string[]> {
  const payload = await http.get(`${API_ENDPOINTS.HEALING}/executors`)
  return unwrapBiz<string[]>(payload, '查询执行器清单失败')
}
