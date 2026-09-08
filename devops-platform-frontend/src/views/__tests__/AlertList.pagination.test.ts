/**
 * AlertList —— **分页状态与 URL 的一致性**测试。
 *
 * ── 为什么第一刀切在这里 ──────────────────────────────────────
 * `AlertList.vue`（689 行）此前零测试。上一轮的结论是
 * 「同构文件是最高命中率的排查方向」——本页与 `TicketList` 同构，
 * 那边踩过的坑在这边极可能原样存在。
 *
 * 对比下来，本页有一处 `TicketList` 没有的结构：
 * **页码被两个独立的 ref 各存了一份**。
 *
 * ```
 * const pageRef = ref(1)                    // 传给 Query，进 queryKey
 * useUrlFilters([... { ref: pageRef, key: 'page' } ...])   // URL 恢复写这个
 *
 * const pagination = useServerPaginationFrom(...)          // 内部 currentPage = ref(1)
 * const { currentPage } = pagination                       // 模板分页条读这个
 * watch(currentPage, (p) => { pageRef.value = p })         // 只有单向同步
 * ```
 *
 * `useServerPaginationFrom` 内部自己 `const currentPage = ref(1)`
 * （useServerPagination.ts:107），它与 `pageRef` 是**两个不同的 ref**。
 * 中间只有一条 `currentPage → pageRef` 的单向 watch。
 *
 * ── 因此存在一个方向是断的 ────────────────────────────────────
 * `useUrlFilters` 在 setup 阶段调用 `applyFromUrl()`，把 URL 里的
 * `?page=3` 写进 **`pageRef`**。但 `pagination.currentPage` 没人写，
 * 它仍是初始值 **1**。
 *
 * 于是打开 `/alerts?page=3` 时：
 *   - 数据层按第 3 页拉（pageRef=3 进了 queryKey）→ 列表显示第 3 页的告警
 *   - 分页条按第 1 页高亮（currentPage=1）→ 底部「1」是选中态
 *
 * **列表内容与分页条指示不一致。** 用户想回第 1 页，点「1」不会有任何反应
 * （goToPage(1) 时 currentPage 本来就是 1，watch 不触发，Query 不重拉），
 * 看起来就是「分页点了没用」。
 *
 * 本文件先用测试**确认这件事是否真的发生**，再决定要不要动产品代码。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: vi.fn() },
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

// 只桩网络层，让 Query / 分页 / URL 三者的真实联动都在被测范围内。
// 桩掉 useAlertListQuery 的话，本文件要验的「pageRef 有没有进 queryKey」
// 就整个被绕过了
const api = vi.hoisted(() => ({
  fetchAlerts: vi.fn(),
  fetchAlertById: vi.fn(),
  acknowledgeAlert: vi.fn(),
  resolveAlert: vi.fn(),
}))
vi.mock('@/api/alerts', () => api)

import AlertList from '../AlertList.vue'

const alert = (id: number) => ({
  id,
  alertName: `告警-${id}`,
  level: 'P1',
  status: 'FIRING',
  service: 'order-service',
  summary: '摘要',
  ticketId: null,
  firstOccurredAt: '2026-08-26 09:00:00',
  lastOccurredAt: '2026-08-26 09:05:00',
  occurrenceCount: 1,
})

let router: Router

const mountPage = async (url = '/alerts', total = 35) => {
  api.fetchAlerts.mockImplementation((params: { page: number; size: number }) => {
    const size = params.size ?? 10
    return Promise.resolve({
      alerts: [alert(params.page * 100), alert(params.page * 100 + 1)],
      total,
      totalPages: Math.ceil(total / size),
      currentPage: params.page,
    })
  })

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/alerts', component: AlertList },
      { path: '/other', component: defineComponent({ template: '<div/>' }) },
    ],
  })
  await router.push(url)
  await router.isReady()

  const wrapper = mount(AlertList, {
    global: {
      plugins: [
        router,
        // 每个用例一个全新 QueryClient：共用会让上一例的缓存把下一例的
        // 首次请求直接命中，fetchAlerts 不被调用，断言全部错位
        [VueQueryPlugin, {
          queryClient: new QueryClient({
            defaultOptions: { queries: { retry: false, staleTime: 0, gcTime: 0 } },
          }),
        }],
      ],
      stubs: {
        DataStateBoundary: { template: '<div><slot /></div>' },
        RelativeTime: true,
        // el-table 整体桩掉且**不渲染默认插槽**：本文件测的是分页与 URL，
        // 表格行不在范围内。若让它渲染，el-table-column 的作用域插槽
        // 拿不到 row，会抛 "Cannot destructure property 'row' of 'undefined'"——
        // 9 个用例会以一个与被测逻辑完全无关的原因集体失败
        'el-table': { template: '<div class="table-stub" />' },
        'el-table-column': true,
        'el-tag': true,
        'el-button': true,
        'el-select': true,
        'el-option': true,
        ServerPagination: {
          name: 'ServerPagination',
          props: ['currentPage', 'totalPages', 'total', 'pageStart', 'pageEnd', 'pageNumbers'],
          template: '<div class="pagination-stub" :data-current="currentPage" />',
        },
      },
    },
  })
  await flushPromises()
  return wrapper
}

/** 分页条实际高亮的页码——模板绑的是 pagination.currentPage */
const shownPage = (w: VueWrapper) =>
  Number(w.findComponent({ name: 'ServerPagination' }).props('currentPage'))

/** 数据层实际请求的页码——来自最后一次 fetchAlerts 调用 */
const requestedPage = () => {
  const calls = api.fetchAlerts.mock.calls
  return (calls[calls.length - 1]?.[0] as { page: number }).page
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('从 URL 恢复页码', () => {
  it('打开 /alerts?page=3 时，数据层与分页条必须指向同一页', async () => {
    // ── 本文件的核心用例 ────────────────────────────────────
    // 值班同事把「?status=FIRING&page=3」的链接甩过来，是本页
    // URL 状态存在的全部理由。恢复出来的两个页码若不一致，
    // 这个功能就是坏的
    const w = await mountPage('/alerts?page=3')

    expect(requestedPage(), '数据层应按第 3 页拉取').toBe(3)
    expect(shownPage(w), '分页条也应高亮第 3 页').toBe(3)
  })

  it('恢复页码后点「回到第 1 页」确实会重新拉取', async () => {
    // 上一条若不成立，这一条会以更直观的方式失败：
    // currentPage 本来就是 1，goToPage(1) 不产生变化，
    // watch 不触发、Query 不重拉——用户看到的是「点了没反应」
    const w = await mountPage('/alerts?page=3')
    api.fetchAlerts.mockClear()

    w.findComponent({ name: 'ServerPagination' }).vm.$emit('page-change', 1)
    await flushPromises()

    expect(api.fetchAlerts).toHaveBeenCalled()
    expect(requestedPage()).toBe(1)
  })

  it('URL 无 page 参数时从第 1 页开始', async () => {
    const w = await mountPage('/alerts')

    expect(requestedPage()).toBe(1)
    expect(shownPage(w)).toBe(1)
  })

  it('URL 里的 size 同样被恢复，并让分页区间按它计算', async () => {
    // size 目前没有 UI 入口，但 `?size=20` 是真实可达的：
    // 它进了 useUrlFilters 的清单，用户手改 URL 或从收藏夹进来都会带上。
    //
    // 这条断言是补上来的——注入验证时发现「只共享 page 不共享 size」
    // 能骗过原有 8 例：pageSize 若各存一份，请求按 20 条拉，
    // 分页条却按 10 条算区间，「显示 1-10 共 35 条」与实际内容对不上
    const w = await mountPage('/alerts?size=20', 35)

    expect(api.fetchAlerts).toHaveBeenCalledWith(expect.objectContaining({ size: 20 }))

    const pager = w.findComponent({ name: 'ServerPagination' })
    expect(pager.props('pageEnd'), '区间结束应按每页 20 计算').toBe(20)
    expect(pager.props('totalPages'), '35 条按 20 每页应为 2 页').toBe(2)
  })

  it('URL 里的筛选条件同样被恢复并进入请求参数', async () => {
    await mountPage('/alerts?status=FIRING&level=P0')

    expect(api.fetchAlerts).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'FIRING', level: 'P0' })
    )
  })
})

describe('翻页', () => {
  it('点下一页：数据层与分页条同步前进', async () => {
    const w = await mountPage('/alerts')

    w.findComponent({ name: 'ServerPagination' }).vm.$emit('page-change', 2)
    await flushPromises()

    expect(requestedPage()).toBe(2)
    expect(shownPage(w)).toBe(2)
  })

  it('页码变化写回 URL，刷新后不丢失', async () => {
    const w = await mountPage('/alerts')

    w.findComponent({ name: 'ServerPagination' }).vm.$emit('page-change', 2)
    await flushPromises()

    expect(router.currentRoute.value.query.page).toBe('2')
  })

  it('超出总页数的页码不触发请求——否则会拉回一个空列表', async () => {
    // 总数 35 / 每页 10 = 4 页。第 99 页是用户手改 URL 或
    // 数据被删后从收藏夹进来的典型情况
    const w = await mountPage('/alerts')
    api.fetchAlerts.mockClear()

    w.findComponent({ name: 'ServerPagination' }).vm.$emit('page-change', 99)
    await flushPromises()

    expect(api.fetchAlerts).not.toHaveBeenCalled()
  })
})

describe('筛选与页码的联动', () => {
  it('改筛选后回到第 1 页——否则会停在按新条件不存在的页上', async () => {
    const w = await mountPage('/alerts?page=3')
    await flushPromises()

    const vm = w.vm as unknown as { statusFilter: string; resetPageOnFilterChange: () => void }
    vm.statusFilter = 'RESOLVED'
    vm.resetPageOnFilterChange()
    await flushPromises()

    expect(requestedPage(), '换筛选条件后应回到第 1 页').toBe(1)
    expect(shownPage(w)).toBe(1)
  })

  it('清除筛选同样复位页码', async () => {
    const w = await mountPage('/alerts?status=FIRING&page=3')
    await flushPromises()

    const vm = w.vm as unknown as { clearFilters: () => void }
    vm.clearFilters()
    await flushPromises()

    expect(requestedPage()).toBe(1)
    expect(shownPage(w)).toBe(1)
  })
})
