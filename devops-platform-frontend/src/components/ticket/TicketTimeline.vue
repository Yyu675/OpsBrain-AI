<script setup lang="ts">
/**
 * 工单时间线。
 *
 * ── 从 TicketDetail.vue 抽出的理由 ────────────────────────────
 * 它是那个文件里**视觉最独立**的一块：一段自成体系的模板 + 236 行
 * 专属样式（气泡、轨道线、头像、事件卡片），与页面其余部分只通过
 * 数据往来，没有共享的交互状态。
 *
 * 抽出后 TicketDetail 少了约 350 行，而这些样式在原文件里夹在
 * 1000+ 行 style 中间，改一个气泡圆角要先找到它属于哪个区块。
 *
 * ── 三类时间线节点 ────────────────────────────────────────────
 * 1. 回复气泡（creator 右对齐 / 其余左对齐）
 * 2. AI 分析卡片（结构化渲染，不与回复气泡混排）
 * 3. SLA 预警/超时事件
 *
 * 顺序即时间顺序，AI 与 SLA 节点固定在末尾——它们描述的是「当前状态」
 * 而非某个历史时刻。
 */
import { AlertTriangle, Clock, Sparkles } from 'lucide-vue-next'

import AnalysisCard from '@/components/ticket/AnalysisCard.vue'
import { initialOf } from '@/composables/useTicketClosure'
import type { StructuredAnalysis } from '@/composables/useTicketAnalysis'
import type { Ticket, TicketReply } from '@/stores/tickets'

/**
 * AI 分析相关的 18 个字段收进一个对象传入。
 *
 * 平铺成 18 个 prop 会让调用处占满一屏，且新增一个字段要改三处
 * （defineProps、调用点、AnalysisCard）。收成对象后调用处是
 * `:analysis="analysisBundle"` 一行，父组件那边也能直接把 composable
 * 的返回值整体传过来。
 */
export interface TimelineAnalysis {
  content: string
  streaming: boolean
  done: boolean
  /** 复用 AnalysisCard 的类型而非重新声明——两处各写一份必然漂移 */
  structured: StructuredAnalysis
  useStructuredRender: boolean
  confidenceClass: string
  citations: string[]
  cost: number
  fromArchive?: boolean
  /** AnalysisCard 用 `string | undefined`，这里保持一致（不是 null） */
  archivedAt?: string
  feedback?: string | null
  /** 分析已存档才有 id，反馈按钮据此启用 */
  id: number | null
  /** 参数是「是否有帮助」的布尔值，不是文本 */
  onFeedback?: (helpful: boolean) => void
  renderMarkdown: (text: string) => string
  onCopyCommand: (cmd: string) => void
  onCopyAnalysis: () => void
  onRegenerate: () => void
  onStop: () => void
}

defineProps<{
  /** 工单本体。SLA 事件节点要读 slaBreached / slaProgress / sla */
  ticket: Ticket
  /** 已过滤掉 role='ai' 的回复——AI 内容由下方 AnalysisCard 结构化呈现 */
  visibleReplies: TicketReply[]
  analysis: TimelineAnalysis
  /** 是否展示 SLA 事件节点。终态工单计时已停，由父组件判定 */
  showSlaAlert: boolean
}>()
</script>

<template>
  <!-- ========== Timeline ========== -->
  <div class="timeline">

    <!-- Dynamic Replies
         排除 role='ai'：AI 分析已由下方 AnalysisCard 以结构化卡片渲染
         （原因列表/可复制命令/置信度）。若不排除，同一份内容会在时间线
         出现两次，且这里的纯文本气泡会丢掉全部结构。
         历史分析仍保留在库中可审计，界面只呈现最新结论。 -->
    <div
      v-for="(reply, i) in visibleReplies"
      :key="reply.time + '-' + reply.author + '-' + i"
      class="timeline-row"
      :class="{ 'timeline-row-right': reply.role === 'creator' }"
    >
      <!-- Creator message (right-aligned) -->
      <template v-if="reply.role === 'creator'">
        <div class="timeline-body timeline-body-right">
          <div class="user-bubble-wrap">
            <div class="user-bubble">
              <p>{{ reply.content }}</p>
            </div>
            <div class="user-bubble-meta">
              <span class="user-name">{{ reply.author }}</span>
              <span class="user-time">{{ reply.time }}</span>
            </div>
          </div>
        </div>
        <div class="timeline-track">
          <div class="track-avatar" :style="{ background: reply.authorColor || '#6366F1' }">{{ initialOf(reply.author) }}</div>
          <div class="track-line"></div>
        </div>
      </template>

      <!-- Agent message (left-aligned) -->
      <template v-else>
        <div class="timeline-track">
          <div class="track-avatar track-avatar-primary">{{ initialOf(reply.author) }}</div>
          <div class="track-line"></div>
        </div>
        <div class="timeline-body">
          <div class="agent-bubble-wrap">
            <div class="agent-bubble">
              <p style="white-space: pre-line;">{{ reply.content }}</p>
            </div>
            <div class="agent-bubble-meta">
              <span class="agent-name">{{ reply.author }}</span>
              <span class="agent-time">{{ reply.time }}</span>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- AI 分析建议（时间线节点） -->
    <div v-if="analysis.content || analysis.streaming" class="timeline-row">
      <div class="timeline-track">
        <div class="track-icon track-icon-primary">
          <Sparkles :size="16" />
        </div>
        <div class="track-line"></div>
      </div>
      <div class="timeline-body">
        <AnalysisCard
          :content="analysis.content"
          :streaming="analysis.streaming"
          :done="analysis.done"
          :structured="analysis.structured"
          :use-structured-render="analysis.useStructuredRender"
          :confidence-class="analysis.confidenceClass"
          :citations="analysis.citations"
          :cost="analysis.cost"
          :from-archive="analysis.fromArchive"
          :archived-at="analysis.archivedAt"
          :feedback="analysis.feedback"
          :can-feedback="analysis.id != null"
          :on-feedback="analysis.onFeedback"
          :render-markdown="analysis.renderMarkdown"
          :on-copy-command="analysis.onCopyCommand"
          :on-copy-analysis="analysis.onCopyAnalysis"
          :on-regenerate="analysis.onRegenerate"
          :on-stop="analysis.onStop"
        />
      </div>
    </div>

    <!-- SLA Warning / Breach -->
    <div
      v-if="showSlaAlert"
      class="timeline-row"
    >
      <div class="timeline-track">
        <div class="track-icon" :class="ticket.slaBreached ? 'track-icon-error' : 'track-icon-warning'">
          <AlertTriangle :size="16" />
        </div>
        <div class="track-line"></div>
      </div>
      <div class="timeline-body">
        <div class="event-bubble" :class="ticket.slaBreached ? 'event-bubble-error' : 'event-bubble-warning'">
          <div class="event-header">
            <Clock :size="14" :class="ticket.slaBreached ? 'error-icon' : 'warning-icon'" />
            <span :class="ticket.slaBreached ? 'event-title-error' : 'event-title-warning'">
              {{ ticket.slaBreached ? 'SLA 已超时' : 'SLA 预警' }}
            </span>
            <span class="event-time">现在</span>
          </div>
          <p v-if="ticket.slaBreached" class="event-text">
            已超出 SLA 承诺时限（{{ ticket.sla }}），请立即处理或升级
          </p>
          <p v-else class="event-text">
            SLA 已消耗 <strong class="warning-strong">{{ ticket.slaProgress }}%</strong>，请尽快处理
          </p>
        </div>
      </div>
    </div>

  </div>
</template>

<style scoped lang="scss">
/* ========== Timeline ========== */
.timeline {
  margin-top: 24px;
}

.timeline-row {
  display: flex;
  gap: 16px;

  &.timeline-row-right {
    justify-content: flex-end;
  }
}

.timeline-track {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex-shrink: 0;
}

.track-icon {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;

  &.track-icon-primary {
    background: var(--color-primary-lighter, var(--brand-subtle));
    color: var(--color-primary-light, var(--brand-hover));
  }

  &.track-icon-warning {
    background: var(--state-warning-bg, var(--warning-subtle));
    color: var(--state-warning, var(--warning));
  }

  &.track-icon-error {
    background: var(--state-error-bg, var(--danger-subtle));
    color: var(--state-error, var(--danger));
  }
}

.track-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: var(--text-xs, 0.75rem);
  font-weight: var(--weight-semibold, 600);
  flex-shrink: 0;

  &.track-avatar-primary {
    background: var(--color-primary, var(--brand));
  }
}

.track-line {
  width: 1px;
  flex: 1;
  margin-top: 8px;
  background: var(--color-border-light, var(--border-1));
}

.timeline-body {
  flex: 1;
  padding-bottom: 24px;
  min-width: 0;

  &.timeline-body-right {
    display: flex;
    justify-content: flex-end;
  }
}

/* Event Bubbles */
.event-bubble {
  padding: 12px 16px;
  border-radius: var(--radius-md, 8px);
  font-size: var(--text-sm, 0.875rem);

  &.event-bubble-primary {
    background: var(--color-primary-lighter, var(--brand-subtle));
  }

  &.event-bubble-warning {
    background: var(--state-warning-bg, var(--warning-subtle));
  }

  &.event-bubble-error {
    background: var(--state-error-bg, var(--danger-subtle));
  }
}

.event-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.event-title-primary {
  color: var(--color-primary-light, var(--brand-hover));
  font-weight: var(--weight-medium, 500);
}

.event-title-warning {
  color: var(--state-warning, var(--warning));
  font-weight: var(--weight-medium, 500);
}

.event-title-error {
  color: var(--state-error, var(--danger));
  font-weight: var(--weight-semibold, 600);
}

.event-time {
  color: var(--color-text-tertiary, var(--text-3));
  font-size: var(--text-xs, 0.75rem);
}

.event-text {
  color: var(--color-text-secondary, var(--text-2));
  margin: 0;

  strong {
    color: var(--color-primary, var(--brand));
  }
}

.warning-strong {
  color: var(--state-warning, var(--warning));
}

.warning-icon {
  color: var(--state-warning, var(--warning));
}

.error-icon {
  color: var(--state-error, var(--danger));
}

/* AI 分析建议 inline card */
.ai-suggestion-card {
  padding: 12px 16px;
  border-radius: var(--radius-md, 8px);
  font-size: var(--text-sm, 0.875rem);
  background: var(--color-primary-lighter, var(--brand-subtle));
  border-left: 4px solid var(--color-primary, var(--brand));
}

.ai-suggestion-title {
  color: var(--color-primary, var(--brand));
  font-weight: var(--weight-semibold, 600);
  font-size: var(--text-sm, 0.875rem);
}

/* User Bubble (creator, right-aligned) */
.user-bubble-wrap {
  max-width: 28rem;
}

.user-bubble {
  padding: 12px 16px;
  border-radius: var(--radius-lg, 12px);
  border-top-right-radius: var(--radius-sm, 4px);
  background: var(--color-primary, var(--brand));
  color: white;
  font-size: var(--text-sm, 0.875rem);

  p {
    margin: 0;
    line-height: var(--leading-relaxed, 1.625);
  }
}

.user-bubble-meta {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 6px;
}

.user-name {
  font-size: var(--text-xs, 0.75rem);
  font-weight: var(--weight-medium, 500);
  color: var(--color-text-primary, var(--text-1));
}

.user-time {
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-tertiary, var(--text-3));
}

/* Agent Bubble (left-aligned) */
.agent-bubble-wrap {
  max-width: 32rem;
}

.agent-bubble {
  padding: 12px 16px;
  border-radius: var(--radius-lg, 12px);
  border-top-left-radius: var(--radius-sm, 4px);
  background: white;
  border: 1px solid var(--color-border-light, var(--border-1));
  color: var(--color-text-primary, var(--text-1));
  font-size: var(--text-sm, 0.875rem);

  p {
    margin: 0;
    line-height: var(--leading-relaxed, 1.625);
  }
}

.agent-bubble-meta {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}

.agent-name {
  font-size: var(--text-xs, 0.75rem);
  font-weight: var(--weight-medium, 500);
  color: var(--color-primary, var(--brand));
}

.agent-time {
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-tertiary, var(--text-3));
}
</style>
