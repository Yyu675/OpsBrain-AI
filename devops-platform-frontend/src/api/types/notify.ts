/**
 * 通知渠道配置类型（批89 · 与后端 NotifyChannelService record 对齐——
 * fieldContract 字段集对账纳管对象，字段增删改名由闸三点名）。
 */

/** 单渠道视图（打码；明文只走 reveal 端点）。 */
export interface NotifyChannelView {
  channelKey: string
  enabled: boolean
  configured: boolean
  /** 配置来源：db（页面保存过=DB 权威）/ yml（环境变量）/ none */
  source: string
  /** webhook 打码（host+path 可见、query 参数值打码） */
  webhookMasked: string | null
  hasSecret: boolean
  /** DB 密文解不开（主密钥变更）→ 页面标黄提示重新保存 */
  needsReEntry: boolean
  updatedBy: string | null
  updateTime: string | null
}

/** 告警接入 secret 视图（只读打码；修改走 PUT /ingest-secret）。 */
export interface IngestSecretView {
  configured: boolean
  source: string
  secretMasked: string | null
  hmacConfigured: boolean
  /** 修改副作用提示（同步 Alertmanager） */
  hint: string
}

/** 整屏视图：两渠道 + 告警接入 secret + 主密钥配置态。 */
export interface NotifyChannelList {
  channels: NotifyChannelView[]
  ingestSecret: IngestSecretView
  /** false = MODEL_KEY_CRYPT_SECRET 未配，所有 webhook/密钥以明文落库（页面挂警告横幅） */
  keyCryptConfigured: boolean
}

/** 测试发送结果（走 NotifyPayloads 单源构造，探针绿=真实消息格式也对）。 */
export interface NotifyTestResult {
  success: boolean
  message: string
  latencyMs: number
}

/** reveal 明文结果（ADMIN 端点 + 访问日志留痕）。 */
export interface NotifySecretRevealed {
  channelKey: string
  webhook: string | null
  secret: string | null
}

/** 保存渠道请求（null=不动该字段；""=清空；非空=覆盖——三态语义）。 */
export interface UpdateNotifyChannelPayload {
  enabled?: boolean | null
  webhook?: string | null
  secret?: string | null
}
