import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref, nextTick } from 'vue'

import {
  useUrlFilters,
  enumParser,
  positiveIntParser,
  textParser,
} from '../useUrlFilters'

/**
 * URL 筛选同步测试。
 *
 * 保护的契约：**筛选结果必须可分享**。运维场景里
 * 「P0 + 未分配的工单」应该是一个能直接粘进群里的链接。
 *
 * 同时锁住几个容易做错的点：默认值不入 URL（否则地址栏全是噪音）、
 * 用 replace 不用 push（否则返回键要按十几次）、非法值静默忽略。
 */

const mockRoute = { query: {} as Record<string, string> }
const replaceSpy = vi.fn()

vi.mock('vue-router', () => ({
  useRoute: () => mockRoute,
  useRouter: () => ({
    replace: (...args: unknown[]) => {
      replaceSpy(...args)
      // 模拟导航生效，便于后续断言基于最新 query
      const arg = args[0] as { query?: Record<string, string> }
      mockRoute.query = arg.query ?? {}
      return Promise.resolve()
    },
    push: vi.fn(),
  }),
}))

beforeEach(() => {
  mockRoute.query = {}
  replaceSpy.mockClear()
})

describe('URL → 状态', () => {
  it('从 URL 读取合法筛选值', () => {
    mockRoute.query = { status: 'processing', page: '3' }
    const status = ref('all')
    const page = ref(1)

    useUrlFilters([
      { ref: status, key: 'status', defaultValue: 'all',
        parse: enumParser(['all', 'pending', 'processing']) },
      { ref: page, key: 'page', defaultValue: 1, parse: positiveIntParser() },
    ])

    expect(status.value).toBe('processing')
    expect(page.value).toBe(3)
  })

  it('非法值静默忽略，保持默认——URL 可能被手工编辑或已过期', () => {
    mockRoute.query = { status: 'garbage', page: '-5' }
    const status = ref('all')
    const page = ref(1)

    useUrlFilters([
      { ref: status, key: 'status', defaultValue: 'all',
        parse: enumParser(['all', 'pending']) },
      { ref: page, key: 'page', defaultValue: 1, parse: positiveIntParser() },
    ])

    expect(status.value).toBe('all')
    expect(page.value).toBe(1)
  })

  it('URL 中缺失的项不影响已有状态', () => {
    mockRoute.query = {}
    const status = ref('pending')

    useUrlFilters([
      { ref: status, key: 'status', defaultValue: 'all',
        parse: enumParser(['all', 'pending']) },
    ])

    expect(status.value).toBe('pending')
  })
})

describe('状态 → URL', () => {
  it('筛选变化后写回 URL，使结果可分享', async () => {
    const status = ref('all')
    useUrlFilters([
      { ref: status, key: 'status', defaultValue: 'all',
        parse: enumParser(['all', 'pending']) },
    ])

    status.value = 'pending'
    await nextTick()

    expect(replaceSpy).toHaveBeenCalledWith({ query: { status: 'pending' } })
  })

  it('等于默认值的项不写入 URL —— 避免地址栏堆满 ?status=all&page=1 噪音', async () => {
    mockRoute.query = { status: 'pending' }
    const status = ref('all')
    useUrlFilters([
      { ref: status, key: 'status', defaultValue: 'all',
        parse: enumParser(['all', 'pending']) },
    ])
    // 构造时读到 pending
    expect(status.value).toBe('pending')

    status.value = 'all'
    await nextTick()

    expect(replaceSpy).toHaveBeenCalledWith({ query: {} })
  })

  it('用 replace 而非 push —— 否则每改一次筛选压一条历史，返回键要按十几次', async () => {
    const kw = ref('')
    useUrlFilters([
      { ref: kw, key: 'kw', defaultValue: '', parse: textParser() },
    ])

    kw.value = 'redis'
    await nextTick()

    expect(replaceSpy).toHaveBeenCalledTimes(1)
  })

  it('保留其它页面状态参数（如 view/tab），不越权清空', async () => {
    mockRoute.query = { view: 'grid' }
    const status = ref('all')
    useUrlFilters([
      { ref: status, key: 'status', defaultValue: 'all',
        parse: enumParser(['all', 'pending']) },
    ])

    status.value = 'pending'
    await nextTick()

    expect(replaceSpy).toHaveBeenCalledWith({ query: { view: 'grid', status: 'pending' } })
  })

  it('内容未变则不触发导航，避免 watch 自激循环', async () => {
    const status = ref('all')
    useUrlFilters([
      { ref: status, key: 'status', defaultValue: 'all',
        parse: enumParser(['all', 'pending']) },
    ])

    // 赋同值：ref 不变化，watch 不触发
    status.value = 'all'
    await nextTick()

    expect(replaceSpy).not.toHaveBeenCalled()
  })

  it('多项同时变化合并为一次导航', async () => {
    const status = ref('all')
    const priority = ref('all')
    useUrlFilters([
      { ref: status, key: 'status', defaultValue: 'all',
        parse: enumParser(['all', 'pending']) },
      { ref: priority, key: 'priority', defaultValue: 'all',
        parse: enumParser(['all', 'P0']) },
    ])

    status.value = 'pending'
    priority.value = 'P0'
    await nextTick()

    expect(replaceSpy).toHaveBeenCalledTimes(1)
    expect(replaceSpy).toHaveBeenCalledWith({
      query: { status: 'pending', priority: 'P0' },
    })
  })
})

describe('解析器', () => {
  it('enumParser 只接受白名单值', () => {
    const p = enumParser(['a', 'b'] as const)
    expect(p('a')).toBe('a')
    expect(p('c')).toBeUndefined()
  })

  it('positiveIntParser 拒绝 0、负数、小数与非数字', () => {
    const p = positiveIntParser()
    expect(p('5')).toBe(5)
    expect(p('0')).toBeUndefined()
    expect(p('-1')).toBeUndefined()
    expect(p('1.5')).toBeUndefined()
    expect(p('abc')).toBeUndefined()
  })

  it('positiveIntParser 拒绝超大值 —— URL 可被构造，size=999999 会拖垮后端', () => {
    expect(positiveIntParser(100)('101')).toBeUndefined()
    expect(positiveIntParser(100)('100')).toBe(100)
  })

  it('textParser 去空白并截断超长输入', () => {
    expect(textParser()('  redis  ')).toBe('redis')
    expect(textParser()('   ')).toBeUndefined()
    expect(textParser(5)('abcdefghij')).toBe('abcde')
  })
})
