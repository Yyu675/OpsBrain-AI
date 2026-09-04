/**
 * Saga 补偿中心 API。
 *
 * 对应后端 `SagaController`（/api/v1/saga/**，限 ADMIN）。
 *
 * ── 这组接口解决什么 ──────────────────────────────────────────
 * 「自动化执行失败了怎么办」的最后一道人工兜底：
 *   - /attention：所有半残事务（PARTIAL_SUCCESS / COMPENSATION_FAILED /
 *     MANUAL_INTERVENTION_REQUIRED）——一张「系统哪里坏了、坏在哪条数据上」的清单
 *   - /steps：单个 Saga 的完整执行链路（供回放与故障定位）
 *   - /compensate：人工触发逆向补偿（回滚已落库的写操作；幂等，可重试）
 *
 * ── 权限 ─────────────────────────────────────────────────────
 * 后端 @SaCheckRole("ADMIN")：补偿是逆向回滚写操作，非管理员不可触发。
 */
import { API_BASE } from '../config/api'
import { http, unwrapBiz } from '../utils/http'

// ==================== 类型（对齐后端 toDetail / ToolExecutionRecord）====================

/** 与后端 ToolExecutionState 枚举一致 */
export type SagaStepState =
  | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'PARTIAL_SUCCESS'
  | 'COMPENSATING' | 'COMPENSATED' | 'COMPENSATION_FAILED'
  | 'MANUAL_INTERVENTION_REQUIRED' | 'SKIPPED'

/** 与后端 ToolFailureType 枚举一致 */
export type SagaFailureType =
  | 'PARAMETER_ERROR' | 'PERMISSION_DENIED' | 'TIMEOUT' | 'RATE_LIMITED'
  | 'SERVICE_UNAVAILABLE' | 'EMPTY_RESULT' | 'PARTIAL_SUCCESS'
  | 'COMPENSATION_FAILED' | 'UNKNOWN'

/** 与后端 ToolRiskLevel 枚举一致 */
export type SagaRiskLevel = 'READ_ONLY' | 'DRAFT' | 'CONTROLLED_WRITE' | 'HIGH_RISK_EXECUTION'

/** 一条工具执行记录（Saga 的一个步骤；后端 toDetail 逐字段对齐） */
export interface SagaStep {
  id: number
  traceId: string
  sessionId: string | null
  sagaId: string
  stepSeq: number
  toolName: string
  riskLevel: SagaRiskLevel | null
  riskLevelLabel: string | null
  state: SagaStepState | null
  stateLabel: string | null
  needsAttention: boolean
  failureType: SagaFailureType | null
  /** 故障处理建议（来自 failureType.getHandlingHint） */
  failureHint: string | null
  errorMessage: string | null
  compensable: boolean
  compensationAction: string | null
  /** 用户真正关心的是「哪条业务数据坏了」 */
  businessKey: string | null
  compensatedAt: string | null
  compensationError: string | null
  attemptCount: number
  durationMs: number | null
  createTime: string
  updateTime: string
}

/** /attention 响应：{ records, count } */
export interface SagaAttentionResponse {
  records: SagaStep[]
  count: number
}

/** /{sagaId}/steps 响应：{ sagaId, steps, stepCount } */
export interface SagaStepsResponse {
  sagaId: string
  steps: SagaStep[]
  stepCount: number
}

/** /{sagaId}/compensate 响应 */
export interface SagaCompensateResult {
  sagaId: string
  compensatedCount: number
  failedCount: number
  compensated: string[]
  failed: string[]
  fullySucceeded: boolean
}

// ==================== API ====================

/** 查询需人工介入的执行记录（三类：半残/补偿失败/需介入） */
export async function fetchSagaAttention(limit = 50): Promise<SagaAttentionResponse> {
  const raw = await http.get<unknown>(`${API_BASE}/saga/attention?limit=${limit}`)
  return unwrapBiz<SagaAttentionResponse>(raw, '查询 Saga 待介入记录失败')
}

/** 查询单个 Saga 完整执行链路（供回放与故障定位） */
export async function fetchSagaSteps(sagaId: string): Promise<SagaStepsResponse> {
  const raw = await http.get<unknown>(`${API_BASE}/saga/${encodeURIComponent(sagaId)}/steps`)
  return unwrapBiz<SagaStepsResponse>(raw, '查询 Saga 步骤失败')
}

/** 手动重试补偿（幂等；失败后修复了下游问题再重试） */
export async function compensateSaga(sagaId: string): Promise<SagaCompensateResult> {
  const raw = await http.post<unknown>(`${API_BASE}/saga/${encodeURIComponent(sagaId)}/compensate`)
  return unwrapBiz<SagaCompensateResult>(raw, '补偿触发失败')
}
