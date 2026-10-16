import { ref, onMounted, onBeforeUnmount, type Ref } from 'vue'

/**
 * 响应式媒体查询。
 *
 * 项目未引入 @vueuse/core，此处提供最小实现——仅包装 `window.matchMedia`
 * 并把匹配状态暴露为响应式 ref，供需要按视口切换**行为**（而非仅样式）的场景使用。
 *
 * 纯样式差异应继续用 CSS 媒体查询；只有当视口决定组件的渲染结构或交互逻辑时
 * 才用本 composable（如窄屏改用悬浮抽屉、禁用某种交互）。
 *
 * @param query 标准媒体查询串，如 `'(max-width: 760px)'`
 * @returns 是否匹配的响应式 ref
 */
export const useMediaQuery = (query: string): Ref<boolean> => {
  const matches = ref(false)
  let mql: MediaQueryList | null = null

  const onChange = (e: MediaQueryListEvent) => {
    matches.value = e.matches
  }

  onMounted(() => {
    // SSR / 老浏览器无 matchMedia：退化为 false（不匹配），调用方走桌面端分支
    if (typeof window === 'undefined' || !window.matchMedia) return
    mql = window.matchMedia(query)
    matches.value = mql.matches
    mql.addEventListener('change', onChange)
  })

  onBeforeUnmount(() => {
    mql?.removeEventListener('change', onChange)
    mql = null
  })

  return matches
}
