/**
 * 轻量级类型安全事件总线
 *
 * 用于全局 AI 对话页 AiChatView 与各页面组件之间的跨路由通信。
 * 不引入 mitt 等外部依赖，遵循项目已有工具模块惯例（如 undoToast.ts / clipboard.ts）。
 *
 * 用法：
 *   import { ticketEvents } from '@/utils/ticketEvents'
 *   // 发布
 *   ticketEvents.emit('ticket-created', 'TKT-20260815-0001')
 *   // 订阅
 *   onMounted(() => ticketEvents.on('ticket-created', handler))
 *   onBeforeUnmount(() => ticketEvents.off('ticket-created', handler))
 */

type EventName = 'ticket-created' | 'ticket-form-submitted'

type EventCallback = (...args: any[]) => void

const listeners = new Map<EventName, Set<EventCallback>>()

function getSet(name: EventName): Set<EventCallback> {
  let s = listeners.get(name)
  if (!s) {
    s = new Set()
    listeners.set(name, s)
  }
  return s
}

export const ticketEvents = {
  on(name: EventName, cb: EventCallback): void {
    getSet(name).add(cb)
  },

  off(name: EventName, cb: EventCallback): void {
    getSet(name).delete(cb)
  },

  emit(name: EventName, ...args: any[]): void {
    getSet(name).forEach(cb => cb(...args))
  },

  /** 清理所有订阅（仅测试用） */
  _clear(): void {
    listeners.clear()
  }
}