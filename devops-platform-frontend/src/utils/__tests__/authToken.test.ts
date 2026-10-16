/**
 * 鉴权 token 读写（`utils/http.ts` 的 get/set/clearAuthToken）测试。
 *
 * ── 为什么第一刀切在这里 ──────────────────────────────────────
 * 这三个函数是 `scan_export_coverage.py`（本轮新增）报出来的 16 个
 * 「从未被任何测试引用的导出」之一，且是其中**承重最狠的一组**：
 *
 *   - `stores/app.ts` 的登录、会话恢复、登出全靠它们；
 *   - `router/index.ts` 的路由守卫用 `getAuthToken()` 判断
 *     「有 token 但 store 里还没登录态」→ 触发会话恢复；
 *   - `httpRequest` 每个请求都读它来附 `satoken` 头；
 *   - `api/chat.ts` 的 SSE 流式请求也单独读它。
 *
 * 它坏了的表现不是报错，而是**用户莫名其妙被登出**、或者
 * **登出后 token 还在、下一个人打开浏览器直接进到上一个人的账号**。
 *
 * ── 真正要守的不变量：内存与 localStorage 的双写一致 ──────────
 * 实现刻意做了「内存优先 + localStorage 兜底」：
 *
 * ```
 * let inMemoryToken: string | null = null
 * getAuthToken = () => inMemoryToken || localStorage.getItem(KEY)
 * ```
 *
 * 目的是隐私模式/禁用 localStorage 时仍能工作。但这个设计有个
 * **必须成立的前提：clear 时两处都要清干净**。
 * 只清 localStorage 而漏了内存变量的话，`getAuthToken()` 会继续
 * 返回旧 token——登出按钮点了，人还在登录状态。
 *
 * 下面的用例把这条不变量在四个方向上钉死（写→读、清→读、
 * localStorage 抛异常时的降级、以及 storage 被外部改动时的优先级）。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'

import { clearAuthToken, getAuthToken, setAuthToken } from '../http'

const KEY = 'opsbrain-token'

beforeEach(() => {
  localStorage.clear()
  // 模块级的 inMemoryToken 是跨用例共享的——不清会让上一例的 token
  // 泄漏到下一例，产生「明明没 set 却读到值」的假象
  clearAuthToken()
})

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  clearAuthToken()
})

describe('基本读写', () => {
  it('未设置时返回 null，而不是空串', () => {
    // 调用方普遍写 `if (getAuthToken())`，空串与 null 都是假值所以不出错；
    // 但 `satoken: getAuthToken() as string` 那处会把空串当成合法头发出去
    expect(getAuthToken()).toBeNull()
  })

  it('set 之后 get 拿到同一个值', () => {
    setAuthToken('tk-abc-123')
    expect(getAuthToken()).toBe('tk-abc-123')
  })

  it('set 同时写进 localStorage，刷新页面后仍在', () => {
    setAuthToken('tk-persist')

    // 直接查存储，而不是再调一次 getAuthToken——后者会先命中内存，
    // 即使 localStorage 根本没写成功也照样通过
    expect(localStorage.getItem(KEY)).toBe('tk-persist')
  })

  it('重复 set 以最后一次为准', () => {
    setAuthToken('tk-old')
    setAuthToken('tk-new')

    expect(getAuthToken()).toBe('tk-new')
    expect(localStorage.getItem(KEY)).toBe('tk-new')
  })
})

describe('清除：内存与存储必须同时清干净', () => {
  it('clear 之后 get 返回 null', () => {
    setAuthToken('tk-abc')
    clearAuthToken()

    expect(getAuthToken()).toBeNull()
  })

  it('clear 之后 localStorage 里也不留残值', () => {
    // 只清内存不清存储的话：本次会话看着是登出了，
    // 但下次打开页面 getAuthToken 会从 localStorage 把它读回来——
    // 登出等于没登出
    setAuthToken('tk-abc')
    clearAuthToken()

    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('clear 之后即使 localStorage 里被塞回旧值，也不会读到内存残留', () => {
    // 反向验证内存确实被清空了。
    // 如果 clear 只清了 localStorage 而漏了 inMemoryToken，
    // 这里塞进去的 'tk-other' 会被内存里的 'tk-abc' 盖住
    setAuthToken('tk-abc')
    clearAuthToken()
    localStorage.setItem(KEY, 'tk-other')

    expect(getAuthToken()).toBe('tk-other')
  })

  it('未登录时 clear 不报错', () => {
    expect(() => clearAuthToken()).not.toThrow()
    expect(getAuthToken()).toBeNull()
  })
})

describe('localStorage 不可用时降级为内存持有', () => {
  it('setItem 抛异常时不崩，且本次会话内仍能读到 token', () => {
    // 隐私模式 / 磁盘配额满 / 浏览器禁用存储。
    // 这里若崩了，用户是「登录接口成功了但页面报错」——
    // 最难排查的一类现象，因为服务端日志一切正常
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('QuotaExceededError')
    })

    expect(() => setAuthToken('tk-mem-only')).not.toThrow()
    // 存不进去也要能用完这一程——内存里那份是兜底
    expect(getAuthToken()).toBe('tk-mem-only')
  })

  it('getItem 抛异常时回落到内存值，而不是把用户踢出去', () => {
    setAuthToken('tk-mem')
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('SecurityError')
    })

    expect(getAuthToken()).toBe('tk-mem')
  })

  it('getItem 抛异常且内存也没有时，如实返回 null', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('SecurityError')
    })

    expect(getAuthToken()).toBeNull()
  })

  it('removeItem 抛异常时 clear 不崩，内存仍被清空', () => {
    setAuthToken('tk-abc')
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('SecurityError')
    })

    expect(() => clearAuthToken()).not.toThrow()
    // 存储清不掉是环境限制，但内存这份必须清掉——
    // 否则当前会话里点了登出还是登录状态
    vi.restoreAllMocks()
    localStorage.clear()
    expect(getAuthToken()).toBeNull()
  })
})

describe('内存优先于 localStorage', () => {
  it('两者都有值时以内存为准', () => {
    // 这个优先级是有意的：内存里那份一定是本次会话写入的最新值，
    // 而 localStorage 可能被同源的另一个标签页改过
    setAuthToken('tk-current')
    localStorage.setItem(KEY, 'tk-stale-from-other-tab')

    expect(getAuthToken()).toBe('tk-current')
  })

  it('内存为空时才回落到 localStorage（模拟页面刷新后的首次读取）', () => {
    // 刷新后模块重新加载，inMemoryToken 是 null，
    // 此时唯一的来源就是 localStorage
    clearAuthToken()
    localStorage.setItem(KEY, 'tk-restored')

    expect(getAuthToken()).toBe('tk-restored')
  })
})
