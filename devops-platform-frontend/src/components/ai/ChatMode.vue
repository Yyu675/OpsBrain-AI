<script setup lang="ts">
import { notify } from '@/utils/notify'
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import {
  Send, Bot, User, Loader, CheckCircle, AlertCircle, Wrench,
  Copy, RefreshCw, Square, Trash2, FileText, ChevronRight
} from 'lucide-vue-next'
import { chatStream } from '@/api/chat'
import { safeMarkdown } from '@/utils/safeMarkdown'
import type { SSEStartEvent, SSEToolStatusEvent, SSETokenEvent, SSECompleteEvent, SSEErrorEvent } from '@/api/types'
import { useChatStore, type ChatMessage } from '@/stores/chat'
import { copyText } from '@/utils/clipboard'
import { errorMessage, isAbortLike } from '@/utils/errors'

/**
 * 对话模式（AI 独立对话页 AiChatView 的对话问答子模式）
 *
 * 消息列表 + 输入区 + SSE 流式逻辑。会话沿用「global」伪键，
 * 与工单详情页 AI 智能分析（AnalysisCard）按工单隔离的会话互不干扰
 * （见 stores/chat.ts 会话桶模型）。
 *
 * 本组件只发 ticket-created 事件，不自行刷新列表——刷新策略由使用方
 * 按自身筛选上下文决定。
 */
const emit = defineEmits<{
  'ticket-created': [ticketId: string]
}>()

const chat = useChatStore()

const inputText = ref('')
const messagesContainer = ref<HTMLElement>()

/** 中断控制器（支持停止生成） */
let abortController: AbortController | null = null

const canSend = computed(() => {
  const text = inputText.value.trim()
  return text.length > 0 && text.length <= 1500 && !chat.isStreaming
})

// ==================== Markdown 渲染 ====================

/** 安全渲染 Markdown（统一走 safeMarkdown，带缓存优化，按 msg id + length 缓存） */
const renderMarkdown = (text: string, cacheKey?: string): string => {
  return safeMarkdown(text, cacheKey)
}

// ==================== 滚动 + 代码块装饰 ====================

const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    // 装饰代码块：给 <pre> 加 data-language 属性供 CSS ::before 显示语言标签
    messagesContainer.value.querySelectorAll('pre').forEach(pre => {
      if (pre.hasAttribute('data-language')) return
      const code = pre.querySelector('code')
      const lang = Array.from(code?.classList ?? [])
        .find(name => name.startsWith('language-'))
        ?.slice('language-'.length)
      pre.setAttribute('data-language', (lang || 'TEXT').toUpperCase())
    })
  }
}

// ==================== 清空 ====================

const clearChat = () => {
  if (chat.isStreaming) {
    notify.warning('AI 正在生成中，无法清空')
    return
  }
  chat.clear()
  inputText.value = ''
  notify.success('已清空对话历史')
}

// ==================== 发送 / 停止 / 重新生成 ====================

/** 执行一次流式问答（复用于发送与重新生成） */
const runStream = async (query: string) => {
  chat.startAssistantMessage(query)
  scrollToBottom()

  abortController = new AbortController()

  try {
    await chatStream(
      query,
      {
        onStart: (data: SSEStartEvent) => {
          chat.currentTraceId = data.traceId
          chat.mergeMetadata({ traceId: data.traceId })
        },

        onToolStatus: (data: SSEToolStatusEvent) => {
          chat.setToolStatus({
            name: data.toolName,
            status: data.status,
            message: data.message
          })
          scrollToBottom()
        },

        onToken: (data: SSETokenEvent) => {
          chat.appendToken(data.text)
          scrollToBottom()
        },

        onComplete: async (data: SSECompleteEvent) => {
          chat.mergeMetadata({ costRmb: data.costRmb })

          // 识别工单创建结果
          if (data.toolResults) {
            const ticketTool = data.toolResults.find(t => t.toolName === 'createDevOpsTicket')
            if (ticketTool?.result) {
              try {
                // result 声明为 unknown（形状随工具而异），此处按 createDevOpsTicket 的契约窄化
                const parsed: unknown = typeof ticketTool.result === 'string'
                  ? JSON.parse(ticketTool.result)
                  : ticketTool.result
                const ticketId = (parsed as { ticketId?: string } | null)?.ticketId

                if (ticketId) {
                  chat.mergeMetadata({ ticketId })
                  emit('ticket-created', ticketId)
                  notify.success(`工单 ${ticketId} 创建成功！`, { duration: 5000 })
                }
              } catch (e) {
                console.error('解析工单结果失败:', e)
              }
            }
          }

          // 引用持久化：优先取 complete.citations，为空则从回答文本回退提取（语义缓存命中时工具不执行、后端无 citations）
          const citeMatches = (chat.streamingMessage?.content ?? '').match(/【来源：[^】]+】/g) ?? []
          const cites = (data.citations && data.citations.length ? data.citations : citeMatches.map(s => s.trim())).filter(Boolean)
          const uniq = [...new Set(cites)]
          if (uniq.length) chat.mergeMetadata({ citations: uniq })

          chat.finishStreaming()
          scrollToBottom()
        },

        onError: (data: SSEErrorEvent) => {
          chat.finishStreaming(`❌ ${data.message || '请求失败，请稍后重试'}`)
          notify.error(data.message || '请求失败，请稍后重试')
        },

        /**
         * 流正常结束但**没收到 complete 事件**时的兜底。
         *
         * fetchEventSource 在服务端关闭流时会正常 resolve——不抛错、不进 catch。
         * 若此前既没有 complete 也没有 error（后端超时切断、网关 502 断流、
         * Nginx proxy_read_timeout 到期都会这样），isStreaming 就永远停在 true：
         *   - 输入框 :disabled="chat.isStreaming" → 一直禁用
         *   - 发送按钮被「停止生成」替换，而此时 abortController 已置空，
         *     stopGeneration 直接 return —— **点了没有任何反应**
         * 结果是整个对话框彻底卡死，用户只能刷新页面。
         *
         * 这里按「是否已有内容」区分收尾文案：有内容说明是中途断流，
         * 保留已生成部分并注明；完全没内容则如实说明没收到回答。
         */
        onClose: () => {
          if (!chat.isStreaming) return   // 已由 complete/error 正常收尾
          const partial = chat.streamingMessage?.content ?? ''
          chat.finishStreaming(
            partial
              ? `${partial}\n\n_（连接已中断，以上为已生成内容）_`
              : '❌ 连接意外中断，未收到回答，请重试'
          )
          notify.warning('连接中断，回答可能不完整')
        }
      },
      abortController,
      chat.ensureSession('global')   // 三层记忆：全局抽屉专用会话（'global' 伪键）
    )
  } catch (error: unknown) {
    // 主动中断不算错误
    if (isAbortLike(error) || abortController?.signal.aborted) {
      const msg = chat.streamingMessage
      const partial = msg?.content ?? ''
      chat.finishStreaming(partial ? `${partial}\n\n_（已停止生成）_` : '_（已停止生成）_')
      return
    }
    chat.finishStreaming(`❌ 连接失败：${errorMessage(error)}`)
    notify.error('连接失败，请检查网络或稍后重试')
  } finally {
    abortController = null
  }
}

const sendMessage = async () => {
  if (!canSend.value) return
  const userText = inputText.value.trim()
  inputText.value = ''
  chat.pushUserMessage(userText)
  scrollToBottom()
  await runStream(userText)
}

/** 停止生成 */
const stopGeneration = () => {
  if (!chat.isStreaming || !abortController) return
  abortController.abort()
  notify.info('已停止生成')
}

/** 重新生成：删掉该条回答，用其原始提问重跑 */
const regenerate = async (msg: ChatMessage) => {
  if (chat.isStreaming) {
    notify.warning('AI 正在生成中，请稍候')
    return
  }
  const query = msg.metadata?.sourceQuery || chat.lastUserQuery
  if (!query) {
    notify.warning('找不到原始提问，无法重新生成')
    return
  }
  chat.removeMessage(msg.id)
  await runStream(query)
}

/** 复制消息内容 */
const copyMessage = async (content: string) => {
  const ok = await copyText(content)
  if (ok) notify.success('已复制到剪贴板')
  else notify.warning('复制失败，请手动选择文本')
}

// ==================== 输入交互 ====================

const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

// ==================== 挂载时初始化 ====================

onMounted(() => {
  // 全局抽屉使用 'global' 伪键独立会话，与任何工单会话隔离
  chat.setActive('global')
  chat.ensureWelcome()
  scrollToBottom()
})

/**
 * 卸载时中止 SSE 并**收尾流式状态**。
 *
 * 只 abort 是不够的：abort 会让 chatStream 的 promise 走 catch 分支，
 * 但组件已卸载、那段 catch 里的 finishStreaming 未必来得及执行；
 * 而 isStreaming 存在 store（跨组件共享），切走再回来输入框仍是禁用态。
 * 故这里显式收尾，与 onClose 的兜底同一目的。
 */
onBeforeUnmount(() => {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  if (chat.isStreaming) {
    const partial = chat.streamingMessage?.content ?? ''
    chat.finishStreaming(partial ? `${partial}\n\n_（已停止生成）_` : '_（已停止生成）_')
  }
})
</script>

<template>
  <div class="chat-mode">
    <!-- 消息列表 -->
    <div ref="messagesContainer" class="messages-list">
      <div
        v-for="msg in chat.messages"
        :key="msg.id"
        class="message-item"
        :class="`message-${msg.role}`"
      >
        <div class="message-avatar">
          <Bot v-if="msg.role === 'assistant' || msg.role === 'system'" :size="18" />
          <User v-else :size="18" />
        </div>

        <div class="message-content">
          <div class="message-bubble">
            <!-- 工具调用状态 -->
            <div v-if="msg.toolStatus" class="tool-status">
              <Loader v-if="msg.toolStatus.status === 'start'" :size="14" class="tool-icon tool-loading" />
              <CheckCircle v-else-if="msg.toolStatus.status === 'success'" :size="14" class="tool-icon tool-success" />
              <AlertCircle v-else :size="14" class="tool-icon tool-error" />
              <span class="tool-text">
                {{ msg.toolStatus.message || `工具调用：${msg.toolStatus.name}` }}
              </span>
            </div>

            <!-- 消息正文：AI 消息渲染 Markdown，用户消息保留纯文本 -->
            <div
              v-if="msg.role === 'assistant'"
              class="message-text markdown-body"
              v-html="renderMarkdown(msg.content, msg.id)"
            ></div>
            <div v-else class="message-text">{{ msg.content }}</div>

            <!-- 引用来源（随会话跨刷新保留）：优先 complete.citations，语义缓存命中时从回答文本回退提取 -->
            <div v-if="msg.metadata?.citations?.length" class="citations-section">
              <div class="citations-header">
                <FileText :size="12" />
                <span>引用来源</span>
              </div>
              <div class="citations-list">
                <div v-for="(cite, ci) in msg.metadata.citations" :key="ci" class="citation-item">
                  <ChevronRight :size="12" class="citation-chevron" />
                  <span class="citation-text markdown-body" v-html="renderMarkdown(cite)"></span>
                </div>
              </div>
            </div>

            <!-- 工单创建成功 -->
            <div v-if="msg.metadata?.ticketId" class="ticket-created">
              <Wrench :size="14" />
              <span>工单已创建：</span>
              <RouterLink :to="`/tickets/${msg.metadata.ticketId}`" class="ticket-link">
                {{ msg.metadata.ticketId }}
              </RouterLink>
            </div>

            <!-- 元信息 + 操作 -->
            <div class="message-footer">
              <div class="message-meta">
                <span class="message-time">{{ msg.timestamp }}</span>
                <span v-if="msg.metadata?.costRmb" class="message-cost">
                  ¥{{ msg.metadata.costRmb.toFixed(4) }}
                </span>
                <span v-if="msg.metadata?.traceId" class="message-trace" :title="`traceId: ${msg.metadata.traceId}`">
                  {{ msg.metadata.traceId }}
                </span>
              </div>

              <!-- 消息操作（AI 消息且非流式中才显示） -->
              <div
                v-if="msg.role === 'assistant' && msg.content && chat.streamingId !== msg.id"
                class="message-actions"
              >
                <button class="msg-action" title="复制" @click="copyMessage(msg.content)">
                  <Copy :size="13" />
                </button>
                <button class="msg-action" title="重新生成" @click="regenerate(msg)">
                  <RefreshCw :size="13" />
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 打字指示器 -->
      <div v-if="chat.isStreaming && !chat.streamingMessage?.content" class="typing-indicator">
        <span></span><span></span><span></span>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="chat-input">
      <textarea
        v-model="inputText"
        :disabled="chat.isStreaming"
        :maxlength="1500"
        placeholder="描述您遇到的问题…（Enter 发送，Shift+Enter 换行）"
        rows="3"
        class="input-textarea"
        @keydown="handleKeydown"
      />
      <div class="input-footer">
        <span class="input-hint">{{ inputText.length }} / 1500</span>
        <div class="input-buttons">
          <button v-if="chat.hasConversation && !chat.isStreaming" class="clear-btn" @click="clearChat">
            <Trash2 :size="14" />
            清空
          </button>
          <button v-if="chat.isStreaming" class="stop-btn" @click="stopGeneration">
            <Square :size="14" />
            停止生成
          </button>
          <button v-else :disabled="!canSend" class="send-btn" @click="sendMessage">
            <Send :size="16" />
            发送
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.chat-mode {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--color-bg);
}

/* 消息列表 */
.messages-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;

  &.message-user { flex-direction: row-reverse; }
}

.message-avatar {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--color-primary-lighter);
  color: var(--color-primary);

  .message-user & {
    background: var(--color-bg-sunken);
    color: var(--color-text-secondary);
  }
}

.message-content { flex: 1; min-width: 0; }

.message-bubble {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  padding: 12px 14px;
  box-shadow: var(--shadow-sm);

  .message-user & {
    background: var(--color-primary);
    border-color: var(--color-primary);
  }

  .message-system & {
    background: var(--color-primary-lighter);
    border-color: var(--color-primary-lighter);
  }
}

/* 工具状态条 */
.tool-status {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  margin-bottom: 10px;
  background: var(--color-bg-sunken);
  border-radius: var(--radius-md);
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.tool-icon {
  flex-shrink: 0;

  &.tool-loading { animation: spin 1s linear infinite; color: var(--color-primary); }
  &.tool-success { color: var(--state-success); }
  &.tool-error { color: var(--state-error); }
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.tool-text { font-weight: var(--weight-medium); }

/* 消息正文 */
.message-text {
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  color: var(--color-text-primary);
  white-space: pre-wrap;
  word-break: break-word;

  .message-user & { color: white; white-space: pre-wrap; }
  .message-system & { color: var(--color-text-secondary); }
}

/* Markdown 渲染样式 */
.markdown-body {
  white-space: normal;

  :deep(p) { margin: 0 0 8px; &:last-child { margin-bottom: 0; } }
  :deep(h1), :deep(h2), :deep(h3), :deep(h4) {
    margin: 12px 0 6px;
    font-size: var(--text-sm);
    font-weight: var(--weight-semibold);
    color: var(--color-text-primary);
    &:first-child { margin-top: 0; }
  }
  :deep(ul), :deep(ol) { margin: 0 0 8px; padding-left: 20px; }
  :deep(li) { margin-bottom: 3px; }
  :deep(strong) { font-weight: var(--weight-semibold); color: var(--color-text-primary); }
  :deep(a) { color: var(--color-primary); text-decoration: underline; }
  :deep(hr) { border: none; border-top: 1px solid var(--color-border-light); margin: 10px 0; }
  :deep(blockquote) {
    margin: 8px 0;
    padding: 6px 12px;
    border-left: 3px solid var(--color-primary-light);
    background: var(--color-primary-lighter);
    color: var(--color-text-secondary);
  }

  /* 行内代码 */
  :deep(code) {
    padding: 2px 6px;
    background: var(--color-bg-sunken);
    color: var(--color-primary);
    border-radius: var(--radius-sm);
    font-family: var(--font-mono);
    font-size: 12px;
  }

  /* 代码块 */
  :deep(pre) {
    margin: 8px 0;
    padding: 30px 12px 10px;
    background: #1E293B;
    border-radius: var(--radius-md);
    overflow-x: auto;
    position: relative;

    code {
      display: block;
      padding: 0;
      background: transparent;
      color: var(--border-1);
      font-size: 12px;
      line-height: 1.6;
    }

    &::before {
      content: attr(data-language);
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      height: 22px;
      display: flex;
      align-items: center;
      padding: 0 12px;
      box-sizing: border-box;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
      color: rgba(226, 232, 240, 0.5);
      font-family: var(--font-body);
      font-size: 10px;
      font-weight: 600;
    }
  }

  /* 表格 */
  :deep(table) {
    width: 100%;
    margin: 8px 0;
    border-collapse: collapse;
    font-size: var(--text-xs);
  }
  :deep(th), :deep(td) {
    padding: 6px 8px;
    border: 1px solid var(--color-border-light);
    text-align: left;
  }
  :deep(th) { background: var(--color-bg-sunken); font-weight: var(--weight-semibold); }
}

/* 引用来源 */
.citations-section {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--color-border-light);
}

.citations-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  color: var(--color-text-secondary);
}

.citations-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.citation-item {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.citation-chevron {
  flex-shrink: 0;
  color: var(--color-text-tertiary);
  margin-top: 2px;
}

.citation-text {
  min-width: 0;
  line-height: 1.5;
}

/* 工单创建提示 */
.ticket-created {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--color-border-light);
  font-size: var(--text-xs);
  color: var(--state-success);
}

.ticket-link {
  font-weight: var(--weight-medium);
  color: var(--color-primary);
  text-decoration: none;
  font-family: var(--font-mono);

  &:hover { text-decoration: underline; }
}

/* 页脚：元信息 + 操作 */
.message-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.message-meta {
  display: flex;
  gap: 10px;
  font-size: 11px;
  color: var(--color-text-tertiary);
  flex-wrap: wrap;

  .message-user & { color: rgba(255, 255, 255, 0.7); }
}

.message-time { font-weight: var(--weight-medium); }
.message-cost { font-family: var(--font-mono); }
.message-trace {
  font-family: var(--font-mono);
  opacity: 0.6;
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;

  .message-bubble:hover & { opacity: 1; }
}

.msg-action {
  width: 24px;
  height: 24px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;

  &:hover { background: var(--color-primary-lighter); color: var(--color-primary); }
}

/* 打字指示器 */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 12px 16px;
  margin-left: 42px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  width: fit-content;
  box-shadow: var(--shadow-sm);

  span {
    width: 7px;
    height: 7px;
    border-radius: var(--radius-full);
    background: var(--color-text-tertiary);
    animation: typing 1.4s infinite;

    &:nth-child(2) { animation-delay: 0.2s; }
    &:nth-child(3) { animation-delay: 0.4s; }
  }
}

@keyframes typing {
  0%, 60%, 100% { opacity: 0.3; transform: translateY(0); }
  30% { opacity: 1; transform: translateY(-4px); }
}

/* 输入区 */
.chat-input {
  border-top: 1px solid var(--color-border-light);
  padding: 14px 20px;
  background: var(--color-surface);
}

.input-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  line-height: var(--leading-normal);
  color: var(--color-text-primary);
  background: var(--color-bg);
  resize: none;
  outline: none;
  transition: border-color 0.15s ease;
  box-sizing: border-box;

  &:focus { border-color: var(--color-primary); }
  &::placeholder { color: var(--color-text-tertiary); }
  &:disabled { opacity: 0.6; cursor: not-allowed; }
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
}

.input-hint {
  font-size: 11px;
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
}

.input-buttons { display: flex; gap: 8px; }

.clear-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 8px 14px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover { border-color: var(--state-error); color: var(--state-error); }
}

.send-btn,
.stop-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;
}

.send-btn {
  background: var(--color-primary);
  color: white;

  &:hover:not(:disabled) { background: var(--color-primary-light); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.stop-btn {
  background: var(--state-error-bg);
  color: var(--state-error);
  border: 1px solid var(--state-error);

  &:hover { background: var(--state-error); color: white; }
}
</style>
