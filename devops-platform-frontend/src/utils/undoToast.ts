import { h } from 'vue'
import { ElNotification } from 'element-plus'
import type { NotificationHandle } from 'element-plus'

interface UndoOptions {
  message: string
  duration?: number
  onUndo: () => void
  onCommit?: () => void
}

export const showUndoToast = ({ message, duration = 5000, onUndo, onCommit }: UndoOptions) => {
  let handled = false
  let handle: NotificationHandle | null = null

  const doUndo = () => {
    if (handled) return
    handled = true
    onUndo()
    handle?.close()
  }

  handle = ElNotification({
    title: '',
    position: 'bottom-right',
    duration,
    showClose: true,
    dangerouslyUseHTMLString: false,
    message: h('div', { style: 'display:flex;align-items:center;gap:12px' }, [
      h('span', { style: 'flex:1' }, message),
      h(
        'button',
        {
          onClick: doUndo,
          style: [
            'border:none',
            'background:transparent',
            'color:var(--color-primary)',
            'font-weight:600',
            'font-size:13px',
            'cursor:pointer',
            'padding:2px 6px',
            'border-radius:4px'
          ].join(';')
        },
        '撤销'
      )
    ]),
    onClose: () => {
      if (!handled) {
        handled = true
        onCommit?.()
      }
    }
  })

  return { close: () => handle?.close() }
}
