<script setup lang="ts">
/**
 * AlertDetail — L2 告警详情页（方案 A / Stage 3 收官）
 *
 * 此前 `/alerts/:id` 指向 FutureCapability 占位页——列表页的告警行点不进去，
 * 用户无法查看单条告警的完整上下文（去重键、发生次数、处置时间线、关联工单）。
 *
 * 三态严格区分（6.18 契约）：
 * - 加载中     → spinner，不得让首帧闪现「未找到」
 * - 确实不存在 → 40004，API 层返回 null → 「告警不存在」+ 返回列表
 * - 加载失败   → 抛异常 → 「加载失败」+ **重试**（数据可能仍在，重试是正确的下一步）
 *
 * 处置时间线由已有字段派生（createTime/firstOccurredAt/lastOccurredAt/
 * acknowledgedAt/resolvedAt），不新增表——告警本就是单实体，无子表。
 */
import { notify } from '@/utils/notify'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useQuery } from '@tanstack/vue-query'
import {
  Bell, CheckCircle, AlertTriangle, Clock, Loader2,
  RefreshCw, Hash, Server, Boxes, Radio, Ticket, Network
} from 'lucide-vue-next'
import { useAlertDetailQuery, useAlertMutations } from '@/api/queries/alerts.query'
import { fetchRelatedAlerts } from '@/api/alerts'
import { fetchTicketById } from '@/api/tickets'
import { levelTagType, statusTagType, getAlertStatusLabel } from '@/utils/alert'
import { formatAbsolute, parseDate } from '@/utils/time'
import RelativeTime from '@/components/common/RelativeTime.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'

defineOptions({ name: 'AlertDetail' })

const route = useRoute()
const router = useRouter()

const alertId = computed(() => String(route.params.id ?? ''))

/**
 * 数据与三态由 TanStack Query 驱动。
 *
 * queryKey 含 alertId，切换 id 自动重拉且**自带竞态防护**——
 * Query 会丢弃陈旧请求的结果，不会出现「上一条告警的数据落到当前页」
 * （6.39 家族）。因此不再需要手写 watch + reset。
 *
 * 三态映射（6.18 契约：notFound 与 error 必须分开）：
 * - isLoading            → 加载中
 * - error                → 加载失败，数据可能仍在，给重试
 * - data === null        → 确实不存在（api 层对 40004 返回 null 而非抛错）
 */
const detailQuery = useAlertDetailQuery(alertId)
const { acknowledge: ackMutation, resolve: resolveMutation } = useAlertMutations()

/**
 * 同事件告警联动（建议3）：同 system + service ±10 分钟窗内。
 * 请求挂在详情数据就绪之后（enabled 依赖 alert 已载入），
 * 切换 id 走 key 变化自动重拉。结果仅是增强信息，失败降级空数组。
 */
const relatedQuery = useQuery({
  queryKey: computed(() => ['related-alerts', alertId.value]),
  queryFn: () => fetchRelatedAlerts(Number(alertId.value)),
  enabled: computed(() => !!alertId.value && detailQuery.data.value !== null),
  staleTime: 30_000,
})
const relatedAlerts = computed(() => relatedQuery.data.value ?? [])

const alert = detailQuery.data
const loading = detailQuery.isLoading
const loadError = detailQuery.error
const notFound = computed(() =>
  !detailQuery.isLoading.value && !detailQuery.error.value && detailQuery.data.value === null
)

/** 处置中：两个 mutation 任一进行中都算 */
const acting = computed(() => ackMutation.isPending.value || resolveMutation.isPending.value)

/** 手动刷新（加载失败时的重试入口） */
const loadDetail = () => detailQuery.refetch()

// ==================== 聚合抑制识别 ====================
// 告警的 ticketId 有两种来路：本告警建的单（ticket.sourceTraceId == 本告警 dedupKey），
// 或窗口内被并入同组告警的工单（sourceTraceId 属于组代表告警）。
// 两者界面上一字之差，排障时含义完全不同——并入的单子的处理进展不由本告警驱动。
const groupTicket = ref<{ sourceTraceId?: string | null } | null>(null)
watch(() => alert.value?.ticketId, async (tid) => {
  groupTicket.value = null
  if (!tid) return
  try {
    groupTicket.value = await fetchTicketById(tid)
  } catch {
    // 拉不到就只展示工单号——聚合标注是增强信息，拿不到不猜
  }
}, { immediate: true })

/** true = 本告警被并入组工单（聚合抑制），并非由它建单 */
const isAggregated = computed(() => {
  const cur = alert.value
  const t = groupTicket.value
  return !!(cur?.ticketId && t?.sourceTraceId && cur.dedupKey && t.sourceTraceId !== cur.dedupKey)
})

// ==================== 处置动作 ====================
// 处置成功后的数据刷新由 mutation 的 onSuccess → invalidateQueries 完成。
// 此前是把响应直接赋给 alert.value——Query 的 data 是缓存驱动的只读值，
// 且失效重拉能一并更新列表页的缓存，比只改当前页的局部状态更完整

const doAcknowledge = async () => {
  const cur = alert.value
  if (!cur || acting.value) return
  try {
    await ackMutation.mutateAsync(cur.id)
    notify.success('已确认告警')
  } catch {
    // 错误提示已由 mutation 的 onError 统一处理
  }
}

const doResolve = async () => {
  const cur = alert.value
  if (!cur || acting.value) return
  try {
    await ElMessageBox.confirm(
      `确定将「${cur.title || cur.alertName || '该告警'}」标记为已恢复吗？`,
      '标记恢复',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await resolveMutation.mutateAsync(cur.id)
    notify.success('已标记恢复')
  } catch {
    // 错误提示已由 mutation 的 onError 统一处理
  }
}

// ==================== 派生展示 ====================

/**
 * 处置时间线：由已有时间字段派生，只渲染真实发生过的节点
 *
 * 不给未发生的节点编造时间（如用 createTime 冒充 acknowledgedAt）——
 * 那会让 MTTA 类指标失真，也误导用户以为已确认（6.44 契约）。
 */
const timeline = computed(() => {
  const a = alert.value
  if (!a) return []
  const nodes: { key: string; label: string; at: string | null; state: 'done' | 'pending'; hint?: string }[] = []

  nodes.push({
    key: 'first',
    label: '首次触发',
    at: a.firstOccurredAt ?? a.createTime,
    state: 'done'
  })

  if ((a.occurrenceCount ?? 1) > 1) {
    nodes.push({
      key: 'repeat',
      label: `重复触发 ${a.occurrenceCount} 次`,
      at: a.lastOccurredAt,
      state: 'done',
      hint: '窗口内按去重键聚合，最近一次时间'
    })
  }

  nodes.push({
    key: 'ack',
    label: '人工确认',
    at: a.acknowledgedAt,
    state: a.acknowledgedAt ? 'done' : 'pending'
  })

  nodes.push({
    key: 'resolved',
    label: '已恢复',
    at: a.resolvedAt,
    state: a.resolvedAt ? 'done' : 'pending'
  })

  return nodes
})

/** 持续时长（分钟）：未恢复则算到当前，已恢复算到恢复时刻 */
const durationText = computed(() => {
  const a = alert.value
  if (!a) return '—'
  const startRaw = a.firstOccurredAt ?? a.createTime
  if (!startRaw) return '—'
  // 必须走 parseDate：后端 LocalDateTime 无时区后缀，
  // 直接 new Date 会按浏览器时区解析，与 Date.now() 混算后
  // 跨时区可差出十几小时（见 utils/time.ts 说明）
  const startDate = parseDate(startRaw)
  if (!startDate) return '—'
  const start = startDate.getTime()
  const endRaw = a.resolvedAt
  const endDate = endRaw ? parseDate(endRaw) : new Date()
  if (!endDate) return '—'
  const end = endDate.getTime()
  const mins = Math.max(0, Math.round((end - start) / 60000))
  if (mins < 60) return `${mins} 分钟`
  const h = Math.floor(mins / 60)
  const m = mins % 60
  if (h < 24) return m ? `${h} 小时 ${m} 分钟` : `${h} 小时`
  const d = Math.floor(h / 24)
  const rh = h % 24
  return rh ? `${d} 天 ${rh} 小时` : `${d} 天`
})

// ==================== 原始标签 / 注解（V11 落库） ====================

/** 防御性解析 labels/annotations JSON 串 → 键值对数组；非法/空 JSON 降级为空数组 */
const parseJsonEntries = (raw: string | null | undefined): { key: string; value: string }[] => {
  if (!raw) return []
  try {
    const obj = JSON.parse(raw)
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return []
    return Object.entries(obj).map(([key, value]) => ({ key, value: String(value) }))
  } catch {
    return []
  }
}

// 这些键已在上方「告警属性」卡或徽标里展示，不再重复——
// 「原始标签」只呈现属性区没有的下钻维度（instance/pod/namespace/job…）
const LABEL_KEYS_SHOWN_ELSEWHERE = new Set(['alertname', 'service', 'module', 'severity', 'system'])

/** 原始标签（过滤掉已在属性区展示的键），值班人看 instance/pod 即可定位实体，不用跳 Grafana */
const alertLabels = computed(() =>
  parseJsonEntries(alert.value?.labelsJson).filter(e => !LABEL_KEYS_SHOWN_ELSEWHERE.has(e.key))
)

/** 告警注解（summary/description/runbook_url/当前值/阈值），全量展示 */
const alertAnnotations = computed(() => parseJsonEntries(alert.value?.annotationsJson))

const canAcknowledge = computed(() => {
  const s = alert.value?.status
  return s !== 'ACKNOWLEDGED' && s !== 'RESOLVED'
})

const canResolve = computed(() => alert.value?.status !== 'RESOLVED')

const goList = () => router.push('/alerts')
</script>

<template>
  <div class="alert-detail">
    <main class="main-container">
      <!-- 面包屑（统一为首页起始的递进链路，分隔符与全站一致） -->
      <div class="breadcrumb">
        <AppBreadcrumb
          :items="[
            { label: '告警事件', to: '/alerts' },
            { label: alertId ? `告警 #${alertId}` : '详情' }
          ]"
        />
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="state-card">
        <Loader2 :size="22" class="spin" />
        <p class="state-text">正在加载告警详情…</p>
      </div>

      <!-- 加载失败：数据可能仍在，提供重试而非「返回列表」 -->
      <div v-else-if="loadError" class="state-card">
        <ApiErrorState :error="loadError" compact retry-label="重试" @retry="loadDetail" />
      </div>

      <!-- 确实不存在 -->
      <div v-else-if="notFound" class="state-card">
        <AlertTriangle :size="28" class="state-icon-warn" />
        <h3 class="state-title">告警不存在</h3>
        <p class="state-text">该告警可能已被清理，或链接有误。</p>
        <button class="btn-primary" type="button" @click="goList">返回告警列表</button>
      </div>

      <!-- 正常内容 -->
      <template v-else-if="alert">
        <!-- 头部卡 -->
        <div class="header-card">
          <div class="header-top">
            <div class="header-title-block">
              <div class="title-row">
                <el-tag :type="levelTagType(alert.level)" size="small" effect="dark">
                  {{ alert.level || '—' }}
                </el-tag>
                <h1 class="page-title">{{ alert.title || alert.alertName || '告警' }}</h1>
              </div>
              <div class="title-meta">
                <el-tag :type="statusTagType(alert.status)" size="small" effect="light">
                  {{ getAlertStatusLabel(alert.status) }}
                </el-tag>
                <span class="meta-sep">·</span>
                <span class="meta-item">
                  <Clock :size="13" />
                  持续 {{ durationText }}
                </span>
                <span class="meta-sep">·</span>
                <span class="meta-item">
                  最近 <RelativeTime :value="alert.lastOccurredAt" />
                </span>
              </div>
            </div>
            <div class="header-actions">
              <button class="btn-outline" type="button" :disabled="loading" @click="loadDetail">
                <RefreshCw :size="15" :class="{ spin: loading }" />
                刷新
              </button>
              <button
                class="btn-outline"
                type="button"
                :disabled="!canAcknowledge || acting"
                @click="doAcknowledge"
              >
                <CheckCircle :size="15" />
                {{ alert.status === 'ACKNOWLEDGED' ? '已确认' : '确认告警' }}
              </button>
              <button
                class="btn-primary"
                type="button"
                :disabled="!canResolve || acting"
                @click="doResolve"
              >
                <Bell :size="15" />
                {{ alert.status === 'RESOLVED' ? '已恢复' : '标记恢复' }}
              </button>
            </div>
          </div>
        </div>

        <div class="content-grid">
          <!-- 左栏 -->
          <div class="col-main">
            <!-- 告警详情 -->
            <section class="card">
              <h3 class="card-title">告警内容</h3>
              <p v-if="alert.description" class="desc-body">{{ alert.description }}</p>
              <p v-else class="desc-empty">该告警未携带详情描述</p>
            </section>

            <!-- 处置时间线 -->
            <section class="card">
              <h3 class="card-title">处置时间线</h3>
              <ul class="timeline">
                <li
                  v-for="node in timeline"
                  :key="node.key"
                  class="tl-node"
                  :class="node.state"
                >
                  <span class="tl-dot">
                    <CheckCircle v-if="node.state === 'done'" :size="12" />
                  </span>
                  <div class="tl-body">
                    <div class="tl-label">
                      {{ node.label }}
                      <span v-if="node.state === 'pending'" class="tl-pending">未发生</span>
                    </div>
                    <div v-if="node.at" class="tl-time" :title="formatAbsolute(node.at)">
                      {{ formatAbsolute(node.at) }}
                    </div>
                    <div v-if="node.hint" class="tl-hint">{{ node.hint }}</div>
                  </div>
                </li>
              </ul>
            </section>

            <!-- 同事件告警（建议3）：同 system + service ±10 分钟窗，一条故障波及的兄弟告警 -->
            <section class="card">
              <h3 class="card-title">
                <Network :size="15" />
                同事件告警
                <span v-if="relatedAlerts.length" class="card-count">{{ relatedAlerts.length }} 条</span>
              </h3>
              <p class="related-hint">
                与本告警同一来源系统、同一服务、首次发生时间 ±10 分钟窗内——一次故障反复触响的兄弟告警
              </p>
              <div v-if="relatedQuery.isLoading.value" class="related-empty">加载中…</div>
              <ul v-else-if="relatedAlerts.length" class="related-list">
                <li v-for="r in relatedAlerts" :key="r.id" class="related-item">
                  <RouterLink :to="`/alerts/${r.id}`" class="related-link">
                    <el-tag :type="levelTagType(r.level)" size="small" effect="dark">{{ r.level || '—' }}</el-tag>
                    <span class="related-name">{{ r.title || r.alertName || `告警 #${r.id}` }}</span>
                    <span v-if="r.observing" class="observing-badge">观察中</span>
                    <span class="related-time">
                      <RelativeTime :value="r.firstOccurredAt" />
                    </span>
                  </RouterLink>
                </li>
              </ul>
              <p v-else class="related-empty">窗口内没有同源同服务的其他告警</p>
            </section>
          </div>

          <!-- 右栏 -->
          <aside class="col-side">
            <!-- 属性 -->
            <section class="card">
              <h3 class="card-title">告警属性</h3>
              <dl class="prop-list">
                <div class="prop-row">
                  <dt><Radio :size="13" /> 来源</dt>
                  <dd>{{ alert.source || '—' }}</dd>
                </div>
                <div class="prop-row">
                  <dt><Hash :size="13" /> 规则名</dt>
                  <dd class="mono">{{ alert.alertName || '—' }}</dd>
                </div>
                <div class="prop-row">
                  <dt><Server :size="13" /> 服务</dt>
                  <dd>{{ alert.service || '—' }}</dd>
                </div>
                <div class="prop-row">
                  <dt><Boxes :size="13" /> 模块</dt>
                  <dd>{{ alert.module || '—' }}</dd>
                </div>
                <div class="prop-row">
                  <dt><AlertTriangle :size="13" /> 发生次数</dt>
                  <dd :class="{ 'val-warn': (alert.occurrenceCount ?? 1) > 1 }">
                    {{ alert.occurrenceCount ?? 1 }}
                  </dd>
                </div>
                <div class="prop-row prop-row--stack">
                  <dt><Hash :size="13" /> 去重键</dt>
                  <dd class="mono dedup">{{ alert.dedupKey || '—' }}</dd>
                </div>
              </dl>
            </section>

            <!-- 原始标签（V11：instance/pod/namespace/job 等下钻维度） -->
            <section v-if="alertLabels.length" class="card">
              <h3 class="card-title">原始标签</h3>
              <dl class="prop-list">
                <div v-for="e in alertLabels" :key="e.key" class="prop-row prop-row--stack">
                  <dt class="mono label-key">{{ e.key }}</dt>
                  <dd class="mono label-val">{{ e.value }}</dd>
                </div>
              </dl>
            </section>

            <!-- 告警注解（summary/description/runbook_url/当前值/阈值） -->
            <section v-if="alertAnnotations.length" class="card">
              <h3 class="card-title">告警注解</h3>
              <dl class="prop-list">
                <div v-for="e in alertAnnotations" :key="e.key" class="prop-row prop-row--stack">
                  <dt class="mono label-key">{{ e.key }}</dt>
                  <dd class="annotation-val">{{ e.value }}</dd>
                </div>
              </dl>
            </section>

            <!-- 关联工单 -->
            <section class="card">
              <h3 class="card-title">关联工单</h3>
              <RouterLink
                v-if="alert.ticketId"
                :to="`/tickets/${alert.ticketId}`"
                class="ticket-link"
              >
                <Ticket :size="15" />
                {{ alert.ticketId }}
              </RouterLink>
              <p v-if="alert.ticketId && isAggregated" class="ticket-aggregate-note">
                聚合抑制：本告警并入同组工单，未单独建单（窗口内同服务已有建单告警）
              </p>
              <p v-else-if="!alert.ticketId" class="desc-empty">
                未关联工单（自动建单可能被关闭，或该告警级别低于建单门槛）
              </p>
            </section>
          </aside>
        </div>
      </template>
    </main>
  </div>
</template>

<style scoped lang="scss">
.alert-detail {
  min-height: 100vh;
  background: var(--surface-0);
}

.main-container {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px 24px 32px;
}

/* ===== 面包屑 ===== */
/* 面包屑容器（内部样式已随 AppBreadcrumb 公共组件收敛） */
.breadcrumb {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}

/* ===== 三态卡 ===== */
.state-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 260px;
  background: var(--surface-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 32px;
}

.state-icon-warn { color: var(--warning, var(--warning)); }

.state-title {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-1);
}

.state-text {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-2);
}

.spin { animation: spin 1s linear infinite; }

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ===== 头部卡 ===== */
.header-card {
  background: var(--surface-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 20px 24px;
  margin-bottom: 16px;
}

.header-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.header-title-block { min-width: 0; flex: 1; }

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.page-title {
  margin: 0;
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--text-1);
  word-break: break-word;
}

.title-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: var(--text-sm);
  color: var(--text-2);
  flex-wrap: wrap;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.meta-sep { color: var(--text-3); }

.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.btn-outline,
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: var(--radius);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;

  &:disabled { opacity: 0.55; cursor: not-allowed; }
}

.btn-outline {
  border: 1px solid var(--border-1);
  background: var(--surface-1);
  color: var(--text-1);

  &:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
}

.btn-primary {
  border: 1px solid var(--brand);
  background: var(--brand);
  color: var(--text-inverse, #fff);

  &:hover:not(:disabled) { filter: brightness(1.06); }
}

/* ===== 双栏 ===== */
.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
}

@media (max-width: 1024px) {
  .content-grid { grid-template-columns: minmax(0, 1fr); }
}

.col-main,
.col-side {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.card {
  background: var(--surface-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 18px 20px;
}

.card-title {
  margin: 0 0 12px 0;
  font-size: var(--text-base);
  font-weight: var(--weight-semibold);
  color: var(--text-1);
  display: flex;
  align-items: center;
  gap: 7px;

  svg { color: var(--text-3); }
}

.card-count {
  margin-left: auto;
  font-size: var(--text-xs);
  font-weight: var(--weight-normal);
  color: var(--text-3);
  background: var(--surface-2, var(--surface-2));
  padding: 1px 8px;
  border-radius: 999px;
}

/* ===== 同事件告警 ===== */
.related-hint {
  margin: -4px 0 12px;
  font-size: var(--text-xs);
  color: var(--text-3);
  line-height: 1.5;
}

.related-empty {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-3);
}

.related-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.related-item { border-bottom: 1px solid var(--border-1); }
.related-item:last-child { border-bottom: none; }

.related-link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  text-decoration: none;
  font-size: var(--text-sm);
  color: var(--text-1);
  border-radius: var(--radius-sm);
  transition: background 0.15s ease;

  &:hover { background: var(--surface-hover); }
}

.related-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.related-time {
  font-size: var(--text-xs);
  color: var(--text-3);
  flex-shrink: 0;
}

.observing-badge {
  flex-shrink: 0;
  font-size: var(--text-xs);
  color: var(--warning, #b45309);
  background: var(--warning-subtle, #fffbeb);
  padding: 1px 7px;
  border-radius: 999px;
}

.desc-body {
  margin: 0;
  font-size: var(--text-sm);
  line-height: 1.65;
  color: var(--text-1);
  white-space: pre-wrap;
  word-break: break-word;
}

.desc-empty {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-3);
}

/* ===== 时间线 ===== */
.timeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.tl-node {
  display: flex;
  gap: 12px;
  padding-bottom: 16px;
  position: relative;

  &:not(:last-child)::before {
    content: '';
    position: absolute;
    left: 9px;
    top: 20px;
    bottom: 0;
    width: 2px;
    background: var(--border-1);
  }

  &:last-child { padding-bottom: 0; }
}

.tl-dot {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 2px solid var(--border-1);
  background: var(--surface-1);
  color: transparent;
  z-index: 1;

  .tl-node.done & {
    border-color: var(--success, var(--success));
    background: var(--success, var(--success));
    color: #fff;
  }
}

.tl-body { min-width: 0; }

.tl-label {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--text-1);

  .tl-node.pending & { color: var(--text-3); font-weight: var(--weight-normal); }
}

.tl-pending {
  margin-left: 6px;
  font-size: var(--text-xs);
  color: var(--text-3);
}

.tl-time {
  margin-top: 2px;
  font-size: var(--text-xs);
  color: var(--text-2);
  font-family: var(--font-mono, monospace);
}

.tl-hint {
  margin-top: 2px;
  font-size: var(--text-xs);
  color: var(--text-3);
}

/* ===== 属性列表 ===== */
.prop-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.prop-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  font-size: var(--text-sm);

  dt {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    color: var(--text-3);
    flex-shrink: 0;
  }

  dd {
    margin: 0;
    color: var(--text-1);
    text-align: right;
    word-break: break-word;
    min-width: 0;
  }

  &--stack {
    flex-direction: column;
    align-items: stretch;

    dd { text-align: left; }
  }
}

.mono { font-family: var(--font-mono, monospace); font-size: var(--text-xs); }

.dedup {
  margin-top: 4px !important;
  padding: 6px 8px;
  background: var(--surface-2, var(--surface-2));
  border-radius: var(--radius-sm);
  word-break: break-all;
}

/* 原始标签/注解（V11）：键名等宽弱色，值承载长内容可断行 */
.label-key {
  font-size: var(--text-xs);
  letter-spacing: 0.02em;
}

.label-val {
  margin-top: 3px;
  padding: 5px 8px;
  background: var(--surface-2, var(--surface-2));
  border-radius: var(--radius-sm);
  word-break: break-all;
  font-size: var(--text-xs);
}

/* 注解值常是长文本（description/runbook_url/阈值说明），不用等宽、正常换行 */
.annotation-val {
  margin-top: 3px;
  color: var(--text-2);
  line-height: var(--leading-normal);
  word-break: break-word;
  white-space: pre-wrap;
}

.val-warn {
  color: var(--warning, var(--warning));
  font-weight: var(--weight-semibold);
}

/* ===== 关联工单 ===== */
.ticket-aggregate-note {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.5;
}
.ticket-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
  font-family: var(--font-mono, monospace);
  color: var(--brand);
  text-decoration: none;

  &:hover { text-decoration: underline; }
}
</style>
