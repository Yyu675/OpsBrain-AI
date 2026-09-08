/**
 * useDocOutline 直接单测。
 *
 * ── 与 KnowledgeDetail.toc.test.ts 的分工 ──────────────────────
 * 那 15 例是**通过页面**验证目录行为的，覆盖得已经不错。但它们绑在
 * `KnowledgeDetail.vue` 上——一旦这个 composable 被别的页面复用
 * （知识库预览、工单里的 SOP 内嵌视图都可能用到），
 * 那组用例保护不到新的调用方。
 *
 * 这里直接对 composable 本身建立契约，不经过任何页面。
 *
 * ── jsdom 没有布局引擎 ────────────────────────────────────────
 * `getBoundingClientRect()` 在 jsdom 下**恒返回全 0**。若不显式造几何数据，
 * scroll spy 的用例会「碰巧通过」而完全没验证到位置判断——
 * 因为每个标题都被判为「已在阈值上方」，最后一个总是胜出。
 * 所以下面凡是涉及位置的用例，都手工给每个标题打上 top 值。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useDocOutline } from '../useDocOutline'

/** 造一个带正文的容器 */
function makeArticle(html: string): HTMLElement {
  const el = document.createElement('div')
  el.innerHTML = html
  document.body.appendChild(el)
  return el
}

/** 给元素固定 getBoundingClientRect().top（jsdom 不做布局，必须手工造） */
function stubTop(el: Element, top: number) {
  ;(el as HTMLElement).getBoundingClientRect = () =>
    ({ top, bottom: top + 20, left: 0, right: 0, width: 0, height: 20, x: 0, y: top, toJSON: () => ({}) }) as DOMRect
}

function setup(html: string, containerTop = 0) {
  const articleContentRef = ref<HTMLElement | null>(makeArticle(html))
  const container = document.createElement('div')
  stubTop(container, containerTop)
  const mainContainer = ref<HTMLElement | null>(container)
  return { ...useDocOutline({ articleContentRef, mainContainer }), articleContentRef, mainContainer }
}

beforeEach(() => {
  document.body.innerHTML = ''
})

describe('buildToc', () => {
  it('提取 h2/h3 并保留层级', () => {
    const o = setup('<h2>安装</h2><p>x</p><h3>依赖</h3><h2>回滚步骤</h2>')
    o.buildToc()

    expect(o.toc.value.map(t => t.text)).toEqual(['安装', '依赖', '回滚步骤'])
    expect(o.toc.value.map(t => t.level)).toEqual([2, 3, 2])
  })

  it('给每个标题写上锚点 id——不写的话点目录毫无反应', () => {
    const o = setup('<h2>一</h2><h2>二</h2>')
    o.buildToc()

    // scrollToToc 靠 getElementById 定位，id 是这里现场写上去的
    expect(document.querySelectorAll('h2')[0].id).toBe('toc-0')
    expect(document.querySelectorAll('h2')[1].id).toBe('toc-1')
    expect(o.toc.value.map(t => t.id)).toEqual(['toc-0', 'toc-1'])
  })

  it('标题为空时给占位文本，不在目录里留一行空白', () => {
    const o = setup('<h2></h2>')
    o.buildToc()

    // 空行会让用户不知道那是什么，也点不明白
    expect(o.toc.value[0].text).toBe('章节 1')
  })

  it('构建后立即算出高亮，不等第一次滚动', () => {
    const o = setup('<h2>一</h2><h2>二</h2>')
    o.buildToc()

    // 留空意味着刚打开文档时一项都不高亮，
    // 用户失去「我在哪一节」的定位
    expect(o.activeToc.value).not.toBe('')
    expect(o.toc.value.map(t => t.id)).toContain(o.activeToc.value)
  })

  it('正文无标题时目录为空且不抛异常', () => {
    const o = setup('<p>只有正文</p>')

    expect(() => o.buildToc()).not.toThrow()
    expect(o.toc.value).toEqual([])
    expect(o.activeToc.value).toBe('')
  })

  it('容器尚未挂载（ref 为 null）时安静返回', () => {
    const articleContentRef = ref<HTMLElement | null>(null)
    const mainContainer = ref<HTMLElement | null>(null)
    const o = useDocOutline({ articleContentRef, mainContainer })

    // v-html 还没渲染完就调用是正常时序，不该炸
    expect(() => o.buildToc()).not.toThrow()
    expect(o.toc.value).toEqual([])
  })

  it('重复构建不累加——切换文档时目录必须整个换掉', () => {
    const o = setup('<h2>一</h2><h2>二</h2>')
    o.buildToc()
    expect(o.toc.value).toHaveLength(2)

    o.articleContentRef.value!.innerHTML = '<h2>新文档</h2>'
    o.buildToc()

    // 累加的话，切到新文档后目录里还挂着上一篇的章节
    expect(o.toc.value).toHaveLength(1)
    expect(o.toc.value[0].text).toBe('新文档')
  })
})

describe('代码块语言标注', () => {
  it('从 language-* class 提取语言并大写', () => {
    const o = setup('<pre><code class="language-bash">ls</code></pre>')
    o.decorateArticleContent()

    expect(document.querySelector('pre')!.getAttribute('data-language')).toBe('BASH')
  })

  it('无语言标记时回落 TEXT，而不是留空属性', () => {
    const o = setup('<pre><code>纯文本</code></pre>')
    o.decorateArticleContent()

    // 空角标会在代码块右上角显示一块没有文字的色块，看起来像渲染坏了
    expect(document.querySelector('pre')!.getAttribute('data-language')).toBe('TEXT')
  })

  it('多个代码块各自标注，互不影响', () => {
    const o = setup(
      '<pre><code class="language-sql">SELECT 1</code></pre>' +
      '<pre><code class="language-yaml">a: 1</code></pre>'
    )
    o.decorateArticleContent()

    const langs = [...document.querySelectorAll('pre')].map(p => p.getAttribute('data-language'))
    expect(langs).toEqual(['SQL', 'YAML'])
  })
})

describe('scroll spy（updateActiveToc）', () => {
  /** 造 3 个标题并分别指定 top */
  function withTops(tops: number[], containerTop = 0) {
    const o = setup(tops.map((_, i) => `<h2>章节 ${i}</h2>`).join(''), containerTop)
    o.buildToc()
    const heads = document.querySelectorAll('h2')
    tops.forEach((t, i) => stubTop(heads[i], t))
    return o
  }

  it('取最后一个位于阈值上方的章节', () => {
    // 阈值 = containerTop(0) + 96。前两个已划过顶部，第三个还在下面
    const o = withTops([-200, 50, 500])
    o.updateActiveToc()

    // 用「最后一个满足条件的」而非第一个：向上滚动时已划过的章节会累积
    expect(o.activeToc.value).toBe('toc-1')
  })

  it('全部章节都在阈值下方时保持第一节高亮', () => {
    const o = withTops([300, 500, 700])
    o.updateActiveToc()

    // 文档顶部尚未滚动，此时高亮第一节是符合直觉的
    expect(o.activeToc.value).toBe('toc-0')
  })

  it('滚到底部时高亮最后一节', () => {
    const o = withTops([-900, -600, -300])
    o.updateActiveToc()

    expect(o.activeToc.value).toBe('toc-2')
  })

  it('恰好等于阈值时算作已进入该节（边界含等号）', () => {
    // ⚠️ 必须让「命中」与「回落默认」是两个不同的答案，否则测不出边界。
    // 若用 [96, 500] 断言 toc-0，边界写成 < 时也会因为回落到第一节而通过——
    // 这正是初版的假绿：注入 <= → < 后它照样是绿的。
    // 这里让第 1 节远在上方（必中）、第 2 节恰好压线，
    // 含等号则答案是 toc-1，不含则停在 toc-0。
    const o = withTops([-500, 96, 800])
    o.updateActiveToc()

    expect(o.activeToc.value).toBe('toc-1')
  })

  it('96px 视差偏移确实生效——标题刚露头就该切换', () => {
    // 同上，得让两种实现给出不同答案：
    // 第 2 节 top=50，含 96 偏移时阈值 96 → 50<=96 命中 toc-1；
    // 偏移写成 0 时阈值 0 → 50>0 不命中，停在 toc-0。
    const o = withTops([-500, 50, 800])
    o.updateActiveToc()

    expect(o.activeToc.value).toBe('toc-1')
  })

  it('阈值随滚动容器位置浮动，而非写死 96', () => {
    // 容器本身距视口顶部 200px，阈值应为 296
    const o = withTops([-500, 250, 800], 200)
    o.updateActiveToc()

    // 第 2 节 top=250：阈值随容器浮动为 296 时命中 toc-1；
    // 若写死 96 则 250>96 不命中，停在 toc-0
    expect(o.activeToc.value).toBe('toc-1')
  })

  it('目录为空时不抛异常', () => {
    const o = setup('<p>无标题</p>')
    o.buildToc()

    expect(() => o.updateActiveToc()).not.toThrow()
    expect(o.activeToc.value).toBe('')
  })
})

describe('scrollToToc', () => {
  it('滚动到目标章节并立即高亮它', () => {
    const o = setup('<h2>一</h2><h2>二</h2>')
    o.buildToc()
    const target = document.getElementById('toc-1')!
    const spy = vi.fn()
    target.scrollIntoView = spy

    o.scrollToToc('toc-1')

    expect(spy).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    // 立即高亮而不等滚动事件：平滑滚动要几百毫秒，
    // 高亮慢半拍会让用户以为没点中
    expect(o.activeToc.value).toBe('toc-1')
  })

  it('目标不存在时安静忽略，不改高亮也不抛异常', () => {
    const o = setup('<h2>一</h2>')
    o.buildToc()
    const before = o.activeToc.value

    expect(() => o.scrollToToc('toc-999')).not.toThrow()
    expect(o.activeToc.value).toBe(before)
  })
})

describe('refreshAfterRender', () => {
  it('一次调用同时完成目录构建与代码块标注', () => {
    const o = setup('<h2>安装</h2><pre><code class="language-bash">ls</code></pre>')

    o.refreshAfterRender()

    // 合成一个入口是为了避免调用方漏掉其中一步——
    // 只 buildToc 则代码块没角标，只 decorate 则目录是空的，两者都不报错
    expect(o.toc.value).toHaveLength(1)
    expect(document.querySelector('pre')!.getAttribute('data-language')).toBe('BASH')
  })
})
