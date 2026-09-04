/**
 * 工单列表的批量操作：选中集合、批量改状态、批量转派、批量删除（可撤销）。
 *
 * 从 `TicketList.vue` 抽出。选择它作为本轮拆分对象的理由是
 * **它已有 13 例测试兜底**——没有测试的重构等于把缺陷原样搬进新文件，
 * 有了测试才能确认「搬完之后行为没变」。
 *
 * <h3>这块的共同风险：一次影响几十张工单，而失败是常态</h3>
 * 批量操作的正确性不在「成功时对不对」，而在<b>部分失败时说没说实话</b>：
 * <ul>
 *   <li>勾 20 张改「已关闭」，其中 8 张处于 pending 根本不允许直接关闭
 *       （状态机要求先 resolved）。若把 20 条全打给后端，
 *       用户只会收到一句「12/20 成功」——<b>既不知道是哪 8 条，也不知道为什么</b>，
 *       只能一张张点开看。所以可达性必须在<b>点之前</b>就算出来并标在选项上；</li>
 *   <li>勾 3 张转派成功 1 张，若一律弹「已分配」，用户以为全办完了，
 *       <b>剩下两张就此无人认领</b>，不会有任何地方再提醒他；</li>
 *   <li>删除后不重新拉取，当前页会少几行（本该由下一页记录补齐）且 total 是旧值。</li>
 * </ul>
 *
 * <h3>为什么每次批量操作后都要 fetchList</h3>
 * 除了分页数量对不上，更隐蔽的是 <b>version 乐观锁</b>：
 * 批量改状态/转派会让后端 version 自增，前端不刷新的话，
 * 用户接着编辑其中一张就会误报「数据已被他人修改」，而他确实没被人改过。
 */
import { computed, ref, type Ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { notify } from '@/utils/notify'
import { showUndoToast } from '@/utils/undoToast'
import { TICKET_STATUS_OPTIONS, canTransitionStatus } from '@/constants/ticket'
import { getStatusLabel, type Ticket, type TicketStatus } from '@/stores/tickets'

/** 批量操作依赖的 store 能力（只声明用到的部分，便于测试替身） */
export interface BulkActionStore {
  tickets: Ticket[]
  bulkDelete: (ids: string[]) => Promise<Array<{ ticket: Ticket; index: number }>>
  bulkRestore: (snaps: Array<{ ticket: Ticket; index: number }>) => Promise<number>
  bulkUpdateStatus: (ids: string[], status: TicketStatus) => Promise<number>
  bulkAssign: (ids: string[], assignee: string) => Promise<number>
}

export interface UseTicketBulkActionsOptions {
  store: BulkActionStore
  /** 页码 ref：删空当前页时需要回退 */
  currentPage: Ref<number>
  fetchList: () => Promise<void> | void
  /**
   * 关闭本模块之外的其他浮层（如列设置面板）。
   *
   * 「点击空白处关闭下拉」是页面级行为，而列设置面板归 useTicketColumns 管。
   * 用回调而非让本模块直接持有那个 ref，避免两个 composable 互相引用。
   */
  onCloseOtherMenus?: () => void
}

export function useTicketBulkActions(options: UseTicketBulkActionsOptions) {
  const { store, currentPage, fetchList, onCloseOtherMenus } = options

  /** 当前选中的工单 ID */
  const selectedIds = ref<string[]>([])

  const bulkStatusOpen = ref(false)
  const bulkAssignOpen = ref(false)

  /** 当前选中的工单实体（批量操作需要读它们的状态做流转校验） */
  const selectedTickets = computed(() =>
    store.tickets.filter(t => selectedIds.value.includes(t.id))
  )

  /**
   * 批量状态选项 —— 按选中工单的**实际状态**计算可达性。
   *
   * 早先实现直接铺 TICKET_STATUS_OPTIONS 全量五项，完全绕过状态机，
   * 把注定失败的请求也打给后端（详见文件头注释）。
   *
   * 改为：只要选中项里**没有任何一张**能走到目标状态，就整项置灰；
   * 部分可达时保留可点但在标签上标出「N/M 可执行」，让用户点之前
   * 就知道这次会影响多少张。
   */
  const bulkStatusOptions = computed(() =>
    TICKET_STATUS_OPTIONS.map(opt => {
      const applicable = selectedTickets.value.filter(
        t => t.status !== opt.value && canTransitionStatus(t.status, opt.value)
      ).length
      const total = selectedTickets.value.length
      return {
        ...opt,
        applicable,
        disabled: applicable === 0,
        hint:
          applicable === 0
            ? `选中的工单都不能变更为「${opt.label}」`
            : applicable < total
              ? `${applicable}/${total} 条可执行，其余状态不允许`
              : '',
      }
    })
  )

  /**
   * 卡片视图的单选切换。
   *
   * 列表视图的全选/单选由 el-table 的 selection 列接管
   * （onSelectionChange 同步到 selectedIds）；卡片视图无表格，仍需手动维护。
   */
  function toggleSelect(id: string) {
    const idx = selectedIds.value.indexOf(id)
    if (idx >= 0) selectedIds.value.splice(idx, 1)
    else selectedIds.value.push(id)
  }

  /** el-table 选择变化 → 同步到 selectedIds */
  function onSelectionChange(rows: Ticket[]) {
    selectedIds.value = rows.map(r => r.id)
  }

  /** 点击空白处关闭批量下拉与列设置面板 */
  function closeBulkMenus(e: MouseEvent) {
    const target = e.target as HTMLElement
    if (!target.closest('.bulk-dropdown')) {
      bulkStatusOpen.value = false
      bulkAssignOpen.value = false
    }
    // 列设置面板同样点击外部关闭，否则会一直挡住表头
    if (!target.closest('.col-setting-wrap')) {
      onCloseOtherMenus?.()
    }
  }

  /** 批量删除（5 秒内可撤销） */
  function bulkDelete() {
    const count = selectedIds.value.length
    if (count === 0) return
    ElMessageBox.confirm(
      `确认删除选中的 ${count} 条工单？删除后 5 秒内可撤销。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
      .then(async () => {
        const ids = [...selectedIds.value]
        selectedIds.value = []
        const snaps = await store.bulkDelete(ids)
        if (snaps.length === 0) {
          notify.warning('没有可删除的工单')
          return
        }

        // 分页在服务端，删除后须重新拉取：
        // 否则当前页会少几行（本该由下一页记录补齐），且 total 仍是旧值。
        // 若当前页已空且非首页，先退一页再拉，避免停在空白页。
        if (store.tickets.length === 0 && currentPage.value > 1) {
          currentPage.value -= 1
        }
        await fetchList()

        // 部分失败时如实告知
        const failedCount = ids.length - snaps.length
        showUndoToast({
          message:
            failedCount > 0
              ? `已删除 ${snaps.length} 条，${failedCount} 条失败`
              : `已删除 ${snaps.length} 条工单`,
          duration: 5000,
          onUndo: async () => {
            // 后端不支持指定 ID 插入，恢复会得到新工单号
            const ok = await store.bulkRestore(snaps)
            await fetchList() // 恢复的是新工单号，须重新拉取才能看到
            notify.success(`已恢复 ${ok} 条工单（工单号已重新生成）`)
          },
        })
      })
      .catch(() => {})
  }

  /** 批量变更状态（只对状态机允许的执行） */
  async function applyBulkStatus(s: TicketStatus) {
    // 只对状态机允许的那些执行，不把注定失败的请求打给后端
    const targets = selectedTickets.value.filter(
      t => t.status !== s && canTransitionStatus(t.status, s)
    )
    const skipped = selectedIds.value.length - targets.length
    if (targets.length === 0) {
      notify.warning(`选中的工单都不能变更为「${getStatusLabel(s)}」`)
      bulkStatusOpen.value = false
      return
    }

    const count = targets.length
    const ids = targets.map(t => t.id)
    selectedIds.value = []
    bulkStatusOpen.value = false

    const ok = await store.bulkUpdateStatus(ids, s)

    // 重新拉取：若当前筛选含状态条件，改完的工单应移出结果集；
    // 且这些工单的 version 已在后端自增，不刷新会导致后续编辑误报冲突
    await fetchList()

    // 跳过的条数要如实说明原因，否则用户以为自己勾的没生效
    const skipNote = skipped > 0 ? `（${skipped} 条因状态不允许已跳过）` : ''
    if (ok === count) {
      notify.success(`已将 ${count} 条工单状态更新为「${getStatusLabel(s)}」${skipNote}`)
    } else {
      notify.warning(`${ok}/${count} 条更新成功，其余失败${skipNote}`)
    }
  }

  /** 批量转派 */
  async function applyBulkAssign(name: string) {
    const count = selectedIds.value.length
    if (count === 0) return
    const ids = [...selectedIds.value]
    selectedIds.value = []
    bulkAssignOpen.value = false

    const ok = await store.bulkAssign(ids, name)

    // 重新拉取：若筛选含负责人条件，改完的工单应移出结果集；
    // 且转派会自增 version，不刷新会导致后续编辑误报冲突
    await fetchList()

    if (ok === count) {
      notify.success(`已将 ${count} 条工单分配给「${name}」`)
    } else {
      notify.warning(`${ok}/${count} 条分配成功，其余失败`)
    }
  }

  return {
    selectedIds,
    selectedTickets,
    bulkStatusOpen,
    bulkAssignOpen,
    bulkStatusOptions,
    toggleSelect,
    onSelectionChange,
    closeBulkMenus,
    bulkDelete,
    applyBulkStatus,
    applyBulkAssign,
  }
}
