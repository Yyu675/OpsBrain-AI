<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ClipboardList, Filter, AlertCircle, CheckCircle2,
  ArrowRight, Loader2, CalendarClock
} from 'lucide-vue-next'
import { listActionItems, updateActionItem, type ActionItemData } from '@/api/tickets'
import { useTicketsStore } from '@/stores/tickets'
import { handleServerError } from '@/utils/notify'

defineOptions({ name: 'ActionItemBoard' })

const router = useRouter()
const store = useTicketsStore()

// ==================== 筛选 ====================

const filters = ref({ status: '', owner: '', overdue: false })

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '待开始' },
  { value: 'DOING', label: '进行中' },
  { value: 'DONE', label: '已完成' },
  { value: 'DROPPED', label: '已放弃' }
]

const STATUS_LABELS: Record<string, string> = {
  OPEN: '待开始',
  DOING: '进行中',
  DONE: '已完成',
  DROPPED: '已放弃'
}

// ==================== 数据 ====================

const items = ref<ActionItemData[]>([])
const loading = ref(false)
const loadError = ref<unknown>(null)

const loadItems = async () => {
  loading.value = true
  loadError.value = null
  try {
    items.value = await listActionItems({
      status: filters.value.status || undefined,
      owner: filters.value.owner.trim() || undefined,
      overdue: filters.value.overdue
    })
  } catch (e) {
    console.error('[改进项看板] 加载失败', e)
    loadError.value = e
  } finally {
    loading.value = false
  }
}

/** 责任人选项：后端真实名录 + 当前改进项中已出现但不在名录的负责人（防选不回） */
const ownerOptions = computed(() => {
  const names = new Set<string>(store.assignees)
  items.value.forEach(i => {
    if (i.owner && !names.has(i.owner)) names.add(i.owner)
  })
  return [...names].sort()
})

// ==================== 派生展示 ====================

/** 今日字符串 yyyy-MM-dd，用于逾期判断 */
const today = new Date()

const isOverdue = (item: ActionItemData): boolean =>
  !!item.dueDate && item.dueDate < todayStr() && item.status !== 'DONE' && item.status !== 'DROPPED'

function todayStr(): string {
  const d = today
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}

function fmtDueDate(due: string | null | undefined): string {
  if (!due) return ''
  const d = new Date(due)
  if (Number.isNaN(d.getTime())) return due
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}

const counts = computed(() => {
  const c = { total: items.value.length, overdue: 0 }
  items.value.forEach(i => { if (isOverdue(i)) c.overdue++ })
  return c
})

// ==================== 操作 ====================

const doUpdateStatus = async (item: ActionItemData, status: string) => {
  try {
    const updated = await updateActionItem(item.id!, status)
    items.value = items.value.map(i => (i.id === item.id ? updated : i))
    ElMessage.success(`已更新为「${STATUS_LABELS[status] || status}」`)
  } catch (e) {
    handleServerError(e, { action: '更新改进项' })
  }
}

const goTicket = (ticketId: string | undefined) => {
  if (ticketId) router.push(`/tickets/${ticketId}`)
}

onMounted(() => {
  loadItems()
  // A2 契约：选人名单必须来自后端名录。看板独立成页，若不做此调用，
  // 从导航直达时 assignees 为空，责任人筛选下拉就只有「待分配」。
  void store.loadTeamMembers()
})
</script>

<template>
  <div class="action-board">
    <main class="main-container">
      <div class="board-header">
        <div class="board-title">
          <ClipboardList :size="20" />
          <h2>改进项看板</h2>
          <span class="sub-tip">复盘产出的改进项，逾期未完成会标红</span>
        </div>
      </div>

      <!-- 统计条 -->
      <div class="stat-bar">
        <span class="stat-item">共 {{ counts.total }} 项</span>
        <span class="stat-item stat-overdue" :class="{ 'has-overdue': counts.overdue > 0 }">
          <AlertCircle :size="14" />
          逾期 {{ counts.overdue }} 项
        </span>
      </div>

      <!-- 筛选区 -->
      <div class="filter-bar">
        <Filter :size="14" class="filter-icon" />
        <select v-model="filters.status" class="filter-input" @change="loadItems">
          <option v-for="s in STATUS_OPTIONS" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
        <input
          v-model="filters.owner"
          type="text"
          class="filter-input owner-input"
          list="action-owners"
          placeholder="责任人"
          @change="loadItems"
        />
        <datalist id="action-owners">
          <option v-for="n in ownerOptions" :key="n" :value="n" />
        </datalist>
        <label class="overdue-toggle" title="只看已逾期且未完成">
          <input type="checkbox" v-model="filters.overdue" @change="loadItems" />
          <span>只看逾期</span>
        </label>
        <button class="btn-outline" @click="loadItems">
          <Loader2 :size="14" :class="{ 'is-loading': loading }" />
          查询
        </button>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="board-state">
        <Loader2 :size="24" class="spin" />
        <p>加载改进项中...</p>
      </div>

      <!-- 加载失败 -->
      <div v-else-if="loadError" class="board-state">
        <AlertCircle :size="24" class="err-icon" />
        <p>加载失败</p>
        <button class="btn-outline" @click="loadItems">重试</button>
      </div>

      <!-- 空态 -->
      <div v-else-if="!items.length" class="board-state">
        <CheckCircle2 :size="24" class="empty-icon" />
        <p>当前条件下暂无改进项</p>
      </div>

      <!-- 列表 -->
      <div v-else class="item-list">
        <div
          v-for="item in items"
          :key="item.id"
          class="item-card"
          :class="{ 'is-overdue': isOverdue(item) }"
        >
          <div class="item-main">
            <span class="item-status" :class="`st-${(item.status || 'OPEN').toLowerCase()}`">
              {{ STATUS_LABELS[item.status || 'OPEN'] || item.status }}
            </span>
            <p class="item-content" :title="item.content">{{ item.content }}</p>
          </div>
          <div class="item-meta">
            <span v-if="item.owner" class="meta-chip">@{{ item.owner }}</span>
            <span v-if="item.dueDate" class="due-chip" :class="{ 'due-overdue': isOverdue(item) }">
              <CalendarClock :size="12" />
              {{ fmtDueDate(item.dueDate) }}
              <span v-if="isOverdue(item)" class="due-flag">已逾期</span>
            </span>
            <span v-if="item.ticketId" class="meta-chip ticket-chip" @click="goTicket(item.ticketId)">
              工单 {{ item.ticketId }}
              <ArrowRight :size="12" />
            </span>
          </div>
          <div class="item-actions">
            <select :value="item.status" class="status-select" @change="doUpdateStatus(item, ($event.target as HTMLSelectElement).value)">
              <option v-for="s in STATUS_OPTIONS" :key="s.value" :value="s.value" :disabled="!s.value">{{ STATUS_LABELS[s.value] || s.label }}</option>
            </select>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped lang="scss">
.action-board {
  min-height: 100vh;
  background: var(--color-bg);
}

.main-container {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px;
}

.board-header {
  margin-bottom: 16px;
}

.board-title {
  display: flex;
  align-items: center;
  gap: 8px;

  h2 {
    font-size: 1.25rem;
    font-weight: 600;
    color: #1f2937;
    margin: 0;
  }

  .sub-tip {
    font-size: 0.75rem;
    color: #9ca3af;
  }
}

.stat-bar {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 0.875rem;
  color: #6b7280;
}

.stat-item { display: inline-flex; align-items: center; gap: 4px; }

.stat-overdue.has-overdue {
  color: #dc2626;
  font-weight: 600;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 16px;
  padding: 12px 16px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.filter-icon {
  color: #9ca3af;
  flex-shrink: 0;
}

.filter-input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 0.85rem;
  color: #374151;
  background: white;
  outline: none;
  width: 140px;
}

.filter-input:focus {
  border-color: var(--el-color-primary, #409eff);
}

.owner-input { width: 150px; }

.overdue-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.85rem;
  color: #4b5563;
  cursor: pointer;
  user-select: none;
}

.board-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 260px;
  color: #6b7280;
  font-size: 0.9rem;

  .spin { animation: spin 1s linear infinite; }
  .err-icon { color: #dc2626; }
  .empty-icon { color: #10b981; }
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.item-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.item-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 18px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  border-left: 3px solid transparent;
  transition: box-shadow 0.2s;
  flex-wrap: wrap;

  &:hover {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  }
}

.item-card.is-overdue {
  border-left-color: #dc2626;
  background: #fef2f2;
}

.item-main {
  flex: 1;
  min-width: 220px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.item-status {
  flex-shrink: 0;
  font-size: 0.75rem;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  color: white;
}

.st-open { background: #6b7280; }      /* 待开始 灰 */
.st-doing { background: #f59e0b; }     /* 进行中 橙 */
.st-done { background: #10b981; }      /* 已完成 绿 */
.st-dropped { background: #9ca3af; }   /* 已放弃 浅灰 */

.item-content {
  margin: 0;
  font-size: 0.9rem;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.item-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-chip,
.due-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 0.75rem;
  color: #4b5563;
  background: #f3f4f6;
  padding: 2px 8px;
  border-radius: 999px;
  white-space: nowrap;
}

.due-overdue {
  color: #dc2626;
  background: #fee2e2;
}

.due-flag {
  font-weight: 700;
}

.ticket-chip {
  cursor: pointer;
  color: var(--el-color-primary, #409eff);
  background: #eff6ff;

  &:hover { text-decoration: underline; }
}

.item-actions {
  flex-shrink: 0;
}

.status-select {
  height: 30px;
  padding: 0 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 0.8rem;
  color: #374151;
  background: white;
  outline: none;
  cursor: pointer;
}
</style>