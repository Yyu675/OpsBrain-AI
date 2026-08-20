<script setup lang="ts">
import { computed } from 'vue'
import { AlertTriangle, RefreshCw } from 'lucide-vue-next'
import { toFriendlyError, type FriendlyError } from '@/utils/http'

/**
 * 统一 API 错误状态展示组件。
 *
 * 用法：
 *   <ApiErrorState v-if="loadError" :error="loadError" @retry="reload" />
 *
 * 特性：
 * - 自动从 HttpError / Error / string 提取友好提示
 * - 显示「出了什么问题」「可能原因」「下一步建议」三段信息
 * - 提供重试按钮
 * - 降级：如果 error 为 null 则不渲染
 */
interface Props {
  /** 错误对象：HttpError / Error / string / null */
  error: unknown
  /** 可选的自定义标题，覆盖自动推断 */
  title?: string
  /** 是否显示重试按钮（默认显示） */
  retryable?: boolean
  /** 重试按钮文案 */
  retryLabel?: string
  /** 紧凑模式（嵌入卡片内部时用） */
  compact?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  retryable: true,
  retryLabel: '重试',
  compact: false
})

defineEmits<{
  retry: []
}>()

const friendly = computed<FriendlyError>(() => toFriendlyError(props.error))

const displayTitle = computed(() => props.title || friendly.value.title)
</script>

<template>
  <div class="api-error-state" :class="{ 'api-error-compact': compact }">
    <div class="api-error-icon">
      <AlertTriangle :size="compact ? 24 : 36" />
    </div>
    <h4 class="api-error-title">{{ displayTitle }}</h4>
    <p class="api-error-detail">{{ friendly.detail }}</p>
    <p v-if="friendly.hint" class="api-error-hint">
      <span class="hint-label">建议：</span>{{ friendly.hint }}
    </p>
    <div class="api-error-actions">
      <button
        v-if="retryable"
        type="button"
        class="api-error-retry"
        @click="$emit('retry')"
      >
        <RefreshCw :size="14" />
        {{ retryLabel }}
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.api-error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 24px;
  text-align: center;
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border-light, #e2e8f0);
  border-radius: var(--radius-lg, 12px);
}

.api-error-compact {
  padding: 24px 16px;
  border: none;
  background: transparent;
}

.api-error-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: #FEF2F2;
  color: #DC2626;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
}

.api-error-compact .api-error-icon {
  width: 40px;
  height: 40px;
}

.api-error-title {
  margin: 0;
  font-size: var(--text-base, 1rem);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary, #1e293b);
}

.api-error-detail {
  margin: 0;
  font-size: var(--text-sm, 0.875rem);
  color: var(--color-text-secondary, #64748b);
  max-width: 480px;
  line-height: var(--leading-normal, 1.6);
  word-break: break-word;
}

.api-error-hint {
  margin: 0;
  font-size: var(--text-xs, 0.75rem);
  color: var(--color-text-tertiary, #94a3b8);
  max-width: 480px;
  line-height: var(--leading-normal, 1.6);
  padding: 6px 12px;
  background: var(--color-bg-sunken, #f8fafc);
  border-radius: var(--radius-sm, 6px);
}

.hint-label {
  font-weight: var(--weight-medium, 500);
  color: var(--color-text-secondary, #64748b);
}

.api-error-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}

.api-error-retry {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 16px;
  border: 1px solid var(--color-primary, #3b82f6);
  border-radius: var(--radius-md, 8px);
  background: var(--color-surface, #fff);
  color: var(--color-primary, #3b82f6);
  font-size: var(--text-sm, 0.875rem);
  font-family: var(--font-body, inherit);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-primary, #3b82f6);
    color: white;
  }
}
</style>
