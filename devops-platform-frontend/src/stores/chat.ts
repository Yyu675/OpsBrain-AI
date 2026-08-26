import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { loadPersisted, savePersisted, clearPersisted, onPersistedChange } from '@/utils/persist'

/**
 * AI 对话消息
 */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: string
  /** 工具调用状态（检索/建单） */
  toolStatus?: {
    name: string
    status: 'start' | 'success' | 'error'
    message?: string
  }
  metadata?: {
    traceId?: string
    ticketId?: string
    costRmb?: number
    /** 引用文档列表（来源标注），随会话跨刷新保留 */
    citations?: string[]
    /** 该轮用户提问原文，用于「重新生成」 */
    sourceQuery?: string
  }
}

/**
 * 单个会话桶：一个工单（或 'global'）对应独立的消息列表与 sessionId
 */
interface SessionBucket {
  /** 三层记忆的多轮关联键 */
  sessionId: string
  /** 该会话的消息列表（独立于其他会话） */
  messages: ChatMessage[]
  /** 最近活跃时间戳（用于 LRU 淘汰） */
  lastActiveAt: number
}

/** localStorage 持久化载荷版本 */
const STORE_VERSION = 2
/** 'global' 伪键：全局抽屉专用会话，与任何工单会话隔离 */
const GLOBAL_KEY = 'global'
/** 最多保留多少个会话桶（超出按 LRU 淘汰最久未活跃的） */
const MAX_BUCKETS = 20

/**
 * AI 对话会话 Store
 *
 * 职责：跨页面共享对话历史与流式状态，使列表页与详情页进入同一会话。
 *
 * 会话模型（2026-08-14 重构）：
 * - 每个工单（ticketId）对应一个独立会话桶（SessionBucket），含独立 messages 与 sessionId
 * - 全局抽屉使用 'global' 伪键独立会话，与工单会话隔离
 * - 会话桶全量持久化到 localStorage（跨刷新保留），LSU 淘汰上限 MAX_BUCKETS
 * - 当前展示的会话由 activeTicketKey 指定，切换工单即切换会话
 * - 后端温记忆（sys_agent_session_summary）天然以 sessionId 为键，
 *   前端按工单隔离 sessionId 即让后端记忆按工单隔离
 */
export const useChatStore = defineStore('chat', () => {
  // ==================== 状态 ====================

  /**
   * 会话桶表：key = ticketId（或 'global'），value = SessionBucket
   * 持久化到 localStorage，跨刷新保留
   */
  const buckets = ref<Record<string, SessionBucket>>({})

  /** 当前激活的会话键（ticketId 或 'global'） */
  const activeTicketKey = ref<string>(GLOBAL_KEY)

  /** 当前正在流式输出的消息 id（用于定位追加 token 的目标） */
  const streamingId = ref<string | null>(null)

  /** 是否正在流式输出 */
  const isStreaming = ref(false)

  /** 当前请求的 traceId（仅供 UI 展示，不参与会话隔离） */
  const currentTraceId = ref('')

  // ==================== 持久化 ====================

  const PERSIST_KEY = 'chat-sessions'

  // 初始化：从 localStorage 恢复
  const restored = loadPersisted<Record<string, SessionBucket>>(PERSIST_KEY, STORE_VERSION)
  if (restored && typeof restored === 'object') {
    // 形状校验：确保每个桶都有 messages 和 sessionId
    const validated: Record<string, SessionBucket> = {}
    for (const [key, bucket] of Object.entries(restored)) {
      if (bucket && typeof bucket === 'object' && Array.isArray(bucket.messages) && bucket.sessionId) {
        validated[key] = bucket
      }
    }
    buckets.value = validated
  }

  /** 防抖保存到 localStorage，避免流式 token 高频写入打满 IO */
  const persist = (() => {
    let timer: ReturnType<typeof setTimeout> | null = null
    const flush = () => {
      savePersisted(PERSIST_KEY, buckets.value, STORE_VERSION)
    }
    const schedule = () => {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        timer = null
        flush()
      }, 400)
    }
    return { schedule, flush }
  })()

  // 深度监听桶表变化，触发防抖持久化
  watch(buckets, () => persist.schedule(), { deep: true })

  /**
   * LRU 淘汰：当桶数超过 MAX_BUCKETS 时，淘汰最久未活跃的桶
   * 'global' 桶永不被淘汰（全局抽屉是常用入口）
   */
  function evictIfNeeded() {
    const keys = Object.keys(buckets.value)
    if (keys.length <= MAX_BUCKETS) return
    // 按 lastActiveAt 升序，淘汰最久未活跃的非 global 桶
    const sorted = keys
      .filter(k => k !== GLOBAL_KEY)
      .sort((a, b) => (buckets.value[a].lastActiveAt ?? 0) - (buckets.value[b].lastActiveAt ?? 0))
    const toEvict = sorted.slice(0, keys.length - MAX_BUCKETS)
    for (const k of toEvict) {
      delete buckets.value[k]
    }
  }

  /** 跨标签页同步：其他标签页修改了 chat-sessions 时，本页拉取最新 */
  onPersistedChange((changedKey) => {
    if (changedKey !== PERSIST_KEY) return
    // 流式期间忽略跨 tab 覆盖：另一标签写入会整表替换 buckets，
    // 当前正在写入的 assistant 消息会被旧快照换掉，token 丢失。
    if (isStreaming.value) return
    const latest = loadPersisted<Record<string, SessionBucket>>(PERSIST_KEY, STORE_VERSION)
    if (latest && typeof latest === 'object') {
      buckets.value = latest
    }
  })

  // ==================== 派生状态 ====================

  /** 当前激活桶的消息列表（所有 UI 读写都经此入口） */
  const messages = computed<ChatMessage[]>({
    get() {
      const bucket = buckets.value[activeTicketKey.value]
      return bucket ? bucket.messages : []
    },
    set(val: ChatMessage[]) {
      const bucket = ensureBucket(activeTicketKey.value)
      bucket.messages = val
    }
  })

  /** 当前激活桶的 sessionId（三层记忆关联键） */
  const sessionId = computed<string>(() => {
    const bucket = buckets.value[activeTicketKey.value]
    return bucket ? bucket.sessionId : ''
  })

  /** 是否已有过真实对话（排除欢迎语） */
  const hasConversation = computed(() =>
    messages.value.some(m => m.role !== 'system')
  )

  /** 当前流式消息对象 */
  const streamingMessage = computed(() =>
    streamingId.value ? messages.value.find(m => m.id === streamingId.value) ?? null : null
  )

  /** 最后一条用户提问（供「重新生成」使用） */
  const lastUserQuery = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'user') return messages.value[i].content
    }
    return ''
  })

  // ==================== 动作 ====================

  const now = () =>
    new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })

  /** 生成会话 ID（时间戳 + 随机后缀，避免并发碰撞） */
  const genSessionId = () =>
    `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`

  /** 确保指定 key 的桶存在（不存在则创建），并刷新其最近活跃时间 */
  function ensureBucket(key: string): SessionBucket {
    if (!buckets.value[key]) {
      buckets.value[key] = {
        sessionId: genSessionId(),
        messages: [],
        lastActiveAt: Date.now()
      }
      evictIfNeeded()
    } else {
      buckets.value[key].lastActiveAt = Date.now()
    }
    return buckets.value[key]
  }

  /**
   * 确保存在会话 ID（幂等）。
   * @param ticketId 工单 ID；不传或为空时使用 'global' 伪键（全局抽屉）
   * @returns 当前激活会话的 sessionId
   *
   * 调用约定：
   * - 工单详情页的 AIContextPanel：chat.ensureSession(props.ticketId)
   * - 全局 AI 对话页 AiChatView：chat.ensureSession('global')
   * - 内部 ensureWelcome 调用前应先 setActive 切到目标 key
   */
  function ensureSession(ticketId?: string): string {
    const key = ticketId && ticketId.trim() ? ticketId.trim() : GLOBAL_KEY
    activeTicketKey.value = key
    const bucket = ensureBucket(key)
    return bucket.sessionId
  }

  /** 显式切换激活会话（进入某工单详情页时调用） */
  function setActive(ticketId?: string) {
    activeTicketKey.value = ticketId && ticketId.trim() ? ticketId.trim() : GLOBAL_KEY
    ensureBucket(activeTicketKey.value)
  }

  /** 首次打开时注入欢迎语（幂等，作用于当前激活会话） */
  function ensureWelcome() {
    ensureSession(activeTicketKey.value)
    if (messages.value.length > 0) return
    messages.value.push({
      id: 'welcome',
      role: 'system',
      content:
        '👋 你好！我是 AI 智能助手。\n\n' +
        '你可以用自然语言描述遇到的问题，我会检索知识库并给出带出处的解答，必要时自动创建工单。\n\n' +
        '例如：\n' +
        '• "生产环境 Redis 连接超时"\n' +
        '• "K8s Pod 频繁 OOMKilled 怎么排查"\n' +
        '• "帮我开个工单：MySQL 主从延迟，优先级 HIGH"',
      timestamp: now()
    })
  }

  /**
   * 消息 ID 生成器。
   *
   * ── 为什么不能只用 Date.now() ─────────────────────────────
   * 原实现是 `user-${Date.now()}` / `assistant-${Date.now()}`。
   * `Date.now()` 只有**毫秒**精度，而下面两种场景会在同一毫秒内
   * 连续建两条消息：
   *   1. 「重新生成」：removeMessage(旧回答) 紧接着 startAssistantMessage(新)；
   *   2. 发送时 pushUserMessage 之后立刻 startAssistantMessage。
   *
   * ID 撞车的后果不是显示乱，而是**操作打在错的消息上**：
   *   - `removeMessage(id)` 用 findIndex 取第一个匹配 → 删掉的可能是另一条；
   *   - `streamingMessage` 同样按 id 查找 → token 会追加到旧消息上。
   *
   * 加一个进程内单调递增序号即可根治，且仍保留时间前缀便于排序与排查。
   * （本问题由 ChatMode 的「重新生成」测试偶发失败暴露：
   *   同一毫秒内新旧两条 assistant 消息 ID 相同，删除删错了对象。）
   */
  let msgSeq = 0
  const nextMsgId = (role: 'user' | 'assistant') => `${role}-${Date.now()}-${++msgSeq}`

  /** 追加用户消息 */
  function pushUserMessage(content: string): ChatMessage {
    const msg: ChatMessage = {
      id: nextMsgId('user'),
      role: 'user',
      content,
      timestamp: now()
    }
    messages.value.push(msg)
    return msg
  }

  /** 创建 AI 响应占位消息并标记为流式目标 */
  function startAssistantMessage(sourceQuery: string): ChatMessage {
    const msg: ChatMessage = {
      id: nextMsgId('assistant'),
      role: 'assistant',
      content: '',
      timestamp: now(),
      metadata: { sourceQuery }
    }
    messages.value.push(msg)
    streamingId.value = msg.id
    isStreaming.value = true
    return msg
  }

  /** 追加流式 token */
  function appendToken(text: string) {
    const msg = streamingMessage.value
    if (msg) msg.content += text
  }

  /** 更新工具调用状态 */
  function setToolStatus(status: NonNullable<ChatMessage['toolStatus']>) {
    const msg = streamingMessage.value
    if (msg) msg.toolStatus = status
  }

  /** 合并元数据 */
  function mergeMetadata(patch: Partial<NonNullable<ChatMessage['metadata']>>) {
    const msg = streamingMessage.value
    if (msg) msg.metadata = { ...msg.metadata, ...patch }
  }

  /** 结束流式（成功或失败） */
  function finishStreaming(errorText?: string) {
    if (errorText) {
      const msg = streamingMessage.value
      if (msg) msg.content = errorText
    }
    streamingId.value = null
    isStreaming.value = false
    // 流式结束立即落盘，避免崩溃丢失
    persist.flush()
  }

  /** 移除指定消息（用于「重新生成」先删旧回答） */
  function removeMessage(id: string) {
    const idx = messages.value.findIndex(m => m.id === id)
    if (idx >= 0) messages.value.splice(idx, 1)
  }

  /**
   * 清空当前激活会话并开启新会话（重置 sessionId，切断记忆关联）
   * 注意：仅清空当前工单/全局会话，不影响其他工单会话
   */
  function clear() {
    const key = activeTicketKey.value
    const newSessionId = genSessionId()
    buckets.value[key] = {
      sessionId: newSessionId,
      messages: [],
      lastActiveAt: Date.now()
    }
    streamingId.value = null
    isStreaming.value = false
    currentTraceId.value = ''
    ensureWelcome()
    persist.flush()
  }

  /**
   * 清空**所有**会话桶并抹掉本地持久化（登出时调用）。
   *
   * 与 {@link clear} 的区别：clear 只重置当前激活会话，用于用户主动
   * 「开启新对话」；本方法针对的是「换人了」。
   *
   * 为什么必须做：对话历史全量落在 localStorage 的 `chat-sessions` 里，
   * 且**没有任何按用户隔离的键**。登出后不清，下一个在同一台机器登录的人
   * 打开 AI 助手就能直接看到上一个人的完整问答记录——
   * 其中包含 AI 从知识库检索出的原文引用（citations 字段随会话一起持久化），
   * 而知识库本身是有可见性分级的（PUBLIC / 内部）。
   * 这等于绕过后端刚做的权限域隔离，属于实打实的越权读取。
   *
   * 共享值守机、跨班交接同一终端在运维场景里非常普遍，不是极端假设。
   */
  function clearAll() {
    buckets.value = {}
    activeTicketKey.value = GLOBAL_KEY
    streamingId.value = null
    isStreaming.value = false
    currentTraceId.value = ''
    // 必须显式 clearPersisted 而非依赖 watch 写入空对象：
    // persist 是 400ms 防抖的，登出后页面可能立刻跳转/刷新，
    // 防抖回调来不及执行，磁盘上的旧会话就留下来了。
    clearPersisted(PERSIST_KEY)
  }

  return {
    // 状态
    messages,
    isStreaming,
    currentTraceId,
    streamingId,
    sessionId,
    activeTicketKey,
    buckets,
    // 派生
    hasConversation,
    streamingMessage,
    lastUserQuery,
    // 动作
    ensureSession,
    setActive,
    ensureWelcome,
    pushUserMessage,
    startAssistantMessage,
    appendToken,
    setToolStatus,
    mergeMetadata,
    finishStreaming,
    removeMessage,
    clear,
    clearAll
  }
})
