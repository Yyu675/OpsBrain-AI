<script setup lang="ts">
/**
 * 列表加载骨架（行条形式）。
 *
 * ── 为什么要有这个组件 ────────────────────────────────────────
 * TicketList / AlertList / KnowledgeBase / ApprovalCenter 各自写了一份
 * `v-for` + `.skeleton-bar` + `@keyframes shimmer`，四份实现已经漂移：
 *
 *   | 页面           | 条高 | 圆角 | 动画时长 | 渐变色                    |
 *   | TicketList     | 20px | 4px  | 1.5s     | **硬编码 #f0f0f0/#e0e0e0** |
 *   | KnowledgeBase  | 72px | 8px  | 1.5s     | **硬编码 #f0f0f0/#e0e0e0** |
 *   | AlertList      | 20px | 6px  | 1.4s     | 令牌（正确）               |
 *   | ApprovalCenter | 20px | —    | —        | 令牌                       |
 *
 * 前两者的硬编码是**真实缺陷而非风格问题**：`#f0f0f0` 在暗色主题下
 * 是刺眼的亮白色块，加载瞬间整屏闪白——恰恰是 index.html 里那段
 * 防闪烁脚本要避免的效果，却在列表加载时又出现了一次。
 *
 * ── 设计取舍 ──────────────────────────────────────────────────
 * 不复用 AppSkeleton：那个是**路由级**骨架（含 toolbar/sidebar 的整页布局，
 * 由 router 的 loadingComponent 使用），塞进列表容器里会多出一层
 * 页面边距与卡片，与列表已有的容器叠加成双层白底。
 * 两者职责不同，各自保留更清晰。
 */
withDefaults(defineProps<{
  /** 骨架行数。建议与该列表的典型每页条数接近，避免加载完成时高度剧烈跳变 */
  rows?: number
  /** 单行高度。列表行用默认 20px，卡片列表传 72px 之类 */
  height?: string
  /** 是否套一层卡片容器（列表页需要，已在卡片内部时传 false） */
  boxed?: boolean
}>(), {
  rows: 6,
  height: '20px',
  boxed: true
})
</script>

<template>
  <!--
    role=status + aria-busy：屏幕阅读器会播报「加载中」。
    此前四处骨架都是纯视觉的 div，读屏用户只会听到一片沉默，
    无从判断是在加载还是已经加载完但没有数据。
  -->
  <div
    class="skeleton-rows"
    :class="{ 'skeleton-rows--boxed': boxed }"
    role="status"
    aria-busy="true"
    aria-label="内容加载中"
  >
    <div
      v-for="n in rows"
      :key="n"
      class="skeleton-rows__bar"
      :style="{ height }"
    />
  </div>
</template>

<style scoped lang="scss">
.skeleton-rows {
  display: flex;
  flex-direction: column;
  gap: 12px;

  &--boxed {
    background: var(--color-surface);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-sm);
    padding: 24px;
  }
}

.skeleton-rows__bar {
  border-radius: var(--radius-sm, 6px);
  /*
   * 全部走令牌：暗色主题下 surface-2 / border-1 会自动切到深色，
   * 不会再出现亮白色块闪屏。
   */
  background: linear-gradient(
    90deg,
    var(--color-bg-sunken, var(--surface-2)) 25%,
    var(--color-border-light, var(--border-1)) 37%,
    var(--color-bg-sunken, var(--surface-2)) 63%
  );
  background-size: 400% 100%;
  animation: skeleton-rows-shimmer 1.4s ease infinite;
}

@keyframes skeleton-rows-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/*
 * 减少动态效果：theme.css 已全局把 animation-duration 压到 0.01ms，
 * 但那会让渐变停在随机一帧、看起来像渲染坏了。
 * 这里显式给一个静态底色，保证静止状态也是合理的视觉。
 */
@media (prefers-reduced-motion: reduce) {
  .skeleton-rows__bar {
    background: var(--color-bg-sunken, var(--surface-2));
  }
}
</style>
