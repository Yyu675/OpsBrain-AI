<script setup lang="ts">
/**
 * 可折叠侧栏容器（知识库三页共用）
 *
 * ## 折叠触发器不由本组件摆放
 *
 * 早期版本渲染了一条独立的 24px 竖条按钮夹在侧栏与正文之间——它孤立于内容之外，
 * 位置与侧栏标题不在同一水平线上，视觉上像多出来一个游离控件。
 *
 * 现改为：本组件**不渲染任何独立折叠条**，而是把 `toggle` / `collapsed`
 * 通过默认插槽的 slot props 交给调用方，由调用方把折叠按钮放进自己的标题行
 * （如「文档分类」「运维知识库」那一行的右端）。折叠控制因此与它所控制的内容同处一处。
 *
 * 折叠态下调用方的标题行已不可见，故此时由本组件在图标轨顶部渲染展开按钮。
 *
 * ## 保留为公共组件的原因
 *
 * 折叠的**逻辑**（持久化、判等方向、宽度过渡、图标轨、移动端堆叠）三页完全一致。
 * 此前三页各写一套已实际漂移（一处 `align-items:flex-start` + 圆角，另两处 `stretch` 无圆角）。
 * 收敛为一份可避免继续漂移；而用户可见的那个游离竖条已经删除，
 * 「折叠按钮长在标题行里」的观感与手写无异。
 *
 * ## 折叠态为何是图标轨而非空白窄条
 *
 * 折叠后仍需看清「当前筛选哪个分类」「大纲读到第几节」，否则折叠即丧失全部功能。
 * `rail` 插槽让调用方在 48px 轨上放图标并高亮当前项，参照 VS Code 活动栏的心智模型：
 * **折叠是收窄而非关闭**。
 *
 * ## 关键实现约束
 *
 * - 展开/收起用 `width` 过渡，不用 `v-show`（后者是 display 硬跳变无动画）
 * - 折叠态与展开态用 `v-if`/`v-else` 而非 `v-show`：两套 DOM 同时存在会互相撑开宽度
 * - 持久化判等必须 `=== 'true'`（写成 `=== 'false'` 会让状态反转且首访看似正常，见项目 6.42）
 */
import { ref, computed, watch, onMounted } from 'vue'
import {
  PanelLeftOpen, PanelRightOpen
} from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  /** 侧栏位于内容区的哪一侧——决定展开图标方向与边框朝向 */
  side?: 'left' | 'right'
  /** localStorage 持久化键。不传则折叠状态仅存活于当前页面生命周期 */
  storageKey?: string
  /** 展开时的侧栏宽度（px） */
  width?: number
  /** 折叠时图标轨的宽度（px） */
  railWidth?: number
  /** 初始是否折叠（无持久化值时生效） */
  defaultCollapsed?: boolean
  /** 无障碍标签，如「分类与标签」「文章大纲」 */
  label?: string
  /**
   * 是否吸顶（侧栏内容随主内容滚动保持可见，如文章大纲）。
   *
   * 不能靠调用方在内部元素上写 `position: sticky`——本组件的 `.cp-panel`
   * 有 `overflow: hidden`（宽度过渡需要裁剪内容），会成为后代 sticky 的
   * 包含滚动盒使其失效。故吸顶必须由本组件在最外层容器上实现。
   */
  sticky?: boolean
  /**
   * 禁用折叠：直接渲染内容，不套折叠容器。
   *
   * 用于调用方在特定视口另有开合机制的场景（如编辑器窄屏的悬浮抽屉）——
   * 若不禁用，两套开合入口并存会互相矛盾：
   * 用户先折叠再点抽屉按钮，内容因已折叠而未渲染，按钮看似失灵。
   */
  disabled?: boolean
}>(), {
  side: 'left',
  width: 240,
  railWidth: 48,
  defaultCollapsed: false,
  label: '侧栏',
  sticky: false,
  disabled: false
})

const emit = defineEmits<{
  (e: 'update:collapsed', value: boolean): void
}>()

/** 读取持久化的折叠状态。判等方向必须是 `=== 'true'`（见文件头注释） */
const readPersisted = (): boolean => {
  if (!props.storageKey) return props.defaultCollapsed
  try {
    const raw = localStorage.getItem(props.storageKey)
    if (raw === null) return props.defaultCollapsed
    return raw === 'true'
  } catch {
    // 隐私模式下 localStorage 不可用：退化为默认值
    return props.defaultCollapsed
  }
}

const collapsed = ref(readPersisted())

const toggle = () => {
  collapsed.value = !collapsed.value
}

watch(collapsed, (v) => {
  emit('update:collapsed', v)
  if (!props.storageKey) return
  try {
    localStorage.setItem(props.storageKey, String(v))
  } catch {
    /* 隐私模式：仅内存持有 */
  }
})

onMounted(() => {
  // 让父组件初始化时即拿到实际折叠态（可能来自持久化，与 default 不同）
  emit('update:collapsed', collapsed.value)
})

const isLeft = computed(() => props.side === 'left')

const panelWidth = computed(() => collapsed.value ? props.railWidth : props.width)

/** 折叠态展开按钮的图标：左栏向右展开，右栏向左展开 */
const expandIcon = computed(() => isLeft.value ? PanelLeftOpen : PanelRightOpen)

defineExpose({ collapsed, toggle })
</script>

<template>
  <!-- 禁用折叠：直接透出内容，不引入容器（见 disabled prop 注释） -->
  <slot v-if="disabled" :toggle="toggle" :collapsed="false" />
  <div
    v-else
    class="cp-panel"
    :class="[`cp-${side}`, { 'cp-collapsed': collapsed, 'cp-sticky': sticky }]"
    :style="{ width: panelWidth + 'px' }"
  >
    <!-- 折叠态：图标轨。顶部内置展开按钮（此时调用方的标题行不可见） -->
    <div
      v-if="collapsed"
      class="cp-rail"
      :style="{ width: railWidth + 'px' }"
    >
      <button
        class="cp-expand"
        type="button"
        :title="`展开${label}`"
        :aria-label="`展开${label}`"
        :aria-expanded="false"
        @click="toggle"
      >
        <component :is="expandIcon" :size="17" />
      </button>
      <div class="cp-rail-sep" />
      <slot name="rail" :toggle="toggle" />
    </div>

    <!--
      展开态：完整内容。
      宽度固定为展开宽度（而非 100%）——过渡期间容器宽度在变，
      若内容跟着容器伸缩会导致文字重排抖动；固定宽度则由容器裁剪，视觉平滑。

      `toggle` / `collapsed` 经 slot props 交出，调用方把折叠按钮放进自己的标题行。
    -->
    <div
      v-else
      class="cp-content"
      :style="{ width: width + 'px' }"
    >
      <slot :toggle="toggle" :collapsed="collapsed" />
    </div>
  </div>
</template>

<style scoped>
.cp-panel {
  /* 宽度过渡：原实现用 v-show 是 display 硬跳变，折叠瞬间视觉突兀 */
  transition: width 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
  flex-shrink: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/*
 * 吸顶：sticky 必须在最外层容器上。
 * `overflow: hidden`（宽度过渡所需）会成为后代 sticky 的包含滚动盒使其失效，
 * 故不能让调用方在内部元素上写 sticky。
 */
.cp-sticky {
  position: sticky;
  top: 0;
  align-self: flex-start;
  max-height: 100vh;
}

.cp-content {
  flex: 1;
  min-height: 0;
  /* 宽度由模板内联样式固定为展开宽度——见模板注释：防过渡期间文字重排抖动 */
  flex-shrink: 0;
  overflow: hidden auto;
}

/* ===== 折叠态图标轨 ===== */
.cp-rail {
  flex: 1;
  min-height: 0;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 0;
  overflow: hidden auto;
  scrollbar-width: none;
}
.cp-rail::-webkit-scrollbar {
  display: none;
}

.cp-rail-sep {
  width: 18px;
  height: 1px;
  margin: 2px 0 4px;
  background: var(--color-border-light, var(--border-1));
  flex-shrink: 0;
}

/* 折叠态展开按钮：与图标轨其余按钮同尺寸，视觉上属于同一列 */
.cp-expand {
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
.cp-expand:hover {
  background: var(--color-primary-lighter, var(--brand-subtle));
  color: var(--color-primary, var(--brand));
}
.cp-expand:focus-visible {
  outline: 2px solid var(--color-primary, var(--brand));
  outline-offset: 1px;
}

/* 折叠态图标轨与主内容之间的分隔线（展开态由调用方内容自带边框） */
.cp-left.cp-collapsed {
  border-right: 1px solid var(--color-border-light, var(--border-1));
}
.cp-right.cp-collapsed {
  border-left: 1px solid var(--color-border-light, var(--border-1));
}

/* 移动端：侧栏改为上下堆叠，图标轨横向排列 */
@media (max-width: 1024px) {
  /* 宽度由模板内联 style 设置，内联优先级高于外部 CSS，故需 !important 覆盖 */
  .cp-panel,
  .cp-content,
  .cp-rail {
    width: 100% !important;
  }
  .cp-panel {
    transition: none;
  }
  /* 吸顶在堆叠布局下无意义（侧栏已不在正文侧边），撤销之 */
  .cp-sticky {
    position: static;
    max-height: none;
  }
  .cp-rail {
    flex-direction: row;
    flex-wrap: wrap;
    justify-content: flex-start;
    padding: 8px 12px;
  }
  .cp-rail-sep {
    width: 1px;
    height: 18px;
    margin: 0 4px;
  }
  .cp-left.cp-collapsed,
  .cp-right.cp-collapsed {
    border-right: none;
    border-left: none;
    border-bottom: 1px solid var(--color-border-light, var(--border-1));
  }
}
</style>
