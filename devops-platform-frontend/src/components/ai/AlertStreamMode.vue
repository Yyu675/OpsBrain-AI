<template>
  <div class="alert-stream-mode">
    <!-- 连接中（首次连接） -->
    <div v-if="connectionState === 'connecting' && alerts.length === 0" class="state-connecting">
      <div class="connecting-icon">
        <BellDot :size="40" />
        <Loader :size="20" class="spinner" />
      </div>
      <p class="connecting-text">正在连接告警服务…</p>
      <p class="connecting-hint">实时告警推送将在连接就绪后自动显示</p>
    </div>

    <!-- 断线/错误时保留已有告警列表，顶部显示重连提示条 -->
    <div
      v-if="connectionState === 'error' || connectionState === 'disconnected'"
      class="reconnect-banner"
    >
      <span class="reconnect-text">
        {{ connectionState === 'disconnected' ? '连接已断开，正在尝试自动重连…' : '告警服务连接失败' }}
      </span>
      <el-button type="primary" size="small" :icon="RefreshCw" :loading="reconnecting" @click="manualReconnect">
        重新连接
      </el-button>
    </div>

    <!-- 已连接但无告警 -->
    <div v-if="connectionState === 'connected' && alerts.length === 0" class="state-empty">
      <EmptyState
        :icon="Bell"
        title="暂无告警"
        description="当前没有活跃告警，系统运行正常"
        size="compact"
      />
    </div>

    <!-- 有告警时显示告警流（断线时也保留列表） -->
    <template v-if="alerts.length > 0">
      <div class="alerts-header">
        <span class="alerts-count">
          <BellDot :size="14" />
          实时告警
          <el-tag size="small" :type="alertCountTagType" effect="dark">
            {{ alerts.length }}
          </el-tag>
        </span>
        <span class="alerts-status" :class="connectionState">
          <span class="status-dot" />
          {{ connectionState === 'connected' ? '已连接' : '已断开' }}
        </span>
      </div>
      <div class="alerts-stream">
        <div
          v-for="event in alerts"
          :key="event.alert.id + '-' + event.timestamp"
          class="alert-card"
          :class="['alert-card--' + event.type.toLowerCase(), 'alert-level--' + levelGroup(event.alert.level)]"
        >
          <div class="alert-card__header">
            <span class="alert-card__type-icon" :class="'type-icon--' + event.type.toLowerCase()">
              <component :is="eventTypeIcon(event.type)" :size="16" />
            </span>
            <el-tag
              :type="levelTagType(event.alert.level)"
              size="small"
              effect="dark"
              class="alert-card__level-badge"
            >
              {{ event.alert.level }}
            </el-tag>
            <span class="alert-card__title">{{ event.alert.title || event.alert.alertName }}</span>
            <span class="alert-card__time">
              <RelativeTime :value="event.alert.firstOccurredAt" />
            </span>
          </div>
          <div class="alert-card__body">
            <p class="alert-card__description">{{ trimDescription(event.alert.description) }}</p>
            <div class="alert-card__meta">
              <span v-if="event.alert.service" class="meta-item">
                <Monitor :size="12" />
                {{ event.alert.service }}
              </span>
              <span v-if="event.alert.module" class="meta-item">
                <User :size="12" />
                {{ event.alert.module }}
              </span>
              <span v-if="event.alert.occurrenceCount && event.alert.occurrenceCount > 1" class="meta-item meta-item--occurrence">
                第 {{ event.alert.occurrenceCount }} 次
              </span>
              <span v-if="event.alert.ticketId" class="meta-item meta-item--ticket">
                工单 {{ event.alert.ticketId }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * AlertStreamMode — 告警流模式组件
 *
 * 连接 /ws/alerts WebSocket，实时接收 NEW / UPDATE / RESOLVED 三类告警事件，
 * 以卡片流形式展示（最新置顶）。
 *
 * 三态：连接中 → 错误/断开（含重试）→ 已连接（空白或卡片流）
 * 自动重连（指数退避 1s→2s→4s→…→30s cap）
 * 组件卸载时自动关闭 WebSocket
 *
 * @see AlertWebSocketNotifier — 后端广播服务
 * @see AlertWebSocketEvent — 事件 DTO（type / timestamp / alert）
 * @see AlertPayload — 12 字段告警载荷
 */

import { ref, watch, onBeforeUnmount, computed } from 'vue'
import { Bell, BellDot, AlertTriangle, CheckCircle, RefreshCw, Loader, Monitor, User } from 'lucide-vue-next'
import type { Component } from 'vue'
import EmptyState from '@/components/common/EmptyState.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'

// ==================== 类型定义 ====================

/** 告警载荷（12 字段，与后端 AlertPayload 对齐） */
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

const props = withDefaults(defineProps<{
  /** 为 true 时建立 WS；切走或抽屉关闭时断开，避免空闲会话膨胀 */
  active?: boolean
}>(), { active: true })

/** WebSocket 事件（与后端 AlertWebSocketEvent 对齐） */
interface AlertEvent {
  type: 'NEW' | 'UPDATE' | 'RESOLVED'
  timestamp: string
  alert: AlertPayload
}

/** 连接状态 */
type ConnectionState = 'connecting' | 'connected' | 'disconnected' | 'error'

// ==================== 状态 ====================

const connectionState = ref<ConnectionState>('connecting')
const alerts = ref<AlertEvent[]>([])
const reconnecting = ref(false)
let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempt = 0
const MAX_RECONNECT_DELAY = 30000
const INITIAL_RECONNECT_DELAY = 1000

// ==================== 计算属性 ====================

/** 告警数量标签类型 */
const alertCountTagType = computed(() => {
  const high = alerts.value.filter(e => levelGroup(e.alert.level) === 'high').length
  if (high > 0) return 'danger'
  const medium = alerts.value.filter(e => levelGroup(e.alert.level) === 'medium').length
  if (medium > 0) return 'warning'
  return 'info'
})

// ==================== WebSocket 连接 ====================

/** 构建 WebSocket URL */
function buildWsUrl(): string {
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${location.host}/ai/ws/alerts`
}

/** 建立 WebSocket 连接 */
function connect(): void {
  cleanupWs()
  clearReconnectTimer()

  connectionState.value = 'connecting'
  reconnecting.value = false

  try {
    ws = new WebSocket(buildWsUrl())
  } catch (e) {
    // 构造失败通常是 URL 非法或协议不被支持——原因必须留痕，否则只看到「连接失败」无从排查
    console.warn('[AlertStream] WebSocket 构造失败', e)
    connectionState.value = 'error'
    scheduleReconnect()
    return
  }

  ws.onopen = () => {
    connectionState.value = 'connected'
    reconnectAttempt = 0
    reconnecting.value = false
  }

  ws.onmessage = (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data) as AlertEvent
      if (!data || !data.type || !data.alert) {
        return
      }
      // 同 alert.id 按 upsert 合并（NEW/UPDATE/RESOLVED 不应变成 3 张卡）
      const existingIdx = alerts.value.findIndex(e => e.alert.id === data.alert.id)
      if (existingIdx >= 0) {
        alerts.value[existingIdx] = data
      } else {
        alerts.value.unshift(data)
        if (alerts.value.length > 50) {
          alerts.value = alerts.value.slice(0, 50)
        }
      }
    } catch {
      // 非 JSON 消息（如后端心跳或调试文本），静默忽略
    }
  }

  ws.onclose = () => {
    if (connectionState.value === 'connected' || connectionState.value === 'connecting') {
      connectionState.value = 'disconnected'
    }
    // 仅在仍处于激活态时重连；切走 tab / 关抽屉后不再打后端
    if (props.active) scheduleReconnect()
  }

  ws.onerror = () => {
    connectionState.value = 'error'
    // onerror 后必跟 onclose，由 onclose 调度重连
  }
}

/** 指数退避重连 */
function scheduleReconnect(): void {
  if (reconnectTimer) return
  const delay = Math.min(
    INITIAL_RECONNECT_DELAY * Math.pow(2, reconnectAttempt),
    MAX_RECONNECT_DELAY
  )
  reconnectAttempt++
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect()
  }, delay)
}

/** 手动重连 */
function manualReconnect(): void {
  reconnecting.value = true
  reconnectAttempt = 0
  clearReconnectTimer()
  connect()
}

/** 清理 WebSocket */
function cleanupWs(): void {
  if (ws) {
    ws.onopen = null
    ws.onmessage = null
    ws.onclose = null
    ws.onerror = null
    if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
      ws.close()
    }
    ws = null
  }
}

/** 清理重连定时器 */
function clearReconnectTimer(): void {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

// ==================== 工具方法 ====================

/** 级别分组：用于颜色标识 */
function levelGroup(level: string): 'high' | 'medium' | 'low' {
  if (!level) return 'low'
  const u = level.toUpperCase()
  if (u === 'P0' || u === 'P1') return 'high'
  if (u === 'P2' || u === 'P3') return 'medium'
  return 'low'
}

/** 事件类型对应图标 */
function eventTypeIcon(type: string): Component {
  switch (type) {
    case 'NEW': return BellDot
    case 'UPDATE': return AlertTriangle
    case 'RESOLVED': return CheckCircle
    default: return Bell
  }
}

/** 级别对应 el-tag type */
function levelTagType(level: string): 'danger' | 'warning' | 'info' {
  const g = levelGroup(level)
  if (g === 'high') return 'danger'
  if (g === 'medium') return 'warning'
  return 'info'
}

/** 截断描述到 3 行（约 120 字符） */
function trimDescription(desc: string | null | undefined): string {
  if (!desc) return ''
  if (desc.length <= 120) return desc
  return desc.slice(0, 117) + '…'
}

// ==================== 生命周期 ====================

watch(
  () => props.active,
  (on) => {
    if (on) {
      connect()
    } else {
      clearReconnectTimer()
      cleanupWs()
      connectionState.value = 'disconnected'
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  cleanupWs()
  clearReconnectTimer()
})
</script>

<style scoped lang="scss">
.alert-stream-mode {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ============ 连接中 ============ */

.state-connecting {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px 20px;
}

.connecting-icon {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--color-primary, #409eff);
}

.spinner {
  position: absolute;
  bottom: -4px;
  right: -4px;
  animation: spin 1s linear infinite;
  color: var(--color-primary, #409eff);
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.connecting-text {
  font-size: var(--text-sm, 14px);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary, #303133);
  margin: 0;
}

.connecting-hint {
  font-size: var(--text-xs, 12px);
  color: var(--color-text-tertiary, #909399);
  margin: 0;
}

/* ============ 断线重连横幅 ============ */

.reconnect-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  margin-bottom: 8px;
  background: rgba(230, 162, 60, 0.1);
  border: 1px solid rgba(230, 162, 60, 0.3);
  border-radius: 8px;
}

.reconnect-text {
  font-size: 0.8125rem;
  color: #e6a23c;
}

/* ============ 连接错误 / 断开 ============ */

.state-error {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px 20px;
}

.error-icon {
  color: var(--state-error, #f56c6c);
}

.error-title {
  font-size: var(--text-sm, 14px);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary, #303133);
  margin: 0;
}

.error-hint {
  font-size: var(--text-xs, 12px);
  color: var(--color-text-tertiary, #909399);
  margin: 0 0 8px 0;
  text-align: center;
  max-width: 280px;
  line-height: 1.5;
}

/* ============ 已连接，无告警 ============ */

.state-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
}

/* ============ 告警流头部 ============ */

.alerts-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border-light, #ebeef5);
  flex-shrink: 0;
}

.alerts-count {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary, #303133);
}

.alerts-status {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-xs, 12px);
  color: var(--color-text-tertiary, #909399);

  &.connected .status-dot {
    background: var(--state-success, #67c23a);
  }

  &.disconnected .status-dot,
  &.error .status-dot {
    background: var(--state-error, #f56c6c);
  }
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}

/* ============ 告警流列表 ============ */

.alerts-stream {
  flex: 1;
  overflow-y: auto;
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* ============ 告警卡片 ============ */

.alert-card {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border-light, #ebeef5);
  border-radius: var(--radius-md, 8px);
  overflow: hidden;
  transition: box-shadow 0.2s;

  &:hover {
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  }

  /* 级别左边框 */
  &.alert-level--high {
    border-left: 3px solid var(--state-error, #f56c6c);
  }
  &.alert-level--medium {
    border-left: 3px solid var(--color-warning, #e6a23c);
  }
  &.alert-level--low {
    border-left: 3px solid var(--color-primary, #409eff);
  }
}

.alert-card__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px 0;
}

.alert-card__type-icon {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;

  &.type-icon--new {
    color: var(--state-error, #f56c6c);
  }
  &.type-icon--update {
    color: var(--color-warning, #e6a23c);
  }
  &.type-icon--resolved {
    color: var(--state-success, #67c23a);
  }
}

.alert-card__level-badge {
  flex-shrink: 0;
}

.alert-card__title {
  flex: 1;
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary, #303133);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alert-card__time {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--color-text-tertiary, #909399);
  white-space: nowrap;
}

.alert-card__body {
  padding: 6px 10px 8px;
}

.alert-card__description {
  font-size: var(--text-xs, 12px);
  color: var(--color-text-secondary, #606266);
  line-height: 1.5;
  margin: 0 0 6px;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.alert-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: var(--color-text-tertiary, #909399);

  &--occurrence {
    color: var(--color-warning, #e6a23c);
  }

  &--ticket {
    color: var(--color-primary, #409eff);
  }
}
</style>