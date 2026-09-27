import { watch } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'

import { useAppStore } from '@/stores/app'

/**
 * 登出时清理「属于上一个用户」的本地数据。
 *
 * ── 要解决什么 ──────────────────────────────────────────────
 * 1. TanStack Query 缓存：gcTime 5 分钟内工单列表、告警、审批队列、
 *    审计日志都留在内存里。下一个用户在同一标签页登录后若命中相同
 *    queryKey，会先看到上一个人的数据（stale-while-revalidate 默认
 *    行为：先渲染缓存再后台刷新）。对只读用户尤其严重。
 * 2. 遗留的 AI 对话持久键 `__store__:chat-sessions`：独立 AI 对话页
 *    已下线（2026-09-27 方案乙，AI 能力只保留嵌入式入口），store 虽删，
 *    老用户机器上仍可能有存量数据——会话里的 citations 是知识库原文
 *    片段，而知识库有可见性分级，必须继续清。
 * 3. 复盘沉淀草稿（sessionStorage `__draft__:sink-draft.*`）。
 *
 * ── 为什么挂在这里而不是各个登出入口 ────────────────────────
 * 登出路径有四条：导航栏菜单、闲置超时、闲置警告里选「立即退出」、
 * http 层 401 后的 resetToGuest。逐个去加清理调用，
 * 必然会漏掉一条——而漏掉的那条恰恰是最难复现的（401 自动登出）。
 * 改为监听 `isAuthenticated` 由 true → false 这个**状态事实**，
 * 无论哪条路径触发都覆盖得到。
 *
 * ── 哪些数据刻意不清 ────────────────────────────────────────
 * - 通知的 readIds / dismissedIds：是「这台机器上处理过哪些告警」的
 *   操作记忆，不含内容，同一人重新登录后仍应生效
 *   （清空会让所有历史告警重新变未读，红点数字暴涨）
 * - 列宽 / 主题 / 密度等界面偏好：属于设备而非账号，
 *   清掉会让共用机器的每个人每次登录都要重调布局
 * - 编辑器草稿：存在 sessionStorage，本就随标签页关闭消失，
 *   且清掉等于把用户没保存的文字直接删了，代价远大于收益
 */
export function useSessionCleanup(): void {
  const app = useAppStore()
  const queryClient = useQueryClient()

  watch(
    () => app.isAuthenticated,
    (authed, wasAuthed) => {
      // 只在「确实从已登录退出」时清理。
      // 不能省略 wasAuthed 判断：应用启动时该值由 undefined → false
      // 也会触发一次 watch，那时清理没有意义（本来就没人登录过）。
      if (wasAuthed === true && !authed) {
        // 遗留 AI 对话持久键（独立对话页已下线，仅存于老用户机器）
        try {
          localStorage.removeItem('__store__:chat-sessions')
        } catch {
          // 隐私模式等异常：清不掉就清不掉，不阻塞登出主流程
        }

        /*
         * 清除复盘沉淀草稿（__draft__:sink-draft.*）。
         *
         * 批 85：前缀从 opsbrain.sink-draft. 改为 __draft__:sink-draft.，
         * 与全站 draftStorage 统一（TicketFormDialog / ArticleFormDialog
         * 同族前缀 __draft__:）。localStorage 改 sessionStorage 在批 85
         * 同时收敛，此处只管清理——sessionStorage 不跨标签页，正常关页
         * 即清，此分支只兜底「浏览器登出时未手动关标签页」边缘场景。
         */
        try {
          const doomed: string[] = []
          for (let i = 0; i < sessionStorage.length; i++) {
            const key = sessionStorage.key(i)
            if (key?.startsWith('__draft__:sink-draft.')) doomed.push(key)
          }
          doomed.forEach(k => sessionStorage.removeItem(k))
        } catch {
          // 隐私模式等异常：清不掉就清不掉，不阻塞登出主流程
        }

        /*
         * 清空 TanStack Query 缓存。
         *
         * 用 clear() 而非 invalidateQueries()：后者只标记过期、数据仍在缓存中，
         * 挡不住「先渲染旧数据」这一步。这里要的是**移除**，不是「下次重拉」。
         */
        queryClient.clear()
      }
    }
  )
}
