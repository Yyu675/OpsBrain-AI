import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// 只保留 clearPersisted：用于清理旧版本写入的本地 mock 文章缓存。
// 文档数据权威来源是后端（筛选/分页/生命周期全部由后端执行），
// 不再持久化到 localStorage（原因见 CLAUDE.md 6.18）。
import { clearPersisted } from '@/utils/persist'
import { errorMessage } from '@/utils/errors'
import { useResourceState } from '@/composables/useResourceState'
import {
  fetchKnowledgeDocs,
  fetchKnowledgeDocDetail,
  fetchKnowledgeCategories,
  fetchKnowledgeDocHotTags,
  fetchKnowledgeDocVersions,
  compareKnowledgeDocVersions,
  createKnowledgeDoc,
  updateKnowledgeDoc,
  publishKnowledgeDoc,
  deprecateKnowledgeDoc,
  restoreKnowledgeDoc,
  purgeKnowledgeDoc,
  NotFoundDocError,
} from '@/api/knowledge'
import type {
  KnowledgeDocListItem,
  KnowledgeDocDetail,
  KnowledgeDocVersion,
  KnowledgeCategoryEntity,
  KnowledgeHotTag,
  KnowledgeDocCreateRequest,
  KnowledgeDocUpdateRequest,
  KnowledgeVersionDiff,
} from '@/api/types'
import { ElMessage } from 'element-plus'

// 仅用于清理旧版本遗留的 mock 缓存，不再写入
const PERSIST_KEY = 'knowledge'

export type DocStatus = KnowledgeDocListItem['status']

/** 列表查询参数（服务端筛选 + 分页） */
export interface KnowledgeQueryParams {
  page?: number
  size?: number
  keyword?: string
  category?: string
  tag?: string
  status?: string
  sort?: string
}

export const useKnowledgeStore = defineStore('knowledge', () => {
  // 不再从 localStorage 恢复 6 篇 mock 文章。删除假分类树 / 假 views / likes / 作者色。
  // 清理历史遗留数据，避免旧版本写入的缓存继续生效。
  clearPersisted(PERSIST_KEY)

  // ==================== 列表 ====================

  const list = ref<KnowledgeDocListItem[]>([])
  const loading = ref(false)
  const loadError = ref<string | null>(null)

  /** 后端返回的匹配总数（按当前筛选条件），用于分页 */
  const total = ref(0)
  const totalPages = ref(1)
  const currentPage = ref(1)
  const pageSize = ref(10)
  /** 全库总数，不随当前筛选变化，用于“全部文档”计数。 */
  const libraryTotal = ref(0)

  /** 上次查询参数，供 refreshList 沿用，避免刷新时丢掉筛选条件 */
  const lastQuery = ref<KnowledgeQueryParams | null>(null)
  let listRequestSequence = 0

  /**
   * 从后端加载文档列表
   * <p>筛选（关键词/分类/标签/状态）与分页均由后端执行，禁止前端本地过滤当前页。</p>
   */
  const loadList = async (params?: KnowledgeQueryParams) => {
    const requestSequence = ++listRequestSequence
    loading.value = true
    loadError.value = null
    lastQuery.value = params ? { ...params } : null

    try {
      const result = await fetchKnowledgeDocs({
        page: params?.page || 1,
        size: params?.size || 10,
        keyword: params?.keyword,
        category: params?.category,
        tag: params?.tag,
        status: params?.status,
        sort: params?.sort,
      })
      // 搜索防抖期间可能连续发出请求，只允许最后一次请求覆盖页面。
      if (requestSequence !== listRequestSequence) return result
      list.value = result.content
      total.value = result.totalElements
      totalPages.value = result.totalPages
      currentPage.value = result.currentPage
      pageSize.value = result.pageSize
      if (!params?.keyword && !params?.category && !params?.tag && !params?.status) {
        libraryTotal.value = result.totalElements
      }
      return result
    } catch (e: unknown) {
      if (requestSequence !== listRequestSequence) return
      loadError.value = errorMessage(e, '加载文档列表失败')
      ElMessage.error(loadError.value)
      throw e
    } finally {
      if (requestSequence === listRequestSequence) loading.value = false
    }
  }

  /** 刷新列表（沿用上次筛选条件） */
  const refreshList = async () => {
    await loadList(lastQuery.value ?? undefined)
  }

  /** 翻页（沿用当前筛选） */
  const goToPage = async (page: number) => {
    if (page < 1 || page > totalPages.value) return
    await loadList({ ...(lastQuery.value ?? {}), page })
  }

  const getById = (id: number) => list.value.find(d => d.id === id)

  const loadLibraryTotal = async () => {
    try {
      const result = await fetchKnowledgeDocs({ page: 1, size: 1 })
      libraryTotal.value = result.totalElements
    } catch (e) {
      console.warn('[KnowledgeStore] 加载知识库总数失败', e)
    }
  }

  // ==================== 详情（三态） ====================

  /**
   * 三态由 useResourceState 统一管理（6.18 契约）。
   *
   * 此前 store 内手写了一份与 composable 完全同构的状态机（含请求序号防竞态），
   * 与 AlertDetail / TicketDetail 各自的手写实现并存三份——同一契约三处实现
   * 必然漂移（实际已漂移：两个详情页的 v-if 条件不一致）。现统一到一处。
   */
  const detailResource = useResourceState<KnowledgeDocDetail>()
  const detail = detailResource.data
  const detailStatus = detailResource.status
  const detailErrorObj = detailResource.error
  /** 错误文案（兼容既有消费方；原始对象走 detailErrorObj 给 ApiErrorState） */
  const detailError = computed(() =>
    detailResource.isError.value ? errorMessage(detailResource.error.value, '加载文档详情失败') : null
  )

  /**
   * 加载文档详情
   * <p>三态严格区分：loading（等待）/ notFound（确实不存在）/ error（网络或服务异常，可重试）。</p>
   * <p>`fetchKnowledgeDocDetail` 对 40004 抛 NotFoundDocError，此处转为 null——
   * useResourceState 以「resolve(null) = 确实不存在、reject = 加载失败」为约定，
   * 转换后两种来源的 notFound 判定才一致。</p>
   */
  const loadDetail = async (id: number) => {
    return detailResource.load(async () => {
      try {
        return await fetchKnowledgeDocDetail(id)
      } catch (e) {
        if ((e as NotFoundDocError)?.isNotFound) return null
        throw e
      }
    })
  }

  // ==================== 侧栏聚合（全库跨页） ====================

  const categories = ref<KnowledgeCategoryEntity[]>([])
  const hotTags = ref<KnowledgeHotTag[]>([])
  const categoriesLoadError = ref<string | null>(null)
  const hotTagsLoadError = ref<string | null>(null)

  /** 扁平分类（后端全库聚合，含文档数），失败静默降级为空 */
  const loadCategories = async () => {
    categoriesLoadError.value = null
    try {
      categories.value = await fetchKnowledgeCategories()
    } catch (e) {
      console.warn('[KnowledgeStore] 加载分类失败，降级为空', e)
      categories.value = []
      categoriesLoadError.value = (e as Error).message || '加载分类失败'
    }
  }

  /** 热门标签（后端仅 PUBLISHED 计数），失败静默降级为空 */
  const loadHotTags = async () => {
    hotTagsLoadError.value = null
    try {
      hotTags.value = await fetchKnowledgeDocHotTags(20)
    } catch (e) {
      console.warn('[KnowledgeStore] 加载热门标签失败，降级为空', e)
      hotTags.value = []
      hotTagsLoadError.value = (e as Error).message || '加载热门标签失败'
    }
  }

  // ==================== 写操作（落库） ====================

  /**
   * 创建文档（publish=true 立即发布并向量化；false 存草稿）
   * @throws DuplicateContentError 内容重复（可跳转重复文档）
   */
  const createDoc = async (req: KnowledgeDocCreateRequest) => {
    const saved = await createKnowledgeDoc(req)
    await refreshList()
    return saved
  }

  /**
   * 更新文档（带乐观锁）
   * @param version 当前持有版本号，防止覆盖他人修改
   * @throws VersionConflictError 版本冲突（须提示刷新，禁止自动覆盖）
   * @throws DuplicateContentError 内容重复
   */
  const updateDoc = async (id: number, req: Omit<KnowledgeDocUpdateRequest, 'version'>, version: number) => {
    const saved = await updateKnowledgeDoc(id, { ...req, version })
    // 只在当前详情就是这篇文档时原地合并，避免覆盖用户已切走的文档
    if (detail.value?.id === id) {
      detail.value = {
        ...detail.value,
        version: saved.version,
        indexStatus: (saved.indexStatus ?? detail.value.indexStatus) as KnowledgeDocDetail['indexStatus'],
        status: saved.status ?? detail.value.status,
        retrievable: saved.retrievable,
        title: req.title ?? detail.value.title,
        category: req.category ?? detail.value.category,
        summary: req.summary ?? detail.value.summary,
        tags: req.tags ?? detail.value.tags,
        content: req.content ?? detail.value.content,
      }
    }
    await refreshList()
    return saved
  }

  /**
   * 发布文档（草稿 → 已发布，触发向量化）
   */
  const publishDoc = async (id: number) => {
    const result = await publishKnowledgeDoc(id)
    if (detail.value?.id === id) {
      detail.value.status = 'PUBLISHED'
      detail.value.indexStatus = (result.indexStatus as KnowledgeDocDetail['indexStatus']) ?? detail.value.indexStatus
      detail.value.retrievable = result.retrievable
      if (result.indexError) detail.value.indexError = result.indexError
    }
    const it = getById(id)
    if (it) {
      it.status = 'PUBLISHED'
      it.indexStatus = (result.indexStatus as KnowledgeDocDetail['indexStatus']) ?? it.indexStatus
    }
    await refreshList()
    return result
  }

  /**
   * 废弃文档（默认「删除」语义：留正文删向量，退出检索）
   * <p>返回快照供撤销。撤销 = 调 restore 重新发布（deprecate 不递增 version，
   * 用废弃前的 version 即可恢复当前内容）。</p>
   */
  const deprecateDoc = async (id: number): Promise<{ version: number } | null> => {
    let version: number | null = detail.value?.id === id ? detail.value.version : null
    if (version == null) {
      try {
        const d = await fetchKnowledgeDocDetail(id)
        version = d.version
      } catch {
        version = null
      }
    }
    await deprecateKnowledgeDoc(id)
    if (detail.value?.id === id) {
      detail.value.status = 'DEPRECATED'
      detail.value.indexStatus = 'SKIPPED'
      detail.value.retrievable = false
    }
    const it = getById(id)
    if (it) {
      it.status = 'DEPRECATED'
      it.indexStatus = 'SKIPPED'
    }
    await refreshList()
    // 拿不到 version 时返回 null，禁止 Undo（而非按 v1 恢复到错误版本）
    return version != null ? { version } : null
  }

  /**
   * 撤销废弃（= 恢复当前版本重新发布）
   */
  const undoDeprecate = async (id: number, version: number) => {
    const result = await restoreKnowledgeDoc(id, version)
    await Promise.all([
      refreshList(),
      detail.value?.id === id ? loadDetail(id) : Promise.resolve(),
    ])
    ElMessage.success('已恢复文档并重新发布')
    return result
  }

  /**
   * 回滚到历史版本
   */
  const restoreVersion = async (id: number, version: number) => {
    const result = await restoreKnowledgeDoc(id, version)
    await Promise.all([refreshList(), loadDetail(id)])
    return result
  }

  /**
   * 物理删除（仅合规场景，须提供理由）
   * <p>删除后详情页由调用方负责跳转（文档已不存在）。</p>
   */
  const purgeDoc = async (id: number, complianceReason: string) => {
    await purgeKnowledgeDoc(id, complianceReason)
    await refreshList()
  }

  // ==================== 版本历史 ====================

  const versions = ref<KnowledgeDocVersion[]>([])
  const versionsLoading = ref(false)
  const versionsError = ref<string | null>(null)

  let versionsRequestSequence = 0
  const loadVersions = async (id: number) => {
    const seq = ++versionsRequestSequence
    versionsLoading.value = true
    versionsError.value = null
    versions.value = []
    try {
      const result = await fetchKnowledgeDocVersions(id)
      if (seq !== versionsRequestSequence) return
      versions.value = result
    } catch (e) {
      if (seq !== versionsRequestSequence) return
      console.warn('[KnowledgeStore] 加载版本历史失败', e)
      versions.value = []
      versionsError.value = (e as Error).message || '加载版本历史失败'
    } finally {
      if (seq === versionsRequestSequence) versionsLoading.value = false
    }
  }

  // ==================== 版本对比（R11） ====================

  const compareResult = ref<KnowledgeVersionDiff | null>(null)
  const compareLoading = ref(false)

  /** 载入两个历史版本的 diff（对照原文，后端行级 LCS） */
  let compareRequestSequence = 0
  const loadCompare = async (id: number, fromV: number, toV: number) => {
    const seq = ++compareRequestSequence
    compareLoading.value = true
    compareResult.value = null
    try {
      const result = await compareKnowledgeDocVersions(id, fromV, toV)
      if (seq !== compareRequestSequence) return
      compareResult.value = result
    } catch (e: unknown) {
      if (seq !== compareRequestSequence) return
      console.warn('[KnowledgeStore] 版本对比失败', e)
      ElMessage.error(errorMessage(e, '版本对比失败'))
    } finally {
      if (seq === compareRequestSequence) compareLoading.value = false
    }
  }

  return {
    // 列表
    list,
    loading,
    loadError,
    total,
    totalPages,
    currentPage,
    pageSize,
    libraryTotal,
    lastQuery,
    loadList,
    refreshList,
    goToPage,
    getById,
    loadLibraryTotal,
    // 详情
    detail,
    detailStatus,
    detailError,
    detailErrorObj,
    loadDetail,
    // 侧栏聚合
    categories,
    hotTags,
    categoriesLoadError,
    hotTagsLoadError,
    loadCategories,
    loadHotTags,
    // 写操作
    createDoc,
    updateDoc,
    publishDoc,
    deprecateDoc,
    undoDeprecate,
    restoreVersion,
    purgeDoc,
    // 版本历史
    versions,
    versionsLoading,
    versionsError,
    loadVersions,
    // 版本对比
    compareResult,
    compareLoading,
    loadCompare,
  }
})
