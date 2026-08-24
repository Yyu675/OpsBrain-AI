<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ComingSoonPanel from '@/components/common/ComingSoonPanel.vue'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'

const route = useRoute()
const title = computed(() => String(route.meta.title || '未来能力'))
const stage = computed(() => String(route.meta.stage || 'L2–L5'))
const description = computed(() => String(route.meta.description || '该能力已进入产品路线图，将在对应自治阶段逐步开放。'))
const capabilities = computed(() => Array.isArray(route.meta.capabilities) ? route.meta.capabilities.map(String) : [])

/**
 * 面包屑层级。
 *
 * 本页被 14 条 L2~L5 路由复用（实时监控 / 自动化策略 / 自愈任务 / 审计日志 …），
 * 此前**没有任何返回入口**——用户点进来只能靠浏览器后退。
 *
 * 层级取自路由路径的父段：`/automation/policies` → 首页 > 自动化 > 自动化策略。
 * 父段仅作分组标签不可点击（这些分组本身没有对应的落地页面，
 * 给它挂链接会跳到 404——宁可不可点，也不给出跳到不存在页面的链接）。
 */
const SEGMENT_LABELS: Record<string, string> = {
  automation: '自动化',
  'self-healing': '自愈',
  governance: '治理'
}

const breadcrumbItems = computed(() => {
  const segs = route.path.split('/').filter(Boolean)
  const items: Array<{ label: string; to?: string }> = []
  // 路径含分组段时（如 /automation/policies）插入分组标签
  if (segs.length > 1) {
    const groupLabel = SEGMENT_LABELS[segs[0]]
    if (groupLabel) items.push({ label: groupLabel })
  }
  items.push({ label: title.value })
  return items
})
</script>

<template>
  <main class="future-page">
    <AppBreadcrumb :items="breadcrumbItems" class="future-breadcrumb" />
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
/* 面包屑贴近页面左上，不参与 ComingSoonPanel 的居中布局 */
.future-breadcrumb {
  max-width: 1280px;
  margin: -48px auto 24px;
}
@media (max-width: 767px) {
  .future-page { padding: 28px 16px; }
  .future-breadcrumb { margin: 0 auto 16px; }
}
</style>
