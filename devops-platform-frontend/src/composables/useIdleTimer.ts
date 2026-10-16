import { onBeforeUnmount, onMounted, toValue, watch, type MaybeRefOrGetter } from 'vue'

export interface IdleTimerOptions {
  /** 警告阈值(ms)。可传响应式值——用户在设置里改超时后无需刷新即时生效 */
  warnAfter?: MaybeRefOrGetter<number>
  /** 超时阈值(ms)。可传响应式值 */
  timeoutAfter?: MaybeRefOrGetter<number>
  onWarn?: (remainingMs: number) => void
  onTimeout?: () => void
  onActive?: () => void
  events?: string[]
}

const DEFAULT_EVENTS = ['mousedown', 'mousemove', 'keydown', 'scroll', 'touchstart', 'wheel']

export const useIdleTimer = (options: IdleTimerOptions = {}) => {
  const {
    warnAfter = 13 * 60 * 1000,
    timeoutAfter = 15 * 60 * 1000,
    onWarn,
    onTimeout,
    onActive,
    events = DEFAULT_EVENTS
  } = options

  // 每次读取都取最新值：warnAfter/timeoutAfter 可能是 ref 或 getter，
  // 用户在设置里改超时后，下一次 schedule() 就用新值，无需刷新页面
  const getWarnAfter = () => toValue(warnAfter)
  const getTimeoutAfter = () => toValue(timeoutAfter)

  let warnTimer: ReturnType<typeof setTimeout> | null = null
  let timeoutTimer: ReturnType<typeof setTimeout> | null = null
  let warned = false
  let paused = false

  const clearTimers = () => {
    if (warnTimer) { clearTimeout(warnTimer); warnTimer = null }
    if (timeoutTimer) { clearTimeout(timeoutTimer); timeoutTimer = null }
  }

  const schedule = () => {
    clearTimers()
    const warnMs = getWarnAfter()
    const timeoutMs = getTimeoutAfter()
    warnTimer = setTimeout(() => {
      warned = true
      onWarn?.(timeoutMs - warnMs)
    }, warnMs)
    timeoutTimer = setTimeout(() => {
      onTimeout?.()
    }, timeoutMs)
  }

  const reset = () => {
    if (paused) return
    if (warned) {
      warned = false
      onActive?.()
    }
    schedule()
  }

  const pause = () => {
    paused = true
    clearTimers()
  }

  const resume = () => {
    paused = false
    schedule()
  }

  const onVisibility = () => {
    if (document.hidden) return
    reset()
  }

  // 超时配置变化时立即按新值重排（不等下一次用户操作），除非已暂停
  watch([getWarnAfter, getTimeoutAfter], () => {
    if (!paused) schedule()
  })

  onMounted(() => {
    events.forEach(ev => window.addEventListener(ev, reset, { passive: true }))
    document.addEventListener('visibilitychange', onVisibility)
    schedule()
  })

  onBeforeUnmount(() => {
    events.forEach(ev => window.removeEventListener(ev, reset))
    document.removeEventListener('visibilitychange', onVisibility)
    clearTimers()
  })

  return { reset, pause, resume }
}
