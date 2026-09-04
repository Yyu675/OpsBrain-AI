import { watch } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'

import { useAppStore } from '@/stores/app'
import { useChatStore } from '@/stores/chat'

/**
 * 登出时清理「属于上一个用户」的本地数据。
 *
 * ── 要解决什么 ──────────────────────────────────────────────
 * AI 对话历史全量持久化在 localStorage 的 `chat-sessions` 里，
 * 而这个键**没有按用户隔离**。此前登出只清 token 与身份缓存，
 * 对话记录原样留在磁盘上——下一个在同一台机器登录的人打开 AI 助手，
 * 会直接看到上一个人的完整问答。
 *
 * 这不只是隐私尴尬：会话里的 `metadata.citations` 存的是 AI 从知识库
 * 检索出的**原文片段**，而知识库有可见性分级（PUBLIC / 内部）。
 * 一个只读用户借此就能读到本不该看到的内部文档内容，
 * 等于绕过后端刚落地的权限域隔离。
 *
 * 共享值守机、跨班交接同一终端在运维场景里非常普遍，不是极端假设。
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
  const chat = useChatStore()
  const queryClient = useQueryClient()

  watch(
    () => app.isAuthenticated,
    (authed, wasAuthed) => {
      // 只在「确实从已登录退出」时清理。
      // 不能省略 wasAuthed 判断：应用启动时该值由 undefined → false
      // 也会触发一次 watch，那时清理没有意义（本来就没人登录过），
      // 反而会把用户上次未读完的对话在冷启动时抹掉。
      if (wasAuthed === true && !authed) {
        chat.clearAll()

        /*
         * 清空 TanStack Query 缓存。
         *
         * 与对话历史是同一类问题，只是载体不同：Query 的 gcTime 是 5 分钟，
         * 期间工单列表、告警、审批队列、审计日志都原样留在内存里。
         * 下一个用户在同一标签页登录后，若命中相同 queryKey，
         * **会先看到上一个人的数据**（stale-while-revalidate 的默认行为：
         * 先渲染缓存再后台刷新）。
         *
         * 对只读用户尤其严重——他本无权看到的工单标题、审批摘要、
         * 审计里的 AI 问答，会在刷新完成前的那一瞬间完整呈现。
         *
         * 用 clear() 而非 invalidateQueries()：后者只标记过期、数据仍在缓存中，
         * 挡不住「先渲染旧数据」这一步。这里要的是**移除**，不是「下次重拉」。
         */
        queryClient.clear()
      }
    }
  )
}
