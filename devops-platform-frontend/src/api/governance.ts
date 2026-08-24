/**
 * L3 自动化治理配置 API。
 *
 * 对应后端 `AutomationGovernanceController`（/api/v1/governance/**，限 ADMIN）。
 *
 * ── 这组接口配置什么 ──────────────────────────────────────────
 * 「AI 能不能自动动生产系统」的边界：
 *   - 风险等级策略：每一级要不要审批、能不能自动执行、一次最多影响几个实例
 *   - 动作白名单：允许清单，未登记的动作一律不允许自动执行
 *
 * ── 关键契约：安全配置只能收紧，不能放宽 ──────────────────────
 * 后端会拒绝「把高危动作配成免审批」「条目环境超出策略允许范围」
 * 「爆炸半径超过策略上限」这三类提交（40001）。
 * 前端表单应当**提前禁用**这些选项而不是等提交后报错——
 * 但服务端校验是最终边界，前端禁用只是体验优化。
 */
import { API_BASE } from '../config/api'
import { http, unwrapBiz } from '../utils/http'

// ==================== 类型 ====================

/** 审批门槛。NONE 免审批 / SINGLE 单人 / DUAL 双人（四眼原则） */
export type ApprovalMode = 'NONE' | 'SINGLE' | 'DUAL'

/** 失败后的升级目标 */
export type EscalateTarget = 'NONE' | 'TICKET' | 'ONCALL'

/** 与后端 ToolRiskLevel 枚举一一对应 */
export type RiskLevel = 'READ_ONLY' | 'DRAFT' | 'CONTROLLED_WRITE' | 'HIGH_RISK_EXECUTION'

export interface RiskPolicy {
  /** 主键即风险等级枚举名。不可修改，也不支持新增等级 */
  riskLevel: RiskLevel
  displayName: string
  description: string | null

  approvalMode: ApprovalMode
  approvalTimeoutMinutes: number

  /** 与 approvalMode 正交：「审批通过了」不等于「可以由机器执行」 */
  autoExecuteAllowed: boolean
  maxBlastRadiusPercent: number
  maxBlastRadiusCount: number
  cooldownSeconds: number
  maxRetries: number

  escalateAfterMinutes: number
  escalateTarget: EscalateTarget

  /** 逗号分隔，如 `staging,dev`。空串 = 不允许任何环境 */
  allowedEnvironments: string

  /** 乐观锁版本。提交时必须原样带回，缺失后端会拒绝 */
  version: number
  updatedBy: string | null
  updateTime: string | null
}

export interface ActionAllowlistEntry {
  id: number
  /** 语言无关的动作标识，如 `k8s.pod.restart`。创建后不可修改 */
  actionKey: string
  displayName: string
  description: string | null
  category: string
  riskLevel: RiskLevel
  /** 目标资源匹配模式。写操作必填，留空后端拒绝 */
  targetPattern: string | null
  environments: string
  /** 参数约束 JSON 字符串 */
  paramSchema: string | null

  /** null = 跟随风险等级策略。只能设 true（收紧），不能设 false 放宽高危动作 */
  requiresApproval: boolean | null
  /** null = 跟随风险等级策略 */
  maxBlastRadiusCount: number | null

  enabled: boolean
  version: number
  updatedBy: string | null
  updateTime: string | null

  /**
   * 服务端算好的「生效后实际约束」。
   *
   * 后端下发而非前端自己合并，是刻意的：合并规则若两边各写一份必然漂移，
   * 而界面显示「无需审批」但引擎实际拦下来，用户会认为系统坏了。
   */
  effectiveRequiresApproval: boolean | null
  effectiveBlastRadiusCount: number | null
}

export interface PagedResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export interface ActionStats {
  total: number
  enabledCount: number
  /** 已启用的高危动作数——需要警惕的风险敞口 */
  highRiskEnabled: number
  /** 已启用且覆盖 prod 环境的动作数 */
  prodEnabled: number
}

export interface RiskPolicyPage {
  items: RiskPolicy[]
  /** 词表随数据下发，前端不维护枚举镜像（镜像必然漂移） */
  approvalModes: Array<{ value: ApprovalMode; label: string; requiredApprovers: number }>
  escalateTargets: Array<{ value: EscalateTarget; label: string }>
}

export interface ActionFilterOptions {
  categories: string[]
  riskLevels: Array<{ value: RiskLevel; label: string; description: string }>
  environments: string[]
  knownCategories: string[]
}

/** 模拟校验结果 */
export interface EvaluateResult {
  actionKey: string
  environment: string
  allowed: boolean
  /** 人类可读的原因。拒绝时必定有值，是这个接口的核心价值 */
  reason: string
  requiresApproval?: boolean
  approvalMode?: ApprovalMode
  blastRadiusCount?: number
  cooldownSeconds?: number
}

// ==================== 请求体 ====================

export interface RiskPolicyPayload {
  approvalMode: ApprovalMode
  approvalTimeoutMinutes: number
  autoExecuteAllowed: boolean
  maxBlastRadiusPercent: number
  maxBlastRadiusCount: number
  cooldownSeconds: number
  maxRetries: number
  escalateAfterMinutes: number
  escalateTarget: EscalateTarget
  allowedEnvironments: string
  version: number
}

export interface ActionPayload {
  actionKey: string
  displayName: string
  description?: string | null
  category: string
  riskLevel: RiskLevel
  targetPattern?: string | null
  environments: string
  paramSchema?: string | null
  requiresApproval?: boolean | null
  maxBlastRadiusCount?: number | null
  enabled?: boolean
  version?: number
}

export interface ActionQuery {
  keyword?: string
  category?: string
  riskLevel?: RiskLevel | ''
  enabled?: boolean
  page?: number
  size?: number
}

/** 只把有值的参数拼进 query，避免 `?keyword=&category=` 这类空参数 */
const toQuery = (params: Record<string, unknown>): string => {
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    sp.set(k, String(v))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}

// ==================== 风险等级策略 ====================

export async function fetchRiskPolicies(): Promise<RiskPolicyPage> {
  const payload = await http.get<unknown>(`${API_BASE}/governance/risk-policies`)
  return unwrapBiz<RiskPolicyPage>(payload, '获取风险策略失败')
}

/**
 * 更新某一级策略。
 *
 * 版本冲突时后端返回 40009，`unwrapBiz` 抛 HttpError——
 * 调用方应提示「已被他人修改」并刷新，**不要静默重试**：
 * 安全策略被静默覆盖正是「以为关掉了实际没关」这类事故的成因。
 */
export async function updateRiskPolicy(
  level: RiskLevel,
  payload: RiskPolicyPayload
): Promise<RiskPolicy> {
  const res = await http.put<unknown>(
    `${API_BASE}/governance/risk-policies/${encodeURIComponent(level)}`,
    payload
  )
  return unwrapBiz<RiskPolicy>(res, '更新风险策略失败')
}

// ==================== 动作白名单 ====================

export async function fetchActions(
  q: ActionQuery = {}
): Promise<PagedResult<ActionAllowlistEntry>> {
  const payload = await http.get<unknown>(`${API_BASE}/governance/actions${toQuery({ ...q })}`)
  return unwrapBiz<PagedResult<ActionAllowlistEntry>>(payload, '获取动作白名单失败')
}

export async function fetchActionStats(): Promise<ActionStats> {
  const payload = await http.get<unknown>(`${API_BASE}/governance/actions/stats`)
  return unwrapBiz<ActionStats>(payload, '获取白名单统计失败')
}

export async function fetchActionFilterOptions(): Promise<ActionFilterOptions> {
  const payload = await http.get<unknown>(`${API_BASE}/governance/actions/filter-options`)
  return unwrapBiz<ActionFilterOptions>(payload, '获取筛选选项失败')
}

export async function fetchActionDetail(id: number): Promise<ActionAllowlistEntry> {
  const payload = await http.get<unknown>(`${API_BASE}/governance/actions/${id}`)
  return unwrapBiz<ActionAllowlistEntry>(payload, '获取动作详情失败')
}

export async function createAction(payload: ActionPayload): Promise<ActionAllowlistEntry> {
  const res = await http.post<unknown>(`${API_BASE}/governance/actions`, payload)
  return unwrapBiz<ActionAllowlistEntry>(res, '创建动作失败')
}

export async function updateAction(
  id: number,
  payload: ActionPayload
): Promise<ActionAllowlistEntry> {
  const res = await http.put<unknown>(`${API_BASE}/governance/actions/${id}`, payload)
  return unwrapBiz<ActionAllowlistEntry>(res, '更新动作失败')
}

/**
 * 启用 / 停用。
 *
 * 独立端点而非「更新时带上 enabled」：列表页的开关只想改一个布尔值，
 * 走全量更新需要前端把该行所有字段回填，任何一项读漏都会被静默重置。
 */
export async function toggleAction(
  id: number,
  enabled: boolean,
  version: number
): Promise<ActionAllowlistEntry> {
  const res = await http.post<unknown>(`${API_BASE}/governance/actions/${id}/toggle`, {
    enabled,
    version,
  })
  return unwrapBiz<ActionAllowlistEntry>(res, enabled ? '启用动作失败' : '停用动作失败')
}

/**
 * 模拟校验：「在 X 环境执行动作 Y，现在允许吗」。
 *
 * 存在的理由是让配置**可验证**。安全配置最糟的失效模式是
 * 「以为配好了实际没生效」——用户改完一堆开关，无从确认结果。
 */
export async function evaluateAction(
  actionKey: string,
  environment: string
): Promise<EvaluateResult> {
  const res = await http.post<unknown>(`${API_BASE}/governance/evaluate`, {
    actionKey,
    environment,
  })
  return unwrapBiz<EvaluateResult>(res, '校验失败')
}
