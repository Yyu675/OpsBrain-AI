/**
 * KnowledgeBase —— **筛选状态写回 URL 的生命周期**测试。
 *
 * ── 为什么先写这一个而不是整页冒烟 ────────────────────────────
 * `KnowledgeBase.vue`（1337 行）是本仓**最大的零测试文件**。
 * 从零开始补，第一刀应该切在「有具体可疑点」的地方，而不是先铺一层
 * 泛泛的渲染断言——后者容易写成一堆「元素存在」的假绿。
 *
 * 本页的可疑点在这里：
 *
 * ```
 * const applySearch = debounce(..., 300)
 * onBeforeUnmount(() => applySearch.flush())      // ← 有清理
 *
 * const syncUrl = debounce(() => router.replace({ query }), 200)
 * watch([...七个筛选项], syncUrl)                  // ← 没有任何清理
 * ```
 *
 * 同一个文件里，一个防抖有卸载清理、另一个没有。
 * `TicketList.vue` 的同名 `applySearch` 也有 `flush()`。
 * 这种「同一模式在相邻两处不一致」通常不是设计，是漏了。
 *
 * ── 漏掉的后果是什么 ──────────────────────────────────────────
 * `syncUrl` 的回调里是 `router.replace({ query })`。若组件卸载时
 * 定时器还挂着，200ms 后它照样会执行——**而那时用户已经在别的页面了**。
 * `router.replace` 用当前路由做基准，于是新页面的 URL 被塞上
 * 知识库的筛选参数（`?cat=K8S&tag=xxx`）。
 *
 * 本文件先用测试**确认这件事是否真的发生**，再决定要不要动产品代码。
 * （拿到确切信息前不改产品代码——本会话已多次遇到「测试报错但错在测试」。）
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: vi.fn(), prompt: vi.fn() },
  ElMessage: Object.assign(vi.fn(), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
}))

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

// 只桩接口，不桩 statusLabel 等纯函数——整模块 mock 会把它们一并抹掉，
// 模板里调用时报 "is not a function"，与被测逻辑毫无关系
vi.mock('@/api/knowledge', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/knowledge')
  return {
    ...actual,
    fetchKnowledgeTags: vi.fn().mockResolvedValue([]),
    createKnowledgeTag: vi.fn(),
    updateKnowledgeTag: vi.fn(),
    deleteKnowledgeTag: vi.fn(),
    mergeKnowledgeTag: vi.fn(),
  }
})

const storeStub = vi.hoisted(() => ({
  list: [] as unknown[],
  categories: [] as unknown[],
  hotTags: [] as string[],
  loading: false,
  error: null as unknown,
  totalPages: 1,
  currentPage: 1,
  total: 0,
  loadList: vi.fn().mockResolvedValue(undefined),
  loadCategories: vi.fn().mockResolvedValue(undefined),
  loadHotTags: vi.fn().mockResolvedValue(undefined),
  goToPage: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/stores/knowledge', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/knowledge')
  return { ...actual, useKnowledgeStore: () => storeStub }
})

import KnowledgeBase from '../KnowledgeBase.vue'

const OTHER = defineComponent({ template: '<div class="other-page" />' })

let router: Router

const mountPage = async (query = '') => {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge', component: KnowledgeBase },
      { path: '/tickets', component: OTHER },
    ],
  })
  await router.push('/knowledge' + query)
  await router.isReady()

  const wrapper = mount(KnowledgeBase, {
    global: {
      plugins: [router],
      stubs: {
        DataStateBoundary: { template: '<div><slot /></div>' },
        CollapsiblePanel: { template: '<div><slot /></div>' },
        CollapseToggle: true,
        RailButton: true,
        RelativeTime: true,
      },
    },
  })
  await wrapper.vm.$nextTick()
  return wrapper
}

type Vm = {
  activeCategory: string | null
  activeTag: string | null
  activeStatus: string
  viewMode: string
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('筛选状态写回 URL', () => {
  it('改动筛选项后（防抖 200ms）写回 query', async () => {
    const w = await mountPage()
    const vm = w.vm as unknown as Vm

    vm.activeCategory = 'K8S'
    await w.vm.$nextTick()

    // 防抖未到点前不该导航——否则每敲一个字都会产生一条历史记录。
    //
    // 断言必须跨过防抖窗口的**中段**（这里取 150ms，窗口是 200ms）：
    // 只在 0ms 处断言的话，把 debounce 的 wait 改成 0 也照样通过
    // （setTimeout(…, 0) 仍是异步，同步检查看不到）。注入验证时正是这条漏了网
    await vi.advanceTimersByTimeAsync(150)
    expect(router.currentRoute.value.query.cat).toBeUndefined()

    await vi.advanceTimersByTimeAsync(100)
    expect(router.currentRoute.value.query.cat).toBe('K8S')
  })

  it('多项同时变化只写一次，不产生中间态 URL', async () => {
    const w = await mountPage()
    const vm = w.vm as unknown as Vm
    const spy = vi.spyOn(router, 'replace')

    vm.activeCategory = 'K8S'
    vm.activeTag = '网络'
    vm.activeStatus = 'PUBLISHED'
    await w.vm.$nextTick()
    await vi.advanceTimersByTimeAsync(250)

    expect(spy).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.query).toMatchObject({
      cat: 'K8S', tag: '网络', status: 'PUBLISHED',
    })
  })

  it('默认值不写进 URL——地址栏只该出现用户真正改过的项', async () => {
    const w = await mountPage()
    const vm = w.vm as unknown as Vm

    vm.activeCategory = 'K8S'
    await w.vm.$nextTick()
    await vi.advanceTimersByTimeAsync(250)

    const q = router.currentRoute.value.query
    expect(q.cat).toBe('K8S')
    // 排序仍是默认的 UPDATED_DESC、视图仍是默认的 list，都不该出现
    expect(q.sort).toBeUndefined()
    expect(q.view).toBeUndefined()
  })
})

describe('组件卸载后不得再改 URL', () => {
  it('离开页面时未落定的 URL 同步不该污染下一个页面的地址栏', async () => {
    // ── 这是本文件的核心用例 ──────────────────────────────────
    // 场景：用户点了分类筛选，200ms 防抖还没到，就点了侧边栏跳去工单列表。
    //
    // 若 syncUrl 在卸载时没被 cancel，那个定时器仍会触发，
    // 里面的 router.replace({ query }) 会以**当前路由**为基准执行——
    // 而当前路由此时已经是 /tickets 了。
    //
    // 用户看到的：地址栏变成 /tickets?cat=K8S，
    // 工单列表按一个它根本不认识的参数刷新，或者干脆丢掉自己的筛选。
    const w = await mountPage()
    const vm = w.vm as unknown as Vm

    vm.activeCategory = 'K8S'
    await w.vm.$nextTick()

    // 防抖窗口内离开页面
    await router.push('/tickets')
    await flushPromises()

    // 断言对象是 router.replace 有没有被调用，而不是最终 URL 长什么样。
    //
    // 原因：`w.unmount()` 会把 router 的 currentRoute 重置回 '/'
    // （测试工具卸载 app 时 vue-router 自己做的），
    // 于是「断言 path 仍是 /tickets」永远不成立——那是 harness 的行为，
    // 与产品代码无关。第一版就是这么写的，失败信息指向「路径不对」，
    // 把真正的问题盖住了。
    const spy = vi.spyOn(router, 'replace')
    w.unmount()

    // 让原本的定时器有机会触发
    await vi.advanceTimersByTimeAsync(500)

    // 修复前这里会失败：syncUrl 的定时器在卸载后照样触发，
    // 带着知识库的筛选参数调用 replace，而当时的路由已经是 /tickets
    expect(spy).not.toHaveBeenCalled()
  })

  it('卸载后即使筛选 ref 仍被改动，也不再触发导航', async () => {
    // 防御第二种形态：watch 未随组件作用域停止。
    // script setup 里的 watch 本应自动停，但若 syncUrl 被
    // 某个模块级结构（如全局事件总线）持有，就会漏网
    const w = await mountPage()
    const vm = w.vm as unknown as Vm

    await router.push('/tickets')
    await flushPromises()
    w.unmount()

    const spy = vi.spyOn(router, 'replace')
    vm.activeTag = '存储'
    await vi.advanceTimersByTimeAsync(500)

    expect(spy).not.toHaveBeenCalled()
  })
})
