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

// ==================== 目录标题的增 / 改 / 删 ====================
//
// 这三个函数直接改写用户正文。它们在 HTML 与 Markdown 两条路径上做同一件事，
// 而两条路径的定位方式完全不同（elementIndex vs lineIndex）——
// 用错一条就是改到别的标题上，用户看到的是「我改了 A，B 却变了」。
//
// 抽出来的另一个理由：组件里这三个函数各自包着 ElMessageBox 的
// try/catch（用户取消），交互与内容变换缠在一起，没法单测。
// 现在交互留在组件、变换在这里，各测各的。

/** 追加一个二级标题到文末（返回新正文，不改入参） */
export const appendHeading = (content: string, text: string, isVisual: boolean): string => {
  const title = text.trim()
  if (!title) return content

  if (isVisual) {
    const parsed = new DOMParser().parseFromString(content, 'text/html')
    const heading = parsed.createElement('h2')
    heading.textContent = title
    // 标题后补一个空段落：不补的话光标无处可去，
    // 用户点完「新增标题」会发现没法接着往下写
    parsed.body.append(heading, parsed.createElement('p'))
    return parsed.body.innerHTML
  }

  const prefix = content.trimEnd()
  return `${prefix}${prefix ? '\n\n' : ''}## ${title}\n\n`
}

/**
 * 重命名指定标题（返回新正文）。
 *
 * 定位必须与 {@link extractToc} 产出的索引一致：
 * 可视化模式用 elementIndex，Markdown 用 lineIndex。
 * 索引越界时原样返回——宁可什么都不做，也不能改错一个标题。
 */
export const renameHeadingIn = (
  content: string,
  item: TocItem,
  text: string,
  isVisual: boolean,
): string => {
  const title = text.trim()
  if (!title) return content

  if (isVisual && item.elementIndex !== undefined) {
    const parsed = new DOMParser().parseFromString(content, 'text/html')
    const heading = parsed.body.querySelectorAll('h2,h3')[item.elementIndex]
    if (!heading) return content
    heading.textContent = title
    return parsed.body.innerHTML
  }

  if (item.lineIndex !== undefined) {
    const lines = content.split('\n')
    if (item.lineIndex < 0 || item.lineIndex >= lines.length) return content
    lines[item.lineIndex] = `${'#'.repeat(item.level)} ${title}`
    return lines.join('\n')
  }

  return content
}

/**
 * 删除指定标题（返回新正文）。
 *
 * <b>只删标题行本身，标题下的正文保留</b>——这是与「删除章节」不同的语义。
 * 连正文一起删会让用户点一下丢掉整段内容，且没有撤销。
 */
export const removeHeadingIn = (
  content: string,
  item: TocItem,
  isVisual: boolean,
): string => {
  if (isVisual && item.elementIndex !== undefined) {
    const parsed = new DOMParser().parseFromString(content, 'text/html')
    const heading = parsed.body.querySelectorAll('h2,h3')[item.elementIndex]
    if (!heading) return content
    heading.remove()
    return parsed.body.innerHTML
  }

  if (item.lineIndex !== undefined) {
    const lines = content.split('\n')
    if (item.lineIndex < 0 || item.lineIndex >= lines.length) return content
    lines.splice(item.lineIndex, 1)
    return lines.join('\n')
  }

  return content
}

// ==================== 块模板 ====================

export type BlockCommand = 'h2' | 'h3' | 'callout' | 'code' | 'table' | 'divider'

/**
 * 插入块的模板。
 *
 * 两套模板必须**语义一一对应**：用户在可视化模式插的表格，
 * 切到 Markdown 模式应该还是表格。少一个键或语义对不上，
 * 切换编辑模式时内容就会变形。
 */
export const HTML_BLOCKS: Record<BlockCommand, string> = {
  h2: '<h2>二级标题</h2><p><br></p>',
  h3: '<h3>三级标题</h3><p><br></p>',
  callout: '<blockquote><p><br></p></blockquote><p><br></p>',
  code: '<pre><code><br></code></pre><p><br></p>',
  table: '<table><tbody><tr><th>字段</th><th>说明</th></tr>'
    + '<tr><td><br></td><td><br></td></tr></tbody></table><p><br></p>',
  divider: '<hr><p><br></p>',
}

export const MARKDOWN_BLOCKS: Record<BlockCommand, string> = {
  h2: '## 二级标题\n\n',
  h3: '### 三级标题\n\n',
  callout: '> 提示内容\n\n',
  code: '```text\n\n```\n\n',
  table: '| 字段 | 说明 |\n| --- | --- |\n|  |  |\n\n',
  divider: '---\n\n',
}

/** 把 Markdown 块追加到文末（可视化模式由富文本编辑器自己插入，不走这里） */
export const appendMarkdownBlock = (content: string, command: BlockCommand): string => {
  const prefix = content.trimEnd()
  return `${prefix}${prefix ? '\n\n' : ''}${MARKDOWN_BLOCKS[command]}`
}

// ==================== 标签 ====================

/** 标签上限，对齐后端 MAX_TAGS_PER_DOC */
export const MAX_TAGS = 20

/**
 * 规整标签列表：去空白、按<b>不区分大小写</b>去重、截断到上限。
 *
 * 大小写不敏感是关键：`K8s` 与 `k8s` 是同一个标签，
 * 当成两个会让标签云里出现一堆看起来重复的项，
 * 而按标签筛选时又只能命中其中一个。
 */
export const normalizeTagList = (tags: string[]): string[] => {
  const seen = new Set<string>()
  return tags
    .map(tag => tag.trim())
    .filter(tag => {
      const normalized = tag.toLocaleLowerCase()
      return !!tag && !seen.has(normalized) && seen.add(normalized)
    })
    .slice(0, MAX_TAGS)
}

/** 判断标签是否已存在（不区分大小写） */
export const hasTag = (tags: string[], tag: string): boolean => {
  const t = tag.trim().toLocaleLowerCase()
  return tags.some(item => item.trim().toLocaleLowerCase() === t)
}

// ==================== 分类路径 ====================

/** 分类树节点的最小形状（与 KnowledgeCategoryEntity 兼容） */
export interface CategoryNodeLike {
  id: number
  parentId: number | null
  name: string
}

/**
 * 构建分类的完整路径，如「运维 / 容器 / K8s」。
 *
 * <b>必须防循环引用</b>：分类的 parentId 由用户配置，
 * 数据异常时可能出现 A→B→A 这样的环。没有 `seen` 集合的话，
 * 这个 while 会死循环——表现是<b>整个页面卡死</b>，
 * 不是报错、不是空白，是浏览器标签页直接无响应。
 *
 * 后端 KnowledgeCategoryService.ensureNoCycle 会拦住新建的环，
 * 但历史数据与并发写入都可能绕过它，前端这层兜底不能省。
 *
 * 断链时（父分类不存在）返回已拼出的部分，而不是空串——
 * 显示「容器 / K8s」比什么都不显示有用。
 */
export const buildCategoryPath = (
  category: CategoryNodeLike,
  all: readonly CategoryNodeLike[],
): string => {
  const names: string[] = [category.name]
  const seen = new Set<number>([category.id])
  let parentId = category.parentId

  while (parentId != null && !seen.has(parentId)) {
    const parent = all.find(item => item.id === parentId)
    if (!parent) break
    names.unshift(parent.name)
    seen.add(parent.id)
    parentId = parent.parentId
  }

  return names.join(' / ')
}

/** 按名称反查分类 ID；找不到返回 null（表示「未归类」而非出错） */
export const findCategoryIdByName = (
  name: string,
  all: readonly CategoryNodeLike[],
): number | null => all.find(item => item.name === name)?.id ?? null
