import { describe, expect, it, vi } from 'vitest'

import { errorMessage, errorName, isAbortLike } from '../errors'

describe('errorMessage', () => {
  it('Error 实例时返回 message', () => {
    expect(errorMessage(new Error('数据库连接超时'))).toBe('数据库连接超时')
  })

  it('Error 的 message 为空时返回兜底文案', () => {
    expect(errorMessage(new Error(''), '兜底')).toBe('兜底')
  })

  it('字符串错误直接返回', () => {
    expect(errorMessage('cancel')).toBe('cancel')
  })

  it('带 message 字段的普通对象取该字段', () => {
    expect(errorMessage({ message: '版本冲突' })).toBe('版本冲突')
  })

  it('message 字段非字符串时返回兜底文案', () => {
    expect(errorMessage({ message: 500 }, '兜底')).toBe('兜底')
  })

  it('null / undefined 返回兜底文案', () => {
    expect(errorMessage(null, '兜底')).toBe('兜底')
    expect(errorMessage(undefined, '兜底')).toBe('兜底')
  })

  it('未指定兜底文案时使用默认值', () => {
    expect(errorMessage(null)).toBe('网络错误')
  })
})

describe('isAbortLike', () => {
  it('AbortError 判为中止', () => {
    const e = new Error('aborted')
    e.name = 'AbortError'
    expect(isAbortLike(e)).toBe(true)
  })

  it('TimeoutError 判为中止', () => {
    const e = new Error('timeout')
    e.name = 'TimeoutError'
    expect(isAbortLike(e)).toBe(true)
  })

  it('ElMessageBox 取消抛出的字符串判为中止', () => {
    expect(isAbortLike('cancel')).toBe(true)
    expect(isAbortLike('close')).toBe(true)
  })

  it('ElMessageBox 取消抛出的 action 对象判为中止', () => {
    expect(isAbortLike({ action: 'cancel' })).toBe(true)
    expect(isAbortLike({ action: 'close' })).toBe(true)
  })

  it('普通业务错误不判为中止', () => {
    expect(isAbortLike(new Error('版本冲突'))).toBe(false)
    expect(isAbortLike({ action: 'confirm' })).toBe(false)
    expect(isAbortLike('版本冲突')).toBe(false)
  })

  it('null / undefined 不判为中止', () => {
    expect(isAbortLike(null)).toBe(false)
    expect(isAbortLike(undefined)).toBe(false)
  })
})

describe('errorName', () => {
  it('Error 实例返回 name', () => {
    expect(errorName(new TypeError('x'))).toBe('TypeError')
  })

  it('带 name 字段的对象返回该字段', () => {
    expect(errorName({ name: 'HttpError' })).toBe('HttpError')
  })

  it('无 name 时返回空串', () => {
    expect(errorName({})).toBe('')
    expect(errorName(null)).toBe('')
  })
})

describe('测试环境自检', () => {
  it('jsdom 环境已就绪且 matchMedia 可用', () => {
    expect(typeof window).toBe('object')
    expect(window.matchMedia('(max-width: 768px)').matches).toBe(false)
  })

  it('localStorage 在用例间被清理', () => {
    expect(localStorage.getItem('leaked-key')).toBeNull()
    localStorage.setItem('leaked-key', '1')
  })

  it('上一个用例写入的 localStorage 不泄漏到本用例', () => {
    expect(localStorage.getItem('leaked-key')).toBeNull()
  })

  it('ResizeObserver 已 mock', () => {
    expect(() => new ResizeObserver(() => {})).not.toThrow()
  })

  it('vi.fn mock 在用例间被清理', () => {
    const fn = vi.fn()
    fn()
    expect(fn).toHaveBeenCalledTimes(1)
  })
})
