<script setup lang="ts">
/**
 * AlertList — L2 告警列表页（Stage 3）
 *
 * 服务端分页 + 状态/级别筛选 + 关联工单 + 人工确认/标记恢复。
 *
 * 与 AlertStreamMode（实时告警流，WebSocket 推送）互补：
 * - 本页是权威列表：服务端分页/筛选，数据来自 REST `GET /api/v1/alerts`。
 * - AlertStreamMode 是秒级实时事件流，两者数据源独立。
 *
 * 排序说明：后端 `AlertQueryService.listAlerts` 固定按 `last_occurred_at DESC`
 * 排序（最新告警在前），REST 列表接口不暴露 sortBy 参数——故本页不做
 * 「可点击排序」列（客户端排序只会排当前页，是 6.37 已明确的静默错误）。
 */
import { notify } from '@/utils/notify'
import { ref, computed, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Bell, AlertTriangle, CheckCircle, RefreshCw, X } from 'lucide-vue-next'
import type { Alert, AlertStatus } from '@/api/types'
import { useAlertListQuery, useAlertMutations } from '@/api/queries/alerts.query'
import {
  levelTagType,
  statusTagType,
  getAlertStatusLabel,
  ALERT_STATUS_OPTIONS,
  ALERT_LEVEL_OPTIONS
} from '@/utils/alert'
import RelativeTime from '@/components/common/RelativeTime.vue'
import ServerPagination from '@/components/common/ServerPagination.vue'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import { useServerPaginationFrom } from '@/composables/useServerPagination'
import {
  useUrlFilters,
  defineUrlFilter,
  enumParser,
  positiveIntParser,
  textParser,
} from '@/composables/useUrlFilters'

// ==================== 状态 ====================

const statusFilter = ref<AlertStatus | ''>('')
const levelFilter = ref<string | ''>('')

/**
 * 分页 + 数据拉取由 TanStack Query 驱动。
 *
 * 相比此前的手写 fetchList：筛选与页码进 queryKey，**参数变化自动重拉**，
 * 不需要在每个筛选控件的 change 里手动调 fetchList（漏一个就出现
 * 「改了筛选但列表没变」）；写操作后走 invalidateQueries 声明式失效，
 * 不需要记住「这个操作该刷新哪几处」（6.17 缺陷根源）。
 *
 * total/totalPages 来自 Query 的响应，故用 useServerPaginationFrom
 * 从中派生——分页状态自己再存一份必然与响应漂移。
 */
const pageRef = ref(1)
const sizeRef = ref(10)

/**
 * 筛选与页码同步到 URL。
 *
 * 告警页此前完全没有 URL 状态：值班同事筛出「FIRING + P0」后
 * 没法把结果甩给同事，刷新也会丢——而「把这个告警列表发给你看」
 * 恰恰是值班交接时最高频的动作。
 */
useUrlFilters([
  defineUrlFilter({
    ref: statusFilter,
    key: 'status',
    defaultValue: '' as AlertStatus | '',
    parse: enumParser(['FIRING', 'ACKNOWLEDGED', 'RESOLVED'] as const),
  }),
  defineUrlFilter({
    ref: levelFilter,
    key: 'level',
    defaultValue: '' as string,
    parse: textParser(32),
  }),
  defineUrlFilter({
    ref: pageRef, key: 'page', defaultValue: 1, parse: positiveIntParser(10000),
  }),
  defineUrlFilter({
    ref: sizeRef, key: 'size', defaultValue: 10, parse: positiveIntParser(200),
  }),
])

const listQuery = useAlertListQuery({
  page: pageRef,
  size: sizeRef,
  status: statusFilter,
  level: levelFilter,
})

const pagination = useServerPaginationFrom(
  { total: () => listQuery.total.value, totalPages: () => listQuery.totalPages.value },
  { pageSize: 10, page: pageRef, size: sizeRef }
)
/**
 * 页码与每页条数**只有一份 ref**（`pageRef` / `sizeRef`），
 * 由本页持有并同时交给 Query 与分页 composable。
 *
 * 此前这里是「composable 内部自己 ref(1)，再用 watch 单向同步到 pageRef」，
 * 注释写着「是同一个 ref」但实际是两个。两个方向都出过问题：
 *
 * 1. **URL 恢复失效**。`useUrlFilters` 在 setup 阶段把 `?page=3` 写进
 *    `pageRef`，而分页条读的 `pagination.currentPage` 仍是 1——
 *    列表显示第 3 页的数据、底部却高亮「1」。
 *
 * 2. **改筛选不回第 1 页**。`resetPage()` 把 `currentPage` 设为 1，
 *    但它本来就是 1，watch 不触发，`pageRef` 仍停在 3——
 *    换了筛选条件，请求却还在拉第 3 页。
 *
 * 共用一份 ref 之后，`useUrlFilters` 写它、`goToPage`/`resetPage` 写它、
 * Query 的 queryKey 读它，三方天然一致，不再需要任何同步 watch。
 */
const { currentPage, total, totalPages, pageNumbers, pageStart, pageEnd } = pagination

const alerts = listQuery.alerts
const listLoading = listQuery.isLoading
const listError = listQuery.error

/** 处置动作（确认/恢复）。成功后自动失效告警领域全部查询 */
const { acknowledge: ackMutation, resolve: resolveMutation } = useAlertMutations()

/** 当前行正在执行确认/恢复的告警 id（防重复点击 + 行内 loading） */
const actionLoadingId = ref<number | null>(null)

const hasFilters = computed(() => statusFilter.value !== '' || levelFilter.value !== '')

/** 触发中的活跃告警数（当前筛选下由后端 total 提供，非全库口径） */
const firingCount = computed(
  () => alerts.value.filter(a => a.status === 'FIRING').length
)

// ==================== 数据操作 ====================

/** 手动刷新：Query 的 refetch 会绕过 staleTime 强制重拉 */
const fetchList = () => listQuery.refetch()

/**
 * 末页删空后退一页（6.17）。
 *
 * Query 拉取完成后检查：当前页无数据但总数不为 0，说明本页被删空了。
 * 用 watch 而非塞进 queryFn——queryFn 应保持纯粹的数据获取。
 */
watch(
  () => [alerts.value.length, total.value] as const,
  ([count]) => {
    if (listQuery.isFetching.value) return
    pagination.retreatIfEmptied(count)
  }
)

// ==================== 分页 ====================
// 页码序列与区间计算见 useServerPagination（与 TicketList 共用同一实现）
// 页码变化经上方 watch 同步到 Query 的 queryKey，自动触发重拉

const goToPage = (p: number) => {
  pagination.goToPage(p)
}

const resetPageOnFilterChange = () => {
  // 筛选变化等于换了数据视图，回第 1 页——否则用户停在
  // 「按新条件本不该存在的第 5 页」。
  // 筛选本身已在 queryKey 中，变化即自动重拉，此处只需复位页码
  pagination.resetPage()
}

const clearFilters = () => {
  statusFilter.value = ''
  levelFilter.value = ''
  pagination.resetPage()
}

// ==================== 处置动作 ====================
// 成功后的列表刷新由 mutation 的 onSuccess → invalidateQueries 完成，
// 不再手动 await fetchList()——那是 6.17「写操作后忘记重拉」的成因

/**
 * 人工确认告警（FIRING → ACKNOWLEDGED，幂等）
 */
const acknowledge = async (row: Alert) => {
  actionLoadingId.value = row.id
  try {
    await ackMutation.mutateAsync(row.id)
    notify.success('已确认告警')
  } catch {
    // 错误提示已由 mutation 的 onError 统一处理
  } finally {
    actionLoadingId.value = null
  }
}

/**
 * 标记告警已恢复（非终态 → RESOLVED，幂等）
 *
 * 标记恢复是较重的人工处置动作，先弹确认框防止误点。
 * 已 RESOLVED 的行按钮置灰（后端也会幂等拒绝，前端先挡住避免无谓请求）。
 */
const resolve = async (row: Alert) => {
  try {
    await ElMessageBox.confirm(
      `确定将「${row.title || row.alertName || '该告警'}」标记为已恢复吗？`,
      '标记恢复',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  actionLoadingId.value = row.id
  try {
    await resolveMutation.mutateAsync(row.id)
    notify.success('已标记恢复')
  } catch {
    // 错误提示已由 mutation 的 onError 统一处理
  } finally {
    actionLoadingId.value = null
  }
}

// 首次加载由 Query 自动触发（挂载即拉取），无需 onMounted
</script>

<template>
  <div class="alert-list">
    <main class="main-container">
      <!-- Page Header -->
      <div class="page-header-card">
        <div class="page-header">
          <div>
            <AppBreadcrumb :items="[{ label: '告警事件' }]" class="page-breadcrumb" />
            <h1 class="page-title">告警事件</h1>
            <p class="page-subtitle">查看、确认与处置 Prometheus 告警，追溯关联工单</p>
          </div>
          <div class="page-actions">
            <button class="btn-refresh" type="button" :disabled="listLoading" @click="fetchList">
              <RefreshCw :size="16" :class="{ spinning: listLoading }" />
              刷新
            </button>
          </div>
        </div>
        <div class="page-summary">
          <div class="summary-item">
            <span class="summary-dot summary-dot--firing" />
            <span class="summary-text">当前页触发中</span>
            <span class="summary-value">{{ firingCount }}</span>
          </div>
          <div class="summary-item">
            <AlertTriangle :size="14" class="summary-icon" />
            <span class="summary-text">共告警</span>
            <span class="summary-value">{{ total }}</span>
          </div>
        </div>
      </div>

      <!-- Filter Bar -->
      <div class="filter-bar">
        <div class="filter-selects">
          <el-select
            v-model="statusFilter"
            class="filter-select"
            placeholder="全部状态"
            clearable
            @change="resetPageOnFilterChange"
          >
            <el-option
              v-for="opt in ALERT_STATUS_OPTIONS"
              :key="opt.value || '__all_status'"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-select
            v-model="levelFilter"
            class="filter-select"
            placeholder="全部级别"
            clearable
            @change="resetPageOnFilterChange"
          >
            <el-option
              v-for="opt in ALERT_LEVEL_OPTIONS"
              :key="opt.value || '__all_level'"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <button v-if="hasFilters" class="btn-clear-filters" type="button" @click="clearFilters">
            <X :size="14" />
            清除
          </button>
        </div>
      </div>

      <!-- 加载 / 错误 / 空态 / 内容四态统一（与 TicketList 共用同一实现） -->
      <DataStateBoundary
        :loading="listLoading"
        :error="listError"
        :count="alerts.length"
        :filtered="hasFilters"
        empty-description="暂无告警，系统运行正常"
        filtered-description="筛选无命中，试试调整条件"
        :skeleton-rows="6"
        @retry="fetchList"
      >
      <!-- 列表 -->
      <div class="table-container">
        <el-table class="alerts-table" :data="alerts" border stripe row-key="id">
          <!-- 级别 -->
          <el-table-column label="级别" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="levelTagType(row.level)" size="small" effect="dark">
                {{ row.level || '—' }}
              </el-tag>
            </template>
          </el-table-column>

          <!-- 标题：主内容列，弹性吸收剩余空间 -->
          <el-table-column label="告警标题" min-width="240">
            <template #default="{ row }">
              <el-tooltip placement="top-start" :show-after="250" effect="light" :disabled="!row.description">
                <template #content>
                  <div class="alert-peek">
                    <div class="peek-title">{{ row.title || row.alertName || '告警' }}</div>
                    <div v-if="row.description" class="peek-desc">{{ row.description }}</div>
                    <div class="peek-row">
                      <span class="peek-label">来源 · 服务 · 模块</span>
                      <span class="peek-value">
                        {{ row.source || '—' }} · {{ row.service || '—' }} · {{ row.module || '—' }}
                      </span>
                    </div>
                    <div class="peek-row">
                      <span class="peek-label">首次发生</span>
                      <span class="peek-value">{{ row.firstOccurredAt || '—' }}</span>
                    </div>
                    <div class="peek-row">
                      <span class="peek-label">去重键</span>
                      <span class="peek-value peek-mono">{{ row.dedupKey || '—' }}</span>
                    </div>
                  </div>
                </template>
                <div class="alert-title-cell">
                  <RouterLink :to="`/alerts/${row.id}`" class="alert-title-link" @click.stop>
                    <span class="alert-title">{{ row.title || row.alertName || '—' }}</span>
                  </RouterLink>
                </div>
              </el-tooltip>
            </template>
          </el-table-column>

          <!-- 服务 -->
          <el-table-column label="服务" width="130" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="cell-muted">{{ row.service || '—' }}</span>
            </template>
          </el-table-column>

          <!-- 模块 -->
          <el-table-column label="模块" width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="cell-muted">{{ row.module || '—' }}</span>
            </template>
          </el-table-column>

          <!-- 状态 -->
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" effect="light">
                {{ getAlertStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>

          <!-- 次数 -->
          <el-table-column label="次数" width="70" align="center">
            <template #default="{ row }">
              <span :class="{ 'occurrence-cell': (row.occurrenceCount ?? 0) > 1 }">
                {{ row.occurrenceCount ?? 1 }}
              </span>
            </template>
          </el-table-column>

          <!-- 最近发生 -->
          <el-table-column label="最近发生" width="120">
            <template #default="{ row }">
              <div class="timestamp"><RelativeTime :value="row.lastOccurredAt" /></div>
            </template>
          </el-table-column>

          <!-- 关联工单 -->
          <el-table-column label="关联工单" width="150">
            <template #default="{ row }">
              <RouterLink v-if="row.ticketId" :to="`/tickets/${row.ticketId}`" class="ticket-link" @click.stop>
                {{ row.ticketId }}
              </RouterLink>
              <span v-else class="cell-muted">—</span>
            </template>
          </el-table-column>

          <!-- 操作 -->
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <div class="actions" @click.stop>
                <button
                  class="action-btn action-btn-primary"
                  :disabled="row.status === 'ACKNOWLEDGED' || row.status === 'RESOLVED' || actionLoadingId === row.id"
                  @click="acknowledge(row)"
                >
                  <CheckCircle :size="14" />
                  {{ row.status === 'ACKNOWLEDGED' ? '已确认' : '确认' }}
                </button>
                <button
                  class="action-btn action-btn-success"
                  :disabled="row.status === 'RESOLVED' || actionLoadingId === row.id"
                  @click="resolve(row)"
                >
                  <Bell :size="14" />
                  {{ row.status === 'RESOLVED' ? '已恢复' : '标记恢复' }}
                </button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      </DataStateBoundary>

      <!-- Pagination -->
      <ServerPagination
        v-if="alerts.length > 0 || totalPages > 1"
        :current-page="currentPage"
        :total-pages="totalPages"
        :total="total"
        :page-start="pageStart"
        :page-end="pageEnd"
        :page-numbers="pageNumbers"
        margin-top="16px"
        @page-change="goToPage"
      />
    </main>
  </div>
</template>

<style scoped lang="scss">
.alert-list {
  min-height: 100vh;
  background: var(--color-bg);
}

.main-container {
  max-width: 1440px;
  margin: 0 auto;
  padding: 24px;
}

/* ===== Page Header ===== */
.page-header-card {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: 24px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.page-title {
  font-size: var(--text-2xl);
  font-weight: var(--weight-bold);
  color: var(--color-text-primary);
  margin: 0 0 4px 0;
}

.page-subtitle {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  margin: 0;
}

.page-actions {
  display: flex;
  gap: 8px;
}

.btn-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
  &:disabled { opacity: 0.55; cursor: not-allowed; }

  .spinning { animation: spin 1s linear infinite; }
}

.page-summary {
  display: flex;
  gap: 24px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border-light);
}

.summary-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
}

.summary-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;

  &--firing { background: var(--state-error, var(--danger)); }
}

.summary-icon { color: var(--color-text-tertiary); }
.summary-text { color: var(--color-text-secondary); }
.summary-value { font-weight: var(--weight-semibold); color: var(--color-text-primary); }

/* ===== Filter Bar ===== */
.filter-bar {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: 16px 20px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.filter-selects {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.filter-select {
  width: 160px;
}

.btn-clear-filters {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border: none;
  background: transparent;
  font-size: var(--text-sm);
  font-family: var(--font-body);
  color: var(--color-text-secondary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: all 0.15s ease;

  &:hover { color: var(--state-error); background: rgba(220, 38, 38, 0.06); }
}

/* 骨架与空态已收敛到 SkeletonRows / DataStateBoundary */
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ===== 表格 ===== */
.table-container {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: 8px;
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.alert-title-cell {
  display: flex;
  align-items: center;
}

/* 标题为进入详情页的入口——无此链接则 /alerts/:id 无从抵达 */
.alert-title-link {
  text-decoration: none;
  color: inherit;
  min-width: 0;

  &:hover .alert-title { color: var(--color-primary); text-decoration: underline; }
}

.alert-title {
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-muted { color: var(--color-text-tertiary); }
.timestamp { color: var(--color-text-secondary); font-size: var(--text-xs); }

.occurrence-cell {
  color: var(--color-warning, var(--warning));
  font-weight: var(--weight-semibold);
}

.ticket-link {
  color: var(--color-primary);
  text-decoration: none;
  font-family: var(--font-mono, monospace);

  &:hover { text-decoration: underline; }
}

/* ===== 操作按钮 ===== */
.actions {
  display: flex;
  gap: 6px;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  font-family: var(--font-body);
  background: var(--color-surface);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;

  &:disabled { opacity: 0.5; cursor: not-allowed; }

  &-primary:hover:not(:disabled) {
    border-color: var(--color-primary);
    color: var(--color-primary);
    background: var(--color-primary-lighter);
  }

  &-success:hover:not(:disabled) {
    border-color: var(--state-success, var(--success));
    color: var(--state-success, var(--success));
    background: rgba(103, 194, 58, 0.08);
  }
}

/* ===== 悬浮速览卡 ===== */
.alert-peek {
  max-width: 360px;
  font-size: var(--text-xs);
}

.peek-title {
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  margin-bottom: 6px;
  word-break: break-word;
}

.peek-desc {
  color: var(--color-text-secondary);
  line-height: 1.5;
  margin-bottom: 8px;
  word-break: break-word;
  white-space: pre-wrap;
}

.peek-row {
  display: flex;
  gap: 8px;
  margin-bottom: 4px;
}

.peek-label {
  flex-shrink: 0;
  color: var(--color-text-tertiary);
}

.peek-value {
  color: var(--color-text-primary);
  word-break: break-all;
}

.peek-mono {
  font-family: var(--font-mono, monospace);
}

</style>
