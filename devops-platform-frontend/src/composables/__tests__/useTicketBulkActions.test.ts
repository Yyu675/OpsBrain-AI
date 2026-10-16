/**
 * 批量操作 composable 直接单测。
 *
 * ── 与 TicketList.bulk.test.ts 的分工 ──────────────────────────
 * 那 13 例是**通过页面**验证的，覆盖得不错。但它们绑在 `TicketList.vue` 上：
 * 一旦本 composable 被别处复用（工单看板、告警批量处置都可能用到批量选择），
 * 那组用例保护不到新的调用方。
 *
 * 这里直接对 composable 建立契约，不挂载任何组件。
 * `BulkActionStore` 已经是为可测性抽出的窄接口，用普通对象当替身即可，
 * 不需要 Pinia。
 *
 * ── 守什么 ────────────────────────────────────────────────────
 * 1. **状态机可达性**——批量改状态前按每张工单的实际状态算可达数，
 *    而不是把注定失败的请求打给后端；
 * 2. **部分成功如实上报**——勾 3 张成功 1 张却弹「已完成」，
 *    用户会以为全办完了，剩下两张就此无人认领；
 * 3. **操作后必须 fetchList**——不只是分页数量，更隐蔽的是 version 乐观锁：
 *    不刷新的话用户接着编辑会误报「数据已被他人修改」，而他确实没被人改过。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'

const notifyMock = vi.hoisted(() => ({
  notify: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  handleServerError: vi.fn(),
}))
vi.mock('@/utils/notify', () => notifyMock)

const msgBox = vi.hoisted(() => ({ confirm: vi.fn(() => Promise.resolve('confirm')), prompt: vi.fn() }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('element-plus')
  return { ...actual, ElMessageBox: msgBox }
})

const undoMock = vi.hoisted(() => ({ showUndoToast: vi.fn() }))
vi.mock('@/utils/undoToast', () => undoMock)

import { useTicketBulkActions, type BulkActionStore } from '../useTicketBulkActions'
import type { Ticket } from '@/stores/tickets'

const makeTicket = (id: string, status: string): Ticket =>
  ({ id, title: `工单 ${id}`, status, priority: 'medium', assignee: '张三' }) as unknown as Ticket

function setup(tickets: Ticket[]) {
  const store: BulkActionStore = {
    tickets,
    bulkDelete: vi.fn(async (ids: string[]) =>
      ids.map((id, i) => ({ ticket: makeTicket(id, 'pending'), index: i }))
    ),
    bulkRestore: vi.fn(async () => 0),
    bulkUpdateStatus: vi.fn(async (ids: string[]) => ids.length),
    bulkAssign: vi.fn(async (ids: string[]) => ids.length),
  }
  const currentPage = ref(1)
  const fetchList = vi.fn()
  const onCloseOtherMenus = vi.fn()
  const api = useTicketBulkActions({ store, currentPage, fetchList, onCloseOtherMenus })
  return { ...api, store, currentPage, fetchList, onCloseOtherMenus }
}

beforeEach(() => {
  vi.clearAllMocks()
  msgBox.confirm.mockResolvedValue('confirm')
})

describe('选择', () => {
  it('toggleSelect 选中与取消', () => {
    const b = setup([makeTicket('A', 'pending')])

    b.toggleSelect('A')
    expect(b.selectedIds.value).toEqual(['A'])

    b.toggleSelect('A')
    expect(b.selectedIds.value).toEqual([])
  })

  it('selectedTickets 只包含真实存在的工单', () => {
    const b = setup([makeTicket('A', 'pending'), makeTicket('B', 'processing')])
    b.selectedIds.value = ['A', 'B', '不存在的ID']

    // 翻页后旧的选中 ID 可能已不在当前列表里。
    // 不过滤的话后续状态机校验会拿 undefined 去读 .status 而崩
    expect(b.selectedTickets.value.map(t => t.id)).toEqual(['A', 'B'])
  })

  it('onSelectionChange 用表格给的行整体替换选中集', () => {
    const b = setup([makeTicket('A', 'pending'), makeTicket('B', 'pending')])
    b.selectedIds.value = ['A']

    b.onSelectionChange([makeTicket('B', 'pending')])

    // 必须是替换而非合并：el-table 传的就是当前完整选中集，
    // 合并会让用户取消勾选后 ID 还留在集合里
    expect(b.selectedIds.value).toEqual(['B'])
  })
})

describe('批量状态选项的可达性', () => {
  it('全部不可达时置灰', () => {
    // void 是不可逆终态
    const b = setup([makeTicket('A', 'void')])
    b.selectedIds.value = ['A']

    const opts = b.bulkStatusOptions.value
    expect(opts.every(o => o.disabled)).toBe(true)
  })

  it('已是目标状态的不计入 applicable——不发无意义的同态请求', () => {
    const b = setup([makeTicket('A', 'pending'), makeTicket('B', 'pending')])
    b.selectedIds.value = ['A', 'B']

    const toPending = b.bulkStatusOptions.value.find(o => o.value === 'pending')!
    // 两张都已是 pending，改成 pending 等于什么都不做
    expect(toPending.applicable).toBe(0)
    expect(toPending.disabled).toBe(true)
  })

  it('部分可达时保留可点，并给出 N/M 计数', () => {
    const b = setup([makeTicket('A', 'pending'), makeTicket('B', 'void')])
    b.selectedIds.value = ['A', 'B']

    const opts = b.bulkStatusOptions.value.filter(o => !o.disabled)
    expect(opts.length).toBeGreaterThan(0)
    // 用户点之前就该知道这次会影响几张，而不是点完才发现只动了一张
    expect(opts[0].applicable).toBeLessThan(2)
    expect(opts[0].hint).toBeTruthy()
  })

  it('未选中任何工单时全部置灰', () => {
    const b = setup([makeTicket('A', 'pending')])

    expect(b.bulkStatusOptions.value.every(o => o.disabled)).toBe(true)
  })
})

describe('批量改状态', () => {
  it('只把可达的工单 ID 发给后端', async () => {
    const b = setup([makeTicket('A', 'pending'), makeTicket('B', 'void')])
    b.selectedIds.value = ['A', 'B']

    await b.applyBulkStatus('processing')

    const ids = (b.store.bulkUpdateStatus as ReturnType<typeof vi.fn>).mock.calls[0][0]
    // void 不可流转，带上它只会让后端返回一个注定的失败
    expect(ids).toEqual(['A'])
  })

  it('全部不可达时不调后端，直接提示', async () => {
    const b = setup([makeTicket('A', 'void')])
    b.selectedIds.value = ['A']

    await b.applyBulkStatus('processing')

    expect(b.store.bulkUpdateStatus).not.toHaveBeenCalled()
    expect(notifyMock.notify.warning).toHaveBeenCalled()
  })

  it('有跳过项时提示如实说明——否则用户以为自己勾的没生效', async () => {
    const b = setup([makeTicket('A', 'pending'), makeTicket('B', 'void')])
    b.selectedIds.value = ['A', 'B']
    ;(b.store.bulkUpdateStatus as ReturnType<typeof vi.fn>).mockResolvedValue(1)

    await b.applyBulkStatus('processing')

    const msgs = [
      ...notifyMock.notify.success.mock.calls,
      ...notifyMock.notify.warning.mock.calls,
    ].map(c => String(c[0]))
    expect(msgs.join(' ')).toMatch(/1|跳过|已跳过/)
  })

  it('操作后重新拉取——version 自增，不刷新会误报编辑冲突', async () => {
    const b = setup([makeTicket('A', 'pending')])
    b.selectedIds.value = ['A']

    await b.applyBulkStatus('processing')

    expect(b.fetchList).toHaveBeenCalled()
  })

  it('操作后清空选中，避免对着已处理的选区再点一次', async () => {
    const b = setup([makeTicket('A', 'pending')])
    b.selectedIds.value = ['A']

    await b.applyBulkStatus('processing')

    expect(b.selectedIds.value).toEqual([])
  })
})

describe('批量转派', () => {
  it('全部成功时提示总数与负责人', async () => {
    const b = setup([makeTicket('A', 'pending'), makeTicket('B', 'pending')])
    b.selectedIds.value = ['A', 'B']

    await b.applyBulkAssign('王芳')

    expect(notifyMock.notify.success).toHaveBeenCalledWith(expect.stringContaining('王芳'))
    expect(notifyMock.notify.warning).not.toHaveBeenCalled()
  })

  it('部分失败时给 N/M 计数，而不是笼统的「已分配」', async () => {
    const b = setup([makeTicket('A', 'pending'), makeTicket('B', 'pending'), makeTicket('C', 'pending')])
    b.selectedIds.value = ['A', 'B', 'C']
    ;(b.store.bulkAssign as ReturnType<typeof vi.fn>).mockResolvedValue(1)

    await b.applyBulkAssign('王芳')

    // 报「已分配 3 条」会让用户以为全办完，剩下两张就此无人认领
    expect(notifyMock.notify.success).not.toHaveBeenCalled()
    const msg = String(notifyMock.notify.warning.mock.calls.at(-1)?.[0] ?? '')
    expect(msg).toContain('1')
    expect(msg).toContain('3')
  })

  it('未选中时直接返回，不打后端也不提示', async () => {
    const b = setup([makeTicket('A', 'pending')])

    await b.applyBulkAssign('王芳')

    expect(b.store.bulkAssign).not.toHaveBeenCalled()
    expect(notifyMock.notify.success).not.toHaveBeenCalled()
  })

  it('操作后关闭下拉并清空选中', async () => {
    const b = setup([makeTicket('A', 'pending')])
    b.selectedIds.value = ['A']
    b.bulkAssignOpen.value = true

    await b.applyBulkAssign('王芳')

    expect(b.bulkAssignOpen.value).toBe(false)
    expect(b.selectedIds.value).toEqual([])
  })
})

describe('批量删除', () => {
  it('二次确认后才删', async () => {
    const b = setup([makeTicket('A', 'pending')])
    b.selectedIds.value = ['A']

    await b.bulkDelete()

    expect(msgBox.confirm).toHaveBeenCalled()
    expect(b.store.bulkDelete).toHaveBeenCalled()
  })

  it('取消确认时不删——点了「取消」却照样删是最严重的一类缺陷', async () => {
    msgBox.confirm.mockRejectedValue(new Error('cancel'))
    const b = setup([makeTicket('A', 'pending')])
    b.selectedIds.value = ['A']

    await b.bulkDelete()

    expect(b.store.bulkDelete).not.toHaveBeenCalled()
  })

  it('未选中时不弹确认框', async () => {
    const b = setup([makeTicket('A', 'pending')])

    await b.bulkDelete()

    expect(msgBox.confirm).not.toHaveBeenCalled()
  })

  it('删除后提供撤销入口——物理删除不可逆，必须给后悔药', async () => {
    const b = setup([makeTicket('A', 'pending')])
    b.selectedIds.value = ['A']

    b.bulkDelete()
    // bulkDelete 返回 void，真正的删除挂在 ElMessageBox.confirm 的 .then() 里。
    // 直接 await 它只是 await undefined，等不到那条链——必须排空微任务队列。
    await flushPromises()

    expect(undoMock.showUndoToast).toHaveBeenCalled()
  })
})

describe('浮层互斥', () => {
  it('点击非下拉区域时关闭批量菜单，并通知关闭其他浮层', () => {
    const b = setup([makeTicket('A', 'pending')])
    b.bulkStatusOpen.value = true
    b.bulkAssignOpen.value = true

    const outside = document.createElement('div')
    document.body.appendChild(outside)
    b.closeBulkMenus({ target: outside } as unknown as MouseEvent)

    expect(b.bulkStatusOpen.value).toBe(false)
    expect(b.bulkAssignOpen.value).toBe(false)
    // 列设置面板归 useTicketColumns 管，用回调通知而非直接持有它的 ref，
    // 避免两个 composable 互相引用
    expect(b.onCloseOtherMenus).toHaveBeenCalled()
  })

  it('点击下拉内部时不关闭', () => {
    const b = setup([makeTicket('A', 'pending')])
    b.bulkStatusOpen.value = true

    const inside = document.createElement('div')
    inside.className = 'bulk-dropdown'
    const child = document.createElement('span')
    inside.appendChild(child)
    document.body.appendChild(inside)

    b.closeBulkMenus({ target: child } as unknown as MouseEvent)

    // 点菜单里的选项就把菜单关掉的话，用户根本选不中
    expect(b.bulkStatusOpen.value).toBe(true)
  })
})
