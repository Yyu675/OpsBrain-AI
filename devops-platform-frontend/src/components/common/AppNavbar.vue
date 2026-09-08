<script setup lang="ts">
import { notify } from '@/utils/notify'
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Monitor, Bell, User, Settings, LogOut, LogIn, CheckCheck } from 'lucide-vue-next'
import ProfileDialog from '@/components/common/ProfileDialog.vue'
import SettingsDialog from '@/components/common/SettingsDialog.vue'
import AvatarFallback from '@/components/common/AvatarFallback.vue'
import { useAppStore } from '@/stores/app'
import { useNotificationsStore, type AppNotification } from '@/stores/notifications'
import { primaryNavigationItems } from '@/config/navigation'
import { usePendingApprovalCountQuery } from '@/api/queries/approval.query'

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const notificationsStore = useNotificationsStore()

// 方向 F RBAC：按当前用户角色过滤导航项。
// 无 roles 的项对所有登录用户可见；有 roles 的（如审批中心 admin-only）仅匹配角色可见。
const navItems = computed(() =>
  primaryNavigationItems.filter(i => !i.roles || app.hasRole(i.roles as Array<'admin' | 'operator' | 'viewer' | 'guest'>))
)

/**
 * 审批中心待审角标。
 *
 * 只对管理员启用——该端点限 ADMIN（后端 @SaCheckRole），
 * 非管理员请求会得到 403，既无意义又会污染控制台。
 * 拉取失败降级为 0（不显示角标）：角标是增值提示，
 * 失败不该弹错误打扰用户（同 6.51 趋势加载失败的降级策略）。
 *
 * 用 Query 而非手写请求 + 自定义事件：审批决策后 ApprovalCenter 的
 * mutation 会 invalidate approvalKeys.all，本角标共用该前缀，
 * 因此**自动刷新**。此前需要「emit('approval-decided') → 此处订阅」
 * 的手工通路，发布方与订阅方分离在两个文件，漏一处角标就停在旧数字。
 */
const canSeeApprovals = computed(() => app.isAuthenticated && app.hasRole(['admin']))
const { count: approvalPending } = usePendingApprovalCountQuery(canSeeApprovals)

const prefetchers: Record<string, () => Promise<unknown>> = {
  home: () => import('@/views/Home.vue'),
  knowledge: () => import('@/views/KnowledgeBase.vue'),
  tickets: () => import('@/views/TicketList.vue'),
  dashboard: () => import('@/views/Dashboard.vue'),
  help: () => import('@/views/HelpCenter.vue')
}
const prefetched = new Set<string>()
const hoverTimers = new Map<string, ReturnType<typeof setTimeout>>()

const doPrefetch = (key: string) => {
  if (prefetched.has(key)) return
  prefetched.add(key)
  prefetchers[key]?.().catch(() => prefetched.delete(key))
}

const prefetchWithIntent = (key: string) => {
  if (prefetched.has(key) || hoverTimers.has(key)) return
  const t = setTimeout(() => {
    hoverTimers.delete(key)
    doPrefetch(key)
  }, 150)
  hoverTimers.set(key, t)
}

const cancelPrefetch = (key: string) => {
  const t = hoverTimers.get(key)
  if (t) {
    clearTimeout(t)
    hoverTimers.delete(key)
  }
}

const activeKey = computed(() => {
  const path = route.path
  if (path === '/') return 'home'
  if (path.startsWith('/knowledge')) return 'knowledge'
  if (path.startsWith('/tickets')) return 'tickets'
  if (path.startsWith('/action-items')) return 'action-items'
  if (path.startsWith('/ai-chat')) return 'ai-chat'
  if (path.startsWith('/dashboard')) return 'dashboard'
  if (path.startsWith('/help')) return 'help'
  return 'home'
})

const unreadCount = computed(() =>
  app.settings.notificationsEnabled ? notificationsStore.unreadCount : 0
)

const showNotifications = ref(false)
const showUserMenu = ref(false)
const profileVisible = ref(false)
const settingsVisible = ref(false)

const toggleNotifications = () => {
  showNotifications.value = !showNotifications.value
  showUserMenu.value = false
}

const toggleUserMenu = () => {
  showUserMenu.value = !showUserMenu.value
  showNotifications.value = false
}

const readNotification = (n: AppNotification) => {
  notificationsStore.markRead(n.id)
  showNotifications.value = false
  if (n.linkTo) router.push(n.linkTo)
}

const markAllRead = () => {
  notificationsStore.markAllRead()
  notify.success('已全部标记为已读')
}

const goProfile = () => {
  showUserMenu.value = false
  profileVisible.value = true
}

const goSettings = () => {
  showUserMenu.value = false
  settingsVisible.value = true
}

const doLogout = async () => {
  showUserMenu.value = false
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    // 方向三：调用 app.signOut() 清登录态（后端 Sa-Token 失效 token + 清本地 token）。
    // signOut 为 async（含后端 logout 调用），await 后再跳登录页确保 token 已清。
    await app.signOut()
    notify.success('已退出登录')
    router.push('/login')
  } catch {
    // cancel
  }
}

const handleClickOutside = (e: MouseEvent) => {
  const target = e.target as HTMLElement
  if (!target.closest('.notification-wrapper')) showNotifications.value = false
  if (!target.closest('.user-wrapper')) showUserMenu.value = false
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
  hoverTimers.forEach(t => clearTimeout(t))
  hoverTimers.clear()
})
</script>

<template>
  <nav class="navbar">
    <div class="navbar-container">
      <RouterLink to="/" class="navbar-logo">
        <div class="logo-icon">
          <Monitor :size="20" :stroke-width="2" />
        </div>
        <span class="logo-text">DevOps智能运维</span>
      </RouterLink>

      <div class="nav-links">
        <RouterLink
          v-for="item in navItems"
          :key="item.key"
          :to="item.path"
          class="nav-link"
          :class="{ active: activeKey === item.key }"
          @mouseenter="prefetchWithIntent(item.key)"
          @mouseleave="cancelPrefetch(item.key)"
          @focus="doPrefetch(item.key)"
          @touchstart.passive="doPrefetch(item.key)"
        >
          {{ item.label }}
          <!-- 待审角标：仅审批中心且有待审项时显示 -->
          <span
            v-if="item.key === 'approvals' && approvalPending > 0"
            class="nav-badge"
            :title="`${approvalPending} 项待审批`"
          >{{ approvalPending > 99 ? '99+' : approvalPending }}</span>
        </RouterLink>
      </div>

      <div class="navbar-actions">
        <!-- 访客态：通知与用户菜单都无意义（通知需受保护 API、用户信息为空），改为登录入口 -->
        <RouterLink v-if="!app.isAuthenticated" to="/login" class="login-btn">
          <LogIn :size="16" />
          登录
        </RouterLink>

        <template v-else>
        <!-- Notification -->
        <div class="notification-wrapper">
          <button
            class="notification-btn"
            :class="{ muted: !app.settings.notificationsEnabled }"
            :title="app.settings.notificationsEnabled ? '通知' : '通知已在系统设置中关闭'"
            @click.stop="toggleNotifications"
          >
            <Bell :size="20" :stroke-width="1.5" />
            <span v-if="app.settings.notificationsEnabled && unreadCount > 0" class="notification-badge">{{ unreadCount }}</span>
            <span v-if="!app.settings.notificationsEnabled" class="notification-mute-dot" aria-hidden="true"></span>
          </button>

          <div v-if="showNotifications" class="dropdown notification-dropdown" @click.stop>
            <div class="dropdown-header">
              <span class="dropdown-title">通知</span>
              <button
                v-if="app.settings.notificationsEnabled && unreadCount > 0"
                class="dropdown-action"
                @click="markAllRead"
              >
                <CheckCheck :size="14" />
                全部已读
              </button>
            </div>
            <div v-if="!app.settings.notificationsEnabled" class="notification-muted">
              通知已在系统设置中关闭。
              <button class="link-btn" @click="showNotifications = false; settingsVisible = true">前往开启</button>
            </div>
            <div v-else-if="notificationsStore.items.length === 0" class="notification-muted">
              暂无通知
            </div>
            <div v-else class="notification-list">
              <div
                v-for="n in notificationsStore.items"
                :key="n.id"
                class="notification-item"
                :class="{ unread: !n.read }"
                @click="readNotification(n)"
              >
                <div class="notification-dot" v-if="!n.read"></div>
                <div class="notification-body">
                  <div class="notification-title">{{ n.title }}</div>
                  <div class="notification-time">{{ n.time }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- User Avatar -->
        <div class="user-wrapper">
          <button class="user-avatar-btn" @click.stop="toggleUserMenu" aria-label="用户菜单">
            <AvatarFallback :name="app.currentUser.name" :size="32" />
          </button>
          <div v-if="showUserMenu" class="dropdown user-dropdown" @click.stop>
            <div class="user-card">
              <AvatarFallback :name="app.currentUser.name" :size="40" />
              <div class="user-card-info">
                <div class="user-card-name">{{ app.currentUser.name }}</div>
                <div class="user-card-role">{{ app.currentUser.title || app.roleLabel }}</div>
              </div>
            </div>
            <div class="dropdown-divider"></div>
            <button class="dropdown-item" @click="goProfile">
              <User :size="16" />
              个人中心
            </button>
            <button class="dropdown-item" @click="goSettings">
              <Settings :size="16" />
              系统设置
            </button>
            <div class="dropdown-divider"></div>
            <button class="dropdown-item dropdown-item-danger" @click="doLogout">
              <LogOut :size="16" />
              退出登录
            </button>
          </div>
        </div>
        </template>
      </div>
    </div>

    <ProfileDialog :visible="profileVisible" @update:visible="profileVisible = $event" />
    <SettingsDialog :visible="settingsVisible" @update:visible="settingsVisible = $event" />
  </nav>
</template>

<style scoped lang="scss">
.navbar {
  position: sticky;
  top: 0;
  z-index: 50;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border-light);
}

.navbar-container {
  max-width: 1280px;
  margin: 0 auto;
  padding: 0 24px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.navbar-logo {
  display: flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
}

.logo-icon {
  width: 32px;
  height: 32px;
  background: var(--color-primary);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-inverse);
  flex-shrink: 0;
}

.logo-text {
  font-size: var(--text-base);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.nav-links {
  display: flex;
  align-items: center;
  gap: 4px;
}

.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-secondary);
  text-decoration: none;
  border-radius: var(--radius-sm);
  transition: all 0.15s ease;
  white-space: nowrap;
}

/* 待审角标：跟在导航文字后，不用绝对定位——导航项宽度随文案变化，
   绝对定位会在不同标签下错位 */
.nav-badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--state-error);
  color: #fff;
  font-size: 11px;
  font-weight: var(--weight-semibold);
  line-height: 18px;
  text-align: center;
}

.nav-link:hover {
  color: var(--color-primary);
  background: var(--color-bg);
}

.nav-link.active {
  color: var(--color-primary);
  background: var(--color-primary-lighter);
}

.navbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* Notification */
.notification-wrapper {
  position: relative;
}

/* 访客态登录入口——替代通知铃与用户菜单 */
.login-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 16px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: #fff;
  font-size: var(--text-sm);
  font-weight: var(--weight-medium, 500);
  text-decoration: none;
  transition: opacity 0.15s ease;

  &:hover {
    opacity: 0.88;
  }
}

.notification-btn {
  position: relative;
  padding: 8px;
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: color 0.15s ease;
  display: flex;
  align-items: center;
  justify-content: center;

  &:hover {
    color: var(--color-text-primary);
  }
}

.notification-badge {
  position: absolute;
  top: 2px;
  right: 2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--state-error);
  color: white;
  font-size: 10px;
  font-weight: var(--weight-semibold);
  line-height: 16px;
  text-align: center;
}

.notification-btn.muted {
  opacity: 0.55;

  &:hover { opacity: 0.85; }
}

.notification-mute-dot {
  position: absolute;
  bottom: 4px;
  right: 4px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-text-tertiary);
  border: 2px solid var(--color-surface);
  box-sizing: content-box;
}

.notification-muted {
  padding: 20px 16px;
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;

  .link-btn {
    display: inline;
    border: none;
    background: transparent;
    padding: 0;
    margin-left: 4px;
    color: var(--color-primary);
    font-size: var(--text-sm);
    font-family: var(--font-body);
    cursor: pointer;
    text-decoration: underline;

    &:hover { color: var(--color-primary-dark); }
  }
}

/* User avatar */
.user-wrapper {
  position: relative;
}

.user-avatar-btn {
  border: none;
  background: transparent;
  padding: 0;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.15s ease;

  &:hover { transform: scale(1.05); }
  &:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
}

/* Dropdown base */
.dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 60;
  overflow: hidden;
  animation: dropdown-in 0.15s ease;
}

@keyframes dropdown-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.notification-dropdown {
  width: 340px;
}

.dropdown-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border-light);
}

.dropdown-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.dropdown-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: none;
  background: transparent;
  font-size: var(--text-xs);
  color: var(--color-primary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background 0.15s ease;

  &:hover {
    background: var(--color-primary-lighter);
  }
}

.notification-list {
  max-height: 360px;
  overflow-y: auto;
}

.notification-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.15s ease;

  &:not(:last-child) {
    border-bottom: 1px solid var(--color-border-light);
  }

  &:hover {
    background: var(--color-bg);
  }

  &.unread {
    background: var(--color-primary-lighter);

    &:hover {
      background: var(--color-primary-lighter);
      filter: brightness(0.98);
    }
  }
}

.notification-dot {
  width: 6px;
  height: 6px;
  margin-top: 8px;
  border-radius: 50%;
  background: var(--color-primary);
  flex-shrink: 0;
}

.notification-body {
  flex: 1;
  min-width: 0;
}

.notification-title {
  font-size: var(--text-sm);
  color: var(--color-text-primary);
  line-height: 1.4;
  margin-bottom: 4px;
}

.notification-time {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

/* User dropdown */
.user-dropdown {
  width: 240px;
  padding: 8px;
}

.user-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
}

.user-card-info {
  min-width: 0;
}

.user-card-name {
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.user-card-role {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  margin-top: 2px;
}

.dropdown-divider {
  height: 1px;
  background: var(--color-border-light);
  margin: 4px 0;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  background: transparent;
  font-size: var(--text-sm);
  font-family: var(--font-body);
  color: var(--color-text-primary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  text-align: left;
  transition: background 0.15s ease;

  &:hover {
    background: var(--color-primary-lighter);
    color: var(--color-primary);
  }

  &.dropdown-item-danger {
    color: var(--state-error);

    &:hover {
      background: rgba(220, 38, 38, 0.08);
      color: var(--state-error);
    }
  }
}

/* ===== Dark Variant (Pattern B) ===== */
.navbar--dark {
  background: var(--color-primary);
  border-bottom: none;

  .logo-icon {
    background: rgba(255, 255, 255, 0.15);
    color: white;
  }

  .logo-text {
    color: white;
  }

  .nav-link {
    color: rgba(255, 255, 255, 0.7);

    &:hover {
      color: white;
      background: rgba(255, 255, 255, 0.08);
    }

    &.active {
      color: white;
      background: rgba(255, 255, 255, 0.12);
    }
  }

  .notification-btn {
    color: rgba(255, 255, 255, 0.7);

    &:hover {
      color: white;
    }
  }

  .notification-mute-dot {
    border-color: var(--color-primary);
  }

  .user-avatar-btn {
    &:focus-visible {
      outline-color: rgba(255, 255, 255, 0.7);
    }
  }
}

@media (max-width: 768px) {
  .navbar-container {
    width: 100%;
    max-width: none;
    padding: 0 12px;
    gap: 8px;
    box-sizing: border-box;
    overflow: hidden;
  }

  .logo-text,
  .notification-wrapper {
    display: none;
  }

  .nav-links {
    flex: 1;
    min-width: 0;
    justify-content: center;
  }

  .nav-link {
    display: none;
  }

  .nav-link.active {
    display: inline-flex;
    padding-inline: 11px;
  }

  .navbar-actions {
    flex-shrink: 0;
    gap: 4px;
  }
}
</style>
