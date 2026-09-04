/**
 * KnowledgeDetail 目录（TOC）与 scroll spy 测试。
 *
 * ── 为什么这块值得测 ────────────────────────────────────────
 * 目录不是装饰：知识库文档动辄上千字，运维在故障中查 SOP 时是靠目录
 * 直接跳到「回滚步骤」那一节的。它有三个静默失败模式：
 *
 * 1. **buildToc 依赖 v-html 渲染后的真实 DOM**。正文经 safeMarkdown 净化，
 *    若净化白名单里没有 h2/h3，标题会被剥成纯文本——目录变成空的，
 *    而正文看上去完全正常，没有任何报错；
 * 2. **锚点 id 是 buildToc 现场写上去的**（`toc-${idx}`）。不写的话
 *    `scrollToToc` 里的 `getElementById` 拿到 null，点目录毫无反应；
 * 3. **scroll spy 的边界**：取「最后一个处于视口上方」的章节。
 *    条件写成 `< top` 而非 `<= top`，或者忘了 96px 视差偏移，
 *    高亮就会慢一节或跳一节——用户看着目录高亮和正文对不上。
 *
 * ── jsdom 没有布局引擎 ──────────────────────────────────────
 * `getBoundingClientRect` 在 jsdom 里恒返回全 0。scroll spy 完全依赖它，
 * 因此必须**显式为每个标题造几何数据**，否则所有元素 top 都是 0，
 * 测试会「碰巧通过」而完全没有验证到位置判断逻辑。
 * 这里用 spyOn 按元素 id 给出模拟的 top 值。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const notifyMock = vi.hoisted(() => ({
  notify: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  handleServerError: vi.fn(),
}))
vi.mock('@/utils/notify', () => notifyMock)

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('element-plus')
  return {
    ...actual,
    ElMessageBox: { confirm: vi.fn(() => Promise.resolve()), prompt: vi.fn() },
  }
})

const storeMock = vi.hoisted(() => ({
  detail: null as unknown,
  detailStatus: 'success',
  versions: [] as unknown[],
  loadDetail: vi.fn(),
  loadVersions: vi.fn(),
  publishDoc: vi.fn(),
  deprecateDoc: vi.fn(),
  undoDeprecate: vi.fn(),
  restoreVersion: vi.fn(),
  purgeDoc: vi.fn(),
  listDocs: vi.fn(),
}))
vi.mock('@/stores/knowledge', () => ({ useKnowledgeStore: () => storeMock }))

// 只替换会发请求的函数，保留 statusLabel 等纯展示函数——
// 整模块 mock 会让模板渲染直接抛错（详见 KnowledgeDetail.actions.test.ts）
vi.mock('@/api/knowledge', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/knowledge')
  return {
    ...actual,
    fetchKnowledgeDocs: vi.fn(() => Promise.resolve({ docs: [], total: 0 })),
    fetchDocVersions: vi.fn(() => Promise.resolve([])),
    diffDocVersion: vi.fn(() => Promise.resolve({ additions: [], deletions: [] })),
  }
})

import KnowledgeDetail from '../KnowledgeDetail.vue'

const MARKDOWN = [
  '# 文档标题',
  '',
  '正文引子。',
  '',
  '## 故障现象',
  '主从延迟持续升高。',
  '',
  '### 判定标准',
  'seconds_behind_master > 60。',
  '',
  '## 回滚步骤',
  '先停写再切换。',
  '',
  '```bash',
  'kubectl rollout undo deploy/order',
  '```',
].join('\n')

const makeDoc = (over: Record<string, unknown> = {}) => ({
  id: 7,
  title: 'Redis 主从延迟处置 SOP',
  content: MARKDOWN,
  status: 'PUBLISHED',
  version: 3,
  category: '中间件',
  tags: [],
  indexStatus: 'SUCCESS',
  createTime: '2026-08-24T10:00:00',
  updateTime: '2026-08-24T10:00:00',
  ...over,
})

let router: Router

async function mountDetail(doc: Record<string, unknown> | null = makeDoc()) {
  storeMock.detail = doc
  storeMock.detailStatus = 'success'
  storeMock.versions = []

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge', component: defineComponent({ template: '<div/>' }) },
      { path: '/knowledge/:id', component: KnowledgeDetail },
      { path: '/knowledge/:id/edit', component: defineComponent({ template: '<div/>' }) },
    ],
  })
  await router.push('/knowledge/7')
  await router.isReady()

  const wrapper = mount(KnowledgeDetail, {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: {
        'el-drawer': true, 'el-dialog': true, 'el-tag': true,
        'el-tooltip': true, 'el-skeleton': true,
        CollapsiblePanel: { template: '<div><slot /></div>' },
        DataStateBoundary: { template: '<div><slot /></div>' },
        RelativeTime: true,
      },
      directives: { permission: {} },
    },
  })
  // safeHtml 由 watch 异步写入，之后还要等 nextTick 才会 buildToc
  await flushPromises()
  await wrapper.vm.$nextTick()
  return wrapper
}

type TocVm = {
  toc: Array<{ id: string; text: string; level: number }>
  activeToc: string
  updateActiveToc: () => void
  scrollToToc: (id: string) => void
}
const vmOf = (w: VueWrapper) => w.vm as unknown as TocVm

/**
 * 为标题元素造几何数据。
 * jsdom 的 getBoundingClientRect 恒返回 0，不 mock 的话位置判断形同虚设。
 */
function stubGeometry(tops: Record<string, number>, containerTop = 0) {
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: Element
  ) {
    const id = (this as HTMLElement).id
    const top = id && id in tops ? tops[id] : containerTop
    return { top, bottom: top + 20, left: 0, right: 0, width: 100, height: 20, x: 0, y: top, toJSON: () => ({}) } as DOMRect
  })
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  storeMock.loadDetail.mockResolvedValue(undefined)
  storeMock.loadVersions.mockResolvedValue(undefined)
})

describe('buildToc', () => {
  it('从渲染后的正文中提取 h2/h3，保留层级', async () => {
    const w = await mountDetail()
    const toc = vmOf(w).toc

    // h1 是文档标题，不进目录（它已经显示在页头）
    expect(toc.map(t => t.text)).toEqual(['故障现象', '判定标准', '回滚步骤'])
    expect(toc.map(t => t.level)).toEqual([2, 3, 2])
  })

  it('给每个标题写上锚点 id——不写的话点目录毫无反应', async () => {
    const w = await mountDetail()

    // scrollToToc 靠 getElementById 定位，id 是 buildToc 现场加上去的
    for (const item of vmOf(w).toc) {
      expect(document.getElementById(item.id)).not.toBeNull()
    }
    expect(vmOf(w).toc.map(t => t.id)).toEqual(['toc-0', 'toc-1', 'toc-2'])
  })

  it('构建后立即算一次高亮，不留空值', async () => {
    const w = await mountDetail()

    // buildToc 末尾会先置 items[0] 再调 updateActiveToc 现算一次。
    // 这里不断言具体是哪一节——jsdom 无布局引擎，getBoundingClientRect
    // 恒为 0，所有标题都被判定为「已在阈值上方」，结果必然是最后一节。
    // 真正的位置判断由下面 scroll spy 那组用例在造好几何数据后验证。
    // 此处只守住「构建完成后 activeToc 一定是目录中的某一项」——
    // 留空会让目录一个高亮都没有，看起来像坏了。
    const ids = vmOf(w).toc.map(t => t.id)
    expect(ids).toContain(vmOf(w).activeToc)
  })

  it('正文无标题时目录为空，不报错', async () => {
    const w = await mountDetail(makeDoc({ content: '只有一段纯文本，没有任何标题。' }))

    expect(vmOf(w).toc).toEqual([])
    // 空目录不该让 activeToc 变成 undefined，那会让模板比较出意外结果
    expect(vmOf(w).activeToc).toBe('')
  })

  it('正文为空时不渲染也不抛异常', async () => {
    const w = await mountDetail(makeDoc({ content: '' }))
    expect(vmOf(w).toc).toEqual([])
  })
})

describe('代码块语言标注', () => {
  it('从 language-* class 提取语言并大写写入 data-language', async () => {
    const w = await mountDetail()
    const pre = w.element.querySelector('pre')

    expect(pre?.getAttribute('data-language')).toBe('BASH')
  })

  it('无语言标记时回落 TEXT，而不是留空属性', async () => {
    const w = await mountDetail(
      makeDoc({ content: '## 标题\n\n```\nplain code\n```' })
    )
    const pre = w.element.querySelector('pre')

    // 留空的话 CSS 的 ::before 会显示成一个空标签框，看起来像渲染坏了
    expect(pre?.getAttribute('data-language')).toBe('TEXT')
  })
})

describe('scroll spy（updateActiveToc）', () => {
  it('取最后一个位于阈值上方的章节', async () => {
    const w = await mountDetail()

    // 阈值 = 容器 top(0) + 96。toc-0/toc-1 已滚过，toc-2 还在下方
    stubGeometry({ 'toc-0': -200, 'toc-1': 20, 'toc-2': 500 })
    vmOf(w).updateActiveToc()

    expect(vmOf(w).activeToc).toBe('toc-1')
  })

  it('全部章节都在阈值下方时保持第一节高亮', async () => {
    const w = await mountDetail()

    // 页面刚打开、还没滚动：不能让 activeToc 变空，
    // 否则目录上一个高亮都没有，看起来像坏了
    stubGeometry({ 'toc-0': 300, 'toc-1': 600, 'toc-2': 900 })
    vmOf(w).updateActiveToc()

    expect(vmOf(w).activeToc).toBe('toc-0')
  })

  it('滚到底部时高亮最后一节', async () => {
    const w = await mountDetail()

    stubGeometry({ 'toc-0': -900, 'toc-1': -600, 'toc-2': -300 })
    vmOf(w).updateActiveToc()

    expect(vmOf(w).activeToc).toBe('toc-2')
  })

  it('恰好等于阈值时算作已进入该节（边界含等号）', async () => {
    const w = await mountDetail()

    // 写成 `< top` 而非 `<= top`，高亮会慢一节——
    // 用户明明看到标题贴在顶部了，目录却还停在上一节
    stubGeometry({ 'toc-0': -100, 'toc-1': 96, 'toc-2': 500 })
    vmOf(w).updateActiveToc()

    expect(vmOf(w).activeToc).toBe('toc-1')
  })

  it('阈值含 96px 视差偏移——标题刚露头就该切换', async () => {
    const w = await mountDetail()

    // top=95 < 96：已过阈值。若忘了 +96 偏移（阈值变成 0），
    // 这里会判定为「还没到」而高亮上一节
    stubGeometry({ 'toc-0': -100, 'toc-1': 95, 'toc-2': 500 })
    vmOf(w).updateActiveToc()

    expect(vmOf(w).activeToc).toBe('toc-1')
  })

  it('目录为空时不抛异常', async () => {
    const w = await mountDetail(makeDoc({ content: '没有标题的正文' }))

    expect(() => vmOf(w).updateActiveToc()).not.toThrow()
    expect(vmOf(w).activeToc).toBe('')
  })
})

describe('scrollToToc', () => {
  it('滚动到目标章节并立即高亮它', async () => {
    const w = await mountDetail()
    const target = document.getElementById('toc-2')!
    const scrollSpy = vi.fn()
    target.scrollIntoView = scrollSpy

    vmOf(w).scrollToToc('toc-2')

    expect(scrollSpy).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    // 立即置高亮而不等 scroll 事件：平滑滚动期间用户会看到目录延迟响应
    expect(vmOf(w).activeToc).toBe('toc-2')
  })

  it('目标不存在时安静忽略，不改高亮也不抛异常', async () => {
    const w = await mountDetail()
    const before = vmOf(w).activeToc

    expect(() => vmOf(w).scrollToToc('toc-999')).not.toThrow()
    expect(vmOf(w).activeToc).toBe(before)
  })
})
