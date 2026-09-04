<script setup lang="ts">
import type { TicketActivity } from '@/stores/tickets'

/**
 * 工单操作记录时间轴 —— **纯展示组件**。
 *
 * 与 `TicketPropsPanel` 同类：零事件、零本地状态，只把数组画成一列。
 *
 * ── 搬运时收紧的一处 ──────────────────────────────────────────
 * 原模板写的是 `v-if="!ticket.activities || ticket.activities.length === 0"`——
 * 因为 `ticket.activities` 在类型上可选，运行时也确实可能是 undefined
 * （store 第 138 行有 `if (!Array.isArray(t.activities)) t.activities = []` 的兜底，
 * 但那只在某一条加载路径上）。
 *
 * 到了组件边界这里，`activities` 声明为**必填数组**，undefined 由父组件
 * 用 `:activities="ticket.activities || []"` 归一。好处是组件内部不必
 * 在两处（空态判断 + v-for）重复防御，少一处漏防的可能。
 *
 * ── key 为什么带序号：**因为没有主键，不是因为怕节点复用** ──
 * 操作记录是后端聚合出来的视图对象，没有 id。原模板拼了
 * `time + user + 序号`，本次搬运原样保留。
 *
 * 需要说明的是：拆分时我一度在注释里写「不加序号会导致 key 重复、
 * Vue 复用错节点、两行内容互换」，随后**做了实测——那是错的**。
 * 本组件是纯静态列表（无子组件状态、无 transition、无 v-model），
 * 重复 key 下 patch 结果、控制台 warn/error 与正确实现完全一致。
 *
 * 所以序号在这里的真实作用只有一个：**满足 Vue 对 key 唯一性的约定**，
 * 为将来这一行长出内部状态（比如可展开详情）留好余地。
 * 现在把它去掉不会有任何用户可见后果——正因如此，
 * 下面那条「同人同秒三条都渲染」的用例守的是**内容不被去重**，
 * 而不是 key（实测注入「去掉序号」它抓不到，注释已据实改写）。
 */

defineProps<{
  /** 操作记录，按时间倒序。父组件负责把 undefined 归一成空数组 */
  activities: TicketActivity[]
}>()
</script>

<template>
  <div class="activity-list">
    <div v-if="activities.length === 0" class="activity-empty">
      暂无操作记录
    </div>
    <div v-for="(a, i) in activities" :key="a.time + '-' + a.user + '-' + i" class="activity-row">
      <div class="activity-dot" :class="`activity-dot-${a.color}`"></div>
      <div class="activity-body">
        <p class="activity-text">
          {{ a.text }}<template v-if="a.detail">: <span class="activity-detail" :class="{ 'activity-detail-highlight': a.highlight }">{{ a.detail }}</span></template>
        </p>
        <p class="activity-meta">{{ a.user }} &middot; {{ a.time }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* ========== Activity Log ========== */
.activity-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.activity-empty {
  text-align: center;
  color: var(--color-text-tertiary, var(--text-3));
  padding: 20px;
  font-size: 0.8125rem;
}

.activity-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.activity-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;

  &.activity-dot-success { background: var(--state-success, var(--success)); }
  &.activity-dot-primary { background: var(--color-primary-light, var(--brand-hover)); }
  &.activity-dot-gray { background: var(--color-text-tertiary, var(--text-3)); }
  &.activity-dot-warning { background: var(--state-warning, var(--warning)); }
}

.activity-body {
  flex: 1;
  min-width: 0;
}

.activity-text {
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-secondary, var(--text-2));
  margin: 0;
}

.activity-detail {
  font-weight: var(--weight-medium, 500);
  color: var(--color-text-primary, var(--text-1));

  &.activity-detail-highlight {
    color: var(--color-primary, var(--brand));
  }
}

.activity-meta {
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-tertiary, var(--text-3));
  margin: 2px 0 0 0;
}
</style>
