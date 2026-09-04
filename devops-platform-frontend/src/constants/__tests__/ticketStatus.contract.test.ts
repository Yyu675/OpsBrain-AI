import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

import {
  canTransitionStatus,
  nextStatuses,
  isTerminalStatus,
  statusOptionsFor,
  type TicketStatus,
} from '../ticket'

/**
 * 工单状态机 —— 前后端一致性契约。
 *
 * 为什么必须锁死：前端这张表只用于**置灰非法选项**（防呆），
 * 后端仍会独立校验。两侧不一致会造成两类割裂：
 *   - 前端比后端宽 → 用户能点，点了报错；
 *   - 前端比后端严 → 能做的操作被置灰，用户以为是 bug。
 * 这类问题没有编译期信号，只能靠交叉校验兜住。
 */

const HERE = dirname(fileURLToPath(import.meta.url))
const BACKEND_ENUM = resolve(
  HERE,
  '../../../../src/main/java/com/devops/agent/domain/biz/entity/TicketEnums.java'
)

/** 解析后端 ALLOWED_TRANSITIONS（Java Map.of 字面量） */
function parseBackendTransitions(): Record<string, string[]> {
  const src = readFileSync(BACKEND_ENUM, 'utf-8')
  const start = src.indexOf('ALLOWED_TRANSITIONS')
  const body = src.slice(start, src.indexOf(');', start))

  const out: Record<string, string[]> = {}
  // 形如：PENDING,    Set.of(PROCESSING, RESOLVED, VOID),
  const re = /(\w+),\s*Set\.of\(([^)]*)\)/g
  let m: RegExpExecArray | null
  while ((m = re.exec(body)) !== null) {
    const from = m[1].toLowerCase()
    const tos = m[2]
      .split(',')
      .map((x) => x.trim().toLowerCase())
      .filter(Boolean)
    out[from] = tos
  }
  return out
}

describe('工单状态机前后端契约', () => {
  it('能解析后端流转表（解析失败说明格式变了，需同步本测试）', () => {
    if (!existsSync(BACKEND_ENUM)) return
    const be = parseBackendTransitions()
    expect(Object.keys(be).length).toBe(5)
  })

  it('每个状态的可流转目标集合两侧完全一致', () => {
    if (!existsSync(BACKEND_ENUM)) return
    const be = parseBackendTransitions()

    const diffs: string[] = []
    Object.entries(be).forEach(([from, tos]) => {
      const fe = [...nextStatuses(from as TicketStatus)].sort()
      const bs = [...tos].sort()
      if (JSON.stringify(fe) !== JSON.stringify(bs)) {
        diffs.push(`${from}: 后端=[${bs}] 前端=[${fe}]`)
      }
    })

    expect(diffs, `状态机分叉：${diffs.join('; ')}`).toEqual([])
  })
})

describe('状态流转判定', () => {
  it('作废是不可逆终态', () => {
    expect(isTerminalStatus('void')).toBe(true)
    expect(nextStatuses('void')).toEqual([])
    for (const to of ['pending', 'processing', 'resolved', 'closed'] as TicketStatus[]) {
      expect(canTransitionStatus('void', to)).toBe(false)
    }
  })

  it('已关闭不能直接退回待处理，但可因复发重开', () => {
    expect(canTransitionStatus('closed', 'pending')).toBe(false)
    expect(canTransitionStatus('closed', 'processing')).toBe(true)
  })

  it('标准闭环畅通', () => {
    expect(canTransitionStatus('pending', 'processing')).toBe(true)
    expect(canTransitionStatus('processing', 'resolved')).toBe(true)
    expect(canTransitionStatus('resolved', 'closed')).toBe(true)
  })

  it('同态幂等，不视为非法', () => {
    expect(canTransitionStatus('closed', 'closed')).toBe(true)
  })

  it('current 缺失时一律不可流转，避免未加载完就误放行', () => {
    expect(canTransitionStatus(undefined, 'processing')).toBe(false)
    expect(nextStatuses(undefined)).toEqual([])
  })
})

describe('statusOptionsFor 驱动 UI 置灰', () => {
  it('非法项被禁用且给出可读原因', () => {
    const opts = statusOptionsFor('pending')
    const closed = opts.find((o) => o.value === 'closed')!

    expect(closed.disabled).toBe(true)
    expect(closed.reason).toContain('不能从')
  })

  it('合法项可用且无原因文案', () => {
    const processing = statusOptionsFor('pending').find((o) => o.value === 'processing')!

    expect(processing.disabled).toBe(false)
    expect(processing.reason).toBeUndefined()
  })

  it('终态下全部禁用，并说明原因是已作废', () => {
    const opts = statusOptionsFor('void')

    // 除自身（幂等）外全部禁用
    expect(opts.filter((o) => o.value !== 'void').every((o) => o.disabled)).toBe(true)
    expect(opts.find((o) => o.value === 'pending')!.reason).toContain('已作废')
  })

  it('返回全部状态项，不做过滤——置灰比消失更利于用户理解', () => {
    expect(statusOptionsFor('pending')).toHaveLength(5)
  })
})
