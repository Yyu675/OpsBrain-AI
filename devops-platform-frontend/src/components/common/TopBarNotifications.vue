<script setup lang="ts">
/**
 * TopBarNotifications —— 顶栏通知铃铛 + 下拉面板（批93 自 AppTopBar 拆出）。
 *
 * 纯展示组件：开关态/未读数/条目由宿主传入，动作一律上抛
 * （toggle / read / mark-all / open-settings），状态与跳转语义留在宿主——
 * AppTopBar 测试对宿主 vm 的断言因此原样成立，拆分不改行为。
 */
import { Bell, CheckCheck } from 'lucide-vue-next'
import type { AppNotification } from '@/stores/notifications'

defineProps<{ show: boolean; unread: number; enabled: boolean; items: AppNotification[] }>()
defineEmits<{ toggle: []; read: [n: AppNotification]; 'mark-all': []; 'open-settings': [] }>()
</script>

<template>
  <div class="notification-wrapper">
    <button
      class="notification-btn"
      :class="{ muted: !enabled }"
      :title="enabled ? '通知' : '通知已在系统设置中关闭'"
      @click.stop="$emit('toggle')"
    >
      <Bell :size="18" :stroke-width="1.8" />
      <span v-if="enabled && unread > 0" class="notification-badge">{{ unread }}</span>
      <span v-if="!enabled" class="notification-mute-dot" aria-hidden="true"></span>
    </button>

    <div v-if="show" class="notification-dropdown" @click.stop>
      <div class="dropdown-header">
        <span class="dropdown-title">通知</span>
        <button
          v-if="enabled && unread > 0"
          class="dropdown-action"
          @click="$emit('mark-all')"
        >
          <CheckCheck :size="14" />
          全部已读
        </button>
      </div>
      <div v-if="!enabled" class="notification-muted">
        通知已在系统设置中关闭。
        <button class="link-btn" @click="$emit('open-settings')">前往开启</button>
      </div>
      <div v-else-if="items.length === 0" class="notification-muted">
        暂无通知
      </div>
      <div v-else class="notification-list">
        <div
          v-for="n in items"
          :key="n.id"
          class="notification-item"
          :class="{ unread: !n.read }"
          @click="$emit('read', n)"
        >
          <div class="notification-dot" v-if="!n.read"></div>
          <div class="notification-body">
            <div class="notification-title">{{ n.title }}</div>
            <div class="notification-time">{{ n.time }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* ===== 通知 ===== */
.notification-wrapper {
  position: relative;
}

.notification-btn {
  position: relative;
  width: 36px;
  height: 36px;
  border: 1px solid var(--border-1);
  background: var(--surface-1);
  color: var(--text-2);
  cursor: pointer;
  border-radius: var(--radius);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);

  &:hover {
    background: var(--surface-hover);
    color: var(--text-1);
  }

  &.muted {
    opacity: 0.55;

    &:hover { opacity: 0.85; }
  }
}

.notification-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--danger);
  color: var(--text-inverse, #fff);
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
  border: 2px solid var(--surface-1);
  box-sizing: content-box;
}

.notification-mute-dot {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-3);
  border: 2px solid var(--surface-1);
  box-sizing: content-box;
}

.notification-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: 340px;
  background: var(--surface-3);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 60;
  overflow: hidden;
  animation: dropdown-in var(--duration-fast) var(--ease-out);
}

/* 两个下拉组件各持一份（scoped keyframes 按 SFC 归属，跨组件引用会失效） */
@keyframes dropdown-in {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.dropdown-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-1);
}

.dropdown-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-1);
}

.dropdown-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: none;
  background: transparent;
  font-size: var(--text-xs);
  color: var(--brand);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background var(--duration-fast) var(--ease-out);

  &:hover { background: var(--brand-subtle); }
}

.notification-muted {
  padding: 20px 16px;
  text-align: center;
  font-size: var(--text-sm);
  color: var(--text-2);
  line-height: 1.6;

  .link-btn {
    display: inline;
    border: none;
    background: transparent;
    padding: 0;
    margin-left: 4px;
    color: var(--brand);
    font-size: var(--text-sm);
    cursor: pointer;
    text-decoration: underline;

    &:hover { color: var(--brand-active); }
  }
}

.notification-list {
  max-height: 360px;
  overflow-y: auto;
}

.notification-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out);

  &:not(:last-child) {
    border-bottom: 1px solid var(--border-1);
  }

  &:hover { background: var(--surface-hover); }

  &.unread { background: var(--brand-subtle); }
}

.notification-dot {
  width: 6px;
  height: 6px;
  margin-top: 8px;
  border-radius: 50%;
  background: var(--brand);
  flex-shrink: 0;
}

.notification-body {
  flex: 1;
  min-width: 0;
}

.notification-title {
  font-size: var(--text-sm);
  color: var(--text-1);
  line-height: 1.4;
  margin-bottom: 4px;
}

.notification-time {
  font-size: var(--text-xs);
  color: var(--text-3);
}
</style>
