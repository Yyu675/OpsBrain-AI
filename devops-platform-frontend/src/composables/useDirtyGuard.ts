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
  /**
   * 点确认按钮后、离开前执行。用于「暂存后离开」这类需要落地副作用的确认路径——
   * 只返回 true 放行会让按钮文案（如「本机暂存并离开」）承诺了却没做。
   */
  onConfirm?: () => void
  /**
   * 提供此项时对话框改为**三选一**（否则为默认的两选一）：
   *
   * - 确认按钮（`confirmText`）→ 离开，草稿保留
   * - 本按钮（`discardText`）→ 调 `onDiscard` 丢弃草稿后离开
   * - 关闭 / Esc → 留在当前页
   *
   * 用于「草稿已自动暂存」的场景（如知识文档编辑器）：此时「离开」与「丢弃」
   * 是两件不同的事，两选一无法表达——用户既可能想稍后回来继续，
   * 也可能想彻底丢掉这次编辑。
   */
  discardText?: string
}

export const useDirtyGuard = (isDirty: DirtyRef, options: Options = {}) => {
  const {
    message = '有未保存的修改，确认离开吗？',
    title = '离开确认',
    confirmText = '放弃修改',
    cancelText = '继续编辑',
    onDiscard,
    onConfirm,
    discardText
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

    // 三选一模式：离开 / 丢弃后离开 / 留下（见 discardText 注释）
    if (discardText) {
      try {
        await ElMessageBox.confirm(message, title, {
          type: 'warning',
          confirmButtonText: confirmText,
          cancelButtonText: discardText,
          // 必须开启：否则「取消」与「关闭(Esc/×)」都 reject 且无从区分，
          // 「丢弃」与「留在本页」两个语义会混成一个
          distinguishCancelAndClose: true
        })
        onConfirm?.()
        return true
      } catch (action) {
        if (action === 'cancel') {
          onDiscard?.()
          return true
        }
        // 关闭 / Esc → 继续编辑
        return false
      }
    }

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
