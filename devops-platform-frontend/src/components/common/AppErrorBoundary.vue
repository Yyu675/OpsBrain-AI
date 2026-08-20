<script setup lang="ts">
import { ref, onErrorCaptured, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { AlertTriangle, RefreshCw, Home, Copy } from 'lucide-vue-next'
import { copyText } from '@/utils/clipboard'

interface Props {
  scope?: string
}
const props = withDefaults(defineProps<Props>(), { scope: '页面' })

const err = ref<Error | null>(null)
const route = useRoute()

onErrorCaptured((e) => {
  console.error('[AppErrorBoundary] captured:', e)
  err.value = e instanceof Error ? e : new Error(String(e))
  return false
})

watch(() => route.fullPath, () => {
  err.value = null
})

const retry = () => {
  err.value = null
}

const backHome = () => {
  err.value = null
  window.location.href = '/'
}

const copySummary = async () => {
  if (!err.value) return
  const lines = [
    `[错误摘要] ${props.scope}`,
    `时间：${new Date().toISOString()}`,
    `路径：${route.fullPath}`,
    `URL：${typeof window !== 'undefined' ? window.location.href : '—'}`,
    `UA：${typeof navigator !== 'undefined' ? navigator.userAgent : '—'}`,
    `消息：${err.value.message || '(无消息)'}`,
    '堆栈：',
    err.value.stack || '(无堆栈)'
  ]
  const ok = await copyText(lines.join('\n'))
  if (ok) ElMessage.success('错误摘要已复制到剪贴板')
  else ElMessage.warning('复制失败，请手动选择技术详情复制')
}
</script>

<template>
  <div class="err-boundary-root">
    <div v-if="err" class="err-boundary">
      <div class="err-card">
        <div class="err-icon">
          <AlertTriangle :size="32" />
        </div>
        <h2 class="err-title">{{ scope }}加载失败</h2>
        <p class="err-msg">{{ err.message || '发生了未知错误' }}</p>
        <p class="err-hint">该问题不会影响其他页面的正常访问</p>
        <div class="err-actions">
          <button class="err-btn err-btn-primary" @click="retry">
            <RefreshCw :size="14" />
            重试
          </button>
          <button class="err-btn" @click="copySummary">
            <Copy :size="14" />
            复制错误摘要
          </button>
          <button class="err-btn" @click="backHome">
            <Home :size="14" />
            返回首页
          </button>
        </div>
        <details v-if="err.stack" class="err-detail">
          <summary>技术详情</summary>
          <pre>{{ err.stack }}</pre>
        </details>
      </div>
    </div>
    <slot v-else />
  </div>
</template>

<style scoped lang="scss">
.err-boundary-root {
  /* 单根容器：满足 <Suspense> 默认插槽单根节点要求，不引入额外布局 */
  display: contents;
}

.err-boundary {
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--color-bg);
}

.err-card {
  max-width: 520px;
  width: 100%;
  padding: 32px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  text-align: center;
}

.err-icon {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--state-error-bg);
  color: var(--state-error);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}

.err-title {
  font-size: var(--text-xl);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 8px 0;
}

.err-msg {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  margin: 0 0 4px 0;
  word-break: break-word;
}

.err-hint {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  margin: 0 0 24px 0;
}

.err-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
  margin-bottom: 16px;
}

.err-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
  }

  &.err-btn-primary {
    background: var(--color-primary);
    color: white;
    border-color: var(--color-primary);

    &:hover {
      background: var(--color-primary-light);
      color: white;
    }
  }
}

.err-detail {
  text-align: left;
  margin-top: 16px;
  padding: 12px;
  background: var(--color-bg-sunken);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  color: var(--color-text-secondary);

  summary {
    cursor: pointer;
    color: var(--color-text-tertiary);
    user-select: none;
  }

  pre {
    margin: 8px 0 0 0;
    max-height: 200px;
    overflow: auto;
    white-space: pre-wrap;
    word-break: break-word;
    font-family: var(--font-mono);
    font-size: 11px;
  }
}
</style>
