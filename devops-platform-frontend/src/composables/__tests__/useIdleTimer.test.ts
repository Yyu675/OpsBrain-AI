/**
 * 空闲登出计时器测试。
 *
 * ── 为什么这条缺口值得优先补 ──────────────────────────────────
 * 它是 `scan_export_coverage.py` 报出的「确属缺口」里风险最高的一个：
 * 消费方是 `App.vue`（根组件，无测试），而它控制的是**自动登出**。
 *
 * 两个方向的失效都很难在开发时发现，因为正常操作路径下永远看不出来：
 *
 * <ul>
 *   <li><b>该登出没登出</b>——运维离开工位，会话一直有效。
 *       这类系统能直接重启 Pod、回滚发布，等于把生产权限敞在那里；</li>
 *   <li><b>误登出正在操作的用户</b>——正在写复盘报告时被踢出去，
 *       未保存内容全丢。比不登出更容易被投诉。</li>
 * </ul>
 *
 * 而它的实现里有若干**只在特定时序下才暴露**的分支：
 * warned 标志位的置位与复位、paused 期间忽略 reset、
 * 配置变化时的即时重排、隐藏标签页不算活跃。
 * 这些正是单测最该覆盖的东西。
 *
 * ── 为什么必须用 fake timers 且断言"临界点两侧" ───────────────
 * 计时类逻辑最典型的假绿是「推进足够久，回调触发了，通过」——
 * 这种断言在阈值被改成任意更小值时同样通过。
 * 所以下面每条都推进到**阈值前一刻确认没触发**，再推过阈值确认触发。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { mount } from '@vue/test-utils'

import { useIdleTimer, type IdleTimerOptions } from '../useIdleTimer'

/**
 * 把 composable 挂进一个真实组件里。
 *
 * 不能直接调用 `useIdleTimer()`——它用了 onMounted/onBeforeUnmount，
 * 脱离组件实例时这两个钩子不会执行，事件监听根本没绑上，
 * 所有「用户操作重置计时」的用例都会以「回调没触发」失败，
 * 而原因与被测逻辑无关。
 */
const mountTimer = (options: IdleTimerOptions) => {
  let api: ReturnType<typeof useIdleTimer> | null = null
  const wrapper = mount(defineComponent({
    setup() {
      api = useIdleTimer(options)
      return () => null
    },
  }))
  return { wrapper, api: api as unknown as ReturnType<typeof useIdleTimer> }
}

const WARN = 13 * 60 * 1000
const TIMEOUT = 15 * 60 * 1000

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('基本计时', () => {
  it('到达警告阈值才触发 onWarn，早一毫秒都不触发', () => {
    const onWarn = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onWarn })

    // 临界点前：不能提前吓唬用户
    vi.advanceTimersByTime(WARN - 1)
    expect(onWarn).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(onWarn).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('onWarn 收到的是「还剩多久登出」，不是已经过去多久', () => {
    // 这个值会被 App.vue 直接渲染成「N 秒后自动登出」。
    // 传错方向的话，用户会看到「780 秒后自动登出」然后 2 分钟就被踢了
    const onWarn = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onWarn })

    vi.advanceTimersByTime(WARN)

    expect(onWarn).toHaveBeenCalledWith(TIMEOUT - WARN)
    wrapper.unmount()
  })

  it('到达超时阈值触发 onTimeout，早一毫秒都不触发', () => {
    const onTimeout = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onTimeout })

    vi.advanceTimersByTime(TIMEOUT - 1)
    expect(onTimeout).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(onTimeout).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('不传阈值时用默认 13/15 分钟', () => {
    const onWarn = vi.fn()
    const onTimeout = vi.fn()
    const { wrapper } = mountTimer({ onWarn, onTimeout })

    vi.advanceTimersByTime(13 * 60 * 1000)
    expect(onWarn).toHaveBeenCalledTimes(1)
    expect(onTimeout).not.toHaveBeenCalled()

    vi.advanceTimersByTime(2 * 60 * 1000)
    expect(onTimeout).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })
})

describe('用户活动重置计时', () => {
  it('键盘操作后重新计时——不能把之前的空闲时间算进去', () => {
    const onTimeout = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onTimeout })

    vi.advanceTimersByTime(TIMEOUT - 1000)
    window.dispatchEvent(new Event('keydown'))

    // 重置后再走「原本只差 1 秒」的时长，不该触发
    vi.advanceTimersByTime(1000)
    expect(onTimeout).not.toHaveBeenCalled()

    // 从重置点重新算满整个超时才触发
    vi.advanceTimersByTime(TIMEOUT - 1000)
    expect(onTimeout).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('已警告后用户回来操作，触发 onActive 把警告收掉', () => {
    // App.vue 用它关闭「即将登出」弹窗。不触发的话弹窗会一直挂着，
    // 用户明明在操作却被告知即将登出
    const onActive = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onActive })

    vi.advanceTimersByTime(WARN)
    window.dispatchEvent(new Event('mousedown'))

    expect(onActive).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('未警告时的操作不触发 onActive——没有弹窗可关', () => {
    // warned 标志位的作用就在这里。漏判会导致每次鼠标移动都调一次 onActive，
    // 而 App.vue 里那是个关闭弹窗的动作，高频空调用
    const onActive = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onActive })

    vi.advanceTimersByTime(WARN - 1000)
    window.dispatchEvent(new Event('mousemove'))
    window.dispatchEvent(new Event('scroll'))

    expect(onActive).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('警告收掉之后的后续操作不再重复触发 onActive', () => {
    // ── 这条是注入验证补出来的 ────────────────────────────────
    // 最初只测了「已警告→操作→onActive 被调用」与「未警告→操作→不调用」，
    // 但把实现里的 `warned = false` 删掉后两条都还是绿的：
    // 前者仍会调用一次，后者初始 warned 就是 false。
    //
    // 漏掉的差异在这里：不复位的话 warned 会一直是 true，
    // 之后**每一次**鼠标移动都会再调一次 onActive——
    // 而 App.vue 里那是个关闭弹窗的动作，会变成高频空调用。
    const onActive = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onActive })

    vi.advanceTimersByTime(WARN)
    window.dispatchEvent(new Event('mousedown'))
    expect(onActive).toHaveBeenCalledTimes(1)

    // 弹窗已关，此时再操作不该重复关闭
    window.dispatchEvent(new Event('mousemove'))
    window.dispatchEvent(new Event('keydown'))

    expect(onActive).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('警告被收掉后，再次空闲会重新警告', () => {
    // 验证 warned 确实被复位了。只置位不复位的话，
    // 用户操作一次之后就再也收不到警告，直接被静默登出
    const onWarn = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onWarn })

    vi.advanceTimersByTime(WARN)
    expect(onWarn).toHaveBeenCalledTimes(1)

    window.dispatchEvent(new Event('keydown'))
    vi.advanceTimersByTime(WARN)

    expect(onWarn).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})

describe('暂停与恢复', () => {
  it('pause 后不再触发任何回调', () => {
    const onWarn = vi.fn()
    const onTimeout = vi.fn()
    const { wrapper, api } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onWarn, onTimeout })

    api.pause()
    vi.advanceTimersByTime(TIMEOUT * 2)

    expect(onWarn).not.toHaveBeenCalled()
    expect(onTimeout).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('pause 期间的用户操作不会偷偷重启计时', () => {
    // reset() 里那句 `if (paused) return` 就是防这个。
    // 漏了的话，鼠标一动就把计时器重新排上，pause 形同虚设
    const onTimeout = vi.fn()
    const { wrapper, api } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onTimeout })

    api.pause()
    window.dispatchEvent(new Event('keydown'))
    vi.advanceTimersByTime(TIMEOUT * 2)

    expect(onTimeout).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('resume 后从头开始重新计时', () => {
    const onTimeout = vi.fn()
    const { wrapper, api } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onTimeout })

    api.pause()
    vi.advanceTimersByTime(TIMEOUT)
    api.resume()

    vi.advanceTimersByTime(TIMEOUT - 1)
    expect(onTimeout).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(onTimeout).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

describe('配置变化即时生效', () => {
  it('超时阈值改小后立即按新值重排，不等下一次操作', () => {
    // 用户在设置里把超时从 15 分钟改成 1 分钟，若要等他下次动鼠标
    // 才生效，这个设置项看起来就是坏的
    const onTimeout = vi.fn()
    const timeoutAfter = ref(TIMEOUT)
    const { wrapper } = mountTimer({
      warnAfter: ref(WARN),
      timeoutAfter,
      onTimeout,
    })

    timeoutAfter.value = 60_000
    // watch 是异步的，要让它跑完才会重排
    return wrapper.vm.$nextTick().then(() => {
      vi.advanceTimersByTime(59_999)
      expect(onTimeout).not.toHaveBeenCalled()

      vi.advanceTimersByTime(1)
      expect(onTimeout).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    })
  })

  it('暂停状态下改配置不会意外把计时器启动起来', async () => {
    const onTimeout = vi.fn()
    const timeoutAfter = ref(TIMEOUT)
    const { wrapper, api } = mountTimer({ warnAfter: ref(WARN), timeoutAfter, onTimeout })

    api.pause()
    timeoutAfter.value = 60_000
    await wrapper.vm.$nextTick()

    vi.advanceTimersByTime(120_000)
    expect(onTimeout).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('标签页可见性', () => {
  const setHidden = (hidden: boolean) => {
    Object.defineProperty(document, 'hidden', { value: hidden, configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
  }

  afterEach(() => {
    Object.defineProperty(document, 'hidden', { value: false, configurable: true })
  })

  it('切回可见时重置计时——用户回来了就该重新算', () => {
    const onTimeout = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onTimeout })

    vi.advanceTimersByTime(TIMEOUT - 1000)
    setHidden(false)

    vi.advanceTimersByTime(1000)
    expect(onTimeout).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('切到隐藏不重置——后台挂着不算活跃', () => {
    // 隐藏也当活跃的话，用户把标签页丢在后台一整天都不会登出，
    // 自动登出这个功能就废了
    const onTimeout = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onTimeout })

    vi.advanceTimersByTime(TIMEOUT - 1000)
    setHidden(true)

    vi.advanceTimersByTime(1000)
    expect(onTimeout).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

describe('卸载清理', () => {
  it('卸载后不再触发回调——否则会对已销毁的页面执行登出', () => {
    const onTimeout = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onTimeout })

    wrapper.unmount()
    vi.advanceTimersByTime(TIMEOUT * 2)

    expect(onTimeout).not.toHaveBeenCalled()
  })

  it('卸载后事件监听已解绑，用户操作不再触发任何逻辑', () => {
    // 监听不解绑是内存泄漏，且回调里引用的是已卸载组件的闭包
    const onActive = vi.fn()
    const onTimeout = vi.fn()
    const { wrapper } = mountTimer({ warnAfter: WARN, timeoutAfter: TIMEOUT, onActive, onTimeout })

    wrapper.unmount()
    window.dispatchEvent(new Event('keydown'))
    vi.advanceTimersByTime(TIMEOUT * 2)

    expect(onActive).not.toHaveBeenCalled()
    expect(onTimeout).not.toHaveBeenCalled()
  })
})
