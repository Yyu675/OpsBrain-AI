/**
 * 运行环境单一来源（2026-10-06 人话化批）。
 *
 * prod / staging / dev 此前在自动化策略、动作白名单、自愈中心四处裸渲染，
 * 统一走 envLabel；码值仍是 API 参数与 option value，只换展示。
 * 未知值原样透出不吞（环境枚举可能随现场扩展）。
 */
const ENV_LABELS: Record<string, string> = {
  prod: '生产',
  staging: '预发',
  dev: '开发'
}

/** 取环境中文标签：未知码原样透出 */
export const envLabel = (env: string | null | undefined): string => {
  if (!env) return '—'
  return ENV_LABELS[env] ?? env
}

/** 逗号串环境字段（如动作的 environments 列）→ 中文顿号连接 */
export const envListLabel = (envs: string | null | undefined): string => {
  if (!envs) return '—'
  const list = envs.split(',').map(s => s.trim()).filter(Boolean)
  return list.length ? list.map(envLabel).join('、') : '—'
}
