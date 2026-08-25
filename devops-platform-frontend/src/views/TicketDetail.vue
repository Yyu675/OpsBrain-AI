<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Clock, AlertTriangle,
  Send, ArrowUp, ArrowRightLeft, X, Check, Info, Plus,
  Sparkles, BookPlus, Paperclip, Trash2, Download, TrendingUp
} from 'lucide-vue-next'
import { useTicketsStore, getStatusLabel, getPriorityLabel, UNASSIGNED } from '@/stores/tickets'
import { canTransitionStatus, isTerminalStatus } from '@/constants/ticket'
import { useAppStore } from '@/stores/app'
import {
  fetchTicketById,
  // B1 首响 / 升级
  // B2 现场处置
  addTicketAction,
  fetchTicketActions,
  updateTicketStage,
  markTicketMitigated,
  // B3 根因 + 验证
  confirmRootCause,
  submitVerification,
  skipVerification,
  type TicketActionRecord
} from '@/api/tickets'
import { useTicketClosure } from '@/composables/useTicketClosure'
import { useTicketAttachments } from '@/composables/useTicketAttachments'
import { useTicketActions } from '@/composables/useTicketActions'
import { useTicketAnalysis } from '@/composables/useTicketAnalysis'
import { getTrends, type TrendData } from '@/api/dashboard'
import { mapServiceToModule } from '@/api/utils/dto-converter'
import { notify, handleServerError } from '@/utils/notify'
import { useExternalResourceState } from '@/composables/useResourceState'
import { useTicketPostmortem } from '@/composables/useTicketPostmortem'
import PostmortemDrawer from '@/components/ticket/PostmortemDrawer.vue'
import TicketTimeline from '@/components/ticket/TicketTimeline.vue'
import TicketInsights from '@/components/ticket/TicketInsights.vue'
import KnowledgeSinkDrawer from '@/components/ticket/KnowledgeSinkDrawer.vue'
import AppEmpty from '@/components/common/AppEmpty.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'
import PageLoading from '@/components/common/PageLoading.vue'
import CollapsibleCard from '@/components/common/CollapsibleCard.vue'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'

const route = useRoute()
const router = useRouter()
const store = useTicketsStore()
const app = useAppStore()

const ticketId = computed(() => String(route.params.id))
const ticket = computed(() => store.getById(ticketId.value))

/**
 * 三态由 useExternalResourceState 统一管理（6.18 契约）。
 *
 * 用「外部数据源」变体而非 useResourceState：工单数据归 store 持有
 * （列表页已加载时详情页无需重拉），composable 再持一份必然与 store 漂移。
 * 此处只统一状态机——此前手写的 loading/loadError/notFound 三个布尔量，
 * 与 AlertDetail 的同名量已出现 v-if 条件不一致（`loading` vs `loading && !ticket`）。
 */
const resource = useExternalResourceState(() => !!ticket.value)
const loading = resource.isLoading
const loadError = resource.error
const notFound = resource.isNotFound

/** 加载工单详情（挂载与重试共用） */
const loadDetail = () => resource.load(async () => {
  if (!ticket.value) {
    const fetchedTicket = await fetchTicketById(ticketId.value)
    if (fetchedTicket) {
      store.addTicket(fetchedTicket)
    }
  }
  if (ticket.value) {
    await store.loadTicketDetail(ticketId.value)
  }
})

/**
 * 时间线中以普通气泡渲染的回复
 *
 * 排除 role='ai'：AI 分析由 AnalysisCard 结构化渲染（原因/命令/置信度），
 * 若同时出现在此循环会重复展示，且纯文本气泡会丢掉全部结构。
 * 历史分析仍存库可审计，界面只呈现最新结论。
 */
const visibleReplies = computed(() =>
  (ticket.value?.replies ?? []).filter(r => r.role !== 'ai')
)

/**
 * 载入分析：优先用存档，没有才调付费 LLM
 *
 * 此前是无条件 runAnalysis()——每次打开/刷新工单详情都调一次 DeepSeek，
 * 结果还只存内存、关页即失。
 */
const initAnalysis = async () => {
  const hasArchive = await loadArchivedAnalysis()
  if (!hasArchive) runAnalysis()
}

onMounted(() => {
  loadDetail()
  loadAttachments()
  loadSimilarTickets()
  loadRelatedDocs()
  loadActions()
  // 趋势不在此触发：它依赖 ticket.service 做服务下钻，而此刻详情尚未加载完。
  // 由下方 watch(ticket.service) 在服务就绪后触发（切换工单同样由它兜住）
  void initAnalysis()
})

/**
 * 切换工单必须重置全部工单相关状态
 *
 * /tickets/:id 是同一路由，切换 id 时 Vue 复用组件实例，onMounted 不再触发。
 * 此前只调 loadDetail()，导致从工单 A 点「相似工单」跳到工单 B 后，
 * B 的时间线继续挂着 A 的 AI 分析、右栏继续显示 A 的相似工单与相关文档——
 * 把 A 的结论当作 B 的呈现给用户。
 */
watch(ticketId, (newId, oldId) => {
  if (newId && newId !== oldId) {
    resetAnalysis()
    actions.value = []
    pm.reset()
    // 重置三态：使上一工单进行中的请求作废，并回到 loading 避免
    // 新工单尚未拉到时残留上一工单的终态（error / notFound）
    resource.reset()
    loadDetail()
    loadSimilarTickets()
    loadRelatedDocs()
    loadActions()
    void initAnalysis()
  }
})

/**
 * 闭环进度、SLA 展示与属性栏的派生计算已抽到 useTicketClosure。
 *
 * 抽出的判定口径（止损按 mitigatedAt 而非状态、首响超时标 skipped、
 * 已解决未验证时验证标 skipped、已超时优先于进度着色）连同注释一并搬走，
 * 由 useTicketClosure.test.ts 单独覆盖。
 */
const { closureStages, properties, showSlaAlert, slaBarClass } = useTicketClosure(ticket)

/** AI 分析 composable */
const {
  analysisContent, analysisStreaming, analysisDone, citations, analysisCost,
  analysisFromArchive, analysisArchivedAt,
  analysisId, analysisFeedback, submitFeedback,
  structured, confidenceClass, useStructuredRender,
  similarTickets, similarLoading, relatedDocs, relatedLoading,
  runAnalysis, stopAnalysis, regenerateAnalysis, generateReply,
  loadSimilarTickets, loadRelatedDocs, renderMarkdown, copyCommand, copyAnalysis,
  loadArchivedAnalysis, resetAnalysis,
} = useTicketAnalysis(
  () => ticketId.value,
  () => ticketContextText.value,
  () => ticket.value?.service ?? '',
  () => ticket.value?.title ?? ''
)

/**
 * 工单趋势（B-1 + 下钻）：供右栏 Insights 迷你折线
 *
 * 按**该工单所属服务**下钻——右栏位于某张工单内，用户预期看到的是该服务的
 * 趋势而非全库。服务未知时退化为全局并由组件标注口径。
 * 取 14 天窗口——右栏窄，7 天点太少看不出走势，30 天在 120px 高度下会挤成锯齿。
 * 失败仅降级为「趋势数据不可用」，不阻塞工单详情主体。
 */
const insightTrend = ref<TrendData | null>(null)
const insightTrendLoading = ref(false)

const loadInsightTrend = async () => {
  if (insightTrendLoading.value) return
  insightTrendLoading.value = true
  try {
    const service = ticket.value?.service
    const module = service ? mapServiceToModule(service) : null
    insightTrend.value = await getTrends(14, module)
  } catch (e) {
    console.warn('[TicketDetail] 趋势数据加载失败', e)
    insightTrend.value = null
  } finally {
    insightTrendLoading.value = false
  }
}

/**
 * 服务就绪/变化时拉取该服务的趋势
 *
 * 用 watch 而非 onMounted：趋势按 ticket.service 下钻，而挂载时详情尚未加载完，
 * 此刻 service 为空会拉成全局口径并显示错误的口径标注。
 * immediate 不需要——service 由 undefined 变为实际值本身就会触发。
 * 切换工单时若新旧服务相同则不重拉（数据一致，省一次请求）。
 */
watch(() => ticket.value?.service, (service, prev) => {
  if (service && service !== prev) {
    void loadInsightTrend()
  }
})

/** 工单上下文文本，自动拼接给 AI */
const ticketContextText = computed(() => {
  const t = ticket.value
  if (!t) return ''
  return `工单编号: ${t.id}\n标题: ${t.title}\n优先级: ${getPriorityLabel(t.priority)}\n状态: ${getStatusLabel(t.status)}\n服务: ${t.service}\n分类: ${t.category}\nSLA: ${t.sla}\n描述: ${t.description}`
})

/**
 * 工单状态流转类写操作已抽到 useTicketActions。
 *
 * 抽出的目的是让「哪个动作有防重入」一眼可查——它们此前散在 200~470 行
 * 之间、中间还夹着派生计算与对话框状态，要确认「升级上报有没有防重入」
 * 得在 2500 行里翻。集中后可以直接对照：每个写活动流的动作都必须有进行中标记。
 *
 * B2/B3 的表单类动作（处置记录/根因/验证）刻意留在本文件：它们与各自的
 * 对话框表单状态强耦合，搬过去要一并带走 6 个 ref，反而让 composable
 * 变成第二个大杂烩。分界线是「是否只依赖工单本身」。
 */
const {
  replyContent, submitting, submitReply,
  transferDialogVisible, transferTarget, workloadOf, openTransferDialog, doTransfer,
  priorityAction, raisePriority,
  acknowledging, doAcknowledge, escalateAction, doEscalate,
  closeTicket, reopenLabel, processingAction, startProcessing,
} = useTicketActions({
  ticket,
  getOperator: () => app.currentUser.name,
  store,
})


/**
 * B3 修复验证：转「已解决」时弹出验证弹窗
 *
 * D3 决策：必填但允许带理由跳过。
 * 提交验证后后端自动转 RESOLVED + 记 verified_at（MTTR 终点）。
 */
const verifyDialogVisible = ref(false)
const verifyForm = ref({ method: 'MONITOR', conclusion: '', skip: false, skipReason: '' })
const verifySubmitting = ref(false)

const openVerifyDialog = () => {
  verifyForm.value = { method: 'MONITOR', conclusion: '', skip: false, skipReason: '' }
  verifyDialogVisible.value = true
}

const resolveTicket = () => {
  // 原直接改状态 → 改为弹出验证弹窗（D3 决策）
  openVerifyDialog()
}

const doSubmitVerification = async () => {
  const cur = ticket.value
  if (!cur || verifySubmitting.value) return
  const f = verifyForm.value
  if (f.skip && !f.skipReason.trim()) {
    notify.warning('跳过验证须填写理由')
    return
  }
  verifySubmitting.value = true
  try {
    const operator = app.currentUser.name || '当前用户'
    const updated = f.skip
      ? await skipVerification(cur.id, f.skipReason.trim(), operator)
      : await submitVerification(cur.id, f.method, f.conclusion.trim(), operator)
    // 同步本地状态
    const t = store.getById(cur.id)
    if (t) {
      t.status = updated.status
      t.verifiedAt = updated.verifiedAt
      t.verifyMethod = updated.verifyMethod
      t.verifySkipped = updated.verifySkipped
      t.version = updated.version
      t.updatedAt = updated.updatedAt
    }
    await store.loadActivities(cur.id)
    verifyDialogVisible.value = false
    notify.success(f.skip ? '已跳过验证，工单标记为已解决' : '验证通过，工单已解决')
  } catch (e) {
    handleServerError(e, { action: '提交验证' })
  } finally {
    verifySubmitting.value = false
  }
}

// ==================== B2 现场处置 ====================

const actions = ref<TicketActionRecord[]>([])
const actionsLoading = ref(false)
const actionDialogVisible = ref(false)
const actionForm = ref({ actionType: 'INVESTIGATE', summary: '', detail: '', effective: null as boolean | null })
const actionSubmitting = ref(false)

const ACTION_TYPES = [
  { value: 'MITIGATE', label: '止损' },
  { value: 'INVESTIGATE', label: '排查' },
  { value: 'FIX', label: '修复' },
  { value: 'ROLLBACK', label: '回滚' },
  { value: 'VERIFY', label: '验证' }
]

const STAGES = [
  { value: 'TRIAGE', label: '排查中' },
  { value: 'MITIGATED', label: '已止损' },
  { value: 'FIXING', label: '修复中' },
  { value: 'VERIFYING', label: '验证中' }
]

const loadActions = async () => {
  const cur = ticket.value
  if (!cur) return
  actionsLoading.value = true
  try {
    actions.value = await fetchTicketActions(cur.id)
  } catch (e) {
    console.warn('加载处置动作失败', e)
  } finally {
    actionsLoading.value = false
  }
}

const openActionDialog = () => {
  actionForm.value = { actionType: 'INVESTIGATE', summary: '', detail: '', effective: null }
  actionDialogVisible.value = true
}

const doAddAction = async () => {
  const cur = ticket.value
  if (!cur || actionSubmitting.value) return
  if (!actionForm.value.summary.trim()) {
    notify.warning('处置摘要不能为空')
    return
  }
  actionSubmitting.value = true
  try {
    await addTicketAction(cur.id, {
      actionType: actionForm.value.actionType,
      summary: actionForm.value.summary.trim(),
      detail: actionForm.value.detail.trim() || undefined,
      operator: app.currentUser.name || '当前用户',
      effective: actionForm.value.effective
    })
    await loadActions()
    await store.loadActivities(cur.id)
    actionDialogVisible.value = false
    notify.success('处置动作已记录')
  } catch (e) {
    handleServerError(e, { action: '记录处置动作' })
  } finally {
    actionSubmitting.value = false
  }
}

const doUpdateStage = async (stage: string) => {
  const cur = ticket.value
  if (!cur) return
  try {
    const updated = await updateTicketStage(cur.id, stage, app.currentUser.name || '当前用户')
    const t = store.getById(cur.id)
    if (t) {
      t.handlingStage = updated.handlingStage
      t.status = updated.status
      t.version = updated.version
      t.updatedAt = updated.updatedAt
    }
    await store.loadActivities(cur.id)
    notify.success(`已切换到「${STAGES.find(s => s.value === stage)?.label || stage}」`)
  } catch (e) {
    handleServerError(e, { action: '切换处置阶段' })
  }
}

/** 标记止损（B2）：补录 mitigated_at，回调沿用阶段切换的响应同步 */
const doMarkMitigated = async () => {
  const cur = ticket.value
  if (!cur) return
  try {
    const updated = await markTicketMitigated(cur.id, app.currentUser.name || '当前用户')
    const t = store.getById(cur.id)
    if (t) {
      t.handlingStage = updated.handlingStage
      t.mitigatedAt = updated.mitigatedAt
      t.status = updated.status
      t.version = updated.version
      t.updatedAt = updated.updatedAt
    }
    await store.loadActivities(cur.id)
    notify.success('已标记止损（业务已恢复，根因可能未定位）')
  } catch (e) {
    handleServerError(e, { action: '标记止损' })
  }
}

// ==================== B3 根因确认 ====================

const rootCauseDialogVisible = ref(false)
const rootCauseForm = ref({ rootCause: '', category: 'UNKNOWN' })
const rootCauseSubmitting = ref(false)

const RC_CATEGORIES = [
  { value: 'CONFIG', label: '配置错误' },
  { value: 'CAPACITY', label: '容量不足' },
  { value: 'CODE', label: '代码缺陷' },
  { value: 'DEPENDENCY', label: '依赖故障' },
  { value: 'NETWORK', label: '网络问题' },
  { value: 'DATA', label: '数据异常' },
  { value: 'HUMAN', label: '人为操作' },
  { value: 'EXTERNAL', label: '外部服务' },
  { value: 'UNKNOWN', label: '未定位' }
]

const openRootCauseDialog = () => {
  // 预填 AI 建议的最新分析内容（一键采纳）
  const aiText = analysisContent.value
  rootCauseForm.value = {
    rootCause: aiText ? aiText.slice(0, 2000) : '',
    category: 'UNKNOWN'
  }
  rootCauseDialogVisible.value = true
}

const doConfirmRootCause = async () => {
  const cur = ticket.value
  if (!cur || rootCauseSubmitting.value) return
  if (!rootCauseForm.value.rootCause.trim()) {
    notify.warning('根因不能为空')
    return
  }
  rootCauseSubmitting.value = true
  try {
    const updated = await confirmRootCause(
      cur.id,
      rootCauseForm.value.rootCause.trim(),
      rootCauseForm.value.category,
      app.currentUser.name || '当前用户'
    )
    const t = store.getById(cur.id)
    if (t) {
      t.rootCauseCategory = updated.rootCauseCategory
      t.version = updated.version
      t.updatedAt = updated.updatedAt
    }
    await store.loadActivities(cur.id)
    rootCauseDialogVisible.value = false
    notify.success('根因已确认')
  } catch (e) {
    handleServerError(e, { action: '确认根因' })
  } finally {
    rootCauseSubmitting.value = false
  }
}

// ==================== B4 复盘 ====================

/**
 * 复盘逻辑已抽到 useTicketPostmortem（原本 120 行内联在此文件中）。
 * 该文件近 2900 行，复盘是其中交互面最窄、最独立的一块。
 */
const pm = useTicketPostmortem({
  getTicketId: () => ticket.value?.id ?? null,
  getOperator: () => app.currentUser.name || "当前用户",
  // 保存复盘会在后端记活动流，需刷新时间线
  onSaved: (ticketId) => store.loadActivities(ticketId)
})
// 解构出模板要用的 ref/方法：模板中写 pm.xxx.value 虽能过类型检查，
// 但不符合 Vue 惯例（模板自动解包 ref），解构后与其它 composable 用法一致
const {
  drawerVisible: pmDrawerVisible,
  form: pmForm,
  newActionItem: pmNewActionItem,
  actionItems: pmActionItems,
  postmortem: pmRecord,
  saving: pmSaving,
  open: openPmDrawer,
  generateDraft: pmGenerateDraft,
  save: pmSave,
  addItem: pmAddItem,
  updateItemStatus: pmUpdateItemStatus,
} = pm


/** AI 生成回复草稿填入回复框 */
const handleGenerateReply = async () => {
  const result = await generateReply()
  if (result) {
    replyContent.value = result
    notify.success('AI 已生成回复草稿，请审核后发送')
  }
}

// ==================== 标签编辑 ====================
const newTagInput = ref('')
const tagRemoving = ref(false)

const addTag = async () => {
  const cur = ticket.value
  if (!cur || !newTagInput.value.trim()) return
  const tag = newTagInput.value.trim()
  if ((cur.tags || []).includes(tag)) {
    notify.warning('标签已存在')
    return
  }
  tagRemoving.value = true
  try {
    const newTags = [...(cur.tags || []), tag]
    await store.updateTags(cur.id, newTags)
    notify.success('标签已添加')
    newTagInput.value = ''
  } catch {
    // store 已提示错误
  } finally {
    tagRemoving.value = false
  }
}

const addTagFromSuggestion = async (tag: string) => {
  const cur = ticket.value
  if (!cur || (cur.tags || []).includes(tag)) return
  tagRemoving.value = true
  try {
    const newTags = [...(cur.tags || []), tag]
    await store.updateTags(cur.id, newTags)
    notify.success('标签已添加')
  } catch {
    // store 已提示错误
  } finally {
    tagRemoving.value = false
  }
}

const removeTag = async (tag: string) => {
  const cur = ticket.value
  if (!cur) return
  tagRemoving.value = true
  try {
    const newTags = (cur.tags || []).filter(t => t !== tag)
    await store.updateTags(cur.id, newTags)
    notify.success('标签已移除')
  } catch {
    // store 已提示错误
  } finally {
    tagRemoving.value = false
  }
}

// ==================== 附件 ====================
// 逻辑已抽到 useTicketAttachments：它是本文件交互面最窄、
// 与其余逻辑耦合最少的一块（不参与状态机、不写活动流、不影响闭环进度）。
// 「切换工单先清空再重载」「加载失败只降级不弹错、上传失败必须提示」
// 等取舍连同注释一并搬走。
const {
  attachments, attachmentsLoading, uploading, fileInputRef,
  loadAttachments, pickAttachment, onAttachmentSelected,
  downloadAttachment, removeAttachment,
} = useTicketAttachments({
  ticketId,
  getOperator: () => app.currentUser.name,
})

// ==================== 知识沉淀 ====================
const sinkOpen = ref(false)

const openSink = () => {
  if (!ticket.value) return
  sinkOpen.value = true
}

const onSinkPublished = (_docId: number, title: string) => {
  notify.success(`已沉淀为知识「${title}」`)
}

const onSinkGotoDoc = (docId: number) => {
  sinkOpen.value = false
  router.push(`/knowledge/${docId}`)
}

// onBeforeUnmount 由 useTicketAnalysis composable 处理 abort
</script>

<template>
  <div class="ticket-detail">

    <!-- 加载态 -->
    <main v-if="loading" class="main-container">
      <PageLoading tip="正在加载工单详情…" />
    </main>

    <!-- 加载失败 -->
    <main v-else-if="loadError" class="main-container">
      <ApiErrorState
        :error="loadError"
        retry-label="重试"
        @retry="loadDetail"
      />
      <div style="text-align: center; margin-top: 8px;">
        <button class="btn-text" @click="router.push('/tickets')">返回工单列表</button>
      </div>
    </main>

    <!-- 确实不存在 -->
    <main v-else-if="notFound" class="main-container">
      <AppEmpty
        kind="notfound"
        title="工单未找到"
        description="该工单可能已删除或从未存在，其它页面不受影响。"
        action-text="返回工单列表"
        secondary-text="上一页"
        @action="router.push('/tickets')"
        @secondary="router.back()"
      />
    </main>

    <template v-else-if="ticket">
      <!-- Breadcrumb（统一为首页起始的递进链路） -->
      <div class="breadcrumb-container">
        <AppBreadcrumb
          :items="[
            { label: '智能工单', to: '/tickets' },
            { label: ticket.id }
          ]"
        />
      </div>

      <!-- Two-column layout -->
      <main class="main-container">
        <div class="content-grid">

          <!-- ========== LEFT COLUMN (65%) ========== -->
          <div class="left-column">

            <!-- Ticket Header Card -->
            <div class="ticket-header-card">
              <div class="ticket-badges">
                <span class="badge" :class="`badge-status-${ticket.status}`">{{ getStatusLabel(ticket.status) }}</span>
                <span class="badge" :class="`badge-priority-${ticket.priority}`">{{ getPriorityLabel(ticket.priority) }}</span>
              </div>
              <h1 class="ticket-title">{{ ticket.title }}</h1>
              <div class="ticket-meta">
                <span class="meta-label">创建人</span>
                <span class="meta-value">{{ ticket.creator }}</span>
                <span class="meta-dot">&middot;</span>
                <span class="meta-label">创建时间</span>
                <span class="meta-value">{{ ticket.createdAt }}</span>
                <span class="meta-dot">&middot;</span>
                <span class="meta-label">负责人</span>
                <span class="meta-value meta-assignee">{{ ticket.assignee }}</span>
              </div>
              <div class="ticket-actions">
                <!-- B1 确认接单：仅在尚未首响时显示。已首响后按钮消失，
                     避免出现「确认接单」与「已响应 3 分钟」并存的矛盾界面 -->
                <button
                  v-if="ticket.firstResponseState !== 'RESPONDED'"
                  class="btn-primary"
                  :disabled="acknowledging"
                  @click="doAcknowledge"
                >
                  <Check :size="16" />
                  {{ acknowledging ? '处理中…' : '确认接单' }}
                </button>
                <button class="btn-outline" @click="openTransferDialog">
                  <ArrowRightLeft :size="16" />
                  转派
                </button>
                <!-- 拆分为两个按钮：提升优先级会重算 SLA 时限，升级上报只记录不动优先级 -->
                <button class="btn-outline" :disabled="priorityAction.pending.value" @click="raisePriority">
                  <ArrowUp :size="16" />
                  {{ priorityAction.pending.value ? '提升中…' : '提升优先级' }}
                </button>
                <button class="btn-outline" :disabled="escalateAction.pending.value" @click="doEscalate">
                  <TrendingUp :size="16" />
                  {{ escalateAction.pending.value ? '提交中…' : '升级上报' }}
                </button>
                <!--
                  状态流转类按钮一律由 canTransitionStatus 驱动，
                  不再手写 `status === 'x' || status === 'y'` 的枚举列表。
                  手写列表与状态机已经漂移出 8 处不一致（见 reopenLabel 注释）。
                -->
                <button
                  class="btn-outline"
                  @click="startProcessing"
                  :disabled="processingAction.pending.value || !canTransitionStatus(ticket.status, 'processing')"
                  :title="canTransitionStatus(ticket.status, 'processing') ? '' : `不能从「${getStatusLabel(ticket.status)}」变更为「处理中」`"
                >
                  <Clock :size="16" />
                  {{ processingAction.pending.value ? '处理中…' : reopenLabel }}
                </button>
                <button class="btn-outline" @click="closeTicket"
                  :disabled="!canTransitionStatus(ticket.status, 'closed')"
                  :title="canTransitionStatus(ticket.status, 'closed') ? '' : '工单需先「标记解决」才能关闭'"
                >
                  <X :size="16" />
                  关闭
                </button>
                <button
                  class="btn-outline"
                  @click="openSink"
                  :disabled="isTerminalStatus(ticket.status)"
                >
                  <BookPlus :size="16" />
                  沉淀为知识
                </button>
                <div class="btn-spacer"></div>
                <button
                  class="btn-primary"
                  @click="resolveTicket"
                  :disabled="!canTransitionStatus(ticket.status, 'resolved')"
                  :title="canTransitionStatus(ticket.status, 'resolved') ? '' : '已作废的工单不可再变更状态'"
                >
                  <Check :size="16" />
                  标记解决
                </button>
                <!-- B2 现场处置 -->
                <button class="btn-outline" @click="openActionDialog" :disabled="isTerminalStatus(ticket.status) || ticket.status === 'closed'">
                  <Plus :size="14" />
                  记录处置
                </button>
                <!-- B3 根因 -->
                <button class="btn-outline" @click="openRootCauseDialog" :disabled="isTerminalStatus(ticket.status) || ticket.status === 'closed'">
                  <AlertTriangle :size="14" />
                  确认根因
                </button>
                <!-- B4 复盘 -->
                <button class="btn-outline" @click="openPmDrawer" :disabled="ticket.status === 'pending'">
                  <BookPlus :size="14" />
                  复盘归档
                </button>
              </div>
            </div>

            <!-- B5 闭环进度条（6 阶段横向步骤条） -->
            <div class="closure-progress-bar">
              <div
                v-for="stage in closureStages"
                :key="stage.key"
                class="cp-step"
                :class="stage.state"
              >
                <div class="cp-dot">
                  <Check v-if="stage.state === 'done'" :size="14" />
                  <span v-else-if="stage.state === 'current'" class="cp-dot-inner"></span>
                </div>
                <span class="cp-label">{{ stage.label }}</span>
                <el-tooltip
                  v-if="stage.meta && stage.state === 'skipped'"
                  :content="'跳过原因：' + stage.meta"
                  placement="top"
                  :show-after="200"
                >
                  <span class="cp-meta cp-meta-skipped">{{ stage.meta.length > 8 ? stage.meta.slice(0, 8) + '…' : stage.meta }}</span>
                </el-tooltip>
                <span v-else-if="stage.meta" class="cp-meta">{{ stage.meta }}</span>
              </div>
            </div>

            <!-- B2 处置阶段切换（仅在处理中状态显示） -->
            <div v-if="ticket.status === 'processing'" class="stage-switcher">
              <button
                v-for="s in STAGES"
                :key="s.value"
                class="stage-btn"
                :class="{ active: ticket.handlingStage === s.value }"
                @click="s.value === 'MITIGATED' ? doMarkMitigated() : doUpdateStage(s.value)"
              >{{ s.label }}</button>
            </div>

            <!-- B2 处置动作列表（时间线中展示） -->
            <div v-if="actions.length" class="action-list-section">
              <h3 class="description-title">处置动作</h3>
              <div v-for="a in actions" :key="a.id" class="action-item" :class="{ 'action-ineffective': a.effective === false }">
                <span class="action-type-badge" :class="`action-type-${(a.actionType || '').toLowerCase()}`">{{ ACTION_TYPES.find(t => t.value === a.actionType)?.label || a.actionType }}</span>
                <span class="action-summary">{{ a.summary }}</span>
                <span v-if="a.effective === true" class="action-eff eff-ok">有效</span>
                <span v-else-if="a.effective === false" class="action-eff eff-no">无效</span>
                <span class="action-meta">{{ a.operator }} · {{ a.createTime }}</span>
              </div>
            </div>

            <!-- 工单描述 -->
            <div v-if="ticket.description" class="ticket-description-card">
              <h3 class="description-title">工单描述</h3>
              <div class="description-body">{{ ticket.description }}</div>
            </div>

            <!-- ========== Timeline ==========
                 已抽到 TicketTimeline.vue：那是本文件视觉最独立的一块
                 （自成体系的模板 + 236 行专属样式），与页面其余部分
                 只通过数据往来，没有共享的交互状态。
                 AI 分析的 18 个字段收成一个对象传入，避免调用处占满一屏。 -->
            <TicketTimeline
              :ticket="ticket"
              :visible-replies="visibleReplies"
              :show-sla-alert="showSlaAlert"
              :analysis="{
                content: analysisContent,
                streaming: analysisStreaming,
                done: analysisDone,
                structured,
                useStructuredRender,
                confidenceClass,
                citations,
                cost: analysisCost,
                fromArchive: analysisFromArchive,
                archivedAt: analysisArchivedAt,
                feedback: analysisFeedback,
                id: analysisId,
                onFeedback: submitFeedback,
                renderMarkdown,
                onCopyCommand: copyCommand,
                onCopyAnalysis: copyAnalysis,
                onRegenerate: regenerateAnalysis,
                onStop: stopAnalysis,
              }"
            />

            <!-- Reply Box -->
            <div class="reply-box" v-if="!['closed', 'resolved', 'void'].includes(ticket.status)">
              <textarea
                v-model="replyContent"
                class="reply-textarea"
                rows="3"
                placeholder="输入回复内容..."
                @keydown.ctrl.enter="submitReply"
              ></textarea>
              <div class="reply-actions">
                <button class="btn-outline" @click="handleGenerateReply">
                  <Sparkles :size="16" class="primary-icon" />
                  AI 生成回复
                </button>
                <div class="btn-spacer"></div>
                <button class="btn-primary" :disabled="submitting" @click="submitReply">
                  <Send :size="16" />
                  {{ submitting ? '发送中...' : '发送' }}
                </button>
              </div>
            </div>
          </div>

          <!-- ========== RIGHT COLUMN (35%) ========== -->
          <aside class="right-sidebar">

            <!-- 工单属性 -->
            <CollapsibleCard title="工单属性" :icon="Info" storage-key="td-props">
              <div class="props-list">
                <div v-for="prop in properties" :key="prop.label" class="prop-row">
                  <span class="prop-label">{{ prop.label }}</span>
                  <span
                    class="prop-value"
                    :class="{
                      'prop-mono': prop.mono,
                      'prop-priority-urgent': prop.type === 'priority-urgent',
                      'prop-priority-high': prop.type === 'priority-high',
                      'prop-status': prop.type === 'status'
                    }"
                  >
                    <span v-if="prop.type?.startsWith('priority-') && prop.type !== 'priority-medium' && prop.type !== 'priority-low'" class="prop-dot"></span>
                    {{ prop.value }}
                  </span>
                </div>
              </div>
              <!-- SLA Progress -->
              <div class="sla-progress">
                <div class="sla-header">
                  <span class="sla-label">SLA 进度</span>
                  <span class="sla-value" :class="{ 'sla-value-breached': ticket.slaBreached }">
                    {{ ticket.slaBreached ? '已超时' : ticket.slaProgress + '% 已消耗' }}
                  </span>
                </div>
                <div class="progress-bar">
                  <div
                    class="progress-fill"
                    :class="slaBarClass"
                    :style="{ width: ticket.slaProgress + '%' }"
                  ></div>
                </div>
              </div>
            </CollapsibleCard>

            <!-- 标签管理 -->
            <CollapsibleCard title="标签" :icon="Info" storage-key="td-tags" :badge="(ticket.tags || []).length || undefined">
              <div class="tag-edit-area">
                <span v-for="tag in (ticket.tags || [])" :key="tag" class="tag-chip">
                  {{ tag }}
                  <button class="tag-remove" @click="removeTag(tag)" :disabled="tagRemoving">×</button>
                </span>
                <div class="tag-add-row">
                  <input
                    v-model="newTagInput"
                    class="tag-input"
                    placeholder="添加标签..."
                    @keydown.enter="addTag"
                    :disabled="tagRemoving"
                  />
                  <button class="tag-add-btn" @click="addTag" :disabled="!newTagInput.trim() || tagRemoving">添加</button>
                </div>
                <div v-if="store.hotTags.length" class="tag-suggestions">
                  <span class="tag-suggest-label">热门：</span>
                  <button
                    v-for="tag in store.hotTags.slice(0, 5)"
                    :key="tag"
                    class="tag-suggest"
                    @click="addTagFromSuggestion(tag)"
                    :disabled="tagRemoving || (ticket.tags || []).includes(tag)"
                  >{{ tag }}</button>
                </div>
              </div>
            </CollapsibleCard>

            <!-- AI 智能分析（只读 Insights） -->
            <CollapsibleCard title="AI 智能分析" :icon="Sparkles" storage-key="td-ai" class="td-ai-card">
              <TicketInsights
                :similar-tickets="similarTickets"
                :similar-loading="similarLoading"
                :related-docs="relatedDocs"
                :related-loading="relatedLoading"
                :confidence="structured.confidence"
                :confidence-class="confidenceClass"
                :trend="insightTrend"
                :trend-loading="insightTrendLoading"
              />
            </CollapsibleCard>

            <!-- 附件 -->
            <CollapsibleCard title="附件" :icon="Paperclip" storage-key="td-attachments" :badge="attachments.length || undefined">
              <div v-if="attachmentsLoading" class="attach-empty">加载中…</div>
              <div v-else-if="attachments.length === 0" class="attach-empty">暂无附件</div>
              <ul v-else class="attach-list">
                <li v-for="item in attachments" :key="item.id" class="attach-item">
                  <div class="attach-meta">
                    <span class="attach-name" :title="item.originalName">{{ item.originalName }}</span>
                    <span class="attach-size">{{ item.sizeText }}</span>
                  </div>
                  <div class="attach-ops">
                    <button class="attach-op" title="下载" @click="downloadAttachment(item)">
                      <Download :size="14" />
                    </button>
                    <button class="attach-op attach-op-danger" title="删除" @click="removeAttachment(item)">
                      <Trash2 :size="14" />
                    </button>
                  </div>
                </li>
              </ul>
              <!--
                用 :ref 动态绑定而非字符串 ref="fileInputRef"：
                后者与 script setup 变量的关联 vue-tsc 识别不到，
                会误报 "declared but its value is never read"。
                动态绑定是显式引用，类型检查能看见。
              -->
              <input
                :ref="(el) => (fileInputRef = el as HTMLInputElement | null)"
                type="file"
                class="attach-file-input"
                @change="onAttachmentSelected"
              />
              <button class="btn-outline attach-upload" :disabled="uploading" @click="pickAttachment">
                <Paperclip :size="14" />
                {{ uploading ? '上传中…' : '上传附件' }}
              </button>
            </CollapsibleCard>

            <!-- 操作记录 -->
            <CollapsibleCard title="操作记录" :icon="Clock" storage-key="td-activity">
              <div class="activity-list">
                <div v-if="!ticket.activities || ticket.activities.length === 0" class="activity-empty">
                  暂无操作记录
                </div>
                <div v-for="(a, i) in ticket.activities" :key="a.time + '-' + a.user + '-' + i" class="activity-row">
                  <div class="activity-dot" :class="`activity-dot-${a.color}`"></div>
                  <div class="activity-body">
                    <p class="activity-text">
                      {{ a.text }}<template v-if="a.detail">: <span class="activity-detail" :class="{ 'activity-detail-highlight': a.highlight }">{{ a.detail }}</span></template>
                    </p>
                    <p class="activity-meta">{{ a.user }} &middot; {{ a.time }}</p>
                  </div>
                </div>
              </div>
            </CollapsibleCard>
          </aside>

        </div>
      </main>
    </template>

    <!-- 转派弹窗 -->
    <el-dialog
      v-model="transferDialogVisible"
      title="转派工单"
      width="360px"
      :close-on-click-modal="false"
    >
      <div style="margin-bottom: 12px;">
        <span style="color: #6b7280; font-size: 0.875rem;">当前负责人：</span>
        <strong>{{ ticket?.assignee || UNASSIGNED }}</strong>
      </div>
      <el-select v-model="transferTarget" placeholder="选择新负责人" style="width: 100%">
        <!-- 名单来自后端 sys_team_member（A2），不再硬编码编造姓名。
             label 带负载提示，避免把工单转派给已满负荷的人 -->
        <el-option
          v-for="name in store.assignees"
          :key="name"
          :label="name + workloadOf(name)"
          :value="name"
        />
      </el-select>
      <template #footer>
        <el-button @click="transferDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!transferTarget" @click="doTransfer">确认转派</el-button>
      </template>
    </el-dialog>

    <KnowledgeSinkDrawer
      v-if="ticket"
      v-model="sinkOpen"
      :ticket-id="ticket.id"
      :ticket-title="ticket.title"
      :ticket-service="ticket.service"
      :ticket-description="ticket.description || ''"
      :ticket-replies="ticket.replies || []"
      :ticket-activities="ticket.activities || []"
      @published="onSinkPublished"
      @goto-doc="onSinkGotoDoc"
    />

    <!-- ========== B2 处置动作记录弹窗 ========== -->
    <el-dialog v-model="actionDialogVisible" title="记录处置动作" width="560px" :close-on-click-modal="false">
      <div class="dialog-form">
        <div class="form-row">
          <label>动作类型</label>
          <select v-model="actionForm.actionType" class="form-input">
            <option v-for="t in ACTION_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
        </div>
        <div class="form-row">
          <label>摘要</label>
          <input v-model="actionForm.summary" type="text" class="form-input" placeholder="一句话：做了什么" maxlength="255" />
        </div>
        <div class="form-row">
          <label>详情</label>
          <textarea v-model="actionForm.detail" class="form-input" rows="4" placeholder="命令/配置/日志片段（可选）"></textarea>
        </div>
        <div class="form-row">
          <label>是否有效</label>
          <select v-model="actionForm.effective" class="form-input">
            <option :value="null">未判定</option>
            <option :value="true">有效</option>
            <option :value="false">无效（失败尝试同样记录）</option>
          </select>
        </div>
      </div>
      <template #footer>
        <el-button @click="actionDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="actionSubmitting" @click="doAddAction">提交</el-button>
      </template>
    </el-dialog>

    <!-- ========== B3 验证弹窗 ========== -->
    <el-dialog v-model="verifyDialogVisible" title="修复验证" width="560px" :close-on-click-modal="false">
      <div class="dialog-form">
        <template v-if="!verifyForm.skip">
          <div class="form-row">
            <label>验证方式</label>
            <select v-model="verifyForm.method" class="form-input">
              <option value="MONITOR">监控确认</option>
              <option value="LOG">日志确认</option>
              <option value="BUSINESS">业务确认</option>
              <option value="MANUAL">人工确认</option>
            </select>
          </div>
          <div class="form-row">
            <label>验证结论</label>
            <textarea v-model="verifyForm.conclusion" class="form-input" rows="4" placeholder="确认业务已恢复、指标回到基线等"></textarea>
          </div>
        </template>
        <template v-else>
          <div class="form-row">
            <label>跳过理由</label>
            <textarea v-model="verifyForm.skipReason" class="form-input" rows="3" placeholder="跳过验证的理由（必填，将记入审计）"></textarea>
          </div>
        </template>
        <label class="skip-toggle">
          <input type="checkbox" v-model="verifyForm.skip" />
          <span>跳过验证（MTTR 统计时将排除此工单）</span>
        </label>
      </div>
      <template #footer>
        <el-button @click="verifyDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="verifySubmitting" @click="doSubmitVerification">
          {{ verifyForm.skip ? '跳过并解决' : '验证通过' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ========== B3 根因确认弹窗 ========== -->
    <el-dialog v-model="rootCauseDialogVisible" title="确认根因" width="600px" :close-on-click-modal="false">
      <div class="dialog-form">
        <div class="form-row">
          <label>根因分类</label>
          <select v-model="rootCauseForm.category" class="form-input">
            <option v-for="c in RC_CATEGORIES" :key="c.value" :value="c.value">{{ c.label }}</option>
          </select>
        </div>
        <div class="form-row">
          <label>根因描述</label>
          <textarea v-model="rootCauseForm.rootCause" class="form-input" rows="8" placeholder="人工确认的根因。可一键采纳 AI 分析内容后编辑。"></textarea>
        </div>
        <p class="form-hint">AI 建议已自动填入，供参考后编辑。人工确认的根因 ≠ AI 建议。</p>
      </div>
      <template #footer>
        <el-button @click="rootCauseDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="rootCauseSubmitting" @click="doConfirmRootCause">确认根因</el-button>
      </template>
    </el-dialog>

    <!-- ========== B4 复盘抽屉（拆分为独立组件，逻辑见 useTicketPostmortem） ========== -->
    <PostmortemDrawer
      v-model:visible="pmDrawerVisible"
      v-model:form="pmForm"
      v-model:new-action-item="pmNewActionItem"
      :action-items="pmActionItems"
      :postmortem="pmRecord"
      :saving="pmSaving"
      @generate-draft="pmGenerateDraft"
      @save="pmSave"
      @add-item="pmAddItem"
      @update-item-status="pmUpdateItemStatus"
    />
  </div>
</template>

<style scoped lang="scss">
.ticket-detail {
  min-height: 100vh;
  background: var(--color-bg);
}

/* ========== Breadcrumb ========== */
.breadcrumb-container {
  max-width: 1280px;
  margin: 0 auto;
  padding: 16px 24px 8px;
}

/* 面包屑内部样式已随 AppBreadcrumb 公共组件收敛（容器定位仍由本页负责） */

/* ========== Main Container ========== */
.main-container {
  max-width: 1280px;
  margin: 0 auto;
  padding: 16px 24px 32px;
}

.content-grid {
  display: flex;
  gap: 24px;

  @media (max-width: 1200px) {
    flex-direction: column;
  }
}

.left-column {
  flex: 65;
  min-width: 0;
}

.right-sidebar {
  flex: 35;
  max-width: 380px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ========== Ticket Header Card ========== */
.ticket-header-card {
  background: white;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-lg, 12px);
  padding: 24px;
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,0.04));
}

/* ========== Ticket Description Card ========== */
.ticket-description-card {
  background: white;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-lg, 12px);
  padding: 20px 24px;
  margin-top: 16px;
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,0.04));
}
.description-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-secondary, var(--text-2));
  margin: 0 0 8px 0;
}
.description-body {
  font-size: 0.9375rem;
  line-height: 1.7;
  color: var(--color-text-primary, var(--text-1));
  white-space: pre-wrap;
  word-break: break-word;
}

.ticket-badges {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  font-size: var(--text-xs, 0.75rem);
  font-weight: var(--weight-medium, 500);
  border-radius: var(--radius-full, 9999px);
}

.badge-status-processing {
  background: var(--color-primary-lighter, var(--brand-subtle));
  color: var(--color-primary-light, var(--brand-hover));
}

.badge-status-pending {
  background: var(--state-warning-bg, var(--warning-subtle));
  color: var(--state-warning, var(--warning));
}

.badge-status-resolved {
  background: var(--state-success, var(--success));
  color: white;
}

.badge-status-closed {
  background: var(--surface-2);
  color: var(--text-2);
}

.badge-priority-urgent {
  background: var(--state-error-bg, var(--danger-subtle));
  color: var(--state-error, var(--danger));
}

.badge-priority-high {
  background: #FEE7D6;
  color: #EA580C;
}

.badge-priority-medium {
  background: var(--color-primary-lighter, var(--brand-subtle));
  color: var(--color-primary, var(--brand));
}

.badge-priority-low {
  background: var(--surface-2);
  color: var(--text-2);
}

.ticket-title {
  font-family: var(--font-display, 'Inter', sans-serif);
  font-size: var(--text-xl, 1.25rem);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary, var(--text-1));
  margin: 0 0 12px 0;
  letter-spacing: -0.01em;
}

.ticket-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-sm, 0.875rem);
  color: var(--color-text-tertiary, var(--text-3));
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.meta-label {
  color: var(--color-text-secondary, var(--text-2));
}

.meta-value {
  color: var(--color-text-primary, var(--text-1));
  font-weight: var(--weight-medium, 500);
}

.meta-assignee {
  color: var(--color-primary-light, var(--brand-hover));
}

.meta-dot {
  margin: 0 4px;
  color: var(--color-text-tertiary, var(--text-3));
}

.ticket-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ========== Shared Buttons ========== */
.btn-outline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--color-border, var(--border-2));
  border-radius: var(--radius-md, 8px);
  font-size: var(--text-sm, 0.875rem);
  font-weight: var(--weight-medium, 500);
  font-family: var(--font-body, 'Inter', sans-serif);
  background: white;
  color: var(--color-text-secondary, var(--text-2));
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary, var(--brand));
    color: var(--color-primary, var(--brand));
  }
}

.btn-spacer {
  flex: 1;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: none;
  border-radius: var(--radius-md, 8px);
  font-size: var(--text-sm, 0.875rem);
  font-weight: var(--weight-medium, 500);
  font-family: var(--font-body, 'Inter', sans-serif);
  background: var(--color-primary, var(--brand));
  color: white;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: var(--color-primary-light, var(--brand-hover));
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.primary-icon {
  color: var(--color-primary-light, var(--brand-hover));
}

/* ========== Reply Box ========== */
.reply-box {
  background: white;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-lg, 12px);
  padding: 16px;
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,0.04));
  margin-top: 8px;
}

.reply-textarea {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-md, 8px);
  font-size: var(--text-sm, 0.875rem);
  line-height: var(--leading-relaxed, 1.625);
  font-family: var(--font-body, 'Inter', sans-serif);
  background: var(--color-bg, var(--surface-0));
  color: var(--color-text-primary, var(--text-1));
  outline: none;
  resize: none;
  box-sizing: border-box;

  &:focus {
    border-color: var(--color-primary-light, var(--brand-hover));
    box-shadow: 0 0 0 2px var(--color-primary-lighter, var(--brand-subtle));
  }
}

.reply-actions {
  display: flex;
  align-items: center;
  margin-top: 12px;
}

/* ========== Side Cards ========== */
/* AI 智能分析面板：CollapsibleCard 的 scoped 根元素带本组件 data-v，
   故父作用域 .td-ai-card 规则可直接命中其根，赋予主色高亮边框以突出重点 */
.td-ai-card {
  border: 2px solid var(--color-primary-lighter, var(--brand-subtle));
}

/* 预览占位标签（无真实数据源时标「预览」，不伪造数字 — 09设计规范 §四.1） */
.preview-tag {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  margin-left: auto;
  border-radius: var(--radius-full, 9999px);
  background: var(--color-bg-sunken, var(--surface-2));
  color: var(--color-text-tertiary, var(--text-3));
  font-size: 10px;
  font-weight: var(--weight-medium, 500);
  line-height: 1.5;
  flex-shrink: 0;
}

/* ========== Properties ========== */
.props-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.prop-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.prop-label {
  font-size: var(--text-sm, 0.875rem);
  color: var(--color-text-tertiary, var(--text-3));
}

.prop-value {
  font-size: var(--text-sm, 0.875rem);
  color: var(--color-text-primary, var(--text-1));
  font-weight: var(--weight-medium, 500);
  display: inline-flex;
  align-items: center;
  gap: 4px;

  &.prop-mono {
    font-family: var(--font-mono, 'JetBrains Mono', monospace);
  }

  &.prop-priority-urgent {
    color: var(--state-error, var(--danger));
  }

  &.prop-priority-high {
    color: #EA580C;
  }

  &.prop-status {
    color: var(--color-primary-light, var(--brand-hover));
  }
}

.prop-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  display: inline-block;
}

/* ========== Tag Edit ========== */
.tag-edit-area { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.tag-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 8px; font-size: 0.75rem;
  background: rgba(64, 158, 255, 0.1); color: var(--brand);
  border-radius: 4px;
}
.tag-remove {
  border: none; background: none; cursor: pointer;
  color: var(--brand); font-size: 0.875rem; line-height: 1;
  padding: 0; opacity: 0.6;
}
.tag-remove:hover { opacity: 1; }
.tag-remove:disabled { opacity: 0.3; cursor: not-allowed; }
.tag-add-row { display: flex; gap: 6px; width: 100%; margin-top: 4px; }
.tag-input {
  flex: 1; padding: 4px 8px; font-size: 0.8125rem;
  border: 1px solid var(--border-1); border-radius: 4px;
}
.tag-add-btn {
  padding: 4px 12px; font-size: 0.8125rem;
  border: 1px solid var(--brand); background: var(--brand); color: #fff;
  border-radius: 4px; cursor: pointer;
}
.tag-add-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.tag-suggestions { display: flex; flex-wrap: wrap; gap: 4px; width: 100%; margin-top: 6px; }
.tag-suggest-label { font-size: 0.75rem; color: var(--text-3); line-height: 1.6; }
.tag-suggest {
  border: 1px solid var(--border-1); background: #fff; color: var(--text-2);
  font-size: 0.75rem; padding: 2px 8px; border-radius: 4px; cursor: pointer;
}
.tag-suggest:hover { border-color: var(--brand); color: var(--brand); }
.tag-suggest:disabled { opacity: 0.3; cursor: not-allowed; }

/* ========== SLA Progress ========== */
.sla-progress {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--color-border-light, var(--border-1));
}

.sla-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.sla-label {
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-tertiary, var(--text-3));
}

.sla-value {
  font-size: var(--text-xs, 0.75rem);
  font-weight: var(--weight-medium, 500);
  color: var(--state-warning, var(--warning));
}

.sla-value-breached {
  color: var(--state-error, var(--danger)) !important;
  font-weight: var(--weight-semibold, 600);
}

.progress-bar {
  width: 100%;
  height: 8px;
  background: var(--color-bg-sunken, var(--surface-2));
  border-radius: var(--radius-full, 9999px);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: var(--radius-full, 9999px);
  transition: width 0.3s ease, background 0.2s ease;

  &.progress-fill-normal { background: var(--color-primary-light, var(--brand-hover)); }
  &.progress-fill-warning { background: var(--state-warning, var(--warning)); }
  &.progress-fill-error { background: var(--state-error, var(--danger)); }
}

/* ========== AI Insights ========== */
.ai-insights {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.insight-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.insight-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-md, 8px);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}

.insight-icon-blue {
  background: var(--color-primary-lighter, var(--brand-subtle));
  color: var(--color-primary-light, var(--brand-hover));
}

.insight-icon-warning {
  background: var(--state-warning-bg, var(--warning-subtle));
  color: var(--state-warning, var(--warning));
}

.insight-content {
  flex: 1;
  min-width: 0;
}

.insight-label {
  font-size: var(--text-sm, 0.875rem);
  font-weight: var(--weight-medium, 500);
  color: var(--color-text-primary, var(--text-1));
  margin: 0;
}

.insight-hint {
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-tertiary, var(--text-3));
  margin: 2px 0 0 0;
}

.insight-hint-warning {
  color: var(--state-warning, var(--warning));
}

/* ========== Activity Log ========== */
.activity-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.activity-empty {
  text-align: center;
  color: var(--color-text-tertiary, var(--text-3));
  padding: 20px;
  font-size: 0.8125rem;
}

.activity-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.activity-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;

  &.activity-dot-success { background: var(--state-success, var(--success)); }
  &.activity-dot-primary { background: var(--color-primary-light, var(--brand-hover)); }
  &.activity-dot-gray { background: var(--color-text-tertiary, var(--text-3)); }
  &.activity-dot-warning { background: var(--state-warning, var(--warning)); }
}

.activity-body {
  flex: 1;
  min-width: 0;
}

.activity-text {
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-secondary, var(--text-2));
  margin: 0;
}

.activity-detail {
  font-weight: var(--weight-medium, 500);
  color: var(--color-text-primary, var(--text-1));

  &.activity-detail-highlight {
    color: var(--color-primary, var(--brand));
  }
}

.activity-meta {
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-tertiary, var(--text-3));
  margin: 2px 0 0 0;
}

/* ========== Empty / Error States ========== */
.empty-card {
  max-width: 520px;
  margin: 40px auto;
  padding: 40px 32px;
  background: var(--color-surface, var(--surface-1));
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-lg, 12px);
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,0.04));
  text-align: center;
}

.empty-icon {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: var(--color-primary-lighter, var(--brand-subtle));
  color: var(--color-primary, var(--brand));
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}

.empty-icon-error {
  background: var(--state-error-bg, var(--danger-subtle));
  color: var(--state-error, var(--danger));
}

.loading-spinner {
  width: 40px;
  height: 40px;
  margin: 0 auto 16px;
  border: 3px solid var(--color-primary-lighter, var(--brand-subtle));
  border-top-color: var(--color-primary, var(--brand));
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.empty-title {
  font-size: var(--text-xl, 1.25rem);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary, var(--text-1));
  margin: 0 0 8px 0;
}

.empty-hint {
  font-size: var(--text-sm, 0.875rem);
  color: var(--color-text-tertiary, var(--text-3));
  margin: 0 0 20px 0;
}

.empty-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
  flex-wrap: wrap;
}

.attach-empty {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
  padding: 8px 0 12px;
}

.attach-list {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attach-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  background: var(--color-bg);
  border-radius: var(--radius-sm);
}

.attach-meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.attach-name {
  font-size: var(--text-sm);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attach-size {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

.attach-ops {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.attach-op {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;

  &:hover {
    background: var(--color-primary-lighter);
    color: var(--color-primary);
  }
}

.attach-op-danger:hover {
  background: var(--state-error-bg);
  color: var(--state-error);
}

.attach-file-input {
  display: none;
}

.attach-upload {
  width: 100%;
  justify-content: center;
}

/* ===== B5 闭环进度条 ===== */
.closure-progress-bar {
  display: flex;
  align-items: flex-start;
  gap: 0;
  padding: 16px 20px;
  background: var(--color-surface, #fff);
  border-radius: var(--radius-md, 8px);
  border: 1px solid var(--color-border-light, var(--border-1));
  margin-bottom: 16px;
}

.cp-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex: 1;
  position: relative;
  text-align: center;
}

/* 连接线 */
.cp-step:not(:last-child)::after {
  content: '';
  position: absolute;
  top: 11px;
  left: 50%;
  right: -50%;
  height: 2px;
  background: var(--color-border, var(--border-1));
  z-index: 0;
}

.cp-step.done:not(:last-child)::after {
  background: var(--success);
}

.cp-dot {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid var(--color-border, var(--border-1));
  background: var(--color-surface, #fff);
  color: var(--text-3);
  z-index: 1;
  flex-shrink: 0;
}

.cp-step.done .cp-dot {
  background: var(--success);
  border-color: var(--success);
  color: #fff;
}

.cp-step.current .cp-dot {
  border-color: var(--brand);
  background: #fff;
}

.cp-dot-inner {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand);
}

.cp-step.skipped .cp-dot {
  border-color: var(--border-2);
  background: var(--surface-2);
  color: var(--text-3);
}

.cp-step.skipped .cp-dot::after {
  content: '—';
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
}

.cp-label {
  font-size: 12px;
  color: var(--color-text-secondary, var(--text-2));
  font-weight: 500;
  white-space: nowrap;
}

.cp-step.done .cp-label { color: var(--success); }
.cp-step.current .cp-label { color: var(--brand); font-weight: 600; }
.cp-step.skipped .cp-label { color: var(--text-3); }

.cp-meta {
  font-size: 11px;
  color: var(--color-text-tertiary, var(--text-3));
  font-variant-numeric: tabular-nums;
}

.cp-meta-skipped {
  cursor: help;
  text-decoration: underline dotted;
  text-decoration-color: var(--text-3);
}

/* ===== B2~B4 弹窗 / 抽屉表单 ===== */
.dialog-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.dialog-form .form-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dialog-form .form-row label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary, var(--text-2));
}

.dialog-form .form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--color-border, var(--border-1));
  border-radius: 6px;
  font-size: 14px;
  font-family: inherit;
  box-sizing: border-box;
}

.form-hint {
  font-size: 12px;
  color: var(--color-text-tertiary, var(--text-3));
  margin: 4px 0 0 0;
}

.skip-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--color-text-secondary, var(--text-2));
  cursor: pointer;
}

/* ===== B2 处置阶段切换 + 动作列表 ===== */
.stage-switcher {
  display: flex;
  gap: 6px;
  padding: 8px 12px;
  background: var(--color-surface, #fff);
  border-radius: var(--radius-md, 8px);
  border: 1px solid var(--color-border-light, var(--border-1));
  margin-bottom: 16px;
}

.stage-btn {
  padding: 6px 14px;
  border: 1px solid var(--color-border, var(--border-1));
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  color: var(--color-text-secondary, var(--text-2));
  cursor: pointer;
  transition: all 0.15s ease;
}

.stage-btn:hover { border-color: var(--color-primary, var(--brand)); color: var(--color-primary, var(--brand)); }
.stage-btn.active { background: var(--color-primary, var(--brand)); color: #fff; border-color: var(--color-primary, var(--brand)); }

.action-list-section {
  margin-bottom: 16px;
}

.action-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border-light, var(--border-1));
  margin-bottom: 6px;
}

.action-item.action-ineffective {
  opacity: 0.6;
  border-style: dashed;
}

.action-type-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: var(--info-subtle);
  color: var(--info);
}

.action-type-badge.action-type-mitigate { background: var(--warning-subtle); color: var(--warning); }
.action-type-badge.action-type-fix { background: var(--success-subtle); color: var(--success); }
.action-type-badge.action-type-rollback { background: var(--danger-subtle); color: var(--danger); }
.action-type-badge.action-type-verify { background: #E0E7FF; color: #4338CA; }

.action-summary { flex: 1; color: var(--color-text-primary, var(--text-1)); }
.action-eff { font-size: 11px; padding: 1px 6px; border-radius: 3px; }
.eff-ok { background: var(--success-subtle); color: var(--success); }
.eff-no { background: var(--danger-subtle); color: var(--danger); }
.action-meta { font-size: 11px; color: var(--color-text-tertiary, var(--text-3)); white-space: nowrap; }
</style>