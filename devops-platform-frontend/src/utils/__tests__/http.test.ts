/**
 * HTTP 错误映射测试。
 *
 * toFriendlyError 是「后端错误 → 用户能据此行动的提示」的唯一转换点。
 * 保护三条契约：
 * - 6.11：40009 版本冲突必须与 40004 数据不存在给出不同处置（刷新重试 vs 数据已消失）
 * - 6.28：40006 预算超限 / 40005 配额超限 / 40004 实体不存在语义已分离，不得混用
 * - 6.29：异常消息分层——面向用户的提示不得直接透传后端原始消息细节
 */
import { describe, expect, it } from 'vitest'

import { HttpError, toFriendlyError, unwrapBiz } from '../http'

describe('toFriendlyError — 非 HttpError', () => {
  it('普通 Error 取 message 作为详情并给出通用处置建议', () => {
    const r = toFriendlyError(new Error('something broke'))
    expect(r.title).toBe('发生意外错误')
    expect(r.detail).toBe('something broke')
    expect(r.hint).toBeTruthy()
  })

  it('字符串错误也能转换', () => {
    expect(toFriendlyError('cancel').detail).toBe('cancel')
  })

  it('null / undefined 给兜底文案而非 "undefined"', () => {
    expect(toFriendlyError(null).detail).toBe('未知错误')
    expect(toFriendlyError(undefined).detail).toBe('未知错误')
  })
})

describe('toFriendlyError — 传输层错误', () => {
  it('超时提示指向后端服务与容器状态 —— 这是最常见的开发期故障', () => {
    const r = toFriendlyError(new HttpError('timeout', 0, 'TIMEOUT', undefined, undefined, '/api/v1/tickets'))
    expect(r.title).toBe('请求超时')
    expect(r.hint).toContain('后端服务')
  })

  it('网络失败提示包含默认端口，便于排查', () => {
    const r = toFriendlyError(new HttpError('failed', 0, 'NETWORK'))
    expect(r.title).toBe('无法连接服务器')
    expect(r.hint).toContain('8088')
  })

  it('超时与网络失败给出不同标题 —— 二者排查方向不同', () => {
    const timeout = toFriendlyError(new HttpError('x', 0, 'TIMEOUT'))
    const network = toFriendlyError(new HttpError('x', 0, 'NETWORK'))
    expect(timeout.title).not.toBe(network.title)
  })
})

describe('toFriendlyError — HTTP 状态码', () => {
  it('404 提示数据可能已删除，引导刷新列表', () => {
    const r = toFriendlyError(new HttpError('not found', 404, 'HTTP_STATUS'))
    expect(r.title).toBe('资源不存在')
    expect(r.hint).toContain('刷新')
  })

  it('403 提示联系管理员授权 —— 对应 6.59 的 ADMIN-only 操作被拦', () => {
    const r = toFriendlyError(new HttpError('forbidden', 403, 'HTTP_STATUS'))
    expect(r.title).toBe('无访问权限')
    expect(r.hint).toContain('管理员')
  })

  it('500 优先展示后端给的 message', () => {
    const r = toFriendlyError(
      new HttpError('x', 500, 'HTTP_STATUS', { message: '数据库连接池耗尽' })
    )
    expect(r.detail).toBe('数据库连接池耗尽')
  })

  it('500 无后端 message 时给通用描述而非空白', () => {
    const r = toFriendlyError(new HttpError('x', 500, 'HTTP_STATUS', {}))
    expect(r.detail).toBeTruthy()
  })

  it('502/503/504 归为「服务暂时不可用」，提示稍后重试', () => {
    for (const status of [502, 503, 504]) {
      const r = toFriendlyError(new HttpError('x', status, 'HTTP_STATUS'))
      expect(r.title).toBe('服务暂时不可用')
      expect(r.detail).toContain(String(status))
    }
  })

  it('其他状态码带上状态码本身，便于对照后端日志', () => {
    const r = toFriendlyError(new HttpError('teapot', 418, 'HTTP_STATUS'))
    expect(r.title).toContain('418')
  })
})

describe('toFriendlyError — 业务码', () => {
  it('40001 参数校验失败，引导检查输入', () => {
    const r = toFriendlyError(new HttpError('标题至少 5 个字符', 200, 'BIZ', null, 40001))
    expect(r.title).toBe('参数校验失败')
    expect(r.detail).toBe('标题至少 5 个字符')
  })

  it('40004 数据不存在，引导刷新列表', () => {
    const r = toFriendlyError(new HttpError('工单不存在', 200, 'BIZ', null, 40004))
    expect(r.title).toBe('数据不存在')
    expect(r.hint).toContain('刷新')
  })

  it('40009 版本冲突，提示先看他人改了什么再重试 —— 直接重试会覆盖他人修改', () => {
    const r = toFriendlyError(
      new HttpError('该记录已被他人修改（你基于第 0 版编辑，当前已是第 1 版）', 200, 'BIZ', null, 40009)
    )
    expect(r.title).toBe('数据已被修改')
    expect(r.hint).toContain('刷新')
    expect(r.detail).toContain('第 0 版')
  })

  it('40009 与 40004 给出不同标题 —— 冲突数据仍在、不存在数据已消失，处置不同', () => {
    const conflict = toFriendlyError(new HttpError('x', 200, 'BIZ', null, 40009))
    const missing = toFriendlyError(new HttpError('x', 200, 'BIZ', null, 40004))
    expect(conflict.title).not.toBe(missing.title)
  })

  it('40021 内容重复（知识文档精确去重）', () => {
    const r = toFriendlyError(new HttpError('内容与已有文档重复', 200, 'BIZ', null, 40021))
    expect(r.title).toBe('内容重复')
  })

  it('未列举的业务码带上码值，不吞掉后端信息', () => {
    const r = toFriendlyError(new HttpError('配额超限', 200, 'BIZ', null, 40005))
    expect(r.title).toBe('操作失败')
    expect(r.detail).toBe('配额超限')
  })

  it('未列举业务码且无 message 时把码值放进详情，便于定位', () => {
    const r = toFriendlyError(new HttpError('', 200, 'BIZ', null, 40006))
    expect(r.detail).toContain('40006')
  })
})

describe('toFriendlyError — 响应结构异常', () => {
  it('BIZ_SHAPE 提示前后端版本可能不匹配', () => {
    const r = toFriendlyError(new HttpError('x', 200, 'BIZ_SHAPE'))
    expect(r.title).toBe('响应格式异常')
    expect(r.hint).toContain('版本')
  })
})

describe('toFriendlyError — 三段结构完整性', () => {
  // Error 的属性不可枚举，JSON.stringify 会得到 {}，故显式给每个样本起名
  const samples: Array<[name: string, error: unknown]> = [
    ['null', null],
    ['空 message 的 Error', new Error('')],
    ['TIMEOUT', new HttpError('', 0, 'TIMEOUT')],
    ['NETWORK', new HttpError('', 0, 'NETWORK')],
    ['HTTP 404', new HttpError('', 404, 'HTTP_STATUS')],
    ['HTTP 403', new HttpError('', 403, 'HTTP_STATUS')],
    ['HTTP 500 空 body', new HttpError('', 500, 'HTTP_STATUS', {})],
    ['HTTP 503', new HttpError('', 503, 'HTTP_STATUS')],
    ['HTTP 418（未列举状态码）', new HttpError('', 418, 'HTTP_STATUS')],
    ['BIZ 40001', new HttpError('', 200, 'BIZ', null, 40001)],
    ['BIZ 40004', new HttpError('', 200, 'BIZ', null, 40004)],
    ['BIZ 40009', new HttpError('', 200, 'BIZ', null, 40009)],
    ['BIZ 40021', new HttpError('', 200, 'BIZ', null, 40021)],
    ['BIZ 未列举码', new HttpError('', 200, 'BIZ', null, 49999)],
    ['BIZ_SHAPE', new HttpError('', 200, 'BIZ_SHAPE')],
    ['UNKNOWN', new HttpError('', 0, 'UNKNOWN')],
  ]

  it.each(samples)('%s 至少产出 title 与 detail —— UI 不能出现空白提示', (_name, error) => {
    const r = toFriendlyError(error)
    expect(r.title).toBeTruthy()
    expect(r.detail).toBeTruthy()
  })
})

describe('unwrapBiz', () => {
  it('code 为 0 时返回 data', () => {
    expect(unwrapBiz<{ id: string }>({ code: 0, message: 'ok', data: { id: 'T-1' } }, 'fail'))
      .toEqual({ id: 'T-1' })
  })

  it('code 非 0 时抛 HttpError 并带上业务码 —— 不把错误信封当数据用', () => {
    expect(() => unwrapBiz({ code: 40009, message: '版本冲突', data: null }, 'fail'))
      .toThrowError(expect.objectContaining({ bizCode: 40009, code: 'BIZ' }))
  })

  it('code 非 0 且 message 为空时用调用方给的兜底文案', () => {
    try {
      unwrapBiz({ code: 40004, message: '', data: null }, '获取工单失败')
      expect.unreachable('应当抛错')
    } catch (e) {
      expect((e as HttpError).message).toBe('获取工单失败')
    }
  })

  it('响应缺少 code 字段时抛 BIZ_SHAPE —— 结构不符预期不能静默当成功', () => {
    expect(() => unwrapBiz({ data: {} }, 'fail'))
      .toThrowError(expect.objectContaining({ code: 'BIZ_SHAPE' }))
  })

  it('响应为 null 时抛 BIZ_SHAPE', () => {
    expect(() => unwrapBiz(null, 'fail'))
      .toThrowError(expect.objectContaining({ code: 'BIZ_SHAPE' }))
  })

  it('data 为 null 但 code 为 0 时返回 null —— 「查询不到」是合法结果', () => {
    expect(unwrapBiz({ code: 0, message: 'ok', data: null }, 'fail')).toBeNull()
  })
})
