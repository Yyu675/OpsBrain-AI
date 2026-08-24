<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Share2,
  History,
  Edit2,
  Send,
  Trash2,
  FileX,
  RefreshCw,
  GitCompare,
  AlertTriangle,
  Ticket,
  FileText,
} from 'lucide-vue-next'
import { showUndoToast } from '@/utils/undoToast'
import { copyText } from '@/utils/clipboard'
import { safeMarkdown } from '@/utils/safeMarkdown'
import { useKnowledgeStore } from '@/stores/knowledge'
import { statusLabel, indexStatusLabel, fetchKnowledgeDocs } from '@/api/knowledge'
import type { KnowledgeDocListItem } from '@/api/types'
import RelativeTime from '@/components/common/RelativeTime.vue'
import KnowledgeTreeSidebar from '@/components/knowledge/KnowledgeTreeSidebar.vue'
import AppEmpty from '@/components/common/AppEmpty.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'
import PageLoading from '@/components/common/PageLoading.vue'
import CollapsiblePanel from '@/components/common/CollapsiblePanel.vue'
import CollapseToggle from '@/components/common/CollapseToggle.vue'
import RailButton from '@/components/common/RailButton.vue'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'
import { useHotkeys } from '@/composables/useHotkeys'
import { notify, handleServerError } from '@/utils/notify'

const route = useRoute()
const router = useRouter()
const store = useKnowledgeStore()

const docId = computed(() => Number(route.params.id))
const doc = computed(() => store.detail)

const related = ref<KnowledgeDocListItem[]>([])
const mainContainer = ref<HTMLElement | null>(null)
const versionsOpen = ref(false)
const restoringVersion = ref<number | null>(null)
const actionLoading = ref<'publish' | 'reindex' | 'deprecate' | 'restore' | 'purge' | null>(null)
let relatedRequestSequence = 0

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

// `[` 收左栏（目录）、`]` 收右栏（大纲）。useHotkeys 已排除输入框聚焦场景
useHotkeys([
  { key: '[', description: '收起/展开目录', handler: () => leftPanelRef.value?.toggle() },
  { key: ']', description: '收起/展开大纲', handler: () => rightPanelRef.value?.toggle() }
])

// Markdown → HTML → DOMPurify 净化
// 注意：不能用 async computed 直接 v-html（Promise 不会被 Vue 解包），
// 改为 watch + ref。
const safeHtml = ref('')
watch(
  () => doc.value?.content,
  async (raw) => {
    if (!raw) {
      safeHtml.value = ''
      return
    }
    safeHtml.value = safeMarkdown(raw, `doc-${doc.value?.id ?? 'unknown'}-${raw.length}`)
    await nextTick()
    decorateArticleContent()
    buildToc()
  },
  { immediate: true }
)

/** 加载详情（三态由 store.detailStatus 驱动） */
const load = async () => {
  try {
    await store.loadDetail(docId.value)
  } catch {
    // 三态已在 store 中区分，无需在此处理
  }
}

/** 相关文章：同分类已发布文档，API 拉取 */
const loadRelated = async () => {
  const requestSequence = ++relatedRequestSequence
  const d = doc.value
  if (!d?.category) {
    related.value = []
    return
  }
  try {
    const res = await fetchKnowledgeDocs({ category: d.category, status: 'PUBLISHED', page: 1, size: 5 })
    if (requestSequence !== relatedRequestSequence) return
    related.value = res.content.filter(x => x.id !== d.id).slice(0, 4)
  } catch (e) {
    if (requestSequence !== relatedRequestSequence) return
    console.warn('[KnowledgeDetail] 加载相关文章失败', e)
    related.value = []
  }
}

watch([() => doc.value?.id, () => doc.value?.category], () => {
  loadRelated()
})

onMounted(async () => {
  load()
  await nextTick()
  mainContainer.value?.addEventListener('scroll', updateActiveToc, { passive: true })
})

onBeforeUnmount(() => {
  mainContainer.value?.removeEventListener('scroll', updateActiveToc)
})

watch(docId, () => {
  if (Number.isNaN(docId.value)) return
  load()
})

// ==================== 文章大纲（TOC + scroll spy） ====================

interface TocItem { id: string; text: string; level: 2 | 3 }
const toc = ref<TocItem[]>([])
const activeToc = ref<string>('')
/** h2/h3 元素引用，供 scroll spy 计算当前阅读章节 */
const tocEls = ref<HTMLElement[]>([])
/** 文章内容容器 ref，替代全局 querySelector */
const articleContentRef = ref<HTMLElement | null>(null)

const decorateArticleContent = () => {
  const contentEl = articleContentRef.value
  if (!contentEl) return
  contentEl.querySelectorAll('pre').forEach(pre => {
    const code = pre.querySelector('code')
    const lang = Array.from(code?.classList ?? [])
      .find(name => name.startsWith('language-'))
      ?.slice('language-'.length)
    pre.setAttribute('data-language', (lang || 'TEXT').toUpperCase())
  })
}

const buildToc = () => {
  const contentEl = articleContentRef.value
  if (!contentEl) return
  const heads = contentEl.querySelectorAll('h2,h3')
  const items: TocItem[] = []
  heads.forEach((h, idx) => {
    const id = `toc-${idx}`
    h.setAttribute('id', id)
    items.push({
      id,
      text: h.textContent || `章节 ${idx + 1}`,
      level: h.tagName.toLowerCase() === 'h3' ? 3 : 2,
    })
  })
  toc.value = items
  tocEls.value = Array.from(heads) as HTMLElement[]
  activeToc.value = items[0]?.id || ''
  updateActiveToc()
}

/** scroll spy：取最后一个处于视口上方（含 96px 视差偏移）的章节并高亮 */
const updateActiveToc = () => {
  const top = (mainContainer.value?.getBoundingClientRect().top ?? 0) + 96
  let current = toc.value[0]?.id || ''
  for (const el of tocEls.value) {
    if (el.getBoundingClientRect().top <= top) current = el.id
  }
  activeToc.value = current
}

const scrollToToc = (id: string) => {
  const el = document.getElementById(id)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    activeToc.value = id
  }
}

// ==================== 格式化 ====================

/** 服务端时间戳 → "YYYY-MM-DD HH:mm"（缺字段返回空串，禁止编造） */
const fmtDateTime = (s?: string | null) => (s ? s.replace('T', ' ').slice(0, 16) : '')

// ==================== 操作 ====================

const shareArticle = async () => {
  const url = window.location.href
  const ok = await copyText(url)
  if (ok) notify.success('文档链接已复制')
  else notify.warning('复制失败，请手动复制链接')
}

const openEdit = () => {
  if (!doc.value) return
  if (doc.value.status === 'DEPRECATED' || doc.value.status === 'ARCHIVED') {
    notify.warning('请先恢复文档再编辑')
    return
  }
  router.push(`/knowledge/editor/${doc.value.id}`)
}

/** 发布（草稿 → 已发布，触发向量化） */
const publishDoc = async () => {
  const d = doc.value
  if (!d) return
  try {
    await ElMessageBox.confirm(`确认发布「${d.title}」？发布后触发向量化，可被 AI 检索。`, '发布确认', {
      type: 'info',
      confirmButtonText: '发布',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    actionLoading.value = 'publish'
    const result = await store.publishDoc(d.id)
    if (result.indexStatus === 'FAILED') {
      notify.warning('文档已发布，但向量化失败，请重试')
    } else {
      notify.success('已发布并完成向量化')
    }
    await load()
  } catch (e) {
    handleServerError(e, { action: '发布文档' })
  } finally {
    actionLoading.value = null
  }
}

/** 已发布但向量化失败时，重用幂等发布接口补建索引。 */
const retryIndex = async () => {
  const d = doc.value
  if (!d) return
  try {
    actionLoading.value = 'reindex'
    const result = await store.publishDoc(d.id)
    if (result.indexStatus === 'FAILED') {
      notify.error(`向量化仍失败：${result.indexError || '请查看服务日志'}`)
    } else {
      notify.success('向量化已恢复')
    }
    await load()
  } catch (e) {
    handleServerError(e, { action: '重试向量化' })
  } finally {
    actionLoading.value = null
  }
}

/** 废弃文档恢复当前版本并重新发布。 */
const restoreDeprecated = async () => {
  const d = doc.value
  if (!d || d.status !== 'DEPRECATED') return
  try {
    await ElMessageBox.confirm(`恢复「${d.title}」并重新发布？恢复后会重建向量索引。`, '恢复文档', {
      type: 'info',
      confirmButtonText: '恢复并发布',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    actionLoading.value = 'restore'
    await store.undoDeprecate(d.id, d.version)
    await load()
  } catch (e) {
    handleServerError(e, { action: '恢复文档' })
  } finally {
    actionLoading.value = null
  }
}

/** 删除 = 废弃（留正文删向量，退出检索），5 秒内可撤销 */
const removeDoc = async () => {
  const d = doc.value
  if (!d) return
  try {
    await ElMessageBox.confirm(
      `确认废弃「${d.title}」？正文会保留供历史查阅，但文档将退出检索。5 秒内可撤销。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    actionLoading.value = 'deprecate'
    const snap = await store.deprecateDoc(d.id)
    showUndoToast({
      message: `文档「${d.title}」已删除（废弃）`,
      duration: 5000,
      onUndo: async () => {
        if (snap) await store.undoDeprecate(d.id, snap.version)
      }
    })
    await load()
  } catch (e) {
    handleServerError(e, { action: '废弃文档' })
  } finally {
    actionLoading.value = null
  }
}

/** 物理删除（仅已废弃文档、合规场景，须提供理由用于审计） */
const purgeDoc = async () => {
  const d = doc.value
  if (!d) return
  let reason: string
  try {
    const res = await ElMessageBox.prompt(
      '彻底删除将不可恢复，仅限合规场景（如内容违规、涉密等需审计举证）。请输入删除理由，将记录用于审计。',
      '物理删除确认',
      {
        type: 'warning',
        confirmButtonText: '彻底删除',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '必填：删除理由',
        inputValidator: (v: string) => (v && v.trim() ? true : '删除理由必填'),
      }
    )
    reason = (res.value ?? '').trim()
  } catch (e) {
    // handleServerError 内部会识别 ElMessageBox 的取消（isAbortLike），
    // 取消时不弹提示，故无需在此手写 cancel 判断
    handleServerError(e, { action: '物理删除' })
    return
  }
  if (!reason) return
  try {
    actionLoading.value = 'purge'
    await store.purgeDoc(d.id, reason)
    notify.success('文档已彻底删除')
    router.push('/knowledge')
  } catch (e) {
    handleServerError(e, { action: '物理删除' })
  } finally {
    actionLoading.value = null
  }
}

// ==================== 版本历史 ====================

const openVersions = () => {
  versionsOpen.value = true
  if (doc.value) store.loadVersions(doc.value.id)
}

const CHANGE_TYPE_LABELS: Record<string, string> = {
  CREATE: '创建',
  UPDATE: '更新',
  RESTORE: '回滚恢复',
  DEPRECATE: '废弃'
}

const changeTypeLabel = (t?: string) => CHANGE_TYPE_LABELS[t ?? ''] ?? (t ?? '')

/** 回滚到历史版本 */
const rollbackVersion = async (version: number) => {
  const d = doc.value
  if (!d) return
  try {
    await ElMessageBox.confirm(`确认回滚到 v${version}？当前内容会作为新版本存入历史，回滚后立即重新发布并向量化。`, '回滚确认', {
      type: 'warning',
      confirmButtonText: '回滚',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  restoringVersion.value = version
  try {
    await store.restoreVersion(d.id, version)
    notify.success(`已回滚到 v${version}`)
    await Promise.all([load(), store.loadVersions(d.id)])
  } catch (e) {
    handleServerError(e, { action: '回滚版本' })
  } finally {
    restoringVersion.value = null
  }
}

/** 对比当前版本 */
const diffOpen = ref(false)

/**
 * 打开 diff 面板并载入对比结果
 * @param version 历史版本号；对比方向恒为「该历史版本 → 当前版本」
 */
const compareAction = async (version: number) => {
  const d = doc.value
  if (!d) return
  diffOpen.value = true
  await store.loadCompare(d.id, version, d.version)
}
</script>

<template>
  <div class="knowledge-detail">
    <div class="detail-workspace">
      <!-- 左侧知识树（可折叠） -->
      <CollapsiblePanel
        ref="leftPanelRef"
        side="left"
        storage-key="kd-left-collapsed"
        label="目录"
        :width="260"
        :rail-width="40"
        @update:collapsed="leftSidebarCollapsed = $event"
      >
        <!-- 折叠态：单图标入口。知识树是层级结构，折叠后无法有意义地压成图标列表，
             故图标轨仅由组件内置的展开按钮承担（点击即展开） -->
        <template #default="{ toggle }">
          <KnowledgeTreeSidebar :current-doc-id="docId" mode="detail" @changed="load">
            <template #title-actions>
              <CollapseToggle side="left" label="目录" @click="toggle" />
            </template>
          </KnowledgeTreeSidebar>
        </template>
      </CollapsiblePanel>
      <main ref="mainContainer" class="main-container">
      <!-- 加载中 -->
      <PageLoading v-if="store.detailStatus === 'loading'" tip="加载中..." />

      <!-- 确实不存在 -->
      <AppEmpty
        v-else-if="store.detailStatus === 'notFound'"
        kind="notfound"
        title="文档未找到"
        description="该文档可能已被物理删除或从未存在，其它页面不受影响。"
        action-text="返回知识库"
        @action="router.push('/knowledge')"
      />

      <!-- 加载失败（数据可能仍在，可重试） -->
      <ApiErrorState
        v-else-if="store.detailStatus === 'error'"
        :error="store.detailErrorObj"
        retry-label="重试"
        @retry="load"
      />
      <div v-if="store.detailStatus === 'error'" style="text-align: center; margin-top: 8px;">
        <button class="btn-text" @click="router.push('/knowledge')">返回知识库</button>
      </div>

      <template v-else-if="doc">
        <div class="reader-layout">
          <!-- 阅读纸 -->
          <article class="reader-paper">
            <div class="paper-top">
              <!--
                统一面包屑：首页起始逐级递进。
                原实现在层级链左侧另有一个「< 返回」按钮，与「运维知识库」这一级
                语义重复（都是回列表页）；且链路不含首页，书签直达时回不到根节点。
                分类一级不可点击——按分类筛选需带 query，与「点父级回上一层」语义不同，
                筛选入口在列表页侧栏。
              -->
              <AppBreadcrumb
                :items="[
                  { label: '知识库', to: '/knowledge' },
                  { label: doc.category || '未分类' },
                  { label: doc.title }
                ]"
              />

              <div class="paper-toolbar">
                <button class="tb-icon-btn" title="版本历史" @click="openVersions">
                  <History :size="17" />
                </button>
                <button class="tb-icon-btn" title="分享" @click="shareArticle">
                  <Share2 :size="17" />
                </button>
                <button v-if="doc.status === 'DRAFT'" class="tb-btn" :disabled="!!actionLoading" @click="publishDoc">
                  <Send :size="14" />
                  {{ actionLoading === 'publish' ? '发布中...' : '发布' }}
                </button>
                <button v-if="doc.status === 'PUBLISHED' && doc.indexStatus === 'FAILED'" class="tb-btn" :disabled="!!actionLoading" @click="retryIndex">
                  <RefreshCw :size="14" />
                  {{ actionLoading === 'reindex' ? '重试中...' : '重试向量化' }}
                </button>
                <button v-if="doc.status === 'DRAFT' || doc.status === 'PUBLISHED'" class="tb-btn tb-btn-primary" @click="openEdit">
                  <Edit2 :size="14" />
                  编辑
                </button>
                <button v-if="doc.status === 'DRAFT' || doc.status === 'PUBLISHED'" class="tb-btn tb-btn-danger" :disabled="!!actionLoading" @click="removeDoc">
                  <Trash2 :size="14" />
                  {{ actionLoading === 'deprecate' ? '处理中...' : '废弃' }}
                </button>
                <button v-if="doc.status === 'DEPRECATED'" class="tb-btn tb-btn-primary" :disabled="!!actionLoading" @click="restoreDeprecated">
                  <RefreshCw :size="14" />
                  {{ actionLoading === 'restore' ? '恢复中...' : '恢复并发布' }}
                </button>
                <button
                  v-if="doc.status === 'DEPRECATED'"
                  v-permission.disable="{ roles: ['admin'] }"
                  class="tb-btn tb-btn-danger"
                  :disabled="!!actionLoading"
                  @click="purgeDoc"
                >
                  <FileX :size="14" />
                  {{ actionLoading === 'purge' ? '删除中...' : '彻底删除' }}
                </button>
              </div>
            </div>

            <h1 class="doc-title">{{ doc.title }}</h1>

            <div class="doc-meta">
              <div v-if="doc.author" class="doc-meta-avatar">
                {{ doc.author.trim().charAt(0) || '?' }}
              </div>
              <div class="doc-meta-info">
                <div class="doc-meta-line">
                  <span v-if="doc.author" class="name">{{ doc.author }}</span>
                  <span v-if="doc.author" class="dot">·</span>
                  <RelativeTime :value="doc.updateTime" />
                  更新
                </div>
                <div class="doc-meta-extra">
                  <span class="extra-chip">版本 v{{ doc.version }}</span>
                  <span class="status-badge" :class="`status-${doc.status.toLowerCase()}`">
                    {{ statusLabel(doc.status) }}
                  </span>
                  <span
                    class="index-badge"
                    :class="{ 'index-ok': doc.indexStatus === 'INDEXED', 'index-failed': doc.indexStatus === 'FAILED' }"
                    :title="doc.indexStatus === 'FAILED' ? (doc.indexError || '向量化失败，不可检索') : ''"
                  >
                    {{ indexStatusLabel(doc.indexStatus) }}
                  </span>
                </div>
              </div>
              <!-- 向量化失败详情 -->
              <div v-if="doc.indexStatus === 'FAILED' && doc.indexError" class="index-error-detail">
                <AlertTriangle :size="14" class="index-error-icon" />
                <span class="index-error-text">{{ doc.indexError }}</span>
              </div>
            </div>

            <div class="tag-row">
              <div v-if="doc.tags.length" class="doc-tags">
                <span v-for="tag in doc.tags" :key="tag" class="doc-tag">{{ tag }}</span>
              </div>
              <!-- L1.5 来源回链：本知识文档由工单沉淀而来，反向链回源工单 -->
              <router-link
                v-if="doc.sourceTicketId"
                :to="`/tickets/${doc.sourceTicketId}`"
                class="source-ticket-badge"
                :title="`本文档由工单 TKT 沉淀，点击查看源工单 #${doc.sourceTicketId}`"
              >
                <Ticket :size="13" />
                来源工单 #{{ doc.sourceTicketId }}
              </router-link>
            </div>

            <div ref="articleContentRef" class="article-content" v-html="safeHtml"></div>

            <div v-if="related.length" class="related-docs">
              <h3>相关文档</h3>
              <RouterLink
                v-for="rel in related"
                :key="rel.id"
                :to="`/knowledge/${rel.id}`"
                class="related-item"
              >
                <FileText :size="14" />
                {{ rel.title }}
              </RouterLink>
            </div>
          </article>

          <!-- 右侧文章大纲 / 文档信息（可折叠；折叠后以章节圆点保留阅读位置） -->
          <CollapsiblePanel
            ref="rightPanelRef"
            side="right"
            storage-key="kd-right-collapsed"
            label="大纲"
            :width="260"
            sticky
            @update:collapsed="rightSidebarCollapsed = $event"
          >
            <!--
              折叠态图标轨：每个章节一个小圆点，当前阅读章节高亮放大，点击直接跳转。
              这是本次改造收益最大的一处——原实现折叠后是纯空白窄条，
              读长文时收起大纲就完全失去「读到哪了」的位置感。
            -->
            <template #rail>
              <RailButton
                v-for="item in toc"
                :key="item.id"
                dot
                :title="item.text"
                :active="activeToc === item.id"
                @click="scrollToToc(item.id)"
              />
            </template>

            <template #default="{ toggle }">
            <aside class="yuque-rightbar">
            <div class="rightbar-panel">
              <div class="toc">
                <div class="toc-title toc-title-actions">
                  <!-- 右侧栏的折叠按钮放**左端**：镜像对称，贴近它要让出的主内容区 -->
                  <CollapseToggle side="right" label="大纲" @click="toggle" />
                  <span>文章大纲</span>
                </div>
                <button
                  v-for="item in toc"
                  :key="item.id"
                  type="button"
                  class="toc-item"
                  :class="{ active: activeToc === item.id, 'level-three': item.level === 3 }"
                  @click="scrollToToc(item.id)"
                >
                  {{ item.text }}
                </button>
                <div v-if="!toc.length" class="toc-empty">正文暂无章节标题</div>
              </div>

              <div class="doc-info">
                <div class="toc-title">文档信息</div>
                <div class="info-row">
                  <span class="info-label">创建时间</span>
                  <span class="info-value">{{ fmtDateTime(doc.createTime) }}</span>
                </div>
                <div class="info-row">
                  <span class="info-label">更新时间</span>
                  <span class="info-value">{{ fmtDateTime(doc.updateTime) }}</span>
                </div>
                <div class="info-row">
                  <span class="info-label">版本</span>
                  <span class="info-value">v{{ doc.version }}</span>
                </div>
                <div v-if="doc.category" class="info-row">
                  <span class="info-label">分类</span>
                  <span class="info-value">{{ doc.category }}</span>
                </div>
              </div>
            </div>
          </aside>
          </template>
          </CollapsiblePanel>
        </div>
      </template>
      </main>
    </div>

    <!-- 版本历史抽屉 -->
    <el-drawer v-model="versionsOpen" title="版本历史" size="420px" destroy-on-close>
      <div v-if="store.versionsLoading" class="versions-empty">版本历史加载中...</div>
      <div v-else-if="store.versionsError" class="versions-empty">
        {{ store.versionsError }}
        <button v-if="doc" class="version-compare" @click="store.loadVersions(doc.id)">重试</button>
      </div>
      <div v-else-if="store.versions.length === 0" class="versions-empty">暂无历史版本</div>
      <div
        v-for="ver in store.versions"
        :key="ver.version"
        class="version-item"
        :class="{ 'version-current': ver.version === doc?.version }"
      >
        <div class="version-head">
          <span class="version-no">v{{ ver.version }}</span>
          <span class="version-type">{{ changeTypeLabel(ver.changeType) }}</span>
          <span v-if="ver.version === doc?.version" class="version-current-tag">当前</span>
        </div>
        <div class="version-meta">
          <span v-if="ver.changedBy" class="version-author">{{ ver.changedBy }}</span>
          <RelativeTime :value="ver.createTime" />
        </div>
        <p v-if="ver.changeReason" class="version-reason">{{ ver.changeReason }}</p>
        <div class="version-actions">
          <button
            v-if="ver.version !== doc?.version"
            class="version-rollback"
            :disabled="restoringVersion === ver.version"
            @click="rollbackVersion(ver.version)"
          >
            {{ restoringVersion === ver.version ? '回滚中...' : '回滚到此版本' }}
          </button>
          <button
            v-if="ver.version !== doc?.version"
            class="version-compare"
            :disabled="store.compareLoading"
            @click="compareAction(ver.version)"
          >
            <GitCompare :size="13" />
            对比当前
          </button>
        </div>
      </div>
    </el-drawer>

    <!-- 版本对比面板 -->
    <el-drawer
      v-model="diffOpen"
      title="版本对比"
      size="min(720px, 90vw)"
      destroy-on-close
    >
      <div v-if="store.compareLoading" class="diff-loading">
        <RefreshCw :size="20" class="diff-loading-icon" />
        正在对比两个版本...
      </div>

      <template v-else-if="store.compareResult">
        <div class="diff-header">
          <span class="diff-version-from">v{{ store.compareResult.fromVersion }}</span>
          <span class="diff-arrow">→</span>
          <span class="diff-version-to">v{{ store.compareResult.toVersion }}</span>
          <span class="diff-title">
            {{ store.compareResult.fromTitle }} → {{ store.compareResult.toTitle }}
          </span>
        </div>

        <div
          v-if="!store.compareResult.segments.some(s => s.type !== 'EQUAL')"
          class="diff-empty"
        >
          「两版本内容相同」
        </div>
        <div v-else class="diff-panel">
          <template v-for="(seg, i) in store.compareResult.segments" :key="i">
            <pre
              v-for="(line, j) in seg.lines"
              :key="`${i}-${j}`"
              class="diff-line"
              :class="`diff-line-${seg.type.toLowerCase()}`"
            ><span class="diff-marker">{{ seg.type === 'EQUAL' ? ' ' : seg.type === 'DELETE' ? '−' : '+' }}</span>{{ line }}</pre>
          </template>
        </div>
      </template>

      <div v-else class="diff-empty">对比失败或无可对比内容</div>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.knowledge-detail {
  height: calc(100vh - 56px);
  min-height: 620px;
  background: var(--color-bg);
  overflow: hidden;
}

.detail-workspace {
  display: flex;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

/* 侧栏折叠（容器/过渡/命中区/持久化）已统一由 CollapsiblePanel 负责，此处不再重复定义 */

.main-container {
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0;
}

/* ==================== 阅读纸布局 ==================== */

.reader-layout {
  display: flex;
  align-items: stretch;
  min-height: 100%;
  background: var(--color-bg-elevated);
}

.reader-paper {
  width: min(100%, 900px);
  flex: 1 1 900px;
  min-width: 0;
  margin: 0 auto;
  background: var(--color-surface);
  border-radius: 0;
  box-shadow: none;
  padding: 28px clamp(28px, 4vw, 52px) 64px;
  box-sizing: border-box;
}

/* 宽度与吸顶交由 CollapsiblePanel（sticky prop）负责，此处只管内部呈现 */
.yuque-rightbar {
  width: 100%;
  height: 100%;
  overflow-y: auto;
  background: var(--color-surface-hover);
  border-left: 1px solid var(--color-border-light);
}

.rightbar-panel {
  padding: 20px 16px;
}

/* ==================== 纸张顶部：面包屑 + 工具栏 ==================== */

.paper-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

/* 面包屑样式已随 AppBreadcrumb 公共组件收敛，本页不再重复定义 */

.paper-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tb-icon-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-bg-sunken);
    color: var(--color-text-primary);
  }
}

.tb-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary-light);
    color: var(--color-primary);
  }

  &.tb-btn-primary {
    background: var(--color-primary);
    border-color: var(--color-primary);
    color: var(--color-text-on-primary);

    &:hover {
      background: var(--color-primary-light);
      border-color: var(--color-primary-light);
      color: var(--color-text-on-primary);
    }
  }

  &.tb-btn-danger {
    color: var(--state-error);
    border-color: var(--color-border);

    &:hover {
      border-color: var(--state-error);
      color: var(--state-error);
      background: var(--state-error-bg);
    }
  }
}

/* ==================== 标题 / 作者 meta / 标签 ==================== */

.doc-title {
  font-size: 32px;
  font-weight: var(--weight-bold);
  line-height: 1.35;
  letter-spacing: -0.015em;
  color: var(--color-text-primary);
  margin: 0 0 20px 0;
  word-break: break-word;
}

.doc-meta {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 20px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--color-border-light);
}

.doc-meta-avatar {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: var(--weight-semibold);
  background: var(--color-primary-lighter);
  color: var(--color-primary);
}

.doc-meta-info {
  font-size: 13px;
  color: var(--color-text-secondary);
  line-height: 1.5;
  min-width: 0;
}

.doc-meta-line {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;

  .name {
    color: var(--color-text-primary);
    font-weight: var(--weight-medium);
  }

  .dot {
    color: var(--color-text-tertiary);
  }
}

.doc-meta-extra {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.extra-chip {
  font-size: 12px;
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
}

.status-badge {
  padding: 2px 8px;
  font-size: 11px;
  font-weight: var(--weight-medium);
  border-radius: var(--radius-full);

  &.status-draft {
    color: var(--color-text-secondary);
    background: var(--color-bg-sunken);
  }

  &.status-published {
    color: #047857;
    background: #d1fae5;
  }

  &.status-deprecated,
  &.status-archived {
    color: var(--color-text-tertiary);
    background: var(--color-bg-sunken);
    text-decoration: line-through;
  }
}

.index-badge {
  padding: 2px 8px;
  font-size: 11px;
  font-weight: var(--weight-medium);
  border-radius: var(--radius-full);
  color: var(--color-text-secondary);
  background: var(--color-bg-sunken);

  &.index-ok {
    color: #0369a1;
    background: #e0f2fe;
  }

  &.index-failed {
    color: var(--state-error, #f56c6c);
    background: var(--state-error-bg, #fef0f0);
  }
}

.index-error-detail {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 8px;
  padding: 8px 12px;
  background: var(--state-error-bg, #fef0f0);
  border-radius: 6px;
  font-size: 0.8125rem;
}
.index-error-icon {
  color: var(--state-error, #f56c6c);
  flex-shrink: 0;
  margin-top: 1px;
}
.index-error-text {
  color: var(--state-error, #f56c6c);
  word-break: break-word;
}

.tag-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 28px;
}

.doc-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.doc-tag {
  padding: 3px 10px;
  border-radius: var(--radius-full);
  font-size: 12px;
  background: var(--color-bg-sunken);
  color: var(--color-text-secondary);
}

/* L1.5 来源回链：由工单沉淀的知识文档反向链回源工单 */
.source-ticket-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: var(--weight-medium);
  color: var(--color-primary);
  background: var(--color-primary-lighter);
  border-radius: var(--radius-full);
  text-decoration: none;
  transition: opacity 0.2s ease;

  &:hover {
    opacity: 0.8;
    text-decoration: none;
  }
}

/* ==================== 正文排版（对齐语雀设计稿） ==================== */

.article-content {
  font-size: 15px;
  color: var(--color-text-primary);
  line-height: 1.8;

  :deep(h2) {
    font-size: 22px;
    font-weight: var(--weight-semibold);
    line-height: var(--leading-tight);
    margin: 32px 0 12px 0;
    color: var(--color-text-primary);
    scroll-margin-top: 88px;
  }

  :deep(h3) {
    font-size: 18px;
    font-weight: var(--weight-semibold);
    line-height: var(--leading-tight);
    margin: 24px 0 10px 0;
    color: var(--color-text-primary);
    scroll-margin-top: 88px;
  }

  :deep(p) {
    font-size: 15px;
    line-height: 1.8;
    color: var(--color-text-secondary);
    margin: 0 0 14px 0;

    &:last-child {
      margin-bottom: 0;
    }
  }

  :deep(ol),
  :deep(ul) {
    padding-left: 22px;
    margin: 0 0 16px 0;
    color: var(--color-text-secondary);
  }

  :deep(li) {
    font-size: 15px;
    line-height: 1.8;
    margin-bottom: 6px;
  }

  :deep(strong) {
    color: var(--color-text-primary);
    font-weight: var(--weight-semibold);
  }

  :deep(a) {
    color: var(--color-primary-light);
  }

  :deep(code) {
    font-family: var(--font-mono);
    font-size: 13px;
    background: var(--color-bg-sunken);
    padding: 2px 5px;
    border-radius: 4px;
    color: var(--color-text-primary);
  }

  /* 代码块（对齐设计稿 code-block） */
  :deep(pre) {
    position: relative;
    background: var(--color-bg-sunken);
    border-radius: var(--radius-md);
    padding: 42px 16px 16px;
    margin: 16px 0;
    overflow-x: auto;
    font-family: var(--font-mono);
    font-size: 13px;
    line-height: 1.6;
    color: var(--color-text-secondary);
    white-space: pre-wrap;
    word-break: break-word;

    code {
      background: transparent;
      padding: 0;
      font-family: inherit;
      font-size: inherit;
      line-height: inherit;
      color: inherit;
    }

    &::before {
      content: attr(data-language);
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      height: 30px;
      display: flex;
      align-items: center;
      padding: 0 14px;
      box-sizing: border-box;
      border-bottom: 1px solid var(--color-border-light);
      color: var(--color-text-tertiary);
      font-family: var(--font-body);
      font-size: 11px;
      font-weight: var(--weight-semibold);
      letter-spacing: 0;
    }
  }

  /* 引用块 / 提示块 → callout：primary-lighter 底 + 左侧 4px 主色竖条 */
  :deep(blockquote) {
    position: relative;
    background: var(--color-primary-lighter);
    border-left: 4px solid var(--color-primary);
    border-radius: var(--radius-md);
    padding: 38px 16px 14px;
    margin: 18px 0;

    &::before {
      content: '提示';
      position: absolute;
      top: 12px;
      left: 16px;
      color: var(--color-primary);
      font-size: var(--text-xs);
      font-weight: var(--weight-semibold);
    }

    p {
      margin: 0 0 6px 0;
      font-size: 14px;
      line-height: 1.6;

      &:last-child {
        margin-bottom: 0;
      }
    }

    strong {
      color: var(--color-text-primary);
    }
  }

  :deep(img) {
    max-width: 100%;
    border-radius: var(--radius-md);
  }

  :deep(figure) {
    margin: 20px 0;
  }

  :deep(figcaption) {
    margin-top: 8px;
    color: var(--color-text-tertiary);
    font-size: 12px;
    text-align: center;
  }

  :deep(table) {
    width: 100%;
    margin: 18px 0;
    border-collapse: collapse;
    table-layout: auto;
    font-size: 14px;
  }

  :deep(th),
  :deep(td) {
    min-width: 88px;
    padding: 9px 12px;
    border: 1px solid var(--color-border);
    color: var(--color-text-secondary);
    text-align: left;
    vertical-align: top;
  }

  :deep(th) {
    color: var(--color-text-primary);
    font-weight: var(--weight-semibold);
    background: var(--color-bg-sunken);
  }

  :deep(del),
  :deep(s) {
    color: var(--color-text-tertiary);
  }

  :deep(hr) {
    border: none;
    border-top: 1px solid var(--color-border-light);
    margin: 24px 0;
  }
}

/* 相关文档（参考文档区块，承接原「相关文章」功能） */
.related-docs {
  margin-top: 48px;
  padding-top: 24px;
  border-top: 1px solid var(--color-border-light);

  h3 {
    font-size: 16px;
    font-weight: var(--weight-semibold);
    margin: 0 0 12px 0;
    color: var(--color-text-primary);
  }
}

.related-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  font-size: 14px;
  color: var(--color-primary-light);
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}

/* ==================== 右侧栏：目录 + 文档信息 ==================== */

.toc {
  margin-bottom: 24px;
}

.toc-title {
  font-size: 12px;
  font-weight: var(--weight-medium);
  color: var(--color-text-tertiary);
  margin-bottom: 10px;
}

/* 标题行内含折叠按钮时改为两端对齐——折叠控制与它所控制的内容同处一行。
   右侧栏的按钮在**左端**（镜像对称），故用 gap 而非 space-between，
   并让文案占据剩余宽度靠左排布。 */
.toc-title-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  /* 抵消按钮自身内边距，使其视觉左边缘与下方列表对齐 */
  margin-left: -4px;
}
.toc-title-actions > span {
  flex: 1;
  min-width: 0;
}

.toc-item {
  display: block;
  width: 100%;
  border: 0;
  background: transparent;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  font-size: 13px;
  color: var(--color-text-secondary);
  text-decoration: none;
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  transition: all 0.15s ease;

  &:hover {
    color: var(--color-primary);
    background: var(--color-bg);
  }

  &.active {
    color: var(--color-primary);
    font-weight: var(--weight-medium);
    background: var(--color-primary-lighter);
  }
}

.toc-item.level-three {
  padding-left: 22px;
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.toc-empty {
  font-size: 12px;
  color: var(--color-text-tertiary);
  padding: 4px 10px;
}

.doc-info {
  padding-top: 20px;
  border-top: 1px solid var(--color-border-light);
}

.info-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--color-border-light);

  &:last-child {
    border-bottom: none;
  }
}

.info-label {
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.info-value {
  color: var(--color-text-secondary);
  text-align: right;
  word-break: break-all;
}

/* ==================== 响应式 ==================== */

@media (max-width: 1180px) {
  .reader-layout {
    flex-direction: column;
    align-items: center;
  }

  .reader-paper {
    width: 100%;
  }

  .yuque-rightbar {
    width: 100%;
    position: static;
    max-height: none;
  }

  .rightbar-panel {
    display: flex;
    gap: 24px;
    flex-wrap: wrap;

    & > div {
      flex: 1;
      min-width: 220px;
    }
  }
}

@media (max-width: 640px) {
  .reader-paper {
    padding: 24px 20px 48px;
  }
}

/* ==================== 空态 / 加载态 ==================== */

.empty-card {
  max-width: 520px;
  margin: 40px auto;
  padding: 40px 32px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  text-align: center;
}

.empty-icon {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: var(--color-primary-lighter);
  color: var(--color-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}

.empty-title {
  font-size: var(--text-xl);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 8px 0;
}

.empty-hint {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
  margin: 0 0 20px 0;
}

.empty-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
  flex-wrap: wrap;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
  }

  &.action-btn-primary {
    background: var(--color-primary);
    border-color: var(--color-primary);
    color: white;

    &:hover {
      background: var(--color-primary-light);
      color: white;
    }
  }
}

/* ==================== 版本历史抽屉 ==================== */

.versions-empty {
  padding: 32px 0;
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--text-sm);
}

.version-item {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  margin-bottom: 12px;

  &.version-current {
    border-color: var(--color-primary-light);
    background: var(--color-primary-lighter);
  }
}

.version-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.version-no {
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  font-size: var(--text-sm);
}

.version-type {
  padding: 2px 8px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  background: var(--color-bg-sunken);
  border-radius: var(--radius-full);
}

.version-current-tag {
  margin-left: auto;
  padding: 2px 8px;
  font-size: var(--text-xs);
  color: #047857;
  background: #d1fae5;
  border-radius: var(--radius-full);
}

.version-meta {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

.version-reason {
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  margin: 8px 0 0 0;
  background: var(--color-bg-sunken);
  border-radius: var(--radius-sm);
  padding: 6px 10px;
}

.version-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  align-items: center;
}

.version-rollback {
  margin-top: 10px;
  padding: 4px 12px;
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  color: var(--color-primary);
  background: transparent;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover:not(:disabled) {
    background: var(--color-primary-lighter);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.version-compare {
  margin-top: 10px;
  padding: 4px 12px;
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  color: var(--color-primary);
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: all 0.15s ease;

  &:hover:not(:disabled) {
    border-color: var(--color-primary);
    background: var(--color-primary-lighter);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

/* ==================== 版本对比 diff 面板 ==================== */

.diff-panel {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  max-height: 60vh;
  overflow-y: auto;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: var(--text-sm);
}

.diff-line {
  margin: 0;
  padding: 2px 12px;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.6;
  display: flex;
  align-items: flex-start;
}

.diff-line-equal {
  color: var(--color-text-secondary);
}

.diff-line-delete {
  background: #fef2f2;
  color: #dc2626;
  border-left: 3px solid #dc2626;
}

.diff-line-insert {
  background: #f0fdf4;
  color: #16a34a;
  border-left: 3px solid #16a34a;
}

.diff-marker {
  display: inline-block;
  min-width: 1.2em;
  user-select: none;
  font-family: monospace;
  flex-shrink: 0;
}

.diff-loading {
  text-align: center;
  padding: 40px 0;
  color: var(--color-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.diff-loading-icon {
  animation: diff-spin 1s linear infinite;
}

@keyframes diff-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.diff-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
  margin-bottom: 16px;
  font-weight: 600;
}

.diff-version-from,
.diff-version-to {
  color: var(--color-primary);
}

.diff-arrow {
  color: var(--color-text-tertiary);
}

.diff-title {
  color: var(--color-text-secondary);
  font-weight: 400;
  font-size: var(--text-xs);
  margin-left: auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.diff-empty {
  text-align: center;
  padding: 40px 0;
  color: var(--color-text-tertiary);
}
</style>
