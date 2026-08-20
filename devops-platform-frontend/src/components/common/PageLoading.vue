<script setup lang="ts">
import { Loader2 } from 'lucide-vue-next'

interface Props {
  tip?: string
}
withDefaults(defineProps<Props>(), { tip: '页面加载中...' })
</script>

<template>
  <div class="page-loading">
    <div class="spinner">
      <Loader2 :size="28" />
    </div>
    <p class="tip">{{ tip }}</p>
  </div>
</template>

<style scoped lang="scss">
.page-loading {
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  background: var(--color-bg);
}

.spinner {
  color: var(--color-primary);
  animation: spin 0.9s linear infinite;
  display: inline-flex;
}

.tip {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
  margin: 0;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
