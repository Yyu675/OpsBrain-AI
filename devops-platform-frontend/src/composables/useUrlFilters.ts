import { watch, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 把筛选状态双向同步到 URL query。
 *
 * ── 要解决什么 ────────────────────────────────────────────────
 * 运维场景的核心动作之一是「把这个筛选结果甩给同事看」：
 * 「P0 + 未分配 + 近 24h 的工单」应该是一个可以直接粘贴到群里的链接。
 *
 * 现状是三种实现并存、且都不完整：
 *   - TicketList  —— 只**读** URL（供 Dashboard 跳转带参），
 *                    用户在页面上改了筛选却不写回，刷新即丢失、也无法分享
 *   - KnowledgeBase —— 自己写了一套读写
 *   - AlertList   —— 完全没有
 *
 * 三处各写各的，行为不一致，用户无法形成稳定预期。本 composable 提供
 * 统一实现：读时校验、写时清理、默认值不入 URL。
 *
 * ── 几个刻意的设计 ────────────────────────────────────────────
 * 1. **等于默认值的项不写进 URL**。否则地址栏会变成
 *    `?status=all&priority=all&assignee=all&page=1` 这种噪音，
 *    既难读，也让「有没有筛选」这件事无法一眼看出。
 *
 * 2. **用 replace 而非 push**。筛选是同一个页面的状态变化，不是导航。
 *    用 push 会让每改一次筛选就压一条历史记录，用户点返回时
 *    要按十几次才能退出去。
 *
 * 3. **非法值静默忽略而非报错**。URL 可被手工编辑或来自过期链接，
 *    遇到不认识的值退回默认即可，弹错误提示只会打扰用户。
 */

/** 单个筛选项的定义 */
export interface UrlFilterSpec<T> {
  /** 绑定的响应式状态 */
  ref: Ref<T>
  /** URL 中的参数名 */
  key: string
  /** 默认值。等于它时不写入 URL */
  defaultValue: T
  /**
   * 从 URL 字符串解析回值。
   * 返回 undefined 表示「非法/不认识」，此时保持默认值不变。
   */
  parse: (raw: string) => T | undefined
  /** 序列化为 URL 字符串。返回 undefined 表示不写入 */
  serialize?: (value: T) => string | undefined
}

/**
 * 类型擦除后的筛选项。
 *
 * 直接用 {@code UrlFilterSpec<any>} 会让 parse 的返回值失去检查——
 * 写错解析器（比如给 number 字段配了返回 string 的 parse）也不报错。
 * 这里把「每项内部自洽」与「数组可异构」两个需求拆开：
 * 构造时用泛型的 {@link defineUrlFilter} 保证前者，
 * 存进数组时擦除为本类型以允许后者。
 */
export interface AnyUrlFilterSpec {
  ref: Ref<unknown>
  key: string
  defaultValue: unknown
  parse: (raw: string) => unknown
  serialize?: (value: never) => string | undefined
}

/**
 * 构造筛选项（保留类型检查）。
 * <p>用它而非直接写对象字面量，可让 TS 校验 parse 的返回类型
 * 确实与 ref 的类型一致。</p>
 */
export function defineUrlFilter<T>(spec: UrlFilterSpec<T>): AnyUrlFilterSpec {
  return spec as unknown as AnyUrlFilterSpec
}

/** 常用的字符串枚举解析器：只接受白名单内的值 */
export function enumParser<T extends string>(allowed: readonly T[]) {
  return (raw: string): T | undefined =>
    (allowed as readonly string[]).includes(raw) ? (raw as T) : undefined
}

/** 数字解析器：只接受正整数（页码、每页条数） */
export function positiveIntParser(max = 100000) {
  return (raw: string): number | undefined => {
    const n = Number(raw)
    return Number.isInteger(n) && n > 0 && n <= max ? n : undefined
  }
}

/** 自由文本解析器：去空白，超长截断（URL 可被构造，需设上限） */
export function textParser(maxLength = 200) {
  return (raw: string): string | undefined => {
    const t = raw.trim()
    return t ? t.slice(0, maxLength) : undefined
  }
}

/**
 * 建立筛选状态与 URL 的双向同步。
 *
 * @param specs 筛选项定义
 * @returns `applyFromUrl` 供需要手动重放的场景调用（如路由复用时）
 */
/**
 * 建立筛选状态与 URL 的双向同步。
 *
 * 参数类型是 {@code AnyUrlFilterSpec[]} 而非 {@code UrlFilterSpec<T>[]}：
 * 各筛选项的值类型互不相同（枚举 / 数字 / 文本），泛型无法统一。
 * {@link AnyUrlFilterSpec} 用「存在类型」的写法保住每项<b>内部</b>的
 * 类型自洽（parse 的返回类型必须与 ref 匹配），同时允许数组异构。
 *
 * @param specs 筛选项定义
 * @returns applyFromUrl 供需要手动重放的场景调用（如路由复用时）
 */
export function useUrlFilters(specs: readonly AnyUrlFilterSpec[]) {
  const route = useRoute()
  const router = useRouter()

  /** URL → 状态。仅在挂载与显式调用时执行 */
  const applyFromUrl = (): void => {
    for (const spec of specs) {
      const raw = route.query[spec.key]
      if (typeof raw !== 'string') continue
      const parsed = spec.parse(raw)
      if (parsed !== undefined) {
        spec.ref.value = parsed
      }
    }
  }

  /** 状态 → URL。默认值不写入，保持地址栏干净 */
  const writeToUrl = (): void => {
    const next: Record<string, string> = {}

    // 保留非本 composable 管理的参数（如 tab、view 等其它页面状态）
    const managed = new Set(specs.map((s) => s.key))
    for (const [k, v] of Object.entries(route.query)) {
      if (!managed.has(k) && typeof v === 'string') {
        next[k] = v
      }
    }

    for (const spec of specs) {
      const value = spec.ref.value
      if (value === spec.defaultValue) continue
      const s = spec.serialize
        ? (spec.serialize as (v: unknown) => string | undefined)(value)
        : String(value)
      if (s !== undefined && s !== '') {
        next[spec.key] = s
      }
    }

    // 内容未变则不触发导航——否则 watch 链会自激
    const cur = route.query
    const sameSize = Object.keys(cur).length === Object.keys(next).length
    const sameContent = sameSize && Object.entries(next).every(([k, v]) => cur[k] === v)
    if (sameContent) return

    // replace 而非 push：筛选是状态变化不是导航，见文件头说明
    void router.replace({ query: next })
  }

  applyFromUrl()

  // 任一筛选项变化即写回。flush:'post' 让同一 tick 内的多项变更合并成一次导航
  watch(
    specs.map((s) => s.ref),
    () => writeToUrl(),
    { flush: 'post' }
  )

  return { applyFromUrl, writeToUrl }
}
