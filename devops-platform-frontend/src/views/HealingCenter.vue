<script setup lang="ts">
/**
 * 自愈中心（S3-1：L4 受控自愈）
 *
 * 一页看全三件事：执行台账（审计）、手工触发（走治理门）、撤销（回滚）。
 * 审批不在这里做——REQUIRES_APPROVAL 的单子去审批中心点头，批准后由后端
 * 回调自愈编排器续走执行（HEALING 分发）。
 *
 * 全部接口限 ADMIN（后端 @SaCheckRole）——非管理员会收到 403。
 */
import { notify } from '@/utils/notify'
import { ref, computed, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { RefreshCw, Zap, RotateCcw, Eye, Wrench } from 'lucide-vue-next'
import {
  listHealingExecutions,
  listHealingExecutors,
  triggerHealing,
  undoHealing,
  type HealingExecution,
} from '@/api/healing'
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

const DECISION_META: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  AUTO_EXECUTE: { label: '免审直执行', type: 'success' },
  REQUIRES_APPROVAL: { label: '需审批', type: 'warning' },
  DENIED: { label: '门拒绝', type: 'danger' },
  NO_EXECUTOR: { label: '无执行器', type: 'info' },
}

const items = ref<HealingExecution[]>([])
const executors = ref<string[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const actingId = ref<number | null>(null)

// 详情抽屉
const detailVisible = ref(false)
const detailRow = ref<HealingExecution | null>(null)

// 触发弹窗
const triggerVisible = ref(false)
const triggerForm = ref({ actionKey: 'mock.disk.cleanup', environment: 'prod', target: '', paramsJson: '{}', alertId: '' })
const triggerSubmitting = ref(false)

const hasRows = computed(() => items.value.length > 0)

async function reload() {
  loading.value = true
  loadError.value = null
  try {
    const [rows, execs] = await Promise.all([listHealingExecutions(50), listHealingExecutors()])
    items.value = rows
    executors.value = execs
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function openDetail(row: HealingExecution) {
  detailRow.value = row
  detailVisible.value = true
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

async function submitTrigger() {
  let params: Record<string, unknown>
  try {
    params = JSON.parse(triggerForm.value.paramsJson || '{}')
  } catch {
    notify.error('参数不是合法 JSON')
    return
  }
  const alertIdNum = triggerForm.value.alertId.trim() === '' ? undefined : Number(triggerForm.value.alertId)
  if (alertIdNum !== undefined && Number.isNaN(alertIdNum)) {
    notify.error('告警 id 必须是数字')
    return
  }
  triggerSubmitting.value = true
  try {
    const outcome = await triggerHealing({
      actionKey: triggerForm.value.actionKey.trim(),
      environment: triggerForm.value.environment.trim(),
      target: triggerForm.value.target.trim() || undefined,
      params,
      alertId: alertIdNum,
    })
    if (outcome.status === 'PENDING_APPROVAL') {
      notify.warning(`已建审批单 #${outcome.approvalId}，去审批中心点头后执行`)
    } else if (outcome.status === 'REJECTED') {
      notify.warning(`治理门拒绝：${outcome.message}`)
    } else if (outcome.status === 'SUCCEEDED') {
      notify.success(`已执行：${outcome.message}`)
    } else {
      notify.error(outcome.message)
    }
    triggerVisible.value = false
    await reload()
  } catch (e) {
    notify.error(e instanceof Error ? e.message : '触发失败')
  } finally {
    triggerSubmitting.value = false
  }
}

async function confirmUndo(row: HealingExecution) {
  try {
    await ElMessageBox.confirm(
      `撤销将按执行前快照回放「${row.actionKey}」（凭据 ${row.undoToken}）。确认撤销？`,
      '撤销确认',
      { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  actingId.value = row.id
  try {
    const outcome = await undoHealing(row.id)
    if (outcome.status === 'UNDONE') {
      notify.success(outcome.message)
    } else {
      notify.warning(outcome.message)
    }
    await reload()
  } catch (e) {
    notify.error(e instanceof Error ? e.message : '撤销失败')
  } finally {
    actingId.value = null
  }
}

const canUndo = (row: HealingExecution) => row.status === 'SUCCEEDED' && !!row.undoToken

onMounted(reload)
</script>

<template>
  <div class="healing-center">
    <header class="page-header">
      <div>
        <h1>自愈中心</h1>
        <p class="subtitle">受控自愈执行台账 —— 触发走治理门，批准去审批中心，撤销按快照回放</p>
      </div>
      <div class="header-actions">
        <el-button :icon="RefreshCw" @click="reload" :loading="loading">刷新</el-button>
        <el-button type="primary" :icon="Zap" @click="triggerVisible = true">触发自愈</el-button>
      </div>
    </header>

    <main class="content">
      <section class="executors-bar">
        <span class="bar-label"><Wrench :size="14" /> 已注册执行器：</span>
        <el-tag v-for="k in executors" :key="k" size="small" effect="plain" class="exec-tag">{{ k }}</el-tag>
        <span v-if="executors.length === 0" class="empty-hint">（无）</span>
      </section>

      <DataStateBoundary
        :loading="loading"
        :error="loadError"
        :count="items.length"
        empty-title="暂无自愈执行记录"
        empty-description="触发一次自愈动作，或等待告警驱动的自动闭环"
      >
        <el-table :data="items" v-if="hasRows" class="exec-table">
          <el-table-column prop="id" label="#" width="70" />
          <el-table-column prop="actionKey" label="动作" min-width="180" show-overflow-tooltip />
          <el-table-column prop="environment" label="环境" width="90" />
          <el-table-column prop="target" label="目标" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.target || '—' }}</template>
          </el-table-column>
          <el-table-column label="门裁决" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="DECISION_META[row.gateDecision]?.type || 'info'">
                {{ DECISION_META[row.gateDecision]?.label || row.gateDecision }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="105">
            <template #default="{ row }">
              <el-tag size="small" :type="STATUS_META[row.status]?.type || 'info'">
                {{ STATUS_META[row.status]?.label || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="executorKey" label="执行器" width="90">
            <template #default="{ row }">{{ row.executorKey || '—' }}</template>
          </el-table-column>
          <el-table-column prop="requestedBy" label="发起人" width="100">
            <template #default="{ row }">{{ row.requestedBy || '—' }}</template>
          </el-table-column>
          <el-table-column label="发起时间" width="120">
            <template #default="{ row }">
              <RelativeTime v-if="row.createdAt" :value="row.createdAt" />
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :icon="Eye" @click="openDetail(row)">详情</el-button>
              <el-button
                v-if="canUndo(row)"
                link
                type="warning"
                :icon="RotateCcw"
                :loading="actingId === row.id"
                @click="confirmUndo(row)"
              >撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </DataStateBoundary>
    </main>

    <!-- 台账详情抽屉 -->
    <el-drawer v-model="detailVisible" title="执行台账详情" size="520px">
      <div v-if="detailRow" class="detail-body">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="台账 #">{{ detailRow.id }}</el-descriptions-item>
          <el-descriptions-item label="动作">{{ detailRow.actionKey }}</el-descriptions-item>
          <el-descriptions-item label="环境 / 目标">{{ detailRow.environment }} / {{ detailRow.target || '—' }}</el-descriptions-item>
          <el-descriptions-item label="门裁决">{{ DECISION_META[detailRow.gateDecision]?.label || detailRow.gateDecision }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ STATUS_META[detailRow.status]?.label || detailRow.status }}</el-descriptions-item>
          <el-descriptions-item label="执行器">{{ detailRow.executorKey || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审批单">{{ detailRow.approvalId ? `#${detailRow.approvalId}` : '—' }}</el-descriptions-item>
          <el-descriptions-item label="关联告警">{{ detailRow.alertId ? `#${detailRow.alertId}` : '—' }}</el-descriptions-item>
          <el-descriptions-item label="撤销凭据">{{ detailRow.undoToken || '—（不可撤销）' }}</el-descriptions-item>
        </el-descriptions>

        <h3 class="block-title">演算计划</h3>
        <pre class="code-block">{{ detailRow.dryRunPlan || '—' }}</pre>

        <h3 class="block-title">执行输出</h3>
        <pre class="code-block">{{ detailRow.output || '—' }}</pre>

        <template v-if="detailRow.error">
          <h3 class="block-title error-title">错误</h3>
          <pre class="code-block error-block">{{ detailRow.error }}</pre>
        </template>

        <h3 class="block-title">参数</h3>
        <pre class="code-block">{{ prettyJson(detailRow.paramsJson) }}</pre>

        <h3 class="block-title">执行前快照</h3>
        <pre class="code-block">{{ prettyJson(detailRow.preSnapshotJson) }}</pre>

        <template v-if="detailRow.verifyStatus">
          <h3 class="block-title">执行后验证</h3>
          <el-tag size="small" :type="verifyTagType(detailRow.verifyStatus)">
            {{ detailRow.verifyStatus }}
          </el-tag>
          <pre class="code-block verify-block">{{ prettyJson(detailRow.verifyResultJson) }}</pre>
        </template>
      </div>
    </el-drawer>

    <!-- 触发自愈弹窗 -->
    <el-dialog v-model="triggerVisible" title="触发自愈动作" width="480px">
      <el-form label-position="top" size="default">
        <el-form-item label="动作标识（actionKey，须已登记白名单）">
          <el-input v-model="triggerForm.actionKey" placeholder="如 mock.disk.cleanup" />
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="triggerForm.environment" class="full-width">
            <el-option label="prod" value="prod" />
            <el-option label="staging" value="staging" />
            <el-option label="dev" value="dev" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标资源">
          <el-input v-model="triggerForm.target" placeholder="如 ns:prod/app-user" />
        </el-form-item>
        <el-form-item label="参数（JSON）">
          <el-input v-model="triggerForm.paramsJson" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="关联告警 id（可空）">
          <el-input v-model="triggerForm.alertId" placeholder="手工触发留空即可" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="triggerVisible = false">取消</el-button>
        <el-button type="primary" :loading="triggerSubmitting" @click="submitTrigger">提交（走治理门）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.healing-center {
  padding: 24px;
  max-width: 1280px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}

.page-header h1 {
  font-size: 22px;
  font-weight: 600;
  margin: 0 0 6px;
}

.subtitle {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.executors-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 14px;
  font-size: 13px;
}

.bar-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-text-color-secondary);
}

.exec-tag {
  font-family: monospace;
}

.empty-hint {
  color: var(--el-text-color-placeholder);
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

.full-width {
  width: 100%;
}
</style>
