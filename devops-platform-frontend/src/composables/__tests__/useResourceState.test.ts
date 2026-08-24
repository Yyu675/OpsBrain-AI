/**
 * 资源三态（四态）管理测试。
 *
 * 保护 6.18 契约：
 * - 初值必须是 loading —— 否则首帧闪现「未找到」，用户误以为链接失效
 * - notFound 与 error 必须分开 —— 前者数据已消失（返回列表），
 *   后者数据可能仍在（重试），用户下一步动作不同
 * 以及 6.39 的切换实体归属错乱防护：
 * - 快速切换 id 时，先发起后到达的请求结果必须被丢弃
 */
import { describe, expect, it, vi } from 'vitest'

import { useExternalResourceState, useResourceState } from '../useResourceState'

interface Doc {
  id: number
  title: string
}

/** 可手动 resolve/reject 的 promise，用于精确控制到达顺序 */
function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('初始状态', () => {
  it('初值为 loading —— 初值若是 notFound 会让首帧闪现「未找到」', () => {
    const r = useResourceState<Doc>()
    expect(r.status.value).toBe('loading')
    expect(r.isLoading.value).toBe(true)
    expect(r.isNotFound.value).toBe(false)
    expect(r.isError.value).toBe(false)
  })

  it('初始无数据无错误', () => {
    const r = useResourceState<Doc>()
    expect(r.data.value).toBeNull()
    expect(r.error.value).toBeNull()
  })
})

describe('加载成功', () => {
  it('拿到数据后状态为 ready', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => ({ id: 1, title: '排查手册' }))

    expect(r.status.value).toBe('ready')
    expect(r.isReady.value).toBe(true)
    expect(r.data.value).toEqual({ id: 1, title: '排查手册' })
  })

  it('load 返回加载到的数据，供调用方链式处理', async () => {
    const r = useResourceState<Doc>()
    const got = await r.load(async () => ({ id: 2, title: 'x' }))
    expect(got).toEqual({ id: 2, title: 'x' })
  })

  it('成功后 error 保持为空', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => ({ id: 1, title: 'x' }))
    expect(r.error.value).toBeNull()
  })
})

describe('确实不存在（notFound）', () => {
  it('loader 返回 null 判为 notFound —— 对应后端 40004 由 api 层转 null', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => null)

    expect(r.status.value).toBe('notFound')
    expect(r.isNotFound.value).toBe(true)
    expect(r.isError.value).toBe(false)
  })

  it('loader 返回 undefined 同样判为 notFound', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => undefined as unknown as Doc | null)
    expect(r.status.value).toBe('notFound')
  })

  it('notFound 时 load 返回 null', async () => {
    const r = useResourceState<Doc>()
    expect(await r.load(async () => null)).toBeNull()
  })

  it('notFound 不设置 error —— 「不存在」不是异常，无需展示错误详情', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => null)
    expect(r.error.value).toBeNull()
  })
})

describe('加载失败（error）', () => {
  it('loader 抛错判为 error 而非 notFound —— 数据可能仍在，须给重试', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => { throw new Error('网络不通') })

    expect(r.status.value).toBe('error')
    expect(r.isError.value).toBe(true)
    expect(r.isNotFound.value).toBe(false)
  })

  it('保留原始错误对象，供 ApiErrorState 做友好提示', async () => {
    const r = useResourceState<Doc>()
    const err = new Error('数据库连接超时')
    await r.load(async () => { throw err })

    expect(r.error.value).toBe(err)
  })

  it('失败时 load 返回 null 且不外抛 —— 调用方无需再包一层 try', async () => {
    const r = useResourceState<Doc>()
    await expect(r.load(async () => { throw new Error('x') })).resolves.toBeNull()
  })

  it('error 与 notFound 互斥，不会同时成立', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => { throw new Error('x') })
    expect(r.isError.value && r.isNotFound.value).toBe(false)
  })
})

describe('四态互斥（判别式联合的核心保证）', () => {
  it('任一时刻恰有一个状态成立 —— 布尔组合会允许 loading 与 error 并存', async () => {
    const r = useResourceState<Doc>()

    const flags = () => [r.isLoading.value, r.isReady.value, r.isNotFound.value, r.isError.value]
    const activeCount = () => flags().filter(Boolean).length

    expect(activeCount()).toBe(1)

    await r.load(async () => ({ id: 1, title: 'x' }))
    expect(activeCount()).toBe(1)

    await r.load(async () => null)
    expect(activeCount()).toBe(1)

    await r.load(async () => { throw new Error('x') })
    expect(activeCount()).toBe(1)
  })
})

describe('加载期间清空旧数据', () => {
  it('重新加载时先清空上一次的数据 —— 避免新旧数据混显', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => ({ id: 1, title: '旧文档' }))
    expect(r.data.value).not.toBeNull()

    const d = deferred<Doc | null>()
    const pending = r.load(() => d.promise)

    expect(r.data.value).toBeNull()
    expect(r.status.value).toBe('loading')

    d.resolve({ id: 2, title: '新文档' })
    await pending
    expect(r.data.value).toEqual({ id: 2, title: '新文档' })
  })

  it('从 error 态重新加载会清掉上次的错误', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => { throw new Error('first') })
    expect(r.error.value).not.toBeNull()

    await r.load(async () => ({ id: 1, title: 'x' }))
    expect(r.error.value).toBeNull()
    expect(r.status.value).toBe('ready')
  })
})

describe('竞态防护（6.39 切换实体归属错乱）', () => {
  it('先发起但后到达的请求结果被丢弃 —— 否则工单 A 的数据会落到工单 B 上', async () => {
    const r = useResourceState<Doc>()

    const first = deferred<Doc | null>()
    const second = deferred<Doc | null>()

    const p1 = r.load(() => first.promise)
    const p2 = r.load(() => second.promise)

    // 第二个请求先返回
    second.resolve({ id: 2, title: '工单 B' })
    await p2
    expect(r.data.value).toEqual({ id: 2, title: '工单 B' })

    // 第一个请求后返回，其结果必须被丢弃
    first.resolve({ id: 1, title: '工单 A' })
    await p1
    expect(r.data.value).toEqual({ id: 2, title: '工单 B' })
  })

  it('被丢弃的请求返回 null，不影响调用方', async () => {
    const r = useResourceState<Doc>()
    const first = deferred<Doc | null>()
    const second = deferred<Doc | null>()

    const p1 = r.load(() => first.promise)
    const p2 = r.load(() => second.promise)

    second.resolve({ id: 2, title: 'B' })
    await p2
    first.resolve({ id: 1, title: 'A' })

    expect(await p1).toBeNull()
  })

  it('陈旧请求的失败不会把已成功的状态改成 error', async () => {
    const r = useResourceState<Doc>()
    const stale = deferred<Doc | null>()
    const fresh = deferred<Doc | null>()

    const p1 = r.load(() => stale.promise)
    const p2 = r.load(() => fresh.promise)

    fresh.resolve({ id: 2, title: 'B' })
    await p2
    expect(r.status.value).toBe('ready')

    stale.reject(new Error('陈旧请求失败'))
    await p1
    expect(r.status.value).toBe('ready')
    expect(r.error.value).toBeNull()
  })

  it('陈旧请求返回 null 不会把已成功的状态改成 notFound', async () => {
    const r = useResourceState<Doc>()
    const stale = deferred<Doc | null>()
    const fresh = deferred<Doc | null>()

    const p1 = r.load(() => stale.promise)
    const p2 = r.load(() => fresh.promise)

    fresh.resolve({ id: 2, title: 'B' })
    await p2
    stale.resolve(null)
    await p1

    expect(r.status.value).toBe('ready')
    expect(r.data.value).toEqual({ id: 2, title: 'B' })
  })
})

describe('reset（切换实体时必须调用）', () => {
  it('回到 loading 态并清空数据与错误', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => ({ id: 1, title: 'x' }))

    r.reset()
    expect(r.status.value).toBe('loading')
    expect(r.data.value).toBeNull()
    expect(r.error.value).toBeNull()
  })

  it('从 error 态 reset 后不残留错误', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => { throw new Error('x') })

    r.reset()
    expect(r.isError.value).toBe(false)
    expect(r.error.value).toBeNull()
  })

  it('reset 使进行中的请求结果作废 —— 切换实体后旧数据不得落地', async () => {
    const r = useResourceState<Doc>()
    const d = deferred<Doc | null>()
    const pending = r.load(() => d.promise)

    r.reset()
    d.resolve({ id: 1, title: '上一个实体' })
    await pending

    expect(r.data.value).toBeNull()
    expect(r.status.value).toBe('loading')
  })

  it('reset 后可正常发起新的加载', async () => {
    const r = useResourceState<Doc>()
    await r.load(async () => ({ id: 1, title: 'A' }))
    r.reset()
    await r.load(async () => ({ id: 2, title: 'B' }))

    expect(r.status.value).toBe('ready')
    expect(r.data.value).toEqual({ id: 2, title: 'B' })
  })
})

describe('loader 调用次数', () => {
  it('每次 load 只调 loader 一次', async () => {
    const r = useResourceState<Doc>()
    const loader = vi.fn(async () => ({ id: 1, title: 'x' }))

    await r.load(loader)
    expect(loader).toHaveBeenCalledTimes(1)
  })
})

// ==================== 外部数据源变体 ====================

describe('useExternalResourceState', () => {
  it('初值为 loading', () => {
    const r = useExternalResourceState(() => false)
    expect(r.status.value).toBe('loading')
  })

  it('加载完成且外部有数据时判为 ready', async () => {
    let stored: Doc | null = null
    const r = useExternalResourceState(() => stored !== null)

    await r.load(async () => { stored = { id: 1, title: 'x' } })
    expect(r.status.value).toBe('ready')
  })

  it('加载完成但外部仍无数据时判为 notFound', async () => {
    const r = useExternalResourceState(() => false)
    await r.load(async () => { /* 后端返回 null，未写入 store */ })

    expect(r.status.value).toBe('notFound')
    expect(r.isNotFound.value).toBe(true)
  })

  it('loader 抛错判为 error 而非 notFound', async () => {
    const r = useExternalResourceState(() => false)
    await r.load(async () => { throw new Error('网络不通') })

    expect(r.status.value).toBe('error')
    expect(r.isError.value).toBe(true)
    expect(r.isNotFound.value).toBe(false)
  })

  it('保留原始错误对象', async () => {
    const r = useExternalResourceState(() => false)
    const err = new Error('超时')
    await r.load(async () => { throw err })
    expect(r.error.value).toBe(err)
  })

  it('失败不外抛，调用方无需再包 try', async () => {
    const r = useExternalResourceState(() => false)
    await expect(r.load(async () => { throw new Error('x') })).resolves.toBeUndefined()
  })

  it('陈旧请求的结果被丢弃 —— 切换实体时旧请求不得改写状态', async () => {
    let stored: Doc | null = null
    const r = useExternalResourceState(() => stored !== null)

    const stale = deferred<void>()
    const fresh = deferred<void>()

    const p1 = r.load(() => stale.promise)
    const p2 = r.load(() => fresh.promise)

    stored = { id: 2, title: 'B' }
    fresh.resolve()
    await p2
    expect(r.status.value).toBe('ready')

    // 陈旧请求失败，不应把已 ready 的状态改成 error
    stale.reject(new Error('陈旧'))
    await p1
    expect(r.status.value).toBe('ready')
    expect(r.error.value).toBeNull()
  })

  it('reset 回到 loading 并清错误', async () => {
    const r = useExternalResourceState(() => false)
    await r.load(async () => { throw new Error('x') })

    r.reset()
    expect(r.status.value).toBe('loading')
    expect(r.error.value).toBeNull()
  })

  it('reset 使进行中的请求作废', async () => {
    let stored: Doc | null = null
    const r = useExternalResourceState(() => stored !== null)

    const d = deferred<void>()
    const pending = r.load(() => d.promise)

    r.reset()
    stored = { id: 1, title: '上一个实体' }
    d.resolve()
    await pending

    expect(r.status.value).toBe('loading')
  })

  it('三态互斥', async () => {
    const r = useExternalResourceState(() => false)
    const activeCount = () =>
      [r.isLoading.value, r.isNotFound.value, r.isError.value].filter(Boolean).length

    expect(activeCount()).toBe(1)
    await r.load(async () => { /* notFound */ })
    expect(activeCount()).toBe(1)
    await r.load(async () => { throw new Error('x') })
    expect(activeCount()).toBe(1)
  })
})
