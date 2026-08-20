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
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bell, AlertTriangle, CheckCircle, RefreshCw, ChevronLeft, ChevronRight, X } from 'lucide-vue-next'
import { fetchAlerts, acknowledgeAlert, resolveAlert } from '@/api/alerts'
import type { Alert, AlertStatus, AlertsResponse } from '@/api/types'
import {
  levelTagType,
  statusTagType,
  getAlertStatusLabel,
  ALERT_STATUS_OPTIONS,
  ALERT_LEVEL_OPTIONS
} from '@/utils/alert'
import { toFriendlyError } from '@/utils/http'
import RelativeTime from '@/components/common/RelativeTime.vue'
import AppEmpty from '@/components/common/AppEmpty.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'

// ==================== 状态 ====================

const alerts = ref<Alert[]>([])
const total = ref(0)
const totalPages = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)

const statusFilter = ref<AlertStatus | ''>('')
const levelFilter = ref<string | ''>('')

const listLoading = ref(false)
const listError = ref<unknown>(null)

/** 当前行正在执行确认/恢复的告警 id（防重复点击 + 行内 loading） */
const actionLoadingId = ref<number | null>(null)

const hasFilters = computed(() => statusFilter.value !== '' || levelFilter.value !== '')

/** 触发中的活跃告警数（当前筛选下由后端 total 提供，非全库口径） */
const firingCount = computed(
  () => alerts.value.filter(a => a.status === 'FIRING').length
)

// ==================== 数据拉取 ====================

const fetchList = async () => {
  const asParam = (v: string) => (v === '' ? undefined : v)
  listLoading.value = true
  listError.value = null
  try {
    const data: AlertsResponse = await fetchAlerts({
      page: currentPage.value,
      size: pageSize.value,
      status: asParam(statusFilter.value),
      level: asParam(levelFilter.value)
    })
    alerts.value = data.alerts
    total.value = data.total
    totalPages.value = data.totalPages
    // 末页删空后退一页，避免停留在空白页
    if (alerts.value.length === 0 && currentPage.value > 1 && data.total > 0) {
      currentPage.value = Math.max(1, data.totalPages)
      await fetchList()
    }
  } catch (e) {
    console.error('加载告警列表失败:', e)
    listError.value = e
  } finally {
    listLoading.value = false
  }
}

// ==================== 分页 ====================

const pageNumbers = computed(() => {
  const totalP = Math.max(1, totalPages.value)
  const cur = currentPage.value
  const pages: (number | 'ellipsis')[] = []
  if (totalP <= 5) {
    for (let i = 1; i <= totalP; i++) pages.push(i)
    return pages
  }
  pages.push(1)
  if (cur > 3) pages.push('ellipsis')
  const start = Math.max(2, cur - 1)
  const end = Math.min(totalP - 1, cur + 1)
  for (let i = start; i <= end; i++) pages.push(i)
  if (cur < totalP - 2) pages.push('ellipsis')
  pages.push(totalP)
  return pages
})

const pageStart = computed(() => (currentPage.value - 1) * pageSize.value + 1)
const pageEnd = computed(() => Math.min(currentPage.value * pageSize.value, total.value))

const goToPage = async (p: number) => {
  if (p < 1 || p > totalPages.value || p === currentPage.value) return
  currentPage.value = p
  await fetchList()
}

const resetPageOnFilterChange = async () => {
  currentPage.value = 1
  await fetchList()
}

const clearFilters = () => {
  statusFilter.value = ''
  levelFilter.value = ''
  currentPage.value = 1
  void fetchList()
}

// ==================== 处置动作 ====================

/**
 * 人工确认告警（FIRING → ACKNOWLEDGED，幂等）
 */
const acknowledge = async (row: Alert) => {
  actionLoadingId.value = row.id
  try {
    await acknowledgeAlert(row.id)
    ElMessage.success('已确认告警')
    await fetchList()
  } catch (e) {
    ElMessage.error(toFriendlyError(e).detail)
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
    await resolveAlert(row.id)
    ElMessage.success('已标记恢复')
    await fetchList()
  } catch (e) {
    ElMessage.error(toFriendlyError(e).detail)
  } finally {
    actionLoadingId.value = null
  }
}

onMounted(() => {
  void fetchList()
})
</script>

<template>
  <div class="alert-list">
    <main class="main-container">
      <!-- Page Header -->
      <div class="page-header-card">
        <div class="page-header">
          <div>
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

      <!-- 加载 / 错误 / 空态 -->
      <div v-if="listLoading && alerts.length === 0" class="alert-skeleton-wrap">
        <div v-for="n in 6" :key="'skel-' + n" class="skeleton-bar" />
      </div>
      <div v-else-if="listError && alerts.length === 0" class="alert-state-wrap">
        <ApiErrorState :error="listError" compact retry-label="重试" @retry="fetchList" />
      </div>
      <div v-else-if="alerts.length === 0" class="alert-state-wrap">
        <AppEmpty
          :kind="hasFilters ? 'search' : 'default'"
          size="sm"
          :description="hasFilters ? '筛选无命中，试试调整条件' : '暂无告警，系统运行正常'"
        />
      </div>

      <!-- 列表 -->
      <div v-else class="table-container">
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

      <!-- Pagination -->
      <div v-if="alerts.length > 0 || totalPages > 1" class="pagination">
        <div class="pagination-info">
          显示 {{ pageStart }}-{{ pageEnd }} 共 {{ total }} 条
        </div>
        <div class="pagination-controls">
          <button
            class="pagination-btn-chevron"
            :disabled="currentPage === 1"
            @click="goToPage(currentPage - 1)"
          >
            <ChevronLeft :size="16" />
          </button>
          <div class="pagination-pages">
            <template v-for="(p, idx) in pageNumbers">
              <span v-if="p === 'ellipsis'" :key="`e-${idx}`" class="pagination-ellipsis">...</span>
              <button
                v-else
                :key="`p-${idx}`"
                class="pagination-page"
                :class="{ active: currentPage === p }"
                @click="goToPage(p)"
              >{{ p }}</button>
            </template>
          </div>
          <button
            class="pagination-btn-chevron"
            :disabled="currentPage >= totalPages"
            @click="goToPage(currentPage + 1)"
          >
            <ChevronRight :size="16" />
          </button>
        </div>
      </div>
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

  &--firing { background: var(--state-error, #f56c6c); }
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

/* ===== 加载骨架 / 空态 / 错误 ===== */
.alert-skeleton-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: 24px;
  box-shadow: var(--shadow-sm);
}

.skeleton-bar {
  height: 20px;
  border-radius: 6px;
  background: linear-gradient(90deg, var(--color-bg-sunken, #f1f5f9) 25%, #e2e8f0 37%, var(--color-bg-sunken, #f1f5f9) 63%);
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
}

@keyframes shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.alert-state-wrap {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
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
  color: var(--color-warning, #e6a23c);
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
    border-color: var(--state-success, #67c23a);
    color: var(--state-success, #67c23a);
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

/* ===== 分页 ===== */
.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
  flex-wrap: wrap;
  gap: 8px;
}

.pagination-info {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.pagination-controls {
  display: flex;
  align-items: center;
  gap: 6px;
}

.pagination-btn-chevron {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
  &:disabled { opacity: 0.4; cursor: not-allowed; }
}

.pagination-pages {
  display: flex;
  align-items: center;
  gap: 4px;
}

.pagination-page {
  min-width: 32px;
  height: 32px;
  padding: 0 6px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover { border-color: var(--color-primary); color: var(--color-primary); }

  &.active {
    background: var(--color-primary);
    border-color: var(--color-primary);
    color: var(--color-text-inverse);
  }
}

.pagination-ellipsis {
  color: var(--color-text-tertiary);
  padding: 0 2px;
}
</style>
