/**
 * 工单 API 服务
 * 封装后端工单接口调用
 */

import { API_ENDPOINTS } from '../../config/api'
import { http, unwrapBiz, HttpError } from '../../utils/http'
import { getStatusLabel, getPriorityLabel } from '../../constants/ticket'
import {
  convertBackendTicketToFrontend,
  mapFrontendPriorityToBackend,
  mapFrontendStatusToBackend,
  mapServiceToModule
} from '../utils/dto-converter'
import type {
  BackendTicket,
  FrontendTicket,
  FrontendTicketPriority,
  FrontendTicketStatus,
  TicketsRequest,
  TicketsResponse,
  TicketReply,
  TicketActivity
} from '../types/ticket'

/**
 * 查询工单列表（分页）
 */
export async function fetchTickets(params: TicketsRequest = {}): Promise<{
  tickets: FrontendTicket[]
  total: number
  page: number
  size: number
  totalPages: number
}> {
  const queryParams = new URLSearchParams()

  if (params.page !== undefined) queryParams.append('page', String(params.page))
  if (params.size !== undefined) queryParams.append('size', String(params.size))
  // 状态与优先级需做枚举映射：前端用小写（pending/urgent），
  // 后端用大写且 urgent 归并为 HIGH。直接透传会导致筛选静默失效
  if (params.status) {
    queryParams.append('status', mapFrontendStatusToBackend(params.status))
  }
  if (params.priority) {
    queryParams.append('priority', mapFrontendPriorityToBackend(params.priority))
  }

  // 全部筛选下沉到后端：前端本地过滤只能作用于当前页，
  // 会让页外数据静默不可见
  if (params.keyword) queryParams.append('keyword', params.keyword)
  if (params.category) queryParams.append('category', params.category)
  if (params.assignee) queryParams.append('assignee', params.assignee)
  if (params.createdFrom) queryParams.append('createdFrom', params.createdFrom)
  if (params.createdTo) queryParams.append('createdTo', params.createdTo)
  // service 是前端展示名（如「容器/K8s」），后端存 module 枚举，需转换
  if (params.service) queryParams.append('module', mapServiceToModule(params.service))
  // 标签重复传参：后端以 List<String> 接收
  if (params.tags?.length) {
    params.tags.forEach(t => queryParams.append('tags', t))
  }
  // 排序同样下沉到后端：本地排序只能排当前页，
  // 「按优先级排序」会漏掉页外更高优先级的工单
  if (params.sortBy) {
    queryParams.append('sortBy', params.sortBy)
    queryParams.append('sortAsc', String(params.sortAsc === true))
  }

  const url = `${API_ENDPOINTS.TICKETS}${
    queryParams.toString() ? `?${queryParams.toString()}` : ''
  }`

  const payload = await http.get<unknown>(url)
  const data = unwrapBiz<TicketsResponse>(payload, '获取工单列表失败')

  // 转换后端数据为前端格式
  const frontendTickets = data.tickets.map(convertBackendTicketToFrontend)

  return {
    tickets: frontendTickets,
    total: data.total,
    page: data.page,
    size: data.size,
    totalPages: data.totalPages
  }
}

/**
 * 根据 traceId 查询工单
 */
export async function fetchTicketByTraceId(traceId: string): Promise<FrontendTicket | null> {
  try {
    const payload = await http.get<unknown>(API_ENDPOINTS.TICKETS_BY_TRACE(traceId))
    const data = unwrapBiz<BackendTicket>(payload, '查询工单失败')
    return convertBackendTicketToFrontend(data)
  } catch (e) {
    if (e instanceof HttpError && (e.status === 404 || e.bizCode === 40004)) {
      return null
    }
    throw e instanceof Error ? e : new Error('查询工单失败')
  }
}

/** 后端版本冲突错误码（P1-4） */
export const CODE_VERSION_CONFLICT = 40009

// ==================== 附件 ====================

/**
 * 附件元数据
 * <p>文件本体存于 MinIO，前端只拿元数据 + 预签名下载链接。</p>
 */
export interface TicketAttachmentMeta {
  id: number
  ticketId: string
  originalName: string
  contentType?: string
  sizeBytes: number
  /** 后端已格式化好的可读大小，避免各端重复实现 */
  sizeText: string
  uploader?: string
  createTime: string
}

/**
 * 上传附件
 * <p>
 * 后端安全控制：扩展名白名单、双扩展名绕过检测、路径穿越拒绝、
 * 大小与数量上限、内容哈希查重。
 * </p>
 */
export async function uploadTicketAttachment(
  ticketId: string,
  file: File,
  uploader?: string
): Promise<TicketAttachmentMeta> {
  const form = new FormData()
  form.append('file', file)
  if (uploader) form.append('uploader', uploader)

  const url = `${API_ENDPOINTS.TICKETS}/${encodeURIComponent(ticketId)}/attachments`
  const payload = await http.post<unknown>(url, form)
  return unwrapBiz<TicketAttachmentMeta>(payload, '上传失败')
}

/**
 * 查询工单附件列表
 */
export async function fetchTicketAttachments(ticketId: string): Promise<TicketAttachmentMeta[]> {
  const url = `${API_ENDPOINTS.TICKETS}/${encodeURIComponent(ticketId)}/attachments`
  const payload = await http.get<unknown>(url)
  return unwrapBiz<TicketAttachmentMeta[]>(payload, '查询附件失败') ?? []
}

/**
 * 获取附件下载链接（预签名 URL）
 * <p>
 * 返回的 URL 直连对象存储，文件不经应用后端，有效期约 5 分钟。
 * </p>
 */
export async function fetchAttachmentDownloadUrl(attachmentId: number): Promise<string> {
  const url = `${API_ENDPOINTS.TICKETS}/attachments/${attachmentId}/download-url`
  const payload = await http.get<unknown>(url)
  const data = unwrapBiz<{ url: string; expiresInSeconds: number }>(payload, '获取下载链接失败')
  return data.url
}

/**
 * 删除附件
 */
export async function deleteTicketAttachment(attachmentId: number): Promise<void> {
  const url = `${API_ENDPOINTS.TICKETS}/attachments/${attachmentId}`
  await http.del<unknown>(url)
}

// ==================== 标签 ====================

/**
 * 替换工单标签（全量）
 *
 * @param tags 空数组表示清空全部标签
 * @returns 归一化后的实际标签（后端会去空/去重/截断超长）
 */
export async function replaceTicketTags(ticketId: string, tags: string[]): Promise<string[]> {
  const payload = await http.put<unknown>(
    `${API_ENDPOINTS.TICKETS}/${encodeURIComponent(ticketId)}/tags`,
    { tags }
  )
  return unwrapBiz<string[]>(payload, '更新标签失败') ?? []
}

/**
 * 查询热门标签
 */
export async function fetchHotTags(limit = 20): Promise<string[]> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.TICKETS}/tags/hot?limit=${limit}`)
  const data = unwrapBiz<{ tags: string[]; counts: Record<string, number> }>(payload, '查询热门标签失败')
  return data?.tags ?? []
}

// ==================== 回复与活动流 ====================

/** 后端回复 DTO */
interface BackendReply {
  id: number
  ticketId: string
  role: string
  author: string
  authorColor?: string
  content: string
  createTime: string
}

/** 后端活动流 DTO */
interface BackendActivity {
  id: number
  ticketId: string
  color: string
  text: string
  detail?: string
  userName: string
  highlight?: boolean
  createTime: string
}

/**
 * 时间戳转可读标签
 * <p>同天回复显示 HH:mm；跨天回复显示 MM-DD HH:mm 以区分日期。</p>
 */
function toTimeLabel(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso.slice(11, 16)
  const now = new Date()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const isSameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate()
  if (isSameDay) return `${hh}:${mm}`
  const mo = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${mo}-${dd} ${hh}:${mm}`
}

function toFrontendReply(b: BackendReply): TicketReply {
  const role = ['creator', 'agent', 'ai', 'system'].includes(b.role)
    ? (b.role as TicketReply['role'])
    : 'agent'
  return {
    role,
    author: b.author,
    authorColor: b.authorColor,
    time: toTimeLabel(b.createTime),
    content: b.content
  }
}

function toFrontendActivity(b: BackendActivity): TicketActivity {
  const color = ['success', 'primary', 'gray', 'warning'].includes(b.color)
    ? (b.color as TicketActivity['color'])
    : 'primary'
  return {
    color,
    text: b.text,
    detail: b.detail,
    user: b.userName,
    time: toTimeLabel(b.createTime),
    highlight: b.highlight
  }
}

/**
 * 查询工单回复
 */
export async function fetchTicketReplies(ticketId: string): Promise<TicketReply[]> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.TICKETS}/${encodeURIComponent(ticketId)}/replies`)
  const data = unwrapBiz<BackendReply[]>(payload, '查询回复失败')
  return (data ?? []).map(toFrontendReply)
}

/**
 * 追加工单回复（落库）
 */
export async function addTicketReply(
  ticketId: string,
  payload: { role: string; author: string; authorColor?: string; content: string }
): Promise<TicketReply> {
  const raw = await http.post<unknown>(
    `${API_ENDPOINTS.TICKETS}/${encodeURIComponent(ticketId)}/replies`,
    payload
  )
  return toFrontendReply(unwrapBiz<BackendReply>(raw, '回复提交失败'))
}

/**
 * 查询工单活动流
 */
export async function fetchTicketActivities(ticketId: string): Promise<TicketActivity[]> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.TICKETS}/${encodeURIComponent(ticketId)}/activities`)
  const data = unwrapBiz<BackendActivity[]>(payload, '查询活动流失败')
  return (data ?? []).map(toFrontendActivity)
}

/**
 * 版本冲突错误
 * <p>
 * 与普通错误区分：普通错误可直接重试，版本冲突必须先刷新
 * 看到他人的修改，否则重试仍会覆盖。
 * </p>
 */
export class VersionConflictError extends Error {
  readonly isVersionConflict = true
  constructor(message: string) {
    super(message)
    this.name = 'VersionConflictError'
  }
}

/** JSON 写接口走统一客户端；40009 映射为版本冲突，默认不重试 */
async function writeTicket(method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', url: string, body: unknown, errorPrefix: string): Promise<BackendTicket> {
  try {
    let payload: unknown
    if (method === 'DELETE') payload = await http.del<unknown>(url)
    else if (method === 'POST') payload = await http.post<unknown>(url, body)
    else if (method === 'PUT') payload = await http.put<unknown>(url, body)
    else payload = await http.patch<unknown>(url, body)
    return unwrapBiz<BackendTicket>(payload, errorPrefix)
  } catch (e) {
    if (e instanceof HttpError && e.bizCode === CODE_VERSION_CONFLICT) {
      throw new VersionConflictError(e.message || '该记录已被他人修改，请刷新后重试')
    }
    throw e instanceof Error ? e : new Error(errorPrefix)
  }
}

/**
 * 创建工单（手动表单入口）
 *
 * @param payload 前端字段，内部转换为后端契约
 * @returns 创建后的工单（含后端生成的工单号）
 */
export async function createTicket(payload: {
  title: string
  description: string
  priority: FrontendTicketPriority
  service: string
  category?: string
  assignee?: string
  sla?: string
  creator?: string
  /** 标签列表，此前用户输入被丢弃 */
  tags?: string[]
}): Promise<FrontendTicket> {
  const body = {
    title: payload.title,
    description: payload.description,
    priority: mapFrontendPriorityToBackend(payload.priority),
    module: mapServiceToModule(payload.service),
    category: payload.category,
    assignee: payload.assignee,
    sla: payload.sla,
    creator: payload.creator,
    tags: payload.tags
  }

  const data = await writeTicket('POST', API_ENDPOINTS.TICKETS, body, '创建工单失败')
  return convertBackendTicketToFrontend(data)
}

/**
 * 更新工单
 * <p>仅传入的字段会被更新，未传字段保持原值。</p>
 */
export async function updateTicket(
  id: string,
  patch: {
    title?: string
    description?: string
    priority?: FrontendTicketPriority
    service?: string
    status?: FrontendTicketStatus
    category?: string
    assignee?: string
    sla?: string
    /** 乐观锁版本号，须传入读取时的值以启用并发保护 */
    version?: number
    /** 标签列表。undefined=不改，空数组=清空 */
    tags?: string[]
  }
): Promise<FrontendTicket> {
  const body: Record<string, string | number | string[] | undefined> = {
    title: patch.title,
    description: patch.description,
    category: patch.category,
    assignee: patch.assignee,
    sla: patch.sla,
    version: patch.version,
    tags: patch.tags
  }
  // 仅在传入时转换，避免把 undefined 转成默认值覆盖原数据
  if (patch.priority) body.priority = mapFrontendPriorityToBackend(patch.priority)
  if (patch.service) body.module = mapServiceToModule(patch.service)
  if (patch.status) body.status = mapFrontendStatusToBackend(patch.status)

  const data = await writeTicket('PUT', API_ENDPOINTS.TICKETS_BY_ID(id), body, '更新工单失败')
  return convertBackendTicketToFrontend(data)
}

/**
 * 变更工单状态
 */
export async function updateTicketStatus(
  id: string,
  status: FrontendTicketStatus
): Promise<FrontendTicket> {
  const data = await writeTicket(
    'PATCH',
    `${API_ENDPOINTS.TICKETS_BY_ID(id)}/status`,
    { status: mapFrontendStatusToBackend(status) },
    '变更工单状态失败'
  )
  return convertBackendTicketToFrontend(data)
}

/**
 * 转派工单
 */
export async function transferTicket(id: string, assignee: string): Promise<FrontendTicket> {
  const data = await writeTicket(
    'PATCH',
    `${API_ENDPOINTS.TICKETS_BY_ID(id)}/assignee`,
    { assignee },
    '转派工单失败'
  )
  return convertBackendTicketToFrontend(data)
}

// ==================== B1 首响 / 升级 / SLA 风险 ====================

/**
 * 确认接单（显式首响）
 *
 * 对应告警侧 ACKNOWLEDGED 语义。若工单仍是待处理，后端会同时推进为处理中——
 * 「已确认接单但状态还是待处理」是自相矛盾的状态。
 *
 * @param assignee 可选：确认的同时认领给此人
 */
export async function acknowledgeTicket(
  id: string,
  responder: string,
  assignee?: string
): Promise<FrontendTicket> {
  const data = await writeTicket(
    'POST',
    `${API_ENDPOINTS.TICKETS_BY_ID(id)}/acknowledge`,
    { responder, assignee },
    '确认接单失败'
  )
  return convertBackendTicketToFrontend(data)
}

/**
 * 升级工单
 *
 * L1 阶段后端只记录 + 留痕，不自动改优先级或换负责人（属 L3 审批范畴）。
 * reason 必填——无理由的升级无法追溯。
 */
export async function escalateTicket(
  id: string,
  reason: string,
  operator: string
): Promise<FrontendTicket> {
  const data = await writeTicket(
    'POST',
    `${API_ENDPOINTS.TICKETS_BY_ID(id)}/escalate`,
    { reason, operator },
    '升级工单失败'
  )
  return convertBackendTicketToFrontend(data)
}

/**
 * SLA 风险清单（首响/解决即将超时或已超时）
 *
 * @param withinMinutes 前瞻窗口（分钟），0=只看已超时
 */
export async function fetchSlaAtRisk(
  withinMinutes = 30,
  size = 50
): Promise<{ total: number; withinMinutes: number; tickets: FrontendTicket[] }> {
  const url = `${API_ENDPOINTS.TICKETS}/sla/at-risk?withinMinutes=${withinMinutes}&size=${size}`
  const payload = await http.get<unknown>(url)
  const data = unwrapBiz<{ total: number; withinMinutes: number; tickets: BackendTicket[] }>(
    payload, '查询 SLA 风险清单失败'
  )
  return {
    total: data?.total ?? 0,
    withinMinutes: data?.withinMinutes ?? withinMinutes,
    tickets: (data?.tickets ?? []).map(convertBackendTicketToFrontend)
  }
}

/**
 * 首响统计（MTTA）
 *
 * avgFirstResponseMinutes 为 null 表示尚无任何首响记录——
 * 不用 0 冒充（0 意为「秒级响应」）。
 */
export async function fetchFirstResponseStats(): Promise<{
  responded: number
  notResponded: number
  breached: number
  avgFirstResponseMinutes: number | null
}> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.TICKETS}/sla/first-response-stats`)
  const data = unwrapBiz<{
    responded?: number
    notResponded?: number
    breached?: number
    avgFirstResponseMinutes?: number | null
  }>(payload, '查询首响统计失败')
  return {
    responded: data?.responded ?? 0,
    notResponded: data?.notResponded ?? 0,
    breached: data?.breached ?? 0,
    avgFirstResponseMinutes: data?.avgFirstResponseMinutes ?? null
  }
}

/**
 * 删除工单
 *
 * @returns 被删除的工单快照，供「撤销」时重建
 */
export async function deleteTicket(id: string): Promise<FrontendTicket> {
  const data = await writeTicket('DELETE', API_ENDPOINTS.TICKETS_BY_ID(id), undefined, '删除工单失败')
  return convertBackendTicketToFrontend(data)
}

/**
 * 查询工单统计（供列表页 KPI）
 */
export async function fetchTicketStats(): Promise<{
  total: number
  todayNew: number
  pending: number
  processing: number
  resolved: number
  /**
   * 未完结的高优先级工单数（后端全量统计）
   *
   * 必须用后端值：前端从 store.tickets 本地过滤只能看到当前页，
   * 且用户未打开列表页时该数组为空，会把「有紧急工单」
   * 误报为「暂无紧急待处理工单」——虚假事实陈述。
   */
  urgentPending: number
  /** 各优先级计数（HIGH/MEDIUM/LOW，缺失补 0） */
  byPriority: Record<string, number>
}> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.TICKETS}/stats`)
  return unwrapBiz(payload, '获取工单统计失败')
}

/**
 * 按筛选条件导出工单 CSV
 * <p>
 * 按当前筛选条件拉全量数据（非当前页），生成 CSV 文本。
 * 调用方负责下载（Blob + a 标签）。
 * 后端不可用时如实告知，不导出当前页冒充全量。
 * </p>
 *
 * @param params 筛选条件（与 fetchTickets 共用）
 * @returns CSV 文本
 */
export async function exportTicketsCsv(params: TicketsRequest = {}): Promise<string> {
  const all: FrontendTicket[] = []
  let page = 1
  const size = 200
  let totalPages = 1

  while (page <= totalPages) {
    const res = await fetchTickets({ ...params, page, size })
    all.push(...res.tickets)
    totalPages = res.totalPages
    page++
  }

  const headers = ['工单号', '标题', '状态', '优先级', '负责人', '创建人', '分类', 'SLA', '创建时间', '更新时间']
  const rows = all.map(t => [
    t.id,
    `"${(t.title || '').replace(/"/g, '""')}"`,
    getStatusLabel(t.status),
    getPriorityLabel(t.priority),
    t.assignee || '',
    t.creator || '',
    t.category || '',
    t.sla || '',
    t.createdAt,
    t.updatedAt
  ].join(','))

  return '﻿' + headers.join(',') + '\n' + rows.join('\n')
}

/**
 * 根据 ID 查询工单详情
 */
export async function fetchTicketById(id: string): Promise<FrontendTicket | null> {
  try {
    const payload = await http.get<unknown>(API_ENDPOINTS.TICKETS_BY_ID(id))
    const data = unwrapBiz<BackendTicket>(payload, '查询工单失败')
    return convertBackendTicketToFrontend(data)
  } catch (e) {
    if (e instanceof HttpError && (e.status === 404 || e.bizCode === 40004)) {
      return null
    }
    throw e instanceof Error ? e : new Error('查询工单失败')
  }
}

// ==================== B2 现场处置 ====================

export interface TicketActionRecord {
  id?: number
  ticketId: string
  actionType: string
  summary: string
  detail?: string | null
  operator: string
  effective?: boolean | null
  startedAt?: string | null
  finishedAt?: string | null
  createTime?: string
}

export async function addTicketAction(id: string, action: {
  actionType: string; summary: string; detail?: string; operator: string; effective?: boolean | null
}): Promise<TicketActionRecord> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.TICKETS_BY_ID(id)}/actions`, action)
  const data = unwrapBiz<{ id: number; action: Record<string, unknown> }>(payload, '记录处置动作失败')
  return { id: data.id, ticketId: id, ...data.action } as unknown as TicketActionRecord
}

export async function fetchTicketActions(id: string): Promise<TicketActionRecord[]> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.TICKETS_BY_ID(id)}/actions`)
  const data = unwrapBiz<TicketActionRecord[]>(payload, '查询处置动作失败')
  return Array.isArray(data) ? data : []
}

export async function updateTicketStage(id: string, stage: string, operator: string): Promise<FrontendTicket> {
  const data = await writeTicket('PATCH', `${API_ENDPOINTS.TICKETS_BY_ID(id)}/stage`, { stage, operator }, '切换处置阶段失败')
  return convertBackendTicketToFrontend(data)
}

export async function markTicketMitigated(id: string, operator: string): Promise<FrontendTicket> {
  const data = await writeTicket('POST', `${API_ENDPOINTS.TICKETS_BY_ID(id)}/mitigate`, { operator }, '标记止损失败')
  return convertBackendTicketToFrontend(data)
}

// ==================== B3 根因分析 + 修复验证 ====================

export async function confirmRootCause(id: string, rootCause: string, category: string, operator: string): Promise<FrontendTicket> {
  const data = await writeTicket('PUT', `${API_ENDPOINTS.TICKETS_BY_ID(id)}/root-cause`, { rootCause, category, operator }, '确认根因失败')
  return convertBackendTicketToFrontend(data)
}

export async function submitVerification(id: string, method: string, conclusion: string, verifier: string): Promise<FrontendTicket> {
  const data = await writeTicket('POST', `${API_ENDPOINTS.TICKETS_BY_ID(id)}/verify`, { method, conclusion, verifier }, '提交验证失败')
  return convertBackendTicketToFrontend(data)
}

export async function skipVerification(id: string, reason: string, operator: string): Promise<FrontendTicket> {
  const data = await writeTicket('POST', `${API_ENDPOINTS.TICKETS_BY_ID(id)}/verify/skip`, { reason, operator }, '跳过验证失败')
  return convertBackendTicketToFrontend(data)
}

// 闭环度量（GET /tickets/metrics/closure）与根因聚合（GET /tickets/root-cause/stats）
// 的前端封装统一在 api/dashboard.ts 的 getClosureMetrics / getRootCauseStats——
// 那里带具体的 ClosureMetrics 类型且已被 Dashboard 消费。此处曾有一份返回
// Record<string, unknown> 的重复封装，零调用且类型更弱，已删除避免两处漂移。

// ==================== B4 复盘归档 ====================

export interface PostmortemData {
  id?: number
  ticketId: string
  timeline?: string | null
  impactScope?: string | null
  impactDuration?: number | null
  lessons?: string | null
  docId?: number | null
  author?: string | null
}

export interface ActionItemData {
  id?: number
  postmortemId: number
  ticketId: string
  content: string
  owner?: string | null
  dueDate?: string | null
  status?: string
}

export async function getPostmortem(ticketId: string): Promise<PostmortemData | null> {
  try {
    const payload = await http.get<unknown>(`${API_ENDPOINTS.TICKETS_BY_ID(ticketId)}/postmortem`)
    return unwrapBiz<PostmortemData | null>(payload, '获取复盘失败')
  } catch { return null }
}

export async function savePostmortem(ticketId: string, pm: PostmortemData, author: string): Promise<PostmortemData> {
  const payload = await http.put<unknown>(`${API_ENDPOINTS.TICKETS_BY_ID(ticketId)}/postmortem`, { ...pm, author })
  return unwrapBiz<PostmortemData>(payload, '保存复盘失败')
}

export async function generateTimelineDraft(ticketId: string): Promise<string> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.TICKETS_BY_ID(ticketId)}/postmortem/draft`, {})
  const data = unwrapBiz<{ timeline: string }>(payload, '生成复盘草稿失败')
  return data.timeline
}

export async function listActionItems(params?: { status?: string; owner?: string; overdue?: boolean }): Promise<ActionItemData[]> {
  const qp = new URLSearchParams()
  if (params?.status) qp.append('status', params.status)
  if (params?.owner) qp.append('owner', params.owner)
  if (params?.overdue) qp.append('overdue', 'true')
  const url = `${API_ENDPOINTS.TICKETS}/postmortem/action-items${qp.toString() ? '?' + qp.toString() : ''}`
  const payload = await http.get<unknown>(url)
  const data = unwrapBiz<ActionItemData[]>(payload, '查询改进项失败')
  return Array.isArray(data) ? data : []
}

export async function addActionItem(ticketId: string, item: { postmortemId: number; content: string; owner?: string; dueDate?: string }): Promise<ActionItemData> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.TICKETS_BY_ID(ticketId)}/postmortem/action-items`, item)
  return unwrapBiz<ActionItemData>(payload, '新建改进项失败')
}

export async function updateActionItem(itemId: number, status: string): Promise<ActionItemData> {
  const payload = await http.patch<unknown>(`${API_ENDPOINTS.TICKETS}/postmortem/action-items/${itemId}`, { status })
  return unwrapBiz<ActionItemData>(payload, '更新改进项失败')
}
