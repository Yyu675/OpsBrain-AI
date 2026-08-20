<script setup lang="ts">
import type { Component } from 'vue'
import { Inbox } from 'lucide-vue-next'

interface Props {
  icon?: Component
  title?: string
  description?: string
  actionLabel?: string
  size?: 'compact' | 'default' | 'large'
}

const props = withDefaults(defineProps<Props>(), {
  icon: () => Inbox,
  title: '暂无数据',
  description: '',
  actionLabel: '',
  size: 'default'
})

defineEmits<{
  action: []
}>()
</script>

<template>
  <div class="empty-state" :class="`empty-state-${size}`">
    <div class="empty-state-icon">
      <component :is="icon" :size="size === 'compact' ? 24 : 36" />
    </div>
    <h4 v-if="title" class="empty-state-title">{{ title }}</h4>
    <p v-if="description" class="empty-state-desc">{{ description }}</p>
    <slot />
    <button
      v-if="actionLabel"
      type="button"
      class="empty-state-action"
      @click="$emit('action')"
    >{{ actionLabel }}</button>
  </div>
</template>

<style scoped lang="scss">
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 24px;
  color: var(--color-text-tertiary);
  text-align: center;
  background: var(--color-surface);
  border: 1px dashed var(--color-border-light);
  border-radius: var(--radius-lg);
}

.empty-state-compact {
  padding: 24px 16px;
  gap: 6px;
  border: none;
  background: transparent;
}

.empty-state-large {
  padding: 80px 24px;
}

.empty-state-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--color-bg-sunken);
  color: var(--color-text-tertiary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
}

.empty-state-compact .empty-state-icon {
  width: 40px;
  height: 40px;
}

.empty-state-title {
  margin: 0;
  font-size: var(--text-base);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.empty-state-desc {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  max-width: 420px;
  line-height: var(--leading-normal);
}

.empty-state-action {
  margin-top: 8px;
  padding: 6px 16px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-primary);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-primary);
    color: white;
  }
}
</style>
