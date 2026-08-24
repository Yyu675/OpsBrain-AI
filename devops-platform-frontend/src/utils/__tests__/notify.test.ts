/**
 * 服务端错误统一处理测试。
 *
 * handleServerError 保护的核心契约（详见 notify.ts 函数注释）：
 * - 一律经 toFriendlyError 做业务码映射，不透传后端原始 message
 * - 主动取消不当作错误弹提示
 * - 走 notify 防抖去重，批量失败不刷屏
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'

import { handleServerError, notify } from '../notify'
import { HttpError } from '../http'

/** ElMessage 是被 mock 的对象形式调用（notify 内部用 ElMessage({...})） */
vi.mock('element-plus', () => ({
  ElMessage: vi.fn(),
}))

const messageMock = vi.mocked(ElMessage)

/** 取最近一次弹出的文案 */
function lastMessage(): string {
  const calls = messageMock.mock.calls
  if (calls.length === 0) return ''
  return (calls[calls.length - 1][0] as { message: string }).message
}

function lastType(): string {
  const calls = messageMock.mock.calls
  if (calls.length === 0) return ''
  return (calls[calls.length - 1][0] as { type: string }).type
}

beforeEach(() => {
  messageMock.mockClear()
  // 冷却表是模块级状态，用例间必须清理，否则同文案的后续断言被去重吃掉
  notify.clearCooldown()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('业务码映射（核心：不透传后端原始文案）', () => {
  it('40009 版本冲突给出「刷新后重新提交」的处置，而非只复述冲突描述', () => {
    handleServerError(
      new HttpError('该记录已被他人修改（你基于第 0 版编辑，当前已是第 1 版）', 200, 'BIZ', null, 40009)
    )

    const msg = lastMessage()
    expect(msg).toContain('数据已被修改')
    expect(msg).toContain('刷新')
  })

  it('40021 内容重复给出「修改内容后重试」', () => {
    handleServerError(new HttpError('内容与已有文档重复', 200, 'BIZ', null, 40021))

    const msg = lastMessage()
    expect(msg).toContain('内容重复')
    expect(msg).toContain('修改内容')
  })

  it('40004 数据不存在引导刷新列表', () => {
    handleServerError(new HttpError('工单不存在', 200, 'BIZ', null, 40004))
    expect(lastMessage()).toContain('刷新')
  })

  it('403 无权限引导联系管理员 —— 对应 ADMIN-only 操作被拦', () => {
    handleServerError(new HttpError('forbidden', 403, 'HTTP_STATUS'))
    expect(lastMessage()).toContain('管理员')
  })

  it('超时提示指向后端服务状态，而非笼统的「请求失败」', () => {
    handleServerError(new HttpError('timeout', 0, 'TIMEOUT'))
    expect(lastMessage()).toContain('后端服务')
  })

  it('弹出类型为 error', () => {
    handleServerError(new HttpError('x', 500, 'HTTP_STATUS', {}))
    expect(lastType()).toBe('error')
  })

  it('返回映射后的 FriendlyError，供调用方写入页面级错误态', () => {
    const r = handleServerError(new HttpError('x', 404, 'HTTP_STATUS'))
    expect(r.title).toBe('资源不存在')
    expect(r.detail).toBeTruthy()
  })
})

describe('action 参数', () => {
  it('传 action 时标题为「{动作}失败」', () => {
    handleServerError(new HttpError('x', 500, 'HTTP_STATUS', {}), { action: '发布文档' })
    expect(lastMessage()).toContain('发布文档失败')
  })

  it('不传 action 时用 toFriendlyError 的通用标题', () => {
    handleServerError(new HttpError('x', 404, 'HTTP_STATUS'))
    expect(lastMessage()).toContain('资源不存在')
  })
})

describe('withHint 参数', () => {
  it('默认连带展示处置建议', () => {
    handleServerError(new HttpError('x', 200, 'BIZ', null, 40009))
    expect(lastMessage()).toContain('（')
  })

  it('withHint=false 时只给标题与详情', () => {
    handleServerError(new HttpError('工单不存在', 200, 'BIZ', null, 40004), {
      action: '删除工单',
      withHint: false,
    })

    const msg = lastMessage()
    expect(msg).toBe('删除工单失败：工单不存在')
    expect(msg).not.toContain('刷新')
  })
})

describe('主动取消不弹提示', () => {
  it('AbortError 不弹 —— 用户点「停止生成」不该看到「操作失败」', () => {
    const e = new Error('aborted')
    e.name = 'AbortError'
    handleServerError(e)

    expect(messageMock).not.toHaveBeenCalled()
  })

  it('TimeoutError（AbortController 超时中止）不弹', () => {
    const e = new Error('timeout')
    e.name = 'TimeoutError'
    handleServerError(e)

    expect(messageMock).not.toHaveBeenCalled()
  })

  it('ElMessageBox 取消抛出的 cancel 字符串不弹 —— 关弹窗不是故障', () => {
    handleServerError('cancel')
    expect(messageMock).not.toHaveBeenCalled()
  })

  it('ElMessageBox 取消抛出的 action 对象不弹', () => {
    handleServerError({ action: 'cancel' })
    expect(messageMock).not.toHaveBeenCalled()
  })

  it('取消时仍返回 FriendlyError，不影响调用方的返回值使用', () => {
    const e = new Error('aborted')
    e.name = 'AbortError'
    expect(handleServerError(e).title).toBeTruthy()
  })

  it('普通业务错误照常弹出（对照组）', () => {
    handleServerError(new HttpError('x', 500, 'HTTP_STATUS', {}))
    expect(messageMock).toHaveBeenCalledTimes(1)
  })
})

describe('防抖去重（批量操作失败不刷屏）', () => {
  it('同一错误连续触发只弹一次 —— 批量删除 10 条全失败不该弹 10 个提示', () => {
    const err = new HttpError('工单不存在', 200, 'BIZ', null, 40004)
    for (let i = 0; i < 10; i++) handleServerError(err, { action: '删除工单' })

    expect(messageMock).toHaveBeenCalledTimes(1)
  })

  it('不同错误各自弹出，不被误合并', () => {
    handleServerError(new HttpError('x', 200, 'BIZ', null, 40004), { action: '删除' })
    handleServerError(new HttpError('y', 200, 'BIZ', null, 40009), { action: '更新' })

    expect(messageMock).toHaveBeenCalledTimes(2)
  })

  it('显式 key 相同时合并 —— 供调用方按业务语义控制去重粒度', () => {
    handleServerError(new HttpError('a', 200, 'BIZ', null, 40004), { key: 'batch' })
    handleServerError(new HttpError('b', 200, 'BIZ', null, 40009), { key: 'batch' })

    expect(messageMock).toHaveBeenCalledTimes(1)
  })

  it('冷却期过后同一错误可再次弹出 —— 用户重试失败需要得到反馈', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 7, 23, 12, 0, 0))

    const err = new HttpError('工单不存在', 200, 'BIZ', null, 40004)
    handleServerError(err, { action: '删除工单' })
    expect(messageMock).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(1500)
    handleServerError(err, { action: '删除工单' })
    expect(messageMock).toHaveBeenCalledTimes(2)
  })

  it('自定义 cooldown 生效', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 7, 23, 12, 0, 0))

    const err = new HttpError('x', 200, 'BIZ', null, 40004)
    handleServerError(err, { cooldown: 5000 })
    vi.advanceTimersByTime(2000)
    handleServerError(err, { cooldown: 5000 })

    expect(messageMock).toHaveBeenCalledTimes(1)
  })
})

describe('ElMessage 不可用时的降级', () => {
  it('ElMessage 抛错时 handleServerError 不外抛 —— 提示失败不应连带中断业务流程', () => {
    messageMock.mockImplementationOnce(() => {
      throw new Error('ElMessage unavailable')
    })

    expect(() => handleServerError(new HttpError('x', 500, 'HTTP_STATUS', {}))).not.toThrow()
  })
})

describe('notify 基础方法', () => {
  it('success / warning / info 各自映射到对应类型', () => {
    notify.success('已保存')
    expect(lastType()).toBe('success')

    notify.warning('注意')
    expect(lastType()).toBe('warning')

    notify.info('提示')
    expect(lastType()).toBe('info')
  })

  it('error 的展示时长长于其他类型 —— 错误需要更多阅读时间', () => {
    notify.error('失败了')
    const errorCall = messageMock.mock.calls.at(-1)![0] as { duration: number }

    notify.success('成功了')
    const successCall = messageMock.mock.calls.at(-1)![0] as { duration: number }

    expect(errorCall.duration).toBeGreaterThan(successCall.duration)
  })

  it('clearCooldown 指定键时只清该键', () => {
    notify.error('A')
    notify.error('B')
    expect(messageMock).toHaveBeenCalledTimes(2)

    notify.clearCooldown('error:A')
    notify.error('A')
    notify.error('B')

    // A 被清除可再弹，B 仍在冷却
    expect(messageMock).toHaveBeenCalledTimes(3)
  })
})
