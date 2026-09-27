<script setup lang="ts">
/**
 * WorkbenchQueues —— 首页工作台的行动队列区（2026-09-27）。
 *
 * ── 为什么加这一区 ────────────────────────────────────────────
 * 值班首屏的 KPI 与趋势回答「系统怎么样」，但不回答「我现在该干什么」。
 * 本区把两个行动队列提到首屏：
 * - 待处理工单队列（按优先级排，点行进详情）
 * - 待审批动作（仅 admin；处置中心的待审队列前置曝光）
 *
 * ── 数据纪律 ─────────────────────────────────────────────────
 * 两个队列都用 TanStack Query 且 key 注册在 queryKeys.ts：
 * 工单写操作失效 ticketKeys.all 时本队列自动重拉；
 * 审批决策失效 approvalKeys.all 时同理。不写自己的刷新逻辑。
 */
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { Activity, Shield, CheckCircle, ChevronRight, Ticket, Clock } from 'lucide-vue-next'
import { fetchTickets } from '@/api/tickets'
import { listApprovals } from '@/api/approval'
import { ticketKeys, approvalKeys } from '@/config/queryKeys'
import { useAppStore } from '@/stores/app'
import { getStatusLabel, getPriorityLabel } from '@/stores/tickets'
import RelativeTime from '@/components/common/RelativeTime.vue'

const router = useRouter()
const app = useAppStore()

// ==================== 待处理工单队列 ====================

const ticketQueue = useQuery({
  // status 只取「待处理」：这是行动队列，处理中/已解决的不该再占值班注意力
  queryKey: ticketKeys.list({ page: 1, size: 6, status: 'pending', sortBy: 'priority', sortAsc: true }),
  queryFn: () => fetchTickets({ page: 1, size: 6, status: 'pending', sortBy: 'priority', sortAsc: true }),
  staleTime: 30_000,
})
const queueTickets = computed(() => ticketQueue.data.value?.tickets ?? [])

// ==================== 待审批动作（admin 专属） ====================

const isAdmin = computed(() => app.isAuthenticated && app.hasRole(['admin']))
const approvalQueue = useQuery({
  queryKey: approvalKeys.list('PENDING', 1, 5),
  queryFn: () => listApprovals('PENDING', 1, 5),
  // 后端限 ADMIN；非管理员请求只收获 403 噪音——直接不发
  enabled: isAdmin,
  staleTime: 20_000,
})
const pendingApprovals = computed(() => approvalQueue.data.value?.items ?? [])

// ==================== 展示映射 ====================

/** 优先级 → 徽章色调（P0 最刺眼，逐级递减） */
const PRIORITY_TONE: Record<string, string> = {
  P0: 'tone-danger', P1: 'tone-warning', P2: 'tone-info', P3: 'tone-muted',
  urgent: 'tone-danger', high: 'tone-warning', medium: 'tone-info', low: 'tone-muted',
}
const priorityTone = (p: string) => PRIORITY_TONE[p] ?? 'tone-muted'

/** 审批风险级 → 徽章色调 */
const RISK_TONE: Record<string, string> = {
  HIGH: 'tone-danger', MEDIUM: 'tone-warning', LOW: 'tone-info',
}
const riskTone = (r: string) => RISK_TONE[r?.toUpperCase()] ?? 'tone-muted'

const goTickets = () => router.push('/tickets?status=pending&sortBy=priority&sortAsc=true')
const goTicket = (id: string) => router.push(`/tickets/${id}`)
const goDisposal = () => router.push('/disposal?tab=approvals')
</script>

<template>
  <div class="queues-grid">
    <!-- 待处理工单队列 -->
    <section class="queue-card" aria-label="待处理工单队列">
      <header class="queue-head">
        <div class="queue-title">
          <Activity :size="15" class="queue-icon tone-icon-brand" />
          <h2>待处理工单队列</h2>
        </div>
        <button class="queue-more" type="button" @click="goTickets">
          查看全部
          <ChevronRight :size="13" />
        </button>
      </header>

      <div v-if="ticketQueue.isLoading.value" class="queue-empty">加载中…</div>
      <div v-else-if="queueTickets.length === 0" class="queue-empty queue-empty--ok">
        <CheckCircle :size="16" />
        当前无待处理工单
      </div>
      <ul v-else class="queue-list">
        <li
          v-for="t in queueTickets"
          :key="t.id"
          class="queue-row"
          role="button"
          tabindex="0"
          @click="goTicket(t.id)"
          @keydown.enter="goTicket(t.id)"
        >
          <span class="badge" :class="priorityTone(t.priority)">{{ getPriorityLabel(t.priority) }}</span>
          <div class="row-main">
            <div class="row-topline">
              <span class="row-id">{{ t.id }}</span>
              <span v-if="t.service" class="row-service">{{ t.service }}</span>
            </div>
            <p class="row-title">{{ t.title }}</p>
          </div>
          <div class="row-side">
            <span class="row-status">{{ getStatusLabel(t.status) }}</span>
            <RelativeTime :value="t.createdAt" class="row-time" />
          </div>
        </li>
      </ul>
    </section>

    <!-- 待审批动作（admin） -->
    <section v-if="isAdmin" class="queue-card" aria-label="待审批动作">
      <header class="queue-head">
        <div class="queue-title">
          <Shield :size="15" class="queue-icon tone-icon-warning" />
          <h2>待审批动作</h2>
        </div>
        <button class="queue-more" type="button" @click="goDisposal">
          去处置中心
          <ChevronRight :size="13" />
        </button>
      </header>

      <div v-if="approvalQueue.isLoading.value" class="queue-empty">加载中…</div>
      <div v-else-if="pendingApprovals.length === 0" class="queue-empty queue-empty--ok">
        <CheckCircle :size="16" />
        无待处理动作
      </div>
      <ul v-else class="queue-list">
        <li
          v-for="a in pendingApprovals"
          :key="a.id"
          class="queue-row"
          role="button"
          tabindex="0"
          @click="goDisposal"
          @keydown.enter="goDisposal"
        >
          <span class="badge" :class="riskTone(a.riskLevel)">{{ a.riskLevel }}</span>
          <div class="row-main">
            <p class="row-title">{{ a.summary }}</p>
            <div class="row-topline">
              <Ticket :size="11" class="row-dim-icon" />
              <span class="row-dim">{{ a.actionType }}</span>
              <Clock :size="11" class="row-dim-icon" />
              <RelativeTime :value="a.createTime" class="row-dim" />
            </div>
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped lang="scss">
.queues-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}

.queue-card {
  background: var(--surface-1);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.queue-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border-1);
}

.queue-title {
  display: flex;
  align-items: center;
  gap: 8px;

  h2 {
    margin: 0;
    font-size: var(--text-sm);
    font-weight: 600;
    color: var(--text-1);
  }
}

.queue-icon {
  flex-shrink: 0;
}

.tone-icon-brand { color: var(--brand); }
.tone-icon-warning { color: var(--warning); }

.queue-more {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: none;
  background: transparent;
  font-size: var(--text-xs);
  color: var(--brand);
  cursor: pointer;
  padding: 2px 4px;
  border-radius: var(--radius-sm);

  &:hover { background: var(--brand-subtle); }
}

.queue-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 32px 16px;
  font-size: var(--text-sm);
  color: var(--text-3);

  &--ok { color: var(--success); }
}

.queue-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.queue-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out);

  &:not(:last-child) {
    border-bottom: 1px solid var(--border-1);
  }

  &:hover, &:focus-visible {
    background: var(--surface-hover);
    outline: none;
  }
}

/* 徽章：彩色底 + 深色字（demo StatCard 同款配色对思路，走令牌） */
.badge {
  flex-shrink: 0;
  margin-top: 1px;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 600;
  line-height: 16px;
  white-space: nowrap;
}

.tone-danger { background: var(--danger-subtle); color: var(--danger); }
.tone-warning { background: var(--warning-subtle); color: var(--warning); }
.tone-info { background: var(--info-subtle); color: var(--info); }
.tone-muted { background: var(--surface-2); color: var(--text-3); }

.row-main {
  flex: 1;
  min-width: 0;
}

.row-topline {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.row-id {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}

.row-service {
  padding: 0 6px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  font-size: 11px;
  color: var(--text-2);
}

.row-title {
  margin: 2px 0 0;
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--text-1);
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.row-side {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.row-status {
  font-size: 11px;
  color: var(--text-2);
}

.row-time {
  font-size: 11px;
  color: var(--text-3);
}

.row-dim-icon {
  color: var(--text-3);
  flex-shrink: 0;
}

.row-dim {
  font-size: 11px;
  color: var(--text-3);
}
</style>
