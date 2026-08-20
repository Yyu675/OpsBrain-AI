import { onMounted, onBeforeUnmount } from 'vue'
import { useNotificationsStore } from '@/stores/notifications'
import { formatAbsolute } from '@/utils/time'

/**
 * 全局告警通知监听
 *
 * 在 App.vue 挂载时：
 * 1. 从后端拉取最近告警重建通知列表（loadFromBackend，修复会话级刷新丢失）
 * 2. 连接 /ws/alerts WebSocket，收到 NEW 告警事件时实时推入通知
 * 3. 断线后按指数退避自动重连
 *
 * 与 AlertStreamMode.vue 的 WS 连接独立：
 * AlertStreamMode 是告警流视图（全量事件），本 composable 只关注 NEW → 通知。
 * 两个连接各自维护生命周期，互不干扰。
 */
interface AlertEvent {
  type: 'NEW' | 'UPDATE' | 'RESOLVED'
  timestamp: string
  alert: {
    id: number
    alertName: string
    level: string
    title: string
    status: string
    service: string
    ticketId?: string
  }
}

const INITIAL_RECONNECT_DELAY = 1000
const MAX_RECONNECT_DELAY = 30000

let ws: WebSocket | null = null
let reconnectAttempt = 0
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let disposed = false

export function useAlertNotifications() {
  const notifications = useNotificationsStore()

  const buildUrl = (): string => {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    return `${protocol}://${window.location.host}/ai/ws/alerts`
  }

  const clearReconnectTimer = () => {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  const scheduleReconnect = () => {
    if (disposed) return
    const delay = Math.min(INITIAL_RECONNECT_DELAY * Math.pow(2, reconnectAttempt), MAX_RECONNECT_DELAY)
    reconnectAttempt += 1
    clearReconnectTimer()
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, delay)
  }

  const connect = () => {
    if (disposed) return
    try {
      ws = new WebSocket(buildUrl())
    } catch {
      scheduleReconnect()
      return
    }

    ws.onmessage = (event) => {
      try {
        const data: AlertEvent = JSON.parse(event.data)
        if (data.type !== 'NEW' || !data.alert) return

        // 稳定 id：使用告警实体 id，与后端拉取（loadFromBackend）共用同一 id 空间，
        // 二者去重——WS 实时推入与刷新后拉取不会产生重复通知条目。
        notifications.addNotification(
          {
            title: `告警: ${data.alert.title || data.alert.alertName}`,
            time: formatAbsolute(data.timestamp),
            read: false,
            // 无关联工单时跳该告警详情页（此前落到列表页 /alerts，用户还得自己找是哪条）
            linkTo: data.alert.ticketId ? `/tickets/${data.alert.ticketId}` : `/alerts/${data.alert.id}`
          },
          data.alert.id
        )
      } catch {
        // 非 JSON 或心跳消息，忽略
      }
    }

    ws.onopen = () => {
      // 连接成功即重置退避计数，使抖动不累积
      reconnectAttempt = 0
    }

    ws.onerror = () => {
      // 静默处理，浏览器会自动触发 onclose
    }

    ws.onclose = () => {
      ws = null
      scheduleReconnect()
    }
  }

  onMounted(() => {
    disposed = false
    void notifications.loadFromBackend()
    connect()
  })

  onBeforeUnmount(() => {
    disposed = true
    clearReconnectTimer()
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      ws.close()
    }
    ws = null
  })
}
