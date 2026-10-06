/**
 * 字段集契约互证（项3 Phase B，2026-10-06）：
 * 手写传输类型 ↔ orval 生成物 的**编译期**字段集对账——第三道契约闸。
 *
 * 三道闸分工（apiContract 双测锁 path+method，本文件锁字段名集合）：
 * - `X_Missing` ≠ never ⟶ 手写缺了后端新字段（后端加字段、手写类型没跟）→ 消费端对它零防护；
 * - `X_Extra`  ≠ never ⟶ 手写有后端已删的字段（或快照滞后：改契约后没跑 api:refresh）
 *   → 该字段 runtime 恒 undefined。先 sync 快照再看红是否仍在，在=后端真删了。
 *
 * **边界（如实声明，不是偷懒）**：只对账字段名集合。必填性 / 枚举值域 / nullable
 * / 值类型以手写为准——springdoc→orval 的产物全字段 optional、无字面量枚举、无
 * 字段级 JSDoc（Java 无 required 概念、未注解 nullable 产不出），生成物是字段全集
 * 的**下界**，不是值域真相源。因此本文件不 import 手写类型去替换、只做结构对账；
 * 值域漂移（字段类型改了、枚举加值）由真机联调兜底（用户工作流：随时真实启动）。
 *
 * **为什么不是测试类**（2026-10-06 用户纪律：不写功能测试类）：这是普通 .ts
 * 编译期断言，vue-tsc（六闸之一）天然执行，零运行时、零新增用例。
 *
 * 刷新：后端契约变更 → `npm run api:refresh`（sync 快照 + orval 生成）→
 * `npm run typecheck` 即对出新漂移。
 */
import type { Alert as GenAlert } from './generated/alert'
import type { AlertPage as GenAlertPage } from './generated/alertPage'
import type { ApprovalRequest as GenApprovalRequest } from './generated/approvalRequest'
import type { AutomationPolicy as GenAutomationPolicy } from './generated/automationPolicy'
import type { CapabilityItem as GenCapabilityItem } from './generated/capabilityItem'
import type { ChannelTemplate as GenChannelTemplate } from './generated/channelTemplate'
import type { ConnectivityResult as GenConnectivityResult } from './generated/connectivityResult'
import type { DevOpsTicket as GenDevOpsTicket } from './generated/devOpsTicket'
import type { ActionAllowlistEntry as GenActionAllowlistEntry } from './generated/actionAllowlistEntry'
import type { ActionFilterOptions as GenActionFilterOptions } from './generated/actionFilterOptions'
import type { ActionStats as GenActionStats } from './generated/actionStats'
import type { EvaluateResult as GenEvaluateResult } from './generated/evaluateResult'
import type { HealingExecution as GenHealingExecution } from './generated/healingExecution'
import type { KnowledgeTag as GenKnowledgeTag } from './generated/knowledgeTag'
import type { ListItem as GenListItem } from './generated/listItem'
import type { DocPage as GenDocPage } from './generated/docPage'
import type { PolicyStats as GenPolicyStats } from './generated/policyStats'
import type { RiskPolicy as GenRiskPolicy } from './generated/riskPolicy'
import type { SimulatedRow as GenSimulatedRow } from './generated/simulatedRow'
import type { SimulateResult as GenSimulateResult } from './generated/simulateResult'
import type { StormStatus as GenStormStatus } from './generated/stormStatus'

import type { Alert, AlertsResponse, ChannelTemplate, ConnectivityResult, CapabilityItem, KnowledgeTag, KnowledgeDocListItem, KnowledgeDocPageResponse } from './types'
import type { BackendTicket } from './types/ticket'
import type { StormStatus } from './alerts'
import type { ApprovalRequest } from './approval'
import type {
  RiskPolicy,
  ActionAllowlistEntry,
  ActionStats,
  ActionFilterOptions,
  EvaluateResult,
  AutomationPolicy,
  PolicyStats,
  SimulatedRow,
  SimulateResult
} from './governance'
import type { HealingExecution } from './healing'

/** 生成有、手写无（后端加字段没跟） */
type GenOnly<G, H> = Exclude<keyof G, keyof H>
/** 手写有、生成无（后端删字段没清，或快照待刷） */
type HandOnly<G, H> = Exclude<keyof H, keyof G>
/** 差集必须 never；非 never 时 TS2344 会把漂移字段名点在错误信息里 */
type AssertDriftFree<T extends never> = T

// ==================== 工单域 ====================

export type BackendTicket_Missing = AssertDriftFree<GenOnly<GenDevOpsTicket, BackendTicket>>
export type BackendTicket_Extra = AssertDriftFree<HandOnly<GenDevOpsTicket, BackendTicket>>

// ==================== 告警域 ====================

export type Alert_Missing = AssertDriftFree<GenOnly<GenAlert, Alert>>
export type Alert_Extra = AssertDriftFree<HandOnly<GenAlert, Alert>>
export type AlertsResponse_Missing = AssertDriftFree<GenOnly<GenAlertPage, AlertsResponse>>
export type AlertsResponse_Extra = AssertDriftFree<HandOnly<GenAlertPage, AlertsResponse>>
export type StormStatus_Missing = AssertDriftFree<GenOnly<GenStormStatus, StormStatus>>
export type StormStatus_Extra = AssertDriftFree<HandOnly<GenStormStatus, StormStatus>>

// ==================== 知识域 ====================

export type KnowledgeTag_Missing = AssertDriftFree<GenOnly<GenKnowledgeTag, KnowledgeTag>>
export type KnowledgeTag_Extra = AssertDriftFree<HandOnly<GenKnowledgeTag, KnowledgeTag>>
export type KnowledgeDocListItem_Missing = AssertDriftFree<GenOnly<GenListItem, KnowledgeDocListItem>>
export type KnowledgeDocListItem_Extra = AssertDriftFree<HandOnly<GenListItem, KnowledgeDocListItem>>
export type KnowledgeDocPageResponse_Missing = AssertDriftFree<GenOnly<GenDocPage, KnowledgeDocPageResponse>>
export type KnowledgeDocPageResponse_Extra = AssertDriftFree<HandOnly<GenDocPage, KnowledgeDocPageResponse>>

// ==================== 审批 / 治理域 ====================

export type ApprovalRequest_Missing = AssertDriftFree<GenOnly<GenApprovalRequest, ApprovalRequest>>
export type ApprovalRequest_Extra = AssertDriftFree<HandOnly<GenApprovalRequest, ApprovalRequest>>
export type RiskPolicy_Missing = AssertDriftFree<GenOnly<GenRiskPolicy, RiskPolicy>>
export type RiskPolicy_Extra = AssertDriftFree<HandOnly<GenRiskPolicy, RiskPolicy>>
export type ActionAllowlistEntry_Missing = AssertDriftFree<GenOnly<GenActionAllowlistEntry, ActionAllowlistEntry>>
export type ActionAllowlistEntry_Extra = AssertDriftFree<HandOnly<GenActionAllowlistEntry, ActionAllowlistEntry>>
export type ActionStats_Missing = AssertDriftFree<GenOnly<GenActionStats, ActionStats>>
export type ActionStats_Extra = AssertDriftFree<HandOnly<GenActionStats, ActionStats>>
export type ActionFilterOptions_Missing = AssertDriftFree<GenOnly<GenActionFilterOptions, ActionFilterOptions>>
export type ActionFilterOptions_Extra = AssertDriftFree<HandOnly<GenActionFilterOptions, ActionFilterOptions>>
export type EvaluateResult_Missing = AssertDriftFree<GenOnly<GenEvaluateResult, EvaluateResult>>
export type EvaluateResult_Extra = AssertDriftFree<HandOnly<GenEvaluateResult, EvaluateResult>>
export type AutomationPolicy_Missing = AssertDriftFree<GenOnly<GenAutomationPolicy, AutomationPolicy>>
export type AutomationPolicy_Extra = AssertDriftFree<HandOnly<GenAutomationPolicy, AutomationPolicy>>
export type PolicyStats_Missing = AssertDriftFree<GenOnly<GenPolicyStats, PolicyStats>>
export type PolicyStats_Extra = AssertDriftFree<HandOnly<GenPolicyStats, PolicyStats>>
export type SimulatedRow_Missing = AssertDriftFree<GenOnly<GenSimulatedRow, SimulatedRow>>
export type SimulatedRow_Extra = AssertDriftFree<HandOnly<GenSimulatedRow, SimulatedRow>>
export type SimulateResult_Missing = AssertDriftFree<GenOnly<GenSimulateResult, SimulateResult>>
export type SimulateResult_Extra = AssertDriftFree<HandOnly<GenSimulateResult, SimulateResult>>

// ==================== 自愈域 ====================

export type HealingExecution_Missing = AssertDriftFree<GenOnly<GenHealingExecution, HealingExecution>>
export type HealingExecution_Extra = AssertDriftFree<HandOnly<GenHealingExecution, HealingExecution>>

// ==================== 渠道 / 平台能力 ====================

export type ChannelTemplate_Missing = AssertDriftFree<GenOnly<GenChannelTemplate, ChannelTemplate>>
export type ChannelTemplate_Extra = AssertDriftFree<HandOnly<GenChannelTemplate, ChannelTemplate>>
export type ConnectivityResult_Missing = AssertDriftFree<GenOnly<GenConnectivityResult, ConnectivityResult>>
export type ConnectivityResult_Extra = AssertDriftFree<HandOnly<GenConnectivityResult, ConnectivityResult>>
export type CapabilityItem_Missing = AssertDriftFree<GenOnly<GenCapabilityItem, CapabilityItem>>
export type CapabilityItem_Extra = AssertDriftFree<HandOnly<GenCapabilityItem, CapabilityItem>>

// ==================== 工单活动流 ====================
// 刻意豁免（2026-10-06 甄别）：TicketReply/TicketActivity 的手写形态是
// dto-converter 下游的**前端渲染模型**（time=格式化时刻、user=展示名、
// color/text=渲染态）——传输字段（id/ticketId/createTime/userName）在转换
// 函数两侧维护，字段集对账不适用映射层。传输真相归 api 模块的入参出参。
