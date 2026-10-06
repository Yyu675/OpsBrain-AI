/**
 * 审计动作码人话字典（批87）。
 *
 * 码值单源 = 后端 AuditActionRegistry（35 登记码 + generic 兜底）。
 * 消费点：审计日志表 row.action、AgentTrace 写操作时间线 op.action——
 * 两处此前裸渲染 `knowledge.doc.create` 式点分码。
 *
 * 未知码原样透出不吞：后端加码先在页面上可见，再回来补表
 * （对着 AuditActionRegistry 抄，别自造码值）。
 */
const AUDIT_ACTION_LABELS: Record<string, string> = {
  'knowledge.doc.create': '新建知识文档',
  'knowledge.doc.update': '更新知识文档',
  'knowledge.doc.delete': '删除知识文档',
  'knowledge.doc.publish': '发布知识文档',
  'knowledge.doc.deprecate': '下线知识文档',
  'knowledge.doc.rollback': '回滚知识文档',
  'knowledge.category.create': '新建分类',
  'knowledge.category.update': '更新分类',
  'knowledge.category.delete': '删除分类',
  'knowledge.tag.create': '新建标签',
  'knowledge.tag.rename': '重命名标签',
  'knowledge.tag.merge': '合并标签',
  'knowledge.tag.delete': '删除标签',
  'ticket.create': '新建工单',
  'ticket.update': '更新工单',
  'ticket.status.change': '变更工单状态',
  'ticket.assign': '派单',
  'ticket.delete': '删除工单',
  'ticket.reply': '回复工单',
  'approval.approve': '批准审批',
  'approval.reject': '驳回审批',
  'alert.acknowledge': '确认告警',
  'alert.resolve': '标记告警恢复',
  'auth.login': '登录',
  'auth.logout': '登出',
  'governance.risk-policy.update': '更新风险等级策略',
  'governance.action.create': '新建白名单动作',
  'governance.action.update': '更新白名单动作',
  'governance.action.toggle': '启停白名单动作',
  'governance.policy.create': '新建自动化策略',
  'governance.policy.update': '更新自动化策略',
  'governance.policy.toggle': '启停自动化策略',
  'governance.policy.delete': '删除自动化策略',
  'governance.policy.dry-run': '演练自动化策略',
  'ai-channel.update': '更新模型渠道',
  generic: '其他操作'
}

/** 取动作中文标签：未知码原样透出 */
export const auditActionLabel = (action: string | null | undefined): string => {
  if (!action) return '—'
  return AUDIT_ACTION_LABELS[action] ?? action
}
