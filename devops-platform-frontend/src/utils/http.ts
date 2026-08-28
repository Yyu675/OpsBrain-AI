import { getBizError, isAutoRetryable } from '../constants/bizCode'

// ==================== Sa-Token 鉴权 token 管理（方向三）====================
// token 存 localStorage，每个请求由 httpRequest 自动附带 satoken 头。
// 读写含 try-catch：隐私模式/禁用 localStorage 时降级为内存不崩（同 persist.ts 契约）。

const AUTH_TOKEN_KEY = 'opsbrain-token'
let inMemoryToken: string | null = null

export const getAuthToken = (): string | null => {
  if (inMemoryToken) return inMemoryToken
  try {
    return localStorage.getItem(AUTH_TOKEN_KEY)
  } catch {
    return inMemoryToken
  }
}

export const setAuthToken = (token: string): void => {
  inMemoryToken = token
  try {
    localStorage.setItem(AUTH_TOKEN_KEY, token)
  } catch {
    /* 隐私模式：仅内存持有 */
  }
}

export const clearAuthToken = (): void => {
  inMemoryToken = null
  try {
    localStorage.removeItem(AUTH_TOKEN_KEY)
  } catch {
    /* 忽略 */
  }
}

/**
 * 处理 401（未登录/登录失效）：清 token 并跳登录页。
 * <p>用自定义事件解耦——http 层不直接依赖 router，由 App 监听后用 router 跳转，
 * 避免硬 location 跳转丢失 SPA 状态。带 from 供登录后回跳。</p>
 */
const handleUnauthorized = (): void => {
  clearAuthToken()
  try {
    // 避免在登录页自身重复派发
    if (!window.location.pathname.includes('/login')) {
      window.dispatchEvent(new CustomEvent('auth:unauthorized', {
        detail: { from: window.location.pathname + window.location.search }
      }))
    }
  } catch {
    /* SSR/无 window：忽略 */
  }
}

export interface HttpRequestOptions extends Omit<RequestInit, 'signal'> {
  timeout?: number
  retries?: number
  retryDelay?: number
  retryOn?: number[]
  signal?: AbortSignal
}

export class HttpError extends Error {
  status: number
  code: string
  /** 后端业务码（如 40004 / 40009 / 40021），HTTP 层失败时为 undefined */
  bizCode?: number
  data: unknown
  /** 请求的 URL 路径（不含 BASE_URL 前缀的短路径，便于展示） */
  url?: string

  constructor(message: string, status: number, code: string, data?: unknown, bizCode?: number, url?: string) {
    super(message)
    this.name = 'HttpError'
    this.status = status
    this.code = code
    this.bizCode = bizCode
    this.data = data
    this.url = url
  }
}

/**
 * 将原始 HttpError 转换为面向用户的友好提示。
 * 返回 { title, detail, hint } 三段，调用方可直接用于 Toast 或错误面板。
 */
export interface FriendlyError {
  title: string
  detail: string
  hint?: string
}

const shortenUrl = (url?: string): string => {
  if (!url) return ''
  try {
    const u = new URL(url, window.location.origin)
    return u.pathname + (u.search ? u.search.slice(0, 30) : '')
  } catch {
    return url.slice(-60)
  }
}

/**
 * 根据 HttpError 的 code / status / bizCode 生成友好提示。
 * 核心原则：告诉用户「出了什么问题」「可能的原因」「下一步该做什么」。
 */
export function toFriendlyError(e: unknown): FriendlyError {
  if (!(e instanceof HttpError)) {
    // Error 的 message 可能为空串，此时 String(e || ...) 的短路判断也救不了
    // （空 message 的 Error 对象本身是 truthy）——须显式兜底，否则 UI 提示框空白
    const raw = e instanceof Error ? e.message : String(e ?? '')
    return {
      title: '发生意外错误',
      detail: raw || '未知错误',
      hint: '请刷新页面重试，如问题持续请联系管理员'
    }
  }

  const path = shortenUrl(e.url)

  // ── 优先查业务码词表 ────────────────────────────────────────────
  // BIZ_ERRORS 与后端 BizError 枚举一一对应（有契约测试守着），
  // 且带 hint 与 retry 语义。此前这张表虽然存在、虽然测试绿，
  // 却**没有任何生产代码在查它**——下面的 switch 各自硬编码了
  // 40001/40004/40009/40021 四个码，其余 18 个（含 50020 监控数据源不可用、
  // 40103 权限不足、42901 限流）统统落到通用兜底文案。
  //
  // 最典型的后果：Prometheus 没起时后端返回 50020 + HTTP 503，
  // 用户看到的却是「服务暂时不可用，可能正在重启或过载，请稍后重试」——
  // 一句把他引向「等一等再刷新」的话，而这个错误的 retry 语义是 NEVER，
  // 正确的下一步是去「接入管理」检查数据源连接。
  //
  // 放在 switch 之前：词表是前后端共同维护的单一真相，
  // 它有的就以它为准；它没有的才退回按传输层特征分类。
  const meta = getBizError(e.bizCode)
  if (meta) {
    return {
      title: meta.title,
      // 后端消息通常比词表标题更具体（含具体字段名、状态名），优先用它做详情。
      // 后端没给 message 时带上码值而不是复述标题——
      // 标题已经显示在上方，详情再说一遍等于没有信息；
      // 而码值能让用户在反馈问题时说清是哪个错误，也便于对照后端日志
      detail: e.message || `${meta.title}（错误码 ${e.bizCode}）`,
      hint: meta.hint
    }
  }

  switch (e.code) {
    case 'TIMEOUT':
      return {
        title: '请求超时',
        // 用抛出方带来的实际时长，不写死 15 秒——各调用方可传自定义 timeout
        // （如导出 CSV 用 60s），写死会让提示与真实等待时间对不上
        detail: `${e.message || '服务器未在预期时间内响应'}${path ? `（${path}）` : ''}`,
        hint: '可能是后端服务未启动、数据库连接中断或网络不通。请检查后端服务状态和 Docker 容器是否正常运行'
      }

    case 'NETWORK':
      return {
        title: '无法连接服务器',
        detail: `网络请求失败${path ? `（${path}）` : ''}`,
        hint: '请确认后端服务已启动（默认端口 8088），检查浏览器控制台查看详细错误'
      }

    case 'HTTP_STATUS':
      if (e.status === 404) {
        return {
          title: '资源不存在',
          detail: `请求的资源未找到（404）${path ? `：${path}` : ''}`,
          hint: '该数据可能已被删除，请刷新列表查看最新数据'
        }
      }
      if (e.status === 403) {
        return {
          title: '无访问权限',
          detail: '当前用户没有权限执行此操作',
          hint: '如需访问，请联系管理员授予相应权限'
        }
      }
      if (e.status === 500) {
        const serverMsg = (e.data as { message?: string })?.message
        return {
          title: '服务器内部错误',
          detail: serverMsg || `后端处理请求时发生异常${path ? `（${path}）` : ''}`,
          hint: '可能是数据库连接失败或后端代码异常，请查看后端日志排查'
        }
      }
      if (e.status === 502 || e.status === 503 || e.status === 504) {
        return {
          title: '服务暂时不可用',
          detail: `服务器返回 ${e.status}，服务可能正在重启或过载`,
          hint: '请稍后重试，如持续不可用请检查后端服务状态'
        }
      }
      return {
        title: `请求失败（HTTP ${e.status}）`,
        // 后端可能返回空 body（无 message），空串会让提示框空白
        detail: e.message || `服务器返回 HTTP ${e.status}${path ? `（${path}）` : ''}`,
        hint: '请稍后重试'
      }

    case 'BIZ':
      // 这里只剩「词表查不到」的兜底。
      //
      // 上方 getBizError() 已优先查表并 return，凡是词表里有的码都到不了这儿。
      // 此前这个 case 里还硬编码了 40001/40400/40009/40021 四个分支，
      // 而这四个码词表全都有 —— 也就是说它们是**四段永远执行不到的死代码**，
      // 却让人误以为「这几个码要在两个地方维护」。
      // 实际风险已经发生过：40004 在词表里是「当前状态不允许该操作」，
      // 在这里却写成「数据不存在」，同一个码两套互相矛盾的文案。
      //
      // 删除时把它们措辞更好的部分回填进了词表（见 bizCode.ts 的 40400）。
      return {
        title: '操作失败',
        detail: e.message || `业务错误（码 ${e.bizCode}）`,
        hint: '请根据提示信息调整后重试'
      }

    case 'BIZ_SHAPE':
      return {
        title: '响应格式异常',
        detail: '服务器返回的数据结构不符合预期',
        hint: '可能是后端接口版本不匹配，请检查前后端版本是否一致'
      }

    default:
      return {
        title: '请求失败',
        detail: e.message || `未识别的错误类型（${e.code}）${path ? `：${path}` : ''}`,
        hint: '请稍后重试'
      }
  }
}

/** 与后端统一包装对齐：code===0 才算成功 */
export interface BizEnvelope<T = unknown> {
  code: number
  message: string
  data: T
  traceId?: string
  timestamp?: number
}

/**
 * 解包非流式接口的统一响应。
 * HTTP 200 但 code!==0 视为业务失败，避免调用方把错误信封当数据用。
 */
export function unwrapBiz<T>(payload: unknown, fallbackMessage: string): T {
  if (!payload || typeof payload !== 'object' || !('code' in payload)) {
    throw new HttpError(fallbackMessage, 200, 'BIZ_SHAPE', payload)
  }
  const env = payload as BizEnvelope<T>
  if (env.code !== 0) {
    throw new HttpError(env.message || fallbackMessage, 200, 'BIZ', env.data, env.code)
  }
  return env.data
}

import { TIMEOUT } from '../config/api'

const DEFAULT_TIMEOUT = TIMEOUT.DEFAULT
const DEFAULT_RETRIES = 2
const DEFAULT_RETRY_DELAY = 400
const RETRYABLE_STATUS = [408, 429, 500, 502, 503, 504]

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/**
 * 超时中止的专用理由对象。
 *
 * 为什么不能用 `new Error('timeout')`：`AbortController.abort(reason)` 会让
 * fetch **以该 reason 原样 reject**。普通 Error 的 `name` 是 `'Error'`，
 * 于是 {@link isAbortError} 判不出来，超时最终被归类成 `NETWORK`——
 * 用户看到的是「无法连接服务器，请确认后端服务已启动」，
 * 而真相是服务连上了、只是 15 秒没返回。这两种故障的排查方向完全相反
 * （一个查进程是否存活，一个查慢查询/线程池），提示给错会把人带偏。
 *
 * 用 `TimeoutError` 这个名字与 `AbortSignal.timeout()` 的标准行为对齐。
 */
const makeTimeoutReason = (): unknown => {
  if (typeof DOMException !== 'undefined') {
    return new DOMException('请求超时', 'TimeoutError')
  }
  // 非浏览器环境（部分测试运行器）兜底：手工构造同名错误
  const e = new Error('请求超时')
  e.name = 'TimeoutError'
  return e
}

const isAbortError = (e: unknown): boolean => {
  if (!e || typeof e !== 'object') return false
  const name = (e as { name?: string }).name
  return name === 'AbortError' || name === 'TimeoutError'
}

/** 是否为**超时**中止（区别于调用方主动 abort） */
const isTimeoutError = (e: unknown): boolean =>
  !!e && typeof e === 'object' && (e as { name?: string }).name === 'TimeoutError'

const linkSignals = (
  external: AbortSignal | undefined,
  internal: AbortController
): (() => void) => {
  if (!external) return () => { /* noop */ }
  if (external.aborted) {
    internal.abort(external.reason)
    return () => { /* noop */ }
  }
  const onAbort = () => internal.abort(external.reason)
  external.addEventListener('abort', onAbort, { once: true })
  return () => external.removeEventListener('abort', onAbort)
}

export const httpRequest = async <T = unknown>(
  url: string,
  options: HttpRequestOptions = {}
): Promise<T> => {
  const {
    timeout = DEFAULT_TIMEOUT,
    retries,
    retryDelay = DEFAULT_RETRY_DELAY,
    retryOn = RETRYABLE_STATUS,
    signal: externalSignal,
    headers,
    ...rest
  } = options

  // 写操作默认不重试：POST/PUT/PATCH/DELETE 超时重试会建重复工单
  const method = (rest.method || 'GET').toUpperCase()
  const isWriteMethod = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)
  const effectiveRetries = retries ?? (isWriteMethod ? 0 : DEFAULT_RETRIES)

  const mergedHeaders: Record<string, string> = {
    Accept: 'application/json',
    ...(rest.body && !(rest.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
    // Sa-Token 鉴权（方向三）：自动附带 token 头。头名 satoken 对齐后端 sa-token.token-name。
    ...(getAuthToken() ? { satoken: getAuthToken() as string } : {}),
    ...(headers as Record<string, string> | undefined)
  }

  let lastErr: unknown = null

  for (let attempt = 0; attempt <= effectiveRetries; attempt++) {
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(makeTimeoutReason()), timeout)
    const unlink = linkSignals(externalSignal, controller)

    try {
      const res = await fetch(url, {
        ...rest,
        headers: mergedHeaders,
        signal: controller.signal
      })

      if (!res.ok) {
        // 先读 body 再决定是否重试。
        //
        // 此前顺序是反的：只看 HTTP 状态就重试，body 还没读。
        // 后果是**业务码携带的 retry 语义完全失效**——后端在
        // BizError 里为每个码标了 NEVER / SAFE / BACKOFF / CLIENT，
        // 但前端在拿到这个码之前就已经重试完了。
        //
        // 最典型的是 50020（监控数据源不可用，retry=NEVER，HTTP 503）：
        // 503 在 RETRYABLE_STATUS 白名单里，于是 Prometheus 没起时
        // 每次打开监控页都会静默发 3 次请求、退避等待约 3 秒，
        // 最后仍然失败。用户白等，后端白扛——而这个错误重试一万次也不会好。
        let payload: unknown = null
        try { payload = await res.json() } catch { /* body not json */ }
        const bizCode = (payload as { code?: number })?.code

        // 有业务码时以它的 retry 语义为准（词表与后端枚举一一对应）；
        // 没有业务码（如网关返回的裸 502）才退回按 HTTP 状态判断
        const retryable = bizCode !== undefined
          ? isAutoRetryable(bizCode)
          : retryOn.includes(res.status)

        if (retryable && attempt < effectiveRetries) {
          lastErr = new HttpError(`HTTP ${res.status}`, res.status, 'HTTP_STATUS',
            payload, bizCode, url)
          await sleep(retryDelay * Math.pow(2, attempt))
          continue
        }
        // 401：未登录或登录失效，清 token 并通知 App 跳登录页。
        // ⚠️ 临时开发开关（2026-08-26）：UI 预览默认不跳登录（VITE_ENABLE_AUTH_REDIRECT=1 可恢复）。
        if (res.status === 401 && import.meta.env.VITE_ENABLE_AUTH_REDIRECT === '1') {
          handleUnauthorized()
        }
        throw new HttpError(
          (payload as { message?: string })?.message || `HTTP ${res.status}`,
          res.status,
          'HTTP_STATUS',
          payload,
          (payload as { code?: number })?.code,
          url
        )
      }

      if (res.status === 204) return undefined as T
      const contentType = res.headers.get('content-type') || ''
      if (contentType.includes('application/json')) {
        return (await res.json()) as T
      }
      return (await res.text()) as unknown as T
    } catch (e) {
      lastErr = e
      if (externalSignal?.aborted) throw e
      if (isAbortError(e)) {
        // 只有**超时**才值得重试。非超时的 AbortError 说明有人主动取消了
        // （组件卸载、用户点「停止」），重试等于无视这个取消意图，
        // 还会在页面已经销毁后继续打后端。
        if (isTimeoutError(e) && attempt < effectiveRetries) {
          await sleep(retryDelay * Math.pow(2, attempt))
          continue
        }
        if (!isTimeoutError(e)) throw e
        throw new HttpError(
          `请求超时（${Math.round(timeout / 1000)} 秒未响应）`,
          0, 'TIMEOUT', undefined, undefined, url
        )
      }
      // 这里是**第二条**重试路径：上面 !res.ok 分支抛出的 HttpError 会落到这个
      // catch 里再判一次。此前它只看 retryOn.includes(e.status)，
      // 于是上面刚按业务码判定「不该重试」而抛出的错误，
      // 又会因为状态码在白名单里被重试一遍——两条路径的判据不一致，
      // 修了上面一处并不生效（50020 实测仍发 3 次请求）。
      //
      // 统一判据：有业务码就以词表的 retry 语义为准，没有才看 HTTP 状态。
      if (e instanceof HttpError) {
        const retryable = e.bizCode !== undefined
          ? isAutoRetryable(e.bizCode)
          : retryOn.includes(e.status)
        if (!retryable) throw e
      }
      if (attempt < effectiveRetries) {
        await sleep(retryDelay * Math.pow(2, attempt))
        continue
      }
      if (e instanceof HttpError) throw e
      throw new HttpError(
        e instanceof Error ? e.message : '网络请求失败',
        0,
        'NETWORK',
        undefined,
        undefined,
        url
      )
    } finally {
      clearTimeout(timeoutId)
      unlink()
    }
  }

  throw lastErr instanceof Error ? lastErr : new HttpError('请求失败', 0, 'UNKNOWN')
}

export const http = {
  get: <T = unknown>(url: string, options?: HttpRequestOptions) =>
    httpRequest<T>(url, { ...options, method: 'GET' }),
  post: <T = unknown>(url: string, body?: unknown, options?: HttpRequestOptions) =>
    httpRequest<T>(url, {
      ...options,
      method: 'POST',
      body: body instanceof FormData ? body : JSON.stringify(body ?? {})
    }),
  put: <T = unknown>(url: string, body?: unknown, options?: HttpRequestOptions) =>
    httpRequest<T>(url, {
      ...options,
      method: 'PUT',
      body: body instanceof FormData ? body : JSON.stringify(body ?? {})
    }),
  patch: <T = unknown>(url: string, body?: unknown, options?: HttpRequestOptions) =>
    httpRequest<T>(url, {
      ...options,
      method: 'PATCH',
      body: body instanceof FormData ? body : JSON.stringify(body ?? {})
    }),
  del: <T = unknown>(url: string, options?: HttpRequestOptions) =>
    httpRequest<T>(url, { ...options, method: 'DELETE' })
}
