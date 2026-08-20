<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bot } from 'lucide-vue-next'
import AppErrorBoundary from '@/components/common/AppErrorBoundary.vue'
import NetworkBanner from '@/components/common/NetworkBanner.vue'
import AppNavbar from '@/components/common/AppNavbar.vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useIdleTimer } from '@/composables/useIdleTimer'
import { useAlertNotifications } from '@/composables/useAlertNotifications'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const route = useRoute()
const app = useAppStore()

// 全局告警通知：连接 /ws/alerts，收到 NEW 告警时推入通知 store
useAlertNotifications()

const compactBodyClass = computed(() => app.settings.compactTable ? 'compact-tables' : '')

/** 是否在 AI 对话页面自身：FAB 在该页面隐藏 */
const isOnAiChat = computed(() => route.path === '/ai-chat')

let warnCloseFn: (() => void) | null = null

const timeoutMs = computed(() => app.settings.idleTimeoutMinutes * 60 * 1000)
const warnMs = computed(() => Math.max(timeoutMs.value - 2 * 60 * 1000, Math.floor(timeoutMs.value * 0.8)))

useIdleTimer({
  // 传 getter 而非 .value：设置里改超时后 useIdleTimer 内 watch 会即时重排，无需刷新
  warnAfter: () => warnMs.value,
  timeoutAfter: () => timeoutMs.value,
  onWarn(remainingMs: number) {
    const seconds = Math.round(remainingMs / 1000)
    warnCloseFn?.()
    let closed = false
    warnCloseFn = () => { closed = true; warnCloseFn = null }
    ElMessageBox.confirm(
      `长时间未操作，将在 ${seconds} 秒后自动退出，是否继续保持登录？`,
      '会话即将过期',
      {
        type: 'warning',
        confirmButtonText: '继续使用',
        cancelButtonText: '立即退出',
        closeOnClickModal: false,
        closeOnPressEscape: false
      }
    )
      .then(() => {
        warnCloseFn = null
        if (closed) return
        ElMessage.success('已延长会话')
      })
      .catch(() => {
        warnCloseFn = null
        if (closed) return
        app.signOut()
        router.push('/403')
      })
  },
  onTimeout() {
    warnCloseFn?.()
    warnCloseFn = null
    if (!app.isAuthenticated) return
    app.signOut()
    ElMessage.warning('长时间未操作，已自动退出登录')
    router.push('/403')
  },
  onActive() {
    warnCloseFn?.()
    warnCloseFn = null
  }
})
</script>

<template>
  <div class="app-root" :class="compactBodyClass">
    <NetworkBanner />
    <AppNavbar />
    <router-view v-slot="{ Component }">
      <AppErrorBoundary scope="页面">
        <keep-alive include="Dashboard">
          <component :is="Component" />
        </keep-alive>
      </AppErrorBoundary>
    </router-view>
    <!-- 全局 AI 对话入口：FAB 悬浮按钮跳转独立页面 -->
    <button
      v-if="!isOnAiChat"
      class="ai-fab"
      @click="router.push({ path: '/ai-chat', query: { from: route.fullPath } })"
      aria-label="AI 智能助手"
    >
      <Bot :size="24" />
    </button>
  </div>
</template>

<style>
.ai-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  border: none;
  background: var(--el-color-primary, #409eff);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 16px rgba(64, 158, 255, 0.4);
  transition: transform 0.2s, box-shadow 0.2s;
  z-index: 2000;
}
.ai-fab:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 24px rgba(64, 158, 255, 0.5);
}
.ai-fab--active {
  transform: rotate(90deg);
}
</style>
