<script setup lang="ts">
/**
 * 自愈执行详情页（S3-5：可观测/可回放收口，§3-5.1）
 *
 * 与自愈中心抽屉同一数据源，但给出可直达链接——审批中心、告警详情
 * 等页面可以用 /self-healing/tasks/:id 直接跳转到某次执行。
 * 批次 5 起附加 V10 步骤时间线（GATE_EVALUATE → … → ESCALATE_TICKET），
 * 旧占位路由 steps/verification/rollback 一并指向本页（锚节点位）。
 */
import { notify } from '@/utils/notify'
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowLeft, RotateCcw } from 'lucide-vue-next'
import { getHealingExecution, undoHealing, type HealingExecution, type HealingStep } from '@/api/healing'
import RelativeTime from '@/components/common/RelativeTime.vue'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'

const STATUS_META: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' | 'primary' }> = {
  SUCCEEDED: { label: '已执行', type: 'success' },
  FAILED: { label: '执行失败', type: 'danger' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  REJECTED: { label: '已拒绝', type: 'info' },
  UNDONE: { label: '已撤销', type: 'primary' },
  UNDO_FAILED: { label: '撤销失败', type: 'danger' },
}

const DECISION_LABELS: Record<string, string> = {
  AUTO_EXECUTE: '免审直执行',
  REQUIRES_APPROVAL: '需审批',
  DENIED: '门拒绝',
  NO_EXECUTOR: '无执行器',
}

const route = useRoute()
const router = useRouter()
// :id（主详情路由）与 :taskId（steps/verification/rollback 回放路由）两参归一
const executionId = Number(route.params.id ?? route.params.taskId)

const row = ref<HealingExecution | null>(null)
const steps = ref<HealingStep[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const undoing = ref(false)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const detail = await getHealingExecution(executionId)
    row.value = detail.execution
    steps.value = detail.steps
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function prettyJson(raw: string | null): string {
  if (!raw) return '—'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/** 验证状态徽章色（PASS 绿 / FAIL 红 / UNKNOWN 灰 / SKIPPED 灰） */
function verifyTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'PASS') return 'success'
  if (status === 'FAIL') return 'danger'
  if (status === 'UNKNOWN') return 'warning'
  return 'info'
}

/** 步骤节点徽章色（OK 绿 / FAIL 红 / 其他灰） */
function stepTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'OK' || status === 'HEALTHY' || status === 'PASS') return 'success'
  if (status === 'FAIL' || status === 'UNHEALTHY' || status === 'P0') return 'danger'
  if (status === 'INTERVENED' || status === 'UNKNOWN' || status === 'P1') return 'warning'
  return 'info'
}

async function confirmUndo() {
  if (!row.value) return
  try {
    await ElMessageBox.confirm(
      `撤销将按执行前快照回放「${row.value.actionKey}」（凭据 ${row.value.undoToken}）。确认撤销？`,
      '撤销确认',
      { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  undoing.value = true
  try {
    const outcome = await undoHealing(executionId)
    if (outcome.status === 'UNDONE') {
      notify.success(outcome.message)
    } else {
      notify.warning(outcome.message)
    }
    await load()
  } catch (e) {
    notify.error(e instanceof Error ? e.message : '撤销失败')
  } finally {
    undoing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="healing-detail">
    <header class="page-header">
      <el-button :icon="ArrowLeft" link @click="router.push('/self-healing/tasks')">返回自愈中心</el-button>
      <h1>执行台账 #{{ executionId }}</h1>
    </header>

    <DataStateBoundary :loading="loading" :error="loadError" :count="row ? 1 : 0">
      <div v-if="row" class="detail-body">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="动作" :span="2">{{ row.actionKey }}</el-descriptions-item>
          <el-descriptions-item label="环境">{{ row.environment }}</el-descriptions-item>
          <el-descriptions-item label="目标">{{ row.target || '—' }}</el-descriptions-item>
          <el-descriptions-item label="门裁决">{{ DECISION_LABELS[row.gateDecision] || row.gateDecision }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="STATUS_META[row.status]?.type || 'info'">
              {{ STATUS_META[row.status]?.label || row.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="执行器">{{ row.executorKey || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审批单">{{ row.approvalId ? `#${row.approvalId}` : '—' }}</el-descriptions-item>
          <el-descriptions-item label="关联告警">{{ row.alertId ? `#${row.alertId}` : '—' }}</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ row.requestedBy || '—' }}</el-descriptions-item>
          <el-descriptions-item label="发起时间">
            <RelativeTime v-if="row.createdAt" :value="row.createdAt" />
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="完成时间">
            <RelativeTime v-if="row.finishedAt" :value="row.finishedAt" />
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="撤销凭据" :span="2">{{ row.undoToken || '—（不可撤销）' }}</el-descriptions-item>
        </el-descriptions>

        <div class="actions-bar">
          <el-button
            v-if="row.status === 'SUCCEEDED' && row.undoToken"
            type="warning"
            :icon="RotateCcw"
            :loading="undoing"
            @click="confirmUndo"
          >撤销本次执行</el-button>
        </div>

        <h3 class="block-title">步骤时间线（回放）</h3>
        <ul v-if="steps.length" class="step-list">
          <li v-for="(st, idx) in steps" :key="idx" class="step-item">
            <el-tag size="small" :type="stepTagType(st.status)" class="step-tag">{{ st.name }}</el-tag>
            <span class="step-status">{{ st.status }}</span>
            <span class="step-detail">{{ st.detail || '—' }}</span>
            <span class="step-at"><RelativeTime :value="st.at" /></span>
          </li>
        </ul>
        <p v-else class="step-empty">无步骤数据（本执行早于 V10 步骤落地，或回放序列未写入）</p>

        <h3 class="block-title">演算计划</h3>
        <pre class="code-block">{{ row.dryRunPlan || '—' }}</pre>

        <h3 class="block-title">执行输出</h3>
        <pre class="code-block">{{ row.output || '—' }}</pre>

        <template v-if="row.error">
          <h3 class="block-title error-title">错误</h3>
          <pre class="code-block error-block">{{ row.error }}</pre>
        </template>

        <h3 class="block-title">参数</h3>
        <pre class="code-block">{{ prettyJson(row.paramsJson) }}</pre>

        <h3 class="block-title">执行前快照</h3>
        <pre class="code-block">{{ prettyJson(row.preSnapshotJson) }}</pre>

        <template v-if="row.verifyStatus">
          <h3 class="block-title">执行后验证</h3>
          <el-tag size="small" :type="verifyTagType(row.verifyStatus)">{{ row.verifyStatus }}</el-tag>
          <pre class="code-block">{{ prettyJson(row.verifyResultJson) }}</pre>
        </template>
      </div>
    </DataStateBoundary>
  </div>
</template>

<style scoped>
.healing-detail {
  padding: 24px;
  max-width: 960px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.page-header h1 {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}

.actions-bar {
  margin: 16px 0;
}

.step-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.step-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  font-size: 13px;
}

.step-item:last-child {
  border-bottom: none;
}

.step-tag {
  min-width: 128px;
  text-align: center;
}

.step-status {
  color: var(--el-text-color-regular);
  min-width: 84px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
}

.step-detail {
  flex: 1;
  color: var(--el-text-color-primary);
  word-break: break-all;
}

.step-at {
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  font-size: 12px;
}

.step-empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 0;
}

.block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin: 16px 0 6px;
}

.error-title {
  color: var(--el-color-danger);
}

.code-block {
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}

.error-block {
  color: var(--el-color-danger);
}
</style>
