import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

import { BIZ_ERRORS, getBizError, isAutoRetryable } from '../bizCode'

/**
 * 前后端业务码契约测试。
 *
 * 保护的契约：**后端 BizError 枚举与前端 BIZ_ERRORS 表必须一一对应**。
 *
 * 这类不一致没有任何编译期信号——后端加个码、前端没同步，
 * 用户就只看到一句无意义的兜底文案，而开发侧毫无察觉。
 * 所以直接读后端源码做交叉校验，让 CI 兜住。
 */

// 用 import.meta.url 而非 __dirname：本项目是 ESM（package.json type=module），
// 且 tsconfig.app.json 的 types 只含 vite/client，没有 node 全局类型。
const HERE = dirname(fileURLToPath(import.meta.url))
const BACKEND_ENUM = resolve(
  HERE,
  '../../../../src/main/java/com/devops/agent/common/error/BizError.java'
)

/** 从 Java 枚举源码里解析出 (code, retry) 对 */
function parseBackendCodes(): Map<number, string> {
  const src = readFileSync(BACKEND_ENUM, 'utf-8')
  const out = new Map<number, string>()
  // 形如：NAME(40009, HttpStatus.CONFLICT, Retry.CLIENT, "...")
  const re = /^\s*[A-Z_]+\((\d{5}),\s*HttpStatus\.[A-Z_]+,\s*Retry\.([A-Z]+),/gm
  let m: RegExpExecArray | null
  while ((m = re.exec(src)) !== null) {
    out.set(Number(m[1]), m[2])
  }
  return out
}

describe('业务码前后端契约', () => {
  it('能从后端枚举解析出错误码（解析失败说明枚举格式变了，需同步本测试）', () => {
    if (!existsSync(BACKEND_ENUM)) {
      // 前端被单独拆仓时跳过，而不是误报失败
      return
    }
    expect(parseBackendCodes().size).toBeGreaterThan(10)
  })

  it('后端每个错误码在前端都有对应文案', () => {
    if (!existsSync(BACKEND_ENUM)) return
    const backend = parseBackendCodes()

    const missing = [...backend.keys()].filter((c) => !(c in BIZ_ERRORS))

    expect(
      missing,
      `以下后端错误码在前端 bizCode.ts 中缺失，用户会看到无意义的兜底文案：${missing.join(', ')}`
    ).toEqual([])
  })

  it('前端不存在后端已移除的僵尸错误码', () => {
    if (!existsSync(BACKEND_ENUM)) return
    const backend = parseBackendCodes()

    const stale = Object.keys(BIZ_ERRORS)
      .map(Number)
      .filter((c) => !backend.has(c))

    expect(stale, `以下前端错误码后端已不存在，应清理：${stale.join(', ')}`).toEqual([])
  })

  it('重试语义两侧一致——不一致会导致前端对不可重试的错误反复重试', () => {
    if (!existsSync(BACKEND_ENUM)) return
    const backend = parseBackendCodes()

    const conflicts: string[] = []
    backend.forEach((retry, code) => {
      const fe = BIZ_ERRORS[code]
      if (fe && fe.retry !== retry) {
        conflicts.push(`${code}: 后端=${retry} 前端=${fe.retry}`)
      }
    })

    expect(conflicts, `重试语义冲突：${conflicts.join('; ')}`).toEqual([])
  })
})

describe('bizCode 查表行为', () => {
  it('已知码返回标题与提示', () => {
    const meta = getBizError(40009)

    expect(meta?.title).toBe('数据已被他人修改')
    expect(meta?.hint).toBeTruthy()
  })

  it('未知码与 undefined 返回 undefined，由调用方兜底', () => {
    expect(getBizError(99999)).toBeUndefined()
    expect(getBizError(undefined)).toBeUndefined()
  })

  it('每条文案都给出「下一步做什么」，而不只是报错', () => {
    const withoutHint = Object.entries(BIZ_ERRORS)
      .filter(([, v]) => !v.hint)
      .map(([k]) => k)

    expect(withoutHint, `以下错误码缺少 hint：${withoutHint.join(', ')}`).toEqual([])
  })

  it('限流与上游超时可自动重试，权限类不可', () => {
    expect(isAutoRetryable(42901)).toBe(true)
    expect(isAutoRetryable(50210)).toBe(true)
    expect(isAutoRetryable(40103)).toBe(false)
    // 乐观锁是 CLIENT：要用户看到最新数据后自己决定，不能自动重试
    expect(isAutoRetryable(40009)).toBe(false)
  })
})
