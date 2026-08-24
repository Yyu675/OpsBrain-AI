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
  clearBackup,
  clearPersisted,
  debounce,
  getBackup,
  loadPersisted,
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
