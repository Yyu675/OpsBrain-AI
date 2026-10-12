<script setup lang="ts">
/**
 * 告警行的处置操作列（从 AlertList 拆出，行数红线纪律）。
 *
 * 确认/恢复按钮的后端守卫是「仅登录」⚪，viewer 的唯一防线是这里的
 * v-permission disable 模式；「问 AI」把告警上下文带到助手页预提问。
 */
import { Bell, CheckCircle, Sparkles } from 'lucide-vue-next'
import type { Alert } from '@/api/types'

defineProps<{
  row: Alert
  /** 该行有处置请求在途（防重复点击） */
  loading: boolean
}>()

const emit = defineEmits<{
  acknowledge: []
  resolve: []
  ask: []
}>()
</script>

<template>
  <div class="actions" @click.stop>
    <button
      class="action-btn action-btn-primary" v-permission.disable="{ codes: ['alert:write'] }"
      :disabled="row.status === 'ACKNOWLEDGED' || row.status === 'RESOLVED' || loading"
      @click="emit('acknowledge')"
    >
      <CheckCircle :size="14" />
      {{ row.status === 'ACKNOWLEDGED' ? '已确认' : '确认' }}
    </button>
    <button
      class="action-btn action-btn-success" v-permission.disable="{ codes: ['alert:write'] }"
      :disabled="row.status === 'RESOLVED' || loading"
      @click="emit('resolve')"
    >
      <Bell :size="14" />
      {{ row.status === 'RESOLVED' ? '已恢复' : '标记恢复' }}
    </button>
    <button class="action-btn" type="button" title="带着这条告警去问 AI" @click="emit('ask')">
      <Sparkles :size="14" />
      问 AI
    </button>
  </div>
</template>

<style scoped lang="scss">
/* 与 AlertList 页内操作列同一视觉（scoped 不穿透子组件，样式随组件走） */
.actions {
  display: flex;
  gap: 6px;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  font-family: var(--font-body);
  background: var(--surface-1);
  color: var(--text-1);
  cursor: pointer;
  white-space: nowrap;

  &:disabled { opacity: 0.5; cursor: not-allowed; }

  &-primary:hover:not(:disabled) {
    border-color: var(--brand);
    color: var(--brand);
    background: var(--brand-subtle);
  }

  &-success:hover:not(:disabled) {
    border-color: var(--success);
    color: var(--success);
  }

  &:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
}
</style>

