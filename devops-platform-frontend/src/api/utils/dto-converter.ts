/**
 * DTO 转换器 - 前后端数据格式转换
 */

import type {
  BackendTicket,
  FrontendTicket,
  BackendTicketStatus,
  FrontendTicketStatus,
  BackendTicketPriority,
  FrontendTicketPriority
} from '../types/ticket'
// 「未指派」哨兵单一来源（constants 层，避免 store → api → store 循环依赖）
import { UNASSIGNED } from '../../constants/ticket'

/**
 * 后端状态 → 前端状态
 */
export function mapBackendStatusToFrontend(status: BackendTicketStatus): FrontendTicketStatus {
  const mapping: Record<BackendTicketStatus, FrontendTicketStatus> = {
    PENDING: 'pending',
    PROCESSING: 'processing',
    RESOLVED: 'resolved',
    CLOSED: 'closed',
    VOID: 'void'
  }
  return mapping[status] || 'pending'
}

/**
 * 前端状态 → 后端状态
 */
export function mapFrontendStatusToBackend(status: FrontendTicketStatus): BackendTicketStatus {
  const mapping: Record<FrontendTicketStatus, BackendTicketStatus> = {
    pending: 'PENDING',
    processing: 'PROCESSING',
    resolved: 'RESOLVED',  // 后端已支持 RESOLVED，不再降级为 CLOSED
    closed: 'CLOSED',
    void: 'VOID'
  }
  return mapping[status] || 'PENDING'
}

/**
 * 前端优先级 → 后端优先级（一一对应，无信息丢失）
 *
 * B0 前：urgent 与 high 都映射为 HIGH，两档塌缩成一档——用户选「高」，
 * 保存后回读变「紧急」，high 档事实上不存在。四档后完全对应。
 */
export function mapFrontendPriorityToBackend(priority: FrontendTicketPriority): BackendTicketPriority {
  const mapping: Record<FrontendTicketPriority, BackendTicketPriority> = {
    urgent: 'P0',
    high: 'P1',
    medium: 'P2',
    low: 'P3'
  }
  return mapping[priority] || 'P2'
}

/**
 * 后端优先级 → 前端优先级
 *
 * 兼容未迁移的历史数据（HIGH/MEDIUM/LOW）：映射与后端
 * TicketEnums.Priority.normalize 及 migration_v16 保持一致——
 * HIGH→P1→high（不是 urgent）。三处映射必须同步，否则同一条数据
 * 在列表、详情、编辑三个入口会显示成不同优先级。
 */
export function mapBackendPriorityToFrontend(priority: BackendTicketPriority): FrontendTicketPriority {
  const mapping: Record<BackendTicketPriority, FrontendTicketPriority> = {
    P0: 'urgent',
    P1: 'high',
    P2: 'medium',
    P3: 'low',
    // 历史数据兼容（与后端迁移映射一致）
    HIGH: 'high',
    MEDIUM: 'medium',
    LOW: 'low'
  }
  return mapping[priority] || 'medium'
}

/**
 * 后端 module 枚举 ↔ 前端 service 中文标签
 *
 * 后端 module 有限枚举（K8S/ALIYUN_SLB/MYSQL/NETWORK/OTHER），
 * 前端 service 是可读标签。双向映射保证「创建→读回」一致。
 * 与后端 TicketEnums.Module.ALL 单源对齐。
 */
const MODULE_TO_SERVICE: Record<string, string> = {
  K8S: '生产集群-K8s',
  MYSQL: '生产环境-MySQL',
  ALIYUN_SLB: '生产环境-Nginx',
  NETWORK: '网络',
  OTHER: '未分类'
}

/** service 标签 → module 枚举（MODULE_TO_SERVICE 的反向表） */
const SERVICE_TO_MODULE: Record<string, string> = {
  '生产集群-K8s': 'K8S',
  '生产环境-MySQL': 'MYSQL',
  '生产环境-Nginx': 'ALIYUN_SLB',
  '网络': 'NETWORK',
  '未分类': 'OTHER'
}

/**
 * 后端 module → 前端 service 标签
 * <p>未知枚举原样返回，避免信息丢失。</p>
 */
export function mapModuleToService(module?: string): string {
  if (!module) return '未分类'
  return MODULE_TO_SERVICE[module.toUpperCase()] || module
}

/**
 * 前端 service 标签 → 后端 module 枚举
 * <p>未匹配则回落 OTHER，保证后端拿到合法值。</p>
 */
export function mapServiceToModule(service?: string): string {
  if (!service) return 'OTHER'
  return SERVICE_TO_MODULE[service] || 'OTHER'
}

/**
 * 后端工单 → 前端工单
 */
export function convertBackendTicketToFrontend(backend: BackendTicket): FrontendTicket {
  return {
    id: backend.id,
    title: backend.title,
    description: backend.description || '',
    status: mapBackendStatusToFrontend(backend.status),
    priority: mapBackendPriorityToFrontend(backend.priority),
    assignee: backend.assignee || UNASSIGNED,
    creator: backend.creator || 'devops-admin',
    createdAt: formatBackendTime(backend.createTime),
    updatedAt: formatBackendTime(backend.updateTime),
    service: mapModuleToService(backend.module), // 枚举 → 可读标签
    category: backend.category || '其他',
    // 标签来自后端关联表。
    // 此前是 extractTagsFromModule(backend.module) 凭 module 编造——
    // 每张工单都被贴上「生产环境」，测试环境工单也如此，直接误导判断。
    tags: backend.tags ?? [],
    sla: backend.sla || '8h 响应 / 24h 解决',
    // SLA 进度由后端按 create_time 与 sla 时限推算。
    // 此前硬编码 0，导致进度条恒为 0%、SLA 预警（≥70%）永不触发
    slaProgress: backend.slaProgress ?? 0,
    slaBreached: backend.slaBreached ?? false,
    // B0：剩余时间由后端算。用 ?? null 而非 ?? 0——0 表示「刚好用完」，
    // null 表示「无法计算」，二者不能混同（同 6.38 的降级值原则）
    slaRemainingMinutes: backend.slaRemainingMinutes ?? null,
    // B1 首响：全部由后端计算（6.15 契约）。用 ?? null 保持「未知/未首响」
    // 与「0 分钟（秒级响应）」的区分
    firstResponseState: backend.firstResponseState ?? 'WAITING',
    firstResponseMinutes: backend.firstResponseMinutes ?? null,
    responseRemainingMinutes: backend.responseRemainingMinutes ?? null,
    firstResponder: backend.firstResponder ?? null,
    escalateReason: backend.escalateReason ?? null,
    // B2/B3 处置阶段与根因分类
    handlingStage: backend.handlingStage ?? null,
    mitigatedAt: backend.mitigatedAt ?? null,
    rootCauseAt: backend.rootCauseAt ?? null,
    verifiedAt: backend.verifiedAt ?? null,
    verifySkipped: backend.verifySkipped ?? false,
    rootCauseCategory: backend.rootCauseCategory ?? null,
    // B3 根因与验证详细字段（6.46 契约：后端已返回，前端需透传）
    rootCause: backend.rootCause ?? null,
    rootCauseBy: backend.rootCauseBy ?? null,
    verifier: backend.verifier ?? null,
    verifyMethod: backend.verifyMethod ?? null,
    verifyConclusion: backend.verifyConclusion ?? null,
    verifySkipReason: backend.verifySkipReason ?? null,
    // 附件需通过单独接口获取，转换器不恒空
    attachments: [],
    replies: [], // 回复需要单独接口获取
    activities: [], // 活动流需要单独接口获取
    version: backend.version ?? 0 // P1-4 乐观锁，更新时须回传
  }
}

/**
 * 格式化后端时间为前端显示格式
 * 后端: "2026-07-17T03:01:04"
 * 前端: "2026-07-17 03:01"
 */
function formatBackendTime(time: string): string {
  if (!time) return ''
  return time.slice(0, 16).replace('T', ' ')
}

// extractTagsFromModule 已删除：
// 该函数凭 module 编造标签（每张工单都贴「生产环境」），
// 属于「假数据以真实数据呈现」，违反「前端不使用 mock 数据」约定。
// 标签现由 sys_ticket_tag 关联表持久化，后端随工单一并返回。
