<script setup lang="ts">
/**
 * 「趋势分析」模式（L2，方案 B-1 落地）
 *
 * 此前是「开发中」假占位。现接入真实后端 `GET /api/v1/dashboard/trends`：
 * - 工单趋势：每日建单数 / 每日验证通过数（MTTR 口径，跳过验证不计入）
 * - 成本趋势：每日 AI 调用成本（元）
 * - 命中率趋势：每日缓存命中率（%，分母为有效查询 CHAT+CACHE_HIT，遵循 6.41 口径）
 *
 * 三态：加载中 / 加载失败（可重试）/ 有数据。窗口可切 7/14/30 天。
 * 全 0 数据不等于无数据——补零是后端有意为之，用于呈现「哪几天无活动」，
 * 故只在 days 为空时才判定为无数据。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { TrendingUp, RefreshCw, Loader2 } from 'lucide-vue-next'
import { getTrends, type TrendData } from '@/api/dashboard'
import { SERVICE_OPTIONS } from '@/stores/tickets'
import { mapServiceToModule, mapModuleToService } from '@/api/utils/dto-converter'
import TrendChart, { type TrendSeries } from '@/components/common/TrendChart.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'
import AppEmpty from '@/components/common/AppEmpty.vue'

const props = withDefaults(defineProps<{
  /** 是否为当前激活模式——非激活时不拉数据，避免切到别的 tab 也发请求 */
  active?: boolean
}>(), { active: true })

const WINDOW_OPTIONS = [
  { value: 7, label: '近 7 天' },
  { value: 14, label: '近 14 天' },
  { value: 30, label: '近 30 天' }
]

const windowDays = ref(7)
/** 服务下钻：'' = 全局。选中后只有工单两条线被过滤，成本/命中率恒为全局 */
const serviceFilter = ref<string>('')
const trend = ref<TrendData | null>(null)
const loading = ref(false)
const loadError = ref<unknown>(null)
/** 是否已拉过数据——避免 active 反复切换时重复请求 */
const loadedOnce = ref(false)

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const module = serviceFilter.value ? mapServiceToModule(serviceFilter.value) : null
    trend.value = await getTrends(windowDays.value, module)
    loadedOnce.value = true
  } catch (e) {
    console.error('[AnalyticsMode] 加载趋势数据失败', e)
    loadError.value = e
  } finally {
    loading.value = false
  }
}

/** 生效的下钻口径展示名（由后端回传的 module 反查，而非直接用本地选择值） */
const scopeLabel = computed(() => {
  const m = trend.value?.module
  return m ? mapModuleToService(m) : '全局'
})

/** 无数据：横轴为空才算，全 0 是「这几天确实无活动」而非无数据 */
const isEmpty = computed(() => !!trend.value && trend.value.days.length === 0)

/** 工单趋势系列 */
const ticketSeries = computed<TrendSeries[]>(() => {
  const t = trend.value
  if (!t) return []
  return [
    { name: '新建工单', data: t.created, type: 'bar', color: '#409eff', suffix: ' 单' },
    { name: '验证通过', data: t.resolved, type: 'line', color: '#67c23a', suffix: ' 单', area: true }
  ]
})

/** AI 调用趋势系列：成本挂右轴（与百分比差两个数量级，共轴会压成贴底直线） */
const callSeries = computed<TrendSeries[]>(() => {
  const t = trend.value
  if (!t) return []
  return [
    { name: '缓存命中率', data: t.cacheHitRate, type: 'line', color: '#e6a23c', suffix: '%', area: true },
    { name: 'AI 成本', data: t.cost, type: 'line', color: '#f56c6c', suffix: ' 元', useRightAxis: true }
  ]
})

/** 汇总数字：窗口内合计与均值，给用户一个不用读图的速览 */
const summary = computed(() => {
  const t = trend.value
  if (!t || !t.days.length) return null
  const sum = (arr: number[]) => arr.reduce((a, b) => a + (b || 0), 0)
  const created = sum(t.created)
  const resolved = sum(t.resolved)
  const cost = sum(t.cost)
  // 命中率取窗口内有效天（命中率 > 0 的天）的均值——把无活动的 0 计入会把均值拉低失真
  const activeRates = t.cacheHitRate.filter(r => r > 0)
  const avgRate = activeRates.length
    ? Math.round((activeRates.reduce((a, b) => a + b, 0) / activeRates.length) * 10) / 10
    : null
  return {
    created,
    resolved,
    cost: Math.round(cost * 10000) / 10000,
    avgRate
  }
})

const changeWindow = (d: number) => {
  if (d === windowDays.value) return
  windowDays.value = d
  void load()
}

/** 切换服务下钻 */
const changeService = () => {
  void load()
}

onMounted(() => {
  if (props.active) void load()
})

// 首次切到本 tab 时才拉数据
watch(() => props.active, (on) => {
  if (on && !loadedOnce.value && !loading.value) void load()
})
</script>

<template>
  <div class="analytics-mode">
    <!-- 头部：标题 + 窗口切换 + 刷新 -->
    <div class="analytics-header">
      <div class="analytics-title">
        <TrendingUp :size="16" />
        <span>趋势分析</span>
      </div>
      <div class="analytics-tools">
        <select
          v-model="serviceFilter"
          class="service-select"
          title="按服务下钻（仅工单两条线，成本与命中率无服务维度）"
          @change="changeService"
        >
          <option value="">全部服务</option>
          <option v-for="s in SERVICE_OPTIONS" :key="s" :value="s">{{ s }}</option>
        </select>
        <div class="window-switch">
          <button
            v-for="opt in WINDOW_OPTIONS"
            :key="opt.value"
            class="window-btn"
            :class="{ active: windowDays === opt.value }"
            type="button"
            @click="changeWindow(opt.value)"
          >{{ opt.label }}</button>
        </div>
        <button class="icon-btn" type="button" :disabled="loading" title="刷新" @click="load">
          <RefreshCw :size="14" :class="{ spin: loading }" />
        </button>
      </div>
    </div>

    <!-- 加载中（首次） -->
    <div v-if="loading && !trend" class="analytics-state">
      <Loader2 :size="20" class="spin" />
      <span>正在加载趋势数据…</span>
    </div>

    <!-- 加载失败 -->
    <div v-else-if="loadError && !trend" class="analytics-state">
      <ApiErrorState :error="loadError" compact retry-label="重试" @retry="load" />
    </div>

    <!-- 无数据 -->
    <div v-else-if="isEmpty" class="analytics-state">
      <AppEmpty kind="default" size="sm" description="暂无趋势数据" />
    </div>

    <!-- 图表区 -->
    <div v-else-if="trend" class="analytics-body">
      <!-- 汇总速览 -->
      <div v-if="summary" class="summary-row">
        <div class="summary-cell">
          <span class="summary-label">新建</span>
          <span class="summary-value">{{ summary.created }}</span>
        </div>
        <div class="summary-cell">
          <span class="summary-label">已验证</span>
          <span class="summary-value">{{ summary.resolved }}</span>
        </div>
        <div class="summary-cell">
          <span class="summary-label">AI 成本<i class="scope-mark" title="全局口径，不随服务下钻变化">全局</i></span>
          <span class="summary-value">¥{{ summary.cost }}</span>
        </div>
        <div class="summary-cell">
          <span class="summary-label">平均命中率<i class="scope-mark" title="全局口径，不随服务下钻变化">全局</i></span>
          <span class="summary-value">
            {{ summary.avgRate !== null ? summary.avgRate + '%' : '—' }}
          </span>
        </div>
      </div>

      <!-- 工单趋势 -->
      <section class="chart-block">
        <h4 class="chart-title">
          工单趋势
          <span class="chart-scope">{{ scopeLabel }}</span>
        </h4>
        <TrendChart
          :labels="trend.days"
          :series="ticketSeries"
          height="220px"
          left-axis-name="单"
          :enable-zoom="trend.days.length > 14"
        />
      </section>

      <!-- AI 调用趋势 -->
      <section class="chart-block">
        <h4 class="chart-title">
          AI 调用趋势
          <!--
            必须标注「全局」且与工单图区分：审计日志无服务维度，
            下钻时这两条线不变。不标注会让用户把全局成本误读为该服务的成本。
          -->
          <span class="chart-scope chart-scope--fixed">全局</span>
          <span class="chart-hint">命中率分母为有效查询，已剔除拒绝/失败调用</span>
        </h4>
        <TrendChart
          :labels="trend.days"
          :series="callSeries"
          height="220px"
          left-axis-name="%"
          right-axis-name="元"
          :enable-zoom="trend.days.length > 14"
        />
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.analytics-mode {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 4px;
}

/* ===== 头部 ===== */
.analytics-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.analytics-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.analytics-tools {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.window-switch {
  display: inline-flex;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}

/* 服务下钻选择器 */
.service-select {
  height: 26px;
  max-width: 150px;
  padding: 0 6px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  font-size: var(--text-xs);
  font-family: var(--font-body);
  color: var(--color-text-secondary);
  cursor: pointer;

  &:hover { border-color: var(--color-primary); }
  &:focus { outline: none; border-color: var(--color-primary); }
}

.window-btn {
  padding: 4px 10px;
  border: none;
  background: var(--color-surface);
  font-size: var(--text-xs);
  font-family: var(--font-body);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:not(:last-child) { border-right: 1px solid var(--color-border-light); }
  &:hover { color: var(--color-primary); }

  &.active {
    background: var(--color-primary);
    color: #fff;
  }
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
  &:disabled { opacity: 0.55; cursor: not-allowed; }
}

.spin { animation: spin 1s linear infinite; }

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ===== 三态 ===== */
.analytics-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 200px;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

/* ===== 图表区 ===== */
.analytics-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.summary-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.summary-cell {
  flex: 1;
  min-width: 74px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  background: var(--color-bg-sunken, #f8fafc);
  border-radius: var(--radius-md);
}

.summary-label {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

/* 速览格内的口径标记：成本/命中率不随下钻变化，需与工单数区分 */
.scope-mark {
  margin-left: 4px;
  padding: 0 3px;
  font-size: 9px;
  font-style: normal;
  color: var(--color-text-tertiary);
  background: var(--color-border-lighter, #ebeef5);
  border-radius: 2px;
  cursor: help;
}

.summary-value {
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  font-family: var(--font-mono, monospace);
}

.chart-block {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: 12px;
}

.chart-title {
  margin: 0 0 6px 0;
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  color: var(--color-text-secondary);
}

.chart-hint {
  margin-left: 6px;
  font-size: 10px;
  font-weight: var(--weight-normal);
  color: var(--color-text-tertiary);
}

/* 口径标注：工单图跟随下钻变化，AI 调用图恒为全局 */
.chart-scope {
  margin-left: 6px;
  padding: 1px 6px;
  font-size: 10px;
  font-weight: var(--weight-medium);
  color: var(--color-primary);
  background: var(--color-primary-lighter, #ecf5ff);
  border-radius: 3px;
}

/* 恒定口径用中性灰，与可变的下钻口径视觉区分 */
.chart-scope--fixed {
  color: var(--color-text-tertiary);
  background: var(--color-bg-sunken, #f1f5f9);
}
</style>
