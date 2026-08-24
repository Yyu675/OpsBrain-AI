<script setup lang="ts">
/**
 * 实时监控（L2）。
 *
 * ── 数据从哪来 ────────────────────────────────────────────────
 * 后端 `/api/v1/metrics/overview` 一次性返回 5 张卡片的瞬时值。
 * 做成一个端点而非前端并发 5 个请求：首屏 5 并发会让 Prometheus
 * 在每次页面刷新时承受 5 倍峰值，而这些查询本来就该一起成功或一起失败。
 *
 * ── 单卡失败不拖垮整页 ────────────────────────────────────────
 * 后端逐条兜底，失败项带 `ok:false` + `error`。某个 exporter 没起时
 * 那张卡显示错误，其余照常——这比整页空白有用得多。
 *
 * ── 为什么每张卡都带迷你趋势线 ────────────────────────────────
 * 单看瞬时值无法判断「85% 是正在爬升还是刚从 95% 降下来」，
 * 而这两种情况的处置完全相反。所以卡片同时拉一小段 range 数据画趋势。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Activity, AlertTriangle, Cpu, Pause, Play, RefreshCw, Server } from 'lucide-vue-next'
import { useRouter } from 'vue-router'

import {
  fetchOverview,
  fetchRange,
  formatMetricValue,
  labelOf,
  severityOf,
  type MetricUnit,
  type OverviewCard,
} from '@/api/metrics'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import TrendChart from '@/components/common/TrendChart.vue'
import { parseDate } from '@/utils/time'

defineOptions({ name: 'Monitoring' })

const router = useRouter()

// ==================== 数据 ====================

const cards = ref<Record<string, OverviewCard>>({})
const loading = ref(false)
const loadError = ref<unknown>(null)
const lastUpdated = ref<number | null>(null)

/** 每张卡的迷你趋势（指标 ID -> 数值序列） */
const sparklines = ref<Record<string, { labels: string[]; values: number[] }>>({})

/** 卡片展示顺序。固定顺序避免每次刷新卡片跳位 */
const CARD_ORDER = ['cpu.usage', 'memory.usage', 'disk.usage', 'load.avg1', 'target.up']

const ICONS: Record<string, typeof Cpu> = {
  'cpu.usage': Cpu,
  'memory.usage': Server,
  'disk.usage': Server,
  'load.avg1': Activity,
  'target.up': Activity,
}

const orderedCards = computed(() =>
  CARD_ORDER.filter((id) => cards.value[id]).map((id) => ({ id, card: cards.value[id] }))
)

const loadOverview = async () => {
  loading.value = true
  loadError.value = null
  try {
    const data = await fetchOverview()
    cards.value = data.cards ?? {}
    lastUpdated.value = data.timestamp ?? Date.now()
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}

/**
 * 拉迷你趋势。
 *
 * 只对 percent 类指标拉——负载与存活数画成折线没有可读性
 * （前者要和核数比，后者是 0/1 阶跃）。少发两个请求也少给 Prometheus 压力。
 */
const SPARK_METRICS = ['cpu.usage', 'memory.usage', 'disk.usage']

const loadSparklines = async () => {
  const results = await Promise.allSettled(
    SPARK_METRICS.map((id) => fetchRange(id, 1, 120).then((r) => ({ id, r })))
  )

  const next: Record<string, { labels: string[]; values: number[] }> = {}
  for (const res of results) {
    if (res.status !== 'fulfilled') continue
    const { id, r } = res.value
    // 多实例时只取第一条：卡片是「总体态势」，多条线挤在 60px 高的图里没法看。
    // 要看每个实例请到趋势分析页
    const series = r.series?.[0]
    if (!series) continue
    next[id] = {
      labels: series.points.map((p) => {
        const d = parseDate(p.t)
        return d ? d.toLocaleTimeString('zh-CN', { hour12: false, second: undefined }) : ''
      }),
      // null（无数据）用 0 会画出假的「跌到底」，
      // 而 ECharts 对 NaN 会自然断线——这才是真实情况
      values: series.points.map((p) => (p.v === null ? NaN : p.v)),
    }
  }
  sparklines.value = next
}

const loadAll = async () => {
  await loadOverview()
  // 趋势失败不影响主数据，不 await 进错误分支
  void loadSparklines()
}

// ==================== 自动刷新 ====================

/**
 * 10 秒轮询——比接入管理页（30s）快，因为这页看的是实时态势。
 *
 * 但提供暂停开关：排障时用户常要盯住某一刻的数值截图或对比，
 * 页面自己刷掉会很干扰。
 */
const REFRESH_MS = 10_000
const paused = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const startTimer = () => {
  if (timer) return
  timer = setInterval(() => {
    // 暂停中或上一次还没回来就跳过，避免请求堆积
    if (paused.value || loading.value) return
    void loadAll()
  }, REFRESH_MS)
}

const togglePause = () => {
  paused.value = !paused.value
}

onMounted(async () => {
  await loadAll()
  startTimer()
})

onUnmounted(() => {
  // 不清会在离开页面后继续轮询，登出后仍在打接口
  if (timer) clearInterval(timer)
  timer = null
})

// ==================== 派生 ====================

/** 卡片主数值：多实例时取最大值——监控关心的是「最糟的那台」 */
const primaryValue = (card: OverviewCard): number | null => {
  const values = card.samples
    .map((s) => s.value)
    .filter((v): v is number => v !== null && !Number.isNaN(v))
  if (!values.length) return null
  return Math.max(...values)
}

/** 达到告警档位的实例数，用于卡片副标题 */
const alertingCount = (card: OverviewCard): number =>
  card.samples.filter((s) => severityOf(s.value, card.unit) !== 'normal').length

const cardSeverity = (card: OverviewCard) => severityOf(primaryValue(card), card.unit)

const fmt = (v: number | null, unit: MetricUnit) => formatMetricValue(v, unit)

/** 有多少个抓取目标掉线——这是「监控自身是否可信」的第一指标 */
const downTargets = computed(() => {
  const card = cards.value['target.up']
  if (!card?.ok) return null
  return card.samples.filter((s) => s.value === 0).length
})

const formatTime = (ts: number | null) => {
  const d = parseDate(ts)
  return d ? d.toLocaleTimeString('zh-CN', { hour12: false }) : '—'
}

const goIntegrations = () => router.push('/integrations')
</script>

<template>
  <div class="monitoring-page">
    <main class="monitoring-main">
      <header class="page-header">
        <div>
          <h1 class="page-title">实时监控</h1>
          <p class="page-sub">
            主机资源与抓取目标的实时态势。数据直接来自 Prometheus，
            <strong>OpsBrain 不存储副本</strong>——所见即监控系统当前的真实读数。
          </p>
        </div>
        <div class="header-actions">
          <span v-if="lastUpdated" class="last-updated">
            {{ paused ? '已暂停 · ' : '' }}更新于 {{ formatTime(lastUpdated) }}
          </span>
          <button
            class="btn-ghost"
            type="button"
            :title="paused ? '恢复自动刷新' : '暂停自动刷新（排障时避免数值跳动）'"
            @click="togglePause"
          >
            <component :is="paused ? Play : Pause" :size="13" />
            {{ paused ? '恢复' : '暂停' }}
          </button>
          <button class="btn-primary" type="button" :disabled="loading" @click="loadAll">
            <RefreshCw :size="13" :class="{ spinning: loading }" /> 刷新
          </button>
        </div>
      </header>

      <!-- 抓取目标掉线：监控自身不可信时必须最先告诉用户 -->
      <div v-if="downTargets !== null && downTargets > 0" class="banner is-danger">
        <AlertTriangle :size="16" />
        <span>
          有 <strong>{{ downTargets }}</strong> 个抓取目标处于掉线状态，
          这些目标的指标已停止更新——下方数值可能是陈旧的
        </span>
        <button class="banner-link" type="button" @click="goIntegrations">去接入管理</button>
      </div>

      <DataStateBoundary
        :loading="loading"
        :error="loadError"
        :count="orderedCards.length"
        empty-title="暂无监控数据"
        empty-description="请确认 Prometheus 数据源已接入"
        empty-action-text="去接入管理"
        :skeleton-rows="3"
        skeleton-height="140px"
        @retry="loadAll"
        @empty-action="goIntegrations"
      >
        <div class="card-grid">
          <article
            v-for="{ id, card } in orderedCards"
            :key="id"
            class="metric-card"
            :class="[`sev-${cardSeverity(card)}`, { 'is-error': !card.ok }]"
          >
            <header class="card-head">
              <component :is="ICONS[id] ?? Activity" :size="15" class="card-icon" />
              <h2 class="card-name">{{ card.name }}</h2>
              <span v-if="card.samples.length > 1" class="card-count">
                {{ card.samples.length }} 项
              </span>
            </header>

            <!-- 单卡失败：如实标注，不影响其余卡片 -->
            <div v-if="!card.ok" class="card-error">
              <AlertTriangle :size="13" />
              <span>{{ card.error || '该指标查询失败' }}</span>
            </div>

            <template v-else>
              <div class="card-value-row">
                <span class="card-value">{{ fmt(primaryValue(card), card.unit) }}</span>
                <span v-if="card.samples.length > 1" class="card-value-note">最高</span>
              </div>

              <p v-if="alertingCount(card) > 0" class="card-alerting">
                {{ alertingCount(card) }} 个实例超过阈值
              </p>
              <p v-else class="card-describe">{{ card.describe }}</p>

              <!-- 迷你趋势：单看瞬时值无法判断是在爬升还是在回落 -->
              <div v-if="sparklines[id]?.values.length" class="spark">
                <TrendChart
                  :labels="sparklines[id].labels"
                  :series="[{
                    name: card.name,
                    data: sparklines[id].values,
                    smooth: true,
                    area: true,
                    suffix: card.unit === 'percent' ? '%' : ''
                  }]"
                  height="64px"
                  :show-legend="false"
                />
              </div>

              <!-- 多实例明细 -->
              <ul v-if="card.samples.length > 1" class="inst-list">
                <li
                  v-for="(s, i) in card.samples.slice(0, 5)"
                  :key="i"
                  class="inst-item"
                  :class="`sev-${severityOf(s.value, card.unit)}`"
                >
                  <span class="inst-name">{{ labelOf(s.labels) }}</span>
                  <span class="inst-value">{{ fmt(s.value, card.unit) }}</span>
                </li>
                <li v-if="card.samples.length > 5" class="inst-more">
                  还有 {{ card.samples.length - 5 }} 项，查看趋势分析页
                </li>
              </ul>
            </template>
          </article>
        </div>
      </DataStateBoundary>
    </main>
  </div>
</template>

<style scoped lang="scss">
.monitoring-page {
  min-height: 100vh;
  background: var(--color-bg);
}

.monitoring-main {
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

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.last-updated {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-quaternary, var(--color-text-tertiary));
}

.btn-ghost,
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
  border-radius: 8px;
  cursor: pointer;

  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.btn-ghost {
  border: 1px solid var(--color-border-light);
  background: transparent;
  color: var(--color-text-secondary);

  &:hover:not(:disabled) { background: var(--color-fill-light); }
}

.btn-primary {
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: #fff;

  &:hover:not(:disabled) { opacity: 0.9; }
}

.spinning { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ===== 横幅 ===== */
.banner {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 14px;
  margin-bottom: 14px;
  border-radius: 8px;
  font-size: 13px;

  strong { font-weight: 700; }

  &.is-danger {
    color: var(--color-danger);
    background: rgb(from var(--color-danger) r g b / 0.07);
    border: 1px solid rgb(from var(--color-danger) r g b / 0.25);
  }
}

.banner-link {
  margin-left: auto;
  flex-shrink: 0;
  padding: 3px 10px;
  font-size: 12px;
  border-radius: 6px;
  border: 1px solid currentColor;
  background: transparent;
  color: inherit;
  cursor: pointer;

  &:hover { background: rgb(from var(--color-danger) r g b / 0.1); }
}

/* ===== 卡片 ===== */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(17rem, 1fr));
  gap: 12px;
}

.metric-card {
  position: relative;
  padding: 15px 17px;
  border: 1px solid var(--color-border-light);
  border-radius: 12px;
  background: var(--color-surface);
  overflow: hidden;

  /* 顶部细色条表示健康档位——比整卡染色克制，不干扰读数 */
  &::before {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    top: 0;
    height: 3px;
    background: var(--color-border);
  }

  &.sev-warn::before { background: var(--color-warning); }
  &.sev-danger::before { background: var(--color-danger); }
  &.is-error::before { background: var(--color-text-quaternary, var(--color-text-tertiary)); }
  &.is-error { opacity: 0.75; }
}

.card-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.card-icon { color: var(--color-text-tertiary); flex-shrink: 0; }

.card-name {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.card-count {
  margin-left: auto;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 9px;
  background: var(--color-fill-light);
  color: var(--color-text-tertiary);
}

.card-value-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: 8px;
}

.card-value {
  font-size: 27px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
  color: var(--color-text-primary);

  .sev-warn & { color: var(--color-warning); }
  .sev-danger & { color: var(--color-danger); }
}

.card-value-note {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.card-describe,
.card-alerting {
  margin: 5px 0 0;
  font-size: 11px;
  line-height: 1.5;
}

.card-describe { color: var(--color-text-quaternary, var(--color-text-tertiary)); }
.card-alerting { color: var(--color-warning); font-weight: 500; }

.card-error {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-tertiary);
  background: var(--color-fill-lighter);
  word-break: break-all;
}

.spark {
  margin-top: 8px;
  margin-left: -6px;
  margin-right: -6px;
}

/* ===== 实例明细 ===== */
.inst-list {
  list-style: none;
  margin: 10px 0 0;
  padding: 9px 0 0;
  border-top: 1px solid var(--color-border-lighter, var(--color-border-light));
  display: grid;
  gap: 4px;
}

.inst-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;

  &.sev-warn .inst-value { color: var(--color-warning); }
  &.sev-danger .inst-value { color: var(--color-danger); }
}

.inst-name {
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--color-text-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inst-value {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--color-text-secondary);
  flex-shrink: 0;
}

.inst-more {
  font-size: 10px;
  color: var(--color-text-quaternary, var(--color-text-tertiary));
}
</style>
