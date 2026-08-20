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

marked.setOptions({ breaks: true, gfm: true })

const ALLOWED_TAGS = [
  'p', 'br', 'strong', 'em', 'del', 'code', 'pre', 'blockquote',
  'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'a', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td', 'hr', 'span',
  'img', 'div', 'figure', 'figcaption'
]

const ALLOWED_ATTR = ['href', 'target', 'rel', 'class', 'src', 'alt', 'title', 'data-language']

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
 * 安全渲染 Markdown/HTML 为可用的 HTML
 *
 * @param raw 原始内容（Markdown 或 HTML）
 * @param cacheKey 可选缓存键，传入时同键复用结果（用于流式消息按 id+length 缓存）
 * @returns DOMPurify 净化后的 HTML 字串
 */
export function safeMarkdown(raw: string, cacheKey?: string): string {
  if (!raw) return ''

  const key = cacheKey ?? raw
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
