import { computed, ref } from 'vue'

import { handleServerError } from '@/utils/notify'

/**
 * 异步动作的「进行中」状态 + 防重入 + 统一错误处理。
 *
 * ── 要解决什么 ────────────────────────────────────────────────
 * 项目里同一件事有三种写法并存：
 *
 *   ① 规范的：`if (busy.value) return; busy.value = true; try/catch/finally`
 *      —— TicketDetail 的 doAcknowledge、AlertDetail 的两个 mutation
 *   ② 只有 try/catch，**没有进行中标记** —— doEscalate / raisePriority /
 *      startProcessing / closeTicket 等
 *   ③ 有标记但每个动作各建一个 ref —— TicketDetail 里已经有 15 个 `ref(false)`
 *
 * ② 是真缺陷：这些动作会写活动流。用户在慢接口下双击「升级上报」，
 * 会提交两次升级、活动流里出现两条一模一样的记录，而工单的时间线
 * 是事后复盘与追责的依据，脏数据的代价不是「不好看」。
 *
 * ③ 的代价是每加一个动作就多一个 ref + 多一处 finally，
 * 忘写 finally 就会永久卡在禁用态（比不加防护更糟）。
 *
 * ── 用法 ──────────────────────────────────────────────────────
 * ```ts
 * const escalate = useAsyncAction(
 *   async (reason: string) => { await escalateTicket(id, reason) },
 *   { action: '升级上报', successMessage: '已提交升级' }
 * )
 * // 模板：:disabled="escalate.pending.value" @click="escalate.run(reason)"
 * ```
 *
 * ── 几个刻意的设计 ────────────────────────────────────────────
 * 1. **重入直接返回 undefined 而非排队**。运维操作没有「攒着一起做」的语义，
 *    第二次点击应当被忽略，而不是等第一次完成后再执行一遍。
 *
 * 2. **用户取消不算失败**。ElMessageBox 取消抛 'cancel'，
 *    交给 handleServerError 内部的 isAbortLike 判断，不弹错误提示——
 *    用户主动取消却收到「操作失败」会让人以为自己点错了。
 *
 * 3. **不吞异常但也不外抛**。run 返回 `T | undefined`：成功给结果、
 *    失败给 undefined 并已弹过提示。调用方想区分只需判返回值，
 *    不必再写一层 try/catch——那正是当前样板代码的来源。
 */
export interface UseAsyncActionOptions {
  /** 操作名，用于「{动作}失败」的错误标题，如 '升级上报' */
  action?: string
  /** 成功后的提示文案。不传则不提示（如后续还有跳转、由目标页给反馈） */
  successMessage?: string
  /**
   * 是否自动处理错误（默认 true）。
   * 置 false 时错误会**原样抛出**，供调用方需要自定义处理的场景使用。
   */
  autoHandleError?: boolean
}

export interface UseAsyncActionReturn<A extends unknown[], T> {
  /** 是否进行中。绑到按钮的 :disabled 与 loading 文案 */
  pending: Readonly<import('vue').Ref<boolean>>
  /** 执行。进行中时重复调用直接返回 undefined */
  run: (...args: A) => Promise<T | undefined>
}

export function useAsyncAction<A extends unknown[], T>(
  fn: (...args: A) => Promise<T>,
  options: UseAsyncActionOptions = {}
): UseAsyncActionReturn<A, T> {
  const { action, successMessage, autoHandleError = true } = options

  const pending = ref(false)

  const run = async (...args: A): Promise<T | undefined> => {
    // 防重入：慢接口下的双击会提交两次，写活动流的动作会留下重复记录
    if (pending.value) return undefined
    pending.value = true
    try {
      const result = await fn(...args)
      if (successMessage) {
        // 动态引入以避免 utils/notify 与本文件的循环依赖风险；
        // notify 自带冷却去重，批量场景不会刷屏
        const { notify } = await import('@/utils/notify')
        notify.success(successMessage)
      }
      return result
    } catch (e) {
      if (!autoHandleError) throw e
      // handleServerError 内部会跳过「用户主动取消」，见其实现
      handleServerError(e, { action })
      return undefined
    } finally {
      // 必须在 finally：任何提前 return / 抛错都要解锁，
      // 否则按钮永久禁用，比不加防护更糟
      pending.value = false
    }
  }

  return { pending: computed(() => pending.value), run }
}
