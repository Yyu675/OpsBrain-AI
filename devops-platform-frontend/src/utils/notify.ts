import { ElMessage, type MessageOptions } from 'element-plus'

import { isAbortLike } from './errors'
import { toFriendlyError, type FriendlyError } from './http'

type NotifyType = 'success' | 'warning' | 'info' | 'error'

interface NotifyOptions extends Omit<MessageOptions, 'type' | 'message'> {
  key?: string
  cooldown?: number
}

const DEFAULT_COOLDOWN = 1000
const lastFired = new Map<string, number>()

const shouldEmit = (key: string, cooldown: number): boolean => {
  const now = Date.now()
  const prev = lastFired.get(key)
  if (prev && now - prev < cooldown) return false
  lastFired.set(key, now)
  return true
}

const emit = (
  type: NotifyType,
  message: string,
  options: NotifyOptions = {}
): void => {
  const { key = `${type}:${message}`, cooldown = DEFAULT_COOLDOWN, ...rest } = options
  if (!shouldEmit(key, cooldown)) return
  try {
    ElMessage({
      type,
      message,
      grouping: true,
      duration: type === 'error' ? 4000 : 2500,
      ...rest
    })
  } catch (e) {
    console.warn('[notify] ElMessage unavailable:', e)
  }
}

export const notify = {
  success: (message: string, options?: NotifyOptions) => emit('success', message, options),
  warning: (message: string, options?: NotifyOptions) => emit('warning', message, options),
  info: (message: string, options?: NotifyOptions) => emit('info', message, options),
  error: (message: string, options?: NotifyOptions) => emit('error', message, options),
  clearCooldown: (key?: string) => {
    if (key) lastFired.delete(key)
    else lastFired.clear()
  }
}

// ==================== 服务端错误统一处理 ====================

export interface HandleServerErrorOptions {
  /**
   * 操作名，用于拼出「{动作}失败」的标题，如 '发布文档'。
   * 不传时使用 toFriendlyError 给出的通用标题。
   */
  action?: string
  /** 去重键，默认按最终文案去重 */
  key?: string
  /** 去重冷却毫秒数 */
  cooldown?: number
  /**
   * 是否连带展示处置建议（hint）。
   * 默认 true —— 用户需要知道「下一步做什么」，而非只看到「失败了」。
   */
  withHint?: boolean
}

/**
 * 服务端错误的统一处理入口。
 *
 * 建立动因：全项目此前有三种并存写法，其中两种是缺陷——
 *   ① `ElMessage.error(toFriendlyError(e).detail)`  正确但重复
 *   ② `ElMessage.error((e as Error).message)`        **绕过业务码映射**
 *   ③ `ElMessage.error(\`发布失败：\${(e as Error).message}\`)`  同上
 *
 * ②③ 的问题是把后端原始文案直接摆给用户：40009 会显示
 * 「该记录已被他人修改（你基于第 0 版编辑…）」却不说「请刷新后重新提交」，
 * 40021 会显示「内容重复」却不说「请修改内容后重试」——
 * 用户看到的是故障描述，不是可执行的下一步（同 6.11 的措辞决策）。
 *
 * 本函数保证三件事：
 * - 一律经 toFriendlyError 做业务码映射，不透传原始 message
 * - 主动取消（AbortError / ElMessageBox 取消）不当作错误弹提示
 * - 走 notify 的防抖去重，批量操作失败时不刷屏
 *
 * @returns 映射后的 FriendlyError，供调用方需要时进一步使用（如写入页面级错误态）
 */
export function handleServerError(
  error: unknown,
  options: HandleServerErrorOptions = {}
): FriendlyError {
  const friendly = toFriendlyError(error)

  // 主动取消不是故障：用户关弹窗、切页面、点「停止生成」都会走到这里，
  // 弹一句「操作失败」反而让用户以为自己的取消动作出了问题
  if (isAbortLike(error)) return friendly

  const title = options.action ? `${options.action}失败` : friendly.title
  const withHint = options.withHint ?? true
  const message = withHint && friendly.hint
    ? `${title}：${friendly.detail}（${friendly.hint}）`
    : `${title}：${friendly.detail}`

  notify.error(message, {
    key: options.key ?? `server-error:${title}:${friendly.detail}`,
    cooldown: options.cooldown
  })

  return friendly
}
