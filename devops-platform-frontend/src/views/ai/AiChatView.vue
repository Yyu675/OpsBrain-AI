<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Bot, MessageSquare, Lightbulb, TrendingUp, Bell } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'
import { ticketEvents } from '@/utils/ticketEvents'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'
import ChatMode from '@/components/ai/ChatMode.vue'
import SuggestionMode from '@/components/ai/SuggestionMode.vue'
import AnalyticsMode from '@/components/ai/AnalyticsMode.vue'
import AlertStreamMode from '@/components/ai/AlertStreamMode.vue'

/**
 * AI 智能助手独立页面（FAB 悬浮按钮跳转，App.vue 全局入口）
 *
 * 四模式：对话问答 / 场景化建议 / 趋势分析 / 实时监控。
 *
 * ## 本轮修掉的布局缺陷
 *
 * 1. **AlertStreamMode 缺 `v-show`** —— 四个模式中唯一没有条件渲染的，
 *    导致它在任何 tab 下都渲染并占据 `.mode-body` 的 flex 空间：
 *    用户在「对话助手」时下方仍挂着告警流的连接态卡片，页面被顶高错位。
 * 2. **`max-width: 1000px` 一刀切** —— 对话适合窄栏（逐行阅读），
 *    但趋势分析的双轴图表与实时监控的告警卡在 1920px 屏上被压在中间 1000px，
 *    两侧各留约 450px 空白。现按模式给不同宽度。
 * 3. **`height: calc(100vh - 56px)` 硬编码导航栏高度** —— NetworkBanner 出现时
 *    整页被顶出视口底部。改为 flex 撑满 + `min-height: 0`，不依赖导航栏具体高度。
 * 4. **全文件零 `@media`** —— 4 个 tab 各约 110px 且 `white-space: nowrap`，
 *    窄屏必然横向溢出。现允许 tab 行横向滚动并收窄内边距。
 * 5. **返回方式与全站不一致** —— 原为读 `?from=` 的单个箭头按钮；
 *    现统一为首页起始的递进面包屑（`from` 仍尊重：作为父级层级插入链路）。
 */
const route = useRoute()

const chat = useChatStore()

type AiMode = 'chat' | 'suggestion' | 'analytics' | 'alerts'

const activeMode = ref<AiMode>('chat')

const modes: Array<{ key: AiMode; label: string; icon: typeof MessageSquare }> = [
  { key: 'chat', label: '对话助手', icon: MessageSquare },
  { key: 'suggestion', label: '智能建议', icon: Lightbulb },
  { key: 'analytics', label: '趋势分析', icon: TrendingUp },
  { key: 'alerts', label: '实时监控', icon: Bell }
]

/**
 * 内容宽度按模式区分。
 *
 * 对话是逐行阅读，过宽会让视线横向扫动距离过长，故保持窄栏；
 * 图表与告警卡是二维信息，窄栏会挤压坐标轴与卡片内的元信息，故放宽。
 */
const MODE_MAX_WIDTH: Record<AiMode, number> = {
  chat: 1000,
  suggestion: 1200,
  analytics: 1600,
  alerts: 1400
}

const contentMaxWidth = computed(() => `${MODE_MAX_WIDTH[activeMode.value]}px`)

/**
 * 来源页：FAB 跳转时带 `?from=/xxx`。
 *
 * 作为面包屑的父级层级插入，而非单独的返回按钮——
 * 这样「从工单详情打开 AI」时链路是「首页 > 智能工单 > AI 智能助手」，
 * 与全站其它页面同一形态。
 *
 * 只接受站内相对路径（与 Login.vue 的 redirectTarget 同一约束），防开放重定向。
 */
const KNOWN_PARENTS: Array<{ prefix: string; label: string }> = [
  { prefix: '/tickets', label: '智能工单' },
  { prefix: '/knowledge', label: '知识库' },
  { prefix: '/alerts', label: '告警事件' },
  { prefix: '/action-items', label: '改进项' },
  { prefix: '/dashboard', label: '数据概览' },
  { prefix: '/approvals', label: '审批中心' }
]

const breadcrumbItems = computed(() => {
  const raw = route.query.from
  const from = Array.isArray(raw) ? raw[0] : raw
  const items: Array<{ label: string; to?: string }> = []

  if (from && from.startsWith('/') && !from.startsWith('//')) {
    const parent = KNOWN_PARENTS.find(p => from.startsWith(p.prefix))
    if (parent) {
      items.push({ label: parent.label, to: from })
    }
  }

  items.push({ label: 'AI 智能助手' })
  return items
})

/** 处理建单事件，通过 ticketEvents 总线广播 */
const handleTicketCreated = (ticketId: string) => {
  ticketEvents.emit('ticket-created', ticketId)
}

onMounted(() => {
  // 确保全局会话存在
  chat.ensureSession('global')
  chat.ensureWelcome()
})
</script>

<template>
  <div class="ai-chat-page">
    <div class="ai-chat-inner" :style="{ maxWidth: contentMaxWidth }">
      <!-- 页面头部 -->
      <header class="page-header">
        <AppBreadcrumb :items="breadcrumbItems" class="page-breadcrumb" />
        <div class="header-title-row">
          <div class="header-icon">
            <Bot :size="20" />
          </div>
          <div class="header-title-block">
            <h1 class="page-title">AI 智能助手</h1>
            <p class="page-subtitle">对话 · 建议 · 分析 · 监控</p>
          </div>
        </div>
      </header>

      <!-- 模式切换 Tab -->
      <div class="mode-tabs">
        <button
          v-for="m in modes"
          :key="m.key"
          class="mode-tab"
          :class="{ active: activeMode === m.key }"
          type="button"
          @click="activeMode = m.key"
        >
          <component :is="m.icon" :size="16" />
          <span>{{ m.label }}</span>
        </button>
      </div>

      <!-- 模式内容区 -->
      <div class="mode-body">
        <ChatMode v-show="activeMode === 'chat'" @ticket-created="handleTicketCreated" />
        <SuggestionMode v-show="activeMode === 'suggestion'" />
        <AnalyticsMode v-show="activeMode === 'analytics'" :active="activeMode === 'analytics'" />
        <!--
          v-show 必须有：此前漏了它，AlertStreamMode 在所有 tab 下都渲染并占位。
          `active` prop 仍要传——它控制 WebSocket 连接生命周期，与显隐是两件事
          （隐藏却保持连接会白占一条连接，故二者同步）。
        -->
        <AlertStreamMode v-show="activeMode === 'alerts'" :active="activeMode === 'alerts'" />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.ai-chat-page {
  /*
    撑满导航栏以下的可视区域。
    高度仍以 calc(100vh - 56px) 为上限兜底（父级未建立高度链时不至于塌成 0），
    但同时给 flex:1 + min-height:0，使其在父级能分配高度时优先按 flex 走，
    NetworkBanner 出现时不会把内容顶出视口。
  */
  display: flex;
  justify-content: center;
  flex: 1;
  min-height: 0;
  height: calc(100vh - 56px);
  max-height: calc(100vh - 56px);
  background: var(--color-bg, var(--surface-0));
}

.ai-chat-inner {
  /* max-width 由 contentMaxWidth 按模式内联设置——见 MODE_MAX_WIDTH 注释 */
  width: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  /* 宽度随模式切换过渡，避免切 tab 时内容区宽度突跳 */
  transition: max-width 0.2s ease;
}

/* 页面头部 */
.page-header {
  padding: 16px 24px 0;
  flex-shrink: 0;
  min-width: 0;
}

.header-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.header-icon {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md, 8px);
  background: var(--color-primary-lighter, #ecf5ff);
  color: var(--color-primary, var(--brand));
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.header-title-block {
  min-width: 0;
}

.page-title {
  margin: 0;
  font-size: var(--text-lg, 16px);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary, #303133);
  line-height: 1.3;
}

.page-subtitle {
  margin: 2px 0 0 0;
  font-size: 12px;
  color: var(--color-text-tertiary, var(--text-3));
}

/* 模式切换 Tab */
.mode-tabs {
  display: flex;
  gap: 8px;
  padding: 16px 24px 0;
  flex-shrink: 0;
  /* 窄屏横向滚动而非溢出（4 个 tab 各约 110px 且不换行） */
  overflow-x: auto;
  scrollbar-width: none;
}
.mode-tabs::-webkit-scrollbar {
  display: none;
}

.mode-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid var(--color-border-light, #e4e7ed);
  border-radius: var(--radius-md, 8px);
  background: var(--color-surface, #fff);
  color: var(--color-text-secondary, #606266);
  font-size: var(--text-sm, 14px);
  font-weight: var(--weight-medium, 500);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
  flex-shrink: 0;

  &:hover {
    border-color: var(--color-primary, var(--brand));
    color: var(--color-primary, var(--brand));
  }

  &.active {
    background: var(--color-primary, var(--brand));
    border-color: var(--color-primary, var(--brand));
    color: #fff;
  }
}

/* 模式内容区 */
.mode-body {
  flex: 1;
  /* min-height:0 必须有：flex 子项默认 min-height:auto，
     内容超高时会撑破容器而非在子组件内滚动 */
  min-height: 0;
  overflow: hidden;
  padding: 16px 24px 24px;
  display: flex;
  flex-direction: column;
}

/* 四个模式组件都以 height:100% 撑满内容区，各自内部滚动 */
.mode-body > * {
  min-height: 0;
}

@media (max-width: 768px) {
  .page-header,
  .mode-tabs {
    padding-left: 12px;
    padding-right: 12px;
  }
  .mode-body {
    padding: 12px;
  }
  .mode-tab {
    padding: 7px 12px;
  }
}
</style>
