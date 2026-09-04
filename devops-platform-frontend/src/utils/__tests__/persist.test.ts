/**
 * 持久化层测试。
 *
 * 覆盖前端 CLAUDE.md P0 #2/#3 两项兜底：
 * - localStorage 写入/读取失败必须降级为内存态，不得让整个 store 挂掉
 *   （隐私模式 / QuotaExceededError / Safari ITP）
 * - schema 版本不匹配时逐版本迁移，并留 __backup 副本，
 *   避免用户配置一次升级全部丢失
 *
 * 以及 6.18 契约的反面教训：服务端分页的数据不得持久化，
 * 故这里只测机制本身，不测业务 store 的持久化内容。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  broadcastPersistChange,
  clearBackup,
  clearPersisted,
  debounce,
  getBackup,
  loadPersisted,
  onPersistedChange,
  onPersistWriteFailure,
  readBackupPayload,
  savePersisted,
} from '../persist'

const PREFIX = '__store__:'

/** 直接写入底层 localStorage，模拟「上一版本遗留的数据」 */
function seedRaw(key: string, raw: string) {
  localStorage.setItem(PREFIX + key, raw)
}

function readRaw(key: string): string | null {
  return localStorage.getItem(PREFIX + key)
}

beforeEach(() => {
  localStorage.clear()
})

describe('savePersisted / loadPersisted 往返', () => {
  it('同版本写入后能原样读回', () => {
    savePersisted('settings', { compactTable: true, idleTimeoutMinutes: 30 }, 1)
    expect(loadPersisted('settings', 1)).toEqual({
      compactTable: true,
      idleTimeoutMinutes: 30,
    })
  })

  it('键未写入时返回 null', () => {
    expect(loadPersisted('never-written', 1)).toBeNull()
  })

  it('数组、布尔、数字等原始值同样能往返', () => {
    savePersisted('list', ['a', 'b'], 1)
    expect(loadPersisted('list', 1)).toEqual(['a', 'b'])

    savePersisted('flag', false, 1)
    expect(loadPersisted('flag', 1)).toBe(false)

    savePersisted('num', 0, 1)
    expect(loadPersisted('num', 1)).toBe(0)
  })

  it('payload 带 version 与 savedAt —— 迁移与排障都依赖这两个字段', () => {
    savePersisted('settings', { a: 1 }, 3)
    const raw = JSON.parse(readRaw('settings')!)
    expect(raw.version).toBe(3)
    expect(typeof raw.savedAt).toBe('number')
  })
})

describe('损坏数据的降级', () => {
  it('非 JSON 内容被清除并返回 null，不抛错', () => {
    seedRaw('settings', '{ 这不是合法 JSON')
    expect(loadPersisted('settings', 1)).toBeNull()
    expect(readRaw('settings')).toBeNull()
  })

  it('缺少 value 字段的 payload 被清除', () => {
    seedRaw('settings', JSON.stringify({ version: 1, savedAt: 1 }))
    expect(loadPersisted('settings', 1)).toBeNull()
    expect(readRaw('settings')).toBeNull()
  })

  it('payload 为 JSON 原始值（非对象）时被清除', () => {
    seedRaw('settings', '"just a string"')
    expect(loadPersisted('settings', 1)).toBeNull()
  })

  it('null payload 被清除', () => {
    seedRaw('settings', 'null')
    expect(loadPersisted('settings', 1)).toBeNull()
  })
})

describe('版本迁移', () => {
  it('低版本数据逐版本迁移到目标版本', () => {
    seedRaw('settings', JSON.stringify({ version: 1, value: { n: 1 }, savedAt: 0 }))

    const result = loadPersisted<{ n: number }>('settings', 3, {
      migrations: {
        2: (old) => ({ n: (old as { n: number }).n + 10 }),
        3: (old) => ({ n: (old as { n: number }).n + 100 }),
      },
    })

    expect(result).toEqual({ n: 111 })
  })

  it('迁移后把结果以新版本写回，下次读取无需再迁移', () => {
    seedRaw('settings', JSON.stringify({ version: 1, value: { n: 1 }, savedAt: 0 }))
    loadPersisted('settings', 2, { migrations: { 2: (old) => ({ n: (old as { n: number }).n + 1 }) } })

    const raw = JSON.parse(readRaw('settings')!)
    expect(raw.version).toBe(2)
    expect(raw.value).toEqual({ n: 2 })
  })

  it('迁移前留 __backup 副本 —— 迁移逻辑写错时数据仍可找回', () => {
    const original = JSON.stringify({ version: 1, value: { n: 1 }, savedAt: 0 })
    seedRaw('settings', original)
    loadPersisted('settings', 2, { migrations: { 2: (old) => old } })

    expect(getBackup('settings')).toBe(original)
  })

  it('缺失中间版本的迁移步骤时跳过该步，不中断整条链', () => {
    seedRaw('settings', JSON.stringify({ version: 1, value: { n: 1 }, savedAt: 0 }))

    const result = loadPersisted<{ n: number }>('settings', 3, {
      migrations: { 3: (old) => ({ n: (old as { n: number }).n + 100 }) },
    })

    expect(result).toEqual({ n: 101 })
  })

  it('迁移函数抛错时清除数据返回 null，不让半迁移状态流入 store', () => {
    seedRaw('settings', JSON.stringify({ version: 1, value: { n: 1 }, savedAt: 0 }))

    const result = loadPersisted('settings', 2, {
      migrations: { 2: () => { throw new Error('迁移逻辑有 bug') } },
    })

    expect(result).toBeNull()
    expect(readRaw('settings')).toBeNull()
  })

  it('未提供 migrations 时低版本数据被丢弃但留 __backup', () => {
    const original = JSON.stringify({ version: 1, value: { n: 1 }, savedAt: 0 })
    seedRaw('settings', original)

    expect(loadPersisted('settings', 2)).toBeNull()
    expect(readRaw('settings')).toBeNull()
    expect(getBackup('settings')).toBe(original)
  })

  it('存储版本高于代码版本（用户降级了前端）时丢弃并留 __backup', () => {
    const future = JSON.stringify({ version: 9, value: { n: 1 }, savedAt: 0 })
    seedRaw('settings', future)

    expect(loadPersisted('settings', 2, { migrations: { 2: (o) => o } })).toBeNull()
    expect(getBackup('settings')).toBe(future)
  })
})

describe('备份读写', () => {
  it('readBackupPayload 解出结构化 payload', () => {
    seedRaw('settings', JSON.stringify({ version: 1, value: { n: 7 }, savedAt: 123 }))
    loadPersisted('settings', 2)

    const backup = readBackupPayload<{ n: number }>('settings')
    expect(backup?.version).toBe(1)
    expect(backup?.value).toEqual({ n: 7 })
  })

  it('无备份时返回 null', () => {
    expect(readBackupPayload('nothing')).toBeNull()
    expect(getBackup('nothing')).toBeNull()
  })

  it('备份内容损坏时返回 null 而非抛错', () => {
    localStorage.setItem(PREFIX + 'settings__backup', '{ 坏了')
    expect(readBackupPayload('settings')).toBeNull()
  })

  it('clearBackup 只清备份，不动主数据', () => {
    savePersisted('settings', { n: 1 }, 1)
    localStorage.setItem(PREFIX + 'settings__backup', '{"version":0,"value":{},"savedAt":0}')

    clearBackup('settings')
    expect(getBackup('settings')).toBeNull()
    expect(loadPersisted('settings', 1)).toEqual({ n: 1 })
  })

  it('clearPersisted 同时清主数据与备份', () => {
    savePersisted('settings', { n: 1 }, 1)
    localStorage.setItem(PREFIX + 'settings__backup', '{"version":0,"value":{},"savedAt":0}')

    clearPersisted('settings')
    expect(loadPersisted('settings', 1)).toBeNull()
    expect(getBackup('settings')).toBeNull()
  })
})

describe('localStorage 不可用时的降级', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('setItem 抛 QuotaExceededError 时不外抛 —— 隐私模式/配额超限不应让 store 挂掉', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })

    expect(() => savePersisted('settings', { n: 1 }, 1)).not.toThrow()
  })

  /**
   * 「不外抛」不等于「可以静默」。
   *
   * 原实现是空 catch，用户调好的列宽 / 主题 / 已读状态会看似保存成功、
   * 刷新后复原，控制台也没有线索。必须让上层有机会提示。
   */
  it('写入失败会通知订阅者，并标出是否为配额超限', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })

    const seen: Array<{ key: string; quotaExceeded: boolean }> = []
    const off = onPersistWriteFailure(({ key, quotaExceeded }) => seen.push({ key, quotaExceeded }))

    savePersisted('col-widths', { id: 120 }, 1)

    expect(seen).toHaveLength(1)
    expect(seen[0].key).toBe('col-widths')
    expect(seen[0].quotaExceeded).toBe(true)

    off()
  })

  it('非配额类失败（如隐私模式 SecurityError）标记 quotaExceeded=false —— 两者提示策略不同', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })

    const seen: boolean[] = []
    const off = onPersistWriteFailure(({ quotaExceeded }) => seen.push(quotaExceeded))

    savePersisted('settings', { n: 1 }, 1)

    expect(seen).toEqual([false])
    off()
  })

  it('取消订阅后不再收到通知', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })

    const fn = vi.fn()
    const off = onPersistWriteFailure(fn)
    off()

    savePersisted('settings', { n: 1 }, 1)
    expect(fn).not.toHaveBeenCalled()
  })

  it('订阅者自身抛错不影响其他订阅者 —— 一个消费方的 bug 不该拖垮持久化层', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })

    const good = vi.fn()
    const offBad = onPersistWriteFailure(() => { throw new Error('consumer bug') })
    const offGood = onPersistWriteFailure(good)

    expect(() => savePersisted('settings', { n: 1 }, 1)).not.toThrow()
    expect(good).toHaveBeenCalledTimes(1)

    offBad()
    offGood()
  })

  it('getItem 抛错时 loadPersisted 返回 null 而非崩溃', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })

    expect(loadPersisted('settings', 1)).toBeNull()
  })

  it('removeItem 抛错时 clearPersisted 不外抛', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })

    expect(() => clearPersisted('settings')).not.toThrow()
  })

  it('循环引用值不会让 savePersisted 抛错', () => {
    const circular: Record<string, unknown> = { a: 1 }
    circular.self = circular

    expect(() => savePersisted('settings', circular, 1)).not.toThrow()
  })
})

describe('debounce', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('等待期内只执行最后一次调用', () => {
    const fn = vi.fn()
    const d = debounce(fn, 300)

    d('a')
    d('b')
    d('c')
    expect(fn).not.toHaveBeenCalled()

    vi.advanceTimersByTime(300)
    expect(fn).toHaveBeenCalledExactlyOnceWith('c')
  })

  it('间隔超过 wait 的调用各自执行', () => {
    const fn = vi.fn()
    const d = debounce(fn, 100)

    d('a')
    vi.advanceTimersByTime(100)
    d('b')
    vi.advanceTimersByTime(100)

    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('flush 立即以最后一次参数执行', () => {
    const fn = vi.fn()
    const d = debounce(fn, 300)

    d('pending')
    d.flush()
    expect(fn).toHaveBeenCalledExactlyOnceWith('pending')

    // flush 后定时器已清，不应再触发第二次
    vi.advanceTimersByTime(300)
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('无待执行调用时 flush 不触发', () => {
    const fn = vi.fn()
    debounce(fn, 300).flush()
    expect(fn).not.toHaveBeenCalled()
  })

  it('cancel 丢弃待执行调用', () => {
    const fn = vi.fn()
    const d = debounce(fn, 300)

    d('discarded')
    d.cancel()
    vi.advanceTimersByTime(300)

    expect(fn).not.toHaveBeenCalled()
  })

  it('cancel 后 flush 不会执行被取消的调用', () => {
    const fn = vi.fn()
    const d = debounce(fn, 300)

    d('discarded')
    d.cancel()
    d.flush()

    expect(fn).not.toHaveBeenCalled()
  })
})

/**
 * 跨标签页同步（`onPersistedChange` / `broadcastPersistChange`）。
 *
 * 这一对是 `scan_export_coverage.py`（本轮新增）报出来的未覆盖导出。
 * 上面 34 例把持久化本身测得很扎实，却完全没碰这两个——
 * 而它们有真实消费方：`stores/chat.ts` 靠 `onPersistedChange`
 * 在其他标签页改动会话后拉取最新数据。
 *
 * ── 要守的两条契约 ────────────────────────────────────────────
 * 1. **自己发的消息自己不处理**。`broadcastPersistChange` 会带上
 *    `origin: TAB_ID`，接收端见到自己的 TAB_ID 必须跳过。
 *    漏了这一步，本标签页保存 → 自己收到通知 → 重新 load →
 *    覆盖掉自己刚写的内存态，正在输入的内容会被回滚。
 *
 * 2. **退订必须真的断开**。`onPersistedChange` 返回退订函数，
 *    组件卸载后若仍在监听，回调里读的是已销毁的 store，
 *    表现为「切走了还在后台改数据」。
 *
 * ── 关于 BroadcastChannel 的可测性 ────────────────────────────
 * jsdom 从 22 起内置 BroadcastChannel，但**同一进程内的两个实例
 * 不会互相投递**（浏览器里是跨 tab 才投递）。所以这里不去模拟
 * 「另一个 tab」，而是直接触发 `storage` 事件——那是同一套 listeners
 * 集合的另一条入口，且 storage 事件在 jsdom 里可以手工派发。
 */
describe('跨标签页同步', () => {
  const PREFIX = '__store__:'

  /** 手工派发一个 storage 事件，模拟「另一个标签页改了某个键」 */
  const fireStorage = (key: string | null) => {
    window.dispatchEvent(new StorageEvent('storage', { key }))
  }

  it('订阅后，其他标签页改动会触发回调并带上去前缀的键名', () => {
    const seen: string[] = []
    const off = onPersistedChange((k) => seen.push(k))

    fireStorage(PREFIX + 'chat-sessions')

    // 回调拿到的应是业务键名，不带 __store__: 前缀——
    // 消费方（chat store）是拿它和自己的 PERSIST_KEY 直接比对的
    expect(seen).toEqual(['chat-sessions'])
    off()
  })

  it('非本库的键不触发回调', () => {
    // localStorage 是同源共享的，别的库（甚至浏览器插件）也会写。
    // 不做前缀过滤的话，任何无关写入都会让 chat store 白重载一次
    const fn = vi.fn()
    const off = onPersistedChange(fn)

    fireStorage('some-other-lib-key')
    fireStorage(null)

    expect(fn).not.toHaveBeenCalled()
    off()
  })

  it('退订后不再收到通知', () => {
    const fn = vi.fn()
    const off = onPersistedChange(fn)

    fireStorage(PREFIX + 'k1')
    expect(fn).toHaveBeenCalledTimes(1)

    off()
    fireStorage(PREFIX + 'k2')

    // 仍是 1：退订之后那次不该再进来
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('多个订阅者互不影响，退订其一不影响其二', () => {
    const a = vi.fn()
    const b = vi.fn()
    const offA = onPersistedChange(a)
    const offB = onPersistedChange(b)

    fireStorage(PREFIX + 'k')
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)

    offA()
    fireStorage(PREFIX + 'k')

    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(2)
    offB()
  })

  it('某个订阅者抛异常时，其余订阅者仍被通知', () => {
    // 实现里每个回调都包了 try/catch（注释写的是 "consumer bug"）。
    // 不包的话，一个组件的回调出错会让所有其他组件都收不到同步——
    // 而这类错误在生产上极难定位：坏的是 A，症状出在 B
    const boom = vi.fn(() => { throw new Error('consumer bug') })
    const ok = vi.fn()
    const off1 = onPersistedChange(boom)
    const off2 = onPersistedChange(ok)

    expect(() => fireStorage(PREFIX + 'k')).not.toThrow()
    expect(boom).toHaveBeenCalled()
    expect(ok).toHaveBeenCalled()

    off1()
    off2()
  })

  it('broadcastPersistChange 不会触发本标签页自己的订阅者', () => {
    // 自己发自己收的话：保存 → 收到通知 → 重新 load →
    // 覆盖掉刚写进内存的值，用户正在输入的内容被回滚
    const fn = vi.fn()
    const off = onPersistedChange(fn)

    broadcastPersistChange('chat-sessions')

    expect(fn).not.toHaveBeenCalled()
    off()
  })

  it('BroadcastChannel 不可用时 broadcast 静默降级，不抛异常', () => {
    // 老浏览器 / 某些隐私模式下没有 BroadcastChannel。
    // 此时跨 tab 同步能力退化（靠 storage 事件兜底），但不能崩
    const original = globalThis.BroadcastChannel
    // @ts-expect-error 故意置空以模拟环境缺失
    delete globalThis.BroadcastChannel

    expect(() => broadcastPersistChange('k')).not.toThrow()

    globalThis.BroadcastChannel = original
  })
})
