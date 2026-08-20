<script setup lang="ts">
import { ref, onErrorCaptured, watch } from 'vue'
import { useRoute } from 'vue-router'
import { AlertCircle, RefreshCw } from 'lucide-vue-next'

interface Props {
  scope?: string
  minHeight?: string
  compact?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  scope: '该模块',
  minHeight: '160px',
  compact: false
})

const err = ref<Error | null>(null)
const retryToken = ref(0)
const route = useRoute()

onErrorCaptured((e) => {
  console.error('[PanelErrorBoundary]', props.scope, 'captured:', e)
  err.value = e instanceof Error ? e : new Error(String(e))
  return false
})

watch(() => route.fullPath, () => {
  err.value = null
})

const retry = () => {
  err.value = null
  retryToken.value += 1
}
</script>

<template>
  <div
    v-if="err"
    class="panel-err"
    :class="{ 'panel-err-compact': props.compact }"
    :style="{ minHeight: props.minHeight }"
    role="alert"
  >
    <div class="panel-err-icon">
      <AlertCircle :size="props.compact ? 18 : 22" />
    </div>
    <div class="panel-err-body">
      <p class="panel-err-title">{{ props.scope }}加载失败</p>
      <p class="panel-err-msg">{{ err.message || '未知错误' }}</p>
    </div>
    <button class="panel-err-retry" type="button" @click="retry">
      <RefreshCw :size="12" />
      重试
    </button>
  </div>
  <slot v-else :retry-token="retryToken" />
</template>

<style scoped lang="scss">
.panel-err {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: var(--color-surface, #fff);
  border: 1px dashed var(--state-error, #ef4444);
  border-radius: var(--radius-md, 8px);
  color: var(--color-text-primary, #1e293b);
}

.panel-err-compact {
  padding: 10px 12px;
  gap: 8px;
}

.panel-err-icon {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--state-error-bg, #fef2f2);
  color: var(--state-error, #ef4444);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.panel-err-compact .panel-err-icon {
  width: 28px;
  height: 28px;
}

.panel-err-body {
  flex: 1;
  min-width: 0;
}

.panel-err-title {
  margin: 0 0 2px;
  font-size: var(--text-sm, 13px);
  font-weight: var(--weight-medium, 500);
}

.panel-err-msg {
  margin: 0;
  font-size: var(--text-xs, 12px);
  color: var(--color-text-tertiary, #94a3b8);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.panel-err-retry {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: 1px solid var(--color-border-light, #e2e8f0);
  border-radius: var(--radius-sm, 6px);
  background: var(--color-surface, #fff);
  color: var(--color-text-secondary, #64748b);
  font-size: var(--text-xs, 12px);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;

  &:hover {
    border-color: var(--color-primary, #3b82f6);
    color: var(--color-primary, #3b82f6);
  }
}
</style>
