/**
 * HTML 净化白名单——**全项目唯一真相**。
 *
 * ── 为什么要有这个文件 ────────────────────────────────────────
 * 在此之前项目里有**三套各自维护的白名单**：
 *
 * | 位置                        | 标签 | 属性 |
 * |-----------------------------|------|------|
 * | `editorContent.ts`（编辑器）| 33   | 8    |
 * | `safeMarkdown.ts`（详情页） | 31   | 8    |
 * | `KnowledgeSinkDrawer.vue`   | 26   | 4    |
 *
 * 三者不一致带来的是**静默的内容丢失**，用户完全看不出原因：
 *
 * - `<u>` 下划线与 `<s>` 删除线只有编辑器允许。用户排好版、保存成功、
 *   编辑态看得见，**一发布到详情页就没了**。他会以为是自己没保存，
 *   回去重排一遍，再发布，还是没有——全程零报错；
 * - `<img>` / `<figure>` / `<table>` 的 `tfoot` 只有前两者允许。
 *   知识沉淀抽屉预览同一篇文档时，图片和表尾直接消失，
 *   用户以为 AI 整理时把内容弄丢了；
 * - 抽屉少了 `src` / `alt` 属性，即便标签放行，图片也渲染不出来。
 *
 * ── 为什么是常量而不是函数 ────────────────────────────────────
 * 三处的**用途确实不同**，不该合并成一个渲染函数：
 *
 * - `safeMarkdown` 面向阅读，带渲染缓存与 `rel="noopener"` 补全 hook；
 * - `toVisualContent` 面向富文本编辑器输入，需要保证结构可编辑；
 * - 抽屉是 AI 产出的预览。
 *
 * 它们该共享的是**「什么标签算安全」这个判断**，而不是渲染流程。
 * 所以这里只导出策略常量，各自的渲染逻辑留在原处。
 *
 * ── 修改须知 ──────────────────────────────────────────────────
 * 放宽白名单等于扩大 XSS 面。新增标签前先问：
 * 这个标签能否携带脚本、能否加载外部资源、能否覆盖页面布局。
 * `htmlSanitizePolicy.test.ts` 会校验三处引用的一致性，
 * 也会拦住 `script` / `iframe` 这类危险标签被误加进来。
 */

/**
 * 允许的标签。
 *
 * 取三套的**并集**而非交集：交集会让编辑器现在能用的下划线、
 * 详情页现在能显示的图片全部失效——那是用「统一」的名义制造新的数据丢失。
 * 并集里的每一个标签在原先至少一处已经是放行状态，不引入新的风险面。
 */
export const SANITIZE_ALLOWED_TAGS: readonly string[] = [
  // 段落与内联
  'p', 'br', 'strong', 'em', 'u', 's', 'del', 'span', 'div',
  // 标题
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  // 列表与引用
  'ul', 'ol', 'li', 'blockquote',
  // 代码
  'code', 'pre',
  // 表格（含 tfoot——运维手册里的汇总行常用）
  'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td',
  // 媒体与分隔
  'a', 'img', 'hr', 'figure', 'figcaption',
]

/**
 * 允许的属性。
 *
 * `data-language` 用于代码块高亮；`rel` 由 `safeMarkdown` 的
 * afterSanitizeAttributes hook 写入（防 tabnabbing），必须在白名单内，
 * 否则 hook 写进去也会被后续净化剥掉。
 */
export const SANITIZE_ALLOWED_ATTR: readonly string[] = [
  'href', 'target', 'rel', 'class', 'src', 'alt', 'title', 'data-language',
]

/**
 * 明确禁止的标签——即便有人把它们加进上面的白名单，测试也会拦下。
 *
 * 这不是运行时防护（DOMPurify 默认就不放行它们），
 * 而是一条**防止白名单被误放宽**的断言依据。
 * 编辑者往往是管理员，一次 XSS 拿到的是最高权限会话。
 */
export const SANITIZE_FORBIDDEN_TAGS: readonly string[] = [
  'script', 'iframe', 'object', 'embed', 'form', 'input',
  'style', 'link', 'meta', 'base',
]

/** DOMPurify 配置对象（三处渲染入口统一引用） */
export const sanitizeConfig = () => ({
  ALLOWED_TAGS: [...SANITIZE_ALLOWED_TAGS],
  ALLOWED_ATTR: [...SANITIZE_ALLOWED_ATTR],
})
