<script setup lang="ts">
/**
 * KnowledgeSinkDrawer.vue — 知识库→工单闭环（PRD §5.2 / 阶段7 复盘归档，方案 A）
 *
 * 流程：
 *   打开抽屉 → 调 chatStream 让 AI 把工单内容整理为结构化 RCA 复盘 Markdown
 *   （故障现象 / 影响范围 / 根因分析 / 处理步骤 / 预防措施 / 改进项）
 *   → 用户可编辑标题/分类/标签/正文
 *   → 「发布」调 createKnowledgeDoc({ publish: true, sourceTicketId }) 立即向量化
 *   → 下次类似故障 AI 可引用；文档反查源工单（B-2 来源回链）
 *
 * 设计原则（遵循契约）：
 *   - AI 整理草稿，用户审核后手动发布（PRD §6.1 原则：AI 不替人决策）
 *   - 重复内容（40021）提示跳转到现有文档，不静默吞掉
 *   - Markdown 渲染用 DOMPurify 白名单（前端 CLAUDE.md 第 12 项）
 *   - 加载 / 失败 / 空三态严格区分（6.18 契约）
 *   - 发布成功后只发事件，刷新策略由父组件决定（6.17 契约）
 */
import { notify } from '@/utils/notify'
import { ref, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  BookPlus, RefreshCw, Square, Send, AlertCircle,
  CheckCircle, Loader, Sparkles
} from 'lucide-vue-next'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { chatStream } from '@/api/chat'
import {
  createKnowledgeDoc,
  fetchKnowledgeDocCategories,
  fetchKnowledgeDocHotTags,
  DuplicateContentError,
} from '@/api/knowledge'
import type {
  KnowledgeDocCreateRequest,
  KnowledgeDocCategory,
  KnowledgeHotTag,
  SSEStartEvent,
  SSEToolStatusEvent,
  SSETokenEvent,
  SSECompleteEvent,
  SSEErrorEvent,
} from '@/api/types'

// ==================== Props / Emits ====================

const props = defineProps<{
  /** 抽屉开关 */
  modelValue: boolean
  /** 工单 ID */
  ticketId: string
  /** 工单标题 */
  ticketTitle: string
  /** 工单服务名（用于分类建议） */
  ticketService: string
  /** 工单描述 */
  ticketDescription: string
  /** 工单回复列表（用于 AI 整理上下文） */
  ticketReplies: Array<{ role: string; author: string; time: string; content: string }>
  /** 工单活动流（用于 AI 整理上下文） */
  ticketActivities: Array<{ text: string; detail?: string; user: string; time: string }>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 发布成功后通知父组件（父组件按自身上下文决定是否刷新，6.17 契约） */
  'published': [docId: number, title: string]
  /** 跳转到既有重复文档 */
  'goto-doc': [docId: number]
}>()

// ==================== 三态加载（6.18 契约）====================

type LoadState = 'idle' | 'loading' | 'done' | 'error'
const loadState = ref<LoadState>('idle')

// ==================== 编辑表单 ====================

const formTitle = ref('')
const formCategory = ref('')
const formTags = ref<string[]>([])
const formContent = ref('')
const formSummary = ref('')

// ==================== AI 整理流 ====================

let abortController: AbortController | null = null
const streaming = ref(false)
const streamTraceId = ref('')
const streamCost = ref(0)

// ==================== 分类 / 标签建议 ====================

const categoryOptions = ref<KnowledgeDocCategory[]>([])
const hotTags = ref<KnowledgeHotTag[]>([])
const suggestionsLoading = ref(false)

// ==================== 发布状态 ====================

const publishing = ref(false)

// ==================== Markdown 渲染（DOMPurify 白名单，前端 CLAUDE.md 第 12 项）====================

marked.setOptions({ breaks: true, gfm: true })

const renderMarkdown = (text: string): string => {
  if (!text) return ''
  const raw = marked.parse(text) as string
  return DOMPurify.sanitize(raw, {
    ALLOWED_TAGS: [
      'p', 'br', 'strong', 'em', 'del', 'code', 'pre', 'blockquote',
      'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'hr', 'span',
    ],
    ALLOWED_ATTR: ['href', 'target', 'rel', 'class'],
  })
}

// ==================== 构建工单上下文（供 AI 整理）====================

const buildSinkQuery = (): string => {
  const parts: string[] = []
  parts.push(`请把以下工单处理过程整理为一篇结构化的运维故障复盘（RCA）知识文档，供知识库沉淀。`)
  parts.push(`要求输出 Markdown，必须包含六个章节，按顺序输出：`)
  parts.push(`## 故障现象\n简述问题表现、发生时间、影响的服务与范围。`)
  parts.push(`## 影响范围\n评估业务影响程度、持续时间、受影响用户/系统。证据不足写"待补充"。`)
  parts.push(`## 根因分析\n根据处理过程推断根本原因，区分"现象"与"根因"。证据不足写"待补充"，不得编造因果。`)
  parts.push(`## 处理步骤\n按时间线列出关键操作，执行命令用代码块包裹，标注每步目的。`)
  parts.push(`## 预防措施\n给出可执行的预防建议（监控、告警阈值、配置规范等）。`)
  parts.push(`## 改进项\n列出后续待跟进事项（如"补充监控指标""更新 SOP"），每条一行。`)
  parts.push(`不要复述工单号、负责人等元信息，只写技术内容。\n`)
  parts.push(`【工单标题】${props.ticketTitle}`)
  parts.push(`【服务】${props.ticketService}`)
  parts.push(`【描述】${props.ticketDescription}`)

  if (props.ticketReplies?.length) {
    const replyText = props.ticketReplies
      .map(r => `[${r.time}][${r.author}] ${r.content}`)
      .join('\n')
    parts.push(`【处理回复】\n${replyText}`)
  }
  if (props.ticketActivities?.length) {
    const actText = props.ticketActivities
      .map(a => `[${a.time}][${a.user}] ${a.text}${a.detail ? `（${a.detail}）` : ''}`)
      .join('\n')
    parts.push(`【活动流】\n${actText}`)
  }

  return parts.join('\n')
}

// ==================== 触发 AI 整理 ====================

const generateDraft = async () => {
  if (streaming.value) return

  loadState.value = 'loading'
  streaming.value = true
  formContent.value = ''
  streamTraceId.value = ''
  streamCost.value = 0

  abortController = new AbortController()

  try {
    await chatStream(
      buildSinkQuery(),
      {
        onStart: (data: SSEStartEvent) => {
          streamTraceId.value = data.traceId
        },
        onToolStatus: (_data: SSEToolStatusEvent) => {
          // 整理模式不展示工具状态结构化 UI
        },
        onToken: (data: SSETokenEvent) => {
          formContent.value += data.text
        },
        onComplete: (data: SSECompleteEvent) => {
          streamCost.value = data.costRmb ?? 0
          if (!formTitle.value) {
            // AI 不产出标题，前端按工单标题预填
            formTitle.value = `【故障复盘】${props.ticketTitle}`
          }
          loadState.value = 'done'
          streaming.value = false
        },
        onError: (data: SSEErrorEvent) => {
          formContent.value += `\n\n❌ ${data.message || 'AI 整理失败，请稍后重试或手动编写'}`
          loadState.value = 'error'
          streaming.value = false
        },
      },
      abortController
      // 整理模式不传 sessionId — 单次整理，不关联多轮记忆
    )
  } catch (error: unknown) {
    const err = error as { name?: string; message?: string }
    if (err?.name === 'AbortError' || abortController?.signal.aborted) {
      formContent.value += '\n\n_（已停止生成）_'
      if (!formContent.value.trim()) {
        loadState.value = 'idle'
      } else {
        loadState.value = 'done'
      }
    } else {
      formContent.value += `\n\n❌ 连接失败：${err?.message || '网络错误'}`
      loadState.value = 'error'
    }
    streaming.value = false
  } finally {
    abortController = null
  }
}

const stopStream = () => {
  if (!streaming.value || !abortController) return
  abortController.abort()
}

const regenerate = () => {
  generateDraft()
}

// ==================== 编辑 / 预览切换 ====================

type EditTab = 'edit' | 'preview'
const activeTab = ref<EditTab>('edit')
const previewScroll = ref<HTMLElement | null>(null)

const switchTab = (tab: EditTab) => {
  activeTab.value = tab
  if (tab === 'preview') {
    nextTick(() => {
      previewScroll.value?.scrollTo({ top: 0 })
    })
  }
}

// ==================== 分类 / 标签建议加载 ====================

const loadSuggestions = async () => {
  suggestionsLoading.value = true
  try {
    const [cats, tags] = await Promise.all([
      fetchKnowledgeDocCategories().catch(() => [] as KnowledgeDocCategory[]),
      fetchKnowledgeDocHotTags(15).catch(() => [] as KnowledgeHotTag[]),
    ])
    categoryOptions.value = cats
    hotTags.value = tags
  } finally {
    suggestionsLoading.value = false
  }
}

/** 标签输入上限 20（对齐后端 MAX_TAGS_PER_DOC） */
const onTagChange = (val: string[]) => {
  if (val.length > 20) {
    notify.warning('最多 20 个标签')
    formTags.value = val.slice(0, 20)
  }
}

// ==================== 发布 ====================

const canPublish = computed(() => {
  return !publishing.value
    && !streaming.value
    && formTitle.value.trim().length > 0
    && formContent.value.trim().length > 0
})

const handlePublish = async () => {
  if (!formTitle.value.trim()) {
    notify.warning('请填写文档标题')
    return
  }
  if (!formContent.value.trim()) {
    notify.warning('文档正文不能为空')
    return
  }

  publishing.value = true
  try {
    const req: KnowledgeDocCreateRequest = {
      title: formTitle.value.trim(),
      category: formCategory.value.trim() || undefined,
      content: formContent.value,
      summary: formSummary.value.trim() || undefined,
      tags: formTags.value,
      publish: true, // 立即向量化
      knowledgeSource: 'ticket-sink',
      // L1.5 来源回链：记录源工单，沉淀后在工单详情页展示「已沉淀为知识」徽标
      sourceTicketId: Number(props.ticketId),
      sourceType: 'TICKET',
    }
    const result = await createKnowledgeDoc(req)

    // 近似重复告警（不阻断）
    if (result.nearDuplicates?.length) {
      notify.warning(
        `检测到 ${result.nearDuplicates.length} 篇近似文档，已发布但仍建议复核去重`
      )
    }

    const retrievable = result.retrievable
    const indexStatus = result.indexStatus
    let msg = `知识文档已发布（ID: ${result.id}）`
    if (retrievable) {
      msg += '，已向量化，下次类似故障 AI 可引用'
    } else if (indexStatus === 'PENDING') {
      msg += '，向量化处理中，稍后可检索'
    } else if (indexStatus === 'FAILED') {
      msg += '，但向量化失败，可在知识库重试'
    } else if (indexStatus === 'SKIPPED') {
      msg += '，未建立索引'
    }
    notify.success(msg, { duration: 6000 })

    emit('published', result.id, formTitle.value.trim())
    closeDrawer()
  } catch (error: unknown) {
    if (error instanceof DuplicateContentError) {
      // 40021 重复内容：提示跳转到现有文档，不静默吞掉
      const dupId = error.duplicateDocId
      const dupTitle = error.duplicateTitle
      try {
        await ElMessageBox.confirm(
          dupTitle
            ? `知识库已存在高度相似的文档：「${dupTitle}」${dupId ? `（ID: ${dupId}）` : ''}。\n是否跳转查看？`
            : `知识库已存在高度相似的文档${dupId ? `（ID: ${dupId}）` : ''}。\n是否跳转查看？`,
          '内容重复',
          {
            confirmButtonText: '跳转查看',
            cancelButtonText: '留在此页',
            type: 'warning',
          }
        )
        if (dupId) {
          emit('goto-doc', dupId)
          closeDrawer()
        }
      } catch {
        // 用户选择留在此页，不做处理
      }
    } else {
      const err = error as Error
      notify.error(err?.message || '发布失败，请稍后重试')
    }
  } finally {
    publishing.value = false
  }
}

// ==================== 抽屉开关 ====================

const closeDrawer = () => {
  if (streaming.value) {
    stopStream()
  }
  emit('update:modelValue', false)
}

const visible = computed({
  get: () => props.modelValue,
  set: (val: boolean) => emit('update:modelValue', val),
})

// 抽屉打开时触发 AI 整理 + 加载建议
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      activeTab.value = 'edit'
      // 重置表单
      formTitle.value = `【故障复盘】${props.ticketTitle}`
      formCategory.value = props.ticketService || ''
      formTags.value = []
      formSummary.value = ''
      loadSuggestions()
      generateDraft()
    }
  }
)

onBeforeUnmount(() => {
  if (abortController) {
    abortController.abort()
  }
})
</script>

<template>
  <el-drawer
    v-model="visible"
    title="沉淀为知识"
    direction="rtl"
    size="680px"
    :close-on-click-modal="false"
    :before-close="((done: () => void) => { closeDrawer(); done() }) as any"
  >
    <template #header>
      <div class="drawer-header">
        <BookPlus :size="18" class="header-icon" />
        <span class="header-title">沉淀为知识</span>
        <span class="header-sub" v-if="ticketId">· {{ ticketId }}</span>
      </div>
    </template>

    <div class="sink-body">
      <!-- AI 整理状态条 -->
      <div class="status-bar" :class="loadState">
        <template v-if="loadState === 'loading'">
          <Loader :size="14" class="spin" />
          <span>AI 正在整理工单内容为结构化文档{{ streamTraceId ? ` · ${streamTraceId}` : '' }}</span>
          <button class="link-btn" @click="stopStream">
            <Square :size="12" /> 停止
          </button>
        </template>
        <template v-else-if="loadState === 'done'">
          <CheckCircle :size="14" />
          <span>AI 整理完成，请审核正文后发布</span>
          <span v-if="streamCost" class="cost">成本 ¥{{ streamCost.toFixed(4) }}</span>
          <button class="link-btn" @click="regenerate">
            <RefreshCw :size="12" /> 重新生成
          </button>
        </template>
        <template v-else-if="loadState === 'error'">
          <AlertCircle :size="14" />
          <span>AI 整理失败，可重新生成或手动编写正文</span>
          <button class="link-btn" @click="regenerate">
            <RefreshCw :size="12" /> 重新生成
          </button>
        </template>
        <template v-else>
          <Sparkles :size="14" />
          <span>点击下方开始生成</span>
          <button class="link-btn" @click="generateDraft">
            <RefreshCw :size="12" /> 生成
          </button>
        </template>
      </div>

      <!-- 标题 -->
      <div class="field">
        <label class="field-label">文档标题 <span class="required">*</span></label>
        <input
          v-model="formTitle"
          class="field-input"
          placeholder="文档标题"
          maxlength="200"
        />
      </div>

      <!-- 分类 + 标签 -->
      <div class="field-row">
        <div class="field flex-1">
          <label class="field-label">分类</label>
          <el-select
            v-model="formCategory"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="选择或输入分类"
            class="field-select"
          >
            <el-option
              v-for="cat in categoryOptions"
              :key="cat.name"
              :label="`${cat.name}（${cat.count}）`"
              :value="cat.name"
            />
          </el-select>
        </div>
        <div class="field flex-2">
          <label class="field-label">标签（最多 20）</label>
          <el-select
            v-model="formTags"
            multiple
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="输入标签后回车"
            class="field-select"
            :multiple-limit="20"
            @change="onTagChange"
          >
            <el-option
              v-for="tag in hotTags"
              :key="tag.tag"
              :label="`${tag.tag}（${tag.count}）`"
              :value="tag.tag"
            />
          </el-select>
        </div>
      </div>

      <!-- 正文编辑 / 预览 -->
      <div class="field">
        <div class="field-label-row">
          <label class="field-label">正文 <span class="required">*</span></label>
          <div class="tab-switch">
            <button
              class="tab-btn"
              :class="{ active: activeTab === 'edit' }"
              @click="switchTab('edit')"
            >编辑</button>
            <button
              class="tab-btn"
              :class="{ active: activeTab === 'preview' }"
              @click="switchTab('preview')"
            >预览</button>
          </div>
        </div>

        <!-- 加载失败兜底（6.18：不存在与加载失败分开） -->
        <div v-if="loadState === 'error' && !formContent.trim()" class="empty-state error-state">
          <AlertCircle :size="28" />
          <p>AI 整理失败</p>
          <button class="btn-outline" @click="regenerate">
            <RefreshCw :size="14" /> 重新生成
          </button>
        </div>

        <!-- 加载中且无内容 -->
        <div v-else-if="loadState === 'loading' && !formContent.trim()" class="empty-state">
          <Loader :size="28" class="spin" />
          <p>AI 正在整理工单内容…</p>
        </div>

        <!-- 编辑模式 -->
        <textarea
          v-else-if="activeTab === 'edit'"
          v-model="formContent"
          class="content-textarea"
          rows="20"
          placeholder="支持 Markdown 语法。AI 整理后会在此填充，您可直接编辑…"
        ></textarea>

        <!-- 预览模式 -->
        <div v-else ref="previewScroll" class="content-preview markdown-body">
          <div v-if="formContent.trim()" v-html="renderMarkdown(formContent)"></div>
          <div v-else class="preview-empty">暂无内容</div>
        </div>
      </div>

      <!-- 摘要 -->
      <div class="field">
        <label class="field-label">摘要（可选）</label>
        <textarea
          v-model="formSummary"
          class="field-textarea"
          rows="2"
          placeholder="一句话概括本篇知识"
          maxlength="500"
        ></textarea>
      </div>
    </div>

    <template #footer>
      <div class="drawer-footer">
        <button class="btn-outline" @click="closeDrawer" :disabled="publishing">
          取消
        </button>
        <button
          class="btn-primary"
          :disabled="!canPublish"
          @click="handlePublish"
        >
          <Send v-if="!publishing" :size="14" />
          <Loader v-else :size="14" class="spin" />
          {{ publishing ? '发布中…' : '发布并入库' }}
        </button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.drawer-header {
  display: flex;
  align-items: center;
  gap: 6px;

  .header-icon { color: var(--color-primary); }
  .header-title { font-size: var(--text-sm); font-weight: var(--weight-semibold); }
  .header-sub { font-size: var(--text-xs); color: var(--color-text-tertiary); }
}

.sink-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 0 4px;
}

/* 状态条 */
.status-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  font-size: var(--text-xs);
  background: var(--color-primary-lighter);
  color: var(--color-text-secondary);

  &.loading { background: var(--color-primary-lighter); }
  &.done { background: rgba(16, 185, 129, 0.08); color: #059669; }
  &.error { background: rgba(239, 68, 68, 0.08); color: var(--state-error); }
  &.idle { background: var(--color-bg-sunken); }

  .cost { margin-left: 4px; color: var(--color-text-tertiary); }

  .spin { animation: spin 1s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }

  .link-btn {
    margin-left: auto;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    background: none;
    border: none;
    color: var(--color-primary);
    cursor: pointer;
    font-size: var(--text-xs);
    &:hover { text-decoration: underline; }
  }
}

/* 字段 */
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-row {
  display: flex;
  gap: 12px;
  .flex-1 { flex: 1; }
  .flex-2 { flex: 2; }
}
.field-label {
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  color: var(--color-text-secondary);
  .required { color: var(--state-error); }
}
.field-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.field-input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--color-bg-primary);
  &:focus { outline: none; border-color: var(--color-primary); }
}
.field-textarea {
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--color-bg-primary);
  resize: vertical;
  &:focus { outline: none; border-color: var(--color-primary); }
}
.field-select { width: 100%; }

/* tab 切换 */
.tab-switch {
  display: flex;
  gap: 2px;
  background: var(--color-bg-sunken);
  border-radius: var(--radius-sm);
  padding: 2px;
}
.tab-btn {
  padding: 3px 10px;
  border: none;
  background: none;
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  cursor: pointer;
  &.active {
    background: var(--color-bg-primary);
    color: var(--color-primary);
    font-weight: var(--weight-medium);
  }
}

/* 正文编辑/预览 */
.content-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--text-xs);
  font-family: var(--font-mono);
  line-height: 1.6;
  background: var(--color-bg-primary);
  resize: vertical;
  min-height: 320px;
  &:focus { outline: none; border-color: var(--color-primary); }
}
.content-preview {
  min-height: 320px;
  max-height: 520px;
  overflow-y: auto;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-primary);
  font-size: var(--text-xs);
  line-height: 1.7;
}
.preview-empty {
  color: var(--color-text-tertiary);
  text-align: center;
  padding: 40px 0;
}

/* 空状态 / 错误态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 320px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-primary);
  color: var(--color-text-tertiary);

  p { font-size: var(--text-xs); margin: 0; }
  &.error-state { color: var(--state-error); }
  .spin { animation: spin 1s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
}

/* 按钮 */
.btn-outline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-primary);
  color: var(--color-text-secondary);
  font-size: var(--text-xs);
  cursor: pointer;
  &:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 16px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: white;
  font-size: var(--text-xs);
  cursor: pointer;
  &:hover:not(:disabled) { background: var(--color-primary-dark, var(--color-primary)); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
  .spin { animation: spin 1s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* Markdown 通用样式（复用 AIContextPanel 视觉） */
.markdown-body {
  white-space: normal;

  :deep(p) { margin: 0 0 6px; &:last-child { margin-bottom: 0; } }
  :deep(h1), :deep(h2), :deep(h3), :deep(h4) {
    margin: 10px 0 4px;
    font-size: var(--text-sm);
    font-weight: var(--weight-semibold);
    color: var(--color-text-primary);
    &:first-child { margin-top: 0; }
  }
  :deep(ul), :deep(ol) { margin: 0 0 6px; padding-left: 16px; }
  :deep(li) { margin-bottom: 2px; }
  :deep(strong) { font-weight: var(--weight-semibold); }
  :deep(a) { color: var(--color-primary); text-decoration: underline; }
  :deep(hr) { border: none; border-top: 1px solid var(--color-border-light); margin: 8px 0; }
  :deep(blockquote) {
    margin: 6px 0;
    padding: 4px 10px;
    border-left: 3px solid var(--color-primary-light);
    background: var(--color-primary-lighter);
    color: var(--color-text-secondary);
    font-size: 11px;
  }
  :deep(code) {
    padding: 1px 5px;
    background: var(--color-bg-sunken);
    color: var(--color-primary);
    border-radius: var(--radius-sm);
    font-family: var(--font-mono);
    font-size: 11px;
  }
  :deep(pre) {
    margin: 6px 0;
    padding: 8px 10px;
    background: #1E293B;
    border-radius: var(--radius-md);
    overflow-x: auto;

    code {
      display: block;
      padding: 0;
      background: transparent;
      color: #E2E8F0;
      font-size: 11px;
      line-height: 1.5;
    }
  }
  :deep(table) {
    width: 100%;
    margin: 6px 0;
    border-collapse: collapse;
    font-size: 11px;
  }
  :deep(th), :deep(td) {
    padding: 4px 6px;
    border: 1px solid var(--color-border-light);
    text-align: left;
  }
}
</style>
