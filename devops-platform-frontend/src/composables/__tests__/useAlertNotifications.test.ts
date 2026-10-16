/**
 * 告警通知 WebSocket 生命周期测试。
 *
 * ── 要锁住什么 ────────────────────────────────────────────────
 * 这个 composable 的状态（ws / reconnectTimer / disposed）是**模块级变量**，
 * 不是组件实例级的。一旦停止逻辑有漏洞，泄漏的连接不属于任何组件，
 * 没有任何生命周期钩子能再回收它。
 *
 * 修复的真实缺陷（已用回退验证，见下）：
 *
 * **登出后在途消息仍会写进通知列表**。`stop()` 只调 `ws.close()` 而不解绑
 * `onmessage`，但 close 是异步的——已经在网络上的那一帧仍会抵达并触发回调，
 * 把**上一个用户**的告警（含标题等业务内容）推进通知 store。
 * 共享值守机上，下一个登录的人会在通知中心看到不属于自己的告警。
 * 这与 useSessionCleanup 要堵的是同一类问题：登出必须切断所有数据入口。
 *
 * 顺带加固的两点（原实现已有 `scheduleReconnect` 的 disposed 判断，
 * 不存在无限重连；这里是补上第二道防线与孤儿连接防护）：
 * - `onclose` 里也判 disposed，不依赖单点
 * - `start()` 先 stop：watch 在已连接状态下重复触发时不留孤儿连接
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'

vi.mock('@/api/alerts', () => ({
  fetchAlerts: vi.fn(async () => ({ alerts: [], total: 0 })),
}))

import { useAppStore } from '@/stores/app'
import { useNotificationsStore } from '@/stores/notifications'
import { useAlertNotifications } from '../useAlertNotifications'

/** 记录所有被创建过的 socket，供断言检查泄漏 */
const sockets: FakeSocket[] = []

class FakeSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3

  readyState = FakeSocket.CONNECTING
  onopen: (() => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  closeCalls = 0

  constructor(public url: string) {
    sockets.push(this)
  }

  /** 模拟浏览器：close() 之后异步派发 onclose */
  close() {
    this.closeCalls++
    this.readyState = FakeSocket.CLOSED
    // 关键：即使调用方已把引用置 null，浏览器仍会调这个回调
    this.onclose?.()
  }

  open() {
    this.readyState = FakeSocket.OPEN
    this.onopen?.()
  }
}

const Host = defineComponent({
  setup() {
    useAlertNotifications()
    return () => h('div')
  },
})

beforeEach(() => {
  sockets.length = 0
  localStorage.clear()
  setActivePinia(createPinia())
  vi.useFakeTimers()
  vi.stubGlobal('WebSocket', FakeSocket as unknown as typeof WebSocket)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

/** 推进足够长的时间，让所有可能排上的重连都跑完 */
const flushReconnects = async () => {
  for (let i = 0; i < 8; i++) {
    await vi.advanceTimersByTimeAsync(31_000)
  }
}

describe('useAlertNotifications — 登出后切断数据入口', () => {
  /**
   * 本组第一条是真正抓到缺陷的用例：回退「解绑 onmessage」后必然失败。
   * 其余几条是防止修复被回退时连带破坏其它行为的护栏。
   */
  it('登出后在途消息不得写入通知列表 —— 否则下一个登录者能看到上一个人的告警', async () => {
    const app = useAppStore()
    const notifications = useNotificationsStore()
    app.isAuthenticated = true

    const wrapper = mount(Host)
    await wrapper.vm.$nextTick()
    const socket = sockets[0]
    socket.open()

    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    // 模拟：close() 之后，网络上那一帧才抵达
    socket.onmessage?.({
      data: JSON.stringify({
        type: 'NEW',
        timestamp: '2026-08-24T10:00:00',
        alert: { id: 99, alertName: 'x', level: 'P0', title: '上一个用户的告警', status: 'FIRING', service: 'pay' },
      }),
    } as MessageEvent)

    expect(notifications.items).toHaveLength(0)
  })

  it('登出后不再建立新连接', async () => {
    const app = useAppStore()
    app.isAuthenticated = true

    const wrapper = mount(Host)
    await wrapper.vm.$nextTick()
    expect(sockets.length).toBe(1)

    sockets[0].open()

    // 登出
    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    const countAfterLogout = sockets.length
    await flushReconnects()

    expect(sockets.length).toBe(countAfterLogout)
  })

  it('组件卸载后不再建立新连接', async () => {
    const app = useAppStore()
    app.isAuthenticated = true

    const wrapper = mount(Host)
    await wrapper.vm.$nextTick()
    sockets[0].open()

    wrapper.unmount()
    const countAfterUnmount = sockets.length

    await flushReconnects()
    expect(sockets.length).toBe(countAfterUnmount)
  })

  it('stop 会解绑回调 —— 迟到的 onclose 不该再触发任何逻辑', async () => {
    const app = useAppStore()
    app.isAuthenticated = true
    const wrapper = mount(Host)
    await wrapper.vm.$nextTick()

    const socket = sockets[0]
    socket.open()

    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    // close 后所有回调都应被解绑
    expect(socket.onclose).toBeNull()
    expect(socket.onmessage).toBeNull()
    expect(socket.onopen).toBeNull()
    expect(socket.onerror).toBeNull()
  })
})

describe('useAlertNotifications — 不产生孤儿连接', () => {
  it('重复置为已登录不会累积连接', async () => {
    const app = useAppStore()
    app.isAuthenticated = true

    const wrapper = mount(Host)
    await wrapper.vm.$nextTick()
    sockets[0].open()

    // 模拟 restoreSession 重复确立登录态：先登出再登录
    app.isAuthenticated = false
    await wrapper.vm.$nextTick()
    app.isAuthenticated = true
    await wrapper.vm.$nextTick()

    // 每一条历史连接要么已关闭，要么是当前唯一活跃的那条
    const alive = sockets.filter(s => s.readyState !== FakeSocket.CLOSED)
    expect(alive.length).toBeLessThanOrEqual(1)
  })

  it('断线（非主动停止）仍会按退避重连 —— 别把功能一起关掉了', async () => {
    const app = useAppStore()
    app.isAuthenticated = true

    const wrapper = mount(Host)
    await wrapper.vm.$nextTick()

    const first = sockets[0]
    first.open()

    // 模拟网络断开：readyState 变 CLOSED 后触发 onclose（非 stop 路径）
    first.readyState = FakeSocket.CLOSED
    first.onclose?.()

    await vi.advanceTimersByTimeAsync(2000)

    expect(sockets.length).toBeGreaterThan(1)
  })
})
