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
 */
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import {
  LayoutDashboard, BookOpen, Ticket, Bell, Activity, ListChecks, Gauge,
  ClipboardCheck, Monitor, Settings, ChevronRight, ChevronsLeft, ChevronsRight, X,
} from 'lucide-vue-next'
import { useAppStore, type Role } from '@/stores/app'
import { primaryNavigationItems } from '@/config/navigation'
import { usePendingApprovalCountQuery } from '@/api/queries/approval.query'
import { useMobileNavState } from '@/composables/useMobileNavState'
import { loadPersisted, savePersisted } from '@/utils/persist'

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

// ==================== 悬停预取（从 AppNavbar 原样迁移） ====================

const prefetchers: Record<string, () => Promise<unknown>> = {
  home: () => import('@/views/Home.vue'),
  knowledge: () => import('@/views/KnowledgeBase.vue'),
  tickets: () => import('@/views/TicketList.vue'),
  disposal: () => import('@/views/DisposalCenter.vue'),
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

// ==================== 移动端抽屉 ====================

const { open: mobileNavOpen, close: closeMobileNav } = useMobileNavState()

/** 抽屉打开时锁 body 滚动；卸载时无条件恢复（登出跳转后整页滚不动的教训） */
watch(mobileNavOpen, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
})

// 用户菜单已迁到 AppTopBar 右上角（2026-09-27 布局调整）

onBeforeUnmount(() => {
  hoverTimers.forEach(t => clearTimeout(t))
  hoverTimers.clear()
  document.body.style.overflow = ''
})
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

    <nav class="sidebar-nav" aria-label="主导航">
      <RouterLink
        v-for="item in navItems"
        :key="item.key"
        :to="item.path"
        class="nav-item"
        :class="{ active: activeKey === item.key }"
        :title="collapsed ? item.label : undefined"
        @mouseenter="prefetchWithIntent(item.key)"
        @mouseleave="cancelPrefetch(item.key)"
        @focus="doPrefetch(item.key)"
        @touchstart.passive="doPrefetch(item.key)"
      >
        <component :is="NAV_ICONS[item.key] ?? Monitor" :size="16" class="nav-icon" />
        <span v-if="!collapsed" class="nav-label">{{ item.label }}</span>
        <span
          v-if="item.key === 'disposal' && approvalPending > 0"
          class="nav-badge"
          :class="{ 'nav-badge--dot': collapsed }"
          :title="`${approvalPending} 项待审批`"
        >{{ collapsed ? '' : (approvalPending > 99 ? '99+' : approvalPending) }}</span>
        <ChevronRight v-else-if="!collapsed && activeKey === item.key" :size="14" class="nav-chevron" />
      </RouterLink>
    </nav>
  </aside>

  <!-- 移动端抽屉（≤768px，由 AppTopBar 的汉堡触发） -->
  <Teleport to="body">
    <div
      v-if="mobileNavOpen"
      class="mobile-nav-backdrop"
      @click="closeMobileNav"
    ></div>
    <div
      id="mobile-nav-drawer"
      class="mobile-nav-drawer"
      :class="{ open: mobileNavOpen }"
      :aria-hidden="!mobileNavOpen"
    >
      <div class="mobile-nav-head">
        <span class="mobile-nav-title">导航</span>
        <button class="mobile-nav-close" aria-label="关闭导航" @click="closeMobileNav">
          <X :size="18" />
        </button>
      </div>
      <nav class="mobile-nav-list">
        <RouterLink
          v-for="item in navItems"
          :key="item.key"
          :to="item.path"
          class="mobile-nav-item"
          :class="{ active: activeKey === item.key }"
        >
          <component :is="NAV_ICONS[item.key] ?? Monitor" :size="16" />
          <span>{{ item.label }}</span>
          <span
            v-if="item.key === 'disposal' && approvalPending > 0"
            class="nav-badge"
          >{{ approvalPending > 99 ? '99+' : approvalPending }}</span>
        </RouterLink>
      </nav>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
/* ===== 桌面侧栏 ===== */
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

.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 10px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: var(--radius);
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--text-2);
  text-decoration: none;
  white-space: nowrap;
  transition: background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);

  &:hover {
    background: var(--surface-hover);
    color: var(--text-1);
  }

  &.active {
    background: var(--brand-subtle);
    color: var(--brand);
    font-weight: 600;
  }
}

.nav-icon {
  flex-shrink: 0;
  color: var(--text-3);
  transition: color var(--duration-fast) var(--ease-out);

  .nav-item:hover & { color: var(--text-2); }
  .nav-item.active & { color: var(--brand); }
}

.nav-label {
  flex: 1;
  min-width: 0;
}

.nav-chevron {
  color: var(--brand);
  opacity: 0.7;
}

/* 待审角标：行内跟随，不绝对定位（导航项宽度随文案变） */
.nav-badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
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

/* ===== 折叠态（纯图标轨，56px） ===== */
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

  .nav-item {
    justify-content: center;
    padding: 10px 0;
    gap: 0;
  }

  .nav-chevron { display: none; }

  /* 折叠时角标退化为圆点（数字放不下，有就是有） */
  .nav-badge--dot {
    min-width: 8px;
    width: 8px;
    height: 8px;
    padding: 0;
    border-radius: 50%;
    margin-left: 4px;
  }
}

/* ===== 移动端抽屉 ===== */
.mobile-nav-backdrop,
.mobile-nav-drawer {
  display: none;
}

@media (max-width: 768px) {
  .sidebar {
    display: none;
  }

  .mobile-nav-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 40;
    background: oklch(0 0 0 / 0.45);
  }

  .mobile-nav-drawer {
    display: flex;
    flex-direction: column;
    position: fixed;
    top: 0;
    left: 0;
    width: min(76vw, 300px);
    height: 100vh;
    z-index: 45;
    padding: 8px 0;
    background: var(--surface-1);
    border-right: 1px solid var(--border-1);
    box-shadow: var(--shadow-lg);
    overflow-y: auto;
    /* 关闭态 translateX + visibility 双管：抽屉里的链接不该留在 tab 顺序里 */
    transform: translateX(-100%);
    visibility: hidden;
    transition: transform var(--duration-normal) var(--ease-out),
      visibility var(--duration-normal) var(--ease-out);

    &.open {
      transform: translateX(0);
      visibility: visible;
    }
  }

  .mobile-nav-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 12px 10px 16px;
    border-bottom: 1px solid var(--border-1);
  }

  .mobile-nav-title {
    font-size: var(--text-sm);
    font-weight: 600;
    color: var(--text-3);
  }

  .mobile-nav-close {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
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

  .mobile-nav-list {
    display: flex;
    flex-direction: column;
    padding: 8px;
    gap: 2px;
  }

  .mobile-nav-item {
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 44px;
    padding: 0 12px;
    border-radius: var(--radius);
    color: var(--text-2);
    font-size: var(--text-sm);
    text-decoration: none;

    &:hover {
      background: var(--surface-hover);
      color: var(--text-1);
    }

    &.active {
      background: var(--brand-subtle);
      color: var(--brand);
      font-weight: 600;
    }
  }
}
</style>
