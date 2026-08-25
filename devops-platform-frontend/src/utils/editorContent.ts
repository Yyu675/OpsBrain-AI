import DOMPurify from 'dompurify'
import { marked } from 'marked'

/**
 * 知识文档编辑器的内容处理逻辑。
 *
 * 从 `KnowledgeEditor.vue`（2013 行）抽出，理由有三：
 *
 * 1. **它们是纯函数**，不依赖组件状态，却被组件内 15 处调用；
 * 2. **它们决定「内容会不会丢」**——`hasMeaningfulContent` 判空写错，
 *    用户写了一屏表格却被告知「请先输入文档内容」；
 *    `toVisualContent` 的净化规则漏一类标签，粘贴来的表格就变成一坨纯文本；
 * 3. **它们在组件里无法单测**。挂载整个编辑器需要 Quill、Turndown、
 *    异步组件与路由，而这几十行逻辑本身跟这些毫无关系。
 *
 * 抽出来之后，拆分 `KnowledgeEditor.vue` 时它们已经有测试托底。
 */

/** 编辑器表单的内容部分（与组件内 EditorForm 结构对齐的最小子集） */
export interface EditorFormLike {
  title: string
  category: string
  summary: string
  tags: string[]
  content: string
  [key: string]: unknown
}

export interface EditorDraftState {
  form: EditorFormLike
  baseVersion: number | null
  publishOnCreate: boolean
  changeReason: string
  editorMode: 'visual' | 'markdown'
}

export interface TocItem {
  id: string
  text: string
  level: 2 | 3
  lineIndex?: number
  elementIndex?: number
}

/**
 * 富文本编辑器允许的标签/属性白名单。
 *
 * 导出是为了让测试能断言「表格、代码块这些常用结构没被误删」——
 * 知识库文档里表格和代码块是主力内容，漏一个标签就等于粘贴进来的内容被吃掉。
 */
export const ALLOWED_TAGS = [
  'p', 'br', 'strong', 'em', 'u', 's', 'del', 'code', 'pre', 'a', 'img',
  'blockquote', 'hr', 'span', 'div', 'figure', 'figcaption',
  'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li',
]

export const ALLOWED_ATTR = [
  'href', 'target', 'rel', 'class', 'src', 'alt', 'title', 'data-language',
]

/**
 * 判断内容是 HTML 还是 Markdown。
 *
 * 只看首个非空字符是不是 `<`。这个判据很粗，但它必须与
 * `toVisualContent` / `hasMeaningfulContent` / TOC 提取三处保持一致——
 * 三处各写一份判断才是真正的风险来源。
 */
export const isHtmlContent = (content: string): boolean => /^\s*</.test(content)

/**
 * 转为可视化编辑器接受的 HTML。
 *
 * Markdown 先渲染再净化；已经是 HTML 的直接净化。
 *
 * **净化不能省**：知识库文档可能来自导入或他人编写，
 * 未净化的内容在编辑态就会执行脚本——而编辑者往往是管理员，
 * 一次 XSS 拿到的是最高权限的会话。
 */
export const toVisualContent = async (content: string): Promise<string> => {
  if (!content.trim()) return '<p><br></p>'
  const html = isHtmlContent(content) ? content : String(await marked(content))
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR })
}

/**
 * 判断内容是否「有实质内容」。
 *
 * 富文本编辑器在「看起来空白」时仍会留下 `<p><br></p>`、零宽字符等痕迹，
 * 直接 `trim()` 判空会把它们当成有内容 —— 用户什么都没写却能提交空文档。
 *
 * 反过来，**纯图片/表格/代码块/分隔线的文档没有文本却是有内容的**。
 * 只看 textContent 会把一篇全是架构图的文档判成空，
 * 用户写了一屏却被告知「请先输入文档内容」。
 */
export const hasMeaningfulContent = (content: string): boolean => {
  if (!isHtmlContent(content)) return !!content.trim()
  const parsed = new DOMParser().parseFromString(content, 'text/html')
  return !!parsed.body.textContent?.replace(/\u200B/g, '').trim()
    || !!parsed.body.querySelector('img,table,pre,hr')
}

/**
 * 取纯文本（用于自动生成摘要）。
 *
 * HTML 走 DOM 解析而不是正则剥标签：正则遇到属性里的 `>`、
 * 注释、CDATA 都会剥错，摘要里会混进半截标签。
 */
export const toPlainText = (content: string): string => {
  const source = isHtmlContent(content)
    ? new DOMParser().parseFromString(content, 'text/html').body.textContent ?? ''
    : content
  return source
    .replace(/[#*`>\-[\]()!]/g, '')
    .replace(/\n+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * 提取文档大纲（h2 / h3）。
 *
 * HTML 与 Markdown 两条路径产出**结构相同**的 TocItem，
 * 区别只在定位方式：HTML 用 elementIndex，Markdown 用 lineIndex。
 * 两者混用会让点击目录跳到错误位置。
 */
export const extractToc = (content: string): TocItem[] => {
  const heads: TocItem[] = []
  if (isHtmlContent(content)) {
    const parsed = new DOMParser().parseFromString(content, 'text/html')
    parsed.body.querySelectorAll('h2,h3').forEach((heading, elementIndex) => {
      const level = heading.tagName.toLowerCase() === 'h3' ? 3 : 2
      heads.push({
        id: `h-${elementIndex}`,
        text: heading.textContent?.trim() || `章节 ${elementIndex + 1}`,
        level,
        elementIndex,
      })
    })
  } else {
    content.split('\n').forEach((line, lineIndex) => {
      const match = /^(##|###)\s+(.+)/.exec(line)
      if (match) {
        heads.push({
          id: `h-${heads.length}`,
          text: match[2].trim(),
          level: match[1].length as 2 | 3,
          lineIndex,
        })
      }
    })
  }
  return heads
}

/**
 * 兼容旧版草稿格式。
 *
 * 老版本直接存 form 对象，新版存 `{ form, baseVersion, ... }`。
 * 不做兼容的话，升级发版会让所有人的在编草稿读出来变成 undefined。
 *
 * 旧格式没有 baseVersion，一律按 `null`（=「不知道基于哪版」）处理，
 * 由调用方走版本冲突确认流程 —— 比默认当成「与服务器同版」安全：
 * 后者会让旧草稿直接覆盖他人的新修改。
 */
export const normalizeDraftState = (
  raw: EditorDraftState | EditorFormLike,
): EditorDraftState => {
  if ('form' in raw) return raw as EditorDraftState
  return {
    form: raw as EditorFormLike,
    baseVersion: null,
    publishOnCreate: false,
    changeReason: '',
    editorMode: 'visual',
  }
}
