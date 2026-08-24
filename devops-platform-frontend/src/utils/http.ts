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

  switch (e.code) {
    case 'TIMEOUT':
      return {
        title: '请求超时',
        detail: `服务器在 15 秒内未响应${path ? `（${path}）` : ''}`,
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
      if (e.bizCode === 40001) {
        return {
          title: '参数校验失败',
          detail: e.message || '提交的数据不符合要求',
          hint: '请检查输入内容后重试'
        }
      }
      if (e.bizCode === 40004) {
        return {
          title: '数据不存在',
          detail: e.message || '请求的资源不存在或已被删除',
          hint: '请刷新列表获取最新数据'
        }
      }
      if (e.bizCode === 40009) {
        return {
          title: '数据已被修改',
          detail: e.message || '该记录已被他人修改，你的编辑基于旧版本',
          hint: '请刷新页面获取最新数据后重新编辑'
        }
      }
      if (e.bizCode === 40021) {
        return {
          title: '内容重复',
          detail: e.message || '提交的内容与已有数据重复',
          hint: '请修改内容后重试'
        }
      }
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

const isAbortError = (e: unknown): boolean => {
  if (!e || typeof e !== 'object') return false
  const name = (e as { name?: string }).name
  return name === 'AbortError' || name === 'TimeoutError'
}

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
    const timeoutId = setTimeout(() => controller.abort(new Error('timeout')), timeout)
    const unlink = linkSignals(externalSignal, controller)

    try {
      const res = await fetch(url, {
        ...rest,
        headers: mergedHeaders,
        signal: controller.signal
      })

      if (!res.ok) {
        if (retryOn.includes(res.status) && attempt < effectiveRetries) {
          lastErr = new HttpError(`HTTP ${res.status}`, res.status, 'HTTP_STATUS')
          await sleep(retryDelay * Math.pow(2, attempt))
          continue
        }
        let payload: unknown = null
        try { payload = await res.json() } catch { /* body not json */ }
        // 401：未登录或登录失效，清 token 并通知 App 跳登录页
        if (res.status === 401) {
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
        if (attempt < effectiveRetries) {
          await sleep(retryDelay * Math.pow(2, attempt))
          continue
        }
        throw new HttpError('请求超时，请检查网络', 0, 'TIMEOUT', undefined, undefined, url)
      }
      if (e instanceof HttpError && !retryOn.includes(e.status)) throw e
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
