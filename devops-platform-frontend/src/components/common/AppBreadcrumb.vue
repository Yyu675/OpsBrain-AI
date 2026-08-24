<script setup lang="ts">
/**
 * 全站统一面包屑（层级导航 + 返回）
 *
 * ## 统一前的问题
 *
 * 各页各写一套返回方式，共 4 种形态且互不一致：
 * - `KnowledgeDetail`：「< 返回 | 运维知识库 > 分类 > 标题」——「返回」与层级链并列，语义重复
 * - `TicketDetail`：「< 返回工单列表 > TKT-xxx」——只有一级父节点
 * - `AlertDetail`：「< 告警事件 / ID」——分隔符用 `/` 而非 `>`
 * - `AiChatView`：仅一个箭头图标按钮，读 `?from=` 回跳
 * - `ActionItemBoard`：**完全没有返回**
 *
 * 且所有页面的链路都不从首页起——用户从书签直接打开详情页时，
 * 无法沿面包屑回到全站根节点。
 *
 * ## 统一后的规则
 *
 * 1. **首页恒为第一级**，逐级递进：`首页 > 知识库 > 分类 > 文档标题`
 * 2. 除末级外每一级都可点击跳转；末级是当前页，不可点击
 * 3. **不再单独放「返回」按钮**——层级链本身即返回入口，父级就是"上一层"。
 *    单独的返回按钮与链路并存会造成两个语义重叠的控件（原 KnowledgeDetail 即如此）
 * 4. 分隔符统一为 `ChevronRight`，不混用 `/`
 *
 * ## 为何不用 router.back()
 *
 * `history.back()` 回到的是"上一个访问过的页面"，与层级无关——
 * 从工单详情点相似工单跳到另一张工单，再点返回会回到上一张工单而非工单列表，
 * 用户预期的"返回列表"落空。层级跳转是确定的，`back()` 是不确定的。
 */
import { computed } from 'vue'
import { Home, ChevronRight } from 'lucide-vue-next'

export interface BreadcrumbItem {
  /** 显示文案 */
  label: string
  /** 跳转目标。不传则该级不可点击（通常是末级当前页） */
  to?: string
}

const props = withDefaults(defineProps<{
  /**
   * 层级链（**不含首页**，首页由组件自动置于最前）。
   *
   * 例：`[{ label: '知识库', to: '/knowledge' }, { label: '容器编排' }, { label: doc.title }]`
   * 渲染为：首页 > 知识库 > 容器编排 > 文档标题
   */
  items: BreadcrumbItem[]
  /** 末级文案过长时的最大宽度（px），超出省略号 */
  currentMaxWidth?: number
}>(), {
  currentMaxWidth: 420
})

/** 完整链路 = 首页 + 调用方给的层级 */
const chain = computed<BreadcrumbItem[]>(() => [
  { label: '首页', to: '/' },
  ...props.items
])
</script>

<template>
  <nav class="app-breadcrumb" aria-label="面包屑导航">
    <template v-for="(item, idx) in chain" :key="`${item.label}-${idx}`">
      <ChevronRight v-if="idx > 0" :size="14" class="bc-sep" aria-hidden="true" />

      <!-- 可跳转层级 -->
      <RouterLink v-if="item.to && idx < chain.length - 1" :to="item.to" class="bc-link" :title="item.label">
        <Home v-if="idx === 0" :size="14" class="bc-home-icon" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </RouterLink>

      <!-- 末级（当前页）或无跳转目标的中间层级 -->
      <span
        v-else
        class="bc-item"
        :class="{ current: idx === chain.length - 1 }"
        :style="idx === chain.length - 1 ? { maxWidth: currentMaxWidth + 'px' } : undefined"
        :title="item.label"
        :aria-current="idx === chain.length - 1 ? 'page' : undefined"
      >
        <Home v-if="idx === 0" :size="14" class="bc-home-icon" aria-hidden="true" />
        {{ item.label }}
      </span>
    </template>
  </nav>
</template>

<style scoped>
.app-breadcrumb {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  font-size: var(--text-sm, 13px);
  color: var(--color-text-tertiary, var(--text-3));
  /* 窄屏时允许横向滚动，而非换行撑高页头或截断层级 */
  overflow-x: auto;
  scrollbar-width: none;
  white-space: nowrap;
}
.app-breadcrumb::-webkit-scrollbar {
  display: none;
}

.bc-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px;
  border-radius: var(--radius-sm, 4px);
  color: var(--color-text-secondary, #606266);
  text-decoration: none;
  flex-shrink: 0;
  transition: color 0.15s ease, background 0.15s ease;
}

.bc-link:hover {
  color: var(--color-primary, var(--brand));
  background: var(--color-primary-lighter, var(--brand-subtle));
}

.bc-link:focus-visible {
  outline: 2px solid var(--color-primary, var(--brand));
  outline-offset: 1px;
}

.bc-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px;
  flex-shrink: 0;
}

/* 末级是当前页：加重字色以示"你在这里"，过长则省略 */
.bc-item.current {
  color: var(--color-text-primary, var(--text-1));
  font-weight: var(--weight-medium, 500);
  overflow: hidden;
  text-overflow: ellipsis;
  display: inline-block;
  vertical-align: bottom;
}

.bc-home-icon {
  flex-shrink: 0;
}

.bc-sep {
  flex-shrink: 0;
  color: var(--color-border, #d9dee7);
}
</style>
