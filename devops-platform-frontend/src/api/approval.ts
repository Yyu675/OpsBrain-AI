/**
 * 审批中心 API（方向 D：L3 人机协同审批）
 *
 * 全部端点后端限 ADMIN 角色（@SaCheckRole("ADMIN")）。非管理员调用返回 403。
 */
import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz } from '../utils/http'

/** 审批单（对齐后端 ApprovalRequest） */
export interface ApprovalRequest {
  id: number
  actionType: string
  toolName: string | null
  riskLevel: string
  summary: string
  payload: string | null
  requester: string
  traceId: string | null
  sessionId: string | null
  status: string          // PENDING/APPROVED/REJECTED/EXPIRED/EXECUTED/EXECUTE_FAILED
  approver: string | null
  decidedAt: string | null
  decisionReason: string | null
  expiresAt: string | null
  executedAt: string | null
  executeResult: string | null
  createTime: string
  updateTime: string
}

export interface ApprovalPage {
  items: ApprovalRequest[]
  total: number
  page: number
  size: number
  totalPages: number
}

/** 查询审批列表。status: PENDING（默认）/ APPROVED / REJECTED / EXECUTED / ALL */
export async function listApprovals(status = 'PENDING', page = 1, size = 20): Promise<ApprovalPage> {
  const url = `${API_ENDPOINTS.APPROVALS}?status=${encodeURIComponent(status)}&page=${page}&size=${size}`
  const payload = await http.get<unknown>(url)
  return unwrapBiz<ApprovalPage>(payload, '查询审批列表失败')
}

/** 待审数量（角标） */
export async function pendingCount(): Promise<number> {
  const url = `${API_ENDPOINTS.APPROVALS}/pending/count`
  const payload = await http.get<unknown>(url)
  const data = unwrapBiz<{ pending: number }>(payload, '查询待审数失败')
  return data?.pending ?? 0
}

/** 审批单详情 */
export async function getApproval(id: number): Promise<ApprovalRequest> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.APPROVALS}/${id}`)
  return unwrapBiz<ApprovalRequest>(payload, '查询审批单失败')
}

/** 批准并执行（reason 可选） */
export async function approveApproval(id: number, reason?: string): Promise<ApprovalRequest> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.APPROVALS}/${id}/approve`, { reason: reason ?? '' })
  return unwrapBiz<ApprovalRequest>(payload, '批准失败')
}

/** 驳回（reason 必填） */
export async function rejectApproval(id: number, reason: string): Promise<ApprovalRequest> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.APPROVALS}/${id}/reject`, { reason })
  return unwrapBiz<ApprovalRequest>(payload, '驳回失败')
}
