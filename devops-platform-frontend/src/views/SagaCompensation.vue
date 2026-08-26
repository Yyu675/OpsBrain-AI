<script setup lang="ts">
/**
 * Saga 补偿中心 —— 自动化失败的最后一道人工兜底（L4 治理能力前端入口）
 *
 * 展示 /saga/attention 的三类半残事务，支持：
 * - 分组查看：全部 / 部分成功（半残）/ 补偿失败 / 需人工介入
 * - 步骤链路：展开查看单个 Saga 的完整执行时间线（/steps）
 * - 人工补偿：二次确认后触发逆向补偿（/compensate），一次只允许一个补偿在跑
 *   （逆向补偿会回滚已落库的写操作，并发下发同样不可预测——与审批中心同一串行化原则）
 *
 * 数据契约见 api/saga.ts（对齐后端 SagaController.toDetail）。
 */
import { computed, onMounted, ref } from 'vue'
import {
  RefreshCw, AlertTriangle, RotateCcw, ChevronDown, ChevronRight, Clock, XCircle, CheckCircle2, Layers
} from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchSagaAttention, fetchSagaSteps, compensateSaga,
  type SagaStep, type SagaStepState
} from '@/api/saga'
import { notify } from '@/utils/notify'

const props = withDefaults(defineProps<{ initialTab?: string }>(), { initialTab: 'ALL' })

// ==================== 状态 ====================

const STATE_LABELS: Record<SagaStepState, string> = {
  PENDING: '待执行', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败',
  PARTIAL_SUCCESS: '部分成功', COMPENSATING: '补偿中', COMPENSATED: '已补偿',
  COMPENSATION_FAILED: '补偿失败', MANUAL_INTERVENTION_REQUIRED: '需人工介入', SKIPPED: '已跳过'
}

/** 需介入的三类状态（供统计/筛选语义参考；列表筛选走 tabs 匹配） */

const tabs = [
  { key: 'ALL', label: '全部', match: () => true },
  { key: 'PARTIAL', label: '部分成功', match: (s: SagaStep) => s.state === 'PARTIAL_SUCCESS' },
  { key: 'FAILED', label: '补偿失败', match: (s: SagaStep) => s.state === 'COMPENSATION_FAILED' },
  { key: 'MANUAL', label: '需人工介入', match: (s: SagaStep) => s.state === 'MANUAL_INTERVENTION_REQUIRED' }
]

const activeTab = ref(props.initialTab)
const records = ref<SagaStep[]>([])
const loading = ref(false)
const loadError = ref('')

/** 展开的 sagaId → 其步骤列表 */
const expandedSteps = ref<Record<string, SagaStep[]>>({})
/** 正在加载步骤的 sagaId 集合 */
const stepsLoading = ref<Set<string>>(new Set())
/** 正在补偿的 sagaId（串行化：一次只允许一个补偿在跑） */
const compensatingId = ref<string | null>(null)

const filtered = computed(() => {
  const tab = tabs.find(t => t.key === activeTab.value) ?? tabs[0]
  return records.value.filter(tab.match)
})

// ==================== 数据加载 ====================

const load = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const res = await fetchSagaAttention(100)
    records.value = res.records ?? []
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败'
    notify.error(loadError.value)
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ==================== 步骤链路 ====================

const toggleSteps = async (step: SagaStep) => {
  const sagaId = step.sagaId
  if (!sagaId) return
  if (expandedSteps.value[sagaId]) {
    const next = { ...expandedSteps.value }
    delete next[sagaId]
    expandedSteps.value = next
    return
  }
  stepsLoading.value = new Set(stepsLoading.value).add(sagaId)
  try {
    const res = await fetchSagaSteps(sagaId)
    // 按 stepSeq 升序 = 执行顺序；补偿展示时逆序高亮
    expandedSteps.value = { ...expandedSteps.value, [sagaId]: [...res.steps].sort((a, b) => a.stepSeq - b.stepSeq) }
  } catch (e) {
    notify.error(e instanceof Error ? e.message : '步骤加载失败')
  } finally {
    const next = new Set(stepsLoading.value)
    next.delete(sagaId)
    stepsLoading.value = next
  }
}

// ==================== 补偿（二次确认 + 串行化）====================

const onCompensate = async (step: SagaStep) => {
  const sagaId = step.sagaId
  if (!sagaId || compensatingId.value) {
    if (compensatingId.value) notify.warning('已有补偿任务在执行中，请等待完成')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将对该事务执行逆向补偿，回滚已落库的写操作（如作废已创建的工单）。\n\nSaga：${sagaId}\n业务主键：${step.businessKey ?? '—'}\n补偿动作：${step.compensationAction ?? '通用补偿'}\n\n补偿动作幂等，重复执行安全。确认继续？`,
      '确认触发补偿',
      { confirmButtonText: '确认补偿', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return // 用户取消
  }

  compensatingId.value = sagaId
  try {
    const result = await compensateSaga(sagaId)
    if (result.fullySucceeded) {
      ElMessage.success(`补偿完成：${result.compensatedCount} 个步骤已回滚，事务已收敛`)
    } else {
      ElMessage.warning(`补偿部分完成：成功 ${result.compensatedCount} / 失败 ${result.failedCount}，仍需人工介入`)
    }
    await load()
    // 刷新已展开的链路
    if (expandedSteps.value[sagaId]) {
      const res = await fetchSagaSteps(sagaId)
      expandedSteps.value = { ...expandedSteps.value, [sagaId]: [...res.steps].sort((a, b) => a.stepSeq - b.stepSeq) }
    }
  } catch (e) {
    notify.error(e instanceof Error ? e.message : '补偿触发失败')
  } finally {
    compensatingId.value = null
  }
}

// ==================== 辅助 ====================

const stateLabel = (s: SagaStepState | null): string => {
  return s ? (STATE_LABELS[s] ?? s) : '—'
}

const stateClass = (s: SagaStepState | null): string => {
  switch (s) {
    case 'PARTIAL_SUCCESS': return 'st-partial'
    case 'COMPENSATION_FAILED': return 'st-failed'
    case 'MANUAL_INTERVENTION_REQUIRED': return 'st-manual'
    case 'COMPENSATED': return 'st-done'
    case 'FAILED': return 'st-failed'
    default: return 'st-normal'
  }
}

const fmtTime = (t: string | null): string => {
  if (!t) return '—'
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return t
  return d.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="saga-page">
    <div class="page-head">
      <div class="head-left">
        <h2 class="page-title">Saga 补偿中心</h2>
        <span class="page-desc">自动化执行失败的最后一道人工兜底：半残事务清单、执行链路回放、人工触发逆向补偿</span>
      </div>
      <button class="refresh-btn" :disabled="loading" @click="load">
        <RefreshCw :size="14" :class="{ spinning: loading }" />
        刷新
      </button>
    </div>

    <!-- 统计条 -->
    <div v-if="records.length" class="stat-bar">
      <div class="stat-item">
        <span class="stat-num">{{ records.length }}</span>
        <span class="stat-label">待介入事务</span>
      </div>
      <div class="stat-item">
        <span class="stat-num st-num-warn">{{ records.filter(r => r.state === 'PARTIAL_SUCCESS').length }}</span>
        <span class="stat-label">部分成功</span>
      </div>
      <div class="stat-item">
        <span class="stat-num st-num-danger">{{ records.filter(r => r.state === 'COMPENSATION_FAILED').length }}</span>
        <span class="stat-label">补偿失败</span>
      </div>
      <div class="stat-item">
        <span class="stat-num st-num-danger">{{ records.filter(r => r.state === 'MANUAL_INTERVENTION_REQUIRED').length }}</span>
        <span class="stat-label">需人工介入</span>
      </div>
    </div>

    <!-- Tab -->
    <div class="tabs">
      <button
        v-for="t in tabs" :key="t.key"
        class="tab" :class="{ active: activeTab === t.key }"
        @click="activeTab = t.key"
      >{{ t.label }}</button>
    </div>

    <!-- 空 / 错误 / 加载 -->
    <div v-if="loadError" class="state-box">
      <XCircle :size="20" class="state-icon err" />
      <span>{{ loadError }}</span>
      <button class="retry-link" @click="load">重试</button>
    </div>
    <div v-else-if="loading" class="state-box"><span class="state-icon">加载中…</span></div>
    <div v-else-if="!filtered.length" class="state-box">
      <CheckCircle2 :size="20" class="state-icon ok" />
      <span>当前没有需要人工介入的 Saga 事务 —— 自动化链路全部收敛 ✅</span>
    </div>

    <!-- 列表 -->
    <div v-else class="record-list">
      <div v-for="rec in filtered" :key="rec.id" class="record-card">
        <!-- 头部行 -->
        <div class="record-head" @click="toggleSteps(rec)">
          <div class="record-main">
            <span class="state-tag" :class="stateClass(rec.state)">{{ stateLabel(rec.state) }}</span>
            <span class="tool-name">{{ rec.toolName }}</span>
            <span class="biz-key" :title="`业务主键：${rec.businessKey ?? '—'}`">
              {{ rec.businessKey ?? '无业务主键' }}
            </span>
          </div>
          <div class="record-meta">
            <span class="meta-time"><Clock :size="12" /> {{ fmtTime(rec.createTime) }}</span>
            <span class="meta-attempt" v-if="rec.attemptCount > 1">已重试 {{ rec.attemptCount }} 次</span>
            <button
              class="act-compensate" :disabled="compensatingId !== null"
              @click.stop="onCompensate(rec)"
            >
              <RotateCcw :size="13" />
              {{ compensatingId === rec.sagaId ? '补偿中…' : '触发补偿' }}
            </button>
            <ChevronDown v-if="expandedSteps[rec.sagaId!]" :size="16" class="chev" />
            <ChevronRight v-else :size="16" class="chev" />
          </div>
        </div>

        <!-- 失败信息 -->
        <div v-if="rec.errorMessage || rec.failureHint" class="record-error">
          <AlertTriangle :size="13" class="err-icon" />
          <div class="err-body">
            <div v-if="rec.errorMessage" class="err-msg">{{ rec.errorMessage }}</div>
            <div v-if="rec.failureHint" class="err-hint">💡 {{ rec.failureHint }}</div>
          </div>
        </div>

        <!-- 步骤链路 -->
        <div v-if="expandedSteps[rec.sagaId!]" class="steps-panel">
          <div class="steps-title"><Layers :size="13" /> 执行链路（按执行顺序，补偿为逆序回滚）</div>
          <div v-if="stepsLoading.has(rec.sagaId!)" class="steps-loading">加载中…</div>
          <div v-else class="steps-list">
            <div
              v-for="s in expandedSteps[rec.sagaId!]" :key="s.id"
              class="step-row" :class="{ 'step-attention': s.needsAttention }"
            >
              <span class="step-seq">{{ s.stepSeq }}</span>
              <span class="step-tool">{{ s.toolName }}</span>
              <span class="step-state" :class="stateClass(s.state)">{{ stateLabel(s.state) }}</span>
              <span class="step-dur" v-if="s.durationMs != null">{{ s.durationMs }}ms</span>
              <span class="step-err" v-if="s.errorMessage" :title="s.errorMessage">{{ s.errorMessage }}</span>
              <span class="step-hint" v-else-if="s.failureHint">{{ s.failureHint }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.saga-page { padding: 20px 24px; max-width: 1200px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-title { font-size: 1.25rem; font-weight: 600; margin: 0 0 4px; }
.page-desc { font-size: .8rem; color: var(--color-text-tertiary, var(--text-3)); }
.refresh-btn { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border: 1px solid var(--border-color, #d9d9d9); border-radius: 6px; background: transparent; cursor: pointer; font-size: .8rem; }
.refresh-btn:hover { border-color: var(--primary, #2563eb); color: var(--primary, #2563eb); }
.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.stat-bar { display: flex; gap: 28px; padding: 12px 16px; border: 1px solid var(--border-color, #ebeef5); border-radius: 8px; margin-bottom: 12px; background: var(--bg-card, #fff); }
.stat-item { display: flex; flex-direction: column; }
.stat-num { font-size: 1.3rem; font-weight: 700; }
.st-num-warn { color: #e6a23c; }
.st-num-danger { color: #f56c6c; }
.stat-label { font-size: .75rem; color: var(--color-text-tertiary, var(--text-3)); }

.tabs { display: flex; gap: 8px; margin-bottom: 14px; }
.tab { padding: 6px 14px; border-radius: 6px; border: 1px solid transparent; background: transparent; cursor: pointer; font-size: .85rem; color: var(--color-text-secondary, var(--text-2)); }
.tab.active { background: rgba(37, 99, 235, .1); color: var(--primary, #2563eb); border-color: rgba(37, 99, 235, .3); }

.state-box { display: flex; align-items: center; gap: 8px; padding: 32px; justify-content: center; color: var(--color-text-tertiary, var(--text-3)); font-size: .9rem; }
.state-icon.ok { color: #67c23a; }
.state-icon.err { color: #f56c6c; }
.retry-link { color: var(--primary, #2563eb); cursor: pointer; background: none; border: none; }

.record-list { display: flex; flex-direction: column; gap: 10px; }
.record-card { border: 1px solid var(--border-color, #ebeef5); border-radius: 8px; background: var(--bg-card, #fff); overflow: hidden; }
.record-head { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; cursor: pointer; }
.record-head:hover { background: rgba(0, 0, 0, .015); }
.record-main { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.state-tag { font-size: .75rem; padding: 2px 8px; border-radius: 10px; font-weight: 500; }
.st-partial { background: rgba(230, 162, 60, .15); color: #b88230; }
.st-failed { background: rgba(245, 108, 108, .15); color: #d03050; }
.st-manual { background: rgba(245, 108, 108, .22); color: #c0392b; }
.st-done { background: rgba(103, 194, 58, .15); color: #529b2e; }
.st-normal { background: rgba(144, 147, 153, .12); color: #606266; }
.tool-name { font-weight: 600; font-size: .9rem; }
.biz-key { font-family: ui-monospace, monospace; font-size: .8rem; background: rgba(37, 99, 235, .08); color: var(--primary, #2563eb); padding: 2px 8px; border-radius: 4px; }
.record-meta { display: flex; align-items: center; gap: 12px; }
.meta-time { display: inline-flex; align-items: center; gap: 4px; font-size: .75rem; color: var(--color-text-tertiary, var(--text-3)); }
.meta-attempt { font-size: .72rem; color: #e6a23c; }
.act-compensate { display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; border-radius: 5px; border: 1px solid #f56c6c; color: #f56c6c; background: transparent; cursor: pointer; font-size: .78rem; }
.act-compensate:hover:not(:disabled) { background: rgba(245, 108, 108, .08); }
.act-compensate:disabled { opacity: .5; cursor: not-allowed; }
.chev { color: var(--color-text-tertiary, var(--text-3)); }

.record-error { display: flex; gap: 8px; padding: 0 16px 10px 16px; }
.err-icon { color: #e6a23c; margin-top: 1px; flex-shrink: 0; }
.err-body { font-size: .8rem; }
.err-msg { font-family: ui-monospace, monospace; color: #c0392b; word-break: break-all; }
.err-hint { color: #b88230; margin-top: 2px; }

.steps-panel { border-top: 1px dashed var(--border-color, #ebeef5); padding: 10px 16px 14px; background: rgba(0, 0, 0, .012); }
.steps-title { display: flex; align-items: center; gap: 6px; font-size: .78rem; color: var(--color-text-secondary, var(--text-2)); margin-bottom: 8px; }
.steps-loading { font-size: .8rem; color: var(--color-text-tertiary, var(--text-3)); padding: 8px; }
.steps-list { display: flex; flex-direction: column; gap: 4px; }
.step-row { display: flex; align-items: center; gap: 10px; font-size: .8rem; padding: 4px 8px; border-radius: 4px; }
.step-row.step-attention { background: rgba(245, 108, 108, .06); }
.step-seq { width: 22px; height: 22px; border-radius: 50%; background: rgba(144, 147, 153, .15); color: var(--text-2, #606266); display: inline-flex; align-items: center; justify-content: center; font-size: .72rem; flex-shrink: 0; }
.step-tool { font-weight: 500; min-width: 140px; }
.step-state { font-size: .72rem; padding: 1px 7px; border-radius: 8px; }
.step-dur { font-size: .72rem; color: var(--color-text-tertiary, var(--text-3)); }
.step-err { flex: 1; font-family: ui-monospace, monospace; font-size: .74rem; color: #c0392b; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.step-hint { flex: 1; font-size: .74rem; color: #b88230; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
