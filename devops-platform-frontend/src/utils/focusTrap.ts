const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
  'details',
  'summary',
  'iframe',
  'audio[controls]',
  'video[controls]'
].join(',')

const isVisible = (el: HTMLElement): boolean => {
  if (!el.offsetParent && el.tagName !== 'BODY') return false
  const style = getComputedStyle(el)
  return style.visibility !== 'hidden' && style.display !== 'none'
}

const getFocusable = (container: HTMLElement): HTMLElement[] => {
  const nodes = Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
  return nodes.filter(isVisible)
}

export interface FocusTrap {
  activate: () => void
  deactivate: () => void
}

export const useFocusTrap = (getContainer: () => HTMLElement | null): FocusTrap => {
  let previouslyFocused: HTMLElement | null = null
  let active = false

  const onKeydown = (e: KeyboardEvent) => {
    if (e.key !== 'Tab') return
    const container = getContainer()
    if (!container) return
    const focusable = getFocusable(container)
    if (focusable.length === 0) {
      e.preventDefault()
      container.focus()
      return
    }
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    const active = document.activeElement as HTMLElement | null
    if (e.shiftKey) {
      if (active === first || !container.contains(active)) {
        e.preventDefault()
        last.focus()
      }
    } else {
      if (active === last || !container.contains(active)) {
        e.preventDefault()
        first.focus()
      }
    }
  }

  const onFocusIn = (e: FocusEvent) => {
    const container = getContainer()
    if (!container) return
    const target = e.target as HTMLElement
    if (container.contains(target)) return
    const focusable = getFocusable(container)
    if (focusable.length > 0) focusable[0].focus()
    else container.focus()
  }

  const activate = () => {
    if (active) return
    active = true
    previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.addEventListener('keydown', onKeydown, true)
    document.addEventListener('focusin', onFocusIn, true)

    queueMicrotask(() => {
      const container = getContainer()
      if (!container) return
      if (!container.hasAttribute('tabindex')) container.setAttribute('tabindex', '-1')
      const focusable = getFocusable(container)
      if (focusable.length > 0) focusable[0].focus()
      else container.focus()
    })
  }

  const deactivate = () => {
    if (!active) return
    active = false
    document.removeEventListener('keydown', onKeydown, true)
    document.removeEventListener('focusin', onFocusIn, true)
    if (previouslyFocused && document.contains(previouslyFocused)) {
      try { previouslyFocused.focus() } catch { /* noop */ }
    }
    previouslyFocused = null
  }

  return { activate, deactivate }
}
