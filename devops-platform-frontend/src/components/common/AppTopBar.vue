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
 *
 * 批93 拆分：通知区/用户菜单的展示与样式下沉 TopBarNotifications /
 * TopBarUserMenu（纯 props+emit），本组件保留状态、跳转语义与
 * 点外关闭——AppTopBar.test 对宿主 vm 的断言因此原样成立。
 */
import { computed, ref, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { LogIn, Menu } from 'lucide-vue-next'
import { ElMessageBox } from 'element-plus'
import GlobalSearchBar from '@/components/common/GlobalSearchBar.vue'
import ProfileDialog from '@/components/common/ProfileDialog.vue'
import TopBarNotifications from '@/components/common/TopBarNotifications.vue'
import TopBarUserMenu from '@/components/common/TopBarUserMenu.vue'
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

/** 通知下拉里的「前往开启」：关面板 + 去系统设置（原内联，抽成具名函数上抛口） */
const openSettingsFromNotice = () => {
  showNotifications.value = false
  void router.push('/settings')
}

const handleClickOutside = (e: MouseEvent) => {
  const target = e.target as HTMLElement
  // 子组件根节点即 .notification-wrapper / .user-menu-wrapper（closest 跨界命中）
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

      <TopBarNotifications
        v-else
        :show="showNotifications"
        :unread="unreadCount"
        :enabled="app.settings.notificationsEnabled"
        :items="notificationsStore.items"
        @toggle="toggleNotifications"
        @read="readNotification"
        @mark-all="markAllRead"
        @open-settings="openSettingsFromNotice"
      />

      <!-- 用户菜单：右上角账户区（从头像点开，下拉含个人中心/设置/帮助/退出） -->
      <TopBarUserMenu
        v-if="app.isAuthenticated"
        :show="showUserMenu"
        :name="app.currentUser.name"
        :role="app.currentUser.title || app.roleLabel"
        @toggle="toggleUserMenu"
        @profile="goProfile"
        @settings="goSettings"
        @help="goHelp"
        @logout="doLogout"
      />
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
