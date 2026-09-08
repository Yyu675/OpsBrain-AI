/**
 * 知识文档编辑器冒烟测试。
 *
 * ── 为什么在拆分过程中特别需要这一组 ──────────────────────────
 * 前面几步把内容处理逻辑抽到了 `utils/editorContent`，纯函数各有测试。
 * 但**纯函数全绿 ≠ 页面还能打开**——第 22 轮拆 TicketDetail 时就吃过这个亏：
 * 删模板时多删了一个闭合标签，`vue-tsc` 只报一串「变量未使用」这类
 * 误导性症状，差点顺手删掉那些变量（那会真正毁掉页面）。
 * 最后是组件测试报 `Element is missing end tag` 才定位到病因。
 *
 * 所以这组测试的价值不在断言多细，而在**它会在模板结构被改坏时立刻红**。
 *
 * ── 覆盖的三件事 ──────────────────────────────────────────────
 * 1. 新建 / 编辑两种模式都能挂载，且模板结构完整；
 * 2. 编辑模式会去加载文档（拆分不能把数据加载链路弄断）；
 * 3. 卸载时清掉自动暂存定时器——不清会在离开后继续往
 *    sessionStorage 写，每开一次编辑器泄漏一个 interval。
 *
 * ── 为什么大量 stub ──────────────────────────────────────────
 * 编辑器依赖 Quill 富文本、md-editor-v3、Turndown 与知识树侧栏，
 * 它们各自有独立测试。这里 stub 掉是为了让失败信号指向
 * 「编辑器自己的结构坏了」，而不是某个第三方组件在 jsdom 里跑不起来。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

const store = vi.hoisted(() => ({
  detail: null as unknown,
  detailStatus: 'idle' as string,
  hotTags: [] as string[],
  loadDetail: vi.fn(),
  loadCategories: vi.fn(),
  loadHotTags: vi.fn(),
  createDoc: vi.fn(),
  updateDoc: vi.fn(),
  publishDoc: vi.fn(),
}))
vi.mock('@/stores/knowledge', () => ({ useKnowledgeStore: () => store }))

const api = vi.hoisted(() => ({
  fetchKnowledgeCategories: vi.fn(),
  fetchKnowledgeTags: vi.fn(),
  createKnowledgeCategory: vi.fn(),
}))
vi.mock('@/api/knowledge', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/knowledge')
  return { ...actual, ...api }
})

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

import KnowledgeEditor from '../KnowledgeEditor.vue'

const Stub = defineComponent({
  props: { modelValue: { type: String, default: '' } },
  setup: (_p, { slots }) => () => h('div', { class: 'stub' }, slots.default?.({ toggle: () => {} })),
})

async function mountEditor(routePath: string): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge/editor/:id', component: KnowledgeEditor },
      { path: '/knowledge/:id', component: { template: '<div />' } },
    ],
  })
  await router.push(routePath)
  await router.isReady()

  const wrapper = mount(KnowledgeEditor, {
    global: {
      plugins: [router],
      stubs: {
        KnowledgeTreeSidebar: Stub,
        CollapsiblePanel: Stub,
        CollapseToggle: true,
        AppBreadcrumb: true,
        MdEditor: Stub,
        KnowledgeRichEditor: Stub,
        Teleport: true,
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('KnowledgeEditor 冒烟', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    store.detail = null
    store.detailStatus = 'idle'
    store.hotTags = ['k8s', 'mysql']
    store.loadDetail.mockResolvedValue(null)
    store.loadCategories.mockResolvedValue([])
    store.loadHotTags.mockResolvedValue([])
    api.fetchKnowledgeCategories.mockResolvedValue([])
    api.fetchKnowledgeTags.mockResolvedValue([])
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('新建模式能挂载，模板结构完整', async () => {
    const { wrapper } = await mountEditor('/knowledge/editor/new')

    // 这一条就是「模板没被改坏」的探针：
    // 少一个闭合标签时 Vue 编译期就会抛错，mount 直接失败
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.html()).not.toBe('')

    wrapper.unmount()
  })

  it('新建模式不去拉文档详情 —— 拉了会对 id=new 发一次必然 404 的请求', async () => {
    const { wrapper } = await mountEditor('/knowledge/editor/new')

    expect(store.loadDetail).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('编辑模式会加载对应文档 —— 拆分不能把数据加载链路弄断', async () => {
    store.loadDetail.mockResolvedValue({
      id: 42, title: '已有文档', category: 'K8s', summary: '',
      tags: ['k8s'], content: '<p>正文</p>', version: 3, status: 'PUBLISHED',
    })

    const { wrapper } = await mountEditor('/knowledge/editor/42')

    expect(store.loadDetail).toHaveBeenCalledWith(42)

    wrapper.unmount()
  })

  it('挂载时加载分类与标签（右侧设置面板依赖它们）', async () => {
    const { wrapper } = await mountEditor('/knowledge/editor/new')

    expect(api.fetchKnowledgeCategories).toHaveBeenCalled()
    expect(api.fetchKnowledgeTags).toHaveBeenCalled()

    wrapper.unmount()
  })

  it('卸载后不再自动暂存 —— 不清定时器会让每开一次编辑器泄漏一个 interval', async () => {
    vi.useFakeTimers()
    try {
      const { wrapper } = await mountEditor('/knowledge/editor/new')
      const clearSpy = vi.spyOn(globalThis, 'clearInterval')

      wrapper.unmount()

      // 卸载必须清掉 startAutoSave 起的 3 秒定时器。
      // 漏清的话，用户离开后它还在往 sessionStorage 写，
      // 而那时 formData 已经是被销毁组件的残留引用
      expect(clearSpy).toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('文档加载失败时页面仍能渲染，不白屏', async () => {
    store.loadDetail.mockResolvedValue(null)
    store.detailStatus = 'notFound'

    const { wrapper } = await mountEditor('/knowledge/editor/999')

    // 后端挂了或文档被删时，编辑器应该还在（哪怕是空表单），
    // 而不是整页崩掉——用户至少还能看到自己刚才写的草稿
    expect(wrapper.exists()).toBe(true)

    wrapper.unmount()
  })
})
