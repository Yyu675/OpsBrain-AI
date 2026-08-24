<script setup lang="ts">
/**
 * 审批中心（方向 D：L3 人机协同审批）
 *
 * 管理员审批 AI 提议的高危动作（如创建高优工单）。批准即由后端重放执行。
 * 全部接口限 ADMIN（后端 @SaCheckRole）——非管理员会收到 403，页面提示无权限。
 *
 * 对齐蓝图 §二：P0/P1 高危动作必须人工审查确认后才执行。
 */
import { notify } from '@/utils/notify'
import { ref, computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ShieldCheck, RefreshCw, Check, X, Clock, AlertTriangle } from 'lucide-vue-next'
import {
  useApprovalListQuery,
  useApprovalMutations,
  type ApprovalRequest,
} from '@/api/queries/approval.query'
import RelativeTime from '@/components/common/RelativeTime.vue'
import AppEmpty from '@/components/common/AppEmpty.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'

const STATUS_TABS = [
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已批准' },
  { value: 'EXECUTED', label: '已执行' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'ALL', label: '全部' }
]

const RISK_LABELS: Record<string, string> = {
  READ_ONLY: '只读',
  DRAFT: '草稿',
  CONTROLLED_WRITE: '受控写',
  HIGH_RISK_EXECUTION: '高危执行'
}
const STATUS_LABELS: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  EXECUTED: '已执行',
  EXECUTE_FAILED: '执行失败',
  REJECTED: '已驳回',
  EXPIRED: '已过期'
}

const activeTab = ref('PENDING')
const actingId = ref<number | null>(null)

/**
 * 列表与决策由 TanStack Query 驱动。
 *
 * activeTab 进 queryKey，切 tab 自动重拉（不需在 switchTab 里手调 fetchList）；
 * 决策成功后 invalidateQueries 一次，列表与导航栏待审角标同时更新——
 * 替掉了此前「emit('approval-decided') → 导航栏订阅」的手工事件通路。
 */
const listQuery = useApprovalListQuery(activeTab)
const { approve: approveMutation, reject: rejectMutation } = useApprovalMutations()

const items = listQuery.items
const loading = listQuery.isLoading
const loadError = listQuery.error
// listQuery.total 未在模板渲染（迁移前也是如此，属既有情况）——
// 本页无分页控件，审批量小时逐条看完即可。若将来加分页再接上

const riskTagType = (risk: string): 'danger' | 'warning' | 'info' => {
  if (risk === 'HIGH_RISK_EXECUTION') return 'danger'
  if (risk === 'CONTROLLED_WRITE') return 'warning'
  return 'info'
}
const statusTagType = (s: string): 'danger' | 'warning' | 'success' | 'info' => {
  if (s === 'PENDING') return 'warning'
  if (s === 'EXECUTED' || s === 'APPROVED') return 'success'
  if (s === 'EXECUTE_FAILED' || s === 'REJECTED' || s === 'EXPIRED') return 'danger'
  return 'info'
}

/** 手动刷新（错误态重试入口） */
const fetchList = () => listQuery.refetch()

const switchTab = (tab: string) => {
  if (tab === activeTab.value) return
  // tab 已在 queryKey 中，改值即触发重拉
  activeTab.value = tab
}

const doApprove = async (row: ApprovalRequest) => {
  try {
    await ElMessageBox.confirm(
      `确认批准并执行「${row.summary}」吗？批准后系统将立即执行该动作。`,
      '批准确认',
      { confirmButtonText: '批准并执行', cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }
  actingId.value = row.id
  try {
    const updated = await approveMutation.mutateAsync(row.id)
    // 批准成功但执行失败必须如实区分（6.57 契约：人的决策已成事实，
    // 执行失败标 EXECUTE_FAILED 供人工介入，不能笼统报「已批准」）
    if (updated.status === 'EXECUTE_FAILED') {
      notify.warning(`已批准，但执行失败：${updated.executeResult || '未知原因'}`)
    } else {
      notify.success(`已批准并执行：${updated.executeResult || '成功'}`)
    }
    // 列表与导航栏待审角标由 mutation 的 invalidateQueries 一并刷新，
    // 不再需要手动 fetchList + emit('approval-decided')
  } catch {
    // 错误提示已由 mutation 的 onError 统一处理
  } finally {
    actingId.value = null
  }
}

const doReject = async (row: ApprovalRequest) => {
  let reason: string
  try {
    const r = await ElMessageBox.prompt(
      `驳回「${row.summary}」，请填写驳回理由（必填，将记入审计）：`,
      '驳回审批',
      {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回理由',
        inputValidator: (v: string) => (v && v.trim() ? true : '驳回理由不能为空')
      }
    )
    reason = r.value.trim()
  } catch { return }
  actingId.value = row.id
  try {
    await rejectMutation.mutateAsync({ id: row.id, reason })
    notify.success('已驳回')
  } catch {
    // 错误提示已由 mutation 的 onError 统一处理
  } finally {
    actingId.value = null
  }
}

const isPending = (row: ApprovalRequest) => row.status === 'PENDING'
const forbidden = computed(() => {
  const e = loadError.value as { bizCode?: number; status?: number } | null
  return !!e && (e.status === 403 || e.bizCode === 40103 || e.bizCode === 40301)
})

// 首次加载由 Query 自动触发（挂载即拉取），无需 onMounted
</script>

<template>
  <div class="approval-center">
    <main class="main-container">
      <div class="page-header-card">
        <div class="page-header">
          <div class="header-title">
            <ShieldCheck :size="22" />
            <div>
              <h1 class="page-title">审批中心</h1>
              <p class="page-subtitle">AI 提议的高危动作需管理员审批后执行（人机协同 · HITL）</p>
            </div>
          </div>
          <button class="btn-refresh" type="button" :disabled="loading" @click="fetchList">
            <RefreshCw :size="16" :class="{ spinning: loading }" /> 刷新
          </button>
        </div>
        <div class="tabs">
          <button
            v-for="t in STATUS_TABS"
            :key="t.value"
            class="tab"
            :class="{ active: activeTab === t.value }"
            @click="switchTab(t.value)"
          >{{ t.label }}</button>
        </div>
      </div>

      <!-- 无权限（非 ADMIN）-->
      <div v-if="forbidden" class="state-wrap">
        <AlertTriangle :size="28" class="state-icon-warn" />
        <h3>无审批权限</h3>
        <p>审批为管理员专属操作，当前账号无权访问。</p>
      </div>
      <div v-else-if="loading && items.length === 0" class="skeleton-wrap">
        <div v-for="n in 5" :key="n" class="skeleton-bar" />
      </div>
      <div v-else-if="loadError" class="state-wrap">
        <ApiErrorState :error="loadError" compact retry-label="重试" @retry="fetchList" />
      </div>
      <div v-else-if="items.length === 0" class="state-wrap">
        <AppEmpty size="sm" :description="activeTab === 'PENDING' ? '暂无待审批事项' : '无记录'" />
      </div>

      <div v-else class="table-container">
        <el-table :data="items" border stripe row-key="id">
          <el-table-column label="风险" width="96" align="center">
            <template #default="{ row }">
              <el-tag :type="riskTagType(row.riskLevel)" size="small" effect="dark">
                {{ RISK_LABELS[row.riskLevel] || row.riskLevel }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="审批事项" min-width="240">
            <template #default="{ row }">
              <div class="summary-cell">
                <span class="summary-text">{{ row.summary }}</span>
                <span class="summary-meta">#{{ row.id }} · {{ row.actionType }} · 申请方 {{ row.requester }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" effect="light">
                {{ STATUS_LABELS[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="120">
            <template #default="{ row }"><RelativeTime :value="row.createTime" /></template>
          </el-table-column>
          <el-table-column label="决策/结果" min-width="180">
            <template #default="{ row }">
              <div class="result-cell">
                <span v-if="row.approver" class="result-line">审批人：{{ row.approver }}</span>
                <span v-if="row.decisionReason" class="result-line">理由：{{ row.decisionReason }}</span>
                <span v-if="row.executeResult" class="result-line" :class="{ fail: row.status === 'EXECUTE_FAILED' }">
                  执行：{{ row.executeResult }}
                </span>
                <span v-if="!row.approver && !row.executeResult" class="result-muted">—</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <div v-if="isPending(row)" class="actions">
                <button class="act act-approve" :disabled="actingId === row.id" @click="doApprove(row)">
                  <Check :size="14" /> 批准
                </button>
                <button class="act act-reject" :disabled="actingId === row.id" @click="doReject(row)">
                  <X :size="14" /> 驳回
                </button>
              </div>
              <span v-else class="act-done"><Clock :size="13" /> 已处理</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </main>
  </div>
</template>

<style scoped lang="scss">
.approval-center { min-height: 100vh; background: var(--color-bg); }
.main-container { max-width: 1280px; margin: 0 auto; padding: 24px; }
.page-header-card { background: var(--color-surface); border-radius: var(--radius-lg); padding: 24px; margin-bottom: 16px; box-shadow: var(--shadow-sm); }
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.header-title { display: flex; align-items: center; gap: 12px; color: var(--color-primary); }
.page-title { margin: 0; font-size: var(--text-2xl); font-weight: var(--weight-bold); color: var(--color-text-primary); }
.page-subtitle { margin: 2px 0 0; font-size: var(--text-sm); color: var(--color-text-secondary); }
.btn-refresh { display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border: 1px solid var(--color-border-light); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-text-primary); font-family: var(--font-body); cursor: pointer; transition: all .15s; &:hover:not(:disabled){border-color: var(--color-primary); color: var(--color-primary);} &:disabled{opacity:.55; cursor:not-allowed;} .spinning{animation:spin 1s linear infinite;} }
.tabs { display: flex; gap: 8px; margin-top: 16px; flex-wrap: wrap; }
.tab { padding: 6px 16px; border: 1px solid var(--color-border-light); border-radius: 20px; background: var(--color-surface); font-size: var(--text-sm); font-family: var(--font-body); color: var(--color-text-secondary); cursor: pointer; transition: all .15s; &:hover{color: var(--color-primary);} &.active{background: var(--color-primary); border-color: var(--color-primary); color:#fff;} }
.state-wrap { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:8px; min-height:240px; background: var(--color-surface); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); padding:32px; h3{margin:0; color: var(--color-text-primary);} p{margin:0; font-size: var(--text-sm); color: var(--color-text-secondary);} }
.state-icon-warn { color: var(--color-warning, #e6a23c); }
.skeleton-wrap { display:flex; flex-direction:column; gap:12px; background: var(--color-surface); border-radius: var(--radius-lg); padding:24px; box-shadow: var(--shadow-sm); }
.skeleton-bar { height:20px; border-radius:6px; background: linear-gradient(90deg,#f1f5f9 25%,#e2e8f0 37%,#f1f5f9 63%); background-size:400% 100%; animation: shimmer 1.4s ease infinite; }
@keyframes shimmer { 0%{background-position:100% 50%;} 100%{background-position:0 50%;} }
@keyframes spin { from{transform:rotate(0);} to{transform:rotate(360deg);} }
.table-container { background: var(--color-surface); border-radius: var(--radius-lg); padding:8px; box-shadow: var(--shadow-sm); overflow:hidden; }
.summary-cell { display:flex; flex-direction:column; gap:2px; }
.summary-text { font-weight: var(--weight-medium); color: var(--color-text-primary); }
.summary-meta { font-size: var(--text-xs); color: var(--color-text-tertiary); font-family: var(--font-mono, monospace); }
.result-cell { display:flex; flex-direction:column; gap:2px; font-size: var(--text-xs); color: var(--color-text-secondary); }
.result-line { word-break: break-word; }
.result-line.fail { color: var(--state-error, #f56c6c); }
.result-muted { color: var(--color-text-tertiary); }
.actions { display:flex; gap:6px; }
.act { display:inline-flex; align-items:center; gap:4px; padding:4px 10px; border:1px solid var(--color-border-light); border-radius: var(--radius-sm); font-size: var(--text-xs); font-family: var(--font-body); background: var(--color-surface); cursor:pointer; transition: all .15s; &:disabled{opacity:.5; cursor:not-allowed;} }
.act-approve:hover:not(:disabled){ border-color: var(--state-success,#67c23a); color: var(--state-success,#67c23a); background: rgba(103,194,58,.08);}
.act-reject:hover:not(:disabled){ border-color: var(--state-error,#f56c6c); color: var(--state-error,#f56c6c); background: rgba(245,108,108,.08);}
.act-done { display:inline-flex; align-items:center; gap:4px; font-size: var(--text-xs); color: var(--color-text-tertiary); }
</style>
