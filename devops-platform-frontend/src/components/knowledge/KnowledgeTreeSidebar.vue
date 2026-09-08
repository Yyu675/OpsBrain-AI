<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  BookOpen,
  ChevronDown,
  ChevronRight,
  FilePlus2,
  FileText,
  Folder,
  FolderOpen,
  Menu,
  MoreHorizontal,
  Plus,
  Search,
  Trash2,
  X,
} from 'lucide-vue-next'
import {
  createKnowledgeCategory,
  deleteKnowledgeCategory,
  fetchKnowledgeCategoryTree,
  moveKnowledgeDocument,
  updateKnowledgeCategory,
  VersionConflictError,
} from '@/api/knowledge'
import type {
  KnowledgeCategoryTreeNode,
  KnowledgeTreeDocument,
} from '@/api/types'
import { notify, handleServerError } from '@/utils/notify'

const props = withDefaults(defineProps<{
  currentDocId?: number | null
  mode?: 'detail' | 'editor'
}>(), {
  currentDocId: null,
  mode: 'detail',
})

const emit = defineEmits<{
  changed: []
}>()

interface CategoryRow {
  category: KnowledgeCategoryTreeNode
  level: number
}

const router = useRouter()
const categories = ref<KnowledgeCategoryTreeNode[]>([])
const uncategorized = ref<KnowledgeTreeDocument[]>([])
const expanded = ref(new Set<number>())
const search = ref('')
const loading = ref(false)
const mobileOpen = ref(false)
const recycleOpen = ref(false)

const activeDocs = (docs: KnowledgeTreeDocument[]) =>
  docs.filter(doc => doc.status !== 'DEPRECATED' && doc.status !== 'ARCHIVED')

const recycleDocs = computed(() => {
  const docs = categories.value.flatMap(category => category.documents).concat(uncategorized.value)
  return docs.filter(doc => doc.status === 'DEPRECATED' || doc.status === 'ARCHIVED')
})

const visibleRows = computed<CategoryRow[]>(() => {
  const byParent = new Map<number | null, KnowledgeCategoryTreeNode[]>()
  for (const category of categories.value) {
    const siblings = byParent.get(category.parentId) ?? []
    siblings.push(category)
    byParent.set(category.parentId, siblings)
  }
  for (const siblings of byParent.values()) {
    siblings.sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, 'zh-CN'))
  }

  const rows: CategoryRow[] = []
  const visit = (parentId: number | null, level: number) => {
    for (const category of byParent.get(parentId) ?? []) {
      rows.push({ category, level })
      if (expanded.value.has(category.id) || search.value.trim()) visit(category.id, level + 1)
    }
  }
  visit(null, 0)
  return rows
})

const normalizedSearch = computed(() => search.value.trim().toLowerCase())

const matchesDoc = (doc: KnowledgeTreeDocument) =>
  !normalizedSearch.value || doc.title.toLowerCase().includes(normalizedSearch.value)

const isDescendantOf = (category: KnowledgeCategoryTreeNode, ancestorId: number) => {
  const seen = new Set<number>([category.id])
  let parentId = category.parentId
  while (parentId != null && !seen.has(parentId)) {
    if (parentId === ancestorId) return true
    seen.add(parentId)
    parentId = categories.value.find(item => item.id === parentId)?.parentId ?? null
  }
  return false
}

const categoryVisible = (category: KnowledgeCategoryTreeNode) => {
  if (!normalizedSearch.value) return true
  return categories.value.some(candidate =>
    (candidate.id === category.id || isDescendantOf(candidate, category.id))
    && (candidate.name.toLowerCase().includes(normalizedSearch.value)
      || activeDocs(candidate.documents).some(matchesDoc))
  )
}

const docsForCategory = (category: KnowledgeCategoryTreeNode) =>
  activeDocs(category.documents).filter(matchesDoc)

async function loadTree() {
  loading.value = true
  try {
    const tree = await fetchKnowledgeCategoryTree()
    categories.value = tree.categories ?? []
    uncategorized.value = tree.uncategorized ?? []
    for (const category of categories.value) {
      if (category.documents.some(doc => doc.id === props.currentDocId)) expanded.value.add(category.id)
    }
    expanded.value = new Set(expanded.value)
  } catch (error) {
    handleServerError(error, { action: '加载目录树' })
  } finally {
    loading.value = false
  }
}

function toggleCategory(id: number) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

function openDocument(doc: KnowledgeTreeDocument) {
  mobileOpen.value = false
  const target = props.mode === 'editor' && doc.status !== 'DEPRECATED' && doc.status !== 'ARCHIVED'
    ? `/knowledge/editor/${doc.id}`
    : `/knowledge/${doc.id}`
  router.push(target)
}

function createDocument(category?: KnowledgeCategoryTreeNode) {
  mobileOpen.value = false
  router.push({
    path: '/knowledge/editor/new',
    query: {
      draft: crypto.randomUUID(),
      ...(category ? { category: category.name } : {}),
    },
  })
}

async function addCategory(parent?: KnowledgeCategoryTreeNode) {
  try {
    const { value } = await ElMessageBox.prompt(
      parent ? `在“${parent.name}”下创建子分类` : '创建知识库根分类',
      '新建分类',
      { inputPlaceholder: '分类名称', inputPattern: /\S+/, inputErrorMessage: '请输入分类名称' }
    )
    await createKnowledgeCategory({ name: value.trim(), parentId: parent?.id ?? null })
    if (parent) expanded.value = new Set([...expanded.value, parent.id])
    await loadTree()
    emit('changed')
    notify.success('分类已创建')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    handleServerError(error, { action: '创建分类' })
  }
}

async function renameCategory(category: KnowledgeCategoryTreeNode) {
  try {
    const { value } = await ElMessageBox.prompt('修改分类名称后，所属文档会同步更新', '重命名分类', {
      inputValue: category.name,
      inputPattern: /\S+/,
      inputErrorMessage: '请输入分类名称',
    })
    await updateKnowledgeCategory(category.id, {
      name: value.trim(),
      parentId: category.parentId,
      sortOrder: category.sortOrder,
    })
    await loadTree()
    emit('changed')
    notify.success('分类已重命名')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    handleServerError(error, { action: '重命名分类' })
  }
}

async function removeCategory(category: KnowledgeCategoryTreeNode) {
  try {
    await ElMessageBox.confirm(
      `确定删除分类“${category.name}”吗？仅空分类可以删除。`,
      '删除分类',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await deleteKnowledgeCategory(category.id)
    await loadTree()
    emit('changed')
    notify.success('分类已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    handleServerError(error, { action: '删除分类' })
  }
}

async function handleCategoryCommand(command: string, category: KnowledgeCategoryTreeNode) {
  if (command === 'child') await addCategory(category)
  if (command === 'rename') await renameCategory(category)
  if (command === 'delete') await removeCategory(category)
}

async function handleMoveCommand(command: string, doc: KnowledgeTreeDocument) {
  try {
    const categoryId = command === 'none' ? null : Number(command)
    await moveKnowledgeDocument(doc.id, categoryId, doc.version)
    await loadTree()
    emit('changed')
    notify.success('文档已移动')
  } catch (error) {
    if (error instanceof VersionConflictError) {
      await loadTree()
      emit('changed')
      notify.warning('文档已被其他人修改，目录已刷新，请确认后重试移动')
      return
    }
    handleServerError(error, { action: '移动文档' })
  }
}

onMounted(loadTree)

defineExpose({ refresh: loadTree })
</script>

<template>
  <button class="kts-mobile-trigger" type="button" title="打开知识库目录" @click="mobileOpen = true">
    <Menu :size="18" />
  </button>
  <div v-if="mobileOpen" class="kts-mobile-mask" @click="mobileOpen = false" />

  <aside class="kts-sidebar" :class="{ 'mobile-open': mobileOpen }">
    <div class="kts-head">
      <div class="kts-brand">
        <BookOpen :size="17" />
        <span>运维知识库</span>
        <!--
          标题行操作位：供调用方放折叠按钮。
          折叠控制应与它所控制的内容同处一行，而本组件的标题行结构属自身职责，
          故以插槽形式让出位置，而非让外层在 DOM 外另放一个游离控件。
        -->
        <span class="kts-brand-actions">
          <slot name="title-actions" />
        </span>
        <button class="kts-mobile-close" type="button" title="关闭目录" @click="mobileOpen = false">
          <X :size="17" />
        </button>
      </div>
      <div class="kts-search">
        <Search :size="15" />
        <input v-model="search" type="search" placeholder="搜索目录和文档" />
        <button v-if="search" type="button" title="清除搜索" @click="search = ''"><X :size="14" /></button>
      </div>
      <button class="kts-new-doc" type="button" @click="createDocument()">
        <FilePlus2 :size="15" />
        <span>新建文档</span>
      </button>
    </div>

    <div class="kts-body">
      <div class="kts-section-title">
        <span>知识库目录</span>
        <button type="button" title="新建根分类" @click="addCategory()"><Plus :size="15" /></button>
      </div>

      <div v-if="loading" class="kts-state">正在加载目录...</div>
      <template v-else>
        <template v-for="row in visibleRows" :key="row.category.id">
          <div v-if="categoryVisible(row.category)" class="kts-category-wrap">
            <div
              class="kts-category"
              role="button"
              tabindex="0"
              :style="{ paddingLeft: `${10 + row.level * 14}px` }"
              @click="toggleCategory(row.category.id)"
              @keydown.enter="toggleCategory(row.category.id)"
              @keydown.space.prevent="toggleCategory(row.category.id)"
            >
              <component :is="expanded.has(row.category.id) ? ChevronDown : ChevronRight" :size="14" />
              <component :is="expanded.has(row.category.id) ? FolderOpen : Folder" :size="16" />
              <span class="kts-label">{{ row.category.name }}</span>
              <span class="kts-count">{{ row.category.docCount }}</span>
              <el-dropdown trigger="click" @command="handleCategoryCommand($event, row.category)">
                <button class="kts-more" type="button" title="分类操作" @click.stop>
                  <MoreHorizontal :size="15" />
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="child">新建子分类</el-dropdown-item>
                    <el-dropdown-item command="rename">重命名</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除空分类</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>

            <div v-if="expanded.has(row.category.id) || search" class="kts-docs">
              <div
                v-for="item in docsForCategory(row.category)"
                :key="item.id"
                class="kts-doc"
                role="button"
                tabindex="0"
                :class="{ active: item.id === currentDocId }"
                :style="{ paddingLeft: `${40 + row.level * 14}px` }"
                @click="openDocument(item)"
                @keydown.enter="openDocument(item)"
                @keydown.space.prevent="openDocument(item)"
              >
                <FileText :size="14" />
                <span class="kts-label">{{ item.title }}</span>
                <el-dropdown trigger="click" @command="handleMoveCommand($event, item)">
                  <button class="kts-more" type="button" title="移动文档" @click.stop>
                    <MoreHorizontal :size="14" />
                  </button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="none">移至未分类</el-dropdown-item>
                      <el-dropdown-item
                        v-for="target in categories.filter(cat => cat.id !== row.category.id)"
                        :key="target.id"
                        :command="String(target.id)"
                      >移至 {{ target.name }}</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
              <button class="kts-add-in" type="button" :style="{ paddingLeft: `${40 + row.level * 14}px` }" @click="createDocument(row.category)">
                <Plus :size="13" /><span>新建文档</span>
              </button>
            </div>
          </div>
        </template>

        <div v-if="activeDocs(uncategorized).filter(matchesDoc).length" class="kts-uncategorized">
          <div class="kts-category" @click="recycleOpen = false">
            <ChevronDown :size="14" /><FolderOpen :size="16" />
            <span class="kts-label">未分类</span>
            <span class="kts-count">{{ activeDocs(uncategorized).length }}</span>
          </div>
          <div
            v-for="item in activeDocs(uncategorized).filter(matchesDoc)"
            :key="item.id"
            class="kts-doc"
            role="button"
            tabindex="0"
            :class="{ active: item.id === currentDocId }"
            @click="openDocument(item)"
            @keydown.enter="openDocument(item)"
            @keydown.space.prevent="openDocument(item)"
          >
            <FileText :size="14" /><span class="kts-label">{{ item.title }}</span>
            <el-dropdown trigger="click" @command="handleMoveCommand($event, item)">
              <button class="kts-more" type="button" title="移动文档" @click.stop><MoreHorizontal :size="14" /></button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="target in categories" :key="target.id" :command="String(target.id)">
                    移至 {{ target.name }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>

        <div v-if="search && !visibleRows.some(row => categoryVisible(row.category)) && !activeDocs(uncategorized).some(matchesDoc)" class="kts-state">
          未找到匹配的目录或文档
        </div>
      </template>
    </div>

    <div class="kts-footer">
      <button type="button" :class="{ active: recycleOpen }" @click="recycleOpen = !recycleOpen">
        <Trash2 :size="15" /><span>回收站</span><span class="kts-count">{{ recycleDocs.length }}</span>
      </button>
      <div v-if="recycleOpen" class="kts-recycle-list">
        <button v-for="item in recycleDocs" :key="item.id" type="button" @click="openDocument(item)">
          <FileText :size="13" /><span>{{ item.title }}</span>
        </button>
        <span v-if="!recycleDocs.length">回收站为空</span>
      </div>
    </div>
  </aside>
</template>

<style scoped lang="scss">
.kts-sidebar {
  width: 260px;
  min-width: 260px;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--color-bg-elevated);
  border-right: 1px solid var(--color-border-light);
  color: var(--color-text-secondary);
}

.kts-sidebar button { border: 0; outline: 0; font-family: inherit; }

.kts-head { padding: 14px 12px 12px; border-bottom: 1px solid var(--color-border-light); }
.kts-brand { height: 28px; display: flex; align-items: center; gap: 7px; margin-bottom: 10px; font-size: 14px; font-weight: 600; color: var(--color-text-primary); }
/* 标题行操作位靠右——margin-left:auto 把折叠按钮推到行尾，与标题同一水平线 */
.kts-brand-actions { margin-left: auto; display: inline-flex; align-items: center; }
.kts-mobile-close { display: none; margin-left: auto; color: var(--color-text-tertiary); }
.kts-search { height: 32px; display: flex; align-items: center; gap: 7px; padding: 0 9px; border: 1px solid var(--color-border-light); border-radius: 6px; background: var(--color-bg); color: var(--color-text-tertiary); }
.kts-search:focus-within { border-color: var(--color-primary-light); background: var(--color-bg-elevated); }
.kts-search input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--color-text-primary); font-size: 13px; }
.kts-search button { display: inline-flex; color: var(--color-text-tertiary); }
.kts-new-doc { width: 100%; height: 32px; margin-top: 9px; display: flex; align-items: center; justify-content: center; gap: 6px; border-radius: 5px; background: var(--color-primary); color: #fff; font-size: 13px; }
.kts-new-doc:hover { background: var(--color-primary-light); }

.kts-body { flex: 1; min-height: 0; overflow-y: auto; padding: 10px 8px; }
.kts-section-title { height: 28px; padding: 0 7px 0 9px; display: flex; align-items: center; justify-content: space-between; font-size: 12px; font-weight: 600; color: var(--color-text-tertiary); }
.kts-section-title button { width: 24px; height: 24px; display: inline-flex; align-items: center; justify-content: center; border-radius: 4px; }
.kts-section-title button:hover { background: var(--color-bg-sunken); color: var(--color-primary); }
.kts-category, .kts-doc { position: relative; min-height: 30px; display: flex; align-items: center; gap: 6px; padding-right: 5px; border-radius: 5px; cursor: pointer; font-size: 13px; }
.kts-category:hover, .kts-doc:hover { background: var(--color-surface-hover); color: var(--color-text-primary); }
.kts-category { padding-left: 10px; }
.kts-doc { padding-left: 40px; color: var(--color-text-secondary); }
.kts-doc.active { background: var(--color-primary-lighter); color: var(--color-primary); font-weight: 500; }
.kts-label { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kts-count { flex-shrink: 0; color: var(--color-text-tertiary); font-size: 11px; font-variant-numeric: tabular-nums; }
.kts-more { width: 24px; height: 24px; display: none; align-items: center; justify-content: center; border-radius: 4px; color: var(--color-text-tertiary); }
.kts-category:hover .kts-more, .kts-doc:hover .kts-more { display: inline-flex; }
.kts-add-in { width: 100%; min-height: 28px; display: flex; align-items: center; gap: 6px; border: 0; background: transparent; color: var(--color-text-tertiary); font-size: 12px; text-align: left; }
.kts-add-in:hover { color: var(--color-primary); }
.kts-state { padding: 24px 10px; text-align: center; font-size: 12px; color: var(--color-text-tertiary); }

.kts-footer { position: relative; flex-shrink: 0; padding: 8px; border-top: 1px solid var(--color-border-light); }
.kts-footer > button { width: 100%; height: 32px; display: flex; align-items: center; gap: 7px; padding: 0 10px; border-radius: 5px; color: var(--color-text-secondary); font-size: 13px; }
.kts-footer > button:hover, .kts-footer > button.active { background: var(--color-bg-sunken); color: var(--color-text-primary); }
.kts-footer > button .kts-count { margin-left: auto; }
.kts-recycle-list { position: absolute; left: 8px; right: 8px; bottom: 44px; max-height: 220px; overflow-y: auto; padding: 6px; border: 1px solid var(--color-border-light); background: var(--color-bg-elevated); box-shadow: var(--shadow-md); border-radius: 6px; }
.kts-recycle-list button { width: 100%; min-height: 30px; display: flex; align-items: center; gap: 6px; padding: 5px 7px; border-radius: 4px; font-size: 12px; text-align: left; }
.kts-recycle-list button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kts-recycle-list button:hover { background: var(--color-surface-hover); }
.kts-recycle-list > span { display: block; padding: 12px; color: var(--color-text-tertiary); font-size: 12px; text-align: center; }

.kts-mobile-trigger, .kts-mobile-mask { display: none; }

@media (max-width: 900px) {
  .kts-mobile-trigger { position: fixed; z-index: 35; left: 8px; top: 64px; width: 34px; height: 34px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--color-border-light); border-radius: 5px; background: var(--color-bg-elevated); box-shadow: var(--shadow-sm); color: var(--color-text-secondary); }
  .kts-mobile-mask { position: fixed; z-index: 70; inset: 56px 0 0; display: block; background: rgba(17, 24, 39, 0.32); }
  .kts-sidebar { position: fixed; z-index: 71; left: 0; top: 56px; bottom: 0; height: auto; transform: translateX(-100%); transition: transform 0.2s ease; box-shadow: var(--shadow-lg); }
  .kts-sidebar.mobile-open { transform: translateX(0); }
  .kts-mobile-close { display: inline-flex; }
}
</style>
