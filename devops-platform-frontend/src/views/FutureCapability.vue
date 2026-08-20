<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ComingSoonPanel from '@/components/common/ComingSoonPanel.vue'

const route = useRoute()
const title = computed(() => String(route.meta.title || '未来能力'))
const stage = computed(() => String(route.meta.stage || 'L2–L5'))
const description = computed(() => String(route.meta.description || '该能力已进入产品路线图，将在对应自治阶段逐步开放。'))
const capabilities = computed(() => Array.isArray(route.meta.capabilities) ? route.meta.capabilities.map(String) : [])
</script>

<template>
  <main class="future-page">
    <ComingSoonPanel
      :stage="stage"
      :title="title"
      :description="description"
      :capabilities="capabilities"
    />
  </main>
</template>

<style scoped>
.future-page {
  min-height: calc(100vh - var(--app-header-height, 70px));
  padding: 72px 24px;
  background: var(--color-bg);
}
@media (max-width: 767px) { .future-page { padding: 28px 16px; } }
</style>
