<script setup lang="ts">
/**
 * 趋势分析（L2）。
 *
 * ── 与实时监控页的分工 ────────────────────────────────────────
 * 实时监控回答「现在怎么样」，本页回答「怎么变成这样的」。
 * 所以这里的核心是**时间轴上的形状**，而不是当前读数：
 *   - 可选时间范围与指标
 *   - 每个实例一条线（监控页为了紧凑只取最大值那条）
 *   - 给出区间内的极值与均值——肉眼从折线上读不准这些数
 *
 * ── 步长由时间范围推导，不让用户填 ────────────────────────────
 * 用户关心的是「看多久」，不是「采样多密」。而 step 填错会直接毁掉图表：
 * 填太小在 7 天窗口上产出几十万个点（后端会夹紧，但仍是无谓传输），
 * 填太大则把尖峰抹平——恰恰是排障最需要看到的东西。
 * 所以由范围推导出一个合理值，用户不必理解这个参数。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { AlertTriangle, BarChart3, RefreshCw, TrendingUp } from 'lucide-vue-next'
import { useRouter } from 'vue-router'

import {
  fetchMetricCatalog,
  fetchRange,
  formatMetricValue,
  labelOf,
  type MetricMeta,
  type MetricSeries,
} from '@/api/metrics'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import TrendChart from '@/components/common/TrendChart.vue'
import {
  defineUrlFilter,
  enumParser,
  textParser,
  useUrlFilters,
} from '@/composables/useUrlFilters'
import { parseDate } from '@/utils/time'

defineOptions({ name: 'Trends' })

const router = useRouter()

// ==================== 时间范围 ====================

/**
 * 预设范围。
 *
 * step 与范围绑定，目标是让点数落在 100~400 之间：
 * 少于 100 曲线会显得棱角分明、看不出形状；
 * 多于 400 在常见屏宽下已超过像素密度，纯属浪费传输。
 */
const RANGES = [
  { id: '1h', label: '1 小时', hours: 1, step: 30 },      // 120 点
  { id: '6h', label: '6 小时', hours: 6, step: 120 },     // 180 点
  { id: '24h', label: '24 小时', hours: 24, step: 300 },  // 288 点
  { id: '7d', label: '7 天', hours: 168, step: 1800 },    // 336 点
  { id: '30d', label: '30 天', hours: 720, step: 3600 },  // 720 点
] as const

type RangeId = (typeof RANGES)[number]['id']

const rangeId = ref<RangeId>('6h')
const metricId = ref('cpu.usage')

const currentRange = computed(
  () => RANGES.find((r) => r.id === rangeId.value) ?? RANGES[1]
)

// 筛选进 URL，让「这台机器昨天的 CPU 曲线」可以直接甩给同事
useUrlFilters([
  defineUrlFilter<RangeId>({
    ref: rangeId,
    key: 'range',
    defaultValue: '6h',
    parse: enumParser(RANGES.map((r) => r.id) as unknown as RangeId[]),
  }),
  defineUrlFilter({
    ref: metricId,
    key: 'metric',
    defaultValue: 'cpu.usage',
    parse: textParser(64),
  }),
])

// ==================== 数据 ====================

const metrics = ref<MetricMeta[]>([])
const series = ref<MetricSeries[]>([])
const loading = ref(false)
const loadError = ref<unknown>(null)
const actualHours = ref<number | null>(null)

const currentMeta = computed(
  () => metrics.value.find((m) => m.id === metricId.value) ?? null
)

const loadCatalog = async () => {
  try {
    const c = await fetchMetricCatalog()
    metrics.value = c.metrics ?? []
    // URL 里带了不存在的指标 ID 时回退到第一个，而不是让页面一直报错。
    // 这种链接常来自旧版本或手工编辑
    if (metrics.value.length && !metrics.value.some((m) => m.id === metricId.value)) {
      metricId.value = metrics.value[0].id
    }
  } catch {
    // 目录失败不阻断——用户可能只是想看默认指标
  }
}

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const r = currentRange.value
    const data = await fetchRange(metricId.value, r.hours, r.step)
    series.value = data.series ?? []
    // 后端可能夹紧超界的 hours，如实回报生效值
    actualHours.value = data.hours ?? r.hours
  } catch (e) {
    loadError.value = e
    series.value = []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadCatalog()
  await load()
})

watch([metricId, rangeId], () => void load())

// ==================== 图表数据 ====================

/**
 * 横轴标签。
 *
 * 短范围只显示时分（24 小时内看具体时刻），长范围带上日期
 * （跨天时只有 "03:00" 无法区分是哪天的凌晨三点）。
 */
const axisLabels = computed(() => {
  const first = series.value[0]
  if (!first) return []
  const longRange = currentRange.value.hours > 24
  return first.points.map((p) => {
    const d = parseDate(p.t)
    if (!d) return ''
    return longRange
      ? d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
        + ' ' + d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
      : d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  })
})

/**
 * 每个实例一条线。
 *
 * 上限 8 条：再多图例会挤满整个图表区，且颜色开始重复、无法分辨。
 * 超出时明确告知被截断，而不是悄悄少画几条——
 * 「我的机器怎么没在图里」是个很难自查的问题。
 */
const MAX_SERIES = 8

const chartSeries = computed(() =>
  series.value.slice(0, MAX_SERIES).map((s) => ({
    name: labelOf(s.labels),
    // null 转 NaN 让 ECharts 断线；用 0 会画出假的「跌到底」
    data: s.points.map((p) => (p.v === null ? NaN : p.v)),
    smooth: true,
    suffix: currentMeta.value?.unit === 'percent' ? '%' : '',
  }))
)

const truncated = computed(() => series.value.length > MAX_SERIES)

// ==================== 统计摘要 ====================

/**
 * 区间内的极值与均值。
 *
 * 折线图能看出形状，但读不准具体数值——「峰值到底是 87 还是 92」
 * 直接影响扩容决策。所以单独算出来。
 */
interface Summary {
  name: string
  min: number | null
  max: number | null
  avg: number | null
  latest: number | null
}

const summaries = computed<Summary[]>(() =>
  series.value.slice(0, MAX_SERIES).map((s) => {
    // 只统计有效点：把 null 计入会让均值失真
    const values = s.points
      .map((p) => p.v)
      .filter((v): v is number => v !== null && !Number.isNaN(v))

    if (!values.length) {
      return { name: labelOf(s.labels), min: null, max: null, avg: null, latest: null }
    }
    return {
      name: labelOf(s.labels),
      min: Math.min(...values),
      max: Math.max(...values),
      avg: values.reduce((a, b) => a + b, 0) / values.length,
      latest: values[values.length - 1],
    }
  })
)

const unit = computed(() => currentMeta.value?.unit ?? 'count')
const fmt = (v: number | null) => formatMetricValue(v, unit.value)

const totalPoints = computed(() =>
  series.value.reduce((sum, s) => sum + s.points.length, 0)
)

const goIntegrations = () => router.push('/integrations')
</script>

<template>
  <div class="trends-page">
    <main class="trends-main">
      <header class="page-header">
        <div>
          <h1 class="page-title">趋势分析</h1>
          <p class="page-sub">
            指标在时间轴上的变化。实时监控回答「现在怎么样」，
            这里回答<strong>「怎么变成这样的」</strong>——用于容量评估与故障回溯。
          </p>
        </div>
        <button class="btn-primary" type="button" :disabled="loading" @click="load">
          <RefreshCw :size="13" :class="{ spinning: loading }" /> 刷新
        </button>
      </header>

      <!-- 控制栏 -->
      <div class="controls">
        <label class="control-field">
          <span class="control-label">指标</span>
          <select v-model="metricId" class="control">
            <option v-for="m in metrics" :key="m.id" :value="m.id">{{ m.name }}</option>
            <option v-if="!metrics.length" :value="metricId">{{ metricId }}</option>
          </select>
        </label>

        <div class="range-group" role="group" aria-label="时间范围">
          <button
            v-for="r in RANGES"
            :key="r.id"
            type="button"
            class="range-btn"
            :class="{ 'is-active': rangeId === r.id }"
            @click="rangeId = r.id"
          >{{ r.label }}</button>
        </div>
      </div>

      <p v-if="currentMeta" class="metric-hint">
        <TrendingUp :size="12" /> {{ currentMeta.describe }}
      </p>

      <DataStateBoundary
        :loading="loading"
        :error="loadError"
        :count="series.length"
        empty-title="该时间范围内无数据"
        empty-description="可能是指标尚未被采集，或数据源刚启动不久"
        empty-action-text="去接入管理"
        :skeleton-rows="1"
        skeleton-height="320px"
        @retry="load"
        @empty-action="goIntegrations"
      >
        <!-- 图表 -->
        <section class="chart-card">
          <header class="chart-head">
            <BarChart3 :size="15" />
            <h2>{{ currentMeta?.name ?? metricId }}</h2>
            <span class="chart-meta">
              近 {{ actualHours ?? currentRange.hours }} 小时 ·
              {{ series.length }} 个实例 · {{ totalPoints }} 个采样点
            </span>
          </header>

          <div v-if="truncated" class="truncate-note">
            <AlertTriangle :size="12" />
            实例较多，仅展示前 {{ MAX_SERIES }} 条（共 {{ series.length }} 条）。
            再多图例会互相遮挡且颜色重复，难以分辨
          </div>

          <TrendChart
            :labels="axisLabels"
            :series="chartSeries"
            height="320px"
            :left-axis-name="unit === 'percent' ? '%' : ''"
            :show-legend="chartSeries.length > 1"
            :enable-zoom="totalPoints > 200"
          />
        </section>

        <!-- 统计摘要 -->
        <section class="summary-card">
          <header class="summary-head">
            <h2>区间统计</h2>
            <span class="summary-hint">折线能看形状，但读不准数值——扩容决策要看这里</span>
          </header>
          <div class="table-wrap">
            <table class="summary-table">
              <thead>
                <tr>
                  <th>实例</th>
                  <th class="num-col">最新</th>
                  <th class="num-col">峰值</th>
                  <th class="num-col">谷值</th>
                  <th class="num-col">均值</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in summaries" :key="s.name">
                  <td class="inst-col">{{ s.name }}</td>
                  <td class="num-col">{{ fmt(s.latest) }}</td>
                  <td class="num-col strong">{{ fmt(s.max) }}</td>
                  <td class="num-col">{{ fmt(s.min) }}</td>
                  <td class="num-col">{{ fmt(s.avg) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </DataStateBoundary>
    </main>
  </div>
</template>

<style scoped lang="scss">
.trends-page {
  min-height: 100vh;
  background: var(--color-bg);
}

.trends-main {
  max-width: 1520px;
  margin: 0 auto;
  padding: 20px 24px 32px;
}

/* ===== 页头 ===== */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-title {
  margin: 0 0 3px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--color-text-primary);
}

.page-sub {
  margin: 0;
  max-width: 70ch;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-tertiary);

  strong { color: var(--color-text-secondary); font-weight: 600; }
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
  border-radius: 8px;
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;
  flex-shrink: 0;

  &:hover:not(:disabled) { opacity: 0.9; }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.spinning { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ===== 控制栏 ===== */
.controls {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.control-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 12rem;
}

.control-label {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.control {
  height: 32px;
  padding: 0 9px;
  font-size: 13px;
  font-family: inherit;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-text-primary);

  &:focus {
    outline: none;
    border-color: var(--color-primary);
    box-shadow: 0 0 0 3px rgb(from var(--color-primary) r g b / 0.1);
  }
}

.range-group {
  display: inline-flex;
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  overflow: hidden;
}

.range-btn {
  height: 32px;
  padding: 0 13px;
  font-size: 12px;
  border: none;
  border-right: 1px solid var(--color-border-light);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;

  &:last-child { border-right: none; }
  &:hover:not(.is-active) { background: var(--color-fill-light); }

  &.is-active {
    background: var(--color-primary);
    color: #fff;
  }
}

.metric-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 0 14px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

/* ===== 图表 ===== */
.chart-card,
.summary-card {
  padding: 16px 18px;
  border: 1px solid var(--color-border-light);
  border-radius: 12px;
  background: var(--color-surface);
}

.chart-card { margin-bottom: 12px; }

.chart-head,
.summary-head {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 10px;
  color: var(--color-text-tertiary);

  h2 {
    margin: 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--color-text-primary);
  }
}

.chart-meta,
.summary-hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-text-quaternary, var(--color-text-tertiary));
  font-variant-numeric: tabular-nums;
}

.truncate-note {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 10px;
  padding: 7px 10px;
  border-radius: 6px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-warning);
  background: rgb(from var(--color-warning) r g b / 0.08);
  border: 1px solid rgb(from var(--color-warning) r g b / 0.2);
}

/* ===== 统计表 ===== */
.table-wrap {
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  overflow: hidden;
}

.summary-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;

  thead th {
    padding: 8px 12px;
    text-align: left;
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--color-text-tertiary);
    background: var(--color-fill-lighter);
    border-bottom: 1px solid var(--color-border-light);
  }

  tbody td {
    padding: 9px 12px;
    border-bottom: 1px solid var(--color-border-lighter, var(--color-border-light));
    color: var(--color-text-primary);
  }

  tbody tr:last-child td { border-bottom: none; }
  tbody tr:hover { background: var(--color-fill-lighter); }
}

.inst-col {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
  color: var(--color-text-secondary);
  word-break: break-all;
}

.num-col {
  text-align: right;
  font-variant-numeric: tabular-nums;
  width: 8rem;
}

.strong { font-weight: 600; }
</style>
