/**
 * sharedClock.ts — 全局时钟供应器（批 85 P1-4）
 *
 * ── 要解决什么 ──────────────────────────────────────────────
 * 页面同时渲染多个 <RelativeTime> 时，每个组件独立开一个 setInterval，
 * 同时在跑的计时器数量 = 组件实例数。工单列表展示 50 条，每条一个创建时间
 * + 一个更新时间 = 100 个 timer 同时 tick。
 *
 * 性能损耗：
 *   - 每个 timer 触发 Vue 响应式更新 → 100 次 computed 重算 → 100 次 DOM diff
 *   - 时钟不同步：各 timer 启动时间略有差异，"3 分钟前" 可能有的跳变、有的还没跳
 *   - 内存占用：每个 ref(Date.now()) 都是独立响应式对象
 *
 * ── 解决方案 ──────────────────────────────────────────────
 * 单例全局时钟：一个 setInterval，所有 RelativeTime 共享同一个响应式 now。
 * 100 个组件订阅同一个 ref → 一次 tick 触发一次批量更新 → Vue 3 自动合并 DOM patch。
 *
 * ── 设计细节 ──────────────────────────────────────────────
 * - 懒启动：首次 subscribe() 才开时钟，避免无人用时空转
 * - 自动停：最后一个订阅者取消时停时钟，防内存泄漏
 * - 默认 60s tick（RelativeTime 默认档）；允许订阅者自定义间隔
 *   （多档共存时取最短 gcd，确保每档需求都满足——实现简化，当前只支持单一间隔）
 *
 * ── 使用方式 ──────────────────────────────────────────────
 * ```ts
 * import { useSharedClock } from '@/utils/sharedClock'
 *
 * const { now } = useSharedClock()  // 自动订阅
 * const label = computed(() => relativeTime(props.value, now.value))
 * // 组件卸载时自动取消订阅（composable 内部 onScopeDispose）
 * ```
 */

import { ref, onScopeDispose } from 'vue'

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null
let subscribers = 0
const intervals = new Map<number, number>() // intervalMs -> count

const tick = () => {
  now.value = Date.now()
}

const getMinInterval = (): number => {
  if (intervals.size === 0) return 60000
  return Math.min(...intervals.keys())
}

const restart = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (subscribers > 0) {
    const minInterval = getMinInterval()
    timer = setInterval(tick, minInterval)
  }
}

/**
 * 订阅全局时钟。
 *
 * 返回共享的响应式 now，组件卸载时自动取消订阅。
 * 首个订阅者启动时钟，最后一个取消时停时钟。
 * 多档间隔共存时，使用最短间隔（满足所有订阅者需求）。
 *
 * @param intervalMs 期望的 tick 间隔（毫秒），默认 60000（1 分钟）
 */
export const useSharedClock = (intervalMs = 60000) => {
  const prevMin = getMinInterval()

  subscribers++
  intervals.set(intervalMs, (intervals.get(intervalMs) || 0) + 1)

  const newMin = getMinInterval()
  if (subscribers === 1 || newMin < prevMin) {
    restart()
  }

  onScopeDispose(() => {
    const count = intervals.get(intervalMs)!
    if (count === 1) {
      intervals.delete(intervalMs)
    } else {
      intervals.set(intervalMs, count - 1)
    }

    subscribers--
    if (subscribers === 0) {
      if (timer) {
        clearInterval(timer)
        timer = null
      }
    } else {
      const newMinAfterRemove = getMinInterval()
      if (newMinAfterRemove !== newMin) {
        restart()
      }
    }
  })

  return { now }
}
