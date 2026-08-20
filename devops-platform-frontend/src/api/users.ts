/**
 * 团队成员 API - 工单负责人名录
 *
 * A2：此前前端硬编码 ASSIGNEE_OPTIONS = ['张明','李四','王五','赵六','孙七','周八','待分配']
 * 是编造名单——库里只有「张明」一个真实负责人。用户选人后写入工单 assignee 自由文本字段，
 * 导致工单被指派给不存在的人，且筛选下拉框恒定七项不随真实数据变化。
 */

import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz } from '../utils/http'
import type { TeamMember } from './types'

/**
 * 查询可指派成员名录
 *
 * @param includeDisabled 是否包含已停用成员，默认 false
 * @returns 成员列表（含 activeTicketCount 负载）
 */
export async function fetchTeamMembers(includeDisabled = false): Promise<TeamMember[]> {
  const url = includeDisabled
    ? `${API_ENDPOINTS.USERS}?includeDisabled=true`
    : API_ENDPOINTS.USERS

  const payload = await http.get(url)
  const data = unwrapBiz<{ total: number; users: TeamMember[] }>(payload, '查询成员名录失败')
  return Array.isArray(data?.users) ? data.users : []
}
