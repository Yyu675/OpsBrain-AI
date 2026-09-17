<script setup lang="ts">
/**
 * AlertFeed — Dashboard 实时告警流组件（批88 P1）
 *
 * 紧凑版告警流，嵌入 Dashboard 右侧面板，复用 AlertStreamMode 的
 * WebSocket 基础设施（同一 /ws/alerts 通道、同一事件协议）。
 *
 * 与 AlertStreamMode 的区别：本组件不管理连接状态横幅/空态大图，
 * 只渲染紧凑列表 + 连接状态指示灯——适合嵌入看板而非独立页。
 *
 * 数据契约：与后端 AlertWebSocketEvent 完全对齐
 * （type / timestamp / alert{12字段}）。
 */

import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { Bell, Radio } from 'lucide-vue-next'
import RelativeTime from '@/components/common/RelativeTime.vue'

interface AlertPayload {
  id: number
  alertName: string
  level: string
  title: string
  description: string
  status: string
  service: string
  module: string
  occurrenceCount: number
  firstOccurredAt: string
  lastOccurredAt: string
  ticketId: string
}

interface AlertEvent {
  type: 'NEW' | 'UPDATE' | 'RESOLVED'
  timestamp: string
  alert: AlertPayload
}

const MAX_DISPLAY = 8
const alerts = ref<AlertEvent[]>([])
const connected = ref(false)
const reconnecting = ref(false)

let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempt = 0
const INITIAL_DELAY = 1000
const MAX_DELAY = 30000

/** 构建 WebSocket URL（与 AlertStreamMode 一致） */
const wsUrl = () => {
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${location.host}/ai/ws/alerts`
}

const levelColor = (level: string) => {
  switch (level) {
    case 'P0': return 'var(--el-color-danger)'
    case 'P1': return 'var(--el-color-warning)'
    case 'P2': return 'var(--el-color-primary)'
    default: return 'var(--el-color-info)'
  }
}

const connect = () => {
  cleanup()
  connected.value = false
  reconnecting.value = true
  try {
    ws = new WebSocket(wsUrl())
  } catch {
    scheduleReconnect()
    return
  }
  ws.onopen = () => { connected.value = true; reconnecting.value = false; reconnectAttempt = 0 }
  ws.onmessage = (event) => {
    try {
      const ev = JSON.parse(event.data) as AlertEvent
      alerts.value.unshift(ev)
      if (alerts.value.length > MAX_DISPLAY) alerts.value.length = MAX_DISPLAY
    } catch { /* 非 JSON 忽略 */ }
  }
  ws.onclose = () => { connected.value = false; scheduleReconnect() }
  ws.onerror = () => { connected.value = false }
}

const scheduleReconnect = () => {
  if (reconnectTimer) clearTimeout(reconnectTimer)
  reconnectAttempt++
  const delay = Math.min(INITIAL_DELAY * Math.pow(2, reconnectAttempt - 1), MAX_DELAY)
  reconnectTimer = setTimeout(() => { reconnectTimer = null; connect() }, delay)
}

const manualReconnect = () => { reconnectAttempt = 0; connect() }

const cleanup = () => {
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  if (ws) { ws.onclose = null; ws.close(); ws = null }
}

onMounted(connect)
onBeforeUnmount(cleanup)

const displayAlerts = computed(() => alerts.value.slice(0, MAX_DISPLAY))
</script>

<template>
  <div class="alert-feed">
    <!-- 头部：标题 + 连接状态 -->
    <div class="feed-header">
      <span class="feed-title">
        <Bell :size="14" />
        实时告警流
      </span>
      <span class="feed-status" :class="{ connected }">
        <Radio :size="12" :class="{ pulse: connected }" />
        {{ connected ? '已连接' : (reconnecting ? '重连中…' : '已断开') }}
      </span>
    </div>

    <!-- 断开时给重试入口 -->
    <div v-if="!connected && !reconnecting" class="feed-retry">
      <el-button size="small" type="primary" plain @click="manualReconnect">重新连接</el-button>
    </div>

    <!-- 告警列表 -->
    <div v-else-if="displayAlerts.length" class="feed-list">
      <div
        v-for="ev in displayAlerts" :key="ev.alert.id + ev.timestamp"
        class="feed-item"
        :class="{ 'is-new': ev.type === 'NEW' }"
      >
        <span class="feed-dot" :style="{ background: levelColor(ev.alert.level) }" />
        <div class="feed-body">
          <div class="feed-title">{{ ev.alert.title || ev.alert.alertName }}</div>
          <div class="feed-meta">
            <span class="feed-badge" :style="{ color: levelColor(ev.alert.level) }">{{ ev.alert.level }}</span>
            <span class="feed-service">{{ ev.alert.service }}</span>
            <span class="feed-time"><RelativeTime :value="ev.timestamp" /></span>
          </div>
          <div v-if="ev.alert.ticketId" class="feed-ticket">工单 {{ ev.alert.ticketId }}</div>
        </div>
      </div>
    </div>

    <!-- 空态 -->
    <div v-else class="feed-empty">
      暂无告警推送——系统运行正常 ✅
    </div>
  </div>
</template>

<style scoped>
.alert-feed { background: var(--color-surface, var(--surface-1)); border-radius: 12px; padding: 16px; }
.feed-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.feed-title { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; }
.feed-status { display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--text-3, #909399); }
.feed-status.connected { color: #67c23a; }
.pulse { animation: pulse 2s infinite; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }

.feed-list { display: flex; flex-direction: column; gap: 6px; max-height: 280px; overflow-y: auto; }
.feed-item { display: flex; gap: 8px; padding: 8px; border-radius: 6px; background: var(--surface-2, #f5f7fa); transition: background 0.2s; }
.feed-item.is-new { background: rgba(64,158,255,.06); }
.feed-dot { flex-shrink: 0; width: 6px; height: 6px; border-radius: 50%; margin-top: 5px; }
.feed-body { flex: 1; min-width: 0; }
.feed-title { font-size: 13px; color: var(--text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.feed-meta { display: flex; gap: 8px; font-size: 11px; color: var(--text-3); margin-top: 2px; }
.feed-badge { font-weight: 600; }
.feed-service { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.feed-ticket { font-size: 11px; color: var(--color-primary, #2563eb); margin-top: 2px; }

.feed-empty { text-align: center; padding: 24px 0; color: var(--text-3); font-size: 13px; }
.feed-retry { text-align: center; padding: 12px 0; }
</style>