<script setup lang="ts">
/**
 * 工单 Insights 面板 — 右栏只读信息
 *
 * 展示：
 * - 相似工单（按服务匹配，可点击跳转）
 * - 相关文档（按服务+标题搜索，可点击跳转）
 * - 趋势（近 14 日建单/验证迷你折线，数据由父组件传入）
 * - 置信度进度条
 *
 * 本组件保持无状态（只收 props 不拉数据）——趋势数据由 TicketDetail 拉取后传入，
 * 组件内自行请求会让同一页面多处重复调用同一端点。
 */
import { computed } from 'vue'
import { FileText, BookOpen, TrendingUp, ChevronRight } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import type { FrontendTicket } from '@/api/types/ticket'
import type { KnowledgeDocListItem } from '@/api/types'
import type { TrendData } from '@/api/dashboard'
import TrendChart, { type TrendSeries } from '@/components/common/TrendChart.vue'

const props = defineProps<{
  similarTickets: FrontendTicket[]
  similarLoading: boolean
  relatedDocs: KnowledgeDocListItem[]
  relatedLoading: boolean
  confidence: number | null
  confidenceClass: string
  /** 全局工单趋势；null=尚未加载或加载失败（显示降级文案，不造假数据） */
  trend?: TrendData | null
  trendLoading?: boolean
}>()

/** 迷你趋势系列：右栏窄，只画两条工单线，成本/命中率留给趋势分析页 */
const trendSeries = computed<TrendSeries[]>(() => {
  const t = props.trend
  if (!t) return []
  return [
    { name: '新建', data: t.created, type: 'line', color: '#409eff', suffix: ' 单', area: true },
    { name: '验证通过', data: t.resolved, type: 'line', color: '#67c23a', suffix: ' 单' }
  ]
})

const hasTrend = computed(() => !!props.trend && props.trend.days.length > 0)
</script>

<template>
  <div class="insights-panel">
    <!-- 相似工单 -->
    <div class="insight-section">
      <div class="insight-header">
        <FileText :size="14" />
        <span class="insight-label">相似工单</span>
        <span v-if="similarTickets.length" class="insight-count">{{ similarTickets.length }}</span>
      </div>
      <div v-if="similarLoading" class="insight-loading">加载中...</div>
      <div v-else-if="similarTickets.length === 0" class="insight-empty">暂无相似工单</div>
      <div v-else class="insight-list">
        <RouterLink
          v-for="t in similarTickets"
          :key="t.id"
          :to="`/tickets/${t.id}`"
          class="insight-link"
        >
          <ChevronRight :size="12" />
          <span class="insight-title">{{ t.title }}</span>
          <span class="insight-id">{{ t.id }}</span>
        </RouterLink>
      </div>
    </div>

    <!-- 相关文档 -->
    <div class="insight-section">
      <div class="insight-header">
        <BookOpen :size="14" />
        <span class="insight-label">相关文档</span>
        <span v-if="relatedDocs.length" class="insight-count">{{ relatedDocs.length }}</span>
      </div>
      <div v-if="relatedLoading" class="insight-loading">加载中...</div>
      <div v-else-if="relatedDocs.length === 0" class="insight-empty">暂无相关文档</div>
      <div v-else class="insight-list">
        <RouterLink
          v-for="doc in relatedDocs"
          :key="doc.id"
          :to="`/knowledge/${doc.id}`"
          class="insight-link"
        >
          <ChevronRight :size="12" />
          <span class="insight-title">{{ doc.title }}</span>
        </RouterLink>
      </div>
    </div>

    <!-- 趋势：近 N 日建单/验证迷你折线 -->
    <div class="insight-section">
      <div class="insight-header">
        <TrendingUp :size="14" />
        <span class="insight-label">趋势</span>
        <!--
          必须标注「全局」：本组件位于某张工单的右栏，用户会合理预期这是
          「该服务的趋势」。当前数据是全库口径，不标注等于让用户误读口径
          （6.38/6.41 契约）。按服务下钻已排后续，届时改为服务名。
        -->
        <span class="insight-scope">全局</span>
        <span v-if="trend && trend.windowDays" class="insight-count">{{ trend.windowDays }} 天</span>
      </div>
      <div v-if="trendLoading" class="insight-loading">加载中...</div>
      <TrendChart
        v-else-if="hasTrend"
        :labels="trend!.days"
        :series="trendSeries"
        height="120px"
        :show-legend="false"
      />
      <div v-else class="insight-empty">趋势数据不可用</div>
      <!-- 图例文字化：迷你图关掉了图例，否则在窄栏里会挤掉图形 -->
      <div v-if="hasTrend" class="trend-legend">
        <span class="legend-item"><i class="legend-dot legend-dot--created"></i>新建</span>
        <span class="legend-item"><i class="legend-dot legend-dot--resolved"></i>验证通过</span>
      </div>
    </div>

    <!-- 置信度 -->
    <div v-if="confidence !== null" class="insight-section">
      <div class="insight-header">
        <span class="insight-label">分析置信度</span>
        <span class="confidence-value" :class="confidenceClass">{{ confidence }}%</span>
      </div>
      <div class="confidence-bar">
        <div class="confidence-fill" :class="confidenceClass" :style="{ width: confidence + '%' }"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.insights-panel { display: flex; flex-direction: column; gap: 12px; }
.insight-section { display: flex; flex-direction: column; gap: 6px; }
.insight-header { display: flex; align-items: center; gap: 4px; font-size: 0.75rem; }
.insight-label { font-weight: 500; color: var(--color-text-primary, #111827); }
.insight-count {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 16px; height: 16px; padding: 0 4px;
  font-size: 0.625rem; font-weight: 600;
  background: var(--color-primary-lighter, #E8F0FC); color: var(--color-primary, #409eff);
  border-radius: 8px;
}
/* 口径标注：说明趋势是全局而非本工单所属服务，避免用户误读 */
.insight-scope {
  padding: 0 5px; height: 15px; line-height: 15px;
  font-size: 0.5625rem; font-weight: 500;
  color: var(--color-text-tertiary, #9ca3af);
  background: var(--color-bg-sunken, #f1f5f9);
  border-radius: 3px;
}
.insight-loading, .insight-empty { font-size: 0.6875rem; color: var(--color-text-tertiary, #9ca3af); }
.insight-list { display: flex; flex-direction: column; gap: 4px; }
.insight-link {
  display: flex; align-items: center; gap: 4px;
  font-size: 0.6875rem; color: var(--color-primary, #409eff);
  text-decoration: none; padding: 2px 0;
  border-radius: 3px; transition: background 0.15s;
}
.insight-link:hover { background: var(--color-primary-lighter, #E8F0FC); }
.insight-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.insight-id { font-family: monospace; font-size: 0.625rem; opacity: 0.7; }

.confidence-value { font-weight: 600; margin-left: auto; }
.confidence-high { color: var(--state-success, #16A34A); }
.confidence-mid { color: var(--state-warning, #D97706); }
.confidence-low { color: var(--state-error, #DC2626); }

.confidence-bar {
  width: 100%; height: 3px; border-radius: 2px; overflow: hidden;
  background: var(--color-bg-sunken, #EBEEF3);
}
.confidence-fill { height: 100%; border-radius: 2px; transition: width 0.3s; }
.confidence-fill.confidence-high { background: var(--state-success, #16A34A); }
.confidence-fill.confidence-mid { background: var(--state-warning, #D97706); }
.confidence-fill.confidence-low { background: var(--state-error, #DC2626); }

/* 迷你趋势图例（图表本身关掉了图例，窄栏里会挤掉图形） */
.trend-legend {
  display: flex;
  gap: 12px;
  margin-top: 4px;
  font-size: 0.625rem;
  color: var(--color-text-tertiary, #909399);
}
.legend-item { display: inline-flex; align-items: center; gap: 4px; }
.legend-dot {
  width: 7px; height: 7px; border-radius: 2px; display: inline-block;
}
.legend-dot--created { background: #409eff; }
.legend-dot--resolved { background: #67c23a; }
</style>
