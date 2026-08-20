import { onMounted, onBeforeUnmount } from 'vue'

export interface Hotkey {
  key: string
  ctrl?: boolean
  meta?: boolean
  shift?: boolean
  alt?: boolean
  description?: string
  handler: (e: KeyboardEvent) => void
}

const isEditableTarget = (el: EventTarget | null): boolean => {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  return el.isContentEditable
}

const matchModifiers = (e: KeyboardEvent, h: Hotkey) => {
  if (!!h.ctrl !== e.ctrlKey) return false
  if (!!h.meta !== e.metaKey) return false
  if (!!h.shift !== e.shiftKey) return false
  if (!!h.alt !== e.altKey) return false
  return true
}

export const useHotkeys = (hotkeys: Hotkey[], options: { allowInInput?: boolean } = {}) => {
  const onKeydown = (e: KeyboardEvent) => {
    if (!options.allowInInput && isEditableTarget(e.target)) {
      // Allow "?" help even inside inputs is not necessary — skip all.
      return
    }
    for (const hk of hotkeys) {
      if (e.key.toLowerCase() !== hk.key.toLowerCase()) continue
      if (!matchModifiers(e, hk)) continue
      hk.handler(e)
      break
    }
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
}
