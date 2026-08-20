import { onBeforeUnmount, onMounted, watch, type Ref, type ComputedRef } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'

type DirtyRef = Ref<boolean> | ComputedRef<boolean>

interface Options {
  message?: string
  title?: string
  confirmText?: string
  cancelText?: string
  onDiscard?: () => void
}

export const useDirtyGuard = (isDirty: DirtyRef, options: Options = {}) => {
  const {
    message = '有未保存的修改，确认离开吗？',
    title = '离开确认',
    confirmText = '放弃修改',
    cancelText = '继续编辑',
    onDiscard
  } = options

  const beforeUnload = (e: BeforeUnloadEvent) => {
    if (!isDirty.value) return
    e.preventDefault()
    e.returnValue = ''
  }

  const bind = () => window.addEventListener('beforeunload', beforeUnload)
  const unbind = () => window.removeEventListener('beforeunload', beforeUnload)

  onMounted(bind)
  onBeforeUnmount(unbind)

  watch(isDirty, v => {
    if (!v) return
    bind()
  })

  onBeforeRouteLeave(async () => {
    if (!isDirty.value) return true
    try {
      await ElMessageBox.confirm(message, title, {
        type: 'warning',
        confirmButtonText: confirmText,
        cancelButtonText: cancelText
      })
      onDiscard?.()
      return true
    } catch {
      return false
    }
  })
}
