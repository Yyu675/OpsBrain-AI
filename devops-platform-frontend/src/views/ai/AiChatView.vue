<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bot, MessageSquare, Lightbulb, TrendingUp, Bell, ArrowLeft } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'
import { ticketEvents } from '@/utils/ticketEvents'
import ChatMode from '@/components/ai/ChatMode.vue'
import SuggestionMode from '@/components/ai/SuggestionMode.vue'
import AnalyticsMode from '@/components/ai/AnalyticsMode.vue'
import AlertStreamMode from '@/components/ai/AlertStreamMode.vue'

/**
 * AI 对话独立页面（FAB 悬浮按钮单独跳转，App.vue 全局入口）
 *
 * 四模式全部迁移：
 * - 对话助手（ChatMode）—— 用户主动提问，SSE 流式对话
 * - 智能建议（SuggestionMode）—— 工单统计概览
 * - 趋势分析（AnalyticsMode）—— TODO(L2) ECharts 趋势预测
 * - 实时监控（AlertStreamMode）—— WebSocket 告警流
 *
 * 入口：FAB 悬浮按钮 → router.push('/ai-chat?from=/xxx')
 * 返回：读取 route.query.from 提供返回按钮
 */
const route = useRoute()
const router = useRouter()

const chat = useChatStore()

type AiMode = 'chat' | 'suggestion' | 'analytics' | 'alerts'

const activeMode = ref<AiMode>('chat')

const modes: Array<{ key: AiMode; label: string; icon: typeof MessageSquare }> = [
  { key: 'chat', label: '对话助手', icon: MessageSquare },
  { key: 'suggestion', label: '智能建议', icon: Lightbulb },
  { key: 'analytics', label: '趋势分析', icon: TrendingUp },
  { key: 'alerts', label: '实时监控', icon: Bell }
]

/** 来源页面路径，用于返回按钮 */
const fromPath = computed(() => {
  const from = route.query.from as string | undefined
  return from || '/'
})

/** 是否能看到返回按钮（有来源页且不是首页） */
const showBack = computed(() => {
  const from = route.query.from as string | undefined
  return !!from && from !== '/'
})

/** 处理建单事件，通过 ticketEvents 总线广播 */
const handleTicketCreated = (ticketId: string) => {
  ticketEvents.emit('ticket-created', ticketId)
}

/** 返回来源页 */
const goBack = () => {
  router.push(fromPath.value)
}

onMounted(() => {
  // 确保全局会话存在
  chat.ensureSession('global')
  chat.ensureWelcome()
})

onBeforeUnmount(() => {
  // 清理：切换页面时无需额外操作，ChatMode 自己的 onBeforeUnmount 会 abort SSE
})
</script>

<template>
  <div class="ai-chat-page">
    <!-- 页面头部 -->
    <header class="page-header">
      <div class="header-left">
        <button
          v-if="showBack"
          class="back-btn"
          @click="goBack"
          :title="`返回 ${fromPath}`"
        >
          <ArrowLeft :size="18" />
        </button>
        <div class="header-title-row">
          <div class="header-icon">
            <Bot :size="20" />
          </div>
          <div class="header-title-block">
            <h1 class="page-title">AI 智能助手</h1>
            <p class="page-subtitle">对话 · 建议 · 分析 · 监控</p>
          </div>
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
      <AlertStreamMode :active="activeMode === 'alerts'" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.ai-chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px);
  max-width: 1000px;
  margin: 0 auto;
  padding: 0;
  background: var(--color-bg, #f5f7fa);
}

/* 页面头部 */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px 0;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.back-btn {
  width: 32px;
  height: 32px;
  border: 1px solid var(--color-border-light, #e4e7ed);
  border-radius: var(--radius-md, 6px);
  background: var(--color-surface, #fff);
  color: var(--color-text-secondary, #606266);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary, #409eff);
    color: var(--color-primary, #409eff);
  }
}

.header-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-icon {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md, 8px);
  background: var(--color-primary-lighter, #ecf5ff);
  color: var(--color-primary, #409eff);
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
  color: var(--color-text-tertiary, #909399);
}

/* 模式切换 Tab */
.mode-tabs {
  display: flex;
  gap: 8px;
  padding: 16px 24px 0;
  flex-shrink: 0;
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

  &:hover {
    border-color: var(--color-primary, #409eff);
    color: var(--color-primary, #409eff);
  }

  &.active {
    background: var(--color-primary, #409eff);
    border-color: var(--color-primary, #409eff);
    color: #fff;
  }
}

/* 模式内容区 */
.mode-body {
  flex: 1;
  overflow: hidden;
  padding: 16px 24px 24px;
  display: flex;
  flex-direction: column;
}
</style>