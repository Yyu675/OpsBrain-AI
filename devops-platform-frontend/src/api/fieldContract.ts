/**
 * 字段集契约互证（项3 Phase B，2026-10-06）：
 * 手写传输类型 ↔ orval 生成物 的**编译期**字段集对账——第三道契约闸。
 *
 * 三道闸分工（apiContract 双测锁 path+method，本文件锁字段名集合）：
 * - `X_Missing` ≠ never ⟶ 手写缺了后端新字段（后端加字段、手写类型没跟）→ 消费端对它零防护；
 * - `X_Extra`  ≠ never ⟶ 手写有后端已删的字段（或快照滞后：改契约后没跑 api:refresh）
 *   → 该字段 runtime 恒 undefined。先 sync 快照再看红是否仍在，在=后端真删了。
 *
 * **边界（如实声明，不是偷懒）**：接口只对账字段名集合——必填性 / nullable / 值类型
 * 以手写为准（Java 无 required 概念、未注解 nullable 产不出，生成物是字段全集的
 * **下界**，不是值域真相源），故不替换、只对账；值类型漂移归真机兜底
 * （用户工作流：随时真实启动）。
 * **枚举值域已升维（批87）**：Java enum 字段 springdoc 会产 const enum →
 * union↔enum 双向 Exclude 断言（ApprovalMode / EscalateTarget 两对）；
 * String 字段承载的手写枚举（如 RiskLevel——DTO 侧是 String，无 enum 孪生）
 * 仍无依据，归真机。快照 refresh：契约变更后 `npm run api:refresh`。
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
import type { NotifyChannelView as GenNotifyChannelView } from './generated/notifyChannelView'
import type { NotifyChannelList as GenNotifyChannelList } from './generated/notifyChannelList'
import type { IngestSecretView as GenIngestSecretView } from './generated/ingestSecretView'
import type { Revealed as GenRevealed } from './generated/revealed'
import type { TestResult as GenTestResult } from './generated/testResult'
import type { CreateRequest as GenCreateRequest } from './generated/createRequest'
import type { UpdateRequest as GenUpdateRequest } from './generated/updateRequest'
import type { HistoryView as GenHistoryView } from './generated/historyView'
import type { ProbeResult as GenProbeResult } from './generated/probeResult'
import type { PolicyStats as GenPolicyStats } from './generated/policyStats'
import type { RiskPolicy as GenRiskPolicy } from './generated/riskPolicy'
import type { RiskPolicyApprovalMode as GenRiskPolicyApprovalMode } from './generated/riskPolicyApprovalMode'
import type { RiskPolicyEscalateTarget as GenRiskPolicyEscalateTarget } from './generated/riskPolicyEscalateTarget'
import type { SimulatedRow as GenSimulatedRow } from './generated/simulatedRow'
import type { SimulateResult as GenSimulateResult } from './generated/simulateResult'
import type { StormStatus as GenStormStatus } from './generated/stormStatus'
import type { PipelineHeartbeat as GenPipelineHeartbeat } from './generated/pipelineHeartbeat'
import type { LogsHeartbeat as GenLogsHeartbeat } from './generated/logsHeartbeat'
import type { KnowledgeHotTagResponse as GenKnowledgeHotTagResponse } from './generated/knowledgeHotTagResponse'
import type { ApprovalPageResponse as GenApprovalPageResponse } from './generated/approvalPageResponse'
import type { UserView as GenUserView } from './generated/userView'
import type { LoginResponse as GenLoginResponse } from './generated/loginResponse'
import type { AgentTraceStatsResponse as GenAgentTraceStatsResponse } from './generated/agentTraceStatsResponse'
import type { AgentTraceDetailResponse as GenAgentTraceDetailResponse } from './generated/agentTraceDetailResponse'
import type { DashboardTrendsResponse as GenDashboardTrendsResponse } from './generated/dashboardTrendsResponse'
import type { HealingStatsResponse as GenHealingStatsResponse } from './generated/healingStatsResponse'
import type { DiagnosisBoardResponse as GenDiagnosisBoardResponse } from './generated/diagnosisBoardResponse'
import type { OperationAuditItem as GenOperationAuditItem } from './generated/operationAuditItem'
import type { AiCallLogItem as GenAiCallLogItem } from './generated/aiCallLogItem'
import type { AiCallStats as GenAiCallStats } from './generated/aiCallStats'
import type { AuditFilterOptionsResponse as GenAuditFilterOptionsResponse } from './generated/auditFilterOptionsResponse'
import type { AuditTraceDetailResponse as GenAuditTraceDetailResponse } from './generated/auditTraceDetailResponse'
import type { MetricCatalogResponse as GenMetricCatalogResponse } from './generated/metricCatalogResponse'
import type { MetricInstantResponse as GenMetricInstantResponse } from './generated/metricInstantResponse'
import type { MetricRangeResponse as GenMetricRangeResponse } from './generated/metricRangeResponse'
import type { MetricOverviewResponse as GenMetricOverviewResponse } from './generated/metricOverviewResponse'
import type { MetricDatasourceResponse as GenMetricDatasourceResponse } from './generated/metricDatasourceResponse'

import type { Alert, AlertsResponse, ChannelTemplate, ConnectivityResult, CapabilityItem, KnowledgeTag, KnowledgeDocListItem, KnowledgeDocPageResponse, KnowledgeDocCreateRequest, KnowledgeDocUpdateRequest, AiChannelHistoryView, ChannelProbeResult, KnowledgeHotTag } from './types'
import type { BackendTicket } from './types/ticket'
import type { StormStatus, PipelineHeartbeat, LogsHeartbeat } from './alerts'
import type { ApprovalRequest, ApprovalPage } from './approval'
import type { AuthUser, LoginResult } from './auth'
import type { AgentTraceStats, AgentTraceDetail } from './agentTrace'
import type { TrendData, DiagnosisBoard } from './dashboard'
import type { HealingStats } from './healing'
import type {
  OperationAuditItem,
  AiCallLogItem,
  AiCallStats,
  FilterOptions as AuditFilterOptions,
  TraceDetail as AuditTraceDetail
} from './auditLogs'
import type {
  MetricCatalog,
  InstantResult as MetricInstantResult,
  RangeResult as MetricRangeResult,
  OverviewResult as MetricOverviewResult,
  DatasourceResult as MetricDatasourceResult
} from './metrics'
import type {
  RiskPolicy,
  ApprovalMode,
  EscalateTarget,
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
import type {
  NotifyChannelView, NotifyChannelList, IngestSecretView,
  NotifyTestResult, NotifySecretRevealed
} from './types/notify'

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
export type PipelineHeartbeat_Missing = AssertDriftFree<GenOnly<GenPipelineHeartbeat, PipelineHeartbeat>>
export type PipelineHeartbeat_Extra = AssertDriftFree<HandOnly<GenPipelineHeartbeat, PipelineHeartbeat>>
export type LogsHeartbeat_Missing = AssertDriftFree<GenOnly<GenLogsHeartbeat, LogsHeartbeat>>
export type LogsHeartbeat_Extra = AssertDriftFree<HandOnly<GenLogsHeartbeat, LogsHeartbeat>>

// ==================== 知识域 ====================

export type KnowledgeTag_Missing = AssertDriftFree<GenOnly<GenKnowledgeTag, KnowledgeTag>>
export type KnowledgeTag_Extra = AssertDriftFree<HandOnly<GenKnowledgeTag, KnowledgeTag>>
export type KnowledgeDocListItem_Missing = AssertDriftFree<GenOnly<GenListItem, KnowledgeDocListItem>>
export type KnowledgeDocListItem_Extra = AssertDriftFree<HandOnly<GenListItem, KnowledgeDocListItem>>
export type KnowledgeDocPageResponse_Missing = AssertDriftFree<GenOnly<GenDocPage, KnowledgeDocPageResponse>>
export type KnowledgeDocPageResponse_Extra = AssertDriftFree<HandOnly<GenDocPage, KnowledgeDocPageResponse>>
export type KnowledgeDocCreateRequest_Missing = AssertDriftFree<GenOnly<GenCreateRequest, KnowledgeDocCreateRequest>>
export type KnowledgeDocCreateRequest_Extra = AssertDriftFree<HandOnly<GenCreateRequest, KnowledgeDocCreateRequest>>
export type KnowledgeDocUpdateRequest_Missing = AssertDriftFree<GenOnly<GenUpdateRequest, KnowledgeDocUpdateRequest>>
export type KnowledgeDocUpdateRequest_Extra = AssertDriftFree<HandOnly<GenUpdateRequest, KnowledgeDocUpdateRequest>>
export type KnowledgeHotTag_Missing = AssertDriftFree<GenOnly<GenKnowledgeHotTagResponse, KnowledgeHotTag>>
export type KnowledgeHotTag_Extra = AssertDriftFree<HandOnly<GenKnowledgeHotTagResponse, KnowledgeHotTag>>

// ==================== 审批 / 治理域 ====================

export type ApprovalRequest_Missing = AssertDriftFree<GenOnly<GenApprovalRequest, ApprovalRequest>>
export type ApprovalRequest_Extra = AssertDriftFree<HandOnly<GenApprovalRequest, ApprovalRequest>>
export type ApprovalPage_Missing = AssertDriftFree<GenOnly<GenApprovalPageResponse, ApprovalPage>>
export type ApprovalPage_Extra = AssertDriftFree<HandOnly<GenApprovalPageResponse, ApprovalPage>>
export type RiskPolicy_Missing = AssertDriftFree<GenOnly<GenRiskPolicy, RiskPolicy>>
export type RiskPolicy_Extra = AssertDriftFree<HandOnly<GenRiskPolicy, RiskPolicy>>

// ==================== 鉴权 / 会话域 ====================

export type AuthUser_Missing = AssertDriftFree<GenOnly<GenUserView, AuthUser>>
export type AuthUser_Extra = AssertDriftFree<HandOnly<GenUserView, AuthUser>>
export type LoginResult_Missing = AssertDriftFree<GenOnly<GenLoginResponse, LoginResult>>
export type LoginResult_Extra = AssertDriftFree<HandOnly<GenLoginResponse, LoginResult>>

// ==================== Agent 轨迹与监控 ====================

export type AgentTraceStats_Missing = AssertDriftFree<GenOnly<GenAgentTraceStatsResponse, AgentTraceStats>>
export type AgentTraceStats_Extra = AssertDriftFree<HandOnly<GenAgentTraceStatsResponse, AgentTraceStats>>
export type AgentTraceDetail_Missing = AssertDriftFree<GenOnly<GenAgentTraceDetailResponse, AgentTraceDetail>>
export type AgentTraceDetail_Extra = AssertDriftFree<HandOnly<GenAgentTraceDetailResponse, AgentTraceDetail>>

// ==================== 仪表盘趋势与自愈中心 ====================

export type TrendData_Missing = AssertDriftFree<GenOnly<GenDashboardTrendsResponse, TrendData>>
export type TrendData_Extra = AssertDriftFree<HandOnly<GenDashboardTrendsResponse, TrendData>>
export type DiagnosisBoard_Missing = AssertDriftFree<GenOnly<GenDiagnosisBoardResponse, DiagnosisBoard>>
export type DiagnosisBoard_Extra = AssertDriftFree<HandOnly<GenDiagnosisBoardResponse, DiagnosisBoard>>
export type HealingStats_Missing = AssertDriftFree<GenOnly<GenHealingStatsResponse, HealingStats>>
export type HealingStats_Extra = AssertDriftFree<HandOnly<GenHealingStatsResponse, HealingStats>>

// ==================== 审计与 AI 调用日志 ====================

export type OperationAuditItem_Missing = AssertDriftFree<GenOnly<GenOperationAuditItem, OperationAuditItem>>
export type OperationAuditItem_Extra = AssertDriftFree<HandOnly<GenOperationAuditItem, OperationAuditItem>>
export type AiCallLogItem_Missing = AssertDriftFree<GenOnly<GenAiCallLogItem, AiCallLogItem>>
export type AiCallLogItem_Extra = AssertDriftFree<HandOnly<GenAiCallLogItem, AiCallLogItem>>
export type AiCallStats_Missing = AssertDriftFree<GenOnly<GenAiCallStats, AiCallStats>>
export type AiCallStats_Extra = AssertDriftFree<HandOnly<GenAiCallStats, AiCallStats>>
export type AuditFilterOptions_Missing = AssertDriftFree<GenOnly<GenAuditFilterOptionsResponse, AuditFilterOptions>>
export type AuditFilterOptions_Extra = AssertDriftFree<HandOnly<GenAuditFilterOptionsResponse, AuditFilterOptions>>
export type AuditTraceDetail_Missing = AssertDriftFree<GenOnly<GenAuditTraceDetailResponse, AuditTraceDetail>>
export type AuditTraceDetail_Extra = AssertDriftFree<HandOnly<GenAuditTraceDetailResponse, AuditTraceDetail>>

// ==================== 监控指标域（L2） ====================

export type MetricCatalog_Missing = AssertDriftFree<GenOnly<GenMetricCatalogResponse, MetricCatalog>>
export type MetricCatalog_Extra = AssertDriftFree<HandOnly<GenMetricCatalogResponse, MetricCatalog>>
export type MetricInstant_Missing = AssertDriftFree<GenOnly<GenMetricInstantResponse, MetricInstantResult>>
export type MetricInstant_Extra = AssertDriftFree<HandOnly<GenMetricInstantResponse, MetricInstantResult>>
export type MetricRange_Missing = AssertDriftFree<GenOnly<GenMetricRangeResponse, MetricRangeResult>>
export type MetricRange_Extra = AssertDriftFree<HandOnly<GenMetricRangeResponse, MetricRangeResult>>
export type MetricOverview_Missing = AssertDriftFree<GenOnly<GenMetricOverviewResponse, MetricOverviewResult>>
export type MetricOverview_Extra = AssertDriftFree<HandOnly<GenMetricOverviewResponse, MetricOverviewResult>>
export type MetricDatasource_Missing = AssertDriftFree<GenOnly<GenMetricDatasourceResponse, MetricDatasourceResult>>
export type MetricDatasource_Extra = AssertDriftFree<HandOnly<GenMetricDatasourceResponse, MetricDatasourceResult>>

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

// ==================== 通知配置域（批89）====================
// 边界记档：Controller @RequestBody record（UpdateChannelBody/UpdateIngestBody）
// springdoc 产 {[key:string]:unknown} 弱字典——body 类不入对账；
// Service 内嵌 record（视图五件）属性齐全，正常配。

export type NotifyChannelView_Missing = AssertDriftFree<GenOnly<GenNotifyChannelView, NotifyChannelView>>
export type NotifyChannelView_Extra = AssertDriftFree<HandOnly<GenNotifyChannelView, NotifyChannelView>>
export type NotifyChannelList_Missing = AssertDriftFree<GenOnly<GenNotifyChannelList, NotifyChannelList>>
export type NotifyChannelList_Extra = AssertDriftFree<HandOnly<GenNotifyChannelList, NotifyChannelList>>
export type IngestSecretView_Missing = AssertDriftFree<GenOnly<GenIngestSecretView, IngestSecretView>>
export type IngestSecretView_Extra = AssertDriftFree<HandOnly<GenIngestSecretView, IngestSecretView>>
export type NotifyTestResult_Missing = AssertDriftFree<GenOnly<GenTestResult, NotifyTestResult>>
export type NotifyTestResult_Extra = AssertDriftFree<HandOnly<GenTestResult, NotifyTestResult>>
export type NotifySecretRevealed_Missing = AssertDriftFree<GenOnly<GenRevealed, NotifySecretRevealed>>
export type NotifySecretRevealed_Extra = AssertDriftFree<HandOnly<GenRevealed, NotifySecretRevealed>>
export type AiChannelHistoryView_Missing = AssertDriftFree<GenOnly<GenHistoryView, AiChannelHistoryView>>
export type AiChannelHistoryView_Extra = AssertDriftFree<HandOnly<GenHistoryView, AiChannelHistoryView>>
export type ChannelProbeResult_Missing = AssertDriftFree<GenOnly<GenProbeResult, ChannelProbeResult>>
export type ChannelProbeResult_Extra = AssertDriftFree<HandOnly<GenProbeResult, ChannelProbeResult>>

// ==================== 枚举值域对账（批87 升维：Java enum 字段 springdoc 产 const enum）====================
// 双向 Exclude：后端枚举加值手写没跟 / 手写多值后端删了 → 编译期点名。

export type ApprovalMode_GenOnly = AssertDriftFree<Exclude<GenRiskPolicyApprovalMode, ApprovalMode>>
export type ApprovalMode_HandOnly = AssertDriftFree<Exclude<ApprovalMode, GenRiskPolicyApprovalMode>>
export type EscalateTarget_GenOnly = AssertDriftFree<Exclude<GenRiskPolicyEscalateTarget, EscalateTarget>>
export type EscalateTarget_HandOnly = AssertDriftFree<Exclude<EscalateTarget, GenRiskPolicyEscalateTarget>>

// ==================== 工单活动流 ====================
// 刻意豁免（2026-10-06 甄别）：TicketReply/TicketActivity 的手写形态是
// dto-converter 下游的**前端渲染模型**（time=格式化时刻、user=展示名、
// color/text=渲染态）——传输字段（id/ticketId/createTime/userName）在转换
// 函数两侧维护，字段集对账不适用映射层。传输真相归 api 模块的入参出参。
