/**
 * 站内跳转路径校验（防开放重定向）。
 *
 * ── 原实现为什么不够 ──────────────────────────────────────────
 * 登录页原本的判断是：
 *
 *     target.startsWith('/') && !target.startsWith('//')
 *
 * 它挡住了 `//evil.com`，但挡不住这些（已实测浏览器解析结果）：
 *
 *   | 输入              | 通过原校验 | 浏览器解析为        |
 *   | `/\evil.com`      | ✅ 是      | `https://evil.com/` |
 *   | `/\t/evil.com`    | ✅ 是      | `https://evil.com/` |
 *   | `/\\evil.com`     | ✅ 是      | `https://evil.com/` |
 *
 * 反斜杠在 URL 解析中被当作正斜杠处理（WHATWG URL 规范），
 * 而 `\t` `\n` `\r` 会被**直接剥离**后再解析。两者都能构造出
 * 「看起来是相对路径、实际是协议相对 URL」的字符串。
 *
 * ── 可利用性说明（不夸大）────────────────────────────────────
 * vue-router 走 `history.pushState`，浏览器强制同源，单靠它跳不出去。
 * 真正的出口是 `router.onError` 里的 `window.location.assign(to.fullPath)`
 * ——它是真实的浏览器导航。触发链是「攻击者构造 redirect 参数 → 用户登录
 * → 恰好该路由的 chunk 加载失败 → onError 兜底 assign」。
 *
 * 链条不算短，但：① 校验本身确实是错的；② 兜底 assign 是为了容错，
 * 不该反过来成为逃逸出口。属于该修的纵深防御，而非"理论风险"。
 *
 * ── 判定策略 ──────────────────────────────────────────────────
 * 不做黑名单（列举 `\` `\t` 永远列不全），改为**用 URL 解析器判同源**：
 * 以任意占位 origin 解析，若解析后的 origin 变了，就是跨站。
 * 这与浏览器实际行为一致，不依赖我们对规范的记忆。
 */

/** 解析时用的占位 origin。只要固定即可，不参与实际跳转 */
const PROBE_ORIGIN = 'https://opsbrain.invalid'

/**
 * 校验并归一化站内跳转路径。
 *
 * @param raw     来源可疑的路径（URL query / 事件 detail 等）
 * @param fallback 校验不通过时的回落路径，默认首页
 * @returns 可安全用于 router.push / location.assign 的**站内**路径
 */
export function safeInternalPath(raw: unknown, fallback = '/'): string {
  const target = Array.isArray(raw) ? raw[0] : raw
  if (typeof target !== 'string' || target === '') return fallback

  // 快速否定：必须以单个 / 开头（排除 http://、//host、相对路径）
  if (!target.startsWith('/')) return fallback

  let parsed: URL
  try {
    parsed = new URL(target, PROBE_ORIGIN)
  } catch {
    return fallback
  }

  // 核心判据：解析后 origin 必须仍是占位 origin。
  // `/\evil.com` 与 `/\t/evil.com` 会在这里被解析成 https://evil.com 而落网。
  if (parsed.origin !== PROBE_ORIGIN) return fallback

  // 回传解析后的 pathname+search+hash 而非原字符串——
  // 原字符串里的控制字符（\t \n \r）若原样交给 location.assign，
  // 浏览器仍会自行剥离后重新解析，等于绕过了本次校验。
  const normalized = `${parsed.pathname}${parsed.search}${parsed.hash}`

  // 归一化后仍须是站内路径（极端输入下 pathname 可能为空）
  return normalized.startsWith('/') ? normalized : fallback
}
