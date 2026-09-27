<script setup lang="ts">
/**
 * 工单详情的处置区：阶段切换条 + 处置动作时间线。
 *
 * 从 TicketDetail 拆出（该文件长期超 300 行上限，AGENTS.md 约定改动时
 * 只减不增）。本组件纯展示：阶段枚举与动作类型枚举在这里——它们只决定
 * 「画什么」，切换阶段、标记止损的写操作仍由父组件执行后抛事件回来。
 */
import type { TicketActionRecord } from '@/api/tickets'

defineProps<{
  /** 仅处理中的工单显示阶段切换条 */
  showStageSwitcher: boolean
  /** 当前处置阶段（handlingStage），用于高亮 */
  currentStage: string | null | undefined
  actions: TicketActionRecord[]
}>()

const emit = defineEmits<{
  /** 切换到某阶段（MITIGATED 由父组件走「标记止损」而非普通阶段切换） */
  'update-stage': [stage: string]
  'mark-mitigated': []
}>()

const STAGES = [
  { value: 'TRIAGE', label: '排查中' },
  { value: 'MITIGATED', label: '已止损' },
  { value: 'FIXING', label: '修复中' },
  { value: 'VERIFYING', label: '验证中' }
]

const ACTION_TYPES = [
  { value: 'MITIGATE', label: '止损' },
  { value: 'INVESTIGATE', label: '排查' },
  { value: 'FIX', label: '修复' },
  { value: 'ROLLBACK', label: '回滚' },
  { value: 'VERIFY', label: '验证' }
]

const actionLabel = (actionType: string | null | undefined) =>
  ACTION_TYPES.find(t => t.value === actionType)?.label || actionType || ''

const onStageClick = (stage: string) => {
  if (stage === 'MITIGATED') emit('mark-mitigated')
  else emit('update-stage', stage)
}
</script>

<template>
  <!-- B2 处置阶段切换（仅在处理中状态显示） -->
  <div v-if="showStageSwitcher" class="stage-switcher">
    <button
      v-for="s in STAGES"
      :key="s.value"
      class="stage-btn"
      :class="{ active: currentStage === s.value }"
      @click="onStageClick(s.value)"
    >{{ s.label }}</button>
  </div>

  <!-- B2 处置动作列表（时间线中展示） -->
  <div v-if="actions.length" class="action-list-section">
    <h3 class="description-title">处置动作</h3>
    <div v-for="a in actions" :key="a.id" class="action-item" :class="{ 'action-ineffective': a.effective === false }">
      <span class="action-type-badge" :class="`action-type-${(a.actionType || '').toLowerCase()}`">{{ actionLabel(a.actionType) }}</span>
      <span class="action-summary">{{ a.summary }}</span>
      <span v-if="a.effective === true" class="action-eff eff-ok">有效</span>
      <span v-else-if="a.effective === false" class="action-eff eff-no">无效</span>
      <span class="action-meta">{{ a.operator }} · {{ a.createTime }}</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.description-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text-2, var(--text-2));
  margin: 0 0 8px 0;
}

/* ===== B2 处置阶段切换 + 动作列表 ===== */
.stage-switcher {
  display: flex;
  gap: 6px;
  padding: 8px 12px;
  background: var(--surface-1, #fff);
  border-radius: var(--radius, 8px);
  border: 1px solid var(--border-1, var(--border-1));
  margin-bottom: 16px;
}

.stage-btn {
  padding: 6px 14px;
  border: 1px solid var(--border-2, var(--border-1));
  border-radius: 6px;
  background: var(--surface-1, var(--surface-1));
  font-size: 13px;
  color: var(--text-2, var(--text-2));
  cursor: pointer;
  transition: all 0.15s ease;
}

.stage-btn:hover { border-color: var(--brand, var(--brand)); color: var(--brand, var(--brand)); }
.stage-btn.active { background: var(--brand, var(--brand)); color: #fff; border-color: var(--brand, var(--brand)); }

.action-list-section {
  margin-bottom: 16px;
}

.action-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  background: var(--surface-1, #fff);
  border: 1px solid var(--border-1, var(--border-1));
  margin-bottom: 6px;
}

.action-item.action-ineffective {
  opacity: 0.6;
  border-style: dashed;
}

.action-type-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: var(--info-subtle);
  color: var(--info);
}

.action-type-badge.action-type-mitigate { background: var(--warning-subtle); color: var(--warning); }
.action-type-badge.action-type-fix { background: var(--success-subtle); color: var(--success); }
.action-type-badge.action-type-rollback { background: var(--danger-subtle); color: var(--danger); }
.action-type-badge.action-type-verify { background: #E0E7FF; color: #4338CA; }

.action-summary { flex: 1; color: var(--text-1, var(--text-1)); }
.action-eff { font-size: 11px; padding: 1px 6px; border-radius: 3px; }
.eff-ok { background: var(--success-subtle); color: var(--success); }
.eff-no { background: var(--danger-subtle); color: var(--danger); }
.action-meta { font-size: 11px; color: var(--text-3, var(--text-3)); white-space: nowrap; }
</style>
