<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onBeforeUnmount, watch, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import TurndownService from 'turndown'
import DOMPurify from 'dompurify'
import {
  Save, Send, Upload, Sparkles, Settings2, FileText, Plus,
  Pencil, Trash2, BookOpen, Eye, Code2, ChevronDown, Lightbulb,
  Table2, Minus, Heading2, Heading3, SquareTerminal, Wrench,
  ListChecks, TriangleAlert, BookTemplate, MoreHorizontal,
  ListTree,
} from 'lucide-vue-next'
import { useKnowledgeStore } from '@/stores/knowledge'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { saveDraft, loadDraft, clearDraft } from '@/utils/draftStorage'
import {
  createKnowledgeCategory,
  DuplicateContentError,
  fetchKnowledgeCategories,
  fetchKnowledgeTags,
  VersionConflictError,
} from '@/api/knowledge'
import type {
  KnowledgeCategoryEntity,
  KnowledgeDocCreateRequest,
  KnowledgeDocSaveResult,
  KnowledgeDocUpdateRequest,
  KnowledgeTag,
} from '@/api/types'
import KnowledgeTreeSidebar from '@/components/knowledge/KnowledgeTreeSidebar.vue'
import CollapsiblePanel from '@/components/common/CollapsiblePanel.vue'
import CollapseToggle from '@/components/common/CollapseToggle.vue'
import RailButton from '@/components/common/RailButton.vue'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'
import { useHotkeys } from '@/composables/useHotkeys'
import { useMediaQuery } from '@/composables/useMediaQuery'
import { notify, handleServerError } from '@/utils/notify'

const route = useRoute()
const router = useRouter()
const store = useKnowledgeStore()
const MdEditor = defineAsyncComponent(async () => {
  await import('md-editor-v3/lib/style.css')
  return (await import('md-editor-v3')).MdEditor
})

/**
 * 富文本编辑器同样异步加载。
 *
 * 此前它是静态 import —— 静态依赖会被提升进入口图，
 * 导致 wangEditor（约 977KB / gzip 327KB）在**每个页面**首屏都被下载，
 * 哪怕用户从不编辑文档。路由本身是 lazy 的，但静态 import 让 lazy 失效。
 *
 * 改为 defineAsyncComponent 后，只有真正切到富文本模式才拉取。
 */
const KnowledgeRichEditor = defineAsyncComponent(
  () => import('@/components/knowledge/KnowledgeRichEditor.vue')
)

// ==================== 路由 / ID 归一 ====================

const isNew = computed(() => route.params.id === 'new' || route.params.id === undefined)
const id = computed(() => {
  if (isNew.value) return null
  const n = Number(route.params.id)
  return Number.isFinite(n) && n > 0 ? n : NaN
})
const draftToken = computed(() => typeof route.query.draft === 'string' ? route.query.draft : 'default')
const draftKey = computed(() => (isNew.value ? `knowledge-doc-new-${draftToken.value}` : `knowledge-doc-${id.value}`))

// ==================== 表单 ====================

const emptyForm = () => ({
  title: '',
  category: '',
  summary: '',
  tags: [] as string[],
  content: ''
})
const formData = ref(emptyForm())
type EditorForm = ReturnType<typeof emptyForm>
interface EditorDraftState {
  form: EditorForm
  baseVersion: number | null
  publishOnCreate: boolean
  changeReason: string
  editorMode: 'visual' | 'markdown'
}
/** 右侧面板 Tab */
const activeSideTab = ref<'settings' | 'toc'>('toc')
/** 编辑时从内容提取的文章大纲项 */
interface TocItem { id: string; text: string; level: 2 | 3; lineIndex?: number; elementIndex?: number }
interface RichEditorExpose { insertHtml: (html: string) => void; focus: () => void }
type BlockCommand = 'h2' | 'h3' | 'callout' | 'code' | 'table' | 'divider'
type StarterTemplateKey = 'blank' | 'troubleshooting' | 'runbook' | 'postmortem'

const tocItems = ref<TocItem[]>([])
const richEditorRef = ref<RichEditorExpose | null>(null)
const directoryCategories = ref<KnowledgeCategoryEntity[]>([])
const managedTags = ref<KnowledgeTag[]>([])
/** 新建时：是否保存后立即发布并向量化 */
const publishOnCreate = ref(false)
/** 编辑时：当前持有的版本号（乐观锁），由详情加载得到 */
const currentVersion = ref(0)
/** 编辑时：变更说明（写入版本历史 change_reason） */
const changeReason = ref('')

const loadingEdit = ref(false)
const editorReady = ref(false)
const saving = ref(false)
const draftSavedAt = ref<string | null>(null)
const mobileSideOpen = ref(false)
const editorPreview = ref(true)
const editorMode = ref<'visual' | 'markdown'>('visual')
const starterDismissed = ref(false)

/**
 * 左右侧栏折叠状态。
 *
 * 折叠/持久化/过渡动画/命中区已统一收敛到 CollapsiblePanel，
 * 此处只保留状态与实例引用供快捷键与图标轨使用。
 */
const leftSidebarCollapsed = ref(false)
const rightSidebarCollapsed = ref(false)
const leftPanelRef = ref<InstanceType<typeof CollapsiblePanel> | null>(null)
const rightPanelRef = ref<InstanceType<typeof CollapsiblePanel> | null>(null)

/**
 * `[` 收左栏（目录）、`]` 收右栏（大纲/属性）。
 *
 * 编辑器正文是 contenteditable / textarea，useHotkeys 已排除可编辑元素，
 * 故在正文中打 `[` `]` 不会误触折叠。
 */
/**
 * 窄屏（≤760px）下右栏改由已有的悬浮抽屉（mobileSideOpen）控制，
 * 此时禁用 CollapsiblePanel 的折叠——否则两套开合入口并存会互相矛盾：
 * 用户先折叠再点抽屉按钮，内容因已折叠而未渲染，按钮看似失灵。
 * 断点与下方 `@media (max-width: 760px)` 的 `.ce-sidebar` 抽屉样式保持一致。
 */
const isNarrowScreen = useMediaQuery('(max-width: 760px)')

useHotkeys([
  { key: '[', description: '收起/展开目录', handler: () => leftPanelRef.value?.toggle() },
  { key: ']', description: '收起/展开侧栏', handler: () => rightPanelRef.value?.toggle() }
])
const turndown = new TurndownService({ headingStyle: 'atx', codeBlockStyle: 'fenced' })

const starterTemplates = [
  {
    key: 'blank' as const,
    label: '空白文档',
    icon: FileText,
    content: '<p><br></p>',
  },
  {
    key: 'troubleshooting' as const,
    label: '故障排查',
    icon: Wrench,
    content: '<h2>现象与影响</h2><p><br></p><h2>排查过程</h2><p><br></p><h2>根因与处理</h2><p><br></p><h2>验证结果</h2><p><br></p>',
  },
  {
    key: 'runbook' as const,
    label: '操作手册',
    icon: ListChecks,
    content: '<h2>适用范围</h2><p><br></p><h2>前置检查</h2><p><br></p><h2>操作步骤</h2><ol><li><br></li></ol><h2>回滚方案</h2><p><br></p>',
  },
  {
    key: 'postmortem' as const,
    label: '故障复盘',
    icon: TriangleAlert,
    content: '<h2>事件摘要</h2><p><br></p><h2>时间线</h2><p><br></p><h2>根因分析</h2><p><br></p><h2>改进事项</h2><ul><li><br></li></ul>',
  },
] satisfies Array<{ key: StarterTemplateKey; label: string; icon: typeof FileText; content: string }>

// 标签上限对齐后端 MAX_TAGS_PER_DOC
const MAX_TAGS = 20

const categoryLabel = (category: KnowledgeCategoryEntity) => {
  const names: string[] = [category.name]
  const seen = new Set<number>([category.id])
  let parentId = category.parentId
  while (parentId != null && !seen.has(parentId)) {
    const parent = directoryCategories.value.find(item => item.id === parentId)
    if (!parent) break
    names.unshift(parent.name)
    seen.add(parent.id)
    parentId = parent.parentId
  }
  return names.join(' / ')
}

const selectedCategoryId = computed<number | null>(() => {
  const selected = directoryCategories.value.find(item => item.name === formData.value.category)
  return selected?.id ?? null
})

// ==================== 离开保护 / 草稿 ====================

const isDirty = ref(false)
// 新建时「保存后立即发布」开关（带 setter，供 v-model）
const publishOnChange = computed({
  get: () => publishOnCreate.value,
  set: (v) => { publishOnCreate.value = v; isDirty.value = true }
})

watch(formData, () => { isDirty.value = true }, { deep: true })
watch(publishOnChange, () => { isDirty.value = true })

/**
 * 离开确认（三选一）。
 *
 * 草稿已由 saveDraft 自动暂存到本机，故「离开」与「丢弃」是两件不同的事：
 * - 本机暂存并离开 → 草稿留着，下次进来自动恢复
 * - 放弃 → clearDraft 彻底丢掉这次编辑
 * - 关闭/Esc → 留在页面继续编辑
 *
 * 原先这段逻辑在页内「返回知识库」按钮的 handleBack 里，只覆盖点那个按钮的路径；
 * 现统一到 useDirtyGuard 的 onBeforeRouteLeave，面包屑跳转、导航栏跳转、
 * 浏览器后退全部走同一套确认，不会有绕过草稿保护的入口。
 */
useDirtyGuard(isDirty, {
  message: '有未提交的内容，是否暂存到本机后离开？',
  title: '提示',
  confirmText: '本机暂存并离开',
  discardText: '放弃',
  onConfirm: () => {
    handleSaveDraft()
    isDirty.value = false
  },
  onDiscard: () => {
    clearDraft(draftKey.value)
    isDirty.value = false
  }
})

let autoSaveTimer: number | null = null
const currentDraftState = (): EditorDraftState => ({
  form: { ...formData.value, tags: [...formData.value.tags] },
  baseVersion: isNew.value ? null : currentVersion.value,
  publishOnCreate: publishOnCreate.value,
  changeReason: changeReason.value,
  editorMode: editorMode.value,
})

const saveCurrentDraft = () => saveDraft(draftKey.value, currentDraftState())

const normalizeDraftState = (raw: EditorDraftState | EditorForm): EditorDraftState => {
  if ('form' in raw) return raw
  return {
    form: raw,
    baseVersion: null,
    publishOnCreate: false,
    changeReason: '',
    editorMode: 'visual',
  }
}

const startAutoSave = () => {
  if (autoSaveTimer) clearInterval(autoSaveTimer)
  autoSaveTimer = window.setInterval(() => {
    if (isDirty.value) {
      saveCurrentDraft()
    }
  }, 3000)
}

const syncEditorViewport = () => {
  editorPreview.value = window.innerWidth > 760
}

const isHtmlContent = (content: string) => /^\s*</.test(content)

const toVisualContent = async (content: string) => {
  if (!content.trim()) return '<p><br></p>'
  const html = isHtmlContent(content) ? content : String(await marked(content))
  // 净化后送入编辑器，防止恶意文档在编辑态执行脚本
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['p','br','strong','em','u','s','del','code','pre','a','img','blockquote','hr','span','div','figure','figcaption','table','thead','tbody','tfoot','tr','th','td','h1','h2','h3','h4','h5','h6','ul','ol','li'],
    ALLOWED_ATTR: ['href','target','rel','class','src','alt','title','data-language']
  })
}

const hasMeaningfulContent = (content: string) => {
  if (!isHtmlContent(content)) return !!content.trim()
  const parsed = new DOMParser().parseFromString(content, 'text/html')
  return !!parsed.body.textContent?.replace(/\u200B/g, '').trim()
    || !!parsed.body.querySelector('img,table,pre,hr')
}

const showStarter = computed(() =>
  isNew.value
  && editorMode.value === 'visual'
  && !starterDismissed.value
  && !hasMeaningfulContent(formData.value.content)
)

const startWithTemplate = async (key: StarterTemplateKey) => {
  const template = starterTemplates.find(item => item.key === key)
  if (!template) return
  starterDismissed.value = true
  formData.value.content = template.content
  await nextTick()
  richEditorRef.value?.focus()
}

const switchEditorMode = async (mode: 'visual' | 'markdown') => {
  if (mode === editorMode.value) return
  starterDismissed.value = true
  formData.value.content = mode === 'visual'
    ? await toVisualContent(formData.value.content)
    : turndown.turndown(formData.value.content)
  editorMode.value = mode
  await nextTick()
}

const insertBlock = async (command: BlockCommand) => {
  const htmlBlocks: Record<BlockCommand, string> = {
    h2: '<h2>二级标题</h2><p><br></p>',
    h3: '<h3>三级标题</h3><p><br></p>',
    callout: '<blockquote><p><br></p></blockquote><p><br></p>',
    code: '<pre><code><br></code></pre><p><br></p>',
    table: '<table><tbody><tr><th>字段</th><th>说明</th></tr><tr><td><br></td><td><br></td></tr></tbody></table><p><br></p>',
    divider: '<hr><p><br></p>',
  }
  const markdownBlocks: Record<BlockCommand, string> = {
    h2: '## 二级标题\n\n',
    h3: '### 三级标题\n\n',
    callout: '> 提示内容\n\n',
    code: '```text\n\n```\n\n',
    table: '| 字段 | 说明 |\n| --- | --- |\n|  |  |\n\n',
    divider: '---\n\n',
  }

  starterDismissed.value = true
  if (editorMode.value === 'visual') {
    await nextTick()
    richEditorRef.value?.insertHtml(htmlBlocks[command])
    return
  }
  const prefix = formData.value.content.trimEnd()
  formData.value.content = `${prefix}${prefix ? '\n\n' : ''}${markdownBlocks[command]}`
}

// ==================== 加载 ====================

const loadCategoriesAndTags = async () => {
  const [categoryResult, tagResult] = await Promise.allSettled([
    fetchKnowledgeCategories(),
    fetchKnowledgeTags(),
  ])
  if (categoryResult.status === 'fulfilled') {
    directoryCategories.value = categoryResult.value
  } else {
    console.warn('[KnowledgeEditor] 加载目录分类失败', categoryResult.reason)
  }
  if (tagResult.status === 'fulfilled') {
    managedTags.value = tagResult.value
  } else {
    console.warn('[KnowledgeEditor] 加载标签失败', tagResult.reason)
  }
  await Promise.all([store.loadCategories(), store.loadHotTags()])
  if (tagResult.status === 'rejected') {
    managedTags.value = store.hotTags.map((item, index) => ({
      id: -(index + 1),
      name: item.tag,
      description: null,
      color: null,
      usageCount: item.count,
    }))
  }
}

const loadDoc = async () => {
  // 编辑态加载失败或跳转后，重置干净状态
  if (isNew.value) {
    starterDismissed.value = false
    formData.value = emptyForm()
    formData.value.category = typeof route.query.category === 'string' ? route.query.category : ''
    currentVersion.value = 0
    changeReason.value = ''
    publishOnCreate.value = false
    draftSavedAt.value = null
    const rawDraft = loadDraft<EditorDraftState | EditorForm>(draftKey.value)
    if (rawDraft) {
      const draft = normalizeDraftState(rawDraft)
      const merged = { ...formData.value, ...draft.form }
      merged.tags = draft.form.tags ?? []
      formData.value = merged
      publishOnCreate.value = draft.publishOnCreate
      changeReason.value = draft.changeReason
      editorMode.value = draft.editorMode
      notify.info('已恢复草稿')
      isDirty.value = true
    }
    if (editorMode.value === 'visual') {
      formData.value.content = await toVisualContent(formData.value.content)
    }
    return
  }

  if (Number.isNaN(id.value)) {
    notify.error('无效的文档 ID')
    router.replace('/knowledge')
    return
  }
  const docId = id.value
  if (docId == null) return

  loadingEdit.value = true
  try {
    const d = await store.loadDetail(docId)
    // store.loadDetail 不再抛错（三态收敛到 useResourceState 后，
    // 「不存在」与「加载失败」都以返回 null 表示），故按 store 状态区分：
    // notFound 跳回列表，error 由页面的三态区渲染重试入口
    if (!d) {
      if (store.detailStatus === 'notFound') {
        notify.error('文档不存在或已被删除')
        router.replace('/knowledge')
      }
      return
    }
    if (d.status === 'DEPRECATED' || d.status === 'ARCHIVED') {
      notify.warning('已废弃或已归档文档需要先恢复才能编辑')
      router.replace(`/knowledge/${docId}`)
      return
    }
    formData.value = {
      title: d.title,
      category: d.category ?? '',
      summary: d.summary ?? '',
      tags: [...d.tags],
      content: await toVisualContent(d.content),
    }
    currentVersion.value = d.version
    publishOnCreate.value = false
    changeReason.value = ''
    isDirty.value = false

    const rawDraft = loadDraft<EditorDraftState | EditorForm>(draftKey.value)
    if (rawDraft) {
      const draft = normalizeDraftState(rawDraft)
      if (draft.baseVersion == null || draft.baseVersion !== d.version) {
        try {
          await ElMessageBox.confirm(
            `本机草稿基于 ${draft.baseVersion == null ? '未知版本' : `v${draft.baseVersion}`}，服务器已是 v${d.version}。为避免覆盖他人修改，是否将草稿恢复为新文档？`,
            '草稿版本冲突',
            { confirmButtonText: '恢复为新文档', cancelButtonText: '忽略并清除', type: 'warning' }
          )
          const newDraftId = crypto.randomUUID()
          saveDraft(`knowledge-doc-new-${newDraftId}`, { ...draft, baseVersion: null })
          clearDraft(draftKey.value)
          isDirty.value = false
          await router.replace({ path: '/knowledge/editor/new', query: { draft: newDraftId } })
          return
        } catch {
          clearDraft(draftKey.value)
        }
      } else {
      try {
        await ElMessageBox.confirm(
          '发现此文档的本机暂存内容，是否恢复？恢复后仍需点击“保存”提交到服务器。',
          '恢复本机暂存',
          { confirmButtonText: '恢复', cancelButtonText: '忽略并清除', type: 'info' }
        )
        formData.value = {
          ...formData.value,
          ...draft.form,
          tags: draft.form.tags ?? [],
          content: draft.editorMode === 'visual'
            ? await toVisualContent(draft.form.content ?? '')
            : draft.form.content ?? '',
        }
        changeReason.value = draft.changeReason
        editorMode.value = draft.editorMode
        normalizeTags()
        draftSavedAt.value = '已恢复'
        isDirty.value = true
      } catch {
        clearDraft(draftKey.value)
      }
      }
    }
  } catch (e) {
    // store.loadDetail 已不抛错（notFound/error 均返回 null 并置 store 状态），
    // 此处兜住的是本函数其余步骤的异常（草稿恢复、内容转换等）
    handleServerError(e, { action: '加载文档' })
  } finally {
    loadingEdit.value = false
  }
}

const mountEditor = async () => {
  editorReady.value = false
  await loadDoc()
  await nextTick()
  editorReady.value = true
}

onMounted(async () => {
  syncEditorViewport()
  window.addEventListener('resize', syncEditorViewport, { passive: true })
  loadCategoriesAndTags()
  await mountEditor()
  startAutoSave()
})

// 监听内容变化，提取目录（防抖 500ms）
let tocTimer: number | null = null
watch(() => formData.value.content, () => {
  if (tocTimer) clearTimeout(tocTimer)
  tocTimer = window.setTimeout(() => {
    const heads: TocItem[] = []
    if (isHtmlContent(formData.value.content)) {
      const parsed = new DOMParser().parseFromString(formData.value.content, 'text/html')
      parsed.body.querySelectorAll('h2,h3').forEach((heading, elementIndex) => {
        const level = heading.tagName.toLowerCase() === 'h3' ? 3 : 2
        heads.push({ id: `h-${elementIndex}`, text: heading.textContent?.trim() || `章节 ${elementIndex + 1}`, level, elementIndex })
      })
    } else {
      const lines = formData.value.content.split('\n')
      lines.forEach((line, lineIndex) => {
        const match = /^(##|###)\s+(.+)/.exec(line)
        if (match) heads.push({ id: `h-${heads.length}`, text: match[2].trim(), level: match[1].length as 2 | 3, lineIndex })
      })
    }
    tocItems.value = heads
  }, 500)
})

watch(
  [() => route.params.id, () => route.query.draft, () => route.query.category],
  async () => { await mountEditor() }
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncEditorViewport)
  if (autoSaveTimer) {
    clearInterval(autoSaveTimer)
    autoSaveTimer = null
  }
  if (tocTimer) {
    clearTimeout(tocTimer)
    tocTimer = null
  }
})

// ==================== 标签 / 摘要工具 ====================

const addTag = (tag: string) => {
  const t = tag.trim()
  if (!t) return
  if (formData.value.tags.some(item => item.trim().toLocaleLowerCase() === t.toLocaleLowerCase())) {
    notify.warning('标签已存在')
    return
  }
  if (formData.value.tags.length >= MAX_TAGS) {
    notify.warning(`最多添加 ${MAX_TAGS} 个标签`)
    return
  }
  formData.value.tags.push(t)
}

const normalizeTags = () => {
  const seen = new Set<string>()
  formData.value.tags = formData.value.tags
    .map(tag => tag.trim())
    .filter(tag => {
      const normalized = tag.toLocaleLowerCase()
      return !!tag && !seen.has(normalized) && seen.add(normalized)
    })
    .slice(0, MAX_TAGS)
}

const createCategoryFromSettings = async () => {
  try {
    const { value } = await ElMessageBox.prompt('新分类会同步出现在左侧知识库目录中', '新建分类', {
      inputPlaceholder: '分类名称',
      inputPattern: /\S+/,
      inputErrorMessage: '请输入分类名称',
    })
    const created = await createKnowledgeCategory({ name: value.trim(), parentId: null })
    await loadCategoriesAndTags()
    formData.value.category = created.name
    notify.success('分类已创建')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    handleServerError(error, { action: '创建分类' })
  }
}

/** 自动生成摘要（去除 Markdown 标记，取前 150 字） */
const generateSummary = () => {
  if (!hasMeaningfulContent(formData.value.content)) {
    notify.warning('请先输入文档内容')
    return
  }
  const source = isHtmlContent(formData.value.content)
    ? new DOMParser().parseFromString(formData.value.content, 'text/html').body.textContent ?? ''
    : formData.value.content
  const text = source.replace(/[#*`>\-[\]()!]/g, '')
    .replace(/\n+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 150)
  if (!text) {
    notify.warning('内容格式不正确，无法生成摘要')
    return
  }
  formData.value.summary = text
  notify.success('已生成摘要')
}

// ==================== 导入 .md ====================

const MAX_IMPORT_SIZE = 5 * 1024 * 1024 // 5MB

const handleImportMd = () => {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.md,.markdown'
  input.onchange = async (e) => {
    const file = (e.target as HTMLInputElement).files?.[0]
    if (!file) return
    if (file.size > MAX_IMPORT_SIZE) {
      notify.error(`文件过大（${(file.size / 1024 / 1024).toFixed(1)}MB），最大支持 5MB`)
      return
    }
    const text = await file.text()
    formData.value.content = editorMode.value === 'visual' ? await toVisualContent(text) : text
    if (!formData.value.title.trim()) formData.value.title = file.name.replace(/\.(md|markdown)$/i, '')
    notify.success(`已导入 ${file.name}`)
  }
  input.click()
}

// ==================== 保存草稿 ====================

const handleSaveDraft = () => {
  if (!saveCurrentDraft()) {
    notify.error('本机暂存失败，请检查浏览器存储空间')
    return
  }
  draftSavedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  notify.success('已暂存到本机浏览器')
}

const showSaveOutcome = async (saved: KnowledgeDocSaveResult, successMessage: string) => {
  const indexFailed = saved.indexStatus === 'FAILED' || saved.indexOutcome?.status === 'FAILED'
  if (indexFailed) {
    notify.warning('文档已保存，但向量化失败，可在详情页重试')
  } else {
    notify.success(successMessage)
  }
  if (saved.nearDuplicates?.length) {
    const titles = saved.nearDuplicates.slice(0, 5).map(item => `《${item.title}》`).join('、')
    await ElMessageBox.alert(`检测到内容相似的文档：${titles}。建议确认是否需要合并或建立引用。`, '相似文档提醒', {
      type: 'warning',
      confirmButtonText: '我知道了',
    })
  }
}

const handleEditorMenu = async (command: 'mode' | 'import' | 'draft') => {
  if (command === 'mode') {
    await switchEditorMode(editorMode.value === 'visual' ? 'markdown' : 'visual')
  } else if (command === 'import') {
    handleImportMd()
  } else {
    handleSaveDraft()
  }
}

// ==================== 保存 / 发布 ====================

const handleSave = async (publishAfterSave = false) => {
  if (!formData.value.title.trim()) {
    notify.warning('请输入标题')
    return
  }
  if (!hasMeaningfulContent(formData.value.content)) {
    notify.warning('请输入内容')
    return
  }
  if (formData.value.tags.length > MAX_TAGS) {
    formData.value.tags = formData.value.tags.slice(0, MAX_TAGS)
  }
  normalizeTags()

  const common = {
    title: formData.value.title.trim(),
    category: formData.value.category.trim(),
    categoryId: selectedCategoryId.value,
    summary: formData.value.summary.trim() || undefined,
    tags: formData.value.tags,
    content: formData.value.content,
  }

  saving.value = true
  try {
    if (isNew.value) {
      const req: KnowledgeDocCreateRequest = { ...common, publish: publishOnCreate.value }
      const saved = await store.createDoc(req)
      await showSaveOutcome(saved, publishOnCreate.value ? '文档已发布' : '草稿已保存')
      clearDraft(draftKey.value)
      isDirty.value = false
      if (autoSaveTimer) clearInterval(autoSaveTimer)
      router.push(`/knowledge/${saved.id}`)
      return
    } else {
      const docId = id.value
      if (docId == null) return
      const req: KnowledgeDocUpdateRequest = {
        ...common,
        version: currentVersion.value,
        changeReason: changeReason.value.trim() || undefined,
      }
      const saved = await store.updateDoc(docId, req, currentVersion.value)
      if (publishAfterSave) {
        const published = await store.publishDoc(docId)
        if (published.indexStatus === 'FAILED') {
          notify.warning('草稿已发布，但向量化失败，可在详情页重试')
        } else {
          notify.success('文档已保存并发布')
        }
      } else {
        await showSaveOutcome(saved, '文档已更新')
      }
      clearDraft(draftKey.value)
      isDirty.value = false
      if (autoSaveTimer) clearInterval(autoSaveTimer)
      router.push(`/knowledge/${saved.id}`)
      return
    }
  } catch (e) {
    if (e instanceof DuplicateContentError) {
      try {
        await ElMessageBox.confirm(
          `内容与已有文档「${e.duplicateTitle ?? '未知'}」重复。是否查看该文档？`,
          '内容重复',
          {
            confirmButtonText: '查看重复文档',
            cancelButtonText: '留在本页',
            type: 'warning',
          }
        )
        if (e.duplicateDocId) router.push(`/knowledge/${e.duplicateDocId}`)
      } catch {
        // 用户选择留在本页，继续编辑
      }
    } else if (e instanceof VersionConflictError) {
      /*
       * 版本冲突：禁止自动覆盖（6.11），但**不能只甩一句「请刷新」就完事**。
       *
       * 原实现弹的是纯文本 toast，用户面临的处境是：
       *   - 页面上没有任何「刷新」入口，只能按 F5
       *   - 而按 F5 会把自己刚写的内容全部丢掉（编辑器里的是未落库的）
       *   - 草稿虽然在 sessionStorage 里，但它的 baseVersion 已过期，
       *     恢复时又会触发一次「草稿版本冲突」弹窗
       * 结果就是用户被困住：既不敢刷新，也提交不上去。
       *
       * 改为给出明确的两条出路，并**在刷新前先把当前内容存进草稿**——
       * 这样无论选哪条，用户写的字都不会凭空消失。
       */
      saveCurrentDraft()
      try {
        await ElMessageBox.confirm(
          '该文档已被他人修改，你的编辑基于旧版本。\n\n' +
          '你的内容已暂存到本机草稿。是否载入最新版本？' +
          '载入后可对照草稿手动合并，避免覆盖他人的改动。',
          '版本冲突',
          {
            type: 'warning',
            confirmButtonText: '载入最新版本',
            cancelButtonText: '留在本页继续编辑',
            distinguishCancelAndClose: true,
          }
        )
        // 重新拉取详情：currentVersion 与表单都会被刷新为服务器最新值
        await loadDoc()
        notify.info('已载入最新版本，你的原内容保留在本机草稿中')
      } catch {
        // 留在本页：保持现状，用户可自行复制内容后再决定
      }
    } else {
      handleServerError(e, { action: '保存文档' })
    }
  } finally {
    saving.value = false
  }
}

const handleSaveAndPublish = () => handleSave(true)
const handleSaveClick = () => handleSave()

// 返回列表：由面包屑（AppBreadcrumb）+ useDirtyGuard 的离开确认统一承担，
// 原页内 handleBack 已删除——它只覆盖点那一个按钮的路径，无法拦住其它离开入口。

const scrollEditorToHeading = async (item: { text: string }) => {
  activeSideTab.value = 'toc'
  await nextTick()
  const contentRoot = editorMode.value === 'visual'
    ? document.querySelector('.ce-paper .kr-editor')
    : document.querySelector('.ce-paper .md-editor-preview')
  if (contentRoot) {
    const heading = Array.from(contentRoot.querySelectorAll('h2,h3')).find(
      el => el.textContent?.trim() === item.text
    )
    if (heading instanceof HTMLElement) {
      heading.scrollIntoView({ behavior: 'smooth', block: 'center' })
      return
    }
  }
  notify.info('当前目录标题尚未渲染')
}

const insertHeading = async () => {
  try {
    const { value } = await ElMessageBox.prompt('标题将追加到文档末尾，可继续在编辑区调整位置', '新增目录标题', {
      inputPlaceholder: '二级标题名称',
      inputPattern: /\S+/,
      inputErrorMessage: '请输入标题名称',
    })
    if (editorMode.value === 'visual') {
      const parsed = new DOMParser().parseFromString(formData.value.content, 'text/html')
      const heading = parsed.createElement('h2')
      heading.textContent = value.trim()
      parsed.body.append(heading, parsed.createElement('p'))
      formData.value.content = parsed.body.innerHTML
    } else {
      const prefix = formData.value.content.trimEnd()
      formData.value.content = `${prefix}${prefix ? '\n\n' : ''}## ${value.trim()}\n\n`
    }
  } catch {
    // 用户取消
  }
}

const renameHeading = async (item: TocItem) => {
  try {
    const { value } = await ElMessageBox.prompt('修改会同步更新正文标题', '重命名文章标题', {
      inputValue: item.text,
      inputPattern: /\S+/,
      inputErrorMessage: '请输入标题名称',
    })
    if (editorMode.value === 'visual' && item.elementIndex !== undefined) {
      const parsed = new DOMParser().parseFromString(formData.value.content, 'text/html')
      const heading = parsed.body.querySelectorAll('h2,h3')[item.elementIndex]
      if (heading) heading.textContent = value.trim()
      formData.value.content = parsed.body.innerHTML
    } else if (item.lineIndex !== undefined) {
      const lines = formData.value.content.split('\n')
      lines[item.lineIndex] = `${'#'.repeat(item.level)} ${value.trim()}`
      formData.value.content = lines.join('\n')
    }
  } catch {
    // 用户取消
  }
}

const removeHeading = async (item: TocItem) => {
  try {
    await ElMessageBox.confirm(`删除目录标题“${item.text}”？标题下正文会保留。`, '删除目录标题', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    if (editorMode.value === 'visual' && item.elementIndex !== undefined) {
      const parsed = new DOMParser().parseFromString(formData.value.content, 'text/html')
      parsed.body.querySelectorAll('h2,h3')[item.elementIndex]?.remove()
      formData.value.content = parsed.body.innerHTML
    } else if (item.lineIndex !== undefined) {
      const lines = formData.value.content.split('\n')
      lines.splice(item.lineIndex, 1)
      formData.value.content = lines.join('\n')
    }
  } catch {
    // 用户取消
  }
}

// ==================== 视图派生 ====================

/** 主操作按钮文案：保存中 / 新建（按发布开关）/ 编辑 */
const primaryLabel = computed(() =>
  saving.value
    ? '保存中...'
    : isNew.value
      ? (publishOnCreate.value ? '发布' : '保存草稿')
      : '保存'
)
</script>

<template>
  <div class="knowledge-editor">
    <!-- 页内工具栏：面包屑 + 标题 + 保存状态 + 操作按钮 -->
    <header class="ce-toolbar">
      <div class="ce-toolbar-inner">
        <div class="ce-toolbar-left">
          <!--
            统一为首页起始的递进面包屑，替代原「返回知识库」按钮。
            未保存草稿的离开确认由 useDirtyGuard 的 onBeforeRouteLeave 兜住——
            它拦截所有路由离开（含面包屑跳转），不会因换成 RouterLink 而绕过。
          -->
          <AppBreadcrumb
            :items="[
              { label: '知识库', to: '/knowledge' },
              { label: formData.title || '无标题文档' }
            ]"
            :current-max-width="260"
          />
        </div>
        <div class="ce-toolbar-right">
          <div class="ce-save-status">
            <span class="ce-save-dot" :class="{ 'ce-save-dot--dirty': isDirty }" />
            <span>
              {{ isDirty ? (draftSavedAt ? `未提交 · 本机暂存 ${draftSavedAt}` : '未提交 · 自动暂存中') : '已提交' }}
            </span>
          </div>
          <el-dropdown trigger="click" @command="handleEditorMenu">
            <button class="ce-btn-more" type="button" title="更多编辑操作">
              <MoreHorizontal :size="18" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="mode">
                  <Code2 v-if="editorMode === 'visual'" :size="15" />
                  <Eye v-else :size="15" />
                  {{ editorMode === 'visual' ? 'Markdown 高级模式' : '返回可视化编辑' }}
                </el-dropdown-item>
                <el-dropdown-item command="import" divided><Upload :size="15" />导入 Markdown</el-dropdown-item>
                <el-dropdown-item command="draft"><Save :size="15" />暂存到本机</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <button class="ce-btn-primary" type="button" :disabled="saving" @click="handleSaveClick">
            <Send :size="15" />
            <span>{{ primaryLabel }}</span>
          </button>
          <button
            v-if="!isNew && store.detail?.status === 'DRAFT'"
            class="ce-btn-outline"
            type="button"
            :disabled="saving"
            @click="handleSaveAndPublish"
          >
            <Send :size="15" />
            <span>保存并发布</span>
          </button>
          <button class="ce-mobile-side" type="button" title="文章大纲和文档属性" @click="mobileSideOpen = true">
            <Settings2 :size="16" />
          </button>
        </div>
      </div>
    </header>

    <!-- 加载中 -->
    <div v-if="loadingEdit || !editorReady" class="ce-loading">
      <span class="ce-spinner" />
      <span>加载文档...</span>
    </div>

    <div v-else class="ce-workspace">
      <!-- 左侧知识树（折叠按钮在知识树自己的标题行内） -->
      <CollapsiblePanel
        ref="leftPanelRef"
        side="left"
        storage-key="ke-left-collapsed"
        label="目录"
        :width="260"
        :rail-width="40"
        @update:collapsed="leftSidebarCollapsed = $event"
      >
        <template #default="{ toggle }">
          <KnowledgeTreeSidebar
            :current-doc-id="id"
            mode="editor"
            @changed="loadCategoriesAndTags"
          >
            <template #title-actions>
              <CollapseToggle side="left" label="目录" @click="toggle" />
            </template>
          </KnowledgeTreeSidebar>
        </template>
      </CollapsiblePanel>
      <div v-if="mobileSideOpen" class="ce-side-mask" @click="mobileSideOpen = false" />
      <div class="ce-body">
      <!-- 居中纸面编辑器 -->
      <main class="ce-main">
        <div class="ce-paper">
          <input
            v-model="formData.title"
            type="text"
            class="ce-doc-title"
            placeholder="请输入标题"
            maxlength="100"
          />
          <div class="ce-paper-meta">
            <el-dropdown v-if="!showStarter" trigger="click" @command="insertBlock">
              <button class="ce-insert-menu" type="button">
                <Plus :size="14" />
                <span>插入</span>
                <ChevronDown :size="13" />
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="h2"><Heading2 :size="15" />二级标题</el-dropdown-item>
                  <el-dropdown-item command="h3"><Heading3 :size="15" />三级标题</el-dropdown-item>
                  <el-dropdown-item command="callout" divided><Lightbulb :size="15" />提示块</el-dropdown-item>
                  <el-dropdown-item command="code"><SquareTerminal :size="15" />代码块</el-dropdown-item>
                  <el-dropdown-item command="table"><Table2 :size="15" />表格</el-dropdown-item>
                  <el-dropdown-item command="divider"><Minus :size="15" />分隔线</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <span class="ce-paper-context">
              <BookOpen :size="13" />
              {{ formData.category || '未分类' }}
            </span>
            <span class="ce-title-count">{{ formData.title.length }}/100</span>
          </div>
          <section v-if="showStarter" class="ce-starter" aria-labelledby="ce-starter-title">
            <div class="ce-starter-title">
              <BookTemplate :size="18" />
              <h2 id="ce-starter-title">选择文档模板</h2>
            </div>
            <div class="ce-starter-grid">
              <button
                v-for="template in starterTemplates"
                :key="template.key"
                type="button"
                @click="startWithTemplate(template.key)"
              >
                <component :is="template.icon" :size="19" />
                <span>{{ template.label }}</span>
              </button>
            </div>
          </section>
          <KnowledgeRichEditor
            v-else-if="editorMode === 'visual'"
            ref="richEditorRef"
            v-model="formData.content"
          />
          <MdEditor
            v-else
            v-model="formData.content"
            language="zh-CN"
            :preview="editorPreview"
            :toolbars="[
              'bold',
              'underline',
              'italic',
              'strikeThrough',
              '-',
              'title',
              'sub',
              'sup',
              'quote',
              'unorderedList',
              'orderedList',
              'task',
              '-',
              'codeRow',
              'code',
              'link',
              'image',
              'table',
              '-',
              'revoke',
              'next',
              '=',
              'pageFullscreen',
              'fullscreen',
              'preview',
              'catalog'
            ]"
            :toolbarsExclude="['github']"
            placeholder="开始编写 Markdown 内容..."
          />
        </div>
      </main>

      <!-- 右侧设置面板（可折叠；折叠后图标轨可直接切到大纲/属性 tab） -->
      <CollapsiblePanel
        ref="rightPanelRef"
        side="right"
        storage-key="ke-right-collapsed"
        label="侧栏"
        :width="240"
        :disabled="isNarrowScreen"
        @update:collapsed="rightSidebarCollapsed = $event"
      >
        <!--
          折叠态图标轨：点击图标既展开侧栏又切到对应 tab——
          比「先展开、再点 tab」两步操作更直接。
        -->
        <template #rail="{ toggle }">
          <RailButton
            title="文章大纲"
            :active="activeSideTab === 'toc'"
            :count="tocItems.length"
            @click="activeSideTab = 'toc'; toggle()"
          >
            <ListTree :size="17" />
          </RailButton>
          <RailButton
            title="文档属性"
            :active="activeSideTab === 'settings'"
            @click="activeSideTab = 'settings'; toggle()"
          >
            <Settings2 :size="16" />
          </RailButton>
        </template>

        <template #default="{ toggle }">
        <aside class="ce-sidebar" :class="{ 'mobile-open': mobileSideOpen }">
        <div class="ce-side-tabs">
          <!-- 右侧栏的折叠按钮放**左端**：镜像对称，贴近它要让出的主内容区 -->
          <CollapseToggle
            v-if="!isNarrowScreen"
            class="ce-side-collapse"
            side="right"
            label="侧栏"
            @click="toggle"
          />
          <button
            class="ce-side-tab"
            :class="{ active: activeSideTab === 'toc' }"
            @click="activeSideTab = 'toc'"
          >大纲</button>
          <button
            class="ce-side-tab"
            :class="{ active: activeSideTab === 'settings' }"
            @click="activeSideTab = 'settings'"
          >属性</button>
        </div>

        <!-- 设置 Tab -->
        <template v-if="activeSideTab === 'settings'">
          <div class="ce-side-head">
            <Settings2 :size="15" />
            <span>文档属性</span>
          </div>

          <div class="ce-side-group">
            <label class="ce-side-label">文档分类</label>
            <div class="ce-category-control">
              <el-select v-model="formData.category" filterable clearable placeholder="选择目录分类">
                <el-option v-for="cat in directoryCategories" :key="cat.id" :label="categoryLabel(cat)" :value="cat.name" />
              </el-select>
              <button type="button" title="新建分类" @click="createCategoryFromSettings"><Plus :size="15" /></button>
            </div>
          </div>

          <div class="ce-side-group">
            <label class="ce-side-label">标签（最多 {{ MAX_TAGS }} 个）</label>
            <el-select
              v-model="formData.tags"
              multiple
              filterable
              allow-create
              default-first-option
              :multiple-limit="MAX_TAGS"
              @change="normalizeTags"
              placeholder="输入标签后回车"
              style="width: 100%"
            >
              <el-option
                v-for="tag in managedTags"
                :key="tag.id"
                :label="tag.name"
                :value="tag.name"
              />
            </el-select>
            <div v-if="store.hotTags.length" class="ce-hot-tags">
              <span class="ce-hot-title">热门：</span>
              <button
                v-for="ht in store.hotTags.slice(0, 8)"
                :key="ht.tag"
                type="button"
                class="ce-hot-tag"
                :disabled="formData.tags.includes(ht.tag) || formData.tags.length >= MAX_TAGS"
                @click="addTag(ht.tag)"
              >
                {{ ht.tag }}
              </button>
            </div>
          </div>

          <div class="ce-side-group">
            <label class="ce-side-label">摘要（可选）</label>
            <textarea
              v-model="formData.summary"
              class="ce-excerpt-input"
              placeholder="简要描述文档内容"
              rows="4"
              maxlength="200"
            ></textarea>
            <div class="ce-excerpt-footer">
              <span class="ce-excerpt-count">
                {{ formData.summary.length }}/200 · 留空将自动提取前 150 字
              </span>
              <button
                v-if="formData.content.trim()"
                class="ce-auto-excerpt"
                type="button"
                @click="generateSummary"
              >
                <Sparkles :size="13" />
                <span>自动生成</span>
              </button>
            </div>
          </div>

          <div class="ce-side-group">
            <!-- 新建：发布开关 -->
            <div v-if="isNew" class="ce-publish-block">
              <div class="ce-publish-head">
                <span class="ce-publish-title">保存后立即发布</span>
                <el-switch v-model="publishOnChange" />
              </div>
              <p class="ce-publish-desc">发布后触发向量化，可被 AI 检索；关闭则仅存草稿</p>
            </div>
            <!-- 编辑：版本信息 + 变更说明 -->
            <div v-else class="ce-publish-block">
              <div class="ce-publish-head">
                <span class="ce-publish-title">
                  当前版本 v{{ currentVersion }}
                  <span v-if="store.detail?.status === 'DRAFT'" class="ce-draft-tag">草稿</span>
                </span>
              </div>
              <p class="ce-publish-desc">保存后版本号 +1；状态为草稿时可在详情页发布</p>
              <el-input
                v-model="changeReason"
                maxlength="100"
                placeholder="变更说明（可选，将记入版本历史）"
                style="width: 100%"
              />
            </div>
          </div>
        </template>

        <!-- 目录 Tab -->
        <template v-if="activeSideTab === 'toc'">
          <div class="ce-side-head">
            <FileText :size="15" />
            <span>文章大纲</span>
            <button class="ce-side-head-action" type="button" title="新增二级标题" @click="insertHeading">
              <Plus :size="15" />
            </button>
          </div>
          <div v-if="tocItems.length" class="ce-toc-list">
            <div
              v-for="item in tocItems"
              :key="item.id"
              class="ce-toc-row"
            >
              <button
                class="ce-toc-item"
                :class="{ 'level-three': item.level === 3 }"
                type="button"
                @click="scrollEditorToHeading(item)"
              >{{ item.text }}</button>
              <button type="button" title="重命名标题" @click="renameHeading(item)"><Pencil :size="13" /></button>
              <button type="button" title="删除标题" @click="removeHeading(item)"><Trash2 :size="13" /></button>
            </div>
          </div>
          <button v-else class="ce-toc-empty ce-toc-empty-action" type="button" @click="insertHeading">
            <Plus :size="14" /> 添加第一个二级标题
          </button>
        </template>
      </aside>
      </template>
      </CollapsiblePanel>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.knowledge-editor {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px);
  min-height: 620px;
  overflow: hidden;
  background: var(--color-bg);
}

/* ==================== 页内工具栏 ==================== */

.ce-toolbar {
  background: var(--color-bg-elevated);
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
}

.ce-toolbar-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 48px;
  padding: 0 16px;
}

.ce-toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}

.ce-toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.ce-btn-ghost, .ce-btn-primary, .ce-btn-more, .ce-mobile-side { border: 0; outline: 0; }
.ce-mobile-side { display: none; width: 34px; height: 34px; align-items: center; justify-content: center; border-radius: 5px; color: var(--color-text-secondary); }
.ce-mobile-side:hover { color: var(--color-primary); background: var(--color-primary-lighter); }
.ce-side-mask { display: none; }

/* 原「返回知识库」按钮及其配套（分隔线/图标/标题）的样式已删除——
   该按钮已被 AppBreadcrumb 面包屑取代，DOM 不存在，这些规则已成死代码。 */

.ce-title-input {
  flex: 1;
  min-width: 180px;
  max-width: 560px;
  border: none;
  background: transparent;
  font-family: var(--font-body);
  font-size: 32px;
  font-weight: var(--weight-bold);
  line-height: 1.3;
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.15s ease;

  &::placeholder {
    color: var(--color-text-tertiary);
    font-weight: var(--weight-normal);
  }
}

.ce-title-count {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
}

.ce-save-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0 4px;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
}

.ce-save-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-full);
  background: var(--state-success);
  box-shadow: 0 0 0 3px var(--state-success-bg);
}
.ce-save-dot--dirty {
  background: var(--state-warning, var(--warning));
  box-shadow: 0 0 0 3px rgba(230, 162, 60, 0.15);
}

.ce-btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 14px;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  white-space: nowrap;
  transition: background 0.15s ease, color 0.15s ease;

  &:hover {
    background: var(--color-bg-sunken);
    color: var(--color-text-primary);
  }
}

.ce-btn-more {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-secondary);
}

.ce-btn-more:hover {
  color: var(--color-primary);
  background: var(--color-primary-lighter);
}

.ce-btn-outline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  white-space: nowrap;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary-light);
    color: var(--color-primary);
  }
}

.ce-btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  white-space: nowrap;
  transition: background 0.15s ease;

  &:hover:not(:disabled) {
    background: var(--color-primary-light);
  }

  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
}

/* ==================== 加载态 ==================== */

.ce-loading {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 80px 0;
  color: var(--color-text-tertiary);
  font-size: var(--text-sm);
}

.ce-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: ce-spin 0.8s linear infinite;
}

@keyframes ce-spin {
  to { transform: rotate(360deg); }
}

/* ==================== 主体区域 ==================== */

.ce-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

/* 侧栏折叠（容器/过渡/命中区/持久化）已统一由 CollapsiblePanel 负责，此处不再重复定义 */

.ce-body {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  align-items: stretch;
  gap: 0;
  width: 100%;
  padding: 0;
  overflow: hidden;
  box-sizing: border-box;
}

/* 居中纸面 */
.ce-main {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: stretch;
  overflow-y: auto;
  padding: 12px 14px 24px;
}

.ce-paper {
  width: 100%;
  max-width: none;
  min-height: calc(100vh - 128px);
  padding: 22px 28px 40px;
  box-sizing: border-box;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  box-shadow: var(--shadow-sm);
}

.ce-doc-title {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--color-text-primary);
  font: 700 30px/1.35 var(--font-display);
}

.ce-doc-title::placeholder { color: var(--color-text-tertiary); font-weight: var(--weight-normal); }

.ce-paper-meta {
  min-height: 34px;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--color-text-tertiary);
  font-size: 12px;
}

.ce-paper-meta .ce-title-count { margin-left: auto; }

.ce-insert-menu {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 9px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.ce-insert-menu:hover {
  border-color: var(--color-primary-light);
  color: var(--color-primary);
}

.ce-paper-context {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ce-starter {
  min-height: calc(100vh - 250px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  gap: 18px;
  padding: 34px 0 48px;
  box-sizing: border-box;
  border-top: 1px solid var(--color-border-light);
}

.ce-starter-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-secondary);
}

.ce-starter-title h2 {
  margin: 0;
  font-size: 15px;
  font-weight: var(--weight-semibold);
  letter-spacing: 0;
}

.ce-starter-grid {
  width: min(100%, 520px);
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.ce-starter-grid button {
  min-height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  font-size: 14px;
  text-align: left;
  transition: border-color 0.15s ease, background 0.15s ease, color 0.15s ease;
}

.ce-starter-grid button:hover {
  border-color: var(--color-primary-light);
  background: var(--color-primary-lighter);
  color: var(--color-primary);
}

.ce-category-control {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 32px;
  gap: 6px;
}

.ce-category-control > button {
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border);
  border-radius: 5px;
  color: var(--color-text-secondary);
}

.ce-category-control > button:hover { border-color: var(--color-primary-light); color: var(--color-primary); }

/* 右侧设置面板 */
/* 宽度由 CollapsiblePanel 的 width prop 控制，此处只管内部呈现 */
.ce-sidebar {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 0 16px 20px;
  overflow-y: auto;
  background: var(--color-surface-hover);
  border-left: 1px solid var(--color-border-light);
  border-radius: 0;
  box-shadow: none;
}

.ce-side-tabs {
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--color-border-light);
  margin: 0 -16px 2px;
  padding: 0 16px;
  flex-shrink: 0;
}

/* 折叠按钮在 tab 行左端（镜像对称：右侧栏的控件贴近主内容区） */
.ce-side-collapse {
  margin-right: 4px;
  flex-shrink: 0;
}

.ce-side-tab {
  flex: 1;
  height: 36px;
  border: none;
  background: transparent;
  font-size: var(--text-sm);
  font-family: var(--font-body);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
  border-bottom: 2px solid transparent;
  padding: 0;

  &.active {
    color: var(--color-primary);
    font-weight: var(--weight-semibold);
    border-bottom-color: var(--color-primary);
  }

  &:hover:not(.active) {
    color: var(--color-text-primary);
  }
}

.ce-toc-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ce-toc-item {
  flex: 1;
  min-width: 0;
  display: block;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  text-decoration: none;
  padding: 6px 8px;
  overflow: hidden;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  border-radius: var(--radius-sm);
  transition: all 0.15s ease;

  &:hover {
    color: var(--color-primary);
    background: var(--color-bg);
  }
}

.ce-toc-item.level-three {
  padding-left: 22px;
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.ce-toc-row { display: flex; align-items: center; gap: 2px; border-radius: 4px; }
.ce-toc-row > button:not(.ce-toc-item) { width: 25px; height: 25px; display: none; align-items: center; justify-content: center; color: var(--color-text-tertiary); border-radius: 4px; }
.ce-toc-row:hover { background: var(--color-bg); }
.ce-toc-row:hover > button { display: inline-flex; }
.ce-toc-row > button:not(.ce-toc-item):hover { color: var(--color-primary); background: var(--color-primary-lighter); }

.ce-toc-empty {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  padding: 4px 10px;
}

.ce-toc-empty-action { display: flex; align-items: center; gap: 6px; border: 1px dashed var(--color-border); border-radius: 5px; padding: 9px 10px; }
.ce-toc-empty-action:hover { border-color: var(--color-primary-light); color: var(--color-primary); }

.ce-side-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--color-border-light);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);
}

.ce-side-head-action { width: 26px; height: 26px; margin-left: auto; display: inline-flex; align-items: center; justify-content: center; border-radius: 4px; color: var(--color-text-tertiary); }
.ce-side-head-action:hover { color: var(--color-primary); background: var(--color-primary-lighter); }

.ce-side-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.ce-side-label {
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  color: var(--color-text-tertiary);
}

.ce-hot-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.ce-hot-title {
  color: var(--color-text-tertiary);
}

.ce-hot-tag {
  padding: 3px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-full);
  background: var(--color-surface-hover);
  color: var(--color-text-secondary);
  font-size: var(--text-xs);
  transition: all 0.15s ease;

  &:hover:not(:disabled) {
    border-color: var(--color-primary-light);
    color: var(--color-primary);
  }

  &:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }
}

.ce-excerpt-input {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-elevated);
  color: var(--color-text-primary);
  font-size: var(--text-sm);
  font-family: inherit;
  line-height: 1.5;
  resize: none;
  box-sizing: border-box;
  transition: border-color 0.15s ease;

  &:focus {
    border-color: var(--color-primary-light);
  }
}

.ce-excerpt-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ce-excerpt-count {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  line-height: 1.4;
}

.ce-auto-excerpt {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-full);
  background: var(--color-bg-elevated);
  color: var(--color-primary);
  font-size: var(--text-xs);
  white-space: nowrap;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary-light);
    background: var(--color-primary-lighter);
  }
}

.ce-publish-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ce-publish-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ce-publish-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);
}

.ce-publish-desc {
  margin: 0;
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--color-text-tertiary);
}

.ce-draft-tag {
  margin-left: 6px;
  padding: 1px 8px;
  font-size: var(--text-xs);
  font-weight: var(--weight-normal);
  color: var(--color-text-secondary);
  background: var(--color-bg-sunken);
  border-radius: var(--radius-full);
}

/* ==================== md-editor 纸面化 ==================== */

:deep(.md-editor) {
  border: none;
  border-radius: var(--radius-md);
  min-height: calc(100vh - 245px);
  height: auto !important;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-elevated);
}

:deep(.md-editor-toolbar) {
  background: var(--color-surface-hover);
  border-bottom: 1px solid var(--color-border-light);
  border-radius: var(--radius-md) var(--radius-md) 0 0;
}

:deep(.md-editor-toolbar-item) {
  color: var(--color-text-secondary);
}

:deep(.md-editor-toolbar-item:hover) {
  color: var(--color-primary);
  background: var(--color-primary-lighter);
}

:deep(.md-editor-content-wrapper) {
  height: auto !important;
  flex: 1;
  min-height: calc(100vh - 294px);
}

:deep(.md-editor-input-wrapper) {
  height: 100% !important;
  min-height: calc(100vh - 294px);
}

:deep(.md-editor-input) {
  height: 100% !important;
  min-height: calc(100vh - 294px);
  padding: 20px 24px;
  overflow-y: auto !important;
}

/* ==================== 响应式 ==================== */

@media (max-width: 1050px) {
  /* 宽度已由 CollapsiblePanel 的 width prop 控制，此处只收窄正文内边距 */
  .ce-main { padding-inline: 10px; }
}

@media (max-width: 900px) {
  .ce-toolbar-inner {
    flex-wrap: wrap;
    min-height: auto;
    padding: 10px 16px;
    row-gap: 10px;
  }

  .ce-title-input {
    max-width: 100%;
  }

  .ce-save-status { display: none; }
}

@media (max-width: 760px) {
  .ce-body { overflow: hidden; }
  .ce-main { padding: 16px 12px 32px; }
  .ce-sidebar {
    position: fixed;
    z-index: 81;
    top: 56px;
    right: 0;
    bottom: 0;
    width: min(300px, 88vw);
    height: auto;
    transform: translateX(100%);
    transition: transform 0.2s ease;
    box-shadow: var(--shadow-lg);
  }
  .ce-sidebar.mobile-open { transform: translateX(0); }
  .ce-side-mask { position: fixed; z-index: 80; inset: 56px 0 0; display: block; background: rgba(17, 24, 39, 0.32); }
  .ce-mobile-side { display: inline-flex; }
  .ce-btn-ghost span, .ce-btn-outline span { display: none; }
  .ce-btn-ghost, .ce-btn-outline { width: 34px; justify-content: center; padding: 0; }
  .ce-paper { padding: 22px 20px 36px; box-sizing: border-box; }
  .ce-doc-title { font-size: 25px; }
  .ce-paper-meta { flex-wrap: wrap; height: auto; padding-bottom: 8px; }
  .ce-title-count { margin-left: auto; }
  .ce-starter { min-height: calc(100vh - 260px); }
  .ce-starter-grid { grid-template-columns: 1fr; }
  :deep(.md-editor-preview-wrapper),
  :deep(.md-editor-resize-operate) { display: none !important; }
  :deep(.md-editor-input-wrapper) { width: 100% !important; }
}

@media (max-width: 600px) {
  .ce-btn-ghost, .ce-btn-outline { display: none; }
  .ce-toolbar-inner { gap: 8px; padding-inline: 10px; }
  .ce-toolbar-left { gap: 7px; }
  .ce-toolbar-right { gap: 5px; }
  .ce-btn-primary { height: 32px; padding-inline: 11px; }
}
</style>
