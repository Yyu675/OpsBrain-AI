<script setup lang="ts">
import { computed, onMounted } from 'vue'
import {
  AlertCircle, Clock, TrendingUp, Layers, PlusCircle,
  Flame, ArrowRight
} from 'lucide-vue-next'
import { useTicketsStore } from '@/stores/tickets'
import { RouterLink } from 'vue-router'

const store = useTicketsStore()

/**
 * 「建议」模式 —— 工单统计分析。
 *
 * 数据源：tickets store 的 stats（后端 /tickets/stats 全量统计，
 * 失败时降级为当前页本地计算）。此处只读统计，不持有列表数据，
 * 由使用方（AiChatView 页面）决定刷新时机。
 *
 * 挂载时主动拉取 stats，确保用户首次打开 AI 对话页就有数据，
 * 而非依赖之前是否打开过工单列表页。
 */
onMounted(() => {
  store.loadStats()
})

const stats = computed(() => store.stats)

interface KpiItem {
  label: string
  value: string
  icon: typeof AlertCircle
  color: 'warning' | 'info' | 'success' | 'error'
  hint: string
}

const kpis = computed<KpiItem[]>(() => [
  {
    label: '待处理',
    value: String(stats.value.pending),
    icon: AlertCircle,
    color: 'warning',
    hint: '等待分配或处理的工单'
  },
  {
    label: '处理中',
    value: String(stats.value.processing),
    icon: Clock,
    color: 'info',
    hint: '正在排查解决的工单'
  },
  {
    label: '已解决',
    value: String(stats.value.resolved),
    icon: TrendingUp,
    color: 'success',
    hint: '完成解决、关闭闭环的工单'
  },
  {
    label: '今日新增',
    value: String(stats.value.todayNew ?? 0),
    icon: PlusCircle,
    color: 'error',
    hint: '今天（自然日）新创建的工单'
  },
  {
    label: '工单总数',
    value: String(stats.value.total),
    icon: Layers,
    color: 'info',
    hint: '全部状态的工单总量'
  }
])

/**
 * 未完结的高优先级工单数。
 *
 * 取后端 /tickets/stats 的 urgentPending（全量统计）。
 *
 * 此前是从 store.tickets 本地过滤——分页下沉后（6.15）该数组仅含当前页，
 * 且本组件只调 loadStats() 不拉列表，用户没打开过工单列表页时数组为空，
 * 会把「有紧急工单」**误报为「暂无紧急待处理工单」**。
 *
 * null 表示统计不可用（后端异常降级），需与「确实为 0」区分展示——
 * 谎报「暂无紧急工单」可能让真实的生产故障被忽略。
 */
const urgentPending = computed<number | null>(() => {
  const v = (stats.value as { urgentPending?: number | null }).urgentPending
  return typeof v === 'number' ? v : null
})

const colorClass = (c: KpiItem['color']) => `kpi-icon-${c}`
</script>

<template>
  <div class="suggestion-mode">
    <header class="suggestion-header">
      <div class="suggestion-title-block">
        <h3 class="suggestion-title">智能建议</h3>
        <p class="suggestion-subtitle">工单统计概览，辅助处理优先级判断</p>
      </div>
      <RouterLink to="/tickets" class="view-tickets-link">
        查看全部工单
        <ArrowRight :size="14" />
      </RouterLink>
    </header>

    <!-- KPI 卡片 -->
    <div class="kpi-grid">
      <div v-for="kpi in kpis" :key="kpi.label" class="kpi-card">
        <div class="kpi-icon" :class="colorClass(kpi.color)">
          <component :is="kpi.icon" :size="20" />
        </div>
        <div class="kpi-content">
          <div class="kpi-value">{{ kpi.value }}</div>
          <div class="kpi-label">{{ kpi.label }}</div>
        </div>
        <div class="kpi-hint">{{ kpi.hint }}</div>
      </div>
    </div>

    <!-- 紧急待处理提醒
         三态严格区分：有紧急工单 / 确实没有 / 统计不可用。
         把「不可用」显示成「暂无紧急工单」会让真实故障被忽略 -->
    <div
      class="urgent-block"
      :class="{
        'has-urgent': urgentPending !== null && urgentPending > 0,
        'is-unknown': urgentPending === null
      }"
    >
      <div class="urgent-icon">
        <Flame :size="18" />
      </div>
      <div class="urgent-text">
        <div class="urgent-title">
          <template v-if="urgentPending === null">紧急工单统计暂不可用</template>
          <template v-else-if="urgentPending > 0">{{ urgentPending }} 张紧急工单待处理</template>
          <template v-else>暂无紧急待处理工单</template>
        </div>
        <div class="urgent-desc">
          <template v-if="urgentPending === null">
            统计服务未返回数据，请前往工单列表按优先级筛选查看。
          </template>
          <template v-else-if="urgentPending > 0">
            请优先安排处理高优先级工单，避免 SLA 超时。
          </template>
          <template v-else>目前没有需要优先介入的紧急工单。</template>
        </div>
      </div>
      <RouterLink
        v-if="urgentPending === null || urgentPending > 0"
        to="/tickets?priority=urgent"
        class="urgent-action"
      >
        {{ urgentPending === null ? '前往查看' : '前往处理' }}
      </RouterLink>
    </div>
  </div>
</template>

<style scoped lang="scss">
/*
  高度撑满 + 自身滚动。
  宿主 AiChatView 的 .mode-body 是 `overflow: hidden` 的 flex 容器——
  子项不自行滚动的话，内容超高会被直接裁掉而非出现滚动条（用户看不到下半部分）。
  另三个模式组件（ChatMode / AnalyticsMode / AlertStreamMode）本就有 height:100%，
  只有本组件漏了。
*/
.suggestion-mode {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}

.suggestion-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.suggestion-title {
  margin: 0 0 4px 0;
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.suggestion-subtitle {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.view-tickets-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-xs);
  color: var(--color-primary);
  text-decoration: none;
  white-space: nowrap;

  &:hover { text-decoration: underline; }
}

/* KPI 卡片 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 20px;

  @media (min-width: 560px) { grid-template-columns: repeat(3, 1fr); }
  @media (min-width: 720px) { grid-template-columns: repeat(5, 1fr); }
}

.kpi-card {
  position: relative;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  padding: 16px;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.kpi-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;

  &.kpi-icon-warning { background: var(--state-warning-bg, #FFF8E1); color: var(--state-warning); }
  &.kpi-icon-info { background: var(--color-primary-lighter); color: var(--color-primary); }
  &.kpi-icon-success { background: var(--state-success-bg); color: var(--state-success); }
  &.kpi-icon-error { background: var(--state-error-bg); color: var(--state-error); }
}

.kpi-content {
  display: flex;
  flex-direction: column;
}

.kpi-value {
  font-size: var(--text-2xl);
  font-weight: var(--weight-bold);
  color: var(--color-text-primary);
  line-height: 1;
}

.kpi-label {
  margin-top: 4px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.kpi-hint {
  font-size: 11px;
  color: var(--color-text-tertiary);
  line-height: 1.4;
}

/* 紧急待处理提醒 */
.urgent-block {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);

  &.has-urgent {
    border-color: var(--state-warning);
    background: var(--state-warning-bg);
  }

  /* 统计不可用：用中性虚线边框，明确区别于「确实没有紧急工单」的实线常态，
     避免用户把「未知」当成「安全」 */
  &.is-unknown {
    border-style: dashed;
    border-color: var(--color-border);
    background: var(--color-bg-sunken);
  }
}

.urgent-icon {
  flex-shrink: 0;
  color: var(--state-warning);
}

.urgent-text {
  flex: 1;
  min-width: 0;
}

.urgent-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.urgent-desc {
  margin-top: 2px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.urgent-action {
  white-space: nowrap;
  font-size: var(--text-xs);
  color: var(--color-primary);
  text-decoration: none;
  font-weight: var(--weight-medium);

  &:hover { text-decoration: underline; }
}
</style>