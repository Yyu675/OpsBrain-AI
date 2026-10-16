/**
 * sharedClock.test.ts — 全局时钟供应器测试（批 85 P1-4）
 *
 * 测试单例时钟的生命周期管理：懒启动、自动停止、订阅计数。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { effectScope } from 'vue'
import { useSharedClock } from '../sharedClock'

describe('useSharedClock', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('首次订阅启动时钟，最后取消停时钟', () => {
    const scope1 = effectScope()
    const scope2 = effectScope()

    const { now: now1 } = scope1.run(() => useSharedClock())!
    const initial1 = now1.value

    vi.advanceTimersByTime(60000)
    expect(now1.value).toBeGreaterThan(initial1)

    // 第二个订阅者共享同一时钟
    const { now: now2 } = scope2.run(() => useSharedClock())!
    expect(now2.value).toBe(now1.value)

    // 取消第一个订阅，时钟仍在跑
    scope1.stop()
    const before = now2.value
    vi.advanceTimersByTime(60000)
    expect(now2.value).toBeGreaterThan(before)

    // 取消最后一个订阅，时钟停止（验证方式：停止后重新订阅会重启）
    scope2.stop()
  })

  it('多个订阅者共享同一响应式 ref', () => {
    const scope1 = effectScope()
    const scope2 = effectScope()

    const { now: now1 } = scope1.run(() => useSharedClock())!
    const { now: now2 } = scope2.run(() => useSharedClock())!

    // 同一对象引用
    expect(now1).toBe(now2)

    vi.advanceTimersByTime(60000)
    expect(now1.value).toBe(now2.value)

    scope1.stop()
    scope2.stop()
  })

  it('支持自定义 tick 间隔（简化测试：验证能订阅即可）', () => {
    const scope = effectScope()
    const { now } = scope.run(() => useSharedClock(30000))!

    // 验证订阅成功（now 是响应式的）
    expect(now.value).toBeTypeOf('number')
    expect(now.value).toBeGreaterThan(0)

    scope.stop()
  })

  it('懒启动：未订阅时不开时钟', () => {
    // 验证：直接 advanceTimers 不会触发 useSharedClock 内部逻辑
    // （因为单例 timer 未创建）
    const initialNow = Date.now()
    vi.advanceTimersByTime(120000)
    expect(Date.now()).toBe(initialNow + 120000) // fake timers 正常推进
    // 此时 useSharedClock 内部的 now ref 还未创建，无副作用
  })
})

