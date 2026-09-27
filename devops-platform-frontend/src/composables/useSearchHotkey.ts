import type { Ref } from 'vue'
import { useHotkeys } from '@/composables/useHotkeys'

/**
 * 检索型列表页的搜索快捷键（2026-09-27，知识库/工单列表共用）。
 *
 * 两个键，全是「少一次鼠标动作」：
 * - `/` 聚焦搜索框（GitHub/GitLab/Stripe 同款行业惯例）；
 * - 输入框内 `Esc` 清空并失焦（交给模板 @keydown.esc 绑定，
 *   因为 useHotkeys 刻意不拦截输入框内的按键）。
 *
 * 返回值里的 onSearchEsc 绑到输入框的 @keydown.esc。
 */
export function useSearchHotkey(
  inputRef: Ref<HTMLInputElement | null>,
  onClear?: () => void,
) {
  useHotkeys([
    { key: '/', description: '聚焦搜索框', handler: () => inputRef.value?.focus() },
  ])

  const onSearchEsc = (e: KeyboardEvent) => {
    onClear?.()
    ;(e.target as HTMLInputElement).blur()
  }

  return { onSearchEsc }
}
