<script setup lang="ts">
/**
 * AppSidebar —— 左侧导航栏（2026-09-27 侧栏布局壳改造）。
 *
 * 取代原顶栏导航（AppNavbar 已拆分：导航归本组件，搜索/通知归 AppTopBar）。
 * SRE 控制台的行业惯例形态：导航在左侧竖排，比顶栏横排更能容纳
 * 图标 + 中文标签 + 角标，窄屏时整体收进抽屉（见 useMobileNavState）。
 *
 * 数据源不变：导航项仍来自 config/navigation.ts（RBAC 过滤），
 * 待审角标仍由 usePendingApprovalCountQuery 驱动。
 *
 * 批93 拆分：桌面导航行 → SidebarDesktopNav，移动抽屉 → SidebarMobileDrawer
 * （滚动锁随块迁移），悬停预取 → composables/useNavPrefetch；本组件保留
 * 导航状态/折叠/高亮/图标映射，折叠态的行级排版经 :deep() 下发给子组件
 * ——AppSidebar.test 对宿主 vm 的断言因此原样成立。
 */
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  LayoutDashboard, BookOpen, Ticket, Bell, Activity, ListChecks, Gauge,
  ClipboardCheck, Monitor, Settings, ChevronsLeft, ChevronsRight, Sparkles,
} from 'lucide-vue-next'
import { useAppStore, type Role } from '@/stores/app'
import { primaryNavigationItems } from '@/config/navigation'
import { usePendingApprovalCountQuery } from '@/api/queries/approval.query'
import { useNavPrefetch } from '@/composables/useNavPrefetch'
import { loadPersisted, savePersisted } from '@/utils/persist'
import SidebarDesktopNav from '@/components/common/SidebarDesktopNav.vue'
import SidebarMobileDrawer from '@/components/common/SidebarMobileDrawer.vue'

const route = useRoute()
const app = useAppStore()

// 方向 F RBAC：按当前用户角色过滤导航项（不变契约）
const navItems = computed(() =>
  primaryNavigationItems.filter(i => !i.roles || app.hasRole(i.roles as Role[]))
)

/** 处置中心待审角标（审批决策后由 query invalidation 自动刷新） */
const canSeeApprovals = computed(() => app.isAuthenticated && app.hasRole(['admin']))
const { count: approvalPending } = usePendingApprovalCountQuery(canSeeApprovals)

/** 侧栏折叠：收起为纯图标轨，状态跨会话持久化（用户偏好） */
const SIDEBAR_COLLAPSED_KEY = 'sidebar-collapsed'
const collapsed = ref(loadPersisted<boolean>(SIDEBAR_COLLAPSED_KEY, 1) ?? false)
const toggleCollapse = () => {
  collapsed.value = !collapsed.value
  savePersisted(SIDEBAR_COLLAPSED_KEY, collapsed.value, 1)
}

/** 导航图标：key → lucide 组件（navigation.ts 管清单与顺序，这里只管视觉映射） */
const NAV_ICONS: Record<string, typeof LayoutDashboard> = {
  home: LayoutDashboard,
  assistant: Sparkles,
  knowledge: BookOpen,
  tickets: Ticket,
  alerts: Bell,
  monitoring: Activity,
  'action-items': ListChecks,
  effectiveness: Gauge,
  disposal: ClipboardCheck,
  settings: Settings,
}

const activeKey = computed(() => {
  const path = route.path
  if (path === '/') return 'home'
  if (path.startsWith('/assistant')) return 'assistant'
  if (path.startsWith('/knowledge')) return 'knowledge'
  if (path.startsWith('/tickets')) return 'tickets'
  if (path.startsWith('/alerts')) return 'alerts'
  if (path.startsWith('/monitoring')) return 'monitoring'
  if (path.startsWith('/effectiveness')) return 'effectiveness'
  if (path.startsWith('/action-items')) return 'action-items'
  // 处置中心：审批/自愈旧路径（重定向前夕）与自愈详情深链都归属它
  if (path.startsWith('/disposal') || path.startsWith('/approvals') || path.startsWith('/self-healing')) return 'disposal'
  // 设置页（含治理旧路由重定向落入）高亮「系统设置」
  if (path.startsWith('/settings')) return 'settings'
  // 未覆盖路径（帮助等）：不高亮任何项——高亮「首页」会误导用户
  return ''
})

// 悬停预取（批93 下沉 useNavPrefetch，去重/定时器/卸载清理行为不变）
const prefetch = useNavPrefetch()
</script>

<template>
  <!-- 桌面侧栏：窄屏整体隐藏，由 AppTopBar 的汉堡 + 底部抽屉接管 -->
  <aside class="sidebar" :class="{ collapsed }">
    <!-- 顶部：logo + 折叠开关（折叠钮放顶部是 IDE/控制台惯例，拇指不用够到底部） -->
    <div class="sidebar-head">
      <RouterLink to="/" class="sidebar-logo">
        <div class="logo-icon">
          <Monitor :size="18" />
        </div>
        <div class="logo-text">
          <span class="logo-name">OpsBrain AI</span>
          <span class="logo-sub">智维大脑 · SRE 平台</span>
        </div>
      </RouterLink>
      <button
        class="collapse-btn"
        type="button"
        :title="collapsed ? '展开导航' : '收起为图标轨'"
        :aria-label="collapsed ? '展开导航' : '收起为图标轨'"
        @click="toggleCollapse"
      >
        <ChevronsRight v-if="collapsed" :size="16" />
        <ChevronsLeft v-else :size="16" />
      </button>
    </div>

    <SidebarDesktopNav
      :nav-items="navItems"
      :collapsed="collapsed"
      :active-key="activeKey"
      :approval-pending="approvalPending"
      :icons="NAV_ICONS"
      :prefetch="prefetch"
    />
  </aside>

  <SidebarMobileDrawer
    :nav-items="navItems"
    :active-key="activeKey"
    :approval-pending="approvalPending"
    :icons="NAV_ICONS"
  />
</template>

<style scoped lang="scss">
/* ===== 桌面侧栏壳（导航行样式在 SidebarDesktopNav，折叠态排版 :deep 下发） ===== */
.sidebar {
  display: flex;
  flex-direction: column;
  width: 200px;
  flex-shrink: 0;
  height: 100vh;
  position: sticky;
  top: 0;
  background: var(--surface-1);
  border-right: 1px solid var(--border-1);
}

/* 顶部行：logo（左，可点首页）+ 折叠开关（右） */
.sidebar-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 14px 12px;
  border-bottom: 1px solid var(--border-1);
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  text-decoration: none;
}

.logo-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-lg);
  background: linear-gradient(135deg, var(--brand) 0%, var(--brand-active) 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--brand-fg);
  flex-shrink: 0;
  box-shadow: var(--shadow-sm);
}

.logo-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.logo-name {
  font-size: var(--text-sm);
  font-weight: 700;
  color: var(--text-1);
  letter-spacing: -0.01em;
}

.logo-sub {
  font-size: 10px;
  color: var(--text-3);
}

/* ===== 顶部折叠开关（图标钮） ===== */
.collapse-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  padding: 0;
  border: none;
  border-radius: var(--radius);
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);

  &:hover {
    background: var(--surface-hover);
    color: var(--text-1);
  }
}

/* ===== 折叠态（纯图标轨，56px） =====
   作用域属性只打在最后一个选择器上：子组件后代不带父 scope，
   行级/图标/角标样式必须 :deep 下发，否则静默失样式（测试查 class 查不出）。 */
.sidebar.collapsed {
  width: 56px;

  /* 顶部行转竖排：logo 图标在上，折叠钮在下，都居中 */
  .sidebar-head {
    flex-direction: column;
    padding: 14px 0 12px;
    gap: 8px;
  }

  .sidebar-logo {
    flex: none;
    justify-content: center;
  }

  .logo-text { display: none; }

  :deep(.nav-item) {
    justify-content: center;
    padding: 10px 0;
    gap: 0;
  }

  :deep(.nav-chevron) { display: none; }

  /* 折叠时角标退化为圆点（数字放不下，有就是有） */
  :deep(.nav-badge--dot) {
    min-width: 8px;
    width: 8px;
    height: 8px;
    padding: 0;
    border-radius: 50%;
    margin-left: 4px;
  }
}
</style>
