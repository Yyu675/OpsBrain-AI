<script setup lang="ts">
/**
 * SidebarMobileDrawer —— 移动端导航抽屉（≤768px，批93 自 AppSidebar 拆出）。
 *
 * 开态是与 AppTopBar 汉堡的共享事实（useMobileNavState 模块单例）；
 * body 滚动锁随块迁移过来（原 AppSidebar 的 watch + 卸载恢复——
 * 登出跳转后整页滚不动的教训，行为一字不改）。
 */
import { watch, onBeforeUnmount } from 'vue'
import type { Component } from 'vue'
import { Monitor, X } from 'lucide-vue-next'
import { useMobileNavState } from '@/composables/useMobileNavState'

const { open: mobileNavOpen, close: closeMobileNav } = useMobileNavState()

/** 抽屉打开时锁 body 滚动；卸载时无条件恢复（登出跳转后整页滚不动的教训） */
watch(mobileNavOpen, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
})

defineProps<{
  navItems: Array<{ key: string; label: string; path: string }>
  activeKey: string
  approvalPending: number
  icons: Record<string, Component>
}>()
</script>

<template>
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
          <component :is="icons[item.key] ?? Monitor" :size="16" />
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
/* 待审角标（与 SidebarDesktopNav 同样式，scoped 各持一份） */
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

/* ===== 移动端抽屉 ===== */
.mobile-nav-backdrop,
.mobile-nav-drawer {
  display: none;
}

@media (max-width: 768px) {
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
