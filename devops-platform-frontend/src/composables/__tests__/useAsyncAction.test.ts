/**
 * useAsyncAction 测试。
 *
 * 核心要锁的是**防重入**：TicketDetail 的「升级上报」「提升优先级」
 * 「标记处理中」原本只有 try/catch、没有进行中标记，慢接口下双击会提交两次。
 * 这三个动作都写活动流，重复提交会在工单时间线里留下两条一模一样的记录——
 * 而时间线是事后复盘与追责的依据，脏数据的代价不只是不好看。
 *
 * 其次是 finally 解锁：忘写会让按钮永久禁用，比不加防护更糟。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const notifyMock = vi.hoisted(() => ({
  handleServerError: vi.fn(),
  notify: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))
vi.mock('@/utils/notify', () => notifyMock)

import { useAsyncAction } from '../useAsyncAction'

/** 手动可控的 deferred，用于把动作卡在「进行中」 */
function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('useAsyncAction — 防重入', () => {
  it('进行中时重复调用被忽略，底层函数只执行一次', async () => {
    const d = deferred<string>()
    const fn = vi.fn(() => d.promise)
    const act = useAsyncAction(fn)

    const first = act.run()
    const second = act.run()   // 模拟双击

    expect(fn).toHaveBeenCalledTimes(1)
    await expect(second).resolves.toBeUndefined()

    d.resolve('done')
    await expect(first).resolves.toBe('done')
  })

  it('pending 在执行期间为 true、结束后回落 false', async () => {
    const d = deferred<void>()
    const act = useAsyncAction(() => d.promise)

    expect(act.pending.value).toBe(false)
    const p = act.run()
    expect(act.pending.value).toBe(true)

    d.resolve()
    await p
    expect(act.pending.value).toBe(false)
  })

  it('失败后 pending 也必须解锁 —— 否则按钮永久禁用', async () => {
    const act = useAsyncAction(async () => { throw new Error('boom') })

    await act.run()

    expect(act.pending.value).toBe(false)
  })

  it('第一次完成后可以再次执行', async () => {
    const fn = vi.fn(async () => 'ok')
    const act = useAsyncAction(fn)

    await act.run()
    await act.run()

    expect(fn).toHaveBeenCalledTimes(2)
  })
})

describe('useAsyncAction — 结果与错误', () => {
  it('成功返回底层函数的结果', async () => {
    const act = useAsyncAction(async (n: number) => n * 2)
    await expect(act.run(21)).resolves.toBe(42)
  })

  it('参数原样透传', async () => {
    const fn = vi.fn(async (_a: string, _b: number) => undefined)
    const act = useAsyncAction(fn)
    await act.run('T-1', 3)
    expect(fn).toHaveBeenCalledWith('T-1', 3)
  })

  it('失败时返回 undefined 并交给 handleServerError', async () => {
    const err = new Error('服务端炸了')
    const act = useAsyncAction(async () => { throw err }, { action: '升级上报' })

    await expect(act.run()).resolves.toBeUndefined()
    expect(notifyMock.handleServerError).toHaveBeenCalledWith(err, { action: '升级上报' })
  })

  it('autoHandleError=false 时原样抛出，供调用方自定义处理', async () => {
    const err = new Error('x')
    const act = useAsyncAction(async () => { throw err }, { autoHandleError: false })

    await expect(act.run()).rejects.toThrow('x')
    expect(notifyMock.handleServerError).not.toHaveBeenCalled()
    // 抛出路径同样要解锁
    expect(act.pending.value).toBe(false)
  })

  it('配置了 successMessage 才提示，未配置则不打扰', async () => {
    const withMsg = useAsyncAction(async () => undefined, { successMessage: '已提交升级' })
    await withMsg.run()
    expect(notifyMock.notify.success).toHaveBeenCalledWith('已提交升级')

    notifyMock.notify.success.mockClear()

    const withoutMsg = useAsyncAction(async () => undefined)
    await withoutMsg.run()
    expect(notifyMock.notify.success).not.toHaveBeenCalled()
  })

  it('失败时不弹成功提示', async () => {
    const act = useAsyncAction(
      async () => { throw new Error('boom') },
      { successMessage: '不该出现' }
    )
    await act.run()
    expect(notifyMock.notify.success).not.toHaveBeenCalled()
  })
})
