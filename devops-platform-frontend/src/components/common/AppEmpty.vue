<script setup lang="ts">
import { computed } from 'vue'
import { Inbox, SearchX, WifiOff, ShieldOff, FileWarning, AlertCircle } from 'lucide-vue-next'

type EmptyKind =
  | 'default'
  | 'search'
  | 'network'
  | 'permission'
  | 'notfound'
  | 'error'

interface Props {
  kind?: EmptyKind
  title?: string
  description?: string
  size?: 'sm' | 'md' | 'lg'
  actionText?: string
  secondaryText?: string
}

const props = withDefaults(defineProps<Props>(), {
  kind: 'default',
  size: 'md'
})

const emit = defineEmits<{
  action: []
  secondary: []
}>()

const iconMap = {
  default: Inbox,
  search: SearchX,
  network: WifiOff,
  permission: ShieldOff,
  notfound: FileWarning,
  error: AlertCircle
} as const

const defaults: Record<EmptyKind, { title: string; description: string }> = {
  default: { title: '暂无数据', description: '当前列表为空' },
  search: { title: '没有找到匹配结果', description: '换个关键词或清空筛选试试' },
  network: { title: '网络连接失败', description: '请检查网络后重试' },
  permission: { title: '暂无访问权限', description: '如认为异常请联系管理员' },
  notfound: { title: '内容不存在', description: '资源可能已被删除或迁移' },
  error: { title: '加载失败', description: '请稍后重试或联系管理员' }
}

const icon = computed(() => iconMap[props.kind])
const displayTitle = computed(() => props.title ?? defaults[props.kind].title)
const displayDesc = computed(() => props.description ?? defaults[props.kind].description)
const iconSize = computed(() => (props.size === 'sm' ? 28 : props.size === 'lg' ? 56 : 40))
</script>

<template>
  <div class="app-empty" :class="[`app-empty-${props.size}`, `app-empty-${props.kind}`]" role="status">
    <div class="app-empty-icon">
      <component :is="icon" :size="iconSize" />
    </div>
    <h4 class="app-empty-title">{{ displayTitle }}</h4>
    <p class="app-empty-desc">{{ displayDesc }}</p>
    <div v-if="props.actionText || props.secondaryText" class="app-empty-actions">
      <button
        v-if="props.actionText"
        type="button"
        class="app-empty-btn app-empty-btn-primary"
        @click="emit('action')"
      >
        {{ props.actionText }}
      </button>
      <button
        v-if="props.secondaryText"
        type="button"
        class="app-empty-btn"
        @click="emit('secondary')"
      >
        {{ props.secondaryText }}
      </button>
    </div>
    <slot name="extra" />
  </div>
</template>

<style scoped lang="scss">
.app-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 32px 16px;
  color: var(--color-text-secondary, #64748b);
}

.app-empty-sm { padding: 20px 12px; }
.app-empty-lg { padding: 56px 24px; }

.app-empty-icon {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: var(--color-bg-sunken, var(--surface-2));
  color: var(--color-text-tertiary, #94a3b8);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}

.app-empty-sm .app-empty-icon { width: 52px; height: 52px; margin-bottom: 10px; }
.app-empty-lg .app-empty-icon { width: 96px; height: 96px; margin-bottom: 20px; }

.app-empty-network .app-empty-icon,
.app-empty-error .app-empty-icon {
  background: var(--state-error-bg, var(--danger-subtle));
  color: var(--state-error, #ef4444);
}

.app-empty-permission .app-empty-icon {
  background: var(--state-warning-bg, var(--warning-subtle));
  color: var(--state-warning, var(--warning));
}

.app-empty-title {
  margin: 0 0 6px;
  font-size: var(--text-base, 15px);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary, #1e293b);
}

.app-empty-sm .app-empty-title { font-size: var(--text-sm, 13px); }
.app-empty-lg .app-empty-title { font-size: var(--text-lg, 17px); }

.app-empty-desc {
  margin: 0;
  max-width: 360px;
  font-size: var(--text-sm, 13px);
  color: var(--color-text-tertiary, #94a3b8);
  line-height: 1.5;
}

.app-empty-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.app-empty-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 1px solid var(--color-border-light, var(--border-1));
  border-radius: var(--radius-md, 8px);
  background: var(--color-surface, #fff);
  color: var(--color-text-primary, #1e293b);
  font-size: var(--text-sm, 13px);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary, var(--brand));
    color: var(--color-primary, var(--brand));
  }

  &.app-empty-btn-primary {
    background: var(--color-primary, var(--brand));
    color: var(--color-text-inverse, #fff);
    border-color: var(--color-primary, var(--brand));

    &:hover {
      background: var(--color-primary-light, #60a5fa);
      color: var(--color-text-inverse, #fff);
    }
  }
}
</style>
