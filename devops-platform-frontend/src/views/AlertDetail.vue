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
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Bell, CheckCircle, AlertTriangle, Clock, Loader2,
  RefreshCw, Hash, Server, Boxes, Radio, Ticket
} from 'lucide-vue-next'
import { useAlertDetailQuery, useAlertMutations } from '@/api/queries/alerts.query'
import { levelTagType, statusTagType, getAlertStatusLabel } from '@/utils/alert'
import { formatAbsolute } from '@/utils/time'
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
  const start = new Date(startRaw).getTime()
  if (Number.isNaN(start)) return '—'
  const endRaw = a.resolvedAt
  const end = endRaw ? new Date(endRaw).getTime() : Date.now()
  if (Number.isNaN(end)) return '—'
  const mins = Math.max(0, Math.round((end - start) / 60000))
  if (mins < 60) return `${mins} 分钟`
  const h = Math.floor(mins / 60)
  const m = mins % 60
  if (h < 24) return m ? `${h} 小时 ${m} 分钟` : `${h} 小时`
  const d = Math.floor(h / 24)
  const rh = h % 24
  return rh ? `${d} 天 ${rh} 小时` : `${d} 天`
})

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
              <p v-else class="desc-empty">
                未关联工单（自动建单可能被关闭，或该告警级别未触发建单）
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
  background: var(--color-bg);
}

.main-container {
  max-width: 1280px;
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
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 32px;
}

.state-icon-warn { color: var(--color-warning, var(--warning)); }

.state-title {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.state-text {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.spin { animation: spin 1s linear infinite; }

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ===== 头部卡 ===== */
.header-card {
  background: var(--color-surface);
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
  color: var(--color-text-primary);
  word-break: break-word;
}

.title-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  flex-wrap: wrap;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.meta-sep { color: var(--color-text-tertiary); }

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
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;

  &:disabled { opacity: 0.55; cursor: not-allowed; }
}

.btn-outline {
  border: 1px solid var(--color-border-light);
  background: var(--color-surface);
  color: var(--color-text-primary);

  &:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
}

.btn-primary {
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: var(--color-text-inverse, #fff);

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
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 18px 20px;
}

.card-title {
  margin: 0 0 12px 0;
  font-size: var(--text-base);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.desc-body {
  margin: 0;
  font-size: var(--text-sm);
  line-height: 1.65;
  color: var(--color-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.desc-empty {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
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
    background: var(--color-border-light);
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
  border: 2px solid var(--color-border-light);
  background: var(--color-surface);
  color: transparent;
  z-index: 1;

  .tl-node.done & {
    border-color: var(--state-success, var(--success));
    background: var(--state-success, var(--success));
    color: #fff;
  }
}

.tl-body { min-width: 0; }

.tl-label {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);

  .tl-node.pending & { color: var(--color-text-tertiary); font-weight: var(--weight-normal); }
}

.tl-pending {
  margin-left: 6px;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

.tl-time {
  margin-top: 2px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  font-family: var(--font-mono, monospace);
}

.tl-hint {
  margin-top: 2px;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
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
    color: var(--color-text-tertiary);
    flex-shrink: 0;
  }

  dd {
    margin: 0;
    color: var(--color-text-primary);
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
  background: var(--color-bg-sunken, var(--surface-2));
  border-radius: var(--radius-sm);
  word-break: break-all;
}

.val-warn {
  color: var(--color-warning, var(--warning));
  font-weight: var(--weight-semibold);
}

/* ===== 关联工单 ===== */
.ticket-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
  font-family: var(--font-mono, monospace);
  color: var(--color-primary);
  text-decoration: none;

  &:hover { text-decoration: underline; }
}
</style>
