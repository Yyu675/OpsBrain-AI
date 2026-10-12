<script setup lang="ts">
/**
 * 告警行的处置操作列（从 AlertList 拆出，行数红线纪律）。
 *
 * 确认/恢复/误报的后端守卫是 OpsWriteGuard requireWrite（ADMIN+OPS，T3 收口）；
 * v-permission disable 是体验层（viewer 看不到可点的按钮而不是点了吃 403）。
 * 「问 AI」把告警上下文带到助手页预提问。
 */
import { Bell, CheckCircle, ShieldAlert, Sparkles } from 'lucide-vue-next'
import type { Alert } from '@/api/types'

defineProps<{
  row: Alert
  /** 该行有处置请求在途（防重复点击） */
  loading: boolean
}>()

const emit = defineEmits<{
  acknowledge: []
  resolve: []
  falsePositive: []
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
    <!-- 误报标记：质量标注与状态机正交，任何状态可标；再点一次取消 -->
    <button
      class="action-btn" :class="{ 'action-btn-flagged': row.falsePositive }"
      v-permission.disable="{ codes: ['alert:write'] }"
      :disabled="loading"
      :title="row.falsePositive ? '取消误报标记' : '标记为误报（反哺阈值优化）'"
      @click="emit('falsePositive')"
    >
      <ShieldAlert :size="14" />
      {{ row.falsePositive ? '已标误报' : '误报' }}
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

  &-flagged {
    border-color: var(--warning);
    color: var(--warning);
    background: var(--surface-1);
  }

  &:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
}
</style>

