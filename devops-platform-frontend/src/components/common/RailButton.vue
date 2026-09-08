<script setup lang="ts">
/**
 * 折叠态图标轨上的单个按钮。
 *
 * 配合 {@link CollapsiblePanel} 的 `rail` 插槽使用：折叠后侧栏收为 48px 图标轨，
 * 每个可导航项在轨上呈现为一个图标按钮，当前项高亮——使折叠后仍能看清
 * 「现在筛选的是哪个分类」「大纲读到第几节」并直接点击跳转。
 *
 * `count` 用小角标呈现（如某分类下的文档数）；超过两位数显示 `99+`，
 * 否则会把 48px 的轨撑开。
 */
withDefaults(defineProps<{
  /** 是否为当前项——高亮 */
  active?: boolean
  /** 悬浮提示。折叠态下图标本身无文字，tooltip 是唯一的语义来源，务必传 */
  title?: string
  /** 右上角计数角标。0 或未传则不显示 */
  count?: number
  /** 用小圆点替代图标（大纲章节等无天然图标的场景） */
  dot?: boolean
}>(), {
  active: false,
  count: 0,
  dot: false
})
</script>

<template>
  <button
    class="rail-btn"
    :class="{ active, 'is-dot': dot }"
    type="button"
    :title="title"
    :aria-label="title"
    :aria-current="active ? 'true' : undefined"
  >
    <span v-if="dot" class="rail-dot" aria-hidden="true" />
    <slot v-else />
    <span v-if="count > 0" class="rail-count">{{ count > 99 ? '99+' : count }}</span>
  </button>
</template>

<style scoped>
.rail-btn {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border: none;
  border-radius: var(--radius-sm, 6px);
  background: transparent;
  color: var(--color-text-tertiary, var(--text-3));
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.rail-btn:hover {
  background: var(--color-surface-hover, var(--surface-2));
  color: var(--color-text-primary, var(--text-1));
}

.rail-btn.active {
  background: var(--color-primary-lighter, var(--brand-subtle));
  color: var(--color-primary, var(--brand));
}

.rail-btn:focus-visible {
  outline: 2px solid var(--color-primary, var(--brand));
  outline-offset: 1px;
}

/* 章节小圆点：大纲这类无天然图标的层级用它表示位置 */
.rail-btn.is-dot {
  width: 34px;
  height: 20px;
}

.rail-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.5;
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.rail-btn.active .rail-dot {
  opacity: 1;
  transform: scale(1.5);
}

/* 计数角标：数字过长会撑破 48px 轨，故模板层已截为 99+ */
.rail-count {
  position: absolute;
  top: -1px;
  right: -1px;
  min-width: 15px;
  height: 15px;
  padding: 0 3px;
  border-radius: 8px;
  background: var(--color-bg-sunken, var(--surface-2));
  color: var(--color-text-tertiary, var(--text-3));
  font-size: 10px;
  line-height: 15px;
  font-weight: var(--weight-medium, 500);
  text-align: center;
  box-sizing: border-box;
}

.rail-btn.active .rail-count {
  background: var(--color-primary, var(--brand));
  color: #fff;
}
</style>
