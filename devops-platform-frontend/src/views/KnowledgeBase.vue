<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Search, Plus, Layers, Server, Network, Database, Boxes, ShieldCheck,
  GitBranch, Folder, Clock, RefreshCw, List, LayoutGrid,
  ChevronLeft, ChevronRight, FileText, Settings2, Pencil, Trash2, GitMerge,
  Tag as TagIcon, Upload
} from 'lucide-vue-next'
import { useKnowledgeStore } from '@/stores/knowledge'
import {
  createKnowledgeTag,
  deleteKnowledgeTag,
  fetchKnowledgeBases,
  fetchKnowledgeTags,
  indexStatusLabel,
  mergeKnowledgeTag,
  statusLabel,
  updateKnowledgeTag,
} from '@/api/knowledge'
import type { KnowledgeBaseItem, KnowledgeCategoryEntity, KnowledgeTag } from '@/api/types'
import { debounce } from '@/utils/persist'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import CollapsiblePanel from '@/components/common/CollapsiblePanel.vue'
import CollapseToggle from '@/components/common/CollapseToggle.vue'
import RailButton from '@/components/common/RailButton.vue'
import KnowledgeBaseManageDialog from '@/components/knowledge/KnowledgeBaseManageDialog.vue'
import KnowledgeUploadDialog from '@/components/knowledge/KnowledgeUploadDialog.vue'
import { useHotkeys } from '@/composables/useHotkeys'
import { useSearchHotkey } from '@/composables/useSearchHotkey'
import { notify, handleServerError } from '@/utils/notify'

const router = useRouter()
const route = useRoute()
const store = useKnowledgeStore()

/**
 * 侧栏（文档分类 + 标签）折叠状态。
 *
 * 折叠/持久化/过渡动画/命中区已统一收敛到 CollapsiblePanel，
 * 本页只需持有状态供快捷键与图标轨使用（三页此前各写一套 CSS 已开始漂移）。
 */
const sidebarCollapsed = ref(false)
const sidebarRef = ref<InstanceType<typeof CollapsiblePanel> | null>(null)

// `[` 收起/展开侧栏；`/` 聚焦搜索 + Esc 清空走共享 composable（与工单列表同一套）
const searchInputRef = ref<HTMLInputElement | null>(null)
useHotkeys([
  { key: '[', description: '收起/展开分类栏', handler: () => sidebarRef.value?.toggle() },
])
const { onSearchEsc } = useSearchHotkey(searchInputRef, () => { searchQuery.value = ''; onSearchInput() })

// 从 URL 恢复筛选状态
const searchQuery = ref(String(route.query.q ?? ''))
const appliedQuery = ref(String(route.query.q ?? ''))
const activeCategory = ref<string | null>(route.query.cat ? String(route.query.cat) : null)
const activeTag = ref<string | null>(route.query.tag ? String(route.query.tag) : null)
const activeStatus = ref(String(route.query.status ?? ''))
const activeSort = ref(String(route.query.sort ?? 'UPDATED_DESC'))
const viewMode = ref<'list' | 'grid'>(String(route.query.view ?? 'list') as 'list' | 'grid')
const tagManagerOpen = ref(false)
const managedTags = ref<KnowledgeTag[]>([])
const tagsLoading = ref(false)
const newTagName = ref('')

// ==================== 知识库（V2 顶层实体） ====================
// activeKbId 是「范围」而非普通筛选：切片参数随库不同，用户需要先知道自己
// 正在哪个库里浏览。URL 参数 kb 可分享直达（与 cat/tag 同一约定）。
const kbList = ref<KnowledgeBaseItem[]>([])
const kbLoadError = ref(false)
const activeKbId = ref<number | null>(route.query.kb ? Number(route.query.kb) : null)
const kbManageOpen = ref(false)
const uploadOpen = ref(false)

const loadBases = async () => {
  kbLoadError.value = false
  try {
    kbList.value = await fetchKnowledgeBases()
  } catch (error) {
    kbLoadError.value = true
    handleServerError(error, { action: '加载知识库列表' })
  }
}

const selectKb = (id: number | null) => {
  activeKbId.value = activeKbId.value === id ? null : id
  reload()
}

const categoryLabel = (category: KnowledgeCategoryEntity) => {
  const names: string[] = [category.name]
  const seen = new Set<number>([category.id])
  let parentId = category.parentId
  while (parentId != null && !seen.has(parentId)) {
    const parent = store.categories.find(item => item.id === parentId)
    if (!parent) break
    names.unshift(parent.name)
    seen.add(parent.id)
    parentId = parent.parentId
  }
  return names.join(' / ')
}

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'DEPRECATED', label: '已废弃' }
]

const hasFilters = computed(
  () => !!activeCategory.value || !!activeTag.value || !!activeStatus.value || !!searchQuery.value
)

const noActiveCategory = computed(() => !activeCategory.value && !activeTag.value && !activeStatus.value && !appliedQuery.value)

const openCreate = () => router.push({
  path: '/knowledge/editor/new',
  query: {
    draft: crypto.randomUUID(),
    // 带着库筛选点「新建」时，新文档默认归属当前库（编辑器仍可改）
    ...(activeKbId.value != null ? { kb: String(activeKbId.value) } : {}),
  },
})

const loadManagedTags = async () => {
  tagsLoading.value = true
  try {
    managedTags.value = await fetchKnowledgeTags()
  } catch (error) {
    handleServerError(error, { action: '加载标签' })
  } finally {
    tagsLoading.value = false
  }
}

const openTagManager = async () => {
  tagManagerOpen.value = true
  await loadManagedTags()
}

const createTag = async () => {
  const name = newTagName.value.trim()
  if (!name) return
  try {
    await createKnowledgeTag({ name })
    newTagName.value = ''
    await Promise.all([loadManagedTags(), store.loadHotTags()])
    notify.success('标签已创建')
  } catch (error) {
    handleServerError(error, { action: '创建标签' })
  }
}

const renameTag = async (tag: KnowledgeTag) => {
  try {
    const { value } = await ElMessageBox.prompt('重命名会同步修改所有引用该标签的文档。', '重命名标签', {
      inputValue: tag.name,
      inputPattern: /\S+/,
      inputErrorMessage: '请输入标签名称',
    })
    await updateKnowledgeTag(tag.id, { name: value.trim(), description: tag.description || undefined, color: tag.color || undefined })
    await Promise.all([loadManagedTags(), store.loadHotTags()])
    notify.success('标签已重命名')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    handleServerError(error, { action: '重命名标签' })
  }
}

const mergeTag = async (tag: KnowledgeTag) => {
  try {
    const { value } = await ElMessageBox.prompt('输入要保留的目标标签名称。', `合并「${tag.name}」`, {
      inputPattern: /\S+/,
      inputErrorMessage: '请输入目标标签',
    })
    const target = managedTags.value.find(item => item.name.toLocaleLowerCase() === value.trim().toLocaleLowerCase())
    if (!target || target.id === tag.id) {
      notify.warning('未找到可合并的目标标签')
      return
    }
    await mergeKnowledgeTag(tag.id, target.id)
    await Promise.all([loadManagedTags(), store.loadHotTags()])
    notify.success('标签已合并')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    handleServerError(error, { action: '合并标签' })
  }
}

const removeTag = async (tag: KnowledgeTag) => {
  try {
    if (tag.usageCount > 0) {
      const { value } = await ElMessageBox.prompt('该标签仍被文档使用，请输入替换标签名称。', `删除「${tag.name}」`, {
        inputPattern: /\S+/,
        inputErrorMessage: '请输入替换标签',
      })
      const replacement = managedTags.value.find(item => item.name.toLocaleLowerCase() === value.trim().toLocaleLowerCase())
      if (!replacement || replacement.id === tag.id) {
        notify.warning('未找到可替换的标签')
        return
      }
      await deleteKnowledgeTag(tag.id, replacement.id)
    } else {
      await ElMessageBox.confirm(`确认删除标签「${tag.name}」？`, '删除标签', {
        type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
      })
      await deleteKnowledgeTag(tag.id)
    }
    await Promise.all([loadManagedTags(), store.loadHotTags()])
    notify.success('标签已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    handleServerError(error, { action: '删除标签' })
  }
}

/** 加载列表：筛选与分页全部由后端执行 */
const reload = () => {
  store.loadList({
    page: 1,
    keyword: appliedQuery.value || undefined,
    category: activeCategory.value || undefined,
    tag: activeTag.value || undefined,
    status: activeStatus.value || undefined,
    sort: activeSort.value,
    kbId: activeKbId.value ?? undefined
  })
}

const applySearch = debounce((v: string) => {
  appliedQuery.value = v
  if (!v && activeSort.value === 'RELEVANCE') activeSort.value = 'UPDATED_DESC'
  reload()
}, 300)

onBeforeUnmount(() => applySearch.flush())

// 筛选状态写回 URL（防抖，避免每次按键都触发路由替换）
const syncUrl = debounce(() => {
  const query: Record<string, string> = {}
  if (appliedQuery.value) query.q = appliedQuery.value
  if (activeCategory.value) query.cat = activeCategory.value
  if (activeTag.value) query.tag = activeTag.value
  if (activeStatus.value) query.status = activeStatus.value
  if (activeKbId.value != null) query.kb = String(activeKbId.value)
  if (activeSort.value !== 'UPDATED_DESC') query.sort = activeSort.value
  if (viewMode.value !== 'list') query.view = viewMode.value
  if (store.currentPage > 1) query.page = String(store.currentPage)
  router.replace({ query })
}, 200)

watch([appliedQuery, activeCategory, activeTag, activeStatus, activeSort, activeKbId, viewMode, () => store.currentPage], syncUrl)

/**
 * 卸载时**取消**尚未落定的 URL 同步。
 *
 * 上面的 `applySearch` 用的是 `flush()`（补执行），这里必须是 `cancel()`（丢弃）——
 * 两者不能照抄，因为回调的性质完全不同：
 *   - `applySearch` 的回调是 `reload()`，只动本组件的数据，卸载后执行最多白跑一次；
 *   - `syncUrl` 的回调是 `router.replace({ query })`，它**以当前路由为基准**。
 *
 * 漏掉这行的实际后果：用户点了分类筛选，200ms 防抖还没到就点侧栏跳去工单列表，
 * 定时器随后触发，把知识库的筛选参数写到了工单页的地址栏上——
 * 地址栏变成 `/tickets?cat=K8S`，工单列表按一个它根本不认识的参数刷新，
 * 或者干脆丢掉用户自己的筛选。
 *
 * 这个缺陷是靠「同一文件里两个防抖，一个有清理一个没有」的不一致发现的，
 * 并由 `KnowledgeBase.urlsync.test.ts` 的卸载用例确认（修复前该用例报
 * `router.replace` 在卸载后仍被调用）。
 */
onBeforeUnmount(() => syncUrl.cancel())

const selectCategory = (name: string) => {
  activeCategory.value = activeCategory.value === name ? null : name
  reload()
}

const selectTag = (tag: string) => {
  activeTag.value = activeTag.value === tag ? null : tag
  reload()
}

const clearFilters = () => {
  applySearch.cancel()
  activeCategory.value = null
  activeTag.value = null
  activeStatus.value = ''
  activeKbId.value = null
  searchQuery.value = ''
  appliedQuery.value = ''
  if (activeSort.value === 'RELEVANCE') activeSort.value = 'UPDATED_DESC'
  reload()
  notify.success('已清除筛选')
}

const retrySidebarData = () => Promise.all([store.loadCategories(), store.loadHotTags()])

const onSearchInput = () => applySearch(searchQuery.value)

const pageNumbers = computed<number[]>(() => {
  const total = store.totalPages
  const cur = store.currentPage
  const pages: number[] = []
  if (total <= 5) {
    for (let i = 1; i <= total; i++) pages.push(i)
    return pages
  }
  pages.push(1)
  if (cur > 3) pages.push(-1)
  const start = Math.max(2, cur - 1)
  const end = Math.min(total - 1, cur + 1)
  for (let i = start; i <= end; i++) pages.push(i)
  if (cur < total - 2) pages.push(-1)
  pages.push(total)
  return pages
})

/** 分类图标（展示映射，非数据） */
function categoryIcon(name: string) {
  const n = name || ''
  if (n.includes('数据') || n.includes('SQL') || n.includes('MySQL') || n.includes('库')) return Database
  if (n.includes('网络') || n.includes('Nginx') || n.includes('网关')) return Network
  if (n.includes('服务器') || n.includes('主机') || n.includes('Linux') || n.includes('SRE')) return Server
  if (n.includes('安全') || n.includes('合规')) return ShieldCheck
  if (n.includes('容器') || n.includes('K8s') || n.includes('Kubernetes') || n.includes('Docker')) return Boxes
  if (n.includes('中间件') || n.includes('Redis') || n.includes('MQ') || n.includes('缓存')) return Boxes
  if (n.includes('CI') || n.includes('CD') || n.includes('Jenkins') || n.includes('部署')) return GitBranch
  return Folder
}

/** 更新时间：已改为直接使用 RelativeTime 组件，此函数保留供宫格视图等可能的手动格式化 */
// formatUpdate 不再需要：模板已直接使用 <RelativeTime :value="doc.updateTime" />

function avatarChar(name?: string | null): string {
  return (name || '文').charAt(0)
}

onMounted(() => {
  store.loadCategories()
  store.loadHotTags()
  void loadBases()
  // 从 URL 恢复页码
  const page = Number(route.query.page) || 1
  // loadLibraryTotal 已删除：loadList 成功后已写 libraryTotal，无需冗余请求
  store.loadList({
    page,
    keyword: appliedQuery.value || undefined,
    category: activeCategory.value || undefined,
    tag: activeTag.value || undefined,
    status: activeStatus.value || undefined,
    sort: activeSort.value,
    kbId: activeKbId.value ?? undefined
  })
})
</script>

<template>
  <div class="knowledge-base">
    <!-- ===== Page Header ===== -->
    <div class="page-header">
      <div>
        <h1 class="page-title">知识库</h1>
        <p class="page-subtitle">统一管理运维文档，智能检索排障方案</p>
      </div>
      <div class="header-actions">
        <button class="btn-plain" type="button" @click="kbManageOpen = true">
          <Settings2 :size="16" />
          知识库管理
        </button>
        <button class="btn-plain" type="button" @click="uploadOpen = true">
          <Upload :size="16" />
          上传文档
        </button>
        <button class="btn-new" @click="openCreate">
          <Plus :size="16" />
          新建文档
        </button>
      </div>
    </div>

    <!-- ===== Search Bar ===== -->
    <div class="search-bar">
      <div class="search-container">
        <Search class="search-icon" :size="18" />
        <input
          ref="searchInputRef"
          v-model="searchQuery"
          @input="onSearchInput"
          @keydown.esc="onSearchEsc"
          type="text"
          class="search-input"
          placeholder="搜索知识文档、排障指南、操作手册..."
        />
      </div>
    </div>

    <!-- ===== Content ===== -->
    <div class="main-container">
      <!-- Left Sidebar（折叠按钮在「文档分类」标题行内；折叠后留图标轨可继续切换筛选） -->
      <CollapsiblePanel
        ref="sidebarRef"
        side="left"
        storage-key="kb-sidebar-collapsed"
        label="分类与标签"
        :width="264"
        @update:collapsed="sidebarCollapsed = $event"
      >
        <!-- 折叠态图标轨：当前筛选项高亮，点击直接切换，无需先展开 -->
        <template #rail>
          <RailButton
            title="全部文档"
            :active="noActiveCategory"
            :count="store.libraryTotal"
            @click="clearFilters"
          >
            <Layers :size="17" />
          </RailButton>
          <div class="rail-divider" />
          <RailButton
            v-for="category in store.categories"
            :key="category.id"
            :title="categoryLabel(category)"
            :active="activeCategory === category.name"
            :count="category.docCount"
            @click="selectCategory(category.name)"
          >
            <component :is="categoryIcon(category.name)" :size="17" />
          </RailButton>
          <template v-if="store.hotTags.length">
            <div class="rail-divider" />
            <RailButton
              v-for="item in store.hotTags.slice(0, 8)"
              :key="item.tag"
              :title="`标签: ${item.tag}（${item.count}）`"
              :active="activeTag === item.tag"
              @click="selectTag(item.tag)"
            >
              <TagIcon :size="15" />
            </RailButton>
          </template>
        </template>

        <template #default="{ toggle }">
        <aside class="sidebar">
        <!-- Knowledge Bases（V2 顶层实体：切片参数随库配置，先选库再浏览） -->
        <div class="sidebar-section">
          <div class="sidebar-title sidebar-title-actions">
            <span>知识库</span>
            <button class="kb-manage-btn" type="button" title="知识库管理" @click="kbManageOpen = true">
              <Settings2 :size="14" />
            </button>
          </div>
          <div class="category-list">
            <button
              class="category-header"
              :class="{ active: activeKbId === null }"
              @click="selectKb(null)"
            >
              <span class="category-name">
                <Layers :size="16" />
                全部知识库
              </span>
            </button>
            <template v-if="kbList.length">
              <button
                v-for="kb in kbList"
                :key="kb.id"
                class="category-header"
                :class="{ active: activeKbId === kb.id }"
                @click="selectKb(kb.id)"
              >
                <span class="category-name" :title="kb.description || kb.name">
                  <Database :size="16" />
                  {{ kb.name }}
                </span>
                <span class="child-count" :class="{ active: activeKbId === kb.id }">
                  {{ kb.docCount }}
                </span>
              </button>
            </template>
            <button v-else-if="kbLoadError" class="sidebar-empty sidebar-retry" type="button" @click="loadBases">知识库加载失败，点击重试</button>
            <p v-else class="sidebar-empty">暂无知识库</p>
          </div>
        </div>

        <!-- Categories -->
        <div class="sidebar-section">
          <div class="sidebar-title sidebar-title-actions">
            <span>文档分类</span>
            <CollapseToggle side="left" label="分类与标签" @click="toggle" />
          </div>
          <div class="category-list">
            <button
              class="category-header"
              :class="{ active: noActiveCategory }"
              @click="clearFilters"
            >
              <span class="category-name">
                <Layers :size="16" />
                全部文档
              </span>
              <span class="child-count" :class="{ active: noActiveCategory }">
                {{ store.libraryTotal }}
              </span>
            </button>
            <template v-if="store.categories.length">
              <button
                v-for="category in store.categories"
                :key="category.id"
                class="category-header"
                :class="{ active: activeCategory === category.name }"
                @click="selectCategory(category.name)"
              >
                <span class="category-name" :title="categoryLabel(category)">
                  <component :is="categoryIcon(category.name)" :size="16" />
                  {{ categoryLabel(category) }}
                </span>
                <span class="child-count" :class="{ active: activeCategory === category.name }">
                  {{ category.docCount }}
                </span>
              </button>
            </template>
            <button v-else-if="store.categoriesLoadError" class="sidebar-empty sidebar-retry" type="button" @click="retrySidebarData">分类加载失败，点击重试</button>
            <p v-else class="sidebar-empty">暂无分类</p>
          </div>
        </div>

        <!-- Hot Tags -->
        <div class="sidebar-section">
          <div class="sidebar-title sidebar-title-actions">
            <span>热门标签</span>
            <button class="tag-manage-trigger" type="button" title="管理标签" @click="openTagManager"><Settings2 :size="14" /></button>
          </div>
          <div v-if="store.hotTags.length" class="tags-list">
            <button
              v-for="item in store.hotTags"
              :key="item.tag"
              type="button"
              class="tag-item"
              :class="{ active: activeTag === item.tag }"
              @click="selectTag(item.tag)"
            >
              {{ item.tag }}
              <span class="tag-count">{{ item.count }}</span>
            </button>
          </div>
          <button v-else-if="store.hotTagsLoadError" class="sidebar-empty sidebar-retry" type="button" @click="retrySidebarData">标签加载失败，点击重试</button>
          <p v-else class="sidebar-empty">暂无标签</p>
        </div>
        </aside>
        </template>
      </CollapsiblePanel>

      <!-- Right Content -->
      <main class="content-area">
        <!-- Sort Bar -->
        <div class="sort-bar">
          <div class="sort-left">
            <div class="filter-group">
              <span class="filter-label">排序:</span>
              <select v-model="activeSort" class="sort-select" @change="reload">
                <option value="UPDATED_DESC">最近更新</option>
                <option value="CREATED_DESC">最近创建</option>
                <option value="TITLE_ASC">标题 A-Z</option>
                <option value="RELEVANCE" :disabled="!appliedQuery">搜索相关度</option>
              </select>
            </div>
            <div class="filter-group">
              <span class="filter-label">状态:</span>
              <select v-model="activeStatus" class="sort-select" @change="reload">
                <option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
                  {{ opt.label }}
                </option>
              </select>
            </div>
            <button
              v-if="hasFilters"
              class="btn-clear"
              @click="clearFilters"
            >
              <RefreshCw :size="13" />
              清除筛选
            </button>
          </div>
          <div class="sort-right">
            <span class="count-text">共 {{ store.total }} 篇文档</span>
            <div class="view-toggle">
              <button
                class="view-btn"
                :class="{ active: viewMode === 'list' }"
                title="列表视图"
                @click="viewMode = 'list'"
              >
                <List :size="15" />
              </button>
              <button
                class="view-btn"
                :class="{ active: viewMode === 'grid' }"
                title="宫格视图"
                @click="viewMode = 'grid'"
              >
                <LayoutGrid :size="15" />
              </button>
            </div>
          </div>
        </div>

        <!--
          四态统一（与 TicketList / AlertList / ApprovalCenter 共用）。

          此处一并退掉 EmptyState —— 项目里 AppEmpty 与 EmptyState 两套
          空态组件并存，同一产品的「没有数据」长着两副面孔（圆角/内边距/
          图标底色/按钮样式都不同）。统一到 AppEmpty，它多出 search /
          network / permission / notfound 几种语义分型，能把「还没有数据」
          与「筛选没命中」区分开——这两句话对用户的下一步动作完全不同。

          原实现的错误分支也缺 length 判断：翻页失败会让整个文档列表消失。
        -->
        <DataStateBoundary
          :loading="store.loading"
          :error="store.loadError"
          :count="store.list.length"
          :filtered="hasFilters"
          empty-title="还没有文档"
          empty-description="创建第一篇文档，沉淀团队的排障经验"
          filtered-description="换个关键词或清空筛选试试"
          empty-action-text="新增文档"
          :skeleton-rows="5"
          skeleton-height="72px"
          @retry="reload"
          @empty-action="openCreate"
        >
          <div v-if="viewMode === 'list'" class="articles-list">
            <article
              v-for="doc in store.list"
              :key="doc.id"
              class="article-row"
              @click="router.push(`/knowledge/${doc.id}`)"
            >
              <div class="article-body">
                <div class="article-head">
                  <RouterLink :to="`/knowledge/${doc.id}`" class="article-title" @click.stop>
                    {{ doc.title }}
                  </RouterLink>
                  <span class="category-pill">{{ doc.category || '未分类' }}</span>
                  <span v-if="doc.status !== 'PUBLISHED'" class="lifecycle-pill">
                    {{ statusLabel(doc.status) }}
                  </span>
                </div>
                <p class="article-excerpt">{{ doc.summary || '暂无摘要' }}</p>
                <div v-if="doc.tags.length" class="article-tags">
                  <span v-for="tag in doc.tags.slice(0, 5)" :key="tag" class="article-tag">{{ tag }}</span>
                </div>
                <div class="article-foot">
                  <div class="meta-item author">
                    <span class="author-avatar">{{ avatarChar(doc.author) }}</span>
                    <span>{{ doc.author || '运维团队' }}</span>
                  </div>
                  <span class="meta-item">
                    <Clock :size="13" />
                    <RelativeTime :value="doc.updateTime" />
                  </span>
                  <span class="meta-item">
                    <FileText :size="13" />
                    v{{ doc.version }}
                  </span>
                  <span
                    class="index-badge"
                    :class="{ 'index-failed': doc.indexStatus === 'FAILED' }"
                  >
                    {{ indexStatusLabel(doc.indexStatus) }}
                  </span>
                </div>
              </div>
            </article>
          </div>

          <div v-else class="articles-grid">
            <article
              v-for="doc in store.list"
              :key="doc.id"
              class="article-card"
              @click="router.push(`/knowledge/${doc.id}`)"
            >
              <div class="card-head">
                <span class="category-pill">{{ doc.category || '未分类' }}</span>
                <span v-if="doc.status !== 'PUBLISHED'" class="lifecycle-pill">
                  {{ statusLabel(doc.status) }}
                </span>
                <span class="index-badge" :class="{ 'index-failed': doc.indexStatus === 'FAILED' }">
                  {{ indexStatusLabel(doc.indexStatus) }}
                </span>
              </div>
              <RouterLink :to="`/knowledge/${doc.id}`" class="card-title" @click.stop>
                {{ doc.title }}
              </RouterLink>
              <p class="card-excerpt">{{ doc.summary || '暂无摘要' }}</p>
              <div v-if="doc.tags.length" class="article-tags">
                <span v-for="tag in doc.tags.slice(0, 3)" :key="tag" class="article-tag">{{ tag }}</span>
              </div>
              <div class="card-foot">
                <span class="meta-item author">
                  <span class="author-avatar">{{ avatarChar(doc.author) }}</span>
                  <span>{{ doc.author || '运维团队' }}</span>
                </span>
                <span class="meta-item"><RelativeTime :value="doc.updateTime" /></span>
              </div>
            </article>
          </div>
        </DataStateBoundary>

        <!-- Pagination -->
        <div v-if="store.totalPages > 1" class="pagination">
          <button
            class="page-btn"
            :disabled="store.currentPage === 1"
            @click="store.goToPage(store.currentPage - 1)"
          >
            <ChevronLeft :size="16" />
          </button>
          <span v-for="(p, i) in pageNumbers" :key="`pn-${i}`">
            <button
              v-if="p !== -1"
              class="page-btn num"
              :class="{ active: p === store.currentPage }"
              @click="store.goToPage(p)"
            >{{ p }}</button>
            <span v-else class="page-ellipsis">...</span>
          </span>
          <button
            class="page-btn"
            :disabled="store.currentPage === store.totalPages"
            @click="store.goToPage(store.currentPage + 1)"
          >
            <ChevronRight :size="16" />
          </button>
        </div>
      </main>
    </div>
  </div>

  <el-dialog v-model="tagManagerOpen" title="标签管理" width="min(640px, calc(100vw - 32px))" destroy-on-close>
    <div class="tag-manager-create">
      <input v-model="newTagName" maxlength="64" placeholder="输入新标签" @keyup.enter="createTag" />
      <button class="btn-new" type="button" @click="createTag">+ 新建标签</button>
    </div>
    <div v-if="tagsLoading" class="tag-manager-state">正在加载标签...</div>
    <div v-else-if="!managedTags.length" class="tag-manager-state">暂无标签</div>
    <div v-else class="tag-manager-list">
      <div v-for="tag in managedTags" :key="tag.id" class="tag-manager-row">
        <span class="article-tag">{{ tag.name }}</span>
        <span class="tag-manager-count">{{ tag.usageCount }} 篇文档</span>
        <div class="tag-manager-actions">
          <button type="button" title="重命名" @click="renameTag(tag)"><Pencil :size="14" /></button>
          <button type="button" title="合并标签" @click="mergeTag(tag)"><GitMerge :size="14" /></button>
          <button type="button" title="删除标签" @click="removeTag(tag)"><Trash2 :size="14" /></button>
        </div>
      </div>
    </div>
  </el-dialog>

  <!-- V2：知识库管理（新建/编辑切片参数/重建索引）与文件上传入库 -->
  <KnowledgeBaseManageDialog v-model="kbManageOpen" @changed="loadBases" />
  <KnowledgeUploadDialog v-model="uploadOpen" :active-kb-id="activeKbId" @uploaded="reload" />
</template>

<style scoped lang="scss">
.knowledge-base {
  min-height: 100vh;
  background: var(--surface-0);
  padding-bottom: 32px;
}

/* ===== Page Header ===== */
.page-header {
  background: var(--surface-1);
  border-bottom: 1px solid var(--border-1);
  padding: 24px 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  font-family: var(--font-display);
  font-size: var(--text-2xl);
  font-weight: var(--weight-bold);
  color: var(--text-1);
  margin: 0 0 4px 0;
  letter-spacing: -0.02em;
}

.page-subtitle {
  font-size: var(--text-sm);
  color: var(--text-3);
  margin: 0;
}

.btn-new {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 20px;
  border-radius: var(--radius);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--brand);
  color: var(--text-inverse);
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: var(--brand-hover);
  }
}

/* ===== Search Bar ===== */
.search-bar {
  background: var(--surface-0);
  padding: 20px 32px 16px;
}

.search-container {
  max-width: 720px;
  position: relative;
}

.search-icon {
  position: absolute;
  left: 14px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--text-3);
  pointer-events: none;
}

.search-input {
  width: 100%;
  height: 44px;
  padding: 0 16px 0 42px;
  border: 1px solid var(--border-2);
  border-left: 3px solid var(--brand-hover);
  border-radius: var(--radius);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--surface-1);
  color: var(--text-1);
  outline: none;
  transition: border-color 0.15s ease;
  box-sizing: border-box;

  &:focus {
    border-color: var(--brand-hover);
  }

  &::placeholder {
    color: var(--text-3);
  }
}

/* ===== Main Container ===== */
.main-container {
  display: flex;
  gap: 0;
  padding: 0 32px 32px;
  max-width: 1400px;
  margin: 0 auto;

  @media (max-width: 1024px) {
    flex-direction: column;
  }
}

/* 折叠态图标轨内的分组分隔线（折叠/过渡/命中区等由 CollapsiblePanel 统一负责） */
.rail-divider {
  width: 18px;
  height: 1px;
  margin: 4px 0;
  background: var(--border-1, var(--border-1));
  flex-shrink: 0;
}

/* ===== Sidebar ===== */
.sidebar {
  width: 100%;
  padding-right: 24px;
  border-right: 1px solid var(--border-1);

  @media (max-width: 1024px) {
    padding-right: 0;
    border-right: none;
  }
}

.sidebar-section {
  margin-bottom: 24px;
}

.sidebar-title {
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  color: var(--text-3);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 10px;
  padding-left: 12px;
}

.sidebar-title-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 8px;
}

.tag-manage-trigger {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
}

.tag-manage-trigger:hover { color: var(--brand); background: var(--brand-subtle); }

.sidebar-empty {
  font-size: var(--text-xs);
  color: var(--text-3);
  margin: 0;
  padding-left: 12px;
}
.sidebar-retry { border: 0; background: transparent; cursor: pointer; text-align: left; }
.sidebar-retry:hover { color: var(--brand); }

.category-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.category-header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border: none;
  background: transparent;
  border-radius: var(--radius);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  color: var(--text-2);
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background: var(--surface-hover);
  }

  &.active {
    background: var(--brand-subtle);
    color: var(--brand);
  }
}

.category-name {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.child-count {
  flex-shrink: 0;
  font-size: var(--text-xs);
  color: var(--text-3);
  background: var(--surface-0);
  padding: 1px 8px;
  border-radius: var(--radius-full);

  &.active {
    background: var(--brand);
    color: #fff;
    font-weight: var(--weight-semibold);
  }
}

/* Tags */
.tags-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-left: 4px;
}

.tag-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  color: var(--text-2);
  background: var(--surface-0);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-full);
  cursor: pointer;
  border: 1px solid var(--border-1);
  font-family: inherit;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--brand-hover);
    color: var(--brand);
  }

  &.active {
    background: var(--brand);
    border-color: var(--brand);
    color: #fff;
  }
}

.tag-count {
  font-size: 10px;
  color: var(--text-3);
}

.tag-item.active .tag-count {
  color: rgba(255, 255, 255, 0.75);
}

/* ===== Content Area ===== */
.content-area {
  flex: 1;
  min-width: 0;
  padding-left: 24px;

  @media (max-width: 1024px) {
    padding-left: 0;
  }
}

.sort-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 12px;
}

.sort-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.filter-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.filter-label {
  font-size: var(--text-sm);
  color: var(--text-2);
  font-weight: var(--weight-medium);
}

.sort-select {
  border: 1px solid var(--border-2);
  border-radius: var(--radius-sm);
  padding: 4px 28px 4px 8px;
  font-size: var(--text-sm);
  font-family: var(--font-body);
  color: var(--text-1);
  background: var(--surface-1);
  outline: none;
  cursor: pointer;
}

.btn-clear {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border: 1px solid var(--border-2);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--surface-1);
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--brand);
    color: var(--brand);
  }
}

.sort-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.count-text {
  font-size: var(--text-sm);
  color: var(--text-3);
}

.view-toggle {
  display: flex;
  align-items: center;
  gap: 2px;
  border: 1px solid var(--border-2);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.view-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px 8px;
  border: none;
  background: var(--surface-1);
  color: var(--text-3);
  cursor: pointer;

  &.active {
    background: var(--brand-subtle);
    color: var(--brand);
  }

  &:not(:last-child) {
    border-right: 1px solid var(--border-2);
  }
}

/* ===== Load / Error ===== */
/* 骨架与空态已收敛到 SkeletonRows / DataStateBoundary */

.load-error {
  color: var(--danger, var(--danger));
}

/* ===== Articles List ===== */
.articles-list {
  border-radius: var(--radius);
  background: var(--surface-1);
  overflow: hidden;
  margin-bottom: 24px;
}

.article-row {
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-1);
  cursor: pointer;
  transition: background 0.12s ease;

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background: var(--surface-hover);
  }
}

.article-body {
  min-width: 0;
}

.article-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.article-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--brand);
  text-decoration: none;
  line-height: var(--leading-tight);

  &:hover {
    color: var(--brand);
  }
}

.category-pill {
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: var(--weight-medium);
  background: var(--brand-subtle);
  color: var(--brand);
  white-space: nowrap;
}

.lifecycle-pill {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  color: #92400e;
  background: var(--warning-subtle);
  font-size: 11px;
  font-weight: var(--weight-medium);
}

.article-excerpt {
  font-size: var(--text-sm);
  color: var(--text-2);
  line-height: var(--leading-relaxed);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.article-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.article-tag {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 1px 8px;
  border-radius: var(--radius-full);
  background: var(--surface-2);
  color: var(--text-2);
  font-size: 12px;
}

.article-foot {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-xs);
  color: var(--text-3);

  &.author {
    gap: 6px;
    color: var(--text-3);
  }
}

.author-avatar {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  font-size: 10px;
  font-weight: var(--weight-semibold);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.index-badge {
  font-size: 11px;
  color: var(--brand);
  background: var(--brand-subtle);
  padding: 1px 8px;
  border-radius: var(--radius-full);

  &.index-failed {
    color: var(--danger, var(--danger));
    background: var(--danger-subtle);
  }
}

/* ===== Articles Grid ===== */
.articles-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.article-card {
  background: var(--surface-1);
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  padding: 16px;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--brand-hover);
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
  }
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.card-title {
  display: block;
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--brand);
  line-height: var(--leading-tight);
  margin-bottom: 6px;
  text-decoration: none;

  &:hover {
    color: var(--brand);
  }
}

.card-excerpt {
  font-size: var(--text-xs);
  color: var(--text-2);
  line-height: var(--leading-relaxed);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin: 0 0 12px;
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.tag-manager-create {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.tag-manager-create input {
  flex: 1;
  min-width: 0;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border-2);
  border-radius: 5px;
  background: var(--surface-1);
  color: var(--text-1);
  font: inherit;
}

.tag-manager-create .btn-new { height: 34px; white-space: nowrap; }

/* ===== V2：页头操作组与知识库管理入口 ===== */
.header-actions { display: flex; align-items: center; gap: 10px; }
.btn-plain {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: var(--radius);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--brand-subtle);
  color: var(--text-1);
  border: 1px solid var(--border-2, #dcdfe6);
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease;

  &:hover {
    border-color: var(--brand);
    color: var(--brand);
  }
}
.kb-manage-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;

  &:hover { color: var(--brand); background: var(--brand-subtle); }
}
.tag-manager-state { padding: 28px 0; color: var(--text-3); text-align: center; font-size: 13px; }
.tag-manager-list { border-top: 1px solid var(--border-1); }
.tag-manager-row { display: flex; align-items: center; gap: 10px; min-height: 44px; border-bottom: 1px solid var(--border-1); }
.tag-manager-count { color: var(--text-3); font-size: 12px; }
.tag-manager-actions { display: flex; align-items: center; gap: 2px; margin-left: auto; }
.tag-manager-actions button { width: 28px; height: 28px; display: inline-flex; align-items: center; justify-content: center; border: 0; border-radius: 4px; background: transparent; color: var(--text-3); cursor: pointer; }
.tag-manager-actions button:hover { color: var(--brand); background: var(--brand-subtle); }

/* ===== Pagination ===== */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.page-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--border-2);
  border-radius: var(--radius);
  background: var(--surface-1);
  color: var(--text-3);
  cursor: pointer;
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  transition: all 0.15s ease;

  &.num {
    color: var(--text-1);
  }

  &.active {
    border-color: var(--brand);
    background: var(--brand);
    color: var(--text-inverse);
  }

  &:hover:not(:disabled):not(.active) {
    border-color: var(--brand);
    color: var(--brand);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.page-ellipsis {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  font-size: var(--text-sm);
  color: var(--text-3);
}
</style>
