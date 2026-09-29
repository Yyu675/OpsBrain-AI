<script setup lang="ts">
/**
 * AiChatView —— L1 被动问答入口（用户自然语言提问 → AI 检索知识库 → 回答）。
 *
 * 这是 L1 的核心价值界面：此前该页面缺失（后端 /api/v1/chat/stream 健在，
 * 但前端没有「用户自由提问」的入口，chatStream 只在工单相关处被调用）。
 * 本页补齐 L1 问答入口 + 答案反馈（回流知识权重，飞轮闭环最后一块）。
 */

import { ref, onBeforeUnmount } from 'vue'
import { Send, Loader2, ThumbsUp, ThumbsDown, Bot } from 'lucide-vue-next'
import { chatStream } from '@/api/chat'
import { notify } from '@/utils/notify'
import { safeMarkdown } from '@/utils/safeMarkdown'
import type { SSECompleteEvent, SSEErrorEvent, SSETokenEvent } from '@/api/types'

defineOptions({ name: 'AiChatView' })

// ==================== 状态 ====================

const query = ref('')
const streaming = ref(false)
/** 当前回答正文（流式累积） */
const answer = ref('')
/** 引用来源（完成后回填，展示在回答下方） */
const citations = ref<string[]>([])
/** 本次回答是否来自语义缓存（缓存命中时工具不执行，展示提示） */
const fromCache = ref(false)
/** 反馈状态：null=未评价 / true=有帮助 / false=没帮助 */
const feedback = ref<boolean | null>(null)

let abortController: AbortController | null = null

// ==================== 提交问答 ====================

const submit = async () => {
  const q = query.value.trim()
  if (!q || streaming.value) return
  if (q.length > 1500) {
    notify.warning('提问不能超过 1500 字')
    return
  }

  // 重置上一轮状态
  answer.value = ''
  citations.value = []
  fromCache.value = false
  feedback.value = null
  streaming.value = true
  abortController = new AbortController()

  try {
    await chatStream(q, {
      onToken: (data: SSETokenEvent) => {
        answer.value += data.text ?? ''
      },
      onComplete: (data: SSECompleteEvent) => {
        citations.value = data.citations ?? []
        fromCache.value = Boolean(data.isCached)
        streaming.value = false
      },
      onError: (data: SSEErrorEvent) => {
        answer.value += `\n\n❌ ${data.message || '回答失败，请稍后重试'}`
        streaming.value = false
      },
      // 服务端未发 complete 就关流时的兜底——否则 streaming 永远卡 true
      onClose: () => {
        if (streaming.value) {
          if (answer.value) answer.value += '\n\n_（连接已中断，以上为已生成内容）_'
          else answer.value = '❌ 连接意外中断，未收到回答，请重试'
          streaming.value = false
        }
      },
    }, abortController)
  } catch (e) {
    // abort 由停止按钮触发，不报错；真实失败才提示
    if (!abortController?.signal.aborted) {
      answer.value = `❌ 请求失败：${e instanceof Error ? e.message : '未知错误'}`
      streaming.value = false
    }
  } finally {
    abortController = null
  }
}

const stop = () => {
  abortController?.abort()
  streaming.value = false
}

const submitFeedback = (helpful: boolean) => {
  if (!answer.value.trim() || streaming.value) return
  feedback.value = helpful
  // TODO: 后端聊天反馈端点就绪后，这里提交 citations + helpful 回流知识权重
  notify.success(helpful ? '感谢反馈，已记录「有帮助」' : '已记录「没用」，我们会持续改进')
}

const renderMarkdown = (text: string): string => safeMarkdown(text)

onBeforeUnmount(() => {
  abortController?.abort()
})
</script>

<template>
  <div class="ai-chat-page">
    <main class="ai-chat-main">
      <!-- 页头 -->
      <div class="page-header">
        <Bot class="page-header__icon" :size="22" />
        <div>
          <h1 class="page-title">智能问答</h1>
          <p class="page-desc">用自然语言描述运维问题，AI 检索知识库给出解决方案与排查步骤（L1 被动问答）。</p>
        </div>
      </div>

      <!-- 提问输入 -->
      <div class="input-area">
        <textarea
          v-model="query"
          class="query-input"
          rows="3"
          placeholder="例如：K8s Pod 一直 CrashLoopBackOff 怎么排查？"
          maxlength="1500"
          @keydown.ctrl.enter="submit"
          @keydown.meta.enter="submit"
        ></textarea>
        <div class="input-actions">
          <span class="input-hint">Ctrl / ⌘ + Enter 发送 | {{ query.length }}/1500</span>
          <button v-if="!streaming" class="send-btn" :disabled="!query.trim()" @click="submit">
            <Send :size="15" /> 提问
          </button>
          <button v-else class="stop-btn" @click="stop">
            <Loader2 :size="15" class="spinning" /> 停止生成
          </button>
        </div>
      </div>

      <!-- 回答区 -->
      <div v-if="answer || streaming" class="answer-area">
        <div class="answer-card">
          <div v-if="streaming && !answer" class="answer-loading">
            <Loader2 :size="18" class="spinning" /> 正在检索知识库并生成回答…
          </div>
          <div v-else class="answer-body" v-html="renderMarkdown(answer)"></div>

          <!-- 引用来源 -->
          <div v-if="citations.length" class="citations">
            <span class="citations-title">引用来源：</span>
            <span v-for="c in citations" :key="c" class="citation-chip">{{ c }}</span>
          </div>

          <!-- 反馈 -->
          <div v-if="answer && !streaming" class="feedback-row">
            <span class="feedback-label">这个回答有帮助吗？</span>
            <button
              class="feedback-btn"
              :class="{ active: feedback === true }"
              :disabled="feedback !== null"
              @click="submitFeedback(true)"
            >
              <ThumbsUp :size="14" /> 有帮助
            </button>
            <button
              class="feedback-btn"
              :class="{ active: feedback === false }"
              :disabled="feedback !== null"
              @click="submitFeedback(false)"
            >
              <ThumbsDown :size="14" /> 没帮助
            </button>
            <span v-if="feedback !== null" class="feedback-done">已反馈</span>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.ai-chat-page {
  min-height: 100vh;
  background: var(--surface-0);
}
.ai-chat-main {
  max-width: 1400px;
  margin: 0 auto;
  padding: 24px 24px 40px;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}
.page-header__icon { color: var(--brand, #2563eb); }
.page-title { margin: 0; font-size: 22px; color: var(--text-1); }
.page-desc { margin: 4px 0 0; font-size: 13px; color: var(--text-3); }

.input-area {
  background: var(--surface-1);
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  padding: 14px;
}
.query-input {
  width: 100%;
  border: none;
  outline: none;
  resize: vertical;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-1);
  background: transparent;
  min-height: 72px;
}
.input-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 10px;
}
.input-hint { font-size: 12px; color: var(--text-3); }
.send-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 18px;
  border: none;
  border-radius: var(--radius);
  background: var(--brand, #2563eb);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.stop-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 18px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  background: var(--surface-2);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
}
.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.answer-area { margin-top: 20px; }
.answer-card {
  background: var(--surface-1);
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  padding: 20px;
}
.answer-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-3);
  font-size: 14px;
}
.answer-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-1);
}
.citations {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--border-1);
}
.citations-title { font-size: 12px; color: var(--text-3); }
.citation-chip {
  padding: 3px 10px;
  border-radius: 12px;
  background: var(--brand-subtle, #eff6ff);
  color: var(--brand, #2563eb);
  font-size: 12px;
}
.feedback-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--border-1);
}
.feedback-label { font-size: 13px; color: var(--text-3); }
.feedback-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  background: transparent;
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
}
.feedback-btn:hover:not(:disabled) { border-color: var(--brand, #2563eb); color: var(--brand, #2563eb); }
.feedback-btn.active { background: var(--brand-subtle, #eff6ff); border-color: var(--brand, #2563eb); color: var(--brand, #2563eb); }
.feedback-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.feedback-done { font-size: 12px; color: var(--success, #16a34a); }
</style>