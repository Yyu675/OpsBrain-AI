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
import { Bot, Link2, ListChecks, RefreshCw, Rows3, Search, Sparkles, X } from 'lucide-vue-next'

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
import AgentTraceDrawer from '@/components/ai/AgentTraceDrawer.vue'
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

/**
 * 紧凑行高开关（对齐 new-api 的行高切换）。
 *
 * 日志类页面「一屏能看多少行」直接决定排查效率——出故障时用户要快速扫过
 * 上百条记录找异常。紧凑模式下隐藏绝对时间的第二行，行高 52 → 40px。
 */
const dense = ref(false)

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

/**
 * 演示数据（仅 dev + `?demo=1` 时启用）。
 *
 * 目的很具体：本页依赖后端 `/api/v1/audit/**`，而前端开发/设计走查时
 * 后端未必在跑。没有它就只能看到空态，无法验证表格密度、色彩层级、
 * 长文本截断这些**只有在真实数据下才暴露**的视觉问题。
 *
 * 三重保险确保它绝不会进生产：
 *   1. `import.meta.env.DEV` —— 生产构建时该分支被 Vite 静态移除
 *   2. 必须显式传 `?demo=1`，正常开发访问仍走真实接口
 *   3. 只读，不写任何状态
 */
const DEMO_MODELS = ['qwen-max', 'qwen-plus', 'deepseek-chat']
const DEMO_ACTIONS = [
  'ticket.create', 'ticket.status.update', 'ticket.assign',
  'knowledge.doc.publish', 'knowledge.doc.delete', 'approval.approve',
]

const buildDemoAiCalls = (): AiCallLogItem[] =>
  Array.from({ length: 12 }, (_, i) => {
    const cached = i % 4 === 1
    return {
      id: 1000 - i,
      trace_id: `${(0x5f2a1b3c + i * 7919).toString(16)}9d4e0a71`,
      model_name: DEMO_MODELS[i % DEMO_MODELS.length],
      is_cached: cached,
      latency_ms: cached ? 12 + i : [420, 1180, 6400, 880, 2300][i % 5],
      cost_rmb: cached ? 0 : Number((0.0012 * (i + 1)).toFixed(4)),
      operation_type: cached ? 'CACHE_HIT' : i % 5 === 0 ? 'CREATE_TICKET' : 'CHAT',
      operator_id: i % 6 === 0 ? 'SYSTEM' : `u-100${i % 3}`,
      affected_resources: i % 5 === 0 ? '["TKT-20260824-000' + i + '"]' : null,
      query_preview: [
        '生产环境 Redis 主从复制延迟持续升高，seconds_behind_master 已达 300 秒，需要排查',
        'K8s Pod 频繁 OOMKilled 怎么定位是内存泄漏还是 limit 设置过低',
        '帮我开个工单：MySQL 慢查询导致订单接口超时，优先级 HIGH',
        'Nginx 502 Bad Gateway 突增，上游健康检查正常，可能是什么原因',
      ][i % 4],
      create_time: `2026-08-24 ${String(15 - Math.floor(i / 3)).padStart(2, '0')}:${String(59 - i * 4).padStart(2, '0')}:00`,
    }
  })

const buildDemoOperations = (): OperationAuditItem[] =>
  Array.from({ length: 12 }, (_, i) => {
    const ok = i % 5 !== 2
    return {
      id: 2000 - i,
      trace_id: `${(0x5f2a1b3c + i * 7919).toString(16)}9d4e0a71`,
      actor_id: i % 4 === 0 ? 'SYSTEM' : `u-100${i % 3}`,
      actor_name: i % 4 === 0 ? null : ['张明', '李强', '王雪'][i % 3],
      action: DEMO_ACTIONS[i % DEMO_ACTIONS.length],
      target_type: i % 3 === 0 ? 'KNOWLEDGE' : 'TICKET',
      target_id: i % 3 === 0 ? `DOC-${420 + i}` : `TKT-20260824-00${i}`,
      http_method: ['POST', 'PATCH', 'DELETE', 'PUT'][i % 4],
      http_path: '/api/v1/tickets',
      status_code: ok ? 200 : [409, 403, 500][i % 3],
      success: ok,
      biz_code: ok ? null : 40009,
      error_message: ok ? null : '数据已被他人修改，请刷新后重试',
      client_ip: `10.20.${i % 5}.${100 + i}`,
      duration_ms: 18 + i * 7,
      create_time: `2026-08-24 ${String(15 - Math.floor(i / 3)).padStart(2, '0')}:${String(58 - i * 4).padStart(2, '0')}:00`,
    }
  })

/** 是否处于演示模式。生产构建下 import.meta.env.DEV 为 false，整个分支被移除 */
const demoMode = import.meta.env.DEV
  && new URLSearchParams(window.location.search).get('demo') === '1'

/** 请求序号防竞态：快速切 Tab 时先发的慢响应不得覆盖后发的 */
let requestSeq = 0

/**
 * URL 里的页码越界时，夹回末页并重拉一次。
 *
 * 为什么放在页面而不是 useServerPagination：那里有明确契约
 * 「setMeta 不改动当前页 —— 翻页与元信息更新是两件事」，
 * 并有测试守着。在 setMeta 里偷偷改页码会破坏这条契约
 * （我第一版就是这么写的，直接挂了两条既有用例）。
 *
 * 越界从哪来：`?page=` 出自分享的链接或手工编辑，而「第几页有效」
 * 只有拿到后端 totalPages 才知道。不处理的话 `?page=9999` 会停在
 * 一个空白页，页码按钮还全部失效（当前页不在窗口内），用户被困住。
 *
 * @returns true 表示已触发重拉，调用方应立即返回避免重复处理
 */
const retryIfPageOutOfRange = async (totalPagesFromServer: number, rowCountNow: number) => {
  const maxPage = Math.max(1, totalPagesFromServer)
  if (currentPage.value <= maxPage || rowCountNow > 0) return false
  currentPage.value = maxPage
  await load()
  return true
}

const load = async () => {
  const seq = ++requestSeq
  loading.value = true
  loadError.value = null

  if (demoMode) {
    aiCalls.value = buildDemoAiCalls()
    operations.value = buildDemoOperations()
    stats.value = { totalCalls: 1284, cacheHits: 391, cacheHitRate: 30.5, totalCost: 12.8734, avgLatencyMs: 1420 }
    filterOptions.value = {
      models: DEMO_MODELS,
      operationTypes: ['CHAT', 'CACHE_HIT', 'CREATE_TICKET'],
      actions: DEMO_ACTIONS,
      targetTypes: ['TICKET', 'KNOWLEDGE'],
    }
    pagination.setMeta({ total: 1284, totalPages: 65 })
    loading.value = false
    return
  }

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
      if (await retryIfPageOutOfRange(res.totalPages, res.items.length)) return
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
      if (await retryIfPageOutOfRange(res.totalPages, res.items.length)) return
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

/**
 * 切 Tab：清掉**上一个 Tab 专属**的筛选，再重置分页重新拉取。
 *
 * 为什么必须清：两个 Tab 的筛选维度完全不同，而它们共用同一批 ref。
 * 原实现只 resetPage：在 AI 调用页筛了 `model=qwen-max` 后切到操作审计，
 * modelName 仍是 'qwen-max' 且会被 load() 拼进请求——
 * 但操作审计的筛选栏根本没有「模型」这个控件，hasFilters 也算不上它，
 * 于是「清除筛选」按钮不显示。
 *
 * 结果是用户看到一份被悄悄过滤过的列表，界面上却没有任何筛选生效的迹象，
 * 而且无从清除。这类「隐形筛选」比报错更难排查——数据少了但没人知道为什么。
 *
 * 时间范围（dateFrom/dateTo）是两个 Tab 共有的语义，刻意保留：
 * 用户查「今天」的 AI 调用后切去看「今天」的操作审计，是合理预期。
 */
watch(activeTab, async (tab) => {
  if (tab === 'operations') {
    modelName.value = ''
    operationType.value = ''
    cachedFilter.value = ''
    minLatency.value = ''
  } else {
    actorId.value = ''
    actionPrefix.value = ''
    targetType.value = ''
    successFilter.value = ''
  }
  pagination.resetPage()
  await load()
})

onMounted(async () => {
  if (demoMode) { await load(); return }
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

  if (demoMode) {
    traceData.value = {
      traceId,
      aiCall: {
        id: 1, trace_id: traceId,
        user_query: '生产环境 Redis 主从复制延迟持续升高，seconds_behind_master 已达 300 秒，需要排查',
        agent_answer: '建议按以下顺序排查：\n1. 检查从库 IO/SQL 线程状态\n2. 查看主库 binlog 写入速率\n3. 确认网络带宽是否打满\n\n【来源：Redis 主从延迟处置 SOP】',
        model_name: 'qwen-max', is_cached: false, latency_ms: 2340, cost_rmb: 0.0186,
        citations: '["Redis 主从延迟处置 SOP 第 3 节","中间件容量基线 v2"]',
        operation_type: 'CREATE_TICKET', affected_resources: '["TKT-20260824-0007"]',
        operator_id: 'u-1001', create_time: '2026-08-24 15:12:00',
      },
      operations: [
        { id: 1, trace_id: traceId, actor_id: 'u-1001', actor_name: '张明', action: 'ticket.create',
          target_type: 'TICKET', target_id: 'TKT-20260824-0007', http_method: 'POST',
          http_path: '/api/v1/tickets', status_code: 200, success: true, biz_code: null,
          error_message: null, client_ip: '10.20.1.104', duration_ms: 86,
          create_time: '2026-08-24 15:12:01' },
        { id: 2, trace_id: traceId, actor_id: 'SYSTEM', actor_name: null, action: 'ticket.assign',
          target_type: 'TICKET', target_id: 'TKT-20260824-0007', http_method: 'PATCH',
          http_path: '/api/v1/tickets/TKT-20260824-0007/assignee', status_code: 200, success: true,
          biz_code: null, error_message: null, client_ip: '127.0.0.1', duration_ms: 24,
          create_time: '2026-08-24 15:12:02' },
        { id: 3, trace_id: traceId, actor_id: 'u-1002', actor_name: '李强', action: 'ticket.status.update',
          target_type: 'TICKET', target_id: 'TKT-20260824-0007', http_method: 'PATCH',
          http_path: '/api/v1/tickets/TKT-20260824-0007/status', status_code: 409, success: false,
          biz_code: 40009, error_message: '数据已被他人修改，请刷新后重试',
          client_ip: '10.20.2.117', duration_ms: 41, create_time: '2026-08-24 15:13:40' },
      ],
    }
    traceLoading.value = false
    return
  }

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

// ==================== 执行轨迹（状态机） ====================

/**
 * 执行轨迹抽屉。
 *
 * 与本页的「链路下钻」是互补的两层，刻意做成两个入口而非合并：
 * - 链路下钻查的是**落库**的审计记录（AI 问答原文、成本、写操作），长期保留；
 * - 执行轨迹查的是**内存**里的状态机迁移（走到哪一步、每段多久、卡在哪），
 *   30 分钟空闲即被清理。
 *
 * 合并成一个面板会给人「数据同源、同样可靠」的错觉——
 * 而实际上老链路必然查不到轨迹，那时展示一片空白反而像是出了故障。
 * 分开之后，「轨迹已过期」是用户点进去才会遇到的、有明确解释的状态。
 *
 * 权限：本页路由已限 roles: ['admin']，后端 AgentTraceController 也标了
 * @SaCheckRole("ADMIN")。因此能走到这个按钮的必然是管理员，
 * 入口无需再做一次角色判断。
 */
const agentTraceOpen = ref(false)
const agentTraceId = ref<string | null>(null)

const openAgentTrace = (traceId: string | null) => {
  if (!traceId) return
  agentTraceId.value = traceId
  agentTraceOpen.value = true
}

/** 短 traceId：全长 32 位在表格里挤占过多宽度，前 8 位足以人工比对 */
const shortTrace = (t: string | null) => (t ? t.slice(0, 8) : '—')
</script>
<template>
  <div class="audit-page">
    <main class="audit-main">
      <!-- ===== 页头：标题 + 右侧操作（对齐 new-api SectionPageLayout） ===== -->
      <header class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{ activeTab === 'ai-calls' ? 'AI 调用日志' : '操作审计' }}</h1>
          <p class="page-sub">AI 调用与系统写操作的完整审计记录，可按 traceId 下钻完整链路</p>
        </div>
        <div class="header-actions">
          <button class="icon-btn" type="button" :title="dense ? '切换为宽松行高' : '切换为紧凑行高'" @click="dense = !dense">
            <Rows3 :size="15" />
          </button>
          <button class="icon-btn" type="button" :disabled="loading" title="刷新" @click="load">
            <RefreshCw :size="15" :class="{ spinning: loading }" />
          </button>
        </div>
      </header>

      <!-- ===== 分段控件式 Tab（new-api 用 shadcn Tabs：胶囊底 + 白色滑块） ===== -->
      <div class="segmented" role="tablist">
        <button
          v-for="t in TABS"
          :key="t.id"
          type="button"
          role="tab"
          class="segmented-item"
          :class="{ active: activeTab === t.id }"
          :aria-selected="activeTab === t.id"
          @click="activeTab = t.id"
        >
          {{ t.label }}
        </button>
      </div>

      <!-- ===== 统计徽章条：h-7 胶囊 + 左侧竖色条 + 等宽数字 ===== -->
      <div v-if="activeTab === 'ai-calls'" class="stat-bar">
        <template v-if="stats">
          <span class="stat-badge">
            <span class="stat-accent accent-1" />
            <span class="stat-label">调用</span>
            <span class="stat-value">{{ stats.totalCalls }}</span>
          </span>
          <span class="stat-badge">
            <span class="stat-accent accent-5" />
            <span class="stat-label">命中</span>
            <span class="stat-value">{{ stats.cacheHits }} · {{ stats.cacheHitRate.toFixed(1) }}%</span>
          </span>
          <span class="stat-badge">
            <span class="stat-accent accent-4" />
            <span class="stat-label">成本</span>
            <span class="stat-value">¥{{ stats.totalCost.toFixed(4) }}</span>
          </span>
          <span class="stat-badge">
            <span class="stat-accent accent-3" />
            <span class="stat-label">均耗时</span>
            <span class="stat-value">{{ stats.avgLatencyMs }}ms</span>
          </span>
        </template>
        <!-- 加载中用同尺寸骨架，避免统计条出现时把下方内容顶下去 -->
        <template v-else-if="loading">
          <span v-for="n in 4" :key="n" class="stat-badge stat-badge--skeleton" />
        </template>
      </div>

      <!-- ===== 筛选工具栏：auto-fit 网格 + 右侧动作区 ===== -->
      <div class="filter-toolbar">
        <div class="filter-grid">
          <template v-if="activeTab === 'ai-calls'">
            <select v-model="modelName" class="ctl" @change="applyFilters">
              <option value="">全部模型</option>
              <option v-for="m in filterOptions.models" :key="m" :value="m">{{ m }}</option>
            </select>
            <select v-model="operationType" class="ctl" @change="applyFilters">
              <option value="">全部类型</option>
              <option v-for="o in filterOptions.operationTypes" :key="o" :value="o">{{ o }}</option>
            </select>
            <select v-model="cachedFilter" class="ctl" @change="applyFilters">
              <option value="">缓存不限</option>
              <option value="true">仅命中</option>
              <option value="false">仅实调</option>
            </select>
            <input
              v-model="minLatency"
              type="number"
              min="0"
              class="ctl"
              placeholder="最小耗时 ms"
              @keyup.enter="applyFilters"
            />
          </template>

          <template v-else>
            <input v-model="actorId" class="ctl" placeholder="操作者 ID" @keyup.enter="applyFilters" />
            <select v-model="actionPrefix" class="ctl" @change="applyFilters">
              <option value="">全部操作</option>
              <option v-for="a in filterOptions.actions" :key="a" :value="a">{{ a }}</option>
            </select>
            <select v-model="targetType" class="ctl" @change="applyFilters">
              <option value="">全部对象</option>
              <option v-for="tt in filterOptions.targetTypes" :key="tt" :value="tt">{{ tt }}</option>
            </select>
            <select v-model="successFilter" class="ctl" @change="applyFilters">
              <option value="">成败不限</option>
              <option value="true">仅成功</option>
              <option value="false">仅失败</option>
            </select>
          </template>

          <div class="ctl-range">
            <input v-model="dateFrom" type="date" class="ctl ctl-date" @change="applyFilters" />
            <span class="range-sep">–</span>
            <input v-model="dateTo" type="date" class="ctl ctl-date" @change="applyFilters" />
          </div>
        </div>

        <div class="filter-actions">
          <button v-if="hasFilters" class="btn-ghost" type="button" @click="clearFilters">
            <X :size="13" /> 清除
          </button>
          <button class="btn-primary-sm" type="button" @click="applyFilters">
            <Search :size="13" /> 查询
          </button>
        </div>
      </div>

      <!-- ===== 表格 ===== -->
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
        <div class="table-card" :class="{ 'is-dense': dense }">
          <!-- AI 调用日志 -->
          <el-table
            v-if="activeTab === 'ai-calls'"
            :data="aiCalls"
            row-key="id"
            :row-class-name="() => 'log-row'"
          >
            <el-table-column label="时间" width="128">
              <template #default="{ row }">
                <div class="c-time">
                  <span class="c-time-rel"><RelativeTime :value="row.create_time" /></span>
                  <span class="c-time-abs">{{ formatAbsolute(row.create_time) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="模型" width="152">
              <template #default="{ row }">
                <span class="tag-model">{{ row.model_name || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="104">
              <template #default="{ row }">
                <span class="tag-soft">{{ row.operation_type || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="缓存" width="76" align="center">
              <template #default="{ row }">
                <span class="dot-tag" :class="row.is_cached ? 'is-hit' : 'is-miss'">
                  <i class="dot" />{{ row.is_cached ? '命中' : '实调' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="92" align="right">
              <template #default="{ row }">
                <span class="num" :class="`lat-${latencyLevel(row.latency_ms)}`">
                  {{ fmtLatency(row.latency_ms) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="成本" width="96" align="right">
              <template #default="{ row }">
                <span class="num num-cost">{{ fmtCost(row.cost_rmb) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="提问" min-width="260">
              <template #default="{ row }">
                <span class="c-preview">{{ row.query_preview || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="" width="104" align="right" fixed="right">
              <template #default="{ row }">
                <button
                  v-if="row.trace_id"
                  class="trace-chip"
                  type="button"
                  :title="`查看链路 ${row.trace_id}`"
                  @click="openTrace(row.trace_id)"
                >
                  <Link2 :size="11" />{{ shortTrace(row.trace_id) }}
                </button>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <!-- 操作审计 -->
          <el-table v-else :data="operations" row-key="id" :row-class-name="() => 'log-row'">
            <el-table-column label="时间" width="128">
              <template #default="{ row }">
                <div class="c-time">
                  <span class="c-time-rel"><RelativeTime :value="row.create_time" /></span>
                  <span class="c-time-abs">{{ formatAbsolute(row.create_time) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作者" width="132">
              <template #default="{ row }">
                <span v-if="row.actor_id === 'SYSTEM'" class="tag-system">
                  <Bot :size="11" /> SYSTEM
                </span>
                <span v-else class="c-actor">{{ row.actor_name || row.actor_id || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" min-width="190">
              <template #default="{ row }">
                <span class="tag-action">{{ row.action }}</span>
              </template>
            </el-table-column>
            <el-table-column label="对象" min-width="160">
              <template #default="{ row }">
                <span v-if="row.target_type" class="c-target">
                  <span class="tag-soft">{{ row.target_type }}</span>
                  <span v-if="row.target_id" class="c-target-id">{{ row.target_id }}</span>
                </span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="96" align="center">
              <template #default="{ row }">
                <span class="dot-tag" :class="row.success ? 'is-ok' : 'is-fail'">
                  <i class="dot" />{{ row.success ? '成功' : row.status_code }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="86" align="right">
              <template #default="{ row }">
                <span class="num">{{ row.duration_ms }}ms</span>
              </template>
            </el-table-column>
            <el-table-column label="来源 IP" width="124">
              <template #default="{ row }">
                <span class="mono muted">{{ row.client_ip || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="" width="104" align="right" fixed="right">
              <template #default="{ row }">
                <button
                  v-if="row.trace_id"
                  class="trace-chip"
                  type="button"
                  :title="`查看链路 ${row.trace_id}`"
                  @click="openTrace(row.trace_id)"
                >
                  <Link2 :size="11" />{{ shortTrace(row.trace_id) }}
                </button>
                <span v-else class="muted">—</span>
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

    <!-- ===== 链路详情 ===== -->
    <el-dialog
      v-model="traceOpen"
      title="链路详情"
      width="min(880px, calc(100vw - 32px))"
      destroy-on-close
      class="trace-dialog"
    >
      <div v-if="traceLoading" class="trace-state">正在加载链路…</div>
      <div v-else-if="traceError" class="trace-state is-error">
        无法加载该链路：记录可能已过保留期
      </div>
      <div v-else-if="traceData" class="trace-body">
        <div class="trace-head">
          <span class="trace-head-label">traceId</span>
          <code class="trace-head-id">{{ traceData.traceId }}</code>
          <!--
            执行轨迹是另一层数据（内存状态机，30 分钟过期），
            与本面板的落库记录互补。做成按钮而非直接展开：
            老链路必然查不到轨迹，默认展开会让空白面板看起来像故障。
          -->
          <button class="trace-head-action" type="button" @click="openAgentTrace(traceData.traceId)">
            查看执行轨迹
          </button>
        </div>

        <section v-if="traceData.aiCall" class="trace-block">
          <h4 class="trace-h"><Sparkles :size="13" /> AI 调用</h4>
          <div class="kv-grid">
            <div class="kv"><span class="k">模型</span><span class="v">{{ traceData.aiCall.model_name || '—' }}</span></div>
            <div class="kv"><span class="k">缓存</span><span class="v">{{ traceData.aiCall.is_cached ? '命中' : '实际调用' }}</span></div>
            <div class="kv"><span class="k">耗时</span><span class="v num">{{ fmtLatency(traceData.aiCall.latency_ms) }}</span></div>
            <div class="kv"><span class="k">成本</span><span class="v num">{{ fmtCost(traceData.aiCall.cost_rmb) }}</span></div>
            <div class="kv"><span class="k">时间</span><span class="v">{{ formatAbsolute(traceData.aiCall.create_time) }}</span></div>
          </div>
          <p class="qa-label">提问</p>
          <pre class="qa-pre">{{ traceData.aiCall.user_query || '（无）' }}</pre>
          <p class="qa-label">回答</p>
          <pre class="qa-pre">{{ traceData.aiCall.agent_answer || '（无）' }}</pre>
          <template v-if="traceCitations.length">
            <p class="qa-label">引用来源</p>
            <ul class="cite-list">
              <li v-for="(c, i) in traceCitations" :key="i">{{ c }}</li>
            </ul>
          </template>
        </section>

        <section class="trace-block">
          <h4 class="trace-h"><ListChecks :size="13" /> 写操作 · {{ traceData.operations.length }}</h4>
          <p v-if="!traceData.operations.length" class="trace-state">本次链路没有产生写操作</p>
          <ol v-else class="op-timeline">
            <li v-for="op in traceData.operations" :key="op.id" class="op-item">
              <span class="op-dot" :class="op.success ? 'is-ok' : 'is-fail'" />
              <div class="op-content">
                <div class="op-line">
                  <span class="tag-action">{{ op.action }}</span>
                  <span v-if="op.target_id" class="c-target-id">{{ op.target_type }}·{{ op.target_id }}</span>
                  <span class="dot-tag" :class="op.success ? 'is-ok' : 'is-fail'">
                    <i class="dot" />{{ op.success ? '成功' : `失败 ${op.status_code}` }}
                  </span>
                </div>
                <div class="op-meta">
                  <span class="mono">{{ formatAbsolute(op.create_time) }}</span>
                  <span class="num">{{ op.duration_ms }}ms</span>
                  <span v-if="op.error_message" class="op-err">{{ op.error_message }}</span>
                </div>
              </div>
            </li>
          </ol>
        </section>
      </div>
    </el-dialog>

    <!-- 执行轨迹：状态机迁移时间轴（内存数据，与上方落库审计互补） -->
    <AgentTraceDrawer v-model:visible="agentTraceOpen" :trace-id="agentTraceId" />
  </div>
</template>

<style scoped lang="scss">
/**
 * 视觉还原自 new-api 的 usage-logs（shadcn/Tailwind 体系），
 * 用本项目的 SCSS + 设计令牌实现，未引入 Tailwind 或其组件库。
 *
 * 对齐的关键视觉特征（取自其 theme.css 与组件 class）：
 *   - 圆角基准 1rem，控件用 md(≈8px)、卡片用 lg(≈12px)
 *   - 控件统一 h-7 / h-8（28/32px），比 Element Plus 默认更紧凑
 *   - 表头 bg 为「前景色 1.5% 混入背景」——极淡的灰，不是常见的深灰条
 *   - 边框统一 border/60~70 透明度，视觉更轻
 *   - 数字一律 tabular-nums 等宽，列表滚动时不跳动
 *   - 徽章用「左侧 2px 竖色条 + 淡底」而非实心色块
 */

.audit-page {
  min-height: 100vh;
  background: var(--color-bg);
}

.audit-main {
  max-width: 1520px;
  margin: 0 auto;
  padding: 20px 24px 32px;
}

/* ===== 页头 ===== */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-title {
  margin: 0 0 3px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--color-text-primary);
}

.page-sub {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-tertiary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

/* 图标按钮：size-8 方形，hover 才显底色（对齐 new-api 的 ghost button） */
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;

  &:hover:not(:disabled) {
    background: var(--color-bg-sunken, var(--surface-2));
    color: var(--color-text-primary);
  }
  &:disabled { opacity: 0.5; cursor: not-allowed; }

  .spinning { animation: audit-spin 1s linear infinite; }
}

@keyframes audit-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ===== 分段控件 Tab（胶囊底 + 白色滑块，shadcn Tabs 的形态）===== */
.segmented {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  margin-bottom: 14px;
  border-radius: var(--radius-md);
  background: var(--color-bg-sunken, var(--surface-2));
}

.segmented-item {
  padding: 5px 14px;
  border: none;
  border-radius: calc(var(--radius-md) - 2px);
  background: transparent;
  color: var(--color-text-tertiary);
  font-size: 13px;
  font-weight: 500;
  font-family: var(--font-body);
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;

  &:hover:not(.active) { color: var(--color-text-primary); }

  &.active {
    background: var(--color-surface);
    color: var(--color-text-primary);
    box-shadow: 0 1px 2px rgb(0 0 0 / 6%);
  }
}

/* ===== 统计徽章条（h-7 胶囊 + 左侧竖色条）===== */
.stat-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  min-height: 28px;
}

.stat-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: color-mix(in oklab, var(--color-bg-sunken, var(--surface-2)) 45%, transparent);
  font-size: 12px;
  box-shadow: 0 1px 1px rgb(0 0 0 / 3%);
}

.stat-badge--skeleton {
  width: 118px;
  border-style: dashed;
  opacity: 0.5;
}

.stat-accent {
  width: 2px;
  height: 14px;
  border-radius: 999px;
  flex-shrink: 0;
}

/* 取自 new-api 的 chart-1..5 色相序列，用本项目令牌兜底 */
.accent-1 { background: oklch(0.72 0.18 250); }
.accent-3 { background: oklch(0.70 0.12 280); }
.accent-4 { background: oklch(0.68 0.19 325); }
.accent-5 { background: oklch(0.68 0.16 155); }

.stat-label { color: var(--color-text-tertiary); }

.stat-value {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-primary);
}

/* ===== 筛选工具栏 ===== */
.filter-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;
}

/* auto-fit 网格：窄屏自动换行，宽屏均分——对齐 new-api 的
   grid-cols-[repeat(auto-fit,minmax(10rem,1fr))] */
.filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(9.5rem, 1fr));
  gap: 8px;
  flex: 1 1 auto;
  min-width: 0;
}

.ctl {
  height: 32px;
  width: 100%;
  min-width: 0;
  padding: 0 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: 13px;
  font-family: var(--font-body);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;

  &::placeholder { color: var(--color-text-tertiary); }

  &:focus {
    outline: none;
    border-color: var(--color-primary);
    box-shadow: 0 0 0 3px color-mix(in oklab, var(--color-primary) 14%, transparent);
  }
}

.ctl-range {
  display: flex;
  align-items: center;
  gap: 5px;
  grid-column: span 2;
  min-width: 0;
}

.ctl-date { flex: 1 1 0; }

.range-sep {
  color: var(--color-text-tertiary);
  flex-shrink: 0;
  font-size: 12px;
}

.filter-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.btn-ghost,
.btn-primary-sm {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 13px;
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 500;
  font-family: var(--font-body);
  cursor: pointer;
  white-space: nowrap;
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.btn-ghost {
  border: 1px solid var(--color-border-light);
  background: var(--color-surface);
  color: var(--color-text-secondary);

  &:hover { border-color: var(--color-primary); color: var(--color-primary); }
}

.btn-primary-sm {
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: #fff;

  &:hover { filter: brightness(1.06); }
}

/* ===== 表格卡片 ===== */
.table-card {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--color-surface);
  margin-bottom: 14px;

  /* 表头：前景色极淡混入背景（new-api 的 --table-header），
     不是常见的深灰条——整体更轻 */
  :deep(.el-table__header th.el-table__cell) {
    background: color-mix(in oklab, var(--color-text-primary) 3%, var(--color-surface));
    border-bottom: 1px solid var(--color-border-light);
    padding: 0;
    height: 38px;
    font-size: 12px;
    font-weight: 500;
    color: var(--color-text-tertiary);
  }

  :deep(.el-table__header th.el-table__cell > .cell) {
    padding-left: 14px;
    padding-right: 14px;
    line-height: 1.4;
  }

  :deep(.el-table td.el-table__cell) {
    padding: 0;
    height: 52px;
    border-bottom: 1px solid color-mix(in oklab, var(--color-border-light) 60%, transparent);
  }

  :deep(.el-table td.el-table__cell > .cell) {
    padding-left: 14px;
    padding-right: 14px;
  }

  /* 紧凑模式：对齐 new-api 的行高切换 */
  &.is-dense :deep(.el-table td.el-table__cell) { height: 40px; }
  &.is-dense .c-time-abs { display: none; }

  :deep(.el-table__row:hover > td.el-table__cell) {
    background: color-mix(in oklab, var(--color-primary) 4%, transparent);
  }

  :deep(.el-table::before) { display: none; }
  :deep(.el-table) { --el-table-border-color: transparent; }
}

/* ===== 单元格 ===== */
.c-time {
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.35;
}

.c-time-rel { font-size: 13px; color: var(--color-text-primary); }

.c-time-abs {
  font-size: 11px;
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.num {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-variant-numeric: tabular-nums;
  font-size: 12.5px;
  color: var(--color-text-primary);
}

.num-cost { color: var(--color-text-secondary); }

.mono {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}

.muted { color: var(--color-text-tertiary); }

/* 慢调用红色加粗——运维查日志的首要目的之一就是找慢请求 */
.lat-slow { color: var(--state-error, var(--danger)); font-weight: 600; }
.lat-fast { color: oklch(0.60 0.145 163); }

.c-preview {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 12.5px;
  line-height: 1.45;
  color: var(--color-text-secondary);
}

.c-actor { font-size: 13px; color: var(--color-text-primary); }

.c-target { display: inline-flex; align-items: center; gap: 6px; }

.c-target-id {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11.5px;
  color: var(--color-text-secondary);
}

/* ===== 标签 ===== */
.tag-model,
.tag-action,
.tag-soft {
  display: inline-block;
  max-width: 100%;
  padding: 2px 7px;
  border-radius: var(--radius-sm);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11.5px;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-model {
  background: color-mix(in oklab, oklch(0.72 0.18 250) 12%, transparent);
  color: oklch(0.48 0.16 250);
}

.tag-action {
  background: var(--color-bg-sunken, var(--surface-2));
  color: var(--color-text-primary);
}

.tag-soft {
  background: var(--color-bg-sunken, var(--surface-2));
  color: var(--color-text-tertiary);
}

/* SYSTEM 单独配色：「是人做的还是定时任务做的」是排查第一问 */
.tag-system {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  border-radius: var(--radius-sm);
  background: color-mix(in oklab, oklch(0.70 0.12 280) 14%, transparent);
  color: oklch(0.48 0.14 280);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11.5px;
}

/* 状态点标签：小圆点 + 文字，比纯色块克制 */
.dot-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;

  .dot {
    width: 5px;
    height: 5px;
    border-radius: 999px;
    background: currentColor;
    flex-shrink: 0;
  }

  &.is-hit,
  &.is-ok {
    background: color-mix(in oklab, oklch(0.60 0.145 163) 13%, transparent);
    color: oklch(0.45 0.13 163);
  }

  &.is-miss {
    background: var(--color-bg-sunken, var(--surface-2));
    color: var(--color-text-tertiary);
  }

  &.is-fail {
    background: color-mix(in oklab, var(--danger) 13%, transparent);
    color: var(--danger);
  }
}

/* trace 芯片：等宽短码 + 链接图标 */
.trace-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
    background: color-mix(in oklab, var(--color-primary) 7%, transparent);
  }
}

/* ===== 链路详情弹窗 ===== */
.trace-state {
  padding: 22px;
  text-align: center;
  font-size: 13px;
  color: var(--color-text-tertiary);

  &.is-error { color: var(--danger); }
}

.trace-head-action {
  /* 推到最右：它切换的是另一层数据源，与左侧 traceId 标识不是同一类信息 */
  margin-left: auto;
  padding: 3px 10px;
  font-size: 12px;
  color: var(--color-primary);
  background: transparent;
  border: 1px solid var(--color-primary);
  border-radius: 4px;
  cursor: pointer;
}
.trace-head-action:hover { background: var(--color-primary); color: #fff; }

.trace-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 12px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--color-border-light);
}

.trace-head-label { font-size: 12px; color: var(--color-text-tertiary); }

.trace-head-id {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  background: var(--color-bg-sunken, var(--surface-2));
  color: var(--color-text-primary);
}

.trace-block { margin-bottom: 20px; }

.trace-h {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.kv {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 7px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: color-mix(in oklab, var(--color-bg-sunken, var(--surface-2)) 40%, transparent);

  .k { font-size: 11px; color: var(--color-text-tertiary); }
  .v { font-size: 13px; color: var(--color-text-primary); }
}

.qa-label {
  margin: 12px 0 5px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--color-text-tertiary);
}

.qa-pre {
  margin: 0;
  padding: 10px 12px;
  max-height: 190px;
  overflow: auto;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: color-mix(in oklab, var(--color-bg-sunken, var(--surface-2)) 50%, transparent);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-primary);
}

.cite-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-text-secondary);
}

/* 写操作时间线 */
.op-timeline {
  list-style: none;
  margin: 0;
  padding: 0 0 0 4px;
}

.op-item {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 0 0 14px 14px;
  border-left: 1px solid var(--color-border-light);

  &:last-child { border-left-color: transparent; padding-bottom: 0; }
}

.op-dot {
  position: absolute;
  left: -4px;
  top: 4px;
  width: 7px;
  height: 7px;
  border-radius: 999px;
  border: 2px solid var(--color-surface);

  &.is-ok { background: oklch(0.60 0.145 163); }
  &.is-fail { background: var(--danger); }
}

.op-content { flex: 1; min-width: 0; }

.op-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  margin-bottom: 3px;
}

.op-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 11.5px;
  color: var(--color-text-tertiary);
}

.op-err { color: var(--danger); }

@media (max-width: 900px) {
  .audit-main { padding: 16px; }
  .ctl-range { grid-column: span 1; }
  .filter-toolbar { flex-direction: column; align-items: stretch; }
  .filter-actions { justify-content: flex-end; }
}
</style>
