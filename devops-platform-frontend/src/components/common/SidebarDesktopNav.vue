<script setup lang="ts">
/**
 * SidebarDesktopNav —— 桌面侧栏导航行列表（批93 自 AppSidebar 拆出）。
 *
 * 父层保留 aside 壳（logo/折叠钮/折叠态宽度），本组件只渲染导航项行；
 * 折叠态的行级排版由父层 :deep(.nav-item) 下发——scoped 属性只打在最后
 * 一个选择器上，子组件后代不带父 scope，不穿透会静默失样式（测试只查
 * class 查不出这坑，暗色/折叠目检才会暴露）。
 */
import type { Component } from 'vue'
import { ChevronRight, Monitor } from 'lucide-vue-next'

interface Props {
  navItems: Array<{ key: string; label: string; path: string }>
  collapsed: boolean
  activeKey: string
  approvalPending: number
  icons: Record<string, Component>
  prefetch: {
    prefetchWithIntent: (key: string) => void
    cancelPrefetch: (key: string) => void
    doPrefetch: (key: string) => void
  }
}

defineProps<Props>()
</script>

<template>
  <nav class="sidebar-nav" aria-label="主导航">
    <RouterLink
      v-for="item in navItems"
      :key="item.key"
      :to="item.path"
      class="nav-item"
      :class="{ active: activeKey === item.key }"
      :title="collapsed ? item.label : undefined"
      @mouseenter="prefetch.prefetchWithIntent(item.key)"
      @mouseleave="prefetch.cancelPrefetch(item.key)"
      @focus="prefetch.doPrefetch(item.key)"
      @touchstart.passive="prefetch.doPrefetch(item.key)"
    >
      <component :is="icons[item.key] ?? Monitor" :size="16" class="nav-icon" />
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
</template>

<style scoped lang="scss">
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
  color: var(--text-inverse, #fff);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
}
</style>
