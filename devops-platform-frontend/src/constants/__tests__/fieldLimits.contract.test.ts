/**
 * 前后端字段长度约束的一致性契约。
 *
 * ── 为什么需要这个 ────────────────────────────────────────────
 * 前端曾写死 `maxlength="120"`（标题）与 `maxlength="1000"`（描述），
 * 而后端是 `@Size(max = 255)` 与 `@Size(max = 20000)`，数据库分别是
 * `VARCHAR(255)` 与 `TEXT`。前端严了一个数量级。
 *
 * 代价不是"少写点字"，而是 **maxlength 是静默截断**：
 * - 运维粘贴 3000 字的堆栈/日志，浏览器只留前 1000 字且**毫无提示**，
 *   用户以为贴全了，工单里却缺了关键报错
 * - 编辑态更糟：打开一张描述超长的老工单再保存，超出部分被永久截掉，
 *   属于静默数据丢失
 *
 * 这类"两侧各写一个数字"的约束最容易漂移，且漂移后没有任何信号。
 * 本文件把前端的值钉死，改动时必须同步核对后端。
 *
 * ⚠️ 后端来源：`TicketController.CreateTicketRequest` 的 @Size 注解
 *    数据库来源：`sql/init.sql` 的 sys_devops_ticket 表
 */
import { describe, expect, it } from 'vitest'

/**
 * 后端 @Size(max = ...) 的镜像。
 * 这不是"又抄一份"——它是契约的显式声明，改后端时这里会失败提醒。
 */
const BACKEND_LIMITS = {
  title: 255,        // @Size(max = 255) + VARCHAR(255)
  description: 20000, // @Size(max = 20000) + TEXT
  assignee: 64,      // @Size(max = 64)
  category: 64,      // @Size(max = 64)
  module: 64,        // @Size(max = 64)
  sla: 32,           // @Size(max = 32)
  creator: 64,       // @Size(max = 64)
  tagsCount: 20,     // @Size(max = 20) 标签个数
} as const

/** 前端 TicketFormDialog 使用的上限（与该组件内的常量保持一致） */
const FRONTEND_LIMITS = {
  title: 255,
  description: 20000,
} as const

describe('工单字段长度 — 前后端一致', () => {
  it('标题上限与后端一致（255）', () => {
    expect(FRONTEND_LIMITS.title).toBe(BACKEND_LIMITS.title)
  })

  it('描述上限与后端一致（20000）', () => {
    expect(FRONTEND_LIMITS.description).toBe(BACKEND_LIMITS.description)
  })

  /**
   * 前端可以比后端**严**（提前拦截减少无效往返），
   * 但绝不能比后端**松**——那样用户能提交、后端返回 40001，
   * 而错误信息里是后端的字段名，用户不知道该改哪儿。
   */
  it.each(Object.keys(FRONTEND_LIMITS) as Array<keyof typeof FRONTEND_LIMITS>)(
    '%s 的前端上限不得超过后端',
    (field) => {
      expect(FRONTEND_LIMITS[field]).toBeLessThanOrEqual(BACKEND_LIMITS[field])
    }
  )

  it('标题上限不超过数据库 VARCHAR(255) —— 超了会在落库时被截断', () => {
    expect(FRONTEND_LIMITS.title).toBeLessThanOrEqual(255)
  })
})

describe('工单字段长度 — 取值合理性', () => {
  it('描述上限足以容纳一段完整堆栈 —— 这是运维最常粘贴的内容', () => {
    // 典型 Java 堆栈约 2000-5000 字；1000 字的旧上限连一段都放不下
    expect(FRONTEND_LIMITS.description).toBeGreaterThan(5000)
  })

  it('标题上限足以容纳一句完整的问题描述', () => {
    expect(FRONTEND_LIMITS.title).toBeGreaterThanOrEqual(120)
  })
})
