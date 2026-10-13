import { onBeforeUnmount } from 'vue'

/**
 * 导航项悬停预取（批93 自 AppSidebar 下沉为 composable，逻辑原样迁移）。
 *
 * 悬停/聚焦 150ms 后预取目标路由 chunk（侧栏惯例：指向即加载，点下去不转圈）。
 * prefetchers 导入表提到模块级——全站共享一份；预取去重 Set 与定时器留在
 * 实例作用域，卸载时清干净（定时器泄漏会在登出后继续 import chunk）。
 */
const prefetchers: Record<string, () => Promise<unknown>> = {
  home: () => import('@/views/Home.vue'),
  assistant: () => import('@/views/AiAssistant.vue'),
  knowledge: () => import('@/views/KnowledgeBase.vue'),
  tickets: () => import('@/views/TicketList.vue'),
  disposal: () => import('@/views/DisposalCenter.vue'),
  help: () => import('@/views/HelpCenter.vue')
}

export function useNavPrefetch() {
  const prefetched = new Set<string>()
  const hoverTimers = new Map<string, ReturnType<typeof setTimeout>>()

  const doPrefetch = (key: string) => {
    if (prefetched.has(key)) return
    prefetched.add(key)
    prefetchers[key]?.().catch(() => prefetched.delete(key))
  }

  const prefetchWithIntent = (key: string) => {
    if (prefetched.has(key) || hoverTimers.has(key)) return
    const t = setTimeout(() => {
      hoverTimers.delete(key)
      doPrefetch(key)
    }, 150)
    hoverTimers.set(key, t)
  }

  const cancelPrefetch = (key: string) => {
    const t = hoverTimers.get(key)
    if (t) {
      clearTimeout(t)
      hoverTimers.delete(key)
    }
  }

  onBeforeUnmount(() => {
    hoverTimers.forEach(t => clearTimeout(t))
    hoverTimers.clear()
  })

  return { doPrefetch, prefetchWithIntent, cancelPrefetch }
}
