import { ElMessage, type MessageOptions } from 'element-plus'

type NotifyType = 'success' | 'warning' | 'info' | 'error'

interface NotifyOptions extends Omit<MessageOptions, 'type' | 'message'> {
  key?: string
  cooldown?: number
}

const DEFAULT_COOLDOWN = 1000
const lastFired = new Map<string, number>()

const shouldEmit = (key: string, cooldown: number): boolean => {
  const now = Date.now()
  const prev = lastFired.get(key)
  if (prev && now - prev < cooldown) return false
  lastFired.set(key, now)
  return true
}

const emit = (
  type: NotifyType,
  message: string,
  options: NotifyOptions = {}
): void => {
  const { key = `${type}:${message}`, cooldown = DEFAULT_COOLDOWN, ...rest } = options
  if (!shouldEmit(key, cooldown)) return
  try {
    ElMessage({
      type,
      message,
      grouping: true,
      duration: type === 'error' ? 4000 : 2500,
      ...rest
    })
  } catch (e) {
    console.warn('[notify] ElMessage unavailable:', e)
  }
}

export const notify = {
  success: (message: string, options?: NotifyOptions) => emit('success', message, options),
  warning: (message: string, options?: NotifyOptions) => emit('warning', message, options),
  info: (message: string, options?: NotifyOptions) => emit('info', message, options),
  error: (message: string, options?: NotifyOptions) => emit('error', message, options),
  clearCooldown: (key?: string) => {
    if (key) lastFired.delete(key)
    else lastFired.clear()
  }
}
