/**
 * 知识库 API - 知识库管理接口
 *
 * 涵盖两部分：
 * 1. 切片级（摄取/浏览）——对应后端 KnowledgeManageController
 * 2. 文档级 CRUD + 生命周期——对应后端 KnowledgeDocController（6.21）
 */

import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz, HttpError, httpRequest } from '../utils/http'
import type {
  KnowledgeDocCreateRequest,
  KnowledgeDocUpdateRequest,
  KnowledgeDocSaveResult,
  KnowledgeDocDetail,
  KnowledgeDocListItem,
  KnowledgeDocPageResponse,
  KnowledgeDocVersion,
  KnowledgeDocCategory,
  KnowledgeHotTag,
  KnowledgeTag,
  KnowledgeVersionDiff,
  KnowledgeCategoryEntity,
  KnowledgeCategoryTreeResponse,
} from './types'

// ==================== 文档级 CRUD + 生命周期（6.21）====================

/** 内容重复错误（后端 40021，附重复文档 ID） */
export class DuplicateContentError extends Error {
  readonly isDuplicate = true
  duplicateDocId: number | null
  duplicateTitle: string | null

  constructor(message: string, docId: number | null = null, title: string | null = null) {
    super(message)
    this.name = 'DuplicateContentError'
    this.duplicateDocId = docId
    this.duplicateTitle = title
  }
}

/** 乐观锁版本冲突（后端 40009，须提示用户刷新后再提交，禁止自动覆盖） */
export class VersionConflictError extends Error {
  readonly isVersionConflict = true

  constructor(message: string) {
    super(message)
    this.name = 'VersionConflictError'
  }
}

/** 文档不存在（后端 40004，详情页据此走 notFound 分支） */
export class NotFoundDocError extends Error {
  readonly isNotFound = true

  constructor(message: string) {
    super(message)
    this.name = 'NotFoundDocError'
  }
}

/**
 * 解包文档接口：业务码映射到既有错误类。
 * 写操作走 http（默认不重试），避免超时重试重复建文档。
 */
function unwrapDoc<T>(payload: unknown, errorPrefix: string, opts: { notFoundIsNotFound?: boolean } = {}): T {
  try {
    return unwrapBiz<T>(payload, errorPrefix)
  } catch (e) {
    if (e instanceof HttpError) {
      if (e.bizCode === 40021) {
        const data = (e.data ?? {}) as Record<string, unknown>
        throw new DuplicateContentError(
          e.message || errorPrefix,
          typeof data.duplicateDocId === 'number' ? data.duplicateDocId : null,
          typeof data.duplicateTitle === 'string' ? data.duplicateTitle : null
        )
      }
      if (e.bizCode === 40009) {
        throw new VersionConflictError(e.message || errorPrefix)
      }
      if (e.bizCode === 40004 && opts.notFoundIsNotFound) {
        throw new NotFoundDocError(e.message || errorPrefix)
      }
    }
    throw e
  }
}

/**
 * 创建文档
 * @param publish true=发布（立即向量化）；false=存草稿
 */
export async function createKnowledgeDoc(
  req: KnowledgeDocCreateRequest
): Promise<KnowledgeDocSaveResult> {
  const payload = await http.post<unknown>(API_ENDPOINTS.KNOWLEDGE_DOCS, req)
  return unwrapDoc<KnowledgeDocSaveResult>(payload, '创建文档失败')
}

/**
 * 更新文档（version CAS 乐观锁）
 */
export async function updateKnowledgeDoc(
  id: number,
  req: KnowledgeDocUpdateRequest
): Promise<KnowledgeDocSaveResult> {
  const payload = await http.put<unknown>(`${API_ENDPOINTS.KNOWLEDGE_DOCS}/${id}`, req)
  return unwrapDoc<KnowledgeDocSaveResult>(payload, '更新文档失败')
}

/**
 * 发布文档（草稿 → 已发布）+ 触发向量化
 */
export async function publishKnowledgeDoc(id: number): Promise<{
  id: number
  indexStatus: string
  retrievable: boolean
  indexError?: string
}> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.KNOWLEDGE_DOCS}/${id}/publish`)
  return unwrapDoc(payload, '发布文档失败')
}

/**
 * 废弃文档（默认「删除」语义：留正文删向量，退出检索）
 */
export async function deprecateKnowledgeDoc(id: number, reason?: string): Promise<void> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.KNOWLEDGE_DOCS}/${id}/deprecate`, { reason })
  unwrapDoc<unknown>(payload, '废弃文档失败')
}

/**
 * 回滚到历史版本
 */
export async function restoreKnowledgeDoc(
  id: number,
  version: number
): Promise<{ id: number; version: number; retrievable: boolean }> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.KNOWLEDGE_DOCS}/${id}/restore`, { version })
  return unwrapDoc(payload, '回滚文档失败')
}

/**
 * 物理删除（必须 complianceReason，仅合规场景）
 */
export async function purgeKnowledgeDoc(id: number, complianceReason: string): Promise<void> {
  const payload = await httpRequest<unknown>(`${API_ENDPOINTS.KNOWLEDGE_DOCS}/${id}/purge`, {
    method: 'DELETE',
    body: JSON.stringify({ complianceReason })
  })
  unwrapDoc<unknown>(payload, '物理删除文档失败')
}

/**
 * 分页查询文档
 */
export async function fetchKnowledgeDocs(params: {
  page?: number
  size?: number
  status?: string
  category?: string
  keyword?: string
  tag?: string
  sort?: string
} = {}): Promise<KnowledgeDocPageResponse> {
  const queryParams = new URLSearchParams()
  if (params.page !== undefined) queryParams.append('page', String(params.page))
  if (params.size !== undefined) queryParams.append('size', String(params.size))
  if (params.status) queryParams.append('status', params.status)
  if (params.category) queryParams.append('category', params.category)
  if (params.keyword) queryParams.append('keyword', params.keyword)
  if (params.tag) queryParams.append('tag', params.tag)
  if (params.sort) queryParams.append('sort', params.sort)

  const url = `${API_ENDPOINTS.KNOWLEDGE_DOCS}${
    queryParams.toString() ? `?${queryParams.toString()}` : ''
  }`
  const payload = await http.get<unknown>(url)
  return unwrapBiz<KnowledgeDocPageResponse>(payload, '查询文档失败')
}

/**
 * 扁平分类聚合（侧栏导航，全库跨页）
 */
export async function fetchKnowledgeDocCategories(): Promise<KnowledgeDocCategory[]> {
  const payload = await http.get<unknown>(API_ENDPOINTS.KNOWLEDGE_DOC_CATEGORIES)
  return unwrapBiz<KnowledgeDocCategory[]>(payload, '查询分类失败')
}

/** 独立目录分类及其文档，用于详情/编辑工作区左侧树。 */
export async function fetchKnowledgeCategoryTree(): Promise<KnowledgeCategoryTreeResponse> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.KNOWLEDGE_CATEGORIES}/tree`)
  return unwrapBiz<KnowledgeCategoryTreeResponse>(payload, '查询目录树失败')
}

export async function fetchKnowledgeCategories(): Promise<KnowledgeCategoryEntity[]> {
  const payload = await http.get<unknown>(API_ENDPOINTS.KNOWLEDGE_CATEGORIES)
  return unwrapBiz<KnowledgeCategoryEntity[]>(payload, '查询目录分类失败')
}

export async function createKnowledgeCategory(req: {
  name: string
  parentId?: number | null
  sortOrder?: number
}): Promise<KnowledgeCategoryEntity> {
  const payload = await http.post<unknown>(API_ENDPOINTS.KNOWLEDGE_CATEGORIES, req)
  return unwrapDoc<KnowledgeCategoryEntity>(payload, '创建分类失败')
}

export async function updateKnowledgeCategory(
  id: number,
  req: { name: string; parentId?: number | null; sortOrder?: number }
): Promise<KnowledgeCategoryEntity> {
  const payload = await http.put<unknown>(`${API_ENDPOINTS.KNOWLEDGE_CATEGORIES}/${id}`, req)
  return unwrapDoc<KnowledgeCategoryEntity>(payload, '更新分类失败')
}

export async function deleteKnowledgeCategory(id: number): Promise<void> {
  const payload = await http.del<unknown>(`${API_ENDPOINTS.KNOWLEDGE_CATEGORIES}/${id}`)
  unwrapDoc<unknown>(payload, '删除分类失败')
}

export async function moveKnowledgeDocument(
  docId: number,
  categoryId: number | null,
  version?: number
): Promise<void> {
  const payload = await http.put<unknown>(
    `${API_ENDPOINTS.KNOWLEDGE_CATEGORIES}/documents/${docId}`,
    { categoryId, version }
  )
  unwrapDoc<unknown>(payload, '移动文档失败')
}

/**
 * 热门标签（仅 PUBLISHED 文档计数，全库跨页）
 */
export async function fetchKnowledgeDocHotTags(limit = 20): Promise<KnowledgeHotTag[]> {
  const payload = await http.get<unknown>(
    `${API_ENDPOINTS.KNOWLEDGE_DOC_TAGS_HOT}?limit=${limit}`
  )
  return unwrapBiz<KnowledgeHotTag[]>(payload, '查询热门标签失败')
}

export async function fetchKnowledgeTags(): Promise<KnowledgeTag[]> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.KNOWLEDGE_BASE}/tags`)
  return unwrapBiz<KnowledgeTag[]>(payload, '查询标签失败')
}

export async function createKnowledgeTag(req: { name: string; description?: string; color?: string }): Promise<KnowledgeTag> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.KNOWLEDGE_BASE}/tags`, req)
  return unwrapDoc<KnowledgeTag>(payload, '创建标签失败')
}

export async function updateKnowledgeTag(id: number, req: { name: string; description?: string; color?: string }): Promise<KnowledgeTag> {
  const payload = await http.put<unknown>(`${API_ENDPOINTS.KNOWLEDGE_BASE}/tags/${id}`, req)
  return unwrapDoc<KnowledgeTag>(payload, '更新标签失败')
}

export async function mergeKnowledgeTag(id: number, targetId: number): Promise<KnowledgeTag> {
  const payload = await http.post<unknown>(`${API_ENDPOINTS.KNOWLEDGE_BASE}/tags/${id}/merge`, { targetId })
  return unwrapDoc<KnowledgeTag>(payload, '合并标签失败')
}

export async function deleteKnowledgeTag(id: number, replacementId?: number): Promise<void> {
  const payload = await httpRequest<unknown>(`${API_ENDPOINTS.KNOWLEDGE_BASE}/tags/${id}`, {
    method: 'DELETE',
    body: JSON.stringify(replacementId ? { replacementId } : {})
  })
  unwrapDoc<unknown>(payload, '删除标签失败')
}

/**
 * 文档详情（含正文）
 */
export async function fetchKnowledgeDocDetail(id: number): Promise<KnowledgeDocDetail> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.KNOWLEDGE_DOCS}/${id}`)
  return unwrapDoc<KnowledgeDocDetail>(payload, '查询文档详情失败', { notFoundIsNotFound: true })
}

/**
 * 按源工单反查已沉淀的文档（L1.5 来源回链）
 * <p>供工单详情页展示「已沉淀为知识」徽标与跳转入口。</p>
 */
export async function findDocsBySourceTicket(ticketId: number): Promise<KnowledgeDocListItem[]> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.KNOWLEDGE_DOCS}/by-source-ticket/${ticketId}`)
  return unwrapDoc<KnowledgeDocListItem[]>(payload, '按源工单反查文档失败')
}

/**
 * 版本历史列表
 */
export async function fetchKnowledgeDocVersions(id: number): Promise<KnowledgeDocVersion[]> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.KNOWLEDGE_DOCS}/${id}/versions`)
  const data = unwrapDoc<{ versions: KnowledgeDocVersion[] }>(payload, '查询版本历史失败')
  return data.versions ?? []
}

/**
 * 版本对比（对照两个历史版本原文，返回三段式差异）
 */
export async function compareKnowledgeDocVersions(
  id: number,
  fromV: number,
  toV: number
): Promise<KnowledgeVersionDiff> {
  const payload = await http.get<unknown>(
    `${API_ENDPOINTS.KNOWLEDGE_DOCS}/${id}/compare?fromV=${fromV}&toV=${toV}`
  )
  return unwrapDoc<KnowledgeVersionDiff>(payload, '版本对比失败')
}

/**
 * 手动触发向量化重试（针对 index_status=FAILED/PENDING 的文档）
 */
export async function retryIndexing(limit = 20): Promise<{ retried: number }> {
  const payload = await http.post<unknown>(
    `${API_ENDPOINTS.KNOWLEDGE_DOCS}/reindex/pending?limit=${limit}`
  )
  return unwrapDoc(payload, '重试向量化失败')
}

// ==================== 索引状态文案（前端统一展示）====================

/** 状态中文文案 */
export function statusLabel(status?: string): string {
  switch (status) {
    case 'DRAFT': return '草稿'
    case 'PUBLISHED': return '已发布'
    case 'DEPRECATED': return '已废弃'
    case 'ARCHIVED': return '已归档'
    default: return status ?? ''
  }
}

/** 索引状态中文文案 */
export function indexStatusLabel(status?: string): string {
  switch (status) {
    case 'PENDING': return '待向量化'
    case 'INDEXED': return '可检索'
    case 'FAILED': return '向量化失败'
    case 'SKIPPED': return '未索引'
    default: return status ?? ''
  }
}
