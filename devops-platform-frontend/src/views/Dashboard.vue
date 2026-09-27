<script setup lang="ts">
import { parseDate } from '@/utils/time'
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { RefreshCw, Ticket, Zap, Activity, Coins } from 'lucide-vue-next'
import {
  useDashboardOverviewQuery,
  useRootCauseStatsQuery,
  useTrendsQuery,
} from '@/api/queries/dashboard.query'
import PageLoading from '@/components/common/PageLoading.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'
import TrendChart, { type TrendSeries } from '@/components/common/TrendChart.vue'
import SlaRiskPanel from '@/components/dashboard/SlaRiskPanel.vue'
import AlertFeed from '@/components/dashboard/AlertFeed.vue'
import WorkbenchQueues from '@/components/dashboard/WorkbenchQueues.vue'

const router = useRouter()

defineOptions({ name: 'Dashboard' })

/** 趋势图点击下钻：跳转到对应日期的工单列表 */
const onTrendClick = (p: { seriesName: string; label: string; value: number }) => {
  router.push({ path: '/tickets', query: { date: p.label } })
}

/**
 * 各区块独立查询（TanStack Query）。
 *
 * 此前是 `Promise.all` + 各自 `.catch(返回兜底值)`：降级逻辑藏在 catch 里，
 * 失败的区块只能显示空白，用户无从重试。现在每个查询有独立的 loading/error，
 * 模板按各自状态渲染——趋势加载失败只让图表区降级、能单独重试，
 * 不影响已加载成功的 KPI（6.51 契约）。
 *
 * 2026-09-26 瘦身：本页定位「值班首屏 · 业务概览」——SLA 风险、实时告警流、
 * AI 成本与工单存量。闭环度量（MTTA/MTTR/阶段完成率）与诊断区（会话/校准/
 * AI 反馈）与效能大盘同源重复，已迁往效能大盘承载，此处不再双份维护。
 */
const overviewQuery = useDashboardOverviewQuery()
const rootCauseQuery = useRootCauseStatsQuery()

/**
 * 趋势窗口天数。
 *
 * 本页固定 7 天（窗口切换在监控中心的趋势探索器里，此处不重复提供入口）。
 * 仍用 ref 而非常量：useTrendsQuery 需要 Ref 以便把天数纳入 queryKey，
 * 将来若加窗口切换只需改这个值，查询会自动重拉。
 */
const trendDays = ref(7)
const trendQuery = useTrendsQuery(trendDays)

// KPI 主数据：它失败即整页错误态，其余区块都是它的补充
const data = overviewQuery.data
const loading = overviewQuery.isLoading
const loadError = overviewQuery.error

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
  // 走 parseDate：趋势图 X 轴若按浏览器时区解析，
  // 跨时区用户看到的时间点会整体平移
  const d = parseDate(ts)
  return d ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : String(ts)
})

/** 刷新：三个查询一并重拉。refetch 会绕过 staleTime */
const loadDashboard = () => {
  void overviewQuery.refetch()
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
// 卡片设计（2026-09-27）：彩色图标芯片 + 大数字 + 口径副标题（StatCard 风格）
const kpis = computed(() => {
  if (!data.value) return []
  return [
    { label: '总工单数', value: data.value.totalTickets.toString(), icon: Ticket, tone: 'brand', to: '/tickets', subtitle: '点击进入工单列表' },
    { label: '缓存命中率', value: `${data.value.cacheHitRate.toFixed(1)}%`, icon: Zap, tone: 'success', subtitle: '语义缓存 · 目标 > 85%' },
    { label: '有效查询数', value: data.value.totalQueries.toString(), icon: Activity, tone: 'info', subtitle: '对话 + 缓存命中' },
    { label: '平均成本(付费)', value: `¥${data.value.avgCostRmb.toFixed(4)}`, icon: Coins, tone: 'warning', subtitle: '仅付费调用均值' }
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
        <!-- 页头：刷新 + 更新时间（面包屑已移除——本页即首页工作台，导航栏已高亮「首页」） -->
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

          <!-- KPI 卡片（StatCard 风格：彩色图标芯片 + 大数字 + 口径副标题） -->
          <div class="kpi-grid">
            <div
              v-for="kpi in kpis" :key="kpi.label"
              class="kpi-card"
              :class="{ 'kpi-card--link': !!kpi.to }"
            >
              <div class="kpi-main">
                <div class="kpi-label">{{ kpi.label }}</div>
                <div class="kpi-value" :class="`kpi-value--${kpi.tone}`">{{ kpi.value }}</div>
                <div v-if="kpi.subtitle" class="kpi-subtitle">{{ kpi.subtitle }}</div>
              </div>
              <div class="kpi-icon" :class="`kpi-icon--${kpi.tone}`">
                <component :is="kpi.icon" :size="20" />
              </div>
              <RouterLink v-if="kpi.to" :to="kpi.to" class="kpi-cover" :aria-label="`${kpi.label}：查看详情`" />
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

          <!-- 实时告警流（/ai/ws/alerts WebSocket 通道，紧凑侧栏） -->
          <div class="data-grid data-grid--single">
            <AlertFeed />
          </div>

          <!-- 行动队列区（2026-09-27）：待处理工单队列 + 待审批动作。
               KPI/趋势回答「系统怎么样」，队列回答「我现在该干什么」 -->
          <div class="data-grid data-grid--single">
            <WorkbenchQueues />
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
                    @chart-click="onTrendClick"
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

          <!-- 根因分类分布（B5）：哪类根因最多——分布直接决定该补哪类知识 -->
          <div class="data-grid data-grid--single">
            <div class="data-panel">
              <div class="panel-header">
                <h3>根因分类分布</h3>
                <span class="panel-hint">闭环度量与诊断详情已迁至「效能大盘」，此处保留业务向的根因分布</span>
              </div>
              <div class="panel-body panel-body--auto">
                <div v-if="rootCauseTop.length" class="rc-list">
                  <div v-for="[cat, count] in rootCauseTop" :key="cat" class="rc-item">
                    <span class="rc-label">{{ RC_LABELS[cat] || cat }}</span>
                    <span class="rc-count">{{ count }}</span>
                  </div>
                </div>
                <AppEmpty v-else size="sm" description="暂无根因数据" />
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
  border: 1px solid var(--border-1);
  border-radius: 8px;
  background: var(--surface-1);
  cursor: pointer;
  font-size: 0.875rem;
  color: var(--text-2);
  transition: border-color 0.15s, color 0.15s;
}
.refresh-btn:hover:not(:disabled) {
  border-color: var(--el-color-primary, var(--brand));
  color: var(--el-color-primary, var(--brand));
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
  color: var(--text-3);
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
  background: var(--surface-2);
  border-radius: var(--radius, 8px);
  color: var(--text-3, #94a3b8);
  font-size: 13px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

/* StatCard 风格（2026-09-27）：彩色图标芯片 + 大数字 + 口径副标题 */
.kpi-card {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  background: var(--surface-1);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  padding: 18px 20px;
  box-shadow: var(--shadow-sm);
  transition: box-shadow var(--duration-normal) var(--ease-out),
    transform var(--duration-fast) var(--ease-out);
}

.kpi-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

/* 可跳转卡片：覆盖层链接整卡可点，右缘出现 → 暗示可跳转 */
.kpi-card--link {
  cursor: pointer;
}

.kpi-cover {
  position: absolute;
  inset: 0;
  border-radius: inherit;
}

.kpi-card--link::after {
  content: '→';
  position: absolute;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 18px;
  color: var(--text-3);
  opacity: 0;
  transition: opacity var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
  /* 不被图标芯片遮住 */
  z-index: 1;
}

.kpi-card--link:hover::after {
  opacity: 1;
  color: var(--brand);
}

.kpi-main {
  min-width: 0;
}

.kpi-label {
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-2);
  margin-bottom: 6px;
}

.kpi-value {
  font-size: var(--text-3xl);
  font-weight: 700;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  color: var(--text-1);
  line-height: 1.15;
}

.kpi-value--brand { color: var(--brand); }
.kpi-value--success { color: var(--success); }
.kpi-value--info { color: var(--info); }
.kpi-value--warning { color: var(--warning); }

.kpi-subtitle {
  margin-top: 4px;
  font-size: var(--text-xs);
  color: var(--text-3);
}

/* 彩色图标芯片（demo StatCard 的核心识别特征） */
.kpi-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: var(--radius-lg);
  display: flex;
  align-items: center;
  justify-content: center;
}

.kpi-icon--brand { background: var(--brand-subtle); color: var(--brand); }
.kpi-icon--success { background: var(--success-subtle); color: var(--success); }
.kpi-icon--info { background: var(--info-subtle); color: var(--info); }
.kpi-icon--warning { background: var(--warning-subtle); color: var(--warning); }

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
  background: var(--surface-1);
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.panel-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0 0 16px 0;
}

.panel-hint {
  display: block;
  margin: -10px 0 12px 0;
  font-size: 12px;
  color: var(--text-3);
}

.panel-body {
  min-height: 200px;
}

/* 根因分布是 chip 流，内容多高就多高，不撑 200px 占位 */
.panel-body--auto {
  min-height: 0;
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
  color: var(--text-1);
}

.model-count {
  color: var(--text-2);
}

.model-bar {
  grid-column: 1 / 2;
  height: 8px;
  background: var(--border-1);
  border-radius: 4px;
  overflow: hidden;
}

.model-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--brand), #8b5cf6);
  transition: width 0.3s;
}

.model-percentage {
  grid-column: 2 / 3;
  text-align: right;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-1);
}

.stats-footer {
  background: var(--surface-1);
  border-radius: 12px;
  padding: 16px 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  text-align: center;
}

.stats-footer p {
  margin: 0;
  font-size: 14px;
  color: var(--text-2);
}

/* ── 根因分类分布（chip 流） ── */
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
  background: var(--surface-2);
  border-radius: 999px;
  font-size: 13px;
}

.rc-label { color: var(--text-2); }
.rc-count { font-weight: 600; color: var(--text-1); font-variant-numeric: tabular-nums; }

</style>
