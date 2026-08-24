import { onMounted, onBeforeUnmount, ref, computed, type ComputedRef } from 'vue'

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

// ==================== 活跃快捷键注册表 ====================
// 帮助面板必须展示「当前页面真实生效」的快捷键，不能另写一份静态清单——
// 静态清单会与实际注册漂移，最终向用户展示不存在的快捷键（同 6.13 编造标签、
// 6.38 虚报统计的问题家族）。这里让注册表成为唯一真相源。

/** 当前已挂载的快捷键集合（按注册批次分组，卸载时整批移除） */
const activeGroups = ref<Hotkey[][]>([])

/** 组合键的可读表示，如 Ctrl + K */
export function formatCombo(hk: Hotkey): string {
  const parts: string[] = []
  if (hk.ctrl) parts.push('Ctrl')
  if (hk.meta) parts.push('Cmd')
  if (hk.alt) parts.push('Alt')
  if (hk.shift) parts.push('Shift')
  // 单字符键统一大写展示（'[' 之类符号键保持原样）
  parts.push(hk.key.length === 1 ? hk.key.toUpperCase() : hk.key)
  return parts.join(' + ')
}

export interface HotkeyHint {
  combo: string
  description: string
}

/**
 * 当前页面真实生效的快捷键清单，供 HotkeysDialog 渲染。
 *
 * 只收录声明了 description 的项——未写说明的快捷键属内部用途，
 * 展示一个没有解释的按键对用户没有意义。
 */
export function useActiveHotkeys(): ComputedRef<HotkeyHint[]> {
  return computed(() => {
    const seen = new Set<string>()
    const hints: HotkeyHint[] = []
    for (const group of activeGroups.value) {
      for (const hk of group) {
        if (!hk.description) continue
        const combo = formatCombo(hk)
        // 多个面板注册同一按键（如 KnowledgeDetail 的 '[' 与 KnowledgeEditor 的 '['）
        // 在同一时刻只会有一个挂载，但仍去重以防同页重复注册
        if (seen.has(combo)) continue
        seen.add(combo)
        hints.push({ combo, description: hk.description })
      }
    }
    return hints
  })
}

export const useHotkeys = (hotkeys: Hotkey[], options: { allowInInput?: boolean } = {}) => {
  const onKeydown = (e: KeyboardEvent) => {
    if (!options.allowInInput && isEditableTarget(e.target)) {
      // 输入框聚焦时不响应，避免干扰打字
      return
    }
    for (const hk of hotkeys) {
      if (e.key.toLowerCase() !== hk.key.toLowerCase()) continue
      if (!matchModifiers(e, hk)) continue
      hk.handler(e)
      break
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', onKeydown)
    activeGroups.value.push(hotkeys)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeydown)
    const idx = activeGroups.value.indexOf(hotkeys)
    if (idx !== -1) activeGroups.value.splice(idx, 1)
  })
}
