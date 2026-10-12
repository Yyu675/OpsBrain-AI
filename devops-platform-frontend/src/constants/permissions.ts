/**
 * 前端权限码字典（方向 F RBAC 的码表）。
 *
 * 后端不下发权限码（/auth/me 只有 role 字段），码表由前端生成，
 * 但**每个码的边界都以后端真实守卫为准**（2026-10-12 逐端点核查）：
 *
 * - `alert:write`     → POST /alerts/{id}/acknowledge|resolve：后端仅校验登录（⚪），
 *                        viewer 的只读**只有前端这一层防线**，所以前端必须拦。
 * - `ticket:write`    → 工单全部写端点：后端同样仅校验登录（⚪），同上。
 * - `knowledge:edit`  → 知识可逆写（建/改/发布/上传）：后端 KnowledgeWriteGuard
 *                        requireEdit() = ADMIN+OPS，前后端在此对齐。
 * - `knowledge:delete`/`governance`/`approvals:*` 等不可逆与管理面**不在 operator 表内**：
 *                        后端本就仅 ADMIN，前端导航/路由已按 roles:['admin'] 挡。
 *
 * 新增码时先到后端确认守卫实况（`@SaCheckRole` / KnowledgeWriteGuard / 裸放行⚪），
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
 * 与后端实况一一对应：这三类写操作后端对 OPS 放行
 * （前两类是「仅登录」放行，第三类是 requireEdit 显式含 OPS）。
 */
export const OPERATOR_PERMISSIONS: PermissionCode[] = [
  PERMISSIONS.alertWrite,
  PERMISSIONS.ticketWrite,
  PERMISSIONS.knowledgeEdit
]

/** VIEWER（后端无此角色的写守卫，全部靠前端拦）：空集合 = 只读 */
export const VIEWER_PERMISSIONS: PermissionCode[] = []
