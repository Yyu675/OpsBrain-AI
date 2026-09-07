<script setup lang="ts">
/**
 * 自愈执行详情页（S3-1 批次 5：L4 第二条占位路由真实化）
 *
 * 与自愈中心抽屉同一数据源，但给出可直达链接——审批中心、告警详情
 * 等页面可以用 /self-healing/tasks/:id 直接跳转到某次执行。
 */
import { notify } from '@/utils/notify'
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowLeft, RotateCcw } from 'lucide-vue-next'
import { getHealingExecution, undoHealing, type HealingExecution } from '@/api/healing'
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
const executionId = Number(route.params.id)

const row = ref<HealingExecution | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const undoing = ref(false)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    row.value = await getHealingExecution(executionId)
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
