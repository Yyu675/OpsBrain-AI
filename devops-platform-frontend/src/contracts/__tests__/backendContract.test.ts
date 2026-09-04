/**
 * 前后端契约一致性测试（消费后端导出的真值）。
 *
 * ── 这一层解决什么 ────────────────────────────────────────────
 * 项目里已有几个「契约测试」，但它们断言的是**手工镜像的一份数字**：
 *
 *     const BACKEND_LIMITS = { title: 255, description: 20000 }  // 抄自后端
 *
 * 这能防住「改了一侧忘另一侧」，却防不住「镜像本身抄错」——镜像仍是手写的。
 *
 * 本文件读的是 `backend-contract.json`，由后端 `ContractExportTest`
 * **用反射从真实代码导出**（@Size 注解、Status.nextStates、BizError 枚举）。
 * 单一真相源是后端代码本身：改了后端不重新导出，这里失败；
 * 导出了但前端没跟上，这里也失败。
 *
 * ── 为什么把 JSON 提交进版本库 ────────────────────────────────
 * 前端 CI 不应依赖「先跑一遍后端测试」。文件进库后前端可独立运行，
 * 且契约变更会出现在 PR diff 里——评审时一眼能看到「这次改了状态机」，
 * 而不是埋在某个 Java 文件的第 60 行。
 *
 * ── 已经用它抓到过什么 ────────────────────────────────────────
 * 状态机曾漂移出 8 处不一致（前端 UI 手写枚举 vs 后端 canTransition），
 * 其中「已解决/已关闭 → 重新打开」被前端误禁用，故障复发只能新建工单，
 * 导致同一故障历史被拆成两张单、MTTR 统计失真。
 */
import { describe, expect, it } from 'vitest'

import contract from '../backend-contract.json'
import {
  TICKET_STATUS_OPTIONS,
  canTransitionStatus,
  isTerminalStatus,
  type TicketStatus,
} from '@/constants/ticket'
import { BIZ_ERRORS } from '@/constants/bizCode'

/** 后端大写状态 → 前端小写状态 */
const toFrontend = (s: string): TicketStatus => s.toLowerCase() as TicketStatus

describe('契约 · 工单状态机', () => {
  it('前端状态集合与后端完全一致', () => {
    const backend = contract.ticketStatus.all.map(toFrontend).sort()
    const frontend = TICKET_STATUS_OPTIONS.map(o => o.value).sort()
    expect(frontend).toEqual(backend)
  })

  /**
   * 逐 from×to 组合比对，而非只测几个样例。
   * 5×5 共 25 组，任何一处漂移都会被点名。
   */
  it('每一对状态的流转判定都与后端一致', () => {
    const mismatches: string[] = []

    for (const from of contract.ticketStatus.all) {
      const allowed = new Set(
        (contract.ticketStatus.transitions as Record<string, string[]>)[from] ?? []
      )
      for (const to of contract.ticketStatus.all) {
        // 后端 canTransition 对同态返回 true（幂等重试不应报错）
        const backendOk = from === to || allowed.has(to)
        const frontendOk = canTransitionStatus(toFrontend(from), toFrontend(to))
        if (backendOk !== frontendOk) {
          mismatches.push(`${from}→${to}: 后端=${backendOk} 前端=${frontendOk}`)
        }
      }
    }

    expect(mismatches, `状态机漂移:\n${mismatches.join('\n')}`).toEqual([])
  })

  it('终态集合一致 —— 作废不可逆是审计要求', () => {
    const backendTerminal = contract.ticketStatus.terminal.map(toFrontend).sort()
    const frontendTerminal = contract.ticketStatus.all
      .map(toFrontend)
      .filter(isTerminalStatus)
      .sort()
    expect(frontendTerminal).toEqual(backendTerminal)
  })

  it('重开路径必须开放 —— 这是曾被误禁用的核心场景', () => {
    // 从契约读，而不是写死 true：万一后端真的改了规则，这里会如实反映
    const resolvedNext = (contract.ticketStatus.transitions as Record<string, string[]>).RESOLVED
    const closedNext = (contract.ticketStatus.transitions as Record<string, string[]>).CLOSED
    expect(resolvedNext).toContain('PROCESSING')
    expect(closedNext).toContain('PROCESSING')

    expect(canTransitionStatus('resolved', 'processing')).toBe(true)
    expect(canTransitionStatus('closed', 'processing')).toBe(true)
  })
})

describe('契约 · 工单字段长度', () => {
  /**
   * 前端 TicketFormDialog 使用的上限。
   * 改这里必须同时改组件里的 TITLE_MAX / DESC_MAX。
   */
  const FRONTEND = { title: 255, description: 20000 } as const

  it.each(Object.keys(FRONTEND) as Array<keyof typeof FRONTEND>)(
    '%s 的前端上限不超过后端 @Size',
    (field) => {
      const backendMax = (contract.fieldLimits.createTicket as Record<string, number>)[field]
      expect(backendMax, `后端契约缺少字段 ${field}`).toBeGreaterThan(0)
      // 前端可以更严（提前拦截省一次往返），绝不能更松
      // （更松则用户能提交、后端返回 40001，错误信息里是后端字段名，用户不知道改哪儿）
      expect(FRONTEND[field]).toBeLessThanOrEqual(backendMax)
    }
  )

  it('描述上限与后端一致 —— 曾严一个数量级导致粘贴的堆栈被静默截断', () => {
    const backendMax = (contract.fieldLimits.createTicket as Record<string, number>).description
    expect(FRONTEND.description).toBe(backendMax)
  })

  it('契约包含所有受限字段，没有遗漏导出', () => {
    const fields = Object.keys(contract.fieldLimits.createTicket)
    // 这几个是表单会提交的，必须在契约里有约束记录
    expect(fields).toEqual(expect.arrayContaining(['title', 'description', 'assignee', 'category']))
  })
})

describe('契约 · 业务码', () => {
  it('前端映射表里的每个码都真实存在于后端 —— 不映射不存在的码', () => {
    const backendCodes = new Set(contract.bizCodes.map(c => c.code))
    const unknown = Object.keys(BIZ_ERRORS)
      .map(Number)
      .filter(code => !backendCodes.has(code))

    expect(unknown, `前端映射了后端不存在的业务码: ${unknown.join(', ')}`).toEqual([])
  })

  it('后端业务码无重复 —— 重复会让前端映射到错误的处置建议', () => {
    const codes = contract.bizCodes.map(c => c.code)
    expect(new Set(codes).size).toBe(codes.length)
  })

  it('前端已覆盖高频业务码的处置建议', () => {
    // 这几个是用户最常撞上、且「下一步该做什么」差别最大的
    for (const code of [40001, 40004, 40009, 40021, 40101]) {
      expect(BIZ_ERRORS[code], `业务码 ${code} 缺少前端处置建议`).toBeTruthy()
      expect(BIZ_ERRORS[code].hint, `业务码 ${code} 缺少「下一步做什么」`).toBeTruthy()
    }
  })

  it('可重试语义在契约中有明确取值 —— 前端据此决定要不要给「重试」按钮', () => {
    const validRetry = new Set(['NEVER', 'SAFE', 'BACKOFF', 'CLIENT'])
    for (const c of contract.bizCodes) {
      expect(validRetry.has(c.retry), `${c.name} 的 retry 取值异常: ${c.retry}`).toBe(true)
    }
  })

  /**
   * 这是本文件最有价值的一条：前端 BIZ_ERRORS 也带 retry 字段，
   * 两侧对「这个错误能不能重试」的判断必须一致。
   *
   * 不一致的后果很具体：后端标 NEVER（重试结果必然相同）而前端标 SAFE，
   * 前端就会自动重试——对 40021 内容重复这类错误，等于反复提交必然失败的请求；
   * 反过来后端 SAFE 前端 NEVER，则本该自动恢复的抖动被当成硬失败甩给用户。
   */
  it('retry 语义与后端逐码一致 —— 决定是自动重试还是直接报错', () => {
    const backendRetry = new Map(contract.bizCodes.map(c => [c.code, c.retry]))
    const mismatches: string[] = []

    for (const [codeStr, meta] of Object.entries(BIZ_ERRORS)) {
      const code = Number(codeStr)
      const backend = backendRetry.get(code)
      if (backend && backend !== meta.retry) {
        mismatches.push(`${code}: 后端=${backend} 前端=${meta.retry}`)
      }
    }

    expect(mismatches, `retry 语义漂移:\n${mismatches.join('\n')}`).toEqual([])
  })
})

describe('契约文件自身的完整性', () => {
  it('三个契约段都非空 —— 导出失败时不能让测试「静默通过」', () => {
    expect(contract.ticketStatus.all.length).toBeGreaterThan(0)
    expect(Object.keys(contract.fieldLimits.createTicket).length).toBeGreaterThan(0)
    expect(contract.bizCodes.length).toBeGreaterThan(0)
  })
})
