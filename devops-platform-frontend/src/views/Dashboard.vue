<script setup lang="ts">
import { computed, ref } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import {
  useClosureMetricsQuery,
  useDashboardOverviewQuery,
  useRootCauseStatsQuery,
  useTrendsQuery,
} from '@/api/queries/dashboard.query'
import PageLoading from '@/components/common/PageLoading.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'
import TrendChart, { type TrendSeries } from '@/components/common/TrendChart.vue'
import SlaRiskPanel from '@/components/dashboard/SlaRiskPanel.vue'

defineOptions({ name: 'Dashboard' })

/**
 * 四个区块各自独立查询（TanStack Query）。
 *
 * 此前是 `Promise.all` + 各自 `.catch(返回兜底值)`：降级逻辑藏在 catch 里，
 * 失败的区块只能显示空白，用户无从重试。现在每个查询有独立的 loading/error，
 * 模板按各自状态渲染——趋势加载失败只让图表区降级、能单独重试，
 * 不影响已加载成功的 KPI（6.51 契约）。
 */
const overviewQuery = useDashboardOverviewQuery()
const closureQuery = useClosureMetricsQuery()
const rootCauseQuery = useRootCauseStatsQuery()

/**
 * 趋势窗口天数。
 *
 * 本页固定 7 天（窗口切换在 AI 助手中心的趋势分析里，此处不重复提供入口）。
 * 仍用 ref 而非常量：useTrendsQuery 需要 Ref 以便把天数纳入 queryKey，
 * 将来若加窗口切换只需改这个值，查询会自动重拉。
 */
const trendDays = ref(7)
const trendQuery = useTrendsQuery(trendDays)

// KPI 主数据：它失败即整页错误态，其余区块都是它的补充
const data = overviewQuery.data
const loading = overviewQuery.isLoading
const loadError = overviewQuery.error

const closure = closureQuery.data
const rootCauseStats = rootCauseQuery.stats
const trend = trendQuery.data

/**
 * 数据更新时间：从 Query 的 dataUpdatedAt 派生。
 *
 * 此前是刷新时手动 `new Date().toLocaleTimeString()`——那记录的是
 * 「点刷新的时刻」而非「数据实际获取的时刻」，缓存命中时二者不同。
 */
const lastUpdated = computed(() => {
  const ts = overviewQuery.dataUpdatedAt.value
  if (!ts) return ''
  return new Date(ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
})

/** 刷新：四个查询一并重拉。refetch 会绕过 staleTime */
const loadDashboard = () => {
  void overviewQuery.refetch()
  void closureQuery.refetch()
  void rootCauseQuery.refetch()
  void trendQuery.refetch()
}

/** 工单趋势：柱（新建）+ 折线（验证通过） */
const ticketTrendSeries = computed<TrendSeries[]>(() => {
  const t = trend.value
  if (!t) return []
  return [
    { name: '新建工单', data: t.created, type: 'bar', color: '#409eff', suffix: ' 单' },
    { name: '验证通过', data: t.resolved, type: 'line', color: '#67c23a', suffix: ' 单', area: true }
  ]
})

/** 成本与命中率：成本挂右轴（量纲差两个数量级） */
const costTrendSeries = computed<TrendSeries[]>(() => {
  const t = trend.value
  if (!t) return []
  return [
    { name: '缓存命中率', data: t.cacheHitRate, type: 'line', color: '#e6a23c', suffix: '%', area: true },
    { name: 'AI 成本', data: t.cost, type: 'line', color: '#f56c6c', suffix: ' 元', useRightAxis: true }
  ]
})

// 根因分类中文标签
const RC_LABELS: Record<string, string> = {
  CONFIG: '配置错误', CAPACITY: '容量不足', CODE: '代码缺陷',
  DEPENDENCY: '依赖故障', NETWORK: '网络问题', DATA: '数据异常',
  HUMAN: '人为操作', EXTERNAL: '外部服务', UNKNOWN: '未定位'
}

// KPI 数据（从 API 动态生成）
// 口径说明（后端 DashboardServiceImpl 已对齐）：
//   总查询数 = 有效查询（CHAT + CACHE_HIT），不含被拒绝/失败的审计行
//   缓存命中率 = CACHE_HIT / 有效查询
//   平均成本 = 付费调用（cost_rmb>0）的均值，缓存命中成本 0 不计入
const kpis = computed(() => {
  if (!data.value) return []
  return [
    { label: '总工单数', value: data.value.totalTickets.toString() },
    { label: '缓存命中率', value: `${data.value.cacheHitRate.toFixed(1)}%` },
    { label: '有效查询数', value: data.value.totalQueries.toString() },
    { label: '平均成本(付费)', value: `¥${data.value.avgCostRmb.toFixed(4)}` }
  ]
})

// B5 闭环 KPI：MTTA / MTTM / MTTR
// null=尚无数据（不显示 0——0 意为"秒级响应"，与"还没有"完全不同）
const fmtMinutes = (m: number | null | undefined): string => {
  if (m === null || m === undefined) return '—'
  if (m < 60) return `${Math.round(m)} 分钟`
  const h = Math.floor(m / 60)
  const r = Math.round(m % 60)
  return r > 0 ? `${h} 小时 ${r} 分钟` : `${h} 小时`
}

const closureKpis = computed(() => {
  const c = closure.value
  if (!c) return []
  return [
    { label: 'MTTA 首响', value: fmtMinutes(c.mttaMinutes) },
    { label: 'MTTM 止损', value: fmtMinutes(c.mttmMinutes) },
    { label: 'MTTR 解决', value: fmtMinutes(c.mttrMinutes) },
    {
      label: '跳过验证率',
      value: c.skipRate === null ? '—' : `${c.skipRate.toFixed(1)}%`
    }
  ]
})

// 各阶段完成率
const stageProgress = computed(() => {
  const c = closure.value
  if (!c || c.total === 0) return []
  const pct = (n: number) => Math.round((n / c.total) * 100)
  return [
    { label: '已首响', count: c.firstResponded, pct: pct(c.firstResponded) },
    { label: '已止损', count: c.mitigated, pct: pct(c.mitigated) },
    { label: '根因确认', count: c.rootCauseConfirmed, pct: pct(c.rootCauseConfirmed) },
    { label: '已验证', count: c.verified, pct: pct(c.verified) }
  ]
})

// 根因分类 top（按数量降序，最多 5 项）
const rootCauseTop = computed(() =>
  Object.entries(rootCauseStats.value)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
)

// 首次加载由 Query 自动触发（挂载即拉取），无需 onMounted
</script>

<template>
  <div class="dashboard">
    <main class="main-container">
      <div class="content-wrapper">
        <!-- 页头：刷新 + 更新时间（面包屑已移除——导航栏已高亮「数据概览」，重复即冗余） -->
        <div class="dashboard-header">
          <button class="refresh-btn" :disabled="loading" @click="loadDashboard">
            <RefreshCw :size="16" :class="{ 'is-loading': loading }" />
            刷新
          </button>
          <span v-if="lastUpdated" class="last-updated">更新于 {{ lastUpdated }}</span>
        </div>

        <!-- 加载中 -->
        <PageLoading v-if="loading" tip="加载看板数据中..." />

        <!-- 加载失败 -->
        <ApiErrorState
          v-else-if="loadError"
          :error="loadError"
          retry-label="重新加载"
          @retry="loadDashboard"
        />

        <!-- 数据展示 -->
        <template v-else-if="data">
          <!-- 无数据提示 -->
          <div v-if="data.totalQueries === 0" class="no-data-banner">
            <p>当前暂无 AI 调用记录，KPI 与图表将在产生对话后填充真实数据。</p>
          </div>

          <!-- KPI 卡片 -->
          <div class="kpi-grid">
            <div v-for="(kpi, index) in kpis" :key="index" class="kpi-card">
              <div class="kpi-label">{{ kpi.label }}</div>
              <div class="kpi-value">{{ kpi.value }}</div>
            </div>
          </div>

          <!--
            SLA 风险清单（B1 端点落地）
            置于趋势图之前：这是「需立即行动」的信息，而趋势是回顾性分析。
            自行管理三态与刷新，加载失败不影响本页其余区块。
          -->
          <div class="data-grid data-grid--single sla-risk-row">
            <SlaRiskPanel />
          </div>

          <!-- 数据详情 -->
          <div class="data-grid">
            <!-- 模型分布 -->
            <div class="data-panel">
              <div class="panel-header">
                <h3>模型调用分布</h3>
              </div>
              <div class="panel-body">
                <div v-if="data.modelDistribution && data.modelDistribution.length > 0" class="model-list">
                  <div v-for="item in data.modelDistribution" :key="item.model" class="model-item">
                    <div class="model-info">
                      <span class="model-name">{{ item.model }}</span>
                      <span class="model-count">{{ item.count }} 次</span>
                    </div>
                    <div class="model-bar">
                      <div class="model-fill" :style="{ width: item.percentage + '%' }"></div>
                    </div>
                    <span class="model-percentage">{{ item.percentage.toFixed(1) }}%</span>
                  </div>
                </div>
                <AppEmpty v-else size="sm" />
              </div>
            </div>

            <!-- 成本与命中率趋势（真实 ECharts，此前是纯文本列表无图形） -->
            <div class="data-panel">
              <div class="panel-header">
                <h3>{{ trend ? `近 ${trend.windowDays} 日成本与命中率` : '成本与命中率趋势' }}</h3>
              </div>
              <div class="panel-body">
                <!--
                  面板级错误隔离：ECharts 渲染异常（如异常数据导致内部报错）
                  若不隔离会被 App 级 AppErrorBoundary 捕获而整页变红，
                  连本已加载成功的 KPI 也看不到了。趋势是增值信息，
                  不应拖挂主体（同 6.51 的降级策略）。
                -->
                <PanelErrorBoundary scope="成本趋势" min-height="240px">
                  <TrendChart
                    v-if="trend && trend.days.length"
                    :labels="trend.days"
                    :series="costTrendSeries"
                    height="240px"
                    left-axis-name="%"
                    right-axis-name="元"
                  />
                  <AppEmpty v-else size="sm" description="暂无趋势数据" />
                </PanelErrorBoundary>
              </div>
            </div>
          </div>

          <!-- 工单趋势（方案 B-1 新增）：建单与验证通过的每日变化 -->
          <div class="data-grid data-grid--single">
            <div class="data-panel">
              <div class="panel-header">
                <h3>{{ trend ? `近 ${trend.windowDays} 日工单趋势` : '工单趋势' }}</h3>
                <span class="panel-hint">验证通过按 MTTR 口径统计，跳过验证的工单不计入</span>
              </div>
              <div class="panel-body">
                <PanelErrorBoundary scope="工单趋势" min-height="240px">
                  <TrendChart
                    v-if="trend && trend.days.length"
                    :labels="trend.days"
                    :series="ticketTrendSeries"
                    height="240px"
                    left-axis-name="单"
                  />
                  <AppEmpty v-else size="sm" description="暂无趋势数据" />
                </PanelErrorBoundary>
              </div>
            </div>
          </div>

          <!-- B5 工单闭环度量 -->
          <div v-if="closure" class="closure-section">
            <h3 class="section-heading">工单闭环度量</h3>

            <!-- 闭环 KPI -->
            <div class="closure-kpi-grid">
              <div v-for="(kpi, i) in closureKpis" :key="i" class="closure-kpi-card">
                <div class="closure-kpi-label">{{ kpi.label }}</div>
                <div class="closure-kpi-value">{{ kpi.value }}</div>
              </div>
            </div>

            <!-- 各阶段完成率 -->
            <div v-if="stageProgress.length" class="stage-progress">
              <div v-for="s in stageProgress" :key="s.label" class="stage-progress-item" :title="`${s.label}: ${s.count}/${closure.total}`">
                <span class="stage-label">{{ s.label }}</span>
                <div class="stage-bar">
                  <div class="stage-fill" :style="{ width: s.pct + '%' }"></div>
                </div>
                <span class="stage-count">{{ s.count }} / {{ closure.total }} ({{ s.pct }}%)</span>
              </div>
            </div>

            <!-- 根因分类分布 -->
            <div v-if="rootCauseTop.length" class="root-cause-section">
              <h4 class="sub-heading">根因分类分布</h4>
              <div class="rc-list">
                <div v-for="[cat, count] in rootCauseTop" :key="cat" class="rc-item">
                  <span class="rc-label">{{ RC_LABELS[cat] || cat }}</span>
                  <span class="rc-count">{{ count }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 统计信息 -->
          <div class="stats-footer">
            <p>有效查询: {{ data.totalQueries }} | 缓存命中: {{ data.cacheHits }} ({{ data.cacheHitRate.toFixed(1) }}%) | 工单: {{ data.totalTickets }} | 平均成本(付费): ¥{{ data.avgCostRmb.toFixed(4) }}</p>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>

<style scoped>
.dashboard {
  height: 100%;
  overflow: auto;
}

.main-container {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;
}

.content-wrapper {
  min-height: 600px;
}

.dashboard-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 1px solid var(--color-border-light, #E5E7EB);
  border-radius: 8px;
  background: white;
  cursor: pointer;
  font-size: 0.875rem;
  color: var(--color-text-secondary, #6b7280);
  transition: border-color 0.15s, color 0.15s;
}
.refresh-btn:hover:not(:disabled) {
  border-color: var(--el-color-primary, #409eff);
  color: var(--el-color-primary, #409eff);
}
.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.refresh-btn .is-loading {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.last-updated {
  font-size: 0.75rem;
  color: var(--color-text-tertiary, #9ca3af);
}

.loading-state,
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  gap: 16px;
}

.no-data-banner {
  text-align: center;
  padding: 16px;
  margin-bottom: 16px;
  background: var(--color-bg-sunken, #f1f5f9);
  border-radius: var(--radius-md, 8px);
  color: var(--color-text-tertiary, #94a3b8);
  font-size: 13px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 20px;
  margin-bottom: 24px;
}

.kpi-card {
  background: white;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  transition: all 0.3s;
}

.kpi-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

.kpi-label {
  font-size: 14px;
  color: #666;
  margin-bottom: 12px;
}

.kpi-value {
  font-size: 32px;
  font-weight: 600;
  color: #1f2937;
  margin-bottom: 8px;
}

.data-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
  gap: 20px;
  margin-bottom: 24px;
}

/* 工单趋势独占整行：双系列折线+柱在半宽下会挤到看不清 */
.data-grid--single {
  grid-template-columns: 1fr;
}

/* SLA 风险清单单独一行，与下方数据详情留出间距 */
.sla-risk-row {
  margin-bottom: 16px;
}

.data-panel {
  background: white;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.panel-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
  margin: 0 0 16px 0;
}

.panel-hint {
  display: block;
  margin: -10px 0 12px 0;
  font-size: 12px;
  color: #9ca3af;
}

.panel-body {
  min-height: 200px;
}

.model-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.model-item {
  display: grid;
  grid-template-columns: 1fr 60px;
  gap: 8px;
  align-items: center;
}

.model-info {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
}

.model-name {
  font-weight: 500;
  color: #1f2937;
}

.model-count {
  color: #6b7280;
}

.model-bar {
  grid-column: 1 / 2;
  height: 8px;
  background: #e5e7eb;
  border-radius: 4px;
  overflow: hidden;
}

.model-fill {
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #8b5cf6);
  transition: width 0.3s;
}

.model-percentage {
  grid-column: 2 / 3;
  text-align: right;
  font-size: 14px;
  font-weight: 500;
  color: #1f2937;
}

.stats-footer {
  background: white;
  border-radius: 12px;
  padding: 16px 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  text-align: center;
}

.stats-footer p {
  margin: 0;
  font-size: 14px;
  color: #6b7280;
}

/* ===== B5 闭环度量 ===== */
.closure-section {
  background: white;
  border-radius: 12px;
  padding: 20px 24px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
  margin-top: 16px;
}

.section-heading {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
  margin: 0 0 16px 0;
}

.closure-kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;

  @media (max-width: 768px) {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .closure-kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

.closure-kpi-card {
  background: #F9FAFB;
  border-radius: 8px;
  padding: 12px 16px;
}

.closure-kpi-label {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 4px;
}

.closure-kpi-value {
  font-size: 20px;
  font-weight: 600;
  color: #1f2937;
  font-variant-numeric: tabular-nums;
}

.stage-progress {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 20px;
}

.stage-progress-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.stage-label {
  width: 80px;
  font-size: 13px;
  color: #4b5563;
  flex-shrink: 0;
}

.stage-bar {
  flex: 1;
  height: 8px;
  background: #E5E7EB;
  border-radius: 4px;
  overflow: hidden;
}

.stage-fill {
  height: 100%;
  background: var(--color-primary, #3B82F6);
  border-radius: 4px;
  transition: width 0.3s ease;
}

.stage-count {
  font-size: 12px;
  color: #9ca3af;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.root-cause-section { margin-top: 8px; }

.sub-heading {
  font-size: 14px;
  font-weight: 600;
  color: #4b5563;
  margin: 0 0 8px 0;
}

.rc-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.rc-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: #F3F4F6;
  border-radius: 999px;
  font-size: 13px;
}

.rc-label { color: #4b5563; }
.rc-count { font-weight: 600; color: #1f2937; font-variant-numeric: tabular-nums; }
</style>
