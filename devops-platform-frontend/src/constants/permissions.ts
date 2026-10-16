/**
 * 前端权限码字典（方向 F RBAC 的码表）。
 *
 * 后端不下发权限码（/auth/me 只有 role 字段），码表由前端生成，
 * 但**每个码的边界都以后端真实守卫为准**（2026-10-12 逐端点核查；同日 T3 后端收口）：
 *
 * - `alert:write`     → POST /alerts/{id}/acknowledge|resolve：OpsWriteGuard
 *                        requireWrite() = ADMIN+OPS（T3 前仅校验登录），前后端在此对齐。
 * - `ticket:write`    → 工单/复盘全部写端点：OpsWriteGuard requireWrite() = ADMIN+OPS；
 *                        物理删除（工单/附件）仅 ADMIN，不在本码内。前后端对齐。
 *                        例外：AI 分析「有用/没用」反馈人人可点（遥测非编辑，后端豁免）。
 * - `knowledge:edit`  → 知识可逆写（建/改/发布/上传）：后端 KnowledgeWriteGuard
 *                        requireEdit() = ADMIN+OPS，前后端在此对齐。
 * - `knowledge:delete`/`governance`/`approvals:*` 等不可逆与管理面**不在 operator 表内**：
 *                        后端本就仅 ADMIN，前端导航/路由已按 roles:['admin'] 挡。
 *
 * 新增码时先到后端确认守卫实况（`@SaCheckRole` / KnowledgeWriteGuard / OpsWriteGuard / 裸放行⚪），
 * 裸放行的端点前端是唯一防线，必须进码表；后端有真闸的，前端只按需做体验优化。
 */
export const PERMISSIONS = {
  alertWrite: 'alert:write',
  ticketWrite: 'ticket:write',
  knowledgeEdit: 'knowledge:edit'
} as const

// 类型刻意不导出：消费方走 store 的 hasPermission(codes: string[])，
// 导出空位只会诱导绕过码表直接用码（knip 零导出纪律，2026-10-12）
type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS]

/**
 * OPS（前端 operator）权限码集合。
 * 与后端守卫一一对应：三类写操作后端都显式含 OPS
 * （alert/ticket 走 OpsWriteGuard.requireWrite，知识走 requireEdit）。
 */
export const OPERATOR_PERMISSIONS: PermissionCode[] = [
  PERMISSIONS.alertWrite,
  PERMISSIONS.ticketWrite,
  PERMISSIONS.knowledgeEdit
]

/** VIEWER（后端 OpsWriteGuard 白名单外的未知角色一律拒写）：空集合 = 只读 */
export const VIEWER_PERMISSIONS: PermissionCode[] = []
