<script setup lang="ts">
/**
 * 自愈观察窗面板（PRD FR-3.1 的可视面，效能大盘挂载）。
 *
 * 回答一个问题：「观察窗这套降噪机制到底救了多少工单？」
 * - 观察中：此刻还在窗口内等待自愈的告警（值班人不用管，系统在看）；
 * - 30 日自愈：窗口内自己恢复、从未建单的——每一条都是少打扰一次值班人；
 * - 30 日转单：到期未自愈、最终建单/进组的；
 * - 自愈率 = 自愈 /（自愈 + 转单），是观察窗机制的核心成效指标。
 *
 * 观察窗未开启时整块隐藏（enabled=false），不显示一排误导性的 0。
 */
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { Telescope } from 'lucide-vue-next'
import { fetchObservationStats } from '@/api/alerts'
import { alertKeys } from '@/config/queryKeys'

defineOptions({ name: 'ObservationStatsPanel' })

const query = useQuery({
  queryKey: alertKeys.observationStats(),
  queryFn: fetchObservationStats,
  staleTime: 30_000,
})

const stats = computed(() => query.data.value ?? null)

/** 自愈率：分母为 0（还没有任何观察级告警走完过窗口）时显示 —，不显示 0%（6.38 口径纪律） */
const selfHealRateText = computed(() => {
  const s = stats.value
  if (!s) return '—'
  const denom = s.selfHealed30d + s.escalated30d
  if (denom === 0) return '—'
  return `${((s.selfHealed30d / denom) * 100).toFixed(1)}%`
})
</script>

<template>
  <section v-if="stats?.enabled" class="panel observation-panel">
    <h2 class="panel-title">
      <Telescope :size="15" />
      自愈观察窗
    </h2>
    <p class="panel-sub">
      {{ stats.levels.join(' / ') }} 级告警先观察 {{ stats.windowMinutes }} 分钟再建单——自愈率越高，被消掉的抖动工单越多
    </p>
    <div class="obs-grid">
      <div class="obs-kpi">
        <div class="obs-value">{{ stats.observingNow }}</div>
        <div class="obs-label">观察中</div>
      </div>
      <div class="obs-kpi">
        <div class="obs-value obs-value--good">{{ stats.selfHealed30d }}</div>
        <div class="obs-label">30 日自愈（未建单）</div>
      </div>
      <div class="obs-kpi">
        <div class="obs-value">{{ stats.escalated30d }}</div>
        <div class="obs-label">30 日转单 / 进组</div>
      </div>
      <div class="obs-kpi">
        <div class="obs-value obs-value--rate">{{ selfHealRateText }}</div>
        <div class="obs-label">自愈率（30 日）</div>
      </div>
    </div>
  </section>
</template>

<style scoped lang="scss">
.observation-panel {
  margin-bottom: var(--space-4);
}

.panel-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0 0 var(--space-1);
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text-1);
}

.panel-sub {
  margin: 0 0 var(--space-4);
  font-size: var(--text-sm);
  color: var(--text-3);
}

.obs-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--space-3);
}

.obs-kpi {
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  background: var(--surface-2);
}

.obs-value {
  font-size: var(--text-2xl);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--text-1);

  &--good {
    color: var(--success);
  }

  &--rate {
    color: var(--brand);
  }
}

.obs-label {
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  color: var(--text-3);
}
</style>
