<script setup lang="ts">
/**
 * SLA 风险面板（B1 端点落地）
 *
 * 呈现「哪些工单即将或已经超时」+ 首响统计（MTTA）。
 *
 * 建立动因：`GET /tickets/sla/at-risk` 与 `/sla/first-response-stats`
 * 自 6.44 起后端就绪、前端封装齐全，但**零 UI 消费**——运维看不到
 * 即将超时的工单，B1 的核心价值（分级响应）未兑现。属「端点就绪、
 * 前端漏接」家族（同 6.49 的止损端点）。
 *
 * 三态严格区分（6.18）：加载中 / 加载失败可重试 / 确实无风险工单。
 * 「确实无风险」是好消息，须与「加载失败」明确区分——后者显示空列表
 * 会让运维误以为一切正常。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { RefreshCw, ShieldAlert, Timer } from 'lucide-vue-next'

import { fetchFirstResponseStats, fetchSlaAtRisk } from '@/api/tickets'
import type { FrontendTicket } from '@/api/types/ticket'
import {
  firstResponseDurationText,
  firstResponseTagType,
  firstResponseText,
  firstResponseTitle,
  slaRemainText,
  slaSeverity,
} from '@/utils/sla'
import { getPriorityLabel } from '@/stores/tickets'
import ApiErrorState from '@/components/common/ApiErrorState.vue'
import AppEmpty from '@/components/common/AppEmpty.vue'

/** 前瞻窗口选项：0 = 只看已超时 */
const WINDOW_OPTIONS = [
  { value: 0, label: '仅已超时' },
  { value: 30, label: '30 分钟内' },
  { value: 120, label: '2 小时内' },
  { value: 480, label: '8 小时内' },
]

const windowMinutes = ref(30)
const tickets = ref<FrontendTicket[]>([])
const total = ref(0)
const loading = ref(true)
const loadError = ref<unknown>(null)

interface FirstResponseStats {
  responded: number
  notResponded: number
  breached: number
  avgFirstResponseMinutes: number | null
}
const stats = ref<FirstResponseStats | null>(null)

/** 请求序号防竞态：快速切换窗口时先发起的请求若后到达会覆盖新结果 */
let requestSequence = 0

const load = async () => {
  const seq = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    // 两个端点并行：首响统计失败不应让风险清单也看不到
    const [risk, statsData] = await Promise.all([
      fetchSlaAtRisk(windowMinutes.value, 20),
      fetchFirstResponseStats().catch(e => {
        console.warn('[SlaRiskPanel] 首响统计加载失败，仅隐藏该区块', e)
        return null
      }),
    ])
    if (seq !== requestSequence) return
    tickets.value = risk.tickets
    total.value = risk.total
    stats.value = statsData
  } catch (e) {
    if (seq !== requestSequence) return
    loadError.value = e
  } finally {
    if (seq === requestSequence) loading.value = false
  }
}

const switchWindow = (value: number) => {
  if (value === windowMinutes.value) return
  windowMinutes.value = value
  void load()
}

/** 已超时的条数：面板标题用它做强提醒 */
const breachedCount = computed(() => tickets.value.filter(t => t.slaBreached).length)

/**
 * MTTA 展示。
 *
 * avgFirstResponseMinutes 为 null 表示尚无任何首响记录——
 * 显示占位符而非 0，否则会被读成「平均秒级响应」（6.44 口径契约）。
 */
const mttaText = computed(() => firstResponseDurationText(stats.value?.avgFirstResponseMinutes))

onMounted(() => void load())
</script>

<template>
  <div class="data-panel sla-risk-panel">
    <div class="panel-header">
      <h3>
        <ShieldAlert :size="16" class="header-icon" />
        SLA 风险清单
        <span v-if="breachedCount > 0" class="breached-badge">{{ breachedCount }} 已超时</span>
      </h3>
      <div class="header-actions">
        <div class="window-tabs">
          <button
            v-for="opt in WINDOW_OPTIONS"
            :key="opt.value"
            class="window-tab"
            :class="{ active: windowMinutes === opt.value }"
            type="button"
            @click="switchWindow(opt.value)"
          >{{ opt.label }}</button>
        </div>
        <button class="btn-refresh" type="button" :disabled="loading" @click="load">
          <RefreshCw :size="14" :class="{ spinning: loading }" />
        </button>
      </div>
    </div>

    <div class="panel-body">
      <!-- 首响统计（MTTA）：加载失败时整块隐藏，不阻塞风险清单 -->
      <div v-if="stats" class="fr-stats">
        <div class="fr-stat">
          <span class="fr-stat-label"><Timer :size="12" /> MTTA</span>
          <span class="fr-stat-value">{{ mttaText }}</span>
        </div>
        <div class="fr-stat">
          <span class="fr-stat-label">已首响</span>
          <span class="fr-stat-value">{{ stats.responded }}</span>
        </div>
        <div class="fr-stat">
          <span class="fr-stat-label">待首响</span>
          <span class="fr-stat-value">{{ stats.notResponded }}</span>
        </div>
        <div class="fr-stat" :class="{ 'fr-stat--alert': stats.breached > 0 }">
          <span class="fr-stat-label">首响超时</span>
          <span class="fr-stat-value">{{ stats.breached }}</span>
        </div>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="risk-skeleton">
        <div v-for="n in 3" :key="n" class="skeleton-row" />
      </div>

      <!-- 加载失败：数据可能仍在，给重试 -->
      <ApiErrorState
        v-else-if="loadError"
        :error="loadError"
        compact
        retry-label="重试"
        @retry="load"
      />

      <!-- 确实无风险工单：与「加载失败」区分——这是好消息 -->
      <AppEmpty
        v-else-if="tickets.length === 0"
        size="sm"
        :description="windowMinutes === 0 ? '暂无已超时工单' : `未来 ${windowMinutes} 分钟内无工单将超时`"
      />

      <ul v-else class="risk-list">
        <li v-for="t in tickets" :key="t.id" class="risk-item" :class="`risk-item--${slaSeverity(t)}`">
          <div class="risk-main">
            <RouterLink :to="`/tickets/${t.id}`" class="risk-title" :title="t.title">
              {{ t.title }}
            </RouterLink>
            <div class="risk-meta">
              <span class="risk-id">{{ t.id }}</span>
              <span class="risk-sep">·</span>
              <span>{{ getPriorityLabel(t.priority) }}</span>
              <span class="risk-sep">·</span>
              <span>{{ t.assignee }}</span>
            </div>
          </div>
          <div class="risk-side">
            <span class="risk-sla" :class="`risk-sla--${slaSeverity(t)}`">
              {{ slaRemainText(t) }}
            </span>
            <el-tag
              :type="firstResponseTagType(t.firstResponseState)"
              size="small"
              effect="light"
              :title="firstResponseTitle(t)"
            >{{ firstResponseText(t) }}</el-tag>
          </div>
        </li>
      </ul>

      <!-- 清单被上限截断时如实告知，不让用户以为这就是全部（6.24 no silent caps）。
           跳转按 priority 排序：slaProgress 不在后端 SORTABLE_COLUMNS 白名单内，
           传它会被静默降级为默认排序，等于给出一个不生效的链接 -->
      <p v-if="!loading && !loadError && total > tickets.length" class="risk-truncated">
        共 {{ total }} 条，仅显示前 {{ tickets.length }} 条 ——
        <RouterLink to="/tickets?sortBy=priority&sortAsc=true">按优先级查看全部</RouterLink>
      </p>
    </div>
  </div>
</template>

<style scoped lang="scss">
.panel-header h3 {
  display: flex;
  align-items: center;
  gap: 6px;
}

.header-icon {
  color: var(--state-warning);
}

.breached-badge {
  padding: 1px 7px;
  border-radius: 9px;
  background: var(--state-error);
  color: #fff;
  font-size: 11px;
  font-weight: var(--weight-semibold);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.window-tabs {
  display: flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--radius-sm);
  background: var(--color-bg);
}

.window-tab {
  padding: 3px 8px;
  border: none;
  border-radius: calc(var(--radius-sm) - 2px);
  background: transparent;
  color: var(--color-text-tertiary);
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover { color: var(--color-text-secondary); }

  &.active {
    background: var(--color-surface);
    color: var(--color-primary);
    font-weight: var(--weight-medium);
  }
}

.btn-refresh {
  display: inline-flex;
  align-items: center;
  padding: 4px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-tertiary);
  cursor: pointer;

  &:disabled { cursor: not-allowed; opacity: 0.6; }
  &:hover:not(:disabled) { color: var(--color-primary); border-color: var(--color-primary); }
}

.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ---------- 首响统计 ---------- */
.fr-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--color-border-light, var(--color-border));
}

.fr-stat {
  flex: 1 1 auto;
  min-width: 84px;
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  background: var(--color-bg);
}

.fr-stat--alert {
  background: var(--color-danger-lighter, rgba(239, 68, 68, 0.08));
}

.fr-stat-label {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.fr-stat-value {
  display: block;
  margin-top: 2px;
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

/* ---------- 风险清单 ---------- */
.risk-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.risk-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  border-left: 3px solid transparent;
  background: var(--color-bg);
}

.risk-item--breached { border-left-color: var(--state-error); }
.risk-item--warning { border-left-color: var(--state-warning); }
.risk-item--normal { border-left-color: var(--color-border); }

.risk-main {
  min-width: 0;
  flex: 1;
}

.risk-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);
  text-decoration: none;

  &:hover { color: var(--color-primary); }
}

.risk-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.risk-id { font-family: var(--font-mono, monospace); }
.risk-sep { margin: 0 4px; }

.risk-side {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.risk-sla {
  font-size: 11px;
  font-weight: var(--weight-medium);
  white-space: nowrap;
}

.risk-sla--breached { color: var(--state-error); }
.risk-sla--warning { color: var(--state-warning); }
.risk-sla--normal { color: var(--color-text-secondary); }

.risk-truncated {
  margin: 10px 0 0;
  font-size: 11px;
  color: var(--color-text-tertiary);
  text-align: center;

  a { color: var(--color-primary); }
}

/* ---------- 骨架屏 ---------- */
.risk-skeleton {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.skeleton-row {
  height: 46px;
  border-radius: var(--radius-sm);
  background: linear-gradient(90deg, var(--color-bg) 25%, var(--color-border) 50%, var(--color-bg) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s ease-in-out infinite;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

@media (max-width: 640px) {
  .risk-item {
    flex-direction: column;
    align-items: flex-start;
  }
  .risk-side { width: 100%; justify-content: space-between; }
}
</style>
