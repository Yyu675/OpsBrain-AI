<script setup lang="ts">
/**
 * AppTopBar —— 内容区顶部窄条（2026-09-27 侧栏布局壳改造）。
 *
 * 职责（从原 AppNavbar 拆分而来）：全局搜索 + 通知铃铛 + 用户菜单 +
 * 访客登录入口 + 移动端汉堡。导航归 AppSidebar，本组件不碰。
 * 页面标题由各页自带 page-header 承载，这里不重复显示。
 *
 * 用户菜单 2026-09-27 从侧栏左下迁到右上：顶部右侧是行业惯例的
 * 账户区位置（GitHub/Grafana/Cloud 控制台皆然），且侧栏折叠成
 * 图标轨后左下已放不下用户卡。
 */
import { computed, ref, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, CheckCheck, LogIn, LogOut, LifeBuoy, Menu, Settings, User } from 'lucide-vue-next'
import { ElMessageBox } from 'element-plus'
import GlobalSearchBar from '@/components/common/GlobalSearchBar.vue'
import AvatarFallback from '@/components/common/AvatarFallback.vue'
import ProfileDialog from '@/components/common/ProfileDialog.vue'
import { useAppStore } from '@/stores/app'
import { useNotificationsStore, type AppNotification } from '@/stores/notifications'
import { useMobileNavState } from '@/composables/useMobileNavState'
import { notify } from '@/utils/notify'

const router = useRouter()
const app = useAppStore()
const notificationsStore = useNotificationsStore()

const { toggle: toggleMobileNav } = useMobileNavState()

const unreadCount = computed(() =>
  app.settings.notificationsEnabled ? notificationsStore.unreadCount : 0
)

const showNotifications = ref(false)

const toggleNotifications = () => { showNotifications.value = !showNotifications.value }

const readNotification = (n: AppNotification) => {
  notificationsStore.markRead(n.id)
  showNotifications.value = false
  if (n.linkTo) router.push(n.linkTo)
}

const markAllRead = () => {
  notificationsStore.markAllRead()
  notify.success('已全部标记为已读')
}

const handleClickOutside = (e: MouseEvent) => {
  const target = e.target as HTMLElement
  if (!target.closest('.notification-wrapper')) showNotifications.value = false
  if (!target.closest('.user-menu-wrapper')) showUserMenu.value = false
}

// ==================== 用户菜单（右上角账户区） ====================

const showUserMenu = ref(false)
const profileVisible = ref(false)

const toggleUserMenu = () => { showUserMenu.value = !showUserMenu.value }

const goProfile = () => {
  showUserMenu.value = false
  profileVisible.value = true
}

const goSettings = () => {
  showUserMenu.value = false
  void router.push('/settings')
}

// 帮助中心入口在用户菜单（2026-09-27 导航收敛）
const goHelp = () => {
  showUserMenu.value = false
  void router.push('/help')
}

const doLogout = async () => {
  showUserMenu.value = false
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await app.signOut()
    notify.success('已退出登录')
    router.push('/login')
  } catch {
    // cancel
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <header class="topbar">
    <!-- 汉堡：仅窄屏可见（CSS 控制），打开侧栏抽屉 -->
    <button
      class="mobile-nav-toggle"
      type="button"
      aria-label="打开导航菜单"
      aria-controls="mobile-nav-drawer"
      @click.stop="toggleMobileNav"
    >
      <Menu :size="20" />
    </button>

    <div class="topbar-search">
      <GlobalSearchBar v-if="app.isAuthenticated" />
    </div>

    <div class="topbar-actions">
      <!-- 访客态：通知无意义（需受保护 API），改为登录入口 -->
      <RouterLink v-if="!app.isAuthenticated" to="/login" class="login-btn">
        <LogIn :size="15" />
        登录
      </RouterLink>

      <div v-else class="notification-wrapper">
        <button
          class="notification-btn"
          :class="{ muted: !app.settings.notificationsEnabled }"
          :title="app.settings.notificationsEnabled ? '通知' : '通知已在系统设置中关闭'"
          @click.stop="toggleNotifications"
        >
          <Bell :size="18" :stroke-width="1.8" />
          <span v-if="app.settings.notificationsEnabled && unreadCount > 0" class="notification-badge">{{ unreadCount }}</span>
          <span v-if="!app.settings.notificationsEnabled" class="notification-mute-dot" aria-hidden="true"></span>
        </button>

        <div v-if="showNotifications" class="notification-dropdown" @click.stop>
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
            <button class="link-btn" @click="showNotifications = false; void router.push('/settings')">前往开启</button>
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

      <!-- 用户菜单：右上角账户区（从头像点开，下拉含个人中心/设置/帮助/退出） -->
      <div v-if="app.isAuthenticated" class="user-menu-wrapper">
        <button
          class="user-btn"
          type="button"
          :title="`${app.currentUser.name}（${app.currentUser.title || app.roleLabel}）`"
          @click.stop="toggleUserMenu"
        >
          <AvatarFallback :name="app.currentUser.name" :size="28" />
        </button>

        <div v-if="showUserMenu" class="user-dropdown" @click.stop>
          <div class="user-dropdown-head">
            <span class="user-dropdown-name">{{ app.currentUser.name }}</span>
            <span class="user-dropdown-role">{{ app.currentUser.title || app.roleLabel }}</span>
          </div>
          <button class="dropdown-item" @click="goProfile">
            <User :size="15" />
            个人中心
          </button>
          <button class="dropdown-item" @click="goSettings">
            <Settings :size="15" />
            系统设置
          </button>
          <button class="dropdown-item" @click="goHelp">
            <LifeBuoy :size="15" />
            帮助中心
          </button>
          <div class="dropdown-divider"></div>
          <button class="dropdown-item dropdown-item-danger" @click="doLogout">
            <LogOut :size="15" />
            退出登录
          </button>
        </div>
      </div>
    </div>
  </header>

  <ProfileDialog :visible="profileVisible" @update:visible="profileVisible = $event" />
</template>

<style scoped lang="scss">
.topbar {
  position: sticky;
  top: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 52px;
  padding: 0 20px;
  background: var(--surface-1);
  border-bottom: 1px solid var(--border-1);
}

.mobile-nav-toggle {
  display: none;
}

.topbar-search {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: center;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

/* 访客登录入口 */
.login-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 16px;
  border-radius: var(--radius);
  background: var(--brand);
  color: var(--brand-fg);
  font-size: var(--text-sm);
  font-weight: 500;
  text-decoration: none;
  transition: background var(--duration-fast) var(--ease-out);

  &:hover { background: var(--brand-hover); }
}

/* ===== 通知 ===== */
.notification-wrapper {
  position: relative;
}

.notification-btn {
  position: relative;
  width: 36px;
  height: 36px;
  border: 1px solid var(--border-1);
  background: var(--surface-1);
  color: var(--text-2);
  cursor: pointer;
  border-radius: var(--radius);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);

  &:hover {
    background: var(--surface-hover);
    color: var(--text-1);
  }

  &.muted {
    opacity: 0.55;

    &:hover { opacity: 0.85; }
  }
}

.notification-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--danger);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
  border: 2px solid var(--surface-1);
  box-sizing: content-box;
}

.notification-mute-dot {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-3);
  border: 2px solid var(--surface-1);
  box-sizing: content-box;
}

.notification-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: 340px;
  background: var(--surface-3);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 60;
  overflow: hidden;
  animation: dropdown-in var(--duration-fast) var(--ease-out);
}

@keyframes dropdown-in {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.dropdown-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-1);
}

.dropdown-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-1);
}

.dropdown-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: none;
  background: transparent;
  font-size: var(--text-xs);
  color: var(--brand);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background var(--duration-fast) var(--ease-out);

  &:hover { background: var(--brand-subtle); }
}

.notification-muted {
  padding: 20px 16px;
  text-align: center;
  font-size: var(--text-sm);
  color: var(--text-2);
  line-height: 1.6;

  .link-btn {
    display: inline;
    border: none;
    background: transparent;
    padding: 0;
    margin-left: 4px;
    color: var(--brand);
    font-size: var(--text-sm);
    cursor: pointer;
    text-decoration: underline;

    &:hover { color: var(--brand-active); }
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
  transition: background var(--duration-fast) var(--ease-out);

  &:not(:last-child) {
    border-bottom: 1px solid var(--border-1);
  }

  &:hover { background: var(--surface-hover); }

  &.unread { background: var(--brand-subtle); }
}

.notification-dot {
  width: 6px;
  height: 6px;
  margin-top: 8px;
  border-radius: 50%;
  background: var(--brand);
  flex-shrink: 0;
}

.notification-body {
  flex: 1;
  min-width: 0;
}

.notification-title {
  font-size: var(--text-sm);
  color: var(--text-1);
  line-height: 1.4;
  margin-bottom: 4px;
}

.notification-time {
  font-size: var(--text-xs);
  color: var(--text-3);
}

/* ===== 用户菜单（右上角账户区） ===== */
.user-menu-wrapper {
  position: relative;
}

.user-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--border-1);
  border-radius: 50%;
  background: var(--surface-1);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out);

  &:hover {
    background: var(--surface-hover);
    border-color: var(--border-2, var(--border-1));
  }
}

.user-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: 200px;
  padding: 6px;
  background: var(--surface-3);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 60;
  animation: dropdown-in var(--duration-fast) var(--ease-out);
}

.user-dropdown-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px 10px;
  border-bottom: 1px solid var(--border-1);
  margin-bottom: 4px;
}

.user-dropdown-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-dropdown-role {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border: none;
  background: transparent;
  font-size: var(--text-sm);
  color: var(--text-1);
  cursor: pointer;
  border-radius: var(--radius-sm);
  text-align: left;
  transition: background var(--duration-fast) var(--ease-out);

  &:hover { background: var(--surface-hover); }
}

.dropdown-item-danger {
  color: var(--danger);

  &:hover { background: var(--danger-subtle, var(--surface-hover)); }
}

.dropdown-divider {
  height: 1px;
  margin: 4px 0;
  background: var(--border-1);
}

/* ===== 窄屏：汉堡出现，搜索收窄 ===== */
@media (max-width: 768px) {
  .topbar {
    padding: 0 12px;
    gap: 8px;
  }

  .mobile-nav-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    /* 44×44 是 WCAG 2.1 AA 的最小可点击区域 */
    width: 40px;
    height: 40px;
    flex-shrink: 0;
    padding: 0;
    border: none;
    border-radius: var(--radius);
    background: transparent;
    color: var(--text-2);
    cursor: pointer;

    &:hover {
      background: var(--surface-hover);
      color: var(--text-1);
    }
  }

  .topbar-search {
    justify-content: flex-start;
  }
}
</style>
