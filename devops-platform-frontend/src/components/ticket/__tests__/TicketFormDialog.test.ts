/**
 * 工单创建/编辑弹窗测试。
 *
 * ── 为什么它比更大的只读页优先 ────────────────────────────────
 * `KnowledgeDetail.vue`（1730 行）比它大，但那是只读页——错了是「看着不对」。
 * 这个弹窗是**写入口**，错了是「工单建错了」，而工单是整个运维流程的载体：
 * 优先级决定 SLA 时限、服务与分类决定派单去向。
 *
 * ── 两段核心逻辑 ──────────────────────────────────────────────
 * 1. `validate()` —— 提交前的闸门。长度上限必须与后端
 *    `TicketController.CreateTicketRequest` 的 `@Size` 对齐：
 *    前端比后端严会**偷偷砍掉用户能写的内容**且无提示，
 *    前端比后端松则让用户白填一遍再收到看不懂的 40001。
 *
 * 2. `applySuggestion()` —— **AI 给的值会不会写进工单**。
 *    这是本类最该守的一段：模型输出不可信，
 *    优先级/服务/分类若不在词表内必须被丢弃。
 *    放行一个词表外的优先级，它会一路写进库并污染分级统计，
 *    而看板上只会显示一个谁也不认识的值。
 *
 * ── 测试手法 ──────────────────────────────────────────────────
 * 弹窗有 32 个函数、1186 行，但绝大部分是模板与 UI 状态。
 * 这里不追求覆盖率，只把上面两段逻辑连同「提交时真的带上了正确数据」
 * 钉死；AI 流式对话、焦点陷阱、草稿暂存各有独立测试或已被其他用例覆盖。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const store = vi.hoisted(() => ({
  assignees: ['张明', '李四'],
  teamMembers: [] as unknown[],
  createTicket: vi.fn(),
  updateTicket: vi.fn(),
  getById: vi.fn(),
  loadTeamMembers: vi.fn(),
  loadTicketDetail: vi.fn(),
}))
// 只替换 useTicketsStore，保留 SERVICE_OPTIONS / CATEGORY_OPTIONS 等真实导出——
// 那几个词表正是「AI 建议是否合法」的判据，用假的等于没测
vi.mock('@/stores/tickets', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/tickets')
  return { ...actual, useTicketsStore: () => store }
})

const notify = vi.hoisted(() => ({
  success: vi.fn(), warning: vi.fn(), error: vi.fn(),
  info: vi.fn(), clearCooldown: vi.fn(),
}))
vi.mock('@/utils/notify', () => ({ notify, handleServerError: vi.fn() }))

vi.mock('@/api/chat', () => ({ chatStream: vi.fn() }))

import { CATEGORY_OPTIONS, SERVICE_OPTIONS } from '@/stores/tickets'
import TicketFormDialog from '../TicketFormDialog.vue'

function mountDialog(props: Record<string, unknown> = {}): VueWrapper {
  return mount(TicketFormDialog, {
    props: { visible: true, ...props },
    global: {
      stubs: { Teleport: true, 'el-select': true, 'el-option': true },
    },
  })
}

/** 取出组件内部实例，直接驱动那两段核心逻辑 */
const vm = (w: VueWrapper): any => w.vm as any

describe('TicketFormDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    vi.clearAllMocks()
    store.createTicket.mockResolvedValue({ id: 'TK-1' })
    store.updateTicket.mockResolvedValue({ id: 'TK-1' })
    store.loadTeamMembers.mockResolvedValue([])
  })

  describe('提交校验：闸门必须与后端上限一致', () => {
    const fill = (w: VueWrapper, over: Record<string, unknown> = {}) => {
      Object.assign(vm(w).form, {
        title: '数据库连接池耗尽告警',
        description: '订单服务连接池打满，大量请求超时，需要紧急排查',
        service: SERVICE_OPTIONS[0],
        category: CATEGORY_OPTIONS[0],
        ...over,
      })
    }

    it('完整表单通过校验', () => {
      const w = mountDialog()
      fill(w)
      expect(vm(w).validate()).toBeNull()
    })

    it('标题为空或过短被拦下', () => {
      const w = mountDialog()
      fill(w, { title: '   ' })
      expect(vm(w).validate()).toContain('标题')

      fill(w, { title: '短' })
      // 5 字下限：一句「报错了」这样的标题在列表里毫无信息量
      expect(vm(w).validate()).toContain('至少 5 个字符')
    })

    it('标题超 255 字被拦下 —— 上限对齐后端 @Size', () => {
      const w = mountDialog()
      fill(w, { title: 'x'.repeat(256) })
      expect(vm(w).validate()).toContain('255')
    })

    it('描述至少 10 字 —— 太短的描述让接手人无从下手', () => {
      const w = mountDialog()
      fill(w, { description: '坏了' })
      expect(vm(w).validate()).toContain('至少 10 个字符')
    })

    it('描述超 20000 字在前端拦下并说明，而不是让后端返回看不懂的 40001', () => {
      const w = mountDialog()
      fill(w, { description: 'x'.repeat(20001) })

      const err = vm(w).validate()
      expect(err).toContain('20000')
      // 提示要带上当前字数，用户才知道要精简多少
      expect(err).toContain('20001')
    })

    it('服务与分类必选 —— 缺了会让工单无法派单', () => {
      const w = mountDialog()
      fill(w, { service: '' })
      expect(vm(w).validate()).toContain('服务')

      fill(w, { category: '' })
      expect(vm(w).validate()).toContain('分类')
    })

    it('校验失败时不调用 store，不产生半截工单', async () => {
      const w = mountDialog()
      fill(w, { title: '' })

      await vm(w).submit()

      expect(store.createTicket).not.toHaveBeenCalled()
      expect(notify.warning).toHaveBeenCalled()
    })

    it('标题两端空格不计入长度 —— 五个空格加一个字不算合格标题', () => {
      const w = mountDialog()
      fill(w, { title: '     短     ' })
      expect(vm(w).validate()).toContain('至少 5 个字符')
    })
  })

  describe('AI 建议采纳：模型输出不可信', () => {
    it('词表内的分类/服务/优先级被采纳', () => {
      const w = mountDialog()

      // 前端优先级词表是 urgent/high/medium/low，提交时由
      // mapFrontendPriorityToBackend 转成后端的 P0-P3。
      // 最初这条按 'P1' 写，CI 报 expected 'medium' to be 'P1'——
      // 查证后确认是**测试假设错了**，两侧各有词表且有显式映射层，
      // 不是漂移。这条注释留着，避免后来者看到差异就去「统一」掉映射
      const applied = vm(w).applySuggestion({
        category: CATEGORY_OPTIONS[1],
        service: SERVICE_OPTIONS[1],
        priority: 'high',
      })

      expect(applied).toBe(true)
      expect(vm(w).form.category).toBe(CATEGORY_OPTIONS[1])
      expect(vm(w).form.service).toBe(SERVICE_OPTIONS[1])
      expect(vm(w).form.priority).toBe('high')
    })

    it('词表外的优先级被丢弃 —— 放行会一路写进库并污染分级统计', () => {
      const w = mountDialog()
      const before = vm(w).form.priority

      const applied = vm(w).applySuggestion({ priority: 'URGENT' })

      // 看板上会显示一个谁也不认识的值，而 SLA 计算按优先级分档
      expect(applied).toBe(false)
      expect(vm(w).form.priority).toBe(before)
    })

    it('词表外的服务与分类同样被丢弃', () => {
      const w = mountDialog()
      const before = { ...vm(w).form }

      const applied = vm(w).applySuggestion({
        category: '模型编的分类',
        service: '不存在的服务',
      })

      expect(applied).toBe(false)
      expect(vm(w).form.category).toBe(before.category)
      expect(vm(w).form.service).toBe(before.service)
    })

    it('非字符串类型不会被强转 —— 模型偶尔会返回数字或对象', () => {
      const w = mountDialog()

      const applied = vm(w).applySuggestion({
        category: 123,
        service: { name: 'x' },
        priority: null,
      })

      expect(applied).toBe(false)
    })

    it('部分合法时只采纳合法的那部分', () => {
      const w = mountDialog()

      const applied = vm(w).applySuggestion({
        category: CATEGORY_OPTIONS[1],   // 合法
        priority: 'SUPER_URGENT',        // 非法
      })

      // 返回 true（确实改了东西），但非法值没进去
      expect(applied).toBe(true)
      expect(vm(w).form.category).toBe(CATEGORY_OPTIONS[1])
      expect(vm(w).form.priority).not.toBe('SUPER_URGENT')
    })

    it('标签去重且不超过 20 个', () => {
      const w = mountDialog()
      vm(w).form.tags = ['mysql']

      vm(w).applySuggestion({ tags: ['mysql', 'p1', 'p1'] })

      expect(vm(w).form.tags).toEqual(['mysql', 'p1'])
    })

    it('超长标签被丢弃（单个上限 20 字）', () => {
      const w = mountDialog()

      vm(w).applySuggestion({ tags: ['x'.repeat(21)] })

      expect(vm(w).form.tags).toEqual([])
    })

    it('已达 20 个标签时不再追加', () => {
      const w = mountDialog()
      vm(w).form.tags = Array.from({ length: 20 }, (_, i) => `t${i}`)

      vm(w).applySuggestion({ tags: ['新标签'] })

      expect(vm(w).form.tags).toHaveLength(20)
      expect(vm(w).form.tags).not.toContain('新标签')
    })

    it('空建议对象不产生改动', () => {
      const w = mountDialog()
      expect(vm(w).applySuggestion({})).toBe(false)
    })
  })

  describe('提交：数据真的带上了', () => {
    it('新建时把裁剪后的字段交给 store', async () => {
      const w = mountDialog()
      Object.assign(vm(w).form, {
        title: '  数据库连接池耗尽  ',
        description: '  订单服务连接池打满，请求大量超时  ',
        service: SERVICE_OPTIONS[0],
        category: CATEGORY_OPTIONS[0],
        priority: 'high',
      })

      await vm(w).submit()
      await flushPromises()

      expect(store.createTicket).toHaveBeenCalledTimes(1)
      const payload = store.createTicket.mock.calls[0][0]
      // 不裁剪的话，标题前后空格会让列表里同一张单看起来缩进不一致，
      // 按标题搜索也搜不到
      expect(payload.title).toBe('数据库连接池耗尽')
      expect(payload.priority).toBe('high')
    })

    it('提交进行中不重复提交 —— 双击会建出两张一样的工单', async () => {
      const w = mountDialog()
      Object.assign(vm(w).form, {
        title: '数据库连接池耗尽告警',
        description: '订单服务连接池打满，大量请求超时需排查',
        service: SERVICE_OPTIONS[0],
        category: CATEGORY_OPTIONS[0],
      })

      vm(w).submitting = true
      await vm(w).submit()

      expect(store.createTicket).not.toHaveBeenCalled()
    })
  })
})
