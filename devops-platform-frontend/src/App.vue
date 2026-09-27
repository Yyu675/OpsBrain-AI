<script setup lang="ts">
import { notify } from '@/utils/notify'
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppErrorBoundary from '@/components/common/AppErrorBoundary.vue'
import NetworkBanner from '@/components/common/NetworkBanner.vue'
import AppSidebar from '@/components/common/AppSidebar.vue'
import AppTopBar from '@/components/common/AppTopBar.vue'
import HotkeysDialog from '@/components/common/HotkeysDialog.vue'
import { ElMessageBox } from 'element-plus'
import { useIdleTimer } from '@/composables/useIdleTimer'
import { useHotkeys } from '@/composables/useHotkeys'
import { useAlertNotifications } from '@/composables/useAlertNotifications'
import { useSessionCleanup } from '@/composables/useSessionCleanup'
import { useAppStore } from '@/stores/app'
import { safeInternalPath } from '@/utils/safeRedirect'

const router = useRouter()
const route = useRoute()
const app = useAppStore()

// 全局告警通知：连接 /ws/alerts，收到 NEW 告警时推入通知 store
useAlertNotifications()

// 登出时清掉上一个用户的本地敏感数据（遗留对话持久键、沉淀草稿、Query 缓存）。
// 挂在根组件是刻意的：登出有四条路径，逐个加清理必漏一条——
// 监听登录态这个状态事实才能全覆盖。详见该 composable 的文件头。
useSessionCleanup()

const compactBodyClass = computed(() => app.settings.compactTable ? 'compact-tables' : '')

/** 无壳页面（登录页）：全屏渲染，不带侧栏/TopBar */
const isBare = computed(() => !!route.meta.bare)

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

  // from 来自 http 层读取的 window.location，同样经校验后再作为 redirect 传递，
  // 避免把一个可控值原样塞进 query 供登录页回跳
  router.push({ name: 'login', query: from ? { redirect: safeInternalPath(from) } : {} })
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
    <!-- 无壳页面（登录页等全屏场景）：不渲染侧栏与 TopBar。
         注意结构：v-if 包在 template 上而不是 router-view 上——
         v-if 与 v-slot 同挂在 router-view 上时作用域插槽的编译结果不可靠 -->
    <template v-if="isBare">
      <router-view v-slot="{ Component }">
        <AppErrorBoundary scope="页面">
          <component :is="Component" />
        </AppErrorBoundary>
      </router-view>
    </template>
    <!-- 侧栏布局壳（2026-09-27）：导航在左（AppSidebar），搜索/通知在顶（AppTopBar），
         内容区随窗口滚动；TopBar sticky 保持在视口顶 -->
    <div v-else class="app-shell">
      <AppSidebar />
      <div class="app-main">
        <AppTopBar />
        <router-view v-slot="{ Component }">
          <AppErrorBoundary scope="页面">
            <!-- 首页承载值班工作台（Dashboard）：缓存 Home 子树即保住工作台状态 -->
            <keep-alive include="Home">
              <component :is="Component" />
            </keep-alive>
          </AppErrorBoundary>
        </router-view>
      </div>
    </div>
    <!-- 快捷键帮助面板：内容由当前页真实注册的快捷键派生 -->
    <HotkeysDialog v-model:visible="hotkeysVisible" />
  </div>
</template>

<style>
/* 布局壳（全局，不 scoped：子组件不需要看到这些类名，但保持就近） */
.app-shell {
  display: flex;
  align-items: flex-start;
  min-height: 100vh;
}

.app-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
</style>
