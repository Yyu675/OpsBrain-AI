/**
 * 工单 API - 工单管理接口
 *
 * 重新导出服务层接口。视图与 store 统一从此处导入，
 * 不直接依赖 services/ticket.service —— 便于将来替换实现层。
 */

export {
  fetchTickets,
  fetchTicketById,
  fetchTicketStats,
  createTicket,
  updateTicket,
  updateTicketStatus,
  transferTicket,
  // B1 首响 / 升级 / SLA 风险
  acknowledgeTicket,
  escalateTicket,
  fetchSlaAtRisk,
  fetchFirstResponseStats,
  // B2 现场处置
  addTicketAction,
  fetchTicketActions,
  updateTicketStage,
  markTicketMitigated,
  type TicketActionRecord,
  // B3 根因 + 验证
  confirmRootCause,
  submitVerification,
  skipVerification,
  // B4 复盘
  getPostmortem,
  savePostmortem,
  generateTimelineDraft,
  listActionItems,
  addActionItem,
  updateActionItem,
  type PostmortemData,
  type ActionItemData,
  deleteTicket,
  // 回复与活动流（此前仅存 Pinia 内存，刷新即丢失）
  fetchTicketReplies,
  addTicketReply,
  fetchTicketActivities,
  // 标签（此前由前端凭 module 编造）
  replaceTicketTags,
  fetchHotTags,
  // CSV 导出（按当前筛选条件拉全量）
  exportTicketsCsv,
  // 附件（MinIO 对象存储；此前是假的占位下载）
  uploadTicketAttachment,
  fetchTicketAttachments,
  fetchAttachmentDownloadUrl,
  deleteTicketAttachment
} from './services/ticket.service'
export type { TicketAttachmentMeta } from './services/ticket.service'
export type { FrontendTicket, BackendTicket } from './types/ticket'
