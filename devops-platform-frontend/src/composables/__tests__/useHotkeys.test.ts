import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'

import { formatCombo, useActiveHotkeys, useHotkeys, type Hotkey } from '../useHotkeys'

/**
 * activeGroups 是模块级状态，用例间必须清理——否则前一个用例注册的快捷键
 * 会泄漏到后续用例，让断言依赖执行顺序。
 */
const mounted: VueWrapper[] = []

afterEach(() => {
  while (mounted.length) {
    const wrapper = mounted.pop()!
    // 用例内可能已显式卸载（卸载行为本身即被测对象），重复 unmount 不应让清理失败
    try {
      wrapper.unmount()
    } catch {
      /* 已卸载 */
    }
  }
})

/** 挂载一个只负责注册快捷键的探针组件 */
function mountRegistrar(hotkeys: Hotkey[]) {
  const wrapper = mount(
    defineComponent({
      setup() {
        useHotkeys(hotkeys)
        return () => h('div')
      },
    })
  )
  mounted.push(wrapper)
  return wrapper
}

/** 挂载一个读取活跃快捷键清单的探针组件 */
function mountReader() {
  const seen: { hints: ReturnType<typeof useActiveHotkeys> | null } = { hints: null }
  const wrapper = mount(
    defineComponent({
      setup() {
        seen.hints = useActiveHotkeys()
        return () => h('div')
      },
    })
  )
  mounted.push(wrapper)
  return { wrapper, hints: seen.hints! }
}

const noop = () => {}

describe('formatCombo', () => {
  it('单字符键统一大写展示', () => {
    expect(formatCombo({ key: 'n', handler: noop })).toBe('N')
  })

  it('符号键保持原样', () => {
    expect(formatCombo({ key: '[', handler: noop })).toBe('[')
    expect(formatCombo({ key: '?', handler: noop })).toBe('?')
  })

  it('具名键保持原样不大写', () => {
    expect(formatCombo({ key: 'Escape', handler: noop })).toBe('Escape')
  })

  it('修饰键按 Ctrl/Cmd/Alt/Shift 固定顺序拼接', () => {
    expect(
      formatCombo({ key: 'k', ctrl: true, meta: true, alt: true, shift: true, handler: noop })
    ).toBe('Ctrl + Cmd + Alt + Shift + K')
  })

  it('只有部分修饰键时不输出未设置的项', () => {
    expect(formatCombo({ key: 's', ctrl: true, handler: noop })).toBe('Ctrl + S')
  })
})

describe('useActiveHotkeys', () => {
  it('未注册任何快捷键时清单为空——帮助面板据此显示空态而非编造条目', () => {
    const { hints } = mountReader()
    expect(hints.value).toEqual([])
  })

  it('只收录已挂载组件注册的快捷键', async () => {
    const { hints } = mountReader()
    mountRegistrar([{ key: '[', description: '收起侧栏', handler: noop }])
    await nextTick()

    expect(hints.value).toEqual([{ combo: '[', description: '收起侧栏' }])
  })

  it('组件卸载后其快捷键从清单移除——面板不得展示已失效的键', async () => {
    const { hints } = mountReader()
    const registrar = mountRegistrar([{ key: '[', description: '收起侧栏', handler: noop }])
    await nextTick()
    expect(hints.value).toHaveLength(1)

    registrar.unmount()
    await nextTick()
    expect(hints.value).toEqual([])
  })

  it('未声明 description 的快捷键不进清单——无说明的按键对用户无意义', async () => {
    const { hints } = mountReader()
    mountRegistrar([
      { key: '[', description: '收起侧栏', handler: noop },
      { key: 'x', handler: noop },
    ])
    await nextTick()

    expect(hints.value).toEqual([{ combo: '[', description: '收起侧栏' }])
  })

  it('同一按键被多处注册时去重，只保留首个说明', async () => {
    const { hints } = mountReader()
    mountRegistrar([{ key: '[', description: '收起目录', handler: noop }])
    mountRegistrar([{ key: '[', description: '收起侧栏', handler: noop }])
    await nextTick()

    expect(hints.value).toEqual([{ combo: '[', description: '收起目录' }])
  })

  it('多组注册按挂载顺序汇总', async () => {
    const { hints } = mountReader()
    mountRegistrar([{ key: '?', description: '打开快捷键面板', handler: noop }])
    mountRegistrar([
      { key: '[', description: '收起目录', handler: noop },
      { key: ']', description: '收起大纲', handler: noop },
    ])
    await nextTick()

    expect(hints.value).toEqual([
      { combo: '?', description: '打开快捷键面板' },
      { combo: '[', description: '收起目录' },
      { combo: ']', description: '收起大纲' },
    ])
  })
})

describe('useHotkeys 触发行为', () => {
  it('按下已注册的键触发 handler', () => {
    let fired = 0
    mountRegistrar([{ key: '[', handler: () => { fired += 1 } }])

    window.dispatchEvent(new KeyboardEvent('keydown', { key: '[' }))
    expect(fired).toBe(1)
  })

  it('按键匹配忽略大小写', () => {
    let fired = 0
    mountRegistrar([{ key: 'n', handler: () => { fired += 1 } }])

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'N' }))
    expect(fired).toBe(1)
  })

  it('修饰键不匹配时不触发', () => {
    let fired = 0
    mountRegistrar([{ key: 's', ctrl: true, handler: () => { fired += 1 } }])

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 's' }))
    expect(fired).toBe(0)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true }))
    expect(fired).toBe(1)
  })

  it('焦点在输入框时不触发——避免干扰打字', () => {
    let fired = 0
    mountRegistrar([{ key: '[', handler: () => { fired += 1 } }])

    const input = document.createElement('input')
    document.body.appendChild(input)
    input.dispatchEvent(new KeyboardEvent('keydown', { key: '[', bubbles: true }))
    expect(fired).toBe(0)
    input.remove()
  })

  it('组件卸载后不再响应按键', () => {
    let fired = 0
    const registrar = mountRegistrar([{ key: '[', handler: () => { fired += 1 } }])
    registrar.unmount()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: '[' }))
    expect(fired).toBe(0)
  })
})
