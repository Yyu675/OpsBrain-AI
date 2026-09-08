/**
 * 离开确认守卫测试。
 *
 * ── 为什么这 98 行值得测 ──────────────────────────────────────
 * 它和 draftStorage 一起构成「用户写的东西会不会丢」的两半：
 * draftStorage 管暂存，本模块管**离开时问不问**。
 *
 * 最容易出错也最容易被忽略的是**三选一分支**。
 * 知识文档编辑器里草稿已自动暂存，此时「离开」与「丢弃」是两件事：
 *
 *   确认按钮      → 离开，草稿保留（稍后回来继续）
 *   取消按钮      → 丢弃草稿后离开
 *   关闭 / Esc    → 留在当前页
 *
 * 三者靠 ElMessageBox 的 `distinguishCancelAndClose` 区分。
 * 关掉这个选项后，「取消」与「关闭」都 reject 且**无从区分**——
 * 用户按 Esc 想继续编辑，系统却把草稿丢了。
 * 这个 bug 不会报错、不会崩溃，只会让人的半小时工作凭空消失。
 *
 * ── 测试手法 ──────────────────────────────────────────────────
 * `onBeforeRouteLeave` 需要真实的路由上下文，所以用 memory router
 * 挂一个宿主组件，靠真实的 `router.push` 触发守卫，
 * 而不是去 mock vue-router 的内部实现——mock 掉的话，
 * 「守卫到底有没有被注册」这件事就测不到了。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref, type Ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView, type Router } from 'vue-router'
import { ElMessageBox } from 'element-plus'

import { useDirtyGuard } from '../useDirtyGuard'

const Blank = defineComponent({ setup: () => () => h('div', 'blank') })

/**
 * 挂载一个使用守卫的页面，返回 router 以便触发跳转。
 *
 * 注意必须用 <RouterView> 渲染，让 Editor 成为**当前路由匹配的组件**——
 * `onBeforeRouteLeave` 是注册到「渲染它的那条路由记录」上的。
 * 第一版直接 `mount(Editor)` 把它挂在路由树之外，守卫根本没注册，
 * 结果 8 个用例全部「跳转成功、确认框从未弹出」——
 * 看起来像产品没做拦截，实际是测试搭错了台子。
 */
async function mountWithGuard(
  isDirty: Ref<boolean>,
  options: Parameters<typeof useDirtyGuard>[1] = {},
): Promise<{ router: Router; unmount: () => void }> {
  const Editor = defineComponent({
    setup() {
      useDirtyGuard(isDirty, options)
      return () => h('div', 'editor')
    },
  })

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Editor },
      { path: '/away', component: Blank },
    ],
  })

  await router.push('/')
  await router.isReady()

  const host = mount(defineComponent({ setup: () => () => h(RouterView) }), {
    global: { plugins: [router] },
  })
  await flushPromises()

  return { router, unmount: () => host.unmount() }
}

describe('useDirtyGuard', () => {
  let confirmSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    confirmSpy = vi.spyOn(ElMessageBox, 'confirm')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('两选一模式（默认）', () => {
    it('未修改时直接放行，不弹确认框', async () => {
      const isDirty = ref(false)
      const { router, unmount } = await mountWithGuard(isDirty)

      await router.push('/away')

      // 没改过东西还弹窗，会让用户每次点返回都被打断一次
      expect(confirmSpy).not.toHaveBeenCalled()
      expect(router.currentRoute.value.path).toBe('/away')
      unmount()
    })

    it('已修改且确认放弃 → 调 onDiscard 并离开', async () => {
      confirmSpy.mockResolvedValue('confirm')
      const isDirty = ref(true)
      const onDiscard = vi.fn()
      const { router, unmount } = await mountWithGuard(isDirty, { onDiscard })

      await router.push('/away')

      expect(confirmSpy).toHaveBeenCalled()
      expect(onDiscard).toHaveBeenCalledOnce()
      expect(router.currentRoute.value.path).toBe('/away')
      unmount()
    })

    it('已修改且取消 → 留在当前页，不调 onDiscard', async () => {
      confirmSpy.mockRejectedValue('cancel')
      const isDirty = ref(true)
      const onDiscard = vi.fn()
      const { router, unmount } = await mountWithGuard(isDirty, { onDiscard })

      await router.push('/away')

      // 用户选了「继续编辑」，绝不能把他的内容清掉
      expect(onDiscard).not.toHaveBeenCalled()
      expect(router.currentRoute.value.path).toBe('/')
      unmount()
    })

    it('自定义文案透传给对话框', async () => {
      confirmSpy.mockResolvedValue('confirm')
      const isDirty = ref(true)
      const { router, unmount } = await mountWithGuard(isDirty, {
        message: '工单未提交，确认离开？',
        title: '放弃填写',
        confirmText: '放弃',
        cancelText: '接着填',
      })

      await router.push('/away')

      expect(confirmSpy).toHaveBeenCalledWith(
        '工单未提交，确认离开？',
        '放弃填写',
        expect.objectContaining({ confirmButtonText: '放弃', cancelButtonText: '接着填' }),
      )
      unmount()
    })
  })

  describe('三选一模式（草稿已自动暂存的场景）', () => {
    const threeWay = {
      message: '草稿已暂存到本机',
      confirmText: '离开（保留草稿）',
      discardText: '丢弃草稿并离开',
    }

    it('开启 distinguishCancelAndClose —— 关掉它「丢弃」与「留下」就混成一个', async () => {
      confirmSpy.mockResolvedValue('confirm')
      const isDirty = ref(true)
      const { router, unmount } = await mountWithGuard(isDirty, threeWay)

      await router.push('/away')

      // 这是三选一能成立的唯一前提：
      // 不开的话，取消与关闭都 reject 且无从区分，
      // 用户按 Esc 想继续编辑，系统却把草稿丢了
      expect(confirmSpy).toHaveBeenCalledWith(
        expect.any(String),
        expect.any(String),
        expect.objectContaining({ distinguishCancelAndClose: true }),
      )
      unmount()
    })

    it('确认 → 离开且**保留**草稿（不调 onDiscard），并执行 onConfirm', async () => {
      confirmSpy.mockResolvedValue('confirm')
      const isDirty = ref(true)
      const onDiscard = vi.fn()
      const onConfirm = vi.fn()
      const { router, unmount } = await mountWithGuard(isDirty,
        { ...threeWay, onDiscard, onConfirm })

      await router.push('/away')

      // 按钮文案是「离开（保留草稿）」，这里若调了 onDiscard
      // 就等于按钮承诺了保留却把草稿删了
      expect(onDiscard).not.toHaveBeenCalled()
      expect(onConfirm).toHaveBeenCalledOnce()
      expect(router.currentRoute.value.path).toBe('/away')
      unmount()
    })

    it('取消（= 丢弃按钮）→ 调 onDiscard 后离开', async () => {
      confirmSpy.mockRejectedValue('cancel')
      const isDirty = ref(true)
      const onDiscard = vi.fn()
      const { router, unmount } = await mountWithGuard(isDirty, { ...threeWay, onDiscard })

      await router.push('/away')

      expect(onDiscard).toHaveBeenCalledOnce()
      expect(router.currentRoute.value.path).toBe('/away')
      unmount()
    })

    it('关闭 / Esc → 留在当前页，草稿不动', async () => {
      confirmSpy.mockRejectedValue('close')
      const isDirty = ref(true)
      const onDiscard = vi.fn()
      const onConfirm = vi.fn()
      const { router, unmount } = await mountWithGuard(isDirty,
        { ...threeWay, onDiscard, onConfirm })

      await router.push('/away')

      // 三个分支里最容易写错的一条：Esc 的语义是「我还没想好」，
      // 既不该离开也不该丢东西
      expect(onDiscard).not.toHaveBeenCalled()
      expect(onConfirm).not.toHaveBeenCalled()
      expect(router.currentRoute.value.path).toBe('/')
      unmount()
    })

    it('未修改时三选一模式同样不弹窗', async () => {
      const isDirty = ref(false)
      const { router, unmount } = await mountWithGuard(isDirty, threeWay)

      await router.push('/away')

      expect(confirmSpy).not.toHaveBeenCalled()
      expect(router.currentRoute.value.path).toBe('/away')
      unmount()
    })

    it('onDiscard / onConfirm 未提供时不报错', async () => {
      confirmSpy.mockRejectedValue('cancel')
      const isDirty = ref(true)
      const { router, unmount } = await mountWithGuard(isDirty, threeWay)

      await expect(router.push('/away')).resolves.not.toThrow()
      unmount()
    })
  })

  describe('刷新 / 关标签页（beforeunload）', () => {
    let addSpy: ReturnType<typeof vi.spyOn>

    beforeEach(() => {
      addSpy = vi.spyOn(window, 'addEventListener')
    })

    it('挂载时注册监听，卸载时移除 —— 不移除会让离开后的页面仍然拦截刷新', async () => {
      const removeSpy = vi.spyOn(window, 'removeEventListener')

      const isDirty = ref(false)
      const { unmount } = await mountWithGuard(isDirty)

      expect(addSpy).toHaveBeenCalledWith('beforeunload', expect.any(Function))

      unmount()
      expect(removeSpy).toHaveBeenCalledWith('beforeunload', expect.any(Function))
    })

    it('未修改时 beforeunload 不阻止刷新', async () => {
      const isDirty = ref(false)
      const { unmount } = await mountWithGuard(isDirty)

      // 直接把守卫的监听器取出来单独调用，避免其他用例遗留在 window 上的
      // 同名监听器干扰 defaultPrevented（jsdom 的事件是全局共享的）
      const event = new Event('beforeunload', { cancelable: true })
      const handler = addSpy.mock.calls
        .filter((c) => c[0] === 'beforeunload')
        .at(-1)?.[1] as EventListener
      handler(event)

      // 没改过东西还拦刷新，浏览器会弹一个用户根本不需要的原生确认框
      expect(event.defaultPrevented).toBe(false)
      unmount()
    })

    it('已修改时 beforeunload 阻止刷新', async () => {
      const isDirty = ref(true)
      const { unmount } = await mountWithGuard(isDirty)

      const event = new Event('beforeunload', { cancelable: true })
      const handler = addSpy.mock.calls
        .filter((c) => c[0] === 'beforeunload')
        .at(-1)?.[1] as EventListener
      handler(event)

      expect(event.defaultPrevented).toBe(true)
      unmount()
    })
  })
})
