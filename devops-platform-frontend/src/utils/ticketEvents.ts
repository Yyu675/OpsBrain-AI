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
 *
 * 载荷类型由 EventPayloads 集中声明——新增事件必须在此登记，
 * 否则 emit/on 的调用点会在编译期报错，避免发布方与订阅方对载荷理解不一致。
 */

/** 事件名 → 载荷参数元组。新增事件在此登记。 */
export interface EventPayloads {
  /** AI 建单成功，载荷为后端返回的工单号 */
  'ticket-created': [ticketId: string]
  /** 手动工单表单提交成功，无载荷 */
  'ticket-form-submitted': []
}
// 曾有 'approval-decided'：审批决策后通知导航栏刷新待审角标。
// 已删除——该通路被 TanStack Query 的失效链路替代：
// ApprovalCenter 的 mutation invalidate approvalKeys.all，
// 导航栏角标共用该前缀因此自动刷新，不再需要发布/订阅两处手工配对。

export type EventName = keyof EventPayloads

export type EventCallback<N extends EventName> = (...args: EventPayloads[N]) => void

/** 内部存储擦除具体事件的参数类型，读写两端由公开方法签名保证类型安全 */
type AnyCallback = (...args: never[]) => void

const listeners = new Map<EventName, Set<AnyCallback>>()

function getSet(name: EventName): Set<AnyCallback> {
  let s = listeners.get(name)
  if (!s) {
    s = new Set()
    listeners.set(name, s)
  }
  return s
}

export const ticketEvents = {
  on<N extends EventName>(name: N, cb: EventCallback<N>): void {
    getSet(name).add(cb as AnyCallback)
  },

  off<N extends EventName>(name: N, cb: EventCallback<N>): void {
    getSet(name).delete(cb as AnyCallback)
  },

  emit<N extends EventName>(name: N, ...args: EventPayloads[N]): void {
    // 复制一份再遍历：订阅方在回调中 off 自己时不会破坏本次遍历
    const snapshot = Array.from(getSet(name))
    snapshot.forEach(cb => (cb as unknown as EventCallback<N>)(...args))
  },

  /** 清理所有订阅（仅测试用） */
  _clear(): void {
    listeners.clear()
  }
}
