<script setup lang="ts">
/**
 * 使用日志 / 操作审计。
 *
 * ── 信息架构参考 new-api 的 usage-logs ────────────────────────
 * 借鉴的是它的**功能性布局**（不受版权保护的产品设计），而非代码或视觉：
 *   分类 Tab → 统计徽章条 → 紧凑筛选栏（含时间范围）→ 表格 → 详情弹窗
 *
 * 实现全部用 OpsBrain 自己的技术栈（Vue + Element Plus + 现有基础件）
 * 与自己的数据（sys_agent_call_log / sys_operation_audit），
 * 不含 new-api 的任何代码。
 *
 * ── 为什么这个页面值得做 ──────────────────────────────────────
 * 两张审计表一直在写，但此前前端**没有任何入口能看**：
 *   - 查「谁在什么时候改了这张工单」只能连数据库
 *   - 查「这周 AI 花了多少钱、哪些调用最慢」只有 Dashboard 的聚合值，无法下钻
 *
 * 相对 new-api 多做的一层是 **traceId 串联**：一次 AI 建单会同时留下
 * 「AI 调用记录」与「工单创建审计」，关联起来才能回答
 * 「这张工单是谁、通过什么方式、基于什么问答创建的」。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { Activity, Coins, Database, RefreshCw, Search, Zap } from 'lucide-vue-next'

import {
  fetchAiCallLogs,
  fetchAuditFilterOptions,
  fetchOperationAudit,
  fetchTraceDetail,
  type AiCallLogItem,
  type AiCallStats,
  type FilterOptions,
  type OperationAuditItem,
  type TraceDetail,
} from '@/api/auditLogs'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import ServerPagination from '@/components/common/ServerPagination.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import { useServerPagination } from '@/composables/useServerPagination'
import {
  defineUrlFilter,
  enumParser,
  positiveIntParser,
  textParser,
  useUrlFilters,
} from '@/composables/useUrlFilters'
import { formatAbsolute } from '@/utils/time'
import { handleServerError } from '@/utils/notify'

defineOptions({ name: 'AuditLogs' })

// ==================== Tab ====================

type TabId = 'ai-calls' | 'operations'
const TABS: Array<{ id: TabId; label: string }> = [
  { id: 'ai-calls', label: 'AI 调用日志' },
  { id: 'operations', label: '操作审计' },
]
const activeTab = ref<TabId>('ai-calls')

// ==================== 筛选状态 ====================

const modelName = ref('')
const operationType = ref('')
/** '' 不限 / 'true' 命中 / 'false' 未命中——用字符串以便直接绑 select */
const cachedFilter = ref('')
const minLatency = ref('')
const actorId = ref('')
const actionPrefix = ref('')
const targetType = ref('')
const successFilter = ref('')
const dateFrom = ref('')
const dateTo = ref('')

const pagination = useServerPagination({ pageSize: 20 })
const { currentPage, pageSize, totalPages, pageNumbers, pageStart, pageEnd } = pagination
const totalCount = pagination.total

/** 日期解析：只接受 YYYY-MM-DD，与 <input type="date"> 的取值一致 */
const dateParser = (raw: string): string | undefined =>
  /^\d{4}-\d{2}-\d{2}$/.test(raw.trim()) ? raw.trim() : undefined

/**
 * 筛选状态同步到 URL，让「这周所有失败的工单删除操作」这类查询可以直接分享。
 * 与 TicketList / KnowledgeBase 共用同一套实现，行为一致。
 */
useUrlFilters([
  defineUrlFilter<TabId>({
    ref: activeTab, key: 'tab', defaultValue: 'ai-calls',
    parse: enumParser(['ai-calls', 'operations']),
  }),
  defineUrlFilter({ ref: modelName, key: 'model', defaultValue: '', parse: textParser(64) }),
  defineUrlFilter({ ref: operationType, key: 'op', defaultValue: '', parse: textParser(64) }),
  defineUrlFilter({
    ref: cachedFilter, key: 'cached', defaultValue: '',
    parse: enumParser(['', 'true', 'false']),
  }),
  defineUrlFilter({ ref: minLatency, key: 'minLatency', defaultValue: '', parse: textParser(8) }),
  defineUrlFilter({ ref: actorId, key: 'actor', defaultValue: '', parse: textParser(64) }),
  defineUrlFilter({ ref: actionPrefix, key: 'action', defaultValue: '', parse: textParser(64) }),
  defineUrlFilter({ ref: targetType, key: 'target', defaultValue: '', parse: textParser(32) }),
  defineUrlFilter({
    ref: successFilter, key: 'success', defaultValue: '',
    parse: enumParser(['', 'true', 'false']),
  }),
  defineUrlFilter({ ref: dateFrom, key: 'from', defaultValue: '', parse: dateParser }),
  defineUrlFilter({ ref: dateTo, key: 'to', defaultValue: '', parse: dateParser }),
  defineUrlFilter({
    ref: currentPage, key: 'page', defaultValue: 1, parse: positiveIntParser(10000),
  }),
])

// ==================== 数据 ====================

const aiCalls = ref<AiCallLogItem[]>([])
const operations = ref<OperationAuditItem[]>([])
const stats = ref<AiCallStats | null>(null)
const loading = ref(false)
const loadError = ref<unknown>(null)
const filterOptions = ref<FilterOptions>({
  models: [], operationTypes: [], actions: [], targetTypes: [],
})

const rowCount = computed(() =>
  activeTab.value === 'ai-calls' ? aiCalls.value.length : operations.value.length
)

const hasFilters = computed(() =>
  activeTab.value === 'ai-calls'
    ? !!modelName.value || !!operationType.value || !!cachedFilter.value
      || !!minLatency.value || !!dateFrom.value || !!dateTo.value
    : !!actorId.value || !!actionPrefix.value || !!targetType.value
      || !!successFilter.value || !!dateFrom.value || !!dateTo.value
)

/**
 * 把 `YYYY-MM-DD` 补成后端要的 ISO date-time。
 *
 * 起止分别补 00:00:00 / 23:59:59 —— 只传日期时后端按 00:00 解析，
 * 「查 8 月 24 日」会漏掉当天全部记录，这类边界最容易被忽略。
 */
const toIsoStart = (d: string) => (d ? `${d}T00:00:00` : undefined)
const toIsoEnd = (d: string) => (d ? `${d}T23:59:59` : undefined)

/** 请求序号防竞态：快速切 Tab 时先发的慢响应不得覆盖后发的 */
let requestSeq = 0

const load = async () => {
  const seq = ++requestSeq
  loading.value = true
  loadError.value = null
  try {
    if (activeTab.value === 'ai-calls') {
      const res = await fetchAiCallLogs({
        modelName: modelName.value || undefined,
        operationType: operationType.value || undefined,
        cached: cachedFilter.value === '' ? undefined : cachedFilter.value === 'true',
        minLatencyMs: minLatency.value ? Number(minLatency.value) : undefined,
        from: toIsoStart(dateFrom.value),
        to: toIsoEnd(dateTo.value),
        page: currentPage.value,
        size: pageSize.value,
      })
      if (seq !== requestSeq) return
      aiCalls.value = res.items
      stats.value = res.stats
      pagination.setMeta({ total: res.total, totalPages: res.totalPages })
    } else {
      const res = await fetchOperationAudit({
        actorId: actorId.value || undefined,
        action: actionPrefix.value || undefined,
        targetType: targetType.value || undefined,
        success: successFilter.value === '' ? undefined : successFilter.value === 'true',
        from: toIsoStart(dateFrom.value),
        to: toIsoEnd(dateTo.value),
        page: currentPage.value,
        size: pageSize.value,
      })
      if (seq !== requestSeq) return
      operations.value = res.items
      pagination.setMeta({ total: res.total, totalPages: res.totalPages })
    }
  } catch (e) {
    if (seq !== requestSeq) return
    loadError.value = e
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

const applyFilters = async () => {
  pagination.resetPage()
  await load()
}

const clearFilters = async () => {
  modelName.value = ''
  operationType.value = ''
  cachedFilter.value = ''
  minLatency.value = ''
  actorId.value = ''
  actionPrefix.value = ''
  targetType.value = ''
  successFilter.value = ''
  dateFrom.value = ''
  dateTo.value = ''
  await applyFilters()
}

const goToPage = async (p: number) => {
  if (pagination.goToPage(p)) await load()
}

// 切 Tab 时重置分页并重新拉取——两个 Tab 的数据源与筛选维度完全不同
watch(activeTab, async () => {
  pagination.resetPage()
  await load()
})

onMounted(async () => {
  // 筛选选项失败不阻塞主列表：下拉退化为纯输入框仍可用
  fetchAuditFilterOptions()
    .then(o => { filterOptions.value = o })
    .catch(e => handleServerError(e, { action: '加载筛选选项' }))
  await load()
})

// ==================== 链路下钻 ====================

const traceOpen = ref(false)
const traceLoading = ref(false)
const traceData = ref<TraceDetail | null>(null)
const traceError = ref<unknown>(null)

const openTrace = async (traceId: string | null) => {
  if (!traceId) return
  traceOpen.value = true
  traceLoading.value = true
  traceError.value = null
  traceData.value = null
  try {
    traceData.value = await fetchTraceDetail(traceId)
  } catch (e) {
    traceError.value = e
  } finally {
    traceLoading.value = false
  }
}

/** citations 后端存的是 JSON 字符串，坏数据不应让抽屉整个崩掉 */
const traceCitations = computed<string[]>(() => {
  const raw = traceData.value?.aiCall?.citations
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.map(String) : [raw]
  } catch {
    return [raw]
  }
})

// ==================== 展示工具 ====================

const fmtCost = (v: number | null) => (v === null || v === undefined ? '—' : `¥${v.toFixed(4)}`)
const fmtLatency = (v: number | null) => (v === null || v === undefined ? '—' : `${v} ms`)

/** 耗时分档配色：慢调用要一眼能看出来，这正是运维查日志的主要目的之一 */
const latencyLevel = (v: number | null): 'fast' | 'normal' | 'slow' => {
  if (v === null || v === undefined) return 'normal'
  if (v >= 5000) return 'slow'
  if (v <= 500) return 'fast'
  return 'normal'
}

/** 短 traceId：全长 32 位在表格里挤占过多宽度，前 8 位足以人工比对 */
const shortTrace = (t: string | null) => (t ? t.slice(0, 8) : '—')
</script>

<template>
  <div class="audit-page">
    <main class="audit-main">
      <!-- 页头 -->
      <header class="page-header">
        <div>
          <h1 class="page-title">使用日志</h1>
          <p class="page-sub">AI 调用与系统写操作的完整审计记录，可按 traceId 下钻完整链路</p>
        </div>
        <button class="btn-refresh" type="button" :disabled="loading" @click="load">
          <RefreshCw :size="15" :class="{ spinning: loading }" />
          刷新
        </button>
      </header>

      <!-- 分类 Tab -->
      <div class="tab-bar" role="tablist">
        <button
          v-for="t in TABS"
          :key="t.id"
          type="button"
          role="tab"
          class="tab-item"
          :class="{ active: activeTab === t.id }"
          :aria-selected="activeTab === t.id"
          @click="activeTab = t.id"
        >
          {{ t.label }}
        </button>
      </div>

      <!-- 统计徽章条（仅 AI 调用页；与列表同一套筛选条件） -->
      <div v-if="activeTab === 'ai-calls' && stats" class="stat-bar">
        <span class="stat-badge">
          <span class="stat-accent accent-blue" />
          <Activity :size="13" />
          <span class="stat-label">调用次数</span>
          <span class="stat-value">{{ stats.totalCalls }}</span>
        </span>
        <span class="stat-badge">
          <span class="stat-accent accent-green" />
          <Database :size="13" />
          <span class="stat-label">缓存命中</span>
          <span class="stat-value">{{ stats.cacheHits }}（{{ stats.cacheHitRate.toFixed(1) }}%）</span>
        </span>
        <span class="stat-badge">
          <span class="stat-accent accent-orange" />
          <Coins :size="13" />
          <span class="stat-label">总成本</span>
          <span class="stat-value">¥{{ stats.totalCost.toFixed(4) }}</span>
        </span>
        <span class="stat-badge">
          <span class="stat-accent accent-purple" />
          <Zap :size="13" />
          <span class="stat-label">平均耗时</span>
          <span class="stat-value">{{ stats.avgLatencyMs }} ms</span>
        </span>
      </div>

      <!-- 筛选栏 -->
      <div class="filter-bar">
        <template v-if="activeTab === 'ai-calls'">
          <select v-model="modelName" class="filter-control" @change="applyFilters">
            <option value="">全部模型</option>
            <option v-for="m in filterOptions.models" :key="m" :value="m">{{ m }}</option>
          </select>
          <select v-model="operationType" class="filter-control" @change="applyFilters">
            <option value="">全部操作类型</option>
            <option v-for="o in filterOptions.operationTypes" :key="o" :value="o">{{ o }}</option>
          </select>
          <select v-model="cachedFilter" class="filter-control" @change="applyFilters">
            <option value="">缓存不限</option>
            <option value="true">仅缓存命中</option>
            <option value="false">仅实际调用</option>
          </select>
          <input
            v-model="minLatency"
            type="number"
            min="0"
            class="filter-control"
            placeholder="最小耗时(ms)"
            @keyup.enter="applyFilters"
          />
        </template>

        <template v-else>
          <input
            v-model="actorId"
            class="filter-control"
            placeholder="操作者 ID"
            @keyup.enter="applyFilters"
          />
          <select v-model="actionPrefix" class="filter-control" @change="applyFilters">
            <option value="">全部操作</option>
            <option v-for="a in filterOptions.actions" :key="a" :value="a">{{ a }}</option>
          </select>
          <select v-model="targetType" class="filter-control" @change="applyFilters">
            <option value="">全部对象类型</option>
            <option v-for="tt in filterOptions.targetTypes" :key="tt" :value="tt">{{ tt }}</option>
          </select>
          <select v-model="successFilter" class="filter-control" @change="applyFilters">
            <option value="">成功与否不限</option>
            <option value="true">仅成功</option>
            <option value="false">仅失败</option>
          </select>
        </template>

        <div class="date-range">
          <span class="date-label">时间</span>
          <input v-model="dateFrom" type="date" class="filter-control" @change="applyFilters" />
          <span class="date-sep">–</span>
          <input v-model="dateTo" type="date" class="filter-control" @change="applyFilters" />
        </div>

        <button class="btn-apply" type="button" @click="applyFilters">
          <Search :size="14" /> 查询
        </button>
        <button v-if="hasFilters" class="btn-clear" type="button" @click="clearFilters">
          清除筛选
        </button>
      </div>

      <!-- 列表：四态统一交给 DataStateBoundary -->
      <DataStateBoundary
        :loading="loading"
        :error="loadError"
        :count="rowCount"
        :filtered="hasFilters"
        empty-description="暂无日志记录"
        filtered-description="当前筛选条件下没有记录，试试放宽条件或扩大时间范围"
        :skeleton-rows="8"
        @retry="load"
      >
        <div class="table-container">
          <!-- AI 调用日志 -->
          <el-table v-if="activeTab === 'ai-calls'" :data="aiCalls" border stripe row-key="id">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                <div class="cell-time">
                  <RelativeTime :value="row.create_time" />
                  <span class="cell-time-abs">{{ formatAbsolute(row.create_time) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="模型" width="150">
              <template #default="{ row }">
                <span class="badge-model">{{ row.model_name || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <span class="badge-op">{{ row.operation_type || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="缓存" width="80" align="center">
              <template #default="{ row }">
                <span :class="row.is_cached ? 'badge-hit' : 'badge-miss'">
                  {{ row.is_cached ? '命中' : '调用' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-num" :class="`latency-${latencyLevel(row.latency_ms)}`">
                  {{ fmtLatency(row.latency_ms) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="成本" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-num">{{ fmtCost(row.cost_rmb) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="提问预览" min-width="240">
              <template #default="{ row }">
                <span class="cell-preview">{{ row.query_preview || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="链路" width="120" fixed="right">
              <template #default="{ row }">
                <button
                  v-if="row.trace_id"
                  class="link-trace"
                  type="button"
                  :title="row.trace_id"
                  @click="openTrace(row.trace_id)"
                >
                  {{ shortTrace(row.trace_id) }}
                </button>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <!-- 操作审计 -->
          <el-table v-else :data="operations" border stripe row-key="id">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                <div class="cell-time">
                  <RelativeTime :value="row.create_time" />
                  <span class="cell-time-abs">{{ formatAbsolute(row.create_time) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作者" width="130">
              <template #default="{ row }">
                <span :class="{ 'actor-system': row.actor_id === 'SYSTEM' }">
                  {{ row.actor_name || row.actor_id || '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" min-width="180">
              <template #default="{ row }">
                <span class="badge-action">{{ row.action }}</span>
              </template>
            </el-table-column>
            <el-table-column label="对象" min-width="160">
              <template #default="{ row }">
                <span v-if="row.target_type" class="cell-target">
                  {{ row.target_type }}<span v-if="row.target_id">·{{ row.target_id }}</span>
                </span>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="100" align="center">
              <template #default="{ row }">
                <span :class="row.success ? 'badge-ok' : 'badge-fail'">
                  {{ row.success ? '成功' : `失败 ${row.status_code}` }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="90" align="right">
              <template #default="{ row }">
                <span class="cell-num">{{ row.duration_ms }} ms</span>
              </template>
            </el-table-column>
            <el-table-column label="来源 IP" width="130">
              <template #default="{ row }">
                <span class="cell-mono">{{ row.client_ip || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="链路" width="120" fixed="right">
              <template #default="{ row }">
                <button
                  v-if="row.trace_id"
                  class="link-trace"
                  type="button"
                  :title="row.trace_id"
                  @click="openTrace(row.trace_id)"
                >
                  {{ shortTrace(row.trace_id) }}
                </button>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </DataStateBoundary>

      <ServerPagination
        :current-page="currentPage"
        :total-pages="totalPages"
        :total="totalCount"
        :page-start="pageStart"
        :page-end="pageEnd"
        :page-numbers="pageNumbers"
        @page-change="goToPage"
      />
    </main>

    <!-- 链路详情：一次请求的 AI 调用 + 全部写操作 -->
    <el-dialog
      v-model="traceOpen"
      title="链路详情"
      width="min(860px, calc(100vw - 32px))"
      destroy-on-close
    >
      <div v-if="traceLoading" class="trace-loading">正在加载链路…</div>
      <div v-else-if="traceError" class="trace-error">
        无法加载该链路：该记录可能已过保留期
      </div>
      <div v-else-if="traceData" class="trace-body">
        <p class="trace-id">traceId：<code>{{ traceData.traceId }}</code></p>

        <section v-if="traceData.aiCall" class="trace-section">
          <h4 class="trace-h">AI 调用</h4>
          <dl class="trace-grid">
            <dt>模型</dt><dd>{{ traceData.aiCall.model_name || '—' }}</dd>
            <dt>缓存</dt><dd>{{ traceData.aiCall.is_cached ? '命中' : '实际调用' }}</dd>
            <dt>耗时</dt><dd>{{ fmtLatency(traceData.aiCall.latency_ms) }}</dd>
            <dt>成本</dt><dd>{{ fmtCost(traceData.aiCall.cost_rmb) }}</dd>
            <dt>时间</dt><dd>{{ formatAbsolute(traceData.aiCall.create_time) }}</dd>
          </dl>
          <div class="trace-qa">
            <p class="trace-qa-label">提问</p>
            <pre class="trace-pre">{{ traceData.aiCall.user_query || '（无）' }}</pre>
            <p class="trace-qa-label">回答</p>
            <pre class="trace-pre">{{ traceData.aiCall.agent_answer || '（无）' }}</pre>
          </div>
          <div v-if="traceCitations.length" class="trace-cites">
            <p class="trace-qa-label">引用来源</p>
            <ul>
              <li v-for="(c, i) in traceCitations" :key="i">{{ c }}</li>
            </ul>
          </div>
        </section>

        <section class="trace-section">
          <h4 class="trace-h">写操作（{{ traceData.operations.length }}）</h4>
          <p v-if="!traceData.operations.length" class="trace-empty">
            本次链路没有产生写操作
          </p>
          <ol v-else class="trace-ops">
            <li v-for="op in traceData.operations" :key="op.id">
              <span class="trace-op-time">{{ formatAbsolute(op.create_time) }}</span>
              <span class="badge-action">{{ op.action }}</span>
              <span v-if="op.target_id" class="cell-target">{{ op.target_type }}·{{ op.target_id }}</span>
              <span :class="op.success ? 'badge-ok' : 'badge-fail'">
                {{ op.success ? '成功' : '失败' }}
              </span>
              <span v-if="op.error_message" class="trace-op-err">{{ op.error_message }}</span>
            </li>
          </ol>
        </section>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.audit-page {
  min-height: 100vh;
  background: var(--color-bg);
}

.audit-main {
  max-width: 1440px;
  margin: 0 auto;
  padding: 24px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.page-title {
  margin: 0 0 4px;
  font-size: var(--text-xl);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.page-sub {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}

.btn-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
  flex-shrink: 0;

  &:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
  &:disabled { opacity: 0.6; cursor: not-allowed; }

  .spinning { animation: audit-spin 1s linear infinite; }
}

@keyframes audit-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ===== Tab ===== */
.tab-bar {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--color-border-light);
  margin-bottom: 16px;
}

.tab-item {
  padding: 9px 18px;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease;

  &:hover { color: var(--color-text-primary); }

  &.active {
    color: var(--color-primary);
    border-bottom-color: var(--color-primary);
    font-weight: var(--weight-medium);
  }
}

/* ===== 统计徽章条 ===== */
.stat-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.stat-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 11px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.stat-accent {
  width: 2px;
  height: 14px;
  border-radius: 999px;
  flex-shrink: 0;
}
.accent-blue { background: var(--color-primary); }
.accent-green { background: var(--state-success, #22c55e); }
.accent-orange { background: var(--state-warning, var(--warning)); }
.accent-purple { background: #8b5cf6; }

.stat-label { color: var(--color-text-tertiary); }

.stat-value {
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  font-variant-numeric: tabular-nums;
}

/* ===== 筛选栏 ===== */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  margin-bottom: 14px;
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.filter-control {
  height: 32px;
  min-width: 132px;
  padding: 0 9px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: var(--text-sm);
  font-family: var(--font-body);

  &:focus { outline: none; border-color: var(--color-primary); }
}

.date-range {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.date-label { font-size: var(--text-xs); color: var(--color-text-tertiary); }
.date-sep { color: var(--color-text-tertiary); }

.btn-apply,
.btn-clear {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 14px;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
}

.btn-apply {
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}

.btn-clear {
  border: 1px solid var(--color-border-light);
  background: var(--color-surface);
  color: var(--color-text-secondary);

  &:hover { border-color: var(--color-primary); color: var(--color-primary); }
}

/* ===== 表格 ===== */
.table-container {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  margin-bottom: 16px;
}

.cell-time { display: flex; flex-direction: column; gap: 1px; }
.cell-time-abs { font-size: 11px; color: var(--color-text-tertiary); }

.cell-num {
  font-variant-numeric: tabular-nums;
  font-family: var(--font-mono, monospace);
  font-size: var(--text-xs);
}

.cell-mono { font-family: var(--font-mono, monospace); font-size: var(--text-xs); }
.cell-muted { color: var(--color-text-tertiary); }

.cell-preview {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.cell-target { font-size: var(--text-xs); color: var(--color-text-secondary); }

/* 慢调用要一眼看出来——这是运维查日志的主要目的之一 */
.latency-slow { color: var(--state-error, var(--danger)); font-weight: var(--weight-semibold); }
.latency-fast { color: var(--state-success, #22c55e); }

.badge-model,
.badge-op,
.badge-action {
  display: inline-block;
  padding: 1px 7px;
  border-radius: var(--radius-sm);
  background: var(--color-bg-sunken, var(--surface-2));
  color: var(--color-text-secondary);
  font-size: 11px;
  font-family: var(--font-mono, monospace);
}

.badge-hit,
.badge-ok {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--state-success-bg, #dcfce7);
  color: var(--state-success, #16a34a);
  font-size: 11px;
}

.badge-miss {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-bg-sunken, var(--surface-2));
  color: var(--color-text-tertiary);
  font-size: 11px;
}

.badge-fail {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--state-error-bg, var(--danger-subtle));
  color: var(--state-error, var(--danger));
  font-size: 11px;
}

/* SYSTEM 操作者视觉上区分开——「是人做的还是定时任务做的」是排查第一问 */
.actor-system {
  font-family: var(--font-mono, monospace);
  font-size: var(--text-xs);
  color: #8b5cf6;
}

.link-trace {
  border: none;
  background: transparent;
  color: var(--color-primary);
  font-family: var(--font-mono, monospace);
  font-size: var(--text-xs);
  cursor: pointer;
  padding: 2px 4px;
  border-radius: var(--radius-sm);

  &:hover { background: var(--color-primary-lighter); text-decoration: underline; }
}

/* ===== 链路详情 ===== */
.trace-loading,
.trace-error,
.trace-empty {
  padding: 24px;
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--text-sm);
}

.trace-error { color: var(--state-error, var(--danger)); }

.trace-id {
  margin: 0 0 14px;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);

  code {
    font-family: var(--font-mono, monospace);
    background: var(--color-bg-sunken, var(--surface-2));
    padding: 2px 6px;
    border-radius: var(--radius-sm);
  }
}

.trace-section { margin-bottom: 20px; }

.trace-h {
  margin: 0 0 10px;
  font-size: var(--text-base);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.trace-grid {
  display: grid;
  grid-template-columns: auto 1fr auto 1fr;
  gap: 6px 12px;
  margin: 0 0 14px;
  font-size: var(--text-sm);

  dt { color: var(--color-text-tertiary); }
  dd { margin: 0; color: var(--color-text-primary); }
}

.trace-qa-label {
  margin: 10px 0 4px;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

.trace-pre {
  margin: 0;
  padding: 10px 12px;
  max-height: 200px;
  overflow: auto;
  background: var(--color-bg-sunken, var(--surface-2));
  border-radius: var(--radius-sm);
  font-family: var(--font-mono, monospace);
  font-size: var(--text-xs);
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-primary);
}

.trace-cites ul {
  margin: 0;
  padding-left: 18px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.trace-ops {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;

  li {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    font-size: var(--text-xs);
  }
}

.trace-op-time {
  font-family: var(--font-mono, monospace);
  color: var(--color-text-tertiary);
}

.trace-op-err { color: var(--state-error, var(--danger)); }

@media (max-width: 768px) {
  .audit-main { padding: 16px; }
  .trace-grid { grid-template-columns: auto 1fr; }
}
</style>
