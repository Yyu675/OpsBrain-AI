<script setup lang="ts">
/**
 * 侧栏折叠按钮——放在侧栏自己的标题行内。
 *
 * 配合 {@link CollapsiblePanel} 的默认插槽 slot props 使用：
 *
 * ```vue
 * <CollapsiblePanel side="left" storage-key="xx-collapsed" v-slot="{ toggle }">
 *   <div class="sidebar-title">
 *     文档分类
 *     <CollapseToggle side="left" @click="toggle" />
 *   </div>
 *   ...
 * </CollapsiblePanel>
 * ```
 *
 * ## 按钮在标题行内的水平位置（由调用方的 CSS 决定，本组件只管图标方向）
 *
 * 按**镜像对称**摆放：折叠控件贴近它要让出的那片空间，也即贴近主内容区。
 * - 左侧栏 → 按钮在标题行**右端**，箭头朝左（往左收）
 * - 右侧栏 → 按钮在标题行**左端**，箭头朝右（往右收）
 *
 * 这样两个按钮从两侧夹住内容区，位置与「收起后内容会往哪边扩展」一致；
 * 若右栏按钮也放右端，它就贴在窗口边缘、离要让出的空间最远，方向感相反。
 *
 * 为何不由 CollapsiblePanel 自己渲染：折叠控制应与它所控制的内容同处一行，
 * 而标题行的结构（文案、其它操作按钮、间距）属调用方职责，
 * 组件无法在不侵入调用方 DOM 的前提下把按钮插进那一行。
 */
withDefaults(defineProps<{
  /** 侧栏所在侧——决定收起图标的方向 */
  side?: 'left' | 'right'
  /** 提示文案中的侧栏名称，如「分类与标签」 */
  label?: string
}>(), {
  side: 'left',
  label: '侧栏'
})
</script>

<template>
  <button
    class="collapse-toggle"
    type="button"
    :title="`收起${label}`"
    :aria-label="`收起${label}`"
    :aria-expanded="true"
  >
    <!-- 左栏向左收、右栏向右收：箭头指向收起后的去向 -->
    <svg
      v-if="side === 'left'"
      width="15" height="15" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
      aria-hidden="true"
    >
      <rect x="3" y="3" width="18" height="18" rx="2" />
      <path d="M9 3v18" />
      <path d="m16 15-3-3 3-3" />
    </svg>
    <svg
      v-else
      width="15" height="15" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
      aria-hidden="true"
    >
      <rect x="3" y="3" width="18" height="18" rx="2" />
      <path d="M15 3v18" />
      <path d="m8 9 3 3-3 3" />
    </svg>
  </button>
</template>

<style scoped>
.collapse-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  border: none;
  border-radius: var(--radius-sm, 5px);
  background: transparent;
  color: var(--color-text-tertiary, #9CA3AF);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.collapse-toggle:hover {
  background: var(--color-primary-lighter, #E8F0FC);
  color: var(--color-primary, #409eff);
}

.collapse-toggle:focus-visible {
  outline: 2px solid var(--color-primary, #409eff);
  outline-offset: 1px;
}
</style>
