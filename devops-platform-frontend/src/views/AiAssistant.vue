<script setup lang="ts">
/**
 * AI 助手（R0-2 方案 A：独立 AI 入口页）。
 *
 * 动机：chatStream 底座（意图路由/RAG/语义缓存/建单工具）此前只藏在
 * 工单表单与分析卡片里，普通用户没有任何独立对话入口——「AI 平台看不见 AI」。
 * 本页复用既有 SSE 五事件契约与 sessionId 多轮记忆，只补门面不加后端。
 *
 * 上下文直达：/assistant?ask=<预置问题> 自动发出第一条提问
 * （告警列表「问 AI」等上下文入口按此形态带问题跳转）。
 */
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Bot, Loader2, Plus, Send, Square, User } from 'lucide-vue-next'
import { useAiAssistant } from '@/composables/useAiAssistant'
import { safeMarkdown } from '@/utils/safeMarkdown'
import { notify } from '@/utils/notify'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'

const route = useRoute()
const { messages, streaming, ask, stop, newSession } = useAiAssistant()

const draft = ref('')
const listRef = ref<HTMLElement | null>(null)

const suggestions = [
  '生产数据库连接池耗尽怎么排查？',
  'K8s Pod 反复 CrashLoopBackOff 的处置步骤',
  '帮我创建一个 P1 工单：支付网关超时',
]

const scrollToBottom = async () => {
  await nextTick()
  listRef.value?.scrollTo({ top: listRef.value.scrollHeight, behavior: 'smooth' })
}

const send = async (text?: string) => {
  const q = (text ?? draft.value).trim()
  if (!q) return
  if (q.length > 1500) {
    notify.warning('提问不能超过 1500 字，长日志请先精简')
    return
  }
  draft.value = ''
  ask(q)
  await scrollToBottom()
}

const onKeydown = (e: KeyboardEvent) => {
  // Enter 发送、Shift+Enter 换行——与主流对话工具一致，不发明新键位
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

// AI 正文经 marked+DOMPurify 白名单净化（LLM 输出不可信，安全铁律）
const renderMarkdown = (id: number, text: string) => safeMarkdown(text, `ai-${id}`)

onMounted(async () => {
  const preset = typeof route.query.ask === 'string' ? route.query.ask.trim() : ''
  if (preset) {
    await send(preset.slice(0, 1500))
  } else {
    await scrollToBottom()
  }
})
</script>

<template>
  <div class="assistant-page">
    <div class="page-head">
      <div>
        <AppBreadcrumb :items="[{ label: 'AI 助手' }]" class="page-breadcrumb" />
        <h1 class="page-title">AI 助手</h1>
        <p class="page-subtitle">知识库问答 · 故障排查指引 · 一句话建单（回答基于 OpsBrain 知识库检索）</p>
      </div>
      <button class="btn-new" type="button" @click="newSession">
        <Plus :size="15" />
        新会话
      </button>
    </div>

    <div ref="listRef" class="msg-list">
      <div v-if="messages.length === 0" class="empty-state">
        <Bot :size="40" class="empty-icon" />
        <p class="empty-title">问我任何运维问题</p>
        <p class="empty-hint">回答基于团队知识库（RAG 检索 + 引用回链），需要时可代为创建工单</p>
        <div class="suggest-row">
          <button v-for="s in suggestions" :key="s" class="suggest-btn" type="button" @click="send(s)">
            {{ s }}
          </button>
        </div>
      </div>

      <div v-for="m in messages" :key="m.id" class="msg" :class="`msg--${m.role}`">
        <span class="msg-avatar" :class="m.role === 'user' ? 'msg-avatar--user' : 'msg-avatar--ai'">
          <User v-if="m.role === 'user'" :size="15" />
          <Bot v-else :size="15" />
        </span>
        <div class="msg-body">
          <div v-if="m.role === 'user'" class="msg-text">{{ m.text }}</div>
          <div v-else-if="m.text" class="msg-markdown" v-html="renderMarkdown(m.id, m.text)" />
          <div v-if="m.toolStatus" class="msg-tool"><Loader2 :size="13" class="spinning" />{{ m.toolStatus }}</div>
          <div v-if="m.error" class="msg-error">{{ m.error }}</div>
          <div v-if="m.citations?.length" class="msg-citations">
            <span class="citations-label">引用：</span>
            <span v-for="(c, i) in m.citations" :key="i" class="citation-chip">{{ c }}</span>
          </div>
          <div v-if="m.costRmb !== null" class="msg-meta">成本 ¥{{ m.costRmb.toFixed(4) }}</div>
        </div>
      </div>
    </div>

    <div class="composer">
      <textarea
        v-model="draft"
        class="composer-input"
        rows="2"
        placeholder="描述问题或粘贴关键报错（Enter 发送 / Shift+Enter 换行，上限 1500 字）"
        @keydown="onKeydown"
      />
      <button v-if="streaming" class="btn-plain composer-stop" type="button" @click="stop">
        <Square :size="14" />停止
      </button>
      <button
        v-else
        class="btn-create composer-send"
        type="button"
        :disabled="!draft.trim()"
        @click="send()"
      >
        <Send :size="14" />发送
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.assistant-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 96px);
  min-height: 480px;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.page-title { font-size: var(--text-xl); font-weight: var(--weight-semibold); color: var(--text-1); }
.page-subtitle { margin-top: 4px; font-size: var(--text-sm); color: var(--text-3); }

.msg-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  background: var(--surface-1);
}

.empty-state {
  margin: auto;
  text-align: center;
  max-width: 560px;
}
.empty-icon { color: var(--brand); }
.empty-title { margin-top: 8px; font-size: var(--text-lg); font-weight: var(--weight-semibold); color: var(--text-1); }
.empty-hint { margin-top: 4px; font-size: var(--text-sm); color: var(--text-3); }

.suggest-row { margin-top: var(--space-4); display: flex; flex-direction: column; gap: 8px; }
.suggest-btn {
  padding: 8px 12px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  background: var(--surface-2);
  color: var(--text-2);
  font-size: var(--text-sm);
  cursor: pointer;

  &:hover { border-color: var(--brand); color: var(--brand); }
}

.msg { display: flex; gap: 10px; max-width: 860px; }
.msg--user { align-self: flex-end; flex-direction: row-reverse; }

.msg-avatar {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;

  &--user { background: var(--brand-subtle); color: var(--brand); }
  &--ai { background: var(--surface-3); color: var(--text-1); }
}

.msg-body { min-width: 0; }
.msg-text {
  padding: 10px 14px;
  border-radius: var(--radius);
  background: var(--brand-subtle);
  color: var(--text-1);
  font-size: var(--text-sm);
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-markdown {
  padding: 10px 14px;
  border-radius: var(--radius);
  background: var(--surface-2);
  color: var(--text-1);
  font-size: var(--text-sm);
  line-height: 1.65;
  word-break: break-word;

  :deep(pre) {
    padding: 10px 12px;
    border-radius: var(--radius);
    background: var(--surface-3);
    overflow-x: auto;
    font-size: var(--text-xs);
  }
  :deep(code) { font-family: var(--font-mono); }
  :deep(table) { border-collapse: collapse; }
  :deep(th), :deep(td) { border: 1px solid var(--border-1); padding: 4px 8px; }
}

.msg-tool { margin-top: 6px; display: flex; align-items: center; gap: 6px; font-size: var(--text-xs); color: var(--text-3); }
.msg-error { margin-top: 6px; font-size: var(--text-sm); color: var(--danger); }
.msg-meta { margin-top: 6px; font-size: var(--text-xs); color: var(--text-3); }

.msg-citations { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.citations-label { font-size: var(--text-xs); color: var(--text-3); }
.citation-chip {
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--border-1);
  background: var(--surface-2);
  font-size: var(--text-xs);
  color: var(--text-2);
}

.composer {
  display: flex;
  align-items: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.composer-input {
  flex: 1;
  resize: none;
  padding: 10px 12px;
  border: 1px solid var(--border-2);
  border-radius: var(--radius);
  background: var(--surface-1);
  color: var(--text-1);
  font-size: var(--text-sm);
  font-family: inherit;

  &:focus { outline: none; border-color: var(--brand); }
}

.composer-send, .composer-stop { flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; }

/* 页内按钮（btn-new/btn-create 是各页 scoped 的惯例，无全局类） */
.btn-new, .btn-create {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-radius: var(--radius);
  font-size: var(--text-sm);
  cursor: pointer;
}
.btn-new {
  border: 1px solid var(--border-2);
  background: var(--surface-1);
  color: var(--text-1);
  &:hover { border-color: var(--brand); color: var(--brand); }
}
.btn-create {
  border: none;
  background: var(--brand);
  color: var(--text-inverse);
  &:hover:not(:disabled) { filter: brightness(1.05); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}
.btn-plain {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border: 1px solid var(--border-2);
  border-radius: var(--radius);
  background: var(--surface-1);
  color: var(--text-2);
  font-size: var(--text-sm);
  cursor: pointer;
  &:hover { color: var(--danger); border-color: var(--danger); }
}

.spinning { animation: spin 1s linear infinite; }
@media (prefers-reduced-motion: reduce) { .spinning { animation: none; } }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
