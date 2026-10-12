<script setup lang="ts">
/**
 * 告警触发中口径摘要（R1-1 口径修正）。
 *
 * 从 AlertList 抽出：此前「当前页触发中」由前端数当前页数组，
 * 值班人把"本页在烧"读成"现在总共在烧"，会低估火势。
 * 现在两个口径都由后端在同一条筛选 SQL 里数出，组件只负责把口径说清楚：
 * - 筛选内：与列表当前筛选（状态/级别/系统/观察中）同 where 的 FIRING 数；
 * - 全库：不受筛选影响的 FIRING 总数。
 */
defineProps<{
  /** 与当前筛选同口径的 FIRING 数（后端 firingFiltered） */
  filtered: number
  /** 全库 FIRING 数（后端 firingTotal，与筛选无关） */
  total: number
}>()
</script>

<template>
  <div class="summary-item" title="与当前筛选条件同口径的 FIRING 数（后端计数，非本页条数）">
    <span class="summary-dot summary-dot--firing" />
    <span class="summary-text">筛选内触发中</span>
    <span class="summary-value">{{ filtered }}</span>
  </div>
  <div class="summary-item" title="全库 FIRING 数（不受筛选影响）">
    <span class="summary-dot" :class="total > 0 ? 'summary-dot--firing' : 'summary-dot--ok'" />
    <span class="summary-text">全库触发中</span>
    <span class="summary-value">{{ total }}</span>
  </div>
</template>

<style scoped lang="scss">
/* 与 AlertList 页内 summary-item 同一视觉（scoped 不继承，样式随组件走） */
.summary-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
}

.summary-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;

  &--firing { background: var(--danger); }
  &--ok { background: var(--success); }
}

.summary-text { color: var(--text-2); }
.summary-value { font-weight: var(--weight-semibold); color: var(--text-1); }
</style>
