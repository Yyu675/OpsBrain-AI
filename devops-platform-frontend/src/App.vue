<script setup lang="ts">
import { notify } from '@/utils/notify'
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bot } from 'lucide-vue-next'
import AppErrorBoundary from '@/components/common/AppErrorBoundary.vue'
import NetworkBanner from '@/components/common/NetworkBanner.vue'
import AppNavbar from '@/components/common/AppNavbar.vue'
import HotkeysDialog from '@/components/common/HotkeysDialog.vue'
import { ElMessageBox } from 'element-plus'
import { useIdleTimer } from '@/composables/useIdleTimer'
import { useHotkeys } from '@/composables/useHotkeys'
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
    // 访客本就未登录，不存在"会话过期"——弹窗是无意义骚扰
    if (!app.isAuthenticated) return
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
        notify.success('已延长会话')
      })
      .catch(() => {
        warnCloseFn = null
        if (closed) return
        app.signOut().finally(() => router.push('/login'))
      })
  },
  onTimeout() {
    warnCloseFn?.()
    warnCloseFn = null
    if (!app.isAuthenticated) return
    notify.warning('长时间未操作，已自动退出登录')
    app.signOut().finally(() => router.push('/login'))
  },
  onActive() {
    warnCloseFn?.()
    warnCloseFn = null
  }
})

/**
 * 监听 http 层派发的 401 事件（token 失效 / 未登录）。
 *
 * 分两种情形：
 * - 在受保护页面：登录已失效，跳登录页并带回跳路径
 * - 在公开页面（首页等）：访客本就未登录，只收敛为访客态**不跳转**——
 *   否则一个漏判访客态的接口调用就能把停留在首页的访客踢去登录页，
 *   「访客默认看首页」的需求即失效
 */
const onUnauthorized = (e: Event) => {
  const detail = (e as CustomEvent).detail as { from?: string } | undefined
  const from = detail?.from
  if (route.path === '/login') return

  // token 已失效，本地状态同步收敛（http 层已清 token）
  app.resetToGuest()

  if (route.meta?.public) return

  router.push({ name: 'login', query: from ? { redirect: from } : {} })
}

onMounted(() => {
  window.addEventListener('auth:unauthorized', onUnauthorized)
})
onBeforeUnmount(() => {
  window.removeEventListener('auth:unauthorized', onUnauthorized)
})

/**
 * 快捷键帮助面板（`?` 唤起）。
 *
 * 挂在根组件而非各页面：面板内容由 useActiveHotkeys 从当前页真实注册的
 * 快捷键派生，各页面只管注册自己的键，无需各自挂一份面板。
 */
const hotkeysVisible = ref(false)
useHotkeys([
  { key: '?', description: '打开快捷键面板', handler: () => { hotkeysVisible.value = true } }
])
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
    <!-- 快捷键帮助面板：内容由当前页真实注册的快捷键派生 -->
    <HotkeysDialog v-model:visible="hotkeysVisible" />
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
  background: var(--el-color-primary, var(--brand));
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
