/**
 * v-permission 指令测试。
 *
 * 保护 6.59 契约：
 * - 前端隐藏/置灰是**体验层**，后端 @SaCheckRole 才是安全底线
 * - 指令只能撤销「自己加的」禁用 —— 元素可能同时被业务逻辑禁用
 *   （如 `:disabled="!!actionLoading"` 的加载态），无条件 removeAttribute
 *   会让操作进行中仍可重复点击
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'

import { permission } from '../permission'
import { useAppStore, type Role } from '@/stores/app'

/**
 * 挂载一个带 v-permission 的按钮。
 *
 * 用 template 字符串而非渲染函数：指令修饰符（.disable）在渲染函数里需要
 * withDirectives 手工构造，可读性差且容易与真实模板用法产生偏差。
 */
function mountWithDirective(opts: {
  value: unknown
  mode?: 'hide' | 'disable'
  businessDisabled?: boolean
}) {
  const DisableComp = defineComponent({
    props: { biz: { type: Boolean, default: false } },
    directives: { permission },
    template: `<button v-permission.disable="value" :disabled="biz || undefined">操作</button>`,
    setup() {
      return { value: opts.value }
    },
  })

  const HideComp = defineComponent({
    directives: { permission },
    template: `<button v-permission="value">操作</button>`,
    setup() {
      return { value: opts.value }
    },
  })

  return mount(opts.mode === 'hide' ? HideComp : DisableComp, {
    props: opts.mode === 'hide' ? {} : { biz: opts.businessDisabled ?? false },
  })
}

function setRole(role: Role, permissions: string[] = []) {
  const app = useAppStore()
  app.currentUser = { ...app.currentUser, role, permissions }
  app.isAuthenticated = true
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('角色校验（hide 模式，默认）', () => {
  it('角色匹配时按钮正常显示', () => {
    setRole('admin')
    const w = mountWithDirective({ value: { roles: ['admin'] }, mode: 'hide' })

    expect(w.find('button').element.style.display).not.toBe('none')
  })

  it('角色不匹配时按钮被隐藏', () => {
    setRole('operator')
    const w = mountWithDirective({ value: { roles: ['admin'] }, mode: 'hide' })

    expect(w.find('button').element.style.display).toBe('none')
  })

  it('未声明 roles 时对所有登录用户可见', () => {
    setRole('viewer')
    const w = mountWithDirective({ value: {}, mode: 'hide' })

    expect(w.find('button').element.style.display).not.toBe('none')
  })
})

describe('disable 模式', () => {
  it('角色匹配时按钮可用', () => {
    setRole('admin')
    const w = mountWithDirective({ value: { roles: ['admin'] } })
    const btn = w.find('button').element as HTMLButtonElement

    expect(btn.hasAttribute('disabled')).toBe(false)
    expect(btn.classList.contains('is-permission-disabled')).toBe(false)
  })

  it('角色不匹配时置灰并加标记 class', () => {
    setRole('operator')
    const w = mountWithDirective({ value: { roles: ['admin'] } })
    const btn = w.find('button').element as HTMLButtonElement

    expect(btn.hasAttribute('disabled')).toBe(true)
    expect(btn.classList.contains('is-permission-disabled')).toBe(true)
  })

  it('无权限时补 aria-disabled，供读屏软件识别', () => {
    setRole('operator')
    const w = mountWithDirective({ value: { roles: ['admin'] } })

    expect(w.find('button').attributes('aria-disabled')).toBe('true')
  })

  it('无权限时补 title 说明原因 —— 否则用户不知道为何点不动', () => {
    setRole('operator')
    const w = mountWithDirective({ value: { roles: ['admin'] } })

    expect(w.find('button').attributes('title')).toBe('无操作权限')
  })
})

describe('不覆盖业务态禁用（本轮修复的缺陷）', () => {
  it('有权限但业务态禁用时，指令不得移除 disabled —— 否则加载中可重复点击', async () => {
    setRole('admin')
    const w = mountWithDirective({ value: { roles: ['admin'] }, businessDisabled: true })
    await nextTick()

    const btn = w.find('button').element as HTMLButtonElement
    expect(btn.hasAttribute('disabled')).toBe(true)
  })

  it('有权限且业务态未禁用时按钮可用', async () => {
    setRole('admin')
    const w = mountWithDirective({ value: { roles: ['admin'] }, businessDisabled: false })
    await nextTick()

    expect((w.find('button').element as HTMLButtonElement).hasAttribute('disabled')).toBe(false)
  })

  it('有权限时不残留 is-permission-disabled class', async () => {
    setRole('admin')
    const w = mountWithDirective({ value: { roles: ['admin'] }, businessDisabled: true })
    await nextTick()

    expect(w.find('button').classes()).not.toContain('is-permission-disabled')
  })
})

describe('权限码校验', () => {
  it('拥有全部所需权限码时通过', () => {
    setRole('operator', ['ticket:delete'])
    const w = mountWithDirective({ value: { codes: ['ticket:delete'] } })

    expect((w.find('button').element as HTMLButtonElement).hasAttribute('disabled')).toBe(false)
  })

  it('缺少任一权限码即拦截 —— 权限是「全部满足」而非「任一满足」', () => {
    setRole('operator', ['ticket:read'])
    const w = mountWithDirective({ value: { codes: ['ticket:read', 'ticket:delete'] } })

    expect((w.find('button').element as HTMLButtonElement).hasAttribute('disabled')).toBe(true)
  })

  it('字符串简写视为单个权限码', () => {
    setRole('operator', ['ticket:delete'])
    const w = mountWithDirective({ value: 'ticket:delete' })

    expect((w.find('button').element as HTMLButtonElement).hasAttribute('disabled')).toBe(false)
  })

  it('通配权限 * 放行一切', () => {
    setRole('admin', ['*'])
    const w = mountWithDirective({ value: { codes: ['any:thing'], roles: ['viewer'] } })

    expect((w.find('button').element as HTMLButtonElement).hasAttribute('disabled')).toBe(false)
  })
})

describe('角色与权限码同时声明', () => {
  it('两者都满足才通过', () => {
    setRole('admin', ['ticket:delete'])
    const w = mountWithDirective({ value: { roles: ['admin'], codes: ['ticket:delete'] } })

    expect((w.find('button').element as HTMLButtonElement).hasAttribute('disabled')).toBe(false)
  })

  it('角色满足但缺权限码时拦截', () => {
    setRole('admin', [])
    const w = mountWithDirective({ value: { roles: ['admin'], codes: ['ticket:delete'] } })

    expect((w.find('button').element as HTMLButtonElement).hasAttribute('disabled')).toBe(true)
  })
})
