import type { Directive, DirectiveBinding } from 'vue'
import { useAppStore, type Role } from '@/stores/app'

type Mode = 'hide' | 'disable'

interface PermissionValue {
  roles?: Role[]
  codes?: string[]
  mode?: Mode
}

const parse = (raw: unknown): PermissionValue => {
  if (!raw) return {}
  if (typeof raw === 'string') return { codes: [raw] }
  if (Array.isArray(raw)) return { codes: raw as string[] }
  if (typeof raw === 'object') return raw as PermissionValue
  return {}
}

const modeFromBinding = (binding: DirectiveBinding): Mode => {
  if (binding.modifiers.disable) return 'disable'
  return parse(binding.value).mode ?? 'hide'
}

const check = (binding: DirectiveBinding): boolean => {
  const value = parse(binding.value)
  const store = useAppStore()
  const okRole = store.hasRole(value.roles)
  const okPerm = store.hasPermission(value.codes)
  return okRole && okPerm
}

/**
 * 权限禁用标记。
 *
 * 指令只能撤销「自己加的」禁用——元素可能同时被业务逻辑禁用
 * （如 `:disabled="!!actionLoading"` 的加载态），无条件 removeAttribute
 * 会抹掉那份禁用，导致操作进行中仍可重复点击。
 */
const PERM_DISABLED_FLAG = '_perm_disabled'

type PermElement = HTMLElement & {
  _perm_display?: string
  _perm_disabled?: boolean
}

const apply = (el: HTMLElement, binding: DirectiveBinding) => {
  const allowed = check(binding)
  const mode = modeFromBinding(binding)
  const target = el as PermElement

  if (allowed) {
    if (mode === 'disable') {
      // 只有此前由本指令加的禁用才撤销，业务态禁用保持不动
      if (target[PERM_DISABLED_FLAG]) {
        el.removeAttribute('disabled')
        el.removeAttribute('aria-disabled')
        target[PERM_DISABLED_FLAG] = false
      }
      el.classList.remove('is-permission-disabled')
    } else {
      const original = target._perm_display
      if (typeof original === 'string') {
        el.style.display = original
      }
    }
    return
  }

  if (mode === 'disable') {
    el.setAttribute('disabled', 'disabled')
    el.setAttribute('aria-disabled', 'true')
    el.classList.add('is-permission-disabled')
    el.setAttribute('title', el.getAttribute('title') || '无操作权限')
    target[PERM_DISABLED_FLAG] = true
  } else {
    if (target._perm_display === undefined) {
      target._perm_display = el.style.display || ''
    }
    el.style.display = 'none'
  }
}

export const permission: Directive = {
  mounted(el: HTMLElement, binding) { apply(el, binding) },
  updated(el: HTMLElement, binding) { apply(el, binding) }
}

export default permission
