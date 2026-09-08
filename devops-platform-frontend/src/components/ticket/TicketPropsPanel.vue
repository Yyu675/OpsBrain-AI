<script setup lang="ts">
import type { TicketProperty } from '@/composables/useTicketClosure'

/**
 * 工单属性栏 + SLA 进度条 —— **纯展示组件**。
 *
 * ── 为什么这块最先从 `TicketDetail.vue` 搬出来 ────────────────
 * 它是右侧栏里唯一「零事件、零本地状态」的区块：只读 props、只输出 DOM，
 * 没有任何 `@click`、没有 `ref`、不调接口。
 * 数据全部来自 `useTicketClosure`（已由 `useTicketClosure.test.ts` 覆盖），
 * 本组件只负责把它画出来。
 *
 * 这类区块搬出去的**回归风险最低**——不存在「事件没接回去」「状态漂移」
 * 这两类拆分最常见的事故；唯一可能出错的是漏搬 class 导致样式塌掉，
 * 由 `TicketDetail.render.smoke.test.ts` 的结构断言兜住。
 *
 * ── 一个刻意保留的判定 ────────────────────────────────────────
 * 优先级圆点只在 urgent / high 上出现（medium / low 不画）。
 * 这不是漏写：中低优先级本就不需要视觉强调，全都画点会让
 * 「哪张单要紧」这个信息失效——运维扫一眼右栏就是为了找那个点。
 * 搬运时原样保留了那串看起来啰嗦的 `!== 'priority-medium' && !== 'priority-low'`，
 * 没有「顺手简化」成 `=== urgent || === high`：两者当前等价，
 * 但将来新增优先级档位时前者默认画点、后者默认不画，语义不同。
 */

defineProps<{
  /** 属性行，来自 useTicketClosure.properties */
  properties: TicketProperty[]
  /** SLA 已消耗百分比（0-100） */
  slaProgress: number
  /** 是否已超时。超时优先于百分比着色 */
  slaBreached: boolean
  /** 进度条颜色 class，来自 useTicketClosure.slaBarClass */
  slaBarClass: string
}>()
</script>

<template>
  <div class="props-list">
    <div v-for="prop in properties" :key="prop.label" class="prop-row">
      <span class="prop-label">{{ prop.label }}</span>
      <span
        class="prop-value"
        :class="{
          'prop-mono': prop.mono,
          'prop-priority-urgent': prop.type === 'priority-urgent',
          'prop-priority-high': prop.type === 'priority-high',
          'prop-status': prop.type === 'status'
        }"
      >
        <span v-if="prop.type?.startsWith('priority-') && prop.type !== 'priority-medium' && prop.type !== 'priority-low'" class="prop-dot"></span>
        {{ prop.value }}
      </span>
    </div>
  </div>
  <!-- SLA Progress -->
  <div class="sla-progress">
    <div class="sla-header">
      <span class="sla-label">SLA 进度</span>
      <span class="sla-value" :class="{ 'sla-value-breached': slaBreached }">
        {{ slaBreached ? '已超时' : slaProgress + '% 已消耗' }}
      </span>
    </div>
    <div class="progress-bar">
      <div
        class="progress-fill"
        :class="slaBarClass"
        :style="{ width: slaProgress + '%' }"
      ></div>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* ========== Properties ========== */
.props-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.prop-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.prop-label {
  font-size: var(--text-sm, 0.875rem);
  color: var(--color-text-tertiary, var(--text-3));
}

.prop-value {
  font-size: var(--text-sm, 0.875rem);
  color: var(--color-text-primary, var(--text-1));
  font-weight: var(--weight-medium, 500);
  display: inline-flex;
  align-items: center;
  gap: 4px;

  &.prop-mono {
    font-family: var(--font-mono, 'JetBrains Mono', monospace);
  }

  &.prop-priority-urgent {
    color: var(--state-error, var(--danger));
  }

  &.prop-priority-high {
    color: #EA580C;
  }

  &.prop-status {
    color: var(--color-primary-light, var(--brand-hover));
  }
}

.prop-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  display: inline-block;
}

/* ========== SLA Progress ========== */
.sla-progress {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--color-border-light, var(--border-1));
}

.sla-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.sla-label {
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-tertiary, var(--text-3));
}

.sla-value {
  font-size: var(--text-xs, 0.75rem);
  font-weight: var(--weight-medium, 500);
  color: var(--state-warning, var(--warning));
}

.sla-value-breached {
  color: var(--state-error, var(--danger)) !important;
  font-weight: var(--weight-semibold, 600);
}

.progress-bar {
  width: 100%;
  height: 8px;
  background: var(--color-bg-sunken, var(--surface-2));
  border-radius: var(--radius-full, 9999px);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: var(--radius-full, 9999px);
  transition: width 0.3s ease, background 0.2s ease;

  &.progress-fill-normal { background: var(--color-primary-light, var(--brand-hover)); }
  &.progress-fill-warning { background: var(--state-warning, var(--warning)); }
  &.progress-fill-error { background: var(--state-error, var(--danger)); }
}
</style>
