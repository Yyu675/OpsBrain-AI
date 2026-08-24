<script setup lang="ts">
/**
 * 结构化分析卡片 — 嵌入工单详情页左栏时间线作为 AI 分析建议节点
 *
 * 渲染 useTicketAnalysis 的结构化结果：
 * - 可能原因（有序列表）
 * - 排查命令（代码块 + 复制按钮）
 * - 置信度标签
 * - 降级：未检测到结构时走全量 markdown 渲染
 */
import { Sparkles, Copy, RefreshCw, Square, ThumbsUp, ThumbsDown } from 'lucide-vue-next'
import type { StructuredAnalysis } from '@/composables/useTicketAnalysis'

defineProps<{
  content: string
  streaming: boolean
  done: boolean
  structured: StructuredAnalysis
  useStructuredRender: boolean
  confidenceClass: string
  citations: string[]
  cost: number
  renderMarkdown: (text: string) => string
  onCopyCommand: (cmd: string) => void
  onCopyAnalysis: () => void
  onRegenerate: () => void
  onStop: () => void
  /**
   * 当前内容是否来自历史存档（而非本次实时生成）
   *
   * 必须如实标注：用户看到一段分析时，需要知道它是刚生成的还是上次存下来的——
   * 工单情况可能已变化，旧结论不一定仍适用。
   */
  fromArchive?: boolean
  /** 存档时间（fromArchive 为真时展示） */
  archivedAt?: string
  /**
   * 当前分析的用户反馈：null=未评价 / 'HELPFUL' / 'UNHELPFUL'
   * 有 analysisId（已存档）时反馈按钮才可用
   */
  feedback?: string | null
  /** 分析是否已存档（决定反馈按钮是否可用） */
  canFeedback?: boolean
  /** 提交反馈回调 */
  onFeedback?: (helpful: boolean) => void
}>()
</script>

<template>
  <div class="analysis-card">
    <div class="analysis-header">
      <Sparkles :size="14" />
      <span class="analysis-title">AI 分析建议</span>
      <span v-if="streaming" class="analysis-status">分析中...</span>
      <!-- 存档标注优先于「完成」：用户需知道这是上次的结论而非刚生成的 -->
      <span v-else-if="fromArchive" class="analysis-status archived" :title="archivedAt ? `分析于 ${archivedAt}` : ''">
        历史分析{{ archivedAt ? ` · ${archivedAt}` : '' }}
      </span>
      <span v-else-if="done" class="analysis-status done">完成</span>
    </div>

    <!-- 结构化渲染 -->
    <div v-if="useStructuredRender" class="analysis-structured">
      <!-- 可能原因 -->
      <div v-if="structured.reasons.length" class="structured-section">
        <div class="structured-header">
          <span>可能原因</span>
        </div>
        <ol class="reasons-list">
          <li v-for="(reason, i) in structured.reasons" :key="i" class="reason-item">
            <span class="reason-num">{{ i + 1 }}</span>
            <span class="reason-text" v-html="renderMarkdown(reason)"></span>
          </li>
        </ol>
      </div>

      <!-- 排查命令 -->
      <div v-if="structured.commands.length" class="structured-section">
        <div class="structured-header">
          <span>排查命令</span>
          <span class="structured-hint">可复制</span>
        </div>
        <div class="commands-list">
          <div v-for="(cmd, i) in structured.commands" :key="i" class="command-item">
            <code class="command-code">{{ cmd }}</code>
            <button class="command-copy" @click="onCopyCommand(cmd)" title="复制">
              <Copy :size="11" />
            </button>
          </div>
        </div>
      </div>

      <!-- 置信度 -->
      <div v-if="structured.confidence !== null" class="structured-section">
        <div class="structured-header">
          <span>置信度</span>
          <span class="confidence-tag" :class="confidenceClass">{{ structured.confidence }}%</span>
        </div>
        <div class="confidence-bar">
          <div class="confidence-fill" :class="confidenceClass" :style="{ width: structured.confidence + '%' }"></div>
        </div>
      </div>

      <!-- 其他内容 -->
      <div v-if="structured.other" class="structured-section other-content" v-html="renderMarkdown(structured.other)"></div>
    </div>

    <!-- 降级：全量 markdown 渲染 -->
    <div
      v-if="content && !useStructuredRender"
      class="analysis-content markdown-body"
      v-html="renderMarkdown(content)"
    ></div>

    <!-- 引用文档 -->
    <div v-if="citations.length" class="citations-section">
      <div class="citations-header">
        <span>引用文档（{{ citations.length }}）</span>
      </div>
      <div class="citations-list">
        <div v-for="(cite, i) in citations" :key="i" class="citation-item">
          <span>{{ cite }}</span>
        </div>
      </div>
    </div>

    <!-- 操作 -->
    <div v-if="done || streaming" class="analysis-actions">
      <span v-if="cost > 0" class="cost-tag">¥{{ cost.toFixed(4) }}</span>

      <!-- 反馈：分析已存档才可评价（AI 准确率数据来源） -->
      <template v-if="done && !streaming && canFeedback">
        <span class="feedback-label">这次分析有用吗？</span>
        <button
          class="feedback-btn"
          :class="{ active: feedback === 'HELPFUL' }"
          title="有用"
          @click="onFeedback && onFeedback(true)"
        >
          <ThumbsUp :size="12" />
        </button>
        <button
          class="feedback-btn"
          :class="{ active: feedback === 'UNHELPFUL' }"
          title="没用"
          @click="onFeedback && onFeedback(false)"
        >
          <ThumbsDown :size="12" />
        </button>
        <span class="action-divider"></span>
      </template>

      <button v-if="streaming" class="analysis-btn" @click="onStop">
        <Square :size="11" /> 停止
      </button>
      <button v-if="done" class="analysis-btn" @click="onCopyAnalysis">
        <Copy :size="11" /> 复制
      </button>
      <button v-if="done && !streaming" class="analysis-btn" @click="onRegenerate">
        <RefreshCw :size="11" /> 重新分析
      </button>
    </div>
  </div>
</template>

<style scoped>
.analysis-card {
  border-left: 3px solid var(--color-primary, var(--brand));
  background: var(--color-primary-lighter, var(--brand-subtle));
  border-radius: 0 8px 8px 0;
  padding: 12px 16px;
}
.analysis-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 0.8125rem;
}
.analysis-title { font-weight: 600; color: var(--color-primary, var(--brand)); }
.analysis-status { font-size: 0.6875rem; color: var(--color-text-tertiary, var(--text-3)); }
.analysis-status.done { color: var(--state-success, var(--success)); }
/* 存档用中性灰而非成功绿：它不是「刚完成」，只是「上次的结论」 */
.analysis-status.archived { color: var(--color-text-tertiary, var(--text-3)); cursor: help; }

.analysis-structured { display: flex; flex-direction: column; gap: 10px; }
.structured-header {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 0.75rem; font-weight: 600; color: var(--color-text-secondary, var(--text-2));
  margin-bottom: 4px;
}
.structured-hint { font-size: 0.625rem; color: var(--color-text-tertiary, var(--text-3)); font-weight: 400; }

.reasons-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 4px; }
.reason-item { display: flex; gap: 8px; font-size: 0.8125rem; line-height: 1.5; }
.reason-num {
  flex-shrink: 0; width: 18px; height: 18px; border-radius: 50%;
  background: var(--color-primary, var(--brand)); color: #fff;
  font-size: 0.625rem; font-weight: 600;
  display: flex; align-items: center; justify-content: center;
}
.reason-text { color: var(--color-text-secondary, var(--text-2)); flex: 1; }

.commands-list { display: flex; flex-direction: column; gap: 4px; }
.command-item {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  background: #fff; border-radius: 4px; padding: 6px 10px;
  border: 1px solid var(--color-border-light, var(--border-1));
}
.command-code { font-family: monospace; font-size: 0.75rem; color: var(--color-primary, var(--brand)); flex: 1; overflow-x: auto; white-space: nowrap; }
.command-copy {
  border: none; background: none; cursor: pointer; padding: 2px;
  color: var(--color-text-tertiary, var(--text-3)); border-radius: 3px;
}
.command-copy:hover { color: var(--color-primary, var(--brand)); background: var(--color-primary-lighter, var(--brand-subtle)); }

.confidence-tag { font-weight: 600; }
.confidence-high { color: var(--state-success, var(--success)); }
.confidence-mid { color: var(--state-warning, var(--warning)); }
.confidence-low { color: var(--state-error, var(--danger)); }

.confidence-bar {
  width: 100%; height: 4px; border-radius: 2px; overflow: hidden;
  background: var(--color-bg-sunken, var(--surface-2));
}
.confidence-fill { height: 100%; border-radius: 2px; transition: width 0.3s; }
.confidence-fill.confidence-high { background: var(--state-success, var(--success)); }
.confidence-fill.confidence-mid { background: var(--state-warning, var(--warning)); }
.confidence-fill.confidence-low { background: var(--state-error, var(--danger)); }

.other-content { font-size: 0.8125rem; color: var(--color-text-tertiary, var(--text-3)); }
.analysis-content { font-size: 0.8125rem; line-height: 1.6; color: var(--color-text-secondary, var(--text-2)); }

.citations-section { margin-top: 8px; }
.citations-header { font-size: 0.6875rem; color: var(--color-text-tertiary, var(--text-3)); margin-bottom: 4px; }
.citations-list { display: flex; flex-direction: column; gap: 2px; }
.citation-item { font-size: 0.6875rem; color: var(--color-primary, var(--brand)); }

.analysis-actions {
  display: flex; align-items: center; gap: 8px;
  margin-top: 8px; padding-top: 8px;
  border-top: 1px solid rgba(64, 158, 255, 0.1);
}
.cost-tag { font-size: 0.625rem; color: var(--color-text-tertiary, var(--text-3)); }
.analysis-btn {
  display: inline-flex; align-items: center; gap: 3px;
  border: none; background: none; cursor: pointer;
  font-size: 0.6875rem; color: var(--color-text-tertiary, var(--text-3));
  padding: 2px 4px; border-radius: 3px;
}
.analysis-btn:hover { color: var(--color-primary, var(--brand)); background: rgba(64, 158, 255, 0.08); }

/* 反馈（策略 B：AI 准确率数据来源） */
.feedback-label { font-size: 0.625rem; color: var(--color-text-tertiary, var(--text-3)); }
.feedback-btn {
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--color-border-light, var(--border-1)); background: #fff; cursor: pointer;
  color: var(--color-text-tertiary, var(--text-3));
  padding: 3px 6px; border-radius: 4px;
  transition: all 0.15s ease;
}
.feedback-btn:hover { color: var(--color-primary, var(--brand)); border-color: var(--color-primary-light, #79bbff); }
/* 选中态：有用绿 / 没用红，明确反映用户已评价 */
.feedback-btn.active { color: #fff; }
.feedback-btn.active:first-of-type,
.feedback-btn.active[title="有用"] { background: var(--state-success, var(--success)); border-color: var(--state-success, var(--success)); }
.feedback-btn.active[title="没用"] { background: #EF4444; border-color: #EF4444; }
.action-divider { width: 1px; height: 12px; background: var(--color-border-light, var(--border-1)); }
</style>
