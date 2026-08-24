<script setup lang="ts">
/**
 * 通用折叠面板组件
 *
 * 特性：
 * - 标题 + 图标 + 展开/收缩
 * - 折叠状态持久化到 localStorage（可选）
 * - 平滑展开动画
 * - 可访问性：aria-expanded
 */
import { ref, watch } from 'vue'
import { ChevronDown, ChevronRight } from 'lucide-vue-next'
import type { Component } from 'vue'

const props = withDefaults(defineProps<{
  /** 面板标题 */
  title: string
  /** 标题图标（lucide 组件） */
  icon?: Component
  /** 是否默认折叠 */
  defaultCollapsed?: boolean
  /** localStorage 持久化 key（传入则持久化，不传则仅内存态） */
  storageKey?: string
  /** 右侧额外内容（如计数徽标） */
  badge?: string | number
}>(), {
  defaultCollapsed: false,
})

const collapsed = ref(props.defaultCollapsed)

// 从 localStorage 恢复
if (props.storageKey) {
  try {
    const saved = localStorage.getItem(props.storageKey)
    if (saved !== null) collapsed.value = saved === 'true'
  } catch {
    // localStorage 不可用
  }
}

// 持久化
watch(collapsed, (v) => {
  if (props.storageKey) {
    try {
      localStorage.setItem(props.storageKey, String(v))
    } catch {
      // 忽略
    }
  }
})

const toggle = () => { collapsed.value = !collapsed.value }
</script>

<template>
  <div class="collapsible-card" :class="{ 'is-collapsed': collapsed }">
    <div class="card-header" @click="toggle" role="button" :aria-expanded="!collapsed" tabindex="0">
      <span class="card-title">
        <component :is="icon" v-if="icon" :size="16" />
        {{ title }}
        <span v-if="badge !== undefined" class="card-badge">{{ badge }}</span>
      </span>
      <component :is="collapsed ? ChevronRight : ChevronDown" :size="16" class="toggle-icon" />
    </div>
    <div v-show="!collapsed" class="card-body">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.collapsible-card {
  background: white;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-lg, 12px);
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,0.04));
  overflow: hidden;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
}
.card-header:hover {
  background: var(--color-surface-hover, var(--surface-2));
}
.card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-primary, var(--text-1));
}
.card-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  font-size: 0.6875rem;
  font-weight: 600;
  background: var(--color-primary-lighter, var(--brand-subtle));
  color: var(--color-primary, var(--brand));
  border-radius: 9px;
}
.toggle-icon {
  color: var(--color-text-tertiary, var(--text-3));
  transition: transform 0.2s;
}
.card-body {
  padding: 0 16px 16px;
}
.is-collapsed .card-header {
  border-bottom: none;
}
</style>
