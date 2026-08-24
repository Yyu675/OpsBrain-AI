<script setup lang="ts">
/**
 * 服务端分页控件。
 *
 * 建立动因：TicketList 与 AlertList 各写了一份分页模板 + 60/66 行 CSS，
 * 且**已经漂移**——chevron 按钮 36px vs 32px、圆角 md vs sm、
 * disabled 透明度 0.5 vs 0.4、控件间距 8px vs 6px。同一控件在两个列表页
 * 长得不一样，正是重复实现的必然结果。
 *
 * 统一取 TicketList 那套尺寸（主列表页，视觉更完整）。
 *
 * 页码序列与区间计算由 useServerPagination 提供，本组件只负责渲染——
 * 逻辑与展示分离便于分别测试。
 */
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'

import type { PageItem } from '@/composables/useServerPagination'

defineProps<{
  currentPage: number
  totalPages: number
  /** 匹配总条数（后端按当前筛选统计） */
  total: number
  /** 当前页区间起始（1-based） */
  pageStart: number
  /** 当前页区间结束 */
  pageEnd: number
  /** 页码按钮序列，含省略号占位 */
  pageNumbers: PageItem[]
  /** 顶部外边距。列表页与卡片视图的上下留白需求不同 */
  marginTop?: string
}>()

const emit = defineEmits<{
  /** 请求跳转到指定页。越界由 useServerPagination.goToPage 拦截 */
  'page-change': [page: number]
}>()

const go = (page: number) => emit('page-change', page)
</script>

<template>
  <div class="pagination" :style="marginTop ? { marginTop } : undefined">
    <div class="pagination-info">
      显示 {{ pageStart }}-{{ pageEnd }} 共 {{ total }} 条
    </div>
    <div class="pagination-controls">
      <button
        class="pagination-btn-chevron"
        type="button"
        :disabled="currentPage <= 1"
        aria-label="上一页"
        @click="go(currentPage - 1)"
      >
        <ChevronLeft :size="16" />
      </button>

      <div class="pagination-pages">
        <template v-for="(p, idx) in pageNumbers">
          <span v-if="p === 'ellipsis'" :key="`e-${idx}`" class="pagination-ellipsis">...</span>
          <button
            v-else
            :key="`p-${idx}`"
            class="pagination-page"
            type="button"
            :class="{ active: currentPage === p }"
            :aria-current="currentPage === p ? 'page' : undefined"
            @click="go(p)"
          >{{ p }}</button>
        </template>
      </div>

      <button
        class="pagination-btn-chevron"
        type="button"
        :disabled="currentPage >= totalPages"
        aria-label="下一页"
        @click="go(currentPage + 1)"
      >
        <ChevronRight :size="16" />
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.pagination-info {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.pagination-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pagination-btn-chevron {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.pagination-pages {
  display: flex;
  align-items: center;
  gap: 4px;
}

.pagination-page {
  min-width: 32px;
  height: 32px;
  padding: 0 6px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-tertiary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
    background: var(--color-primary-lighter);
  }

  &.active {
    border-color: var(--color-primary);
    background: var(--color-primary);
    color: var(--color-text-inverse);
  }
}

.pagination-ellipsis {
  display: flex;
  align-items: center;
  padding: 0 2px;
  color: var(--color-text-tertiary);
}

@media (max-width: 640px) {
  .pagination {
    justify-content: center;
  }
}
</style>
