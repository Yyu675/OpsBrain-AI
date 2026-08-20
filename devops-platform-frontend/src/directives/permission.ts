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

const apply = (el: HTMLElement, binding: DirectiveBinding) => {
  const allowed = check(binding)
  const mode = modeFromBinding(binding)

  if (allowed) {
    if (mode === 'disable') {
      el.removeAttribute('disabled')
      el.classList.remove('is-permission-disabled')
      el.setAttribute('aria-disabled', 'false')
    } else {
      const original = (el as HTMLElement & { _perm_display?: string })._perm_display
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
  } else {
    const store = el as HTMLElement & { _perm_display?: string }
    if (store._perm_display === undefined) {
      store._perm_display = el.style.display || ''
    }
    el.style.display = 'none'
  }
}

export const permission: Directive = {
  mounted(el: HTMLElement, binding) { apply(el, binding) },
  updated(el: HTMLElement, binding) { apply(el, binding) }
}

export default permission
