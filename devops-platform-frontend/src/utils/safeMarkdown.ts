/**
 * 统一 Markdown 安全渲染工具
 *
 * 全局唯一的 Markdown → HTML → DOMPurify 净化入口。
 * 所有 v-html 渲染 Markdown 的场景都必须走此函数，禁止自行调用 marked + DOMPurify。
 *
 * 安全措施：
 * - 白名单标签/属性
 * - 强制给 target="_blank" 的 a 标签补 rel="noopener noreferrer"（防 tabnabbing）
 * - 缓存渲染结果，避免每个 token 全量 parse（ChatMode/AIContextPanel 性能优化）
 */

import { marked } from 'marked'
import DOMPurify from 'dompurify'

import { SANITIZE_ALLOWED_ATTR, SANITIZE_ALLOWED_TAGS } from './htmlSanitizePolicy'

marked.setOptions({ breaks: true, gfm: true })

// 白名单来自 htmlSanitizePolicy（全项目唯一真相）。
// 此前这里自带一份，缺 <u>/<s>——编辑器存得进、这里渲染时被剥掉，
// 用户看到的是「排版发布后就没了」，且没有任何报错。
const ALLOWED_TAGS = [...SANITIZE_ALLOWED_TAGS]
const ALLOWED_ATTR = [...SANITIZE_ALLOWED_ATTR]

// DOMPurify hook: 给所有 target=_blank 的 a 标签强制补 rel
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A' && node.getAttribute('target') === '_blank') {
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

/** 渲染缓存：key = 内容 hash（或完整内容），value = 净化后的 HTML */
const renderCache = new Map<string, string>()
const MAX_CACHE_SIZE = 200

/**
 * 内容指纹（FNV-1a 32 位）。
 *
 * 为什么需要：调用方传的 cacheKey 形如 `doc-42-{length}`，
 * **长度相同但内容不同的两次渲染会撞 key**，导致返回上一次的 HTML。
 * 这不是理论问题——运维文档把「主从延迟 30 秒」改成「90 秒」长度不变，
 * 用户看到的仍是旧值，而这种数字在运维手册里是要照着执行的。
 *
 * 用非加密哈希足够：这里只需要「内容变了 key 就变」，
 * 不涉及安全对抗，FNV-1a 比 SHA 快一个数量级且无需异步。
 */
function fingerprint(text: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    // FNV 质数乘法，用移位实现避免大整数溢出
    h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0
  }
  return h.toString(36)
}

/**
 * 安全渲染 Markdown/HTML 为可用的 HTML
 *
 * @param raw 原始内容（Markdown 或 HTML）
 * @param cacheKey 可选缓存键前缀。**内部会附加内容指纹**，
 *                 故调用方无需（也不应）自行拼接长度来区分版本
 * @returns DOMPurify 净化后的 HTML 字串
 */
export function safeMarkdown(raw: string, cacheKey?: string): string {
  if (!raw) return ''

  // 始终把内容指纹并入 key：调用方给的前缀只用于分组（区分不同文档/消息），
  // 真正保证「内容变则缓存失效」的是指纹
  const key = cacheKey ? `${cacheKey}#${fingerprint(raw)}` : raw
  const cached = renderCache.get(key)
  if (cached !== undefined) return cached

  try {
    // 统一走 marked.parse，不区分 Markdown/HTML
    // marked 对 HTML 原文会原样透传，不影响结果
    const html = marked.parse(raw) as string
    const safe = DOMPurify.sanitize(html, {
      ALLOWED_TAGS,
      ALLOWED_ATTR,
      ADD_ATTR: ['rel'],
    })

    if (renderCache.size >= MAX_CACHE_SIZE) {
      const firstKey = renderCache.keys().next().value
      if (firstKey) renderCache.delete(firstKey)
    }
    renderCache.set(key, safe)
    return safe
  } catch {
    // 极端畸形输入降级为转义纯文本，不让调用方崩溃
    return DOMPurify.sanitize(raw, { ALLOWED_TAGS: [], ALLOWED_ATTR: [] })
  }
}

/** 清空渲染缓存（测试用） */
export function clearMarkdownCache(): void {
  renderCache.clear()
}
