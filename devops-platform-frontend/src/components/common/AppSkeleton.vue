<script setup lang="ts">
interface Props {
  variant?: 'list' | 'detail' | 'dashboard'
  rows?: number
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'list',
  rows: 6
})
</script>

<template>
  <div class="skeleton-wrap" :data-variant="props.variant" role="status" aria-label="加载中">
    <!-- list variant -->
    <template v-if="props.variant === 'list'">
      <div class="sk-toolbar">
        <div class="sk-line sk-line-sm" style="width: 240px" />
        <div class="sk-line sk-line-sm" style="width: 120px" />
      </div>
      <div class="sk-list">
        <div v-for="i in props.rows" :key="i" class="sk-row">
          <div class="sk-avatar" />
          <div class="sk-row-body">
            <div class="sk-line" style="width: 60%" />
            <div class="sk-line sk-line-sm" style="width: 40%" />
          </div>
          <div class="sk-badge" />
        </div>
      </div>
    </template>

    <!-- detail variant -->
    <template v-else-if="props.variant === 'detail'">
      <div class="sk-detail-header">
        <div class="sk-line sk-line-lg" style="width: 320px" />
        <div class="sk-line sk-line-sm" style="width: 200px; margin-top: 12px" />
      </div>
      <div class="sk-detail-body">
        <div class="sk-line" style="width: 100%" />
        <div class="sk-line" style="width: 92%" />
        <div class="sk-line" style="width: 85%" />
        <div class="sk-line" style="width: 78%" />
      </div>
      <div class="sk-detail-side">
        <div class="sk-card" />
        <div class="sk-card" />
      </div>
    </template>

    <!-- dashboard variant -->
    <template v-else>
      <div class="sk-kpi-grid">
        <div v-for="i in 4" :key="i" class="sk-kpi" />
      </div>
      <div class="sk-chart-grid">
        <div class="sk-chart" />
        <div class="sk-chart" />
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.skeleton-wrap {
  padding: 24px;
  max-width: 1280px;
  margin: 0 auto;
}

.sk-line,
.sk-avatar,
.sk-badge,
.sk-card,
.sk-kpi,
.sk-chart {
  background: linear-gradient(
    90deg,
    var(--color-bg-sunken, var(--surface-2)) 25%,
    var(--color-border-light, var(--border-1)) 50%,
    var(--color-bg-sunken, var(--surface-2)) 75%
  );
  background-size: 200% 100%;
  animation: shimmer 1.4s linear infinite;
  border-radius: 6px;
}

@keyframes shimmer {
  0% { background-position: 200% 0 }
  100% { background-position: -200% 0 }
}

.sk-line { height: 14px; margin: 6px 0 }
.sk-line-sm { height: 10px }
.sk-line-lg { height: 22px }

.sk-toolbar {
  display: flex;
  justify-content: space-between;
  padding: 12px 0 24px;
}

.sk-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sk-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: 10px;
}

.sk-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  flex-shrink: 0;
}

.sk-row-body { flex: 1 }

.sk-badge {
  width: 72px;
  height: 24px;
  border-radius: 999px;
}

.sk-detail-header {
  padding: 24px 0 16px;
  border-bottom: 1px solid var(--color-border-light, var(--border-1));
  margin-bottom: 20px;
}

.sk-detail-body .sk-line {
  height: 12px;
  margin: 10px 0;
}

.sk-detail-side {
  display: flex;
  gap: 16px;
  margin-top: 24px;
}

.sk-card {
  flex: 1;
  height: 160px;
  border-radius: 10px;
}

.sk-kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.sk-kpi {
  height: 96px;
  border-radius: 10px;
}

.sk-chart-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 24px;
}

.sk-chart {
  height: 320px;
  border-radius: 10px;
}

@media (max-width: 900px) {
  .sk-kpi-grid { grid-template-columns: repeat(2, 1fr) }
  .sk-chart-grid { grid-template-columns: 1fr }
}
</style>
