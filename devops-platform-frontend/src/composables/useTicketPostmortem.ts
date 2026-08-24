import { ref } from 'vue'
import { ElMessage } from 'element-plus'

import {
  addActionItem,
  generateTimelineDraft,
  getPostmortem,
  listActionItems,
  savePostmortem,
  updateActionItem,
  type ActionItemData,
  type PostmortemData,
} from '@/api/tickets'
import { handleServerError } from '@/utils/notify'

/**
 * B4 复盘归档逻辑。
 *
 * 从 TicketDetail 抽出（该文件近 2900 行，复盘是其中交互面最窄、
 * 最独立的一块：自有表单状态 + 改进项 CRUD，与工单主体仅通过 ticketId
 * 和「保存后刷新活动流」两点耦合）。
 *
 * @param getTicketId 返回当前工单号；无工单时返回 null
 * @param getOperator 返回当前操作人名称（写入复盘的 changedBy）
 * @param onSaved     复盘保存成功后的回调，用于刷新活动流
 */
export function useTicketPostmortem(options: {
  getTicketId: () => string | null
  getOperator: () => string
  onSaved?: (ticketId: string) => void | Promise<void>
}) {
  const postmortem = ref<PostmortemData | null>(null)
  const postmortemLoading = ref(false)
  const drawerVisible = ref(false)
  const form = ref({
    timeline: '',
    impactScope: '',
    impactDuration: null as number | null | undefined,
    lessons: '',
  })
  const actionItems = ref<ActionItemData[]>([])
  const saving = ref(false)
  const newActionItem = ref({ content: '', owner: '', dueDate: '' })

  const load = async () => {
    const ticketId = options.getTicketId()
    if (!ticketId) return
    postmortemLoading.value = true
    try {
      const pm = await getPostmortem(ticketId)
      postmortem.value = pm
      if (pm) {
        form.value = {
          timeline: pm.timeline || '',
          impactScope: pm.impactScope || '',
          impactDuration: pm.impactDuration,
          lessons: pm.lessons || '',
        }
        // 只保留当前工单的改进项。
        //
        // listActionItems() 返回全系统改进项（ActionItemBoard 看板需要跨工单
        // 全量，故接口不按工单过滤）。此处若不过滤，抽屉会把别的工单的改进项
        // 显示成本工单的——属「把 A 的数据当作 B 的呈现」（同 6.39 家族）。
        const all = await listActionItems()
        actionItems.value = all.filter(item => item.ticketId === ticketId)
      }
    } catch (e) {
      console.warn('加载复盘失败', e)
    } finally {
      postmortemLoading.value = false
    }
  }

  const open = async () => {
    drawerVisible.value = true
    if (postmortem.value) return

    await load()
    if (postmortem.value) return

    // 首次打开且尚无复盘记录：自动生成时间线草稿。
    // 失败降级为空表单——草稿是便利功能，不该阻塞用户手工填写
    const ticketId = options.getTicketId()
    if (!ticketId) return
    try {
      form.value.timeline = await generateTimelineDraft(ticketId)
    } catch (e) {
      console.warn('[Postmortem] 首次打开生成草稿失败，留空供手工填写', e)
    }
  }

  const generateDraft = async () => {
    const ticketId = options.getTicketId()
    if (!ticketId) return
    try {
      form.value.timeline = await generateTimelineDraft(ticketId)
      ElMessage.success('时间线草稿已生成')
    } catch (e) {
      console.warn('[Postmortem] 时间线草稿生成失败', e)
      ElMessage.error('草稿生成失败')
    }
  }

  const save = async () => {
    const ticketId = options.getTicketId()
    if (!ticketId || saving.value) return
    saving.value = true
    try {
      postmortem.value = await savePostmortem(
        ticketId,
        {
          ticketId,
          timeline: form.value.timeline,
          impactScope: form.value.impactScope,
          impactDuration: form.value.impactDuration,
          lessons: form.value.lessons,
        },
        options.getOperator()
      )
      await options.onSaved?.(ticketId)
      ElMessage.success('复盘已保存')
    } catch (e) {
      handleServerError(e, { action: '保存复盘' })
    } finally {
      saving.value = false
    }
  }

  const addItem = async () => {
    const ticketId = options.getTicketId()
    const postmortemId = postmortem.value?.id
    // 改进项挂在复盘记录下，复盘未保存时没有可挂载的 postmortemId
    if (!ticketId || !postmortemId) return
    if (!newActionItem.value.content.trim()) {
      ElMessage.warning('改进项内容不能为空')
      return
    }
    try {
      const item = await addActionItem(ticketId, {
        postmortemId,
        content: newActionItem.value.content.trim(),
        owner: newActionItem.value.owner.trim() || undefined,
        dueDate: newActionItem.value.dueDate || undefined,
      })
      actionItems.value = [...actionItems.value, item]
      newActionItem.value = { content: '', owner: '', dueDate: '' }
      ElMessage.success('改进项已添加')
    } catch (e) {
      handleServerError(e, { action: '添加改进项' })
    }
  }

  const updateItemStatus = async (itemId: number, status: string) => {
    try {
      await updateActionItem(itemId, status)
      actionItems.value = actionItems.value.map(i =>
        i.id === itemId ? { ...i, status } : i
      )
      ElMessage.success('状态已更新')
    } catch (e) {
      handleServerError(e, { action: '更新改进项状态' })
    }
  }

  /** 切换工单时重置——否则上一张工单的复盘内容会残留（同 6.39） */
  const reset = () => {
    postmortem.value = null
    actionItems.value = []
    form.value = { timeline: '', impactScope: '', impactDuration: null, lessons: '' }
    newActionItem.value = { content: '', owner: '', dueDate: '' }
    drawerVisible.value = false
  }

  return {
    postmortem,
    postmortemLoading,
    drawerVisible,
    form,
    actionItems,
    saving,
    newActionItem,
    load,
    open,
    generateDraft,
    save,
    addItem,
    updateItemStatus,
    reset,
  }
}
