import { ref, watch, type Ref } from 'vue'
import { ElMessageBox } from 'element-plus'

import {
  deleteTicketAttachment,
  fetchAttachmentDownloadUrl,
  fetchTicketAttachments,
  uploadTicketAttachment,
  type TicketAttachmentMeta,
} from '@/api/tickets'
import { handleServerError, notify } from '@/utils/notify'

/**
 * 工单附件的加载、上传、下载与删除。
 *
 * ── 为什么它适合独立成 composable ─────────────────────────────
 * 附件是 `TicketDetail.vue` 里**交互面最窄、与其余逻辑耦合最少**的一块：
 * 它只依赖工单 ID 与当前用户名，不参与状态机、不写活动流、
 * 不影响闭环进度。搬出去几乎不需要回填任何上下文。
 *
 * ── 几个刻意的行为 ────────────────────────────────────────────
 * 1. **加载失败只降级不报错**。附件是辅助信息，拉不到时把列表清空即可，
 *    弹一个红色提示会让用户以为工单本身出了问题。
 *    而上传/删除失败必须提示——那是用户主动发起的动作，
 *    静默失败会让他以为已经成功。
 *
 * 2. **切换工单时立即清空**。`/tickets/:id` 是同一路由，切换 id 时
 *    组件实例被复用。不清空的话，从工单 A 跳到 B 的瞬间，
 *    B 的附件区会短暂显示 A 的附件——用户可能正好在那一刻点了下载。
 *
 * 3. **上传后就地追加，不整表重拉**。重拉多一次往返，且在慢网络下
 *    会出现「上传成功提示已弹、列表却还没更新」的空窗。
 */

export interface UseTicketAttachmentsOptions {
  /** 工单 ID。用 Ref——切换工单时要能感知 */
  ticketId: Ref<string>
  /** 上传者名字，取自登录态 */
  getOperator: () => string
}

export interface UseTicketAttachmentsReturn {
  attachments: Ref<TicketAttachmentMeta[]>
  attachmentsLoading: Ref<boolean>
  uploading: Ref<boolean>
  fileInputRef: Ref<HTMLInputElement | null>
  loadAttachments: () => Promise<void>
  pickAttachment: () => void
  onAttachmentSelected: (e: Event) => Promise<void>
  downloadAttachment: (item: TicketAttachmentMeta) => Promise<void>
  removeAttachment: (item: TicketAttachmentMeta) => Promise<void>
}

export function useTicketAttachments(
  options: UseTicketAttachmentsOptions
): UseTicketAttachmentsReturn {
  const { ticketId, getOperator } = options

  const attachments = ref<TicketAttachmentMeta[]>([])
  const attachmentsLoading = ref(false)
  const uploading = ref(false)
  const fileInputRef = ref<HTMLInputElement | null>(null)

  const loadAttachments = async () => {
    const id = ticketId.value
    if (!id) return
    attachmentsLoading.value = true
    try {
      attachments.value = await fetchTicketAttachments(id)
    } catch (e) {
      // 只降级不弹错：附件是辅助信息，拉不到不该让用户以为工单坏了
      console.warn('加载附件失败', e)
      attachments.value = []
    } finally {
      attachmentsLoading.value = false
    }
  }

  const pickAttachment = () => fileInputRef.value?.click()

  const onAttachmentSelected = async (e: Event) => {
    const input = e.target as HTMLInputElement
    const file = input.files?.[0]
    // 立刻清空 input：不清的话选同一个文件第二次不会触发 change 事件，
    // 用户会以为「点了没反应」
    input.value = ''
    const id = ticketId.value
    if (!file || !id) return

    uploading.value = true
    try {
      const saved = await uploadTicketAttachment(id, file, getOperator())
      // 就地追加而非重拉：省一次往返，也避免「提示已弹、列表未更新」的空窗
      attachments.value = [...attachments.value, saved]
      notify.success(`已上传 ${saved.originalName}`)
    } catch (err) {
      // 用户主动发起的动作，失败必须提示
      handleServerError(err, { action: '上传附件' })
    } finally {
      uploading.value = false
    }
  }

  const downloadAttachment = async (item: TicketAttachmentMeta) => {
    try {
      const url = await fetchAttachmentDownloadUrl(item.id)
      // noopener：新窗口拿不到 window.opener，防止下载页反向操作本页
      window.open(url, '_blank', 'noopener')
    } catch (err) {
      handleServerError(err, { action: '获取下载链接' })
    }
  }

  const removeAttachment = async (item: TicketAttachmentMeta) => {
    try {
      await ElMessageBox.confirm(
        `确定删除附件「${item.originalName}」？`,
        '删除附件',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return   // 用户取消
    }
    try {
      await deleteTicketAttachment(item.id)
      attachments.value = attachments.value.filter(a => a.id !== item.id)
      notify.success('附件已删除')
    } catch (err) {
      handleServerError(err, { action: '删除附件' })
    }
  }

  /**
   * 切换工单时立即清空再重载。
   *
   * 不清空的话，从工单 A 跳到 B 的瞬间 B 的附件区会短暂显示 A 的附件——
   * 用户可能正好在那一刻点了下载，拿到的是另一张单的文件。
   */
  watch(ticketId, () => {
    attachments.value = []
    void loadAttachments()
  })

  return {
    attachments,
    attachmentsLoading,
    uploading,
    fileInputRef,
    loadAttachments,
    pickAttachment,
    onAttachmentSelected,
    downloadAttachment,
    removeAttachment,
  }
}
