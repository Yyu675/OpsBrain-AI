/**
 * 知识库文档「正文渲染后处理」：目录构建、scroll spy、章节跳转、代码块语言标注。
 *
 * 从 `KnowledgeDetail.vue` 抽出。这四件事共享同一组前提——
 * 它们都必须在 `v-html` 把 Markdown 渲染成真实 DOM **之后**才能执行，
 * 且都依赖同一个正文容器 ref。
 *
 * <h3>目录不是装饰</h3>
 * 运维在故障处置中查 SOP，靠的就是目录直接跳到「回滚步骤」那一节。
 * 它失效时的三种形态<b>都不会报错</b>：
 * <ol>
 *   <li><b>目录变空、正文却完全正常</b>——{@code buildToc} 从渲染后的 DOM 里
 *       捞 h2/h3，若净化白名单不含标题标签，正文看着好好的，目录却是空的；</li>
 *   <li><b>点目录毫无反应</b>——锚点 id 是 {@code buildToc} 现场写上去的，
 *       不写则 {@code getElementById} 拿到 null，点击静默失败；</li>
 *   <li><b>高亮与正文对不上</b>——scroll spy 的边界判断（{@code <=} 还是 {@code <}、
 *       有没有算上 96px 视差偏移）错了，用户看着第 3 节，目录高亮在第 2 节。</li>
 * </ol>
 *
 * <h3>为什么 activeToc 不能留空</h3>
 * 侧栏目录靠 {@code activeToc === item.id} 决定高亮。留空意味着一项都不高亮，
 * 用户失去「我现在在哪一节」的定位——这在长文档里等于目录只剩跳转功能。
 * 因此 {@code buildToc} 末尾会立即算一次当前章节，而不是等第一次滚动。
 */
import { ref, type Ref } from 'vue'

export interface TocItem {
  id: string
  text: string
  level: 2 | 3
}

/** 顶部视差偏移（px）：正文顶部被吸顶区域遮住的高度 */
const SCROLL_SPY_OFFSET = 96

export interface UseDocOutlineOptions {
  /** 正文容器 ref（`v-html` 挂载点） */
  articleContentRef: Ref<HTMLElement | null>
  /** 滚动容器 ref（scroll spy 的参照系） */
  mainContainer: Ref<HTMLElement | null>
}

export function useDocOutline(options: UseDocOutlineOptions) {
  const { articleContentRef, mainContainer } = options

  const toc = ref<TocItem[]>([])
  const activeToc = ref<string>('')
  /** h2/h3 元素引用，供 scroll spy 计算当前阅读章节 */
  const tocEls = ref<HTMLElement[]>([])

  /**
   * 给代码块标注语言。
   *
   * 语言取自 highlight.js 约定的 `language-xxx` class；取不到时标 TEXT
   * 而非留空——空的角标会让代码块右上角出现一块没有文字的色块，
   * 看起来像渲染坏了。
   */
  function decorateArticleContent() {
    const contentEl = articleContentRef.value
    if (!contentEl) return
    contentEl.querySelectorAll('pre').forEach(pre => {
      const code = pre.querySelector('code')
      const lang = Array.from(code?.classList ?? [])
        .find(name => name.startsWith('language-'))
        ?.slice('language-'.length)
      pre.setAttribute('data-language', (lang || 'TEXT').toUpperCase())
    })
  }

  /**
   * 从渲染后的正文里构建目录，并就地写入锚点 id。
   *
   * 写 id 是跳转的前提：Markdown 渲染出的 h2/h3 本身没有 id，
   * 不写的话 `scrollToToc` 里的 `getElementById` 必然拿到 null。
   */
  function buildToc() {
    const contentEl = articleContentRef.value
    if (!contentEl) return
    const heads = contentEl.querySelectorAll('h2,h3')
    const items: TocItem[] = []
    heads.forEach((h, idx) => {
      const id = `toc-${idx}`
      h.setAttribute('id', id)
      items.push({
        id,
        // 标题为空时给个占位，否则目录里出现一行空白，用户不知道那是什么
        text: h.textContent || `章节 ${idx + 1}`,
        level: h.tagName.toLowerCase() === 'h3' ? 3 : 2,
      })
    })
    toc.value = items
    tocEls.value = Array.from(heads) as HTMLElement[]
    activeToc.value = items[0]?.id || ''
    // 立即算一次：不等第一次滚动，否则打开文档时目录一项都不高亮
    updateActiveToc()
  }

  /**
   * scroll spy：取最后一个处于视口上方（含视差偏移）的章节并高亮。
   *
   * 用「最后一个满足条件的」而非「第一个」——正文向上滚动时，
   * 已划过顶部的章节会不断累积，只有最后一个才是当前正在读的那节。
   */
  function updateActiveToc() {
    const top = (mainContainer.value?.getBoundingClientRect().top ?? 0) + SCROLL_SPY_OFFSET
    let current = toc.value[0]?.id || ''
    for (const el of tocEls.value) {
      if (el.getBoundingClientRect().top <= top) current = el.id
    }
    activeToc.value = current
  }

  /** 点击目录跳转到对应章节 */
  function scrollToToc(id: string) {
    const el = document.getElementById(id)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' })
      // 立即高亮而不等滚动事件：平滑滚动期间用户已经点了，
      // 高亮却要等几百毫秒才跟上，看起来像没点中
      activeToc.value = id
    }
  }

  /**
   * 正文渲染完成后的统一入口。
   *
   * 两步顺序无强依赖，但合成一个方法可以避免调用方漏掉其中一步——
   * 只 buildToc 不 decorate，代码块就没有语言角标；反之目录是空的。
   */
  function refreshAfterRender() {
    decorateArticleContent()
    buildToc()
  }

  return {
    toc,
    activeToc,
    tocEls,
    decorateArticleContent,
    buildToc,
    updateActiveToc,
    scrollToToc,
    refreshAfterRender,
  }
}
