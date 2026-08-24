<script setup lang="ts">
/**
 * 列表数据的「加载 / 失败 / 空 / 有内容」四态统一渲染。
 *
 * ── 要解决什么 ────────────────────────────────────────────────
 * 五个列表页各写一份四分支 v-if 链，条件写法已经不一致：
 *
 *   TicketList      v-if="listLoading && pagedTickets.length === 0"
 *   AlertList       v-if="listLoading && alerts.length === 0"
 *   ApprovalCenter  v-else-if="loading && items.length === 0"   ← 前面还挂了别的分支
 *   KnowledgeBase   v-if="store.loading && store.list.length === 0"
 *   ActionItemBoard v-if="loading"                              ← 少了 length 判断
 *
 * 最后一个是**真缺陷**：`v-if="loading"` 不带 length 判断，意味着每次
 * 刷新筛选时已有的列表会整个消失、换成加载态，加载完再重新出现——
 * 用户看到的是内容闪断，而不是「在原位刷新」。这正是分散实现的必然结果：
 * 四处写对了、一处写漏，没有任何机制能发现。
 *
 * 另外五页的空态组件也不统一（AppEmpty vs EmptyState 两套并存），
 * 错误态有的用 ApiErrorState、有的自己拼 icon + 文案 + 按钮。
 *
 * ── 状态优先级（唯一真相）────────────────────────────────────
 * 1. 有数据 → 永远渲染内容（加载中则叠加遮罩，见 busy 插槽说明）
 * 2. 无数据 + 加载中 → 骨架
 * 3. 无数据 + 有错误 → 错误态（可重试）
 * 4. 无数据 + 无错误 → 空态
 *
 * 「有数据优先」是刻意的：翻页 / 改筛选时把已有内容换成骨架，
 * 会让页面高度塌陷再撑开，滚动位置也跟着跳。保留旧内容 + 顶部进度条
 * 是列表类产品的通行做法（GitHub / Linear 皆如此）。
 */
import { computed } from 'vue'

import AppEmpty from './AppEmpty.vue'
import ApiErrorState from './ApiErrorState.vue'
import SkeletonRows from './SkeletonRows.vue'

const props = withDefaults(defineProps<{
  /** 是否正在请求 */
  loading?: boolean
  /** 错误对象（HttpError / Error / string / null）。为空表示无错误 */
  error?: unknown
  /** 当前已有的数据条数。决定走「首屏」还是「就地刷新」路径 */
  count?: number

  /** 是否处于筛选状态——空态文案要区分「还没有数据」与「筛选没命中」 */
  filtered?: boolean
  /** 空态标题，不传用 AppEmpty 按 kind 推断的默认值 */
  emptyTitle?: string
  /** 无筛选时的空态描述 */
  emptyDescription?: string
  /** 有筛选时的空态描述 */
  filteredDescription?: string
  /** 空态主按钮文案，不传则不渲染按钮 */
  emptyActionText?: string

  /** 骨架行数与行高，透传给 SkeletonRows */
  skeletonRows?: number
  skeletonHeight?: string
  /** 骨架是否套卡片容器 */
  skeletonBoxed?: boolean
}>(), {
  loading: false,
  count: 0,
  filtered: false,
  skeletonRows: 6,
  skeletonHeight: '20px',
  skeletonBoxed: true
})

const emit = defineEmits<{
  /** 错误态点「重试」 */
  retry: []
  /** 空态点主按钮 */
  'empty-action': []
}>()

const hasData = computed(() => props.count > 0)
const hasError = computed(() => props.error !== null && props.error !== undefined)

/**
 * 就地刷新：已有数据但仍在请求。
 * 此时不换骨架，只在内容上方显示一条细进度条——
 * 用户能知道「在刷新」，又不失去当前正在看的内容。
 */
const isRefreshing = computed(() => hasData.value && props.loading)

/**
 * 「刷新失败但仍有旧数据」是独立于四态之外的一种情形。
 *
 * 不能因为刷新失败就把已有列表清空换成错误页——用户手上的数据
 * 虽然旧但仍然有用（尤其值守场景：网络抖一下不该让告警列表变空白）。
 * 改为保留内容 + 顶部一条可重试的提示条。
 */
const showStaleBanner = computed(() => hasData.value && hasError.value && !props.loading)

const emptyKind = computed(() => (props.filtered ? 'search' : 'default'))
const emptyDesc = computed(() =>
  props.filtered ? props.filteredDescription : props.emptyDescription
)
</script>

<template>
  <div class="data-state">
    <!-- 就地刷新：细进度条，不遮挡内容 -->
    <div v-if="isRefreshing" class="data-state__progress" role="status" aria-label="正在刷新">
      <div class="data-state__progress-bar" />
    </div>

    <!-- 刷新失败但有旧数据：保留内容，顶部提示可重试 -->
    <div v-if="showStaleBanner" class="data-state__stale" role="alert">
      <span>刷新失败，当前显示的是上一次加载的数据</span>
      <button type="button" class="data-state__stale-retry" @click="emit('retry')">
        重试
      </button>
    </div>

    <!-- 1. 有数据：永远优先渲染内容 -->
    <slot v-if="hasData" />

    <!-- 2. 无数据 + 加载中 -->
    <SkeletonRows
      v-else-if="loading"
      :rows="skeletonRows"
      :height="skeletonHeight"
      :boxed="skeletonBoxed"
    />

    <!-- 3. 无数据 + 有错误 -->
    <div v-else-if="hasError" class="data-state__slot">
      <ApiErrorState :error="error" compact retry-label="重试" @retry="emit('retry')" />
    </div>

    <!-- 4. 无数据 + 无错误 -->
    <div v-else class="data-state__slot">
      <slot name="empty">
        <AppEmpty
          :kind="emptyKind"
          size="sm"
          :title="emptyTitle"
          :description="emptyDesc"
          :action-text="emptyActionText"
          @action="emit('empty-action')"
        />
      </slot>
    </div>
  </div>
</template>

<style scoped lang="scss">
.data-state {
  position: relative;
}

.data-state__slot {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 8px 16px;
}

/* 顶部细进度条：indeterminate 往复动画，不占布局高度 */
.data-state__progress {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--color-bg-sunken, var(--surface-2));
  z-index: 2;
}

.data-state__progress-bar {
  width: 40%;
  height: 100%;
  border-radius: 2px;
  background: var(--color-primary, var(--brand));
  animation: data-state-indeterminate 1.1s ease-in-out infinite;
}

@keyframes data-state-indeterminate {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(350%); }
}

/*
 * 减少动态效果下往复动画会被压成静止的一小段，看着像卡死。
 * 改为铺满 + 半透明，表达「进行中」而不依赖运动。
 */
@media (prefers-reduced-motion: reduce) {
  .data-state__progress-bar {
    width: 100%;
    opacity: 0.5;
    animation: none;
  }
}

.data-state__stale {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 8px 14px;
  border-radius: var(--radius-md, 8px);
  background: var(--state-warning-bg, var(--warning-subtle));
  color: var(--state-warning, var(--warning));
  font-size: var(--text-sm, 13px);
}

.data-state__stale-retry {
  flex-shrink: 0;
  padding: 3px 12px;
  border: 1px solid currentColor;
  border-radius: var(--radius-sm, 6px);
  background: transparent;
  color: inherit;
  font-size: var(--text-xs, 12px);
  font-family: var(--font-body);
  cursor: pointer;

  &:hover {
    background: color-mix(in oklab, currentColor 12%, transparent);
  }
}
</style>
