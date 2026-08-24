<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, Plus, Layers, Server, Network, Database, Boxes, ShieldCheck,
  GitBranch, Folder, Clock, RefreshCw, List, LayoutGrid,
  ChevronLeft, ChevronRight, FileText, Settings2, Pencil, Trash2, GitMerge,
  Tag as TagIcon
} from 'lucide-vue-next'
import { useKnowledgeStore } from '@/stores/knowledge'
import {
  createKnowledgeTag,
  deleteKnowledgeTag,
  fetchKnowledgeTags,
  indexStatusLabel,
  mergeKnowledgeTag,
  statusLabel,
  updateKnowledgeTag,
} from '@/api/knowledge'
import type { KnowledgeCategoryEntity, KnowledgeTag } from '@/api/types'
import { debounce } from '@/utils/persist'
import EmptyState from '@/components/common/EmptyState.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import CollapsiblePanel from '@/components/common/CollapsiblePanel.vue'
import CollapseToggle from '@/components/common/CollapseToggle.vue'
import RailButton from '@/components/common/RailButton.vue'
import { useHotkeys } from '@/composables/useHotkeys'
import { handleServerError } from '@/utils/notify'

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

// `[` 收起/展开侧栏。useHotkeys 已排除输入框聚焦场景，不会干扰搜索框输入
useHotkeys([
  { key: '[', description: '收起/展开分类栏', handler: () => sidebarRef.value?.toggle() }
])

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
  query: { draft: crypto.randomUUID() },
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
    ElMessage.success('标签已创建')
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
    ElMessage.success('标签已重命名')
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
      ElMessage.warning('未找到可合并的目标标签')
      return
    }
    await mergeKnowledgeTag(tag.id, target.id)
    await Promise.all([loadManagedTags(), store.loadHotTags()])
    ElMessage.success('标签已合并')
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
        ElMessage.warning('未找到可替换的标签')
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
    ElMessage.success('标签已删除')
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
    sort: activeSort.value
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
  if (activeSort.value !== 'UPDATED_DESC') query.sort = activeSort.value
  if (viewMode.value !== 'list') query.view = viewMode.value
  if (store.currentPage > 1) query.page = String(store.currentPage)
  router.replace({ query })
}, 200)

watch([appliedQuery, activeCategory, activeTag, activeStatus, activeSort, viewMode, () => store.currentPage], syncUrl)

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
  searchQuery.value = ''
  appliedQuery.value = ''
  if (activeSort.value === 'RELEVANCE') activeSort.value = 'UPDATED_DESC'
  reload()
  ElMessage.success('已清除筛选')
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
  // 从 URL 恢复页码
  const page = Number(route.query.page) || 1
  // loadLibraryTotal 已删除：loadList 成功后已写 libraryTotal，无需冗余请求
  store.loadList({
    page,
    keyword: appliedQuery.value || undefined,
    category: activeCategory.value || undefined,
    tag: activeTag.value || undefined,
    status: activeStatus.value || undefined,
    sort: activeSort.value
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
      <button class="btn-new" @click="openCreate">
        <Plus :size="16" />
        新建文档
      </button>
    </div>

    <!-- ===== Search Bar ===== -->
    <div class="search-bar">
      <div class="search-container">
        <Search class="search-icon" :size="18" />
        <input
          v-model="searchQuery"
          @input="onSearchInput"
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

        <!-- Loading（首屏） -->
        <div v-if="store.loading && store.list.length === 0" class="load-state">
          <div v-for="n in 5" :key="n" class="skeleton-row"></div>
        </div>

        <!-- 加载失败 -->
        <ApiErrorState
          v-else-if="store.loadError"
          :error="store.loadError"
          @retry="reload"
        />

        <!-- Empty State -->
        <EmptyState
          v-else-if="store.list.length === 0"
          title="没有匹配的文档"
          description="换个筛选条件，或创建第一篇文档"
          :action-label="'新增文档'"
          @action="openCreate"
        />

        <!-- Articles List -->
        <template v-else>
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
        </template>

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
</template>

<style scoped lang="scss">
.knowledge-base {
  min-height: 100vh;
  background: var(--color-bg);
  padding-bottom: 32px;
}

/* ===== Page Header ===== */
.page-header {
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border-light);
  padding: 24px 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  font-family: var(--font-display);
  font-size: var(--text-2xl);
  font-weight: var(--weight-bold);
  color: var(--color-text-primary);
  margin: 0 0 4px 0;
  letter-spacing: -0.02em;
}

.page-subtitle {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
  margin: 0;
}

.btn-new {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 20px;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: var(--color-primary-light);
  }
}

/* ===== Search Bar ===== */
.search-bar {
  background: var(--color-bg);
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
  color: var(--color-text-tertiary);
  pointer-events: none;
}

.search-input {
  width: 100%;
  height: 44px;
  padding: 0 16px 0 42px;
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-primary-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  outline: none;
  transition: border-color 0.15s ease;
  box-sizing: border-box;

  &:focus {
    border-color: var(--color-primary-light);
  }

  &::placeholder {
    color: var(--color-text-tertiary);
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
  background: var(--color-border-light, #E5E7EB);
  flex-shrink: 0;
}

/* ===== Sidebar ===== */
.sidebar {
  width: 100%;
  padding-right: 24px;
  border-right: 1px solid var(--color-border-light);

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
  color: var(--color-text-tertiary);
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
  color: var(--color-text-tertiary);
  cursor: pointer;
}

.tag-manage-trigger:hover { color: var(--color-primary); background: var(--color-primary-lighter); }

.sidebar-empty {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  margin: 0;
  padding-left: 12px;
}
.sidebar-retry { border: 0; background: transparent; cursor: pointer; text-align: left; }
.sidebar-retry:hover { color: var(--color-primary); }

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
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  color: var(--color-text-secondary);
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-surface-hover);
  }

  &.active {
    background: var(--color-primary-lighter);
    color: var(--color-primary);
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
  color: var(--color-text-tertiary);
  background: var(--color-bg);
  padding: 1px 8px;
  border-radius: var(--radius-full);

  &.active {
    background: var(--color-primary);
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
  color: var(--color-text-secondary);
  background: var(--color-bg);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-full);
  cursor: pointer;
  border: 1px solid var(--color-border-light);
  font-family: inherit;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary-light);
    color: var(--color-primary);
  }

  &.active {
    background: var(--color-primary);
    border-color: var(--color-primary);
    color: #fff;
  }
}

.tag-count {
  font-size: 10px;
  color: var(--color-text-tertiary);
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
  color: var(--color-text-secondary);
  font-weight: var(--weight-medium);
}

.sort-select {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 4px 28px 4px 8px;
  font-size: var(--text-sm);
  font-family: var(--font-body);
  color: var(--color-text-primary);
  background: var(--color-surface);
  outline: none;
  cursor: pointer;
}

.btn-clear {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
  }
}

.sort-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.count-text {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}

.view-toggle {
  display: flex;
  align-items: center;
  gap: 2px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.view-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px 8px;
  border: none;
  background: var(--color-surface);
  color: var(--color-text-tertiary);
  cursor: pointer;

  &.active {
    background: var(--color-primary-lighter);
    color: var(--color-primary);
  }

  &:not(:last-child) {
    border-right: 1px solid var(--color-border);
  }
}

/* ===== Load / Error ===== */
.load-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 60px 24px;
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--text-sm);
}
.skeleton-row {
  width: 100%;
  height: 72px;
  background: linear-gradient(90deg, #f0f0f0 25%, #e0e0e0 50%, #f0f0f0 75%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.5s infinite;
  border-radius: 8px;
}
@keyframes skeleton-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.load-error {
  color: var(--color-danger, #f56c6c);
}

/* ===== Articles List ===== */
.articles-list {
  border-radius: var(--radius-md);
  background: var(--color-surface);
  overflow: hidden;
  margin-bottom: 24px;
}

.article-row {
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border-light);
  cursor: pointer;
  transition: background 0.12s ease;

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background: var(--color-surface-hover);
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
  color: var(--color-text-link);
  text-decoration: none;
  line-height: var(--leading-tight);

  &:hover {
    color: var(--color-primary);
  }
}

.category-pill {
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: var(--weight-medium);
  background: var(--color-primary-lighter);
  color: var(--color-primary);
  white-space: nowrap;
}

.lifecycle-pill {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  color: #92400e;
  background: #fef3c7;
  font-size: 11px;
  font-weight: var(--weight-medium);
}

.article-excerpt {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
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
  background: var(--color-bg-sunken);
  color: var(--color-text-secondary);
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
  color: var(--color-text-tertiary);

  &.author {
    gap: 6px;
    color: var(--color-text-tertiary);
  }
}

.author-avatar {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #3b82f6;
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
  color: var(--color-primary);
  background: var(--color-primary-lighter);
  padding: 1px 8px;
  border-radius: var(--radius-full);

  &.index-failed {
    color: var(--color-danger, #f56c6c);
    background: var(--state-error-bg);
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
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: 16px;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary-light);
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
  color: var(--color-text-link);
  line-height: var(--leading-tight);
  margin-bottom: 6px;
  text-decoration: none;

  &:hover {
    color: var(--color-primary);
  }
}

.card-excerpt {
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
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
  border: 1px solid var(--color-border);
  border-radius: 5px;
  background: var(--color-bg-elevated);
  color: var(--color-text-primary);
  font: inherit;
}

.tag-manager-create .btn-new { height: 34px; white-space: nowrap; }
.tag-manager-state { padding: 28px 0; color: var(--color-text-tertiary); text-align: center; font-size: 13px; }
.tag-manager-list { border-top: 1px solid var(--color-border-light); }
.tag-manager-row { display: flex; align-items: center; gap: 10px; min-height: 44px; border-bottom: 1px solid var(--color-border-light); }
.tag-manager-count { color: var(--color-text-tertiary); font-size: 12px; }
.tag-manager-actions { display: flex; align-items: center; gap: 2px; margin-left: auto; }
.tag-manager-actions button { width: 28px; height: 28px; display: inline-flex; align-items: center; justify-content: center; border: 0; border-radius: 4px; background: transparent; color: var(--color-text-tertiary); cursor: pointer; }
.tag-manager-actions button:hover { color: var(--color-primary); background: var(--color-primary-lighter); }

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
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-tertiary);
  cursor: pointer;
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  transition: all 0.15s ease;

  &.num {
    color: var(--color-text-primary);
  }

  &.active {
    border-color: var(--color-primary);
    background: var(--color-primary);
    color: var(--color-text-inverse);
  }

  &:hover:not(:disabled):not(.active) {
    border-color: var(--color-primary);
    color: var(--color-primary);
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
  color: var(--color-text-tertiary);
}
</style>
