<script setup lang="ts">
/**
 * 效能大盘（PRD FR-7 的最小可用面）。
 *
 * 回答一个问题：这个平台的处置体系运转得好不好。
 * 数据全部来自既有端点的聚合，不做新的后端计算：
 *   告警压缩比  = 告警总数 / 告警自动建单数（closure.alertSourced）
 *   MTTA / MTTR = 闭环度量
 *   知识命中率  = 诊断证据 knowledge 方向的 SUCCESS 占比（诊断看板）
 *   自动闭环率  = 自愈台账 stats
 *   管道心跳    = 看门狗告警的新鲜度
 *
 * 与「数据概览」的分工（2026-09-26 瘦身后）：那页看值班首屏（SLA 风险/
 * 实时告警流/AI 成本与工单存量），本页看处置效能与飞轮——原先两页双份
 * 维护的闭环度量与诊断区统一收在本页。
 */
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { RefreshCw, Activity, Gauge, BookOpen, ShieldCheck, Timer, Zap } from 'lucide-vue-next'
import { getClosureMetrics, getDiagnosisBoard, getMissingPostmortem, getDocHealth, getEffectivenessTrend, type DocHealth } from '@/api/dashboard'
import { useAiAnalysisStatsQuery } from '@/api/queries/dashboard.query'
import { deprecateKnowledgeDoc } from '@/api/knowledge'
import { notify } from '@/utils/notify'
import { ElMessageBox } from 'element-plus'
import { getHealingStats } from '@/api/healing'
import { fetchAlerts, fetchPipelineHeartbeat, fetchLogsHeartbeat, type LogsHeartbeat } from '@/api/alerts'
import PageLoading from '@/components/common/PageLoading.vue'
import TrendChart, { type TrendSeries } from '@/components/common/TrendChart.vue'
import ObservationStatsPanel from '@/components/dashboard/ObservationStatsPanel.vue'

defineOptions({ name: 'EffectivenessView' })

/** 压缩比时间窗（null=全时段）；进 closure 查询的 queryKey，切换即重拉 */
const windowDays = ref<number | null>(null)
const closure = useQuery({
  queryKey: computed(() => ['eff', 'closure', windowDays.value]),
  queryFn: () => getClosureMetrics(windowDays.value ?? undefined),
  staleTime: 30_000,
})
const healing = useQuery({ queryKey: ['eff', 'healing'], queryFn: getHealingStats, staleTime: 30_000 })
const heartbeat = useQuery({ queryKey: ['eff', 'heartbeat'], queryFn: fetchPipelineHeartbeat, staleTime: 30_000, refetchInterval: 60_000 })
const alerts = useQuery({ queryKey: ['eff', 'alertsTotal'], queryFn: () => fetchAlerts({ page: 1, size: 1 }), staleTime: 30_000 })
const board = useQuery({
  queryKey: computed(() => ['eff', 'board', windowDays.value]),
  // 全时段映射到看板的最大窗（后端夹紧上限 90 天）；选了窗口则与压缩比同口径
  queryFn: () => getDiagnosisBoard(windowDays.value ?? 90),
  staleTime: 30_000,
})

const loading = computed(() => closure.isLoading.value || healing.isLoading.value || board.isLoading.value)

/**
 * AI 根因分析反馈（承接自数据概览瘦身迁出的读数）。
 * 口径纪律：rated=0 时后端给 0.0，但那是「还没有人评过分」不是「准确率 0%」——
 * null/无数据 ≠ 0，显示「—」。
 */
const aiStatsQuery = useAiAnalysisStatsQuery()
const aiStats = aiStatsQuery.data

const aiAccuracyText = computed(() => {
  const s = aiStats.value
  if (!s || s.rated === 0) return '—'
  return `${(s.helpfulRate * 100).toFixed(1)}%`
})
const aiRatedText = computed(() => {
  const s = aiStats.value
  if (!s) return '—'
  return s.rated === 0 ? '暂无反馈' : `${s.helpful}/${s.rated}`
})

/** 工单闭环阶段完成率（承接自数据概览）：已首响→已止损→根因确认→已验证 */
const stageProgress = computed(() => {
  const c = closure.data.value
  if (!c || c.total === 0) return []
  const pct = (n: number) => Math.round((n / c.total) * 100)
  return [
    { label: '已首响', count: c.firstResponded, pct: pct(c.firstResponded) },
    { label: '已止损', count: c.mitigated, pct: pct(c.mitigated) },
    { label: '根因确认', count: c.rootCauseConfirmed, pct: pct(c.rootCauseConfirmed) },
    { label: '已验证', count: c.verified, pct: pct(c.verified) },
  ]
})

/** 按状态取诊断会话数；窗口里可能没有某状态，缺省 0 */
const sessionCount = (status: string) =>
  board.data.value?.sessions.byStatus.find((s) => s.status === status)?.count ?? 0

/** 假设置信度校准读数：ECE 越低越好；空判定集显示「—」（没人反馈 ≠ 误差 0） */
const calibration = computed(() => board.data.value?.calibration ?? null)
const calibEceText = computed(() =>
  calibration.value?.ece == null ? '—' : `${(calibration.value.ece * 100).toFixed(1)}%`)
const calibAccText = computed(() =>
  calibration.value?.empiricalAccuracy == null ? '—' : `${(calibration.value.empiricalAccuracy * 100).toFixed(1)}%`)

/** 诊断量趋势：柱=当日发起，折=当日完成（补零语义由后端保证，此处只管画） */
const diagnosisTrendSeries = computed<TrendSeries[]>(() => {
  const t = board.data.value?.sessionTrend
  if (!t || !t.days.length) return []
  return [
    { name: '发起诊断', data: t.created, type: 'bar', color: '#409eff', suffix: ' 次' },
    { name: '完成诊断', data: t.completed, type: 'line', color: '#67c23a', suffix: ' 次' },
  ]
})

// ---- 模板便捷访问（避免在模板里写 closure.data.value?.x 长链） ----
const closureTotal = computed(() => closure.data.value?.total ?? 0)
const sessions = computed(() => board.data.value?.sessions ?? null)
const sessionTrendDays = computed(() => board.data.value?.sessionTrend.days ?? [])

const calibEceTitle = computed(() => {
  const c = calibration.value
  if (!c || c.ece == null) return '判定集为空：尚无 HELPFUL/WRONG 反馈'
  return `判定集 ${c.ratedTotal} 条（PARTIAL 豁免 ${c.excludedPartial}）`
})
const aiAccuracyTitle = computed(() => {
  const s = aiStats.value
  if (!s || s.rated === 0) return '暂无反馈数据'
  return `有用 ${s.helpful} / 已评分 ${s.rated}`
})

const refresh = () => {
  void closure.refetch(); void healing.refetch(); void heartbeat.refetch(); void alerts.refetch(); void board.refetch()
  void aiStatsQuery.refetch()
}

const fmtMinutes = (v: number | null | undefined) => v == null ? '—' : (v >= 60 ? `${(v / 60).toFixed(1)}h` : `${Math.round(v)}m`)
const fmtRate = (v: number | null | undefined) => v == null ? '—' : `${(v * 100).toFixed(1)}%`

/** 压缩比的数值形态（目标判定用）；显示形态由 compression 组装 */
const compressionValue = computed<number | null>(() => {
  const c = closure.data.value
  if (windowDays.value != null && c?.alertsInWindow != null && c?.alertSourcedInWindow != null) {
    return c.alertSourcedInWindow > 0 ? c.alertsInWindow / c.alertSourcedInWindow : null
  }
  const total = alerts.data.value?.total ?? 0
  const sourced = c?.alertSourced ?? 0
  return sourced > 0 ? total / sourced : null
})

/** 压缩比口径：选了窗口就用同窗的告警/建单计数（否则全时段告警对上窗口建单是错的） */
const compression = computed(() => {
  const v = compressionValue.value
  return v == null ? '—' : `${v.toFixed(1)}:1`
})

/**
 * 压缩比口径说明：选了窗口就用同窗的告警/建单计数（否则全时段告警对上窗口建单是错的）。
 * 2026-09-27 Incident 方案 C：口径升级为「告警 : 事件 : 工单」三档——
 * 「事件数」是派生归并（同 system+service+10分钟窗），让「一次故障反复响」
 * 与「多个不同故障」在数字上分开，压缩比不再被高频重复告警虚增。
 */
const compressionHint = computed(() => {
  const c = closure.data.value
  const windowMode = windowDays.value != null
  const incidents = windowMode ? c?.incidentsInWindow : c?.incidentsTotal
  const alertCount = windowMode ? c?.alertsInWindow : alerts.data.value?.total
  const ticketCount = windowMode ? c?.alertSourcedInWindow : c?.alertSourced
  if (incidents != null && alertCount != null && ticketCount != null) {
    return `${windowMode ? `近 ${windowDays.value} 天` : '全时段'}：告警 ${alertCount} · 事件 ${incidents} · 建单 ${ticketCount}`
  }
  return windowMode ? `近 ${windowDays.value} 天同窗口径` : '全时段：告警数 : 自动建单数'
})

/** 知识命中率：诊断证据 knowledge 方向 SUCCESS 占比 */
const knowledgeHitRate = computed(() => {
  const dir = board.data.value?.evidenceDirections?.find(d => d.type === 'knowledge')
  return dir ? dir.successRate : null
})

/** 复盘完成率：复盘数 / 已完结工单数；已完结为 0 时 null（不是没有，是没法算） */
const postmortemRate = computed(() => closure.data.value?.postmortemRate)

/** 目标值标注：数字配了基准才是判断（PRD §2.2 北极星/P 阶段目标）。 */
const targetOf = (key: string): { text: string; met: boolean | null } | null => {
  const h = healing.data.value
  switch (key) {
    case 'compression': {
      const v = compressionValue.value
      return v == null ? null : { text: '目标 ≥10:1', met: v >= 10 }
    }
    case 'knowledge': {
      const r = knowledgeHitRate.value
      return r == null ? null : { text: '目标 ≥50%', met: r >= 0.5 }
    }
    case 'postmortem': {
      const r = postmortemRate.value
      return r == null ? null : { text: '目标 ≥70%', met: r >= 70 }
    }
    case 'autoclose': {
      const r = h?.autoClosedLoopRate
      // PRD：P0/P1 阶段恒 0（有意不放权）——标为「观察」而非「未达标」
      return r == null ? null : { text: 'P2 目标 ≥10%', met: null }
    }
    default:
      return null
  }
}

const kpis = computed(() => [
  { key: 'compression', label: '告警压缩比', value: compression.value, icon: Gauge, tone: 'info', hint: compressionHint.value },
  { key: 'mtta', label: 'MTTA 首响', value: fmtMinutes(closure.data.value?.mttaMinutes), icon: Timer, tone: 'info', hint: '告警发生 → 有人认领 · 全时段' },
  { key: 'mttr', label: 'MTTR 解决', value: fmtMinutes(closure.data.value?.mttrMinutes), icon: Timer, tone: 'info', hint: '建单 → 验证通过 · 全时段' },
  { key: 'knowledge', label: '知识命中率', value: fmtRate(knowledgeHitRate.value), icon: BookOpen, tone: 'success', hint: '诊断引用到知识的比例' },
  { key: 'postmortem', label: '复盘完成率', value: postmortemRate.value == null ? '—' : `${postmortemRate.value.toFixed(1)}%`, icon: BookOpen, tone: 'success', hint: '完结工单中带复盘的比例 · 全时段 · 点击查看欠账清单', action: true },
  { key: 'autoclose', label: '自动闭环率', value: fmtRate(healing.data.value?.autoClosedLoopRate), icon: Zap, tone: 'success', hint: '自愈动作自动完成比例' },
  { key: 'verifyfail', label: '验证失败率', value: fmtRate(healing.data.value?.verifyFailRate), icon: ShieldCheck, tone: 'warning', hint: '自愈后验证未通过比例' },
])

/** 复盘完成率卡的行动出口：点开看欠账清单 */
const missingPostmortemOpen = ref(false)
const missingPostmortem = useQuery({
  queryKey: ['eff', 'missing-postmortem'],
  queryFn: () => getMissingPostmortem(20),
  staleTime: 30_000,
})

const onKpiClick = (key: string) => {
  if (key === 'postmortem') {
    missingPostmortemOpen.value = true
    void missingPostmortem.refetch()
  }
}

/** 管道心跳展示态：正常 / 静默 / 未知 */
const pipeline = computed(() => {
  const hb = heartbeat.data.value
  if (!hb) return { state: 'unknown', text: '未知', note: '心跳接口不可达' }
  if (hb.silent) return { state: 'silent', text: '静默', note: `看门狗超 ${hb.silenceMinutes} 分钟未送达，告警可能正在丢失` }
  return { state: 'ok', text: '正常', note: `最后心跳 ${hb.lastSeenAt ?? '—'}` }
})

/** 日志管道心跳（与告警管道同族）：采集断流时诊断的日志证据会静默变空 */
const logsHeartbeat = useQuery({
  queryKey: ['eff', 'logs-heartbeat'],
  queryFn: (): Promise<LogsHeartbeat | null> => fetchLogsHeartbeat(),
  staleTime: 30_000,
  refetchInterval: 60_000,
})
const logsPipeline = computed(() => {
  const hb = logsHeartbeat.data.value
  if (!hb) return { state: 'unknown', text: '未知', note: '探测接口不可达' }
  if (!hb.lokiEnabled) return { state: 'unknown', text: '未启用', note: '日志源未接（devops.logs.loki.enabled=false）' }
  if (!hb.probed) return { state: 'unknown', text: '未知', note: '尚未探测（启动宽限内）' }
  if (hb.silent) return { state: 'silent', text: '静默', note: `超 ${hb.silenceMinutes} 分钟无新日志，采集管道可能断流（查 Promtail 目标健康度）` }
  return { state: 'ok', text: '正常', note: `最新日志 ${hb.freshestLogAt ?? '—'}` }
})

/** 证据方向健康度：四条取数路各自的现状 */
const directions = computed(() => board.data.value?.evidenceDirections ?? [])

const sufficiencyRows = computed(() => board.data.value?.sessions.sufficiency ?? [])

/** 知识健康度（治理出口）：点踩多于点赞的文档是需要复核的负资产 */
const docHealth = useQuery({ queryKey: ['eff', 'doc-health'], queryFn: (): Promise<DocHealth[]> => getDocHealth(), staleTime: 30_000 })
const docsNeedReview = computed(() =>
  (docHealth.data.value ?? []).filter(d => d.wrong > d.helpful)
)

/** 下架一篇负资产文档（复核动作闭环：看完→处置，而不是看完还得自己去文档页找按钮） */
const deprecateDoc = async (d: DocHealth) => {
  try {
    await ElMessageBox.confirm(
      `下架「${d.title}」？下架后退出检索（不再被引用），历史版本保留可恢复。`,
      '确认下架',
      { type: 'warning', confirmButtonText: '下架', cancelButtonText: '取消' }
    )
  } catch {
    return   // 取消
  }
  try {
    await deprecateKnowledgeDoc(d.docId, '效能大盘复核下架（反馈为负）')
    notify.success('已下架')
    void docHealth.refetch()
  } catch (e) {
    notify.error('下架失败：' + (e instanceof Error ? e.message : String(e)))
  }
}

/** 效能趋势（每日快照）：复盘率与压缩比的水位变化 */
const trend = useQuery({ queryKey: ['eff', 'trend'], queryFn: () => getEffectivenessTrend(30), staleTime: 60_000 })

const trendLabels = computed(() =>
  (trend.data.value ?? []).map(s => s.snapshot_date?.slice(5) ?? '')
)

const trendSeries = computed<TrendSeries[]>(() => {
  const rows = trend.data.value ?? []
  if (!rows.length) return []
  return [
    {
      name: '复盘完成率',
      data: rows.map(s => s.finished_tickets > 0 ? Math.round(s.postmortem_count / s.finished_tickets * 1000) / 10 : 0),
      suffix: '%',
    },
    {
      name: '告警压缩比',
      data: rows.map(s => s.alert_sourced_tickets > 0 ? Math.round(s.alerts_total / s.alert_sourced_tickets * 10) / 10 : 0),
      suffix: ':1',
    },
    {
      name: '知识命中率',
      data: rows.map(s => s.knowledge_evidence > 0 ? Math.round(s.knowledge_hits / s.knowledge_evidence * 1000) / 10 : 0),
      suffix: '%',
    },
    // 快照是累计口径，当日新增 = 相邻两天的差——处置效能与告警进来多少放一张图上
    {
      name: '当日新增告警',
      type: 'bar',
      useRightAxis: true,
      data: rows.map((s, i) => i === 0 ? s.alerts_total : Math.max(0, s.alerts_total - rows[i - 1].alerts_total)),
    },
    {
      name: '当日新增工单',
      type: 'bar',
      useRightAxis: true,
      data: rows.map((s, i) => i === 0 ? s.total_tickets : Math.max(0, s.total_tickets - rows[i - 1].total_tickets)),
    },
  ]
})
</script>

<template>
  <div class="effectiveness">
    <main class="main-container">
      <div class="page-header">
        <div>
          <h1 class="page-title">效能大盘</h1>
          <p class="page-subtitle">处置体系的运转状况：压缩、时效、飞轮与自愈</p>
        </div>
        <div class="header-actions">
          <!-- 窗口作用于压缩比与诊断分布；MTTA/MTTR/复盘率仍是全时段口径，卡片上有标注 -->
          <el-select v-model="windowDays" class="window-select" size="small" title="时间窗作用于：告警压缩比、诊断分布">
            <el-option :value="null" label="全时段" />
            <el-option :value="7" label="近 7 天" />
            <el-option :value="30" label="近 30 天" />
          </el-select>
          <button class="refresh-btn" :disabled="loading" @click="refresh">
            <RefreshCw :size="15" :class="{ 'is-loading': loading }" />
            刷新
          </button>
        </div>
      </div>

      <PageLoading v-if="loading" tip="加载效能数据中..." />

      <template v-else>
        <!-- 管道心跳：告警与日志两条自监控管道的供电指示灯；静默时给出排查入口 -->
        <div class="pipeline-card" :class="`pipeline-${pipeline.state}`">
          <Activity :size="16" />
          <span class="pipeline-label">告警管道</span>
          <span class="pipeline-state">{{ pipeline.text }}</span>
          <span class="pipeline-note">{{ pipeline.note }}</span>
          <RouterLink v-if="pipeline.state === 'silent'" class="pipeline-handbook" to="/knowledge?q=%E7%9B%91%E6%8E%A7%E7%AE%A1%E9%81%93">查看排查手册</RouterLink>
        </div>
        <div class="pipeline-card" :class="`pipeline-${logsPipeline.state}`">
          <Activity :size="16" />
          <span class="pipeline-label">日志管道</span>
          <span class="pipeline-state">{{ logsPipeline.text }}</span>
          <span class="pipeline-note">{{ logsPipeline.note }}</span>
          <RouterLink v-if="logsPipeline.state === 'silent'" class="pipeline-handbook" to="/knowledge?q=%E7%9B%91%E6%8E%A7%E7%AE%A1%E9%81%93">查看排查手册</RouterLink>
        </div>

        <!-- KPI 卡 -->
        <div class="kpi-grid">
          <div
            v-for="k in kpis" :key="k.key" class="kpi-card"
            :class="{ 'kpi-card--action': k.action }"
            :role="k.action ? 'button' : undefined"
            :tabindex="k.action ? 0 : undefined"
            @click="k.action && onKpiClick(k.key)"
            @keydown.enter="k.action && onKpiClick(k.key)"
          >
            <div class="kpi-icon" :class="`kpi-${k.tone}`">
              <component :is="k.icon" :size="18" />
            </div>
            <div class="kpi-body">
              <div class="kpi-value">{{ k.value }}</div>
              <div class="kpi-label">{{ k.label }}</div>
              <div class="kpi-hint">{{ k.hint }}</div>
              <div
                v-if="targetOf(k.key)"
                class="kpi-target"
                :class="{ 'target-met': targetOf(k.key)!.met === true, 'target-missed': targetOf(k.key)!.met === false }"
              >
                {{ targetOf(k.key)!.text }}<template v-if="targetOf(k.key)!.met === true"> · 达标</template><template v-else-if="targetOf(k.key)!.met === false"> · 未达标</template>
              </div>
            </div>
          </div>
        </div>

        <!-- 工单闭环阶段（承接自数据概览）：首响→止损→根因确认→验证 的漏斗水位 -->
        <section v-if="stageProgress.length" class="panel" style="margin-bottom: 16px;">
          <h2 class="panel-title">工单闭环阶段</h2>
          <p class="panel-sub">全时段 {{ closureTotal }} 单——哪一格水位低，流程断点就在哪一格</p>
          <div class="stage-progress">
            <div v-for="s in stageProgress" :key="s.label" class="stage-row" :title="`${s.label}: ${s.count}/${closureTotal}`">
              <span class="stage-label">{{ s.label }}</span>
              <div class="stage-bar"><div class="stage-fill" :style="{ width: s.pct + '%' }" /></div>
              <span class="stage-num">{{ s.count }} / {{ closureTotal }}（{{ s.pct }}%）</span>
            </div>
          </div>
        </section>

        <!-- 自愈观察窗（FR-3.1）：观察级告警的降噪成效——自愈率、观察中、转单数 -->
        <ObservationStatsPanel />

        <div class="grid-2">
          <!-- 证据方向健康度：诊断的输入管路 -->
          <section class="panel">
            <h2 class="panel-title">证据方向健康度</h2>
            <p class="panel-sub">诊断四路取数各自的成功率——NO_DATA 计入分母，断源一眼可见</p>
            <div v-for="d in directions" :key="d.type" class="dir-row">
              <span class="dir-name">{{ d.type }}</span>
              <div class="dir-bar">
                <div class="dir-fill" :style="{ width: `${Math.round(d.successRate * 100)}%` }" />
              </div>
              <span class="dir-num">{{ Math.round(d.successRate * 100) }}%（{{ d.success }}/{{ d.total }}）</span>
            </div>
            <p v-if="!directions.length" class="panel-empty">暂无诊断数据</p>
          </section>

          <!-- 诊断充分性分布 -->
          <section class="panel">
            <h2 class="panel-title">诊断充分性分布（{{ windowDays == null ? '近 90 天' : `近 ${windowDays} 天` }}）</h2>
            <p class="panel-sub">SUFFICIENT 才是「证据够、可推理」的会话</p>
            <div v-for="s in sufficiencyRows" :key="s.sufficiency" class="dir-row">
              <span class="dir-name">{{ s.sufficiency }}</span>
              <span class="dir-num">{{ s.count }} 次</span>
            </div>
            <p v-if="!sufficiencyRows.length" class="panel-empty">暂无诊断数据</p>
          </section>
        </div>

        <!-- 诊断会话与 AI 效果（承接自数据概览瘦身迁出的诊断区）：
             读数口径与看板窗口联动（上方窗口选择器） -->
        <section v-if="sessions" class="panel" style="margin-bottom: 16px;">
          <h2 class="panel-title">诊断会话与 AI 效果</h2>
          <p class="panel-sub">诊断量、耗时与成本，加反馈闭环的两个读数：根因准确率与假设置信度校准（ECE 越低越好）</p>
          <div class="mini-kpi-grid">
            <div class="mini-kpi">
              <div class="mini-kpi-value">{{ sessions.total }}</div>
              <div class="mini-kpi-label">诊断会话</div>
            </div>
            <div class="mini-kpi">
              <div class="mini-kpi-value">{{ sessionCount('COMPLETED') }}</div>
              <div class="mini-kpi-label">完成</div>
            </div>
            <div class="mini-kpi">
              <div class="mini-kpi-value">{{ sessions.avgDurationSeconds == null ? '—' : sessions.avgDurationSeconds + 's' }}</div>
              <div class="mini-kpi-label">平均耗时</div>
            </div>
            <div class="mini-kpi">
              <div class="mini-kpi-value">{{ sessions.avgCostRmb == null ? '—' : '¥' + sessions.avgCostRmb.toFixed(4) }}</div>
              <div class="mini-kpi-label">单次均价</div>
            </div>
            <div class="mini-kpi">
              <div class="mini-kpi-value" :title="aiAccuracyTitle">{{ aiAccuracyText }}</div>
              <div class="mini-kpi-label">根因准确率</div>
            </div>
            <div class="mini-kpi">
              <div class="mini-kpi-value">{{ aiRatedText }}</div>
              <div class="mini-kpi-label">反馈 有用/已评分</div>
            </div>
            <div class="mini-kpi">
              <div class="mini-kpi-value" :title="calibEceTitle">{{ calibEceText }}</div>
              <div class="mini-kpi-label">校准误差 ECE</div>
            </div>
            <div class="mini-kpi">
              <div class="mini-kpi-value">{{ calibAccText }}</div>
              <div class="mini-kpi-label">经验正确率</div>
            </div>
          </div>
          <TrendChart
            v-if="diagnosisTrendSeries.length"
            :labels="sessionTrendDays"
            :series="diagnosisTrendSeries"
            height="220px"
          />
          <p v-else class="panel-empty">窗口内暂无诊断会话趋势</p>
        </section>

        <!-- 效能趋势：治理指标的水位变化（快照口径，读历史不重算） -->
        <section class="panel" style="margin-bottom: 16px;">
          <h2 class="panel-title">效能趋势（近 30 天）</h2>
          <p class="panel-sub">左轴是效能水位（复盘率/压缩比/命中率），右轴是当日新增告警与工单——处置效能与告警压力一张图上看</p>
          <p v-if="!trendSeries.length" class="panel-empty">快照自今日开始累积，明天起出现趋势线</p>
          <TrendChart
            v-else
            :labels="trendLabels"
            :series="trendSeries"
            height="280px"
            :target-line="{ value: 70, label: '复盘率目标 70%' }"
          />
        </section>

        <!-- 知识健康度：反馈在收，这里的面板是它的治理出口 -->
        <section class="panel">
          <h2 class="panel-title">知识健康度</h2>
          <p class="panel-sub">点踩多于点赞的文档是负资产——复核后修订或下架，别让它们继续污染检索</p>
          <div v-for="d in docsNeedReview" :key="d.docId" class="dir-row">
            <RouterLink :to="`/knowledge/${d.docId}`" class="doc-link">{{ d.title }}</RouterLink>
            <span class="dir-num">👍 {{ d.helpful }} · 👎 {{ d.wrong }}</span>
            <button class="doc-deprecate-btn" @click="deprecateDoc(d)">下架</button>
          </div>
          <p v-if="!docsNeedReview.length" class="panel-empty">
            {{ (docHealth.data.value?.length ?? 0) === 0 ? '还没有反馈数据' : '没有需要复核的文档' }}
          </p>
        </section>
      </template>
    </main>

    <!-- 复盘欠账清单：复盘完成率卡的行动出口 -->
    <el-dialog v-model="missingPostmortemOpen" title="已完结但未复盘的工单" width="560px">
      <p class="dialog-sub">复盘是知识飞轮的入口——这些单子结了案但没留下任何东西</p>
      <div v-if="missingPostmortem.isLoading.value" class="dialog-empty">加载中…</div>
      <p v-else-if="!missingPostmortem.data.value?.length" class="dialog-empty">没有欠账的完结单</p>
      <div v-else class="missing-list">
        <RouterLink
          v-for="t in missingPostmortem.data.value" :key="t.id"
          :to="`/tickets/${t.id}`" class="missing-item"
          @click="missingPostmortemOpen = false"
        >
          <span class="missing-id">{{ t.id }}</span>
          <span class="missing-title">{{ t.title }}</span>
          <span class="missing-status">{{ t.status }}</span>
        </RouterLink>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.effectiveness { min-height: 100vh; background: var(--surface-0); }
.main-container { max-width: 1200px; margin: 0 auto; padding: 20px; }

.page-header {
  display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px;
}
.page-title { font-size: var(--text-xl, 1.25rem); font-weight: var(--weight-bold); color: var(--text-1); }
.page-subtitle { font-size: var(--text-sm); color: var(--text-2); margin-top: 4px; }
.refresh-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 14px; border: 1px solid var(--border-2); border-radius: var(--radius);
  background: var(--surface-1); color: var(--text-2); cursor: pointer; font-size: var(--text-sm);
  &:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
}
.is-loading { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.header-actions { display: flex; align-items: center; gap: 10px; }
.window-select { width: 110px; }

.kpi-card--action { cursor: pointer; transition: border-color .15s ease, box-shadow .15s ease; }
.kpi-card--action:hover { border-color: var(--brand); }
.kpi-card--action:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }

.dialog-sub { font-size: var(--text-xs); color: var(--text-3); margin: 0 0 12px; }
.dialog-empty { color: var(--text-3); font-size: var(--text-sm); padding: 12px 0; }
.missing-list { display: flex; flex-direction: column; gap: 6px; max-height: 50vh; overflow-y: auto; }
.missing-item {
  display: flex; align-items: center; gap: 10px; padding: 9px 12px;
  border: 1px solid var(--border-1); border-radius: var(--radius);
  text-decoration: none; color: var(--text-1); font-size: var(--text-sm);
  &:hover { border-color: var(--brand); }
}
.missing-id { font-family: monospace; color: var(--brand); flex-shrink: 0; }
.missing-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.missing-status { font-size: var(--text-xs); color: var(--text-3); flex-shrink: 0; }

.doc-link { color: var(--text-1); text-decoration: none; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.doc-link:hover { color: var(--brand); }

.doc-deprecate-btn {
  flex-shrink: 0;
  padding: 2px 10px;
  font-size: var(--text-xs);
  border: 1px solid var(--warning, #b45309);
  color: var(--warning, #b45309);
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
  &:hover { background: var(--warning-subtle, #fffbeb); }
}

.pipeline-card {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 16px; border-radius: var(--radius); margin-bottom: 16px;
  border: 1px solid var(--border-2);
  &.pipeline-ok { background: var(--success-subtle, #f0fdf4); color: var(--success, #15803d); }
  &.pipeline-silent { background: var(--danger-subtle, #fef2f2); color: var(--danger, #b91c1c); }
  &.pipeline-unknown { background: var(--surface-1); color: var(--text-3); }
}
.pipeline-label { font-weight: var(--weight-semibold); }
.pipeline-state { font-weight: var(--weight-bold); }
.pipeline-note { font-size: var(--text-xs); opacity: 0.85; flex: 1; }
.pipeline-handbook {
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  color: inherit;
  text-decoration: underline;
  flex-shrink: 0;
  white-space: nowrap;
}

.kpi-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 16px;
  @media (max-width: 900px) { grid-template-columns: repeat(2, 1fr); }
}
.kpi-card {
  background: var(--surface-1); border: 1px solid var(--border-2); border-radius: var(--radius-lg);
  padding: 16px; display: flex; gap: 12px; align-items: flex-start;
}
.kpi-icon {
  width: 36px; height: 36px; border-radius: var(--radius);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
  &.kpi-info { background: var(--info-subtle, #eff6ff); color: var(--info, #2563eb); }
  &.kpi-success { background: var(--success-subtle, #f0fdf4); color: var(--success, #15803d); }
  &.kpi-warning { background: var(--warning-subtle, #fffbeb); color: var(--warning, #b45309); }
}
.kpi-value { font-size: var(--text-xl, 1.3rem); font-weight: var(--weight-bold); color: var(--text-1); }
.kpi-label { font-size: var(--text-sm); color: var(--text-2); margin-top: 2px; }
.kpi-hint { font-size: var(--text-xs); color: var(--text-3); margin-top: 2px; }
.kpi-target { font-size: var(--text-xs); margin-top: 4px; color: var(--text-3); }
.kpi-target.target-met { color: var(--success, #15803d); }
.kpi-target.target-missed { color: var(--warning, #b45309); }

.grid-2 {
  display: grid; grid-template-columns: 1fr 1fr; gap: 14px;
  @media (max-width: 900px) { grid-template-columns: 1fr; }
}
.panel {
  background: var(--surface-1); border: 1px solid var(--border-2);
  border-radius: var(--radius-lg); padding: 16px;
}
.panel-title { font-size: var(--text-base, 1rem); font-weight: var(--weight-semibold); color: var(--text-1); }
.panel-sub { font-size: var(--text-xs); color: var(--text-3); margin: 4px 0 12px; }
.panel-empty { color: var(--text-3); font-size: var(--text-sm); padding: 12px 0; }

.dir-row {
  display: flex; align-items: center; gap: 10px; padding: 7px 0;
  border-bottom: 1px solid var(--border-1);
  &:last-child { border-bottom: none; }
}
.dir-name { width: 90px; font-size: var(--text-sm); color: var(--text-2); flex-shrink: 0; }
.dir-bar { flex: 1; height: 8px; background: var(--surface-0); border-radius: 4px; overflow: hidden; }
.dir-fill { height: 100%; background: var(--brand); border-radius: 4px; }
.dir-num { font-size: var(--text-xs); color: var(--text-3); flex-shrink: 0; }

/* ── 工单闭环阶段（承接自数据概览） ── */
.stage-progress { display: flex; flex-direction: column; gap: 10px; }
.stage-row { display: flex; align-items: center; gap: 12px; }
.stage-label { width: 80px; font-size: var(--text-sm); color: var(--text-2); flex-shrink: 0; }
.stage-bar { flex: 1; height: 8px; background: var(--surface-0); border-radius: 4px; overflow: hidden; }
.stage-fill { height: 100%; background: var(--brand); border-radius: 4px; transition: width 0.3s ease; }
.stage-num { font-size: var(--text-xs); color: var(--text-3); white-space: nowrap; font-variant-numeric: tabular-nums; }

/* ── 诊断会话与 AI 效果：迷你 KPI 网格（承接自数据概览诊断区） ── */
.mini-kpi-grid {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 14px;
  @media (max-width: 900px) { grid-template-columns: repeat(2, 1fr); }
}
.mini-kpi {
  background: var(--surface-0); border-radius: var(--radius); padding: 12px 16px;
}
.mini-kpi-value {
  font-size: 20px; font-weight: var(--weight-bold);
  color: var(--text-1); font-variant-numeric: tabular-nums;
}
.mini-kpi-label { font-size: var(--text-xs); color: var(--text-3); margin-top: 4px; }

</style>
