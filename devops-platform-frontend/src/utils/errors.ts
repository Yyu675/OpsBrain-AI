/**
 * unknown 错误的安全取值工具。
 *
 * TypeScript 的 catch 变量是 unknown（严格模式下），直接 `error.message` 无法通过类型检查，
 * 项目此前在多处用 `catch (e: any)` 绕过——那会连带丢掉整条链路的类型保护（6.45 契约：
 * as any 是技术债非解决方案）。这里集中提供窄化后的取值方式。
 *
 * 注意：这些函数只负责「安全读出信息」，不负责「决定给用户看什么」。
 * 面向用户的文案映射走 utils/http.ts 的 toFriendlyError。
 */

/** 取错误消息；非 Error 对象降级为字符串化，空值给出兜底文案。 */
export function errorMessage(error: unknown, fallback = '网络错误'): string {
  if (error instanceof Error) return error.message || fallback
  if (typeof error === 'string') return error || fallback
  if (error && typeof error === 'object') {
    const msg = (error as { message?: unknown }).message
    if (typeof msg === 'string' && msg) return msg
  }
  return fallback
}

/**
 * 判断错误是否为「主动中止」。
 *
 * 覆盖三种来源：
 * - fetch/AbortController 抛出的 DOMException(name='AbortError')
 * - 超时中止（name='TimeoutError'）
 * - Element Plus 的 ElMessageBox 取消（抛字符串 'cancel' 或 { action: 'cancel' }）
 *
 * 中止不是故障，不该被当作错误上报给用户——这是项目多处 catch 里区分
 * 「已停止生成」与「连接失败」的判据。
 */
export function isAbortLike(error: unknown): boolean {
  if (error === 'cancel' || error === 'close') return true
  if (!error || typeof error !== 'object') return false
  const name = (error as { name?: unknown }).name
  if (name === 'AbortError' || name === 'TimeoutError') return true
  const action = (error as { action?: unknown }).action
  return action === 'cancel' || action === 'close'
}

/** 取错误的 name 字段（用于日志分类），无则返回空串。 */
export function errorName(error: unknown): string {
  if (error instanceof Error) return error.name
  if (error && typeof error === 'object') {
    const name = (error as { name?: unknown }).name
    if (typeof name === 'string') return name
  }
  return ''
}
