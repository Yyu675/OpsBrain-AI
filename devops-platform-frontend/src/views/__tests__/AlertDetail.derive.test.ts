/**
 * AlertDetail —— **派生展示逻辑**测试。
 *
 * ── 为什么切这一刀 ────────────────────────────────────────────
 * `AlertDetail.vue`（703 行）此前零测试。它的数据层用 TanStack Query，
 * 切换 id 自带竞态防护，`TicketDetail` 踩过的「切换工单不重置」那类坑
 * 在这里结构上就不存在——对比下来数据层是干净的。
 *
 * 风险集中在**派生展示**：处置时间线、持续时长、两个动作可用性判定。
 * 它们的共同特征是**算错了不会抛异常**，只会安静地显示错误的值：
 *
 * <ul>
 *   <li>时间线给未发生的节点编造时间 → 用户以为已经有人确认过了，
 *       没人跟进；MTTA 类指标同时失真；</li>
 *   <li>持续时长算错 → 这是判断「这个告警要不要升级」的主要依据；</li>
 *   <li>`canAcknowledge` / `canResolve` 判错 → 要么按钮该亮不亮
 *       （处置不了），要么该灰不灰（对已恢复的告警重复操作）。</li>
 * </ul>
 *
 * 静默的错误比崩溃更危险，所以这批逻辑值得单独覆盖。
 *
 * ── 时区这一条是重点 ──────────────────────────────────────────
 * `durationText` 必须走 `parseDate` 而不是 `new Date`：后端返回的
 * `LocalDateTime` 没有时区后缀，`new Date('2026-08-26 09:00:00')`
 * 会按**浏览器本地时区**解析，而 `Date.now()` 是绝对时刻，
 * 两者混算在非服务器时区下能差出十几个小时——
 * 一个刚触发 5 分钟的告警可能显示成「8 小时」。
 * 下面用固定时区的构造数据把这条钉死。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
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

const api = vi.hoisted(() => ({
  fetchAlerts: vi.fn(),
  fetchAlertById: vi.fn(),
  acknowledgeAlert: vi.fn(),
  resolveAlert: vi.fn(),
}))
vi.mock('@/api/alerts', () => api)

import AlertDetail from '../AlertDetail.vue'

/**
 * 后端返回的时间格式：`LocalDateTime`，**无时区后缀**。
 * 这正是 parseDate 存在的理由，测试数据必须保持这个形态。
 */
type AlertLike = {
  id: number
  alertName: string
  title?: string
  level: string
  status: string
  service: string
  summary: string | null
  ticketId: number | null
  firstOccurredAt: string | null
  lastOccurredAt: string | null
  acknowledgedAt: string | null
  resolvedAt: string | null
  createTime: string | null
  occurrenceCount: number
}

const alert = (over: Partial<AlertLike> = {}): AlertLike => ({
  id: 1,
  alertName: 'PodCrashLoopBackOff',
  title: 'order-service Pod 反复重启',
  level: 'P1',
  status: 'FIRING',
  service: 'order-service',
  summary: '容器启动后 30 秒内退出',
  ticketId: null,
  firstOccurredAt: '2026-08-26 09:00:00',
  lastOccurredAt: '2026-08-26 09:00:00',
  acknowledgedAt: null,
  resolvedAt: null,
  createTime: '2026-08-26 09:00:00',
  occurrenceCount: 1,
  ...over,
})

let router: Router

const mountDetail = async (data: AlertLike | null) => {
  api.fetchAlertById.mockResolvedValue(data)

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/alerts', component: defineComponent({ template: '<div/>' }) },
      { path: '/alerts/:id', component: AlertDetail },
    ],
  })
  await router.push('/alerts/1')
  await router.isReady()

  const wrapper = mount(AlertDetail, {
    global: {
      plugins: [
        router,
        [VueQueryPlugin, {
          queryClient: new QueryClient({
            defaultOptions: { queries: { retry: false, staleTime: 0, gcTime: 0 } },
          }),
        }],
      ],
      stubs: {
        DataStateBoundary: { template: '<div><slot /></div>' },
        ApiErrorState: true,
        AppEmpty: true,
        PageLoading: true,
        AppBreadcrumb: true,
        RelativeTime: true,
        'el-tag': true,
        'el-button': true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

type TimelineNode = { key: string; label: string; at: string | null; state: string; hint?: string }
type Vm = {
  timeline: TimelineNode[]
  durationText: string
  canAcknowledge: boolean
  canResolve: boolean
  notFound: boolean
  loading: boolean
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('处置时间线', () => {
  it('未确认未恢复：四节点里只有首次触发是 done，其余 pending', async () => {
    const w = await mountDetail(alert())
    const tl = vmOf(w).timeline

    expect(tl.map((n) => n.key)).toEqual(['first', 'ack', 'resolved'])
    expect(tl.map((n) => n.state)).toEqual(['done', 'pending', 'pending'])
  })

  it('未发生的节点 at 必须为 null——不得拿 createTime 顶替', async () => {
    // 这是本组最重要的一条。给未发生的节点编造时间，
    // 用户会以为「已经有人确认过了」而不去跟进，
    // 同时 MTTA（平均确认时长）会被算成 0
    const w = await mountDetail(alert({ acknowledgedAt: null, resolvedAt: null }))
    const tl = vmOf(w).timeline

    expect(tl.find((n) => n.key === 'ack')?.at).toBeNull()
    expect(tl.find((n) => n.key === 'resolved')?.at).toBeNull()
  })

  it('已确认：ack 节点转 done 并带上真实时间', async () => {
    const w = await mountDetail(alert({
      status: 'ACKNOWLEDGED',
      acknowledgedAt: '2026-08-26 09:12:00',
    }))
    const ack = vmOf(w).timeline.find((n) => n.key === 'ack')

    expect(ack?.state).toBe('done')
    expect(ack?.at).toBe('2026-08-26 09:12:00')
  })

  it('重复触发才插入 repeat 节点，只发生一次时不插', async () => {
    const once = await mountDetail(alert({ occurrenceCount: 1 }))
    expect(vmOf(once).timeline.map((n) => n.key)).not.toContain('repeat')

    const many = await mountDetail(alert({
      occurrenceCount: 7,
      lastOccurredAt: '2026-08-26 09:30:00',
    }))
    const repeat = vmOf(many).timeline.find((n) => n.key === 'repeat')

    expect(repeat?.label).toBe('重复触发 7 次')
    // 展示的是最近一次时间，不是首次——否则「重复触发」这个节点没有信息量
    expect(repeat?.at).toBe('2026-08-26 09:30:00')
  })

  it('repeat 节点排在首次触发之后、人工确认之前', async () => {
    // 时间线的顺序就是它的全部意义，插错位置比不插更误导
    const w = await mountDetail(alert({ occurrenceCount: 3 }))
    expect(vmOf(w).timeline.map((n) => n.key)).toEqual(['first', 'repeat', 'ack', 'resolved'])
  })

  it('firstOccurredAt 缺失时回落到 createTime', async () => {
    const w = await mountDetail(alert({ firstOccurredAt: null, createTime: '2026-08-26 08:00:00' }))
    expect(vmOf(w).timeline.find((n) => n.key === 'first')?.at).toBe('2026-08-26 08:00:00')
  })
})

describe('持续时长', () => {
  it('未恢复：算到当前时刻，且必须按服务器时区解析', async () => {
    // ── 时区这条是重点 ──────────────────────────────────────
    // 后端给的是无时区后缀的 LocalDateTime。若用 new Date 直接解析，
    // 会被当成浏览器本地时间，与 Date.now() 混算后跨时区差十几小时——
    // 刚触发 5 分钟的告警可能显示成「8 小时」。
    //
    // 这里把「现在」固定在首次触发后 45 分钟（用带时区的绝对时刻表达），
    // 只有走 parseDate 补齐服务器时区才能得到 45
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-26T09:45:00+08:00'))

    const w = await mountDetail(alert({ firstOccurredAt: '2026-08-26 09:00:00', resolvedAt: null }))
    expect(vmOf(w).durationText).toBe('45 分钟')
  })

  it('已恢复：算到恢复时刻，不再随时间增长', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-27T00:00:00+08:00'))

    const w = await mountDetail(alert({
      firstOccurredAt: '2026-08-26 09:00:00',
      resolvedAt: '2026-08-26 09:30:00',
      status: 'RESOLVED',
    }))

    // 当前时间已过去一整天，但已恢复的告警时长必须冻结在 30 分钟
    expect(vmOf(w).durationText).toBe('30 分钟')
  })

  it('跨小时与跨天分别用不同粒度表述', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-30T00:00:00+08:00'))

    const hourish = await mountDetail(alert({
      firstOccurredAt: '2026-08-26 09:00:00',
      resolvedAt: '2026-08-26 11:05:00',
    }))
    expect(vmOf(hourish).durationText).toBe('2 小时 5 分钟')

    const exact = await mountDetail(alert({
      firstOccurredAt: '2026-08-26 09:00:00',
      resolvedAt: '2026-08-26 12:00:00',
    }))
    // 整点不拖一个「0 分钟」的尾巴
    expect(vmOf(exact).durationText).toBe('3 小时')

    const dayish = await mountDetail(alert({
      firstOccurredAt: '2026-08-26 09:00:00',
      resolvedAt: '2026-08-28 14:00:00',
    }))
    expect(vmOf(dayish).durationText).toBe('2 天 5 小时')
  })

  it('时间缺失时显示占位符，不显示 NaN', async () => {
    const w = await mountDetail(alert({ firstOccurredAt: null, createTime: null }))
    expect(vmOf(w).durationText).toBe('—')
  })

  it('时间格式非法时同样降级为占位符', async () => {
    // parseDate 对非法值返回 null。若这里漏判，页面会显示 "NaN 分钟"
    const w = await mountDetail(alert({ firstOccurredAt: 'not-a-date', createTime: null }))
    expect(vmOf(w).durationText).toBe('—')
  })
})

describe('动作可用性', () => {
  it('FIRING：两个动作都可用', async () => {
    const w = await mountDetail(alert({ status: 'FIRING' }))
    expect(vmOf(w).canAcknowledge).toBe(true)
    expect(vmOf(w).canResolve).toBe(true)
  })

  it('ACKNOWLEDGED：不能重复确认，但仍可标记恢复', async () => {
    const w = await mountDetail(alert({ status: 'ACKNOWLEDGED' }))
    expect(vmOf(w).canAcknowledge).toBe(false)
    expect(vmOf(w).canResolve).toBe(true)
  })

  it('RESOLVED：两个动作都不可用——终态不该再被操作', async () => {
    const w = await mountDetail(alert({ status: 'RESOLVED' }))
    expect(vmOf(w).canAcknowledge).toBe(false)
    expect(vmOf(w).canResolve).toBe(false)
  })
})

describe('三态：notFound 与 error 必须分开（6.18 契约）', () => {
  it('接口返回 null 判定为 notFound，而非加载失败', async () => {
    // api 层对 40004 返回 null 而不抛错。两者混为一谈的话，
    // 「告警不存在」会显示成「加载失败，请重试」，
    // 用户会一直点重试等一个永远不会来的结果
    const w = await mountDetail(null)

    expect(vmOf(w).notFound).toBe(true)
    expect(vmOf(w).loading).toBe(false)
  })

  it('有数据时 notFound 为 false', async () => {
    const w = await mountDetail(alert())
    expect(vmOf(w).notFound).toBe(false)
  })
})
