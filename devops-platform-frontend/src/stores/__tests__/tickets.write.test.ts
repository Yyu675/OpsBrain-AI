/**
 * tickets store 写方法测试（步骤 0：Query 迁移前置保护网）。
 *
 * 为何优先做这个：这 12 个写方法里的乐观更新 + 回滚，正是 CLAUDE.md 中
 * **已经坏过四次**的地方——
 *   6.9  写操作只改 Pinia 内存，刷新即丢失
 *   6.16 状态变更后漏同步 version，接着编辑必然误报 40009 冲突
 *   6.17 分页下沉后写操作仍只改本地数组，total 与页内行数失准
 *   6.18 把服务端分页的一页数据持久化，恢复后与 total 矛盾
 * 而 store 此前零测试。这套用例既是现状的行为基准（迁移 Query 时对照它，
 * 行为不变才算成功），也是这四类缺陷的回归防线。
 *
 * mock 边界：只 mock api 层与 UI 提示，store 自身逻辑全部真实执行——
 * 被测对象就是 store 的状态管理，mock 掉它等于什么都没测。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useTicketsStore, type TicketReply } from '../tickets'
import type { FrontendTicket } from '@/api/types/ticket'

const api = vi.hoisted(() => ({
  fetchTickets: vi.fn(),
  fetchTicketById: vi.fn(),
  fetchTicketStats: vi.fn(),
  createTicket: vi.fn(),
  updateTicket: vi.fn(),
  updateTicketStatus: vi.fn(),
  transferTicket: vi.fn(),
  deleteTicket: vi.fn(),
  addTicketReply: vi.fn(),
  fetchTicketReplies: vi.fn(),
  fetchTicketActivities: vi.fn(),
  replaceTicketTags: vi.fn(),
  fetchHotTags: vi.fn(),
}))

const notify = vi.hoisted(() => ({ handleServerError: vi.fn() }))

vi.mock('@/api/tickets', () => api)
vi.mock('@/api/users', () => ({ fetchTeamMembers: vi.fn().mockResolvedValue([]) }))
vi.mock('@/utils/notify', () => notify)
vi.mock('element-plus', () => ({
  ElMessage: Object.assign(vi.fn(), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
}))

/**
 * 版本冲突错误：结构对齐 api/services/ticket.service.ts 的 VersionConflictError。
 *
 * 必须放进 vi.hoisted —— vi.mock 的工厂会被提升到文件顶部执行，
 * 普通 class 声明此时尚未初始化，直接引用会抛
 * 「Cannot access before initialization」。
 */
const errors = vi.hoisted(() => {
  class FakeVersionConflictError extends Error {
    readonly isVersionConflict = true
    constructor(message = '该记录已被他人修改') {
      super(message)
      this.name = 'VersionConflictError'
    }
  }
  return { FakeVersionConflictError }
})

vi.mock('@/api/services/ticket.service', () => ({
  VersionConflictError: errors.FakeVersionConflictError,
}))

function ticket(overrides: Partial<FrontendTicket> = {}): FrontendTicket {
  return {
    id: 'TKT-A',
    title: '生产库连接池打满',
    description: '',
    status: 'pending',
    priority: 'high',
    assignee: '张明',
    creator: 'admin',
    createdAt: '2026-08-23 10:00',
    updatedAt: '2026-08-23 10:00',
    service: '生产环境-MySQL',
    category: '数据库',
    tags: [],
    sla: '4h 响应 / 8h 解决',
    slaProgress: 20,
    slaBreached: false,
    slaRemainingMinutes: 300,
    firstResponseState: 'WAITING',
    firstResponseMinutes: null,
    responseRemainingMinutes: 25,
    firstResponder: null,
    escalateReason: null,
    handlingStage: null,
    mitigatedAt: null,
    rootCauseAt: null,
    verifiedAt: null,
    verifySkipped: false,
    rootCauseCategory: null,
    rootCause: null,
    rootCauseBy: null,
    verifier: null,
    verifyMethod: null,
    verifyConclusion: null,
    verifySkipReason: null,
    attachments: [],
    replies: [],
    activities: [],
    version: 0,
    ...overrides,
  } as FrontendTicket
}

/** 建一个已装载若干工单的 store */
function storeWith(...items: FrontendTicket[]) {
  const store = useTicketsStore()
  items.forEach(t => store.addTicket(t))
  return store
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(api).forEach(m => m.mockReset())
  notify.handleServerError.mockReset()

  // 这些辅助拉取在多数写路径末尾被调用，给出默认返回避免用例反复声明
  api.fetchTicketStats.mockResolvedValue({
    byStatus: {}, todayNew: 0, byPriority: {}, urgentPending: 0,
  })
  api.fetchTicketActivities.mockResolvedValue([])
  api.fetchTicketReplies.mockResolvedValue([])
  api.fetchHotTags.mockResolvedValue([])
  api.fetchTicketById.mockResolvedValue(null)
  // 形状对齐 api/services/ticket.service.ts 的 fetchTickets 返回值
  // （{ tickets, total, page, size, totalPages }，不是分页信封的 content/totalElements）
  api.fetchTickets.mockResolvedValue({
    tickets: [], total: 0, page: 1, size: 10, totalPages: 0,
  })
})

// ==================== updateStatus ====================

describe('updateStatus — 乐观更新与回滚', () => {
  it('成功时状态落定，并同步后端返回的派生字段', async () => {
    const store = storeWith(ticket())
    api.updateTicketStatus.mockResolvedValue(
      ticket({ status: 'processing', updatedAt: '2026-08-23 11:00', version: 1, slaProgress: 35, slaBreached: false })
    )

    await store.updateStatus('TKT-A', 'processing')
    const t = store.getById('TKT-A')!

    expect(t.status).toBe('processing')
    expect(t.updatedAt).toBe('2026-08-23 11:00')
  })

  it('必须同步 version —— 后端状态变更会自增版本号，漏同步则接着编辑必然误报 40009（6.16）', async () => {
    const store = storeWith(ticket({ version: 0 }))
    api.updateTicketStatus.mockResolvedValue(ticket({ status: 'processing', version: 1 }))

    await store.updateStatus('TKT-A', 'processing')

    expect(store.getById('TKT-A')!.version).toBe(1)
  })

  it('必须同步 SLA 派生值 —— 转终态后后端冻结计时，不同步则 UI 仍显示增长值（6.16）', async () => {
    const store = storeWith(ticket({ slaProgress: 20, slaBreached: false }))
    api.updateTicketStatus.mockResolvedValue(
      ticket({ status: 'resolved', version: 1, slaProgress: 88, slaBreached: true })
    )

    await store.updateStatus('TKT-A', 'resolved')
    const t = store.getById('TKT-A')!

    expect(t.slaProgress).toBe(88)
    expect(t.slaBreached).toBe(true)
  })

  it('失败时回滚状态 —— 不让 UI 停留在未落库的状态', async () => {
    const store = storeWith(ticket({ status: 'pending' }))
    api.updateTicketStatus.mockRejectedValue(new Error('500'))

    await expect(store.updateStatus('TKT-A', 'processing')).rejects.toThrow()

    expect(store.getById('TKT-A')!.status).toBe('pending')
  })

  it('失败时向用户报错并外抛 —— 调用方需据此跳过成功提示', async () => {
    const store = storeWith(ticket())
    api.updateTicketStatus.mockRejectedValue(new Error('500'))

    await expect(store.updateStatus('TKT-A', 'processing')).rejects.toThrow()
    expect(notify.handleServerError).toHaveBeenCalled()
  })

  it('状态未变化时不发请求', async () => {
    const store = storeWith(ticket({ status: 'pending' }))
    await store.updateStatus('TKT-A', 'pending')
    expect(api.updateTicketStatus).not.toHaveBeenCalled()
  })

  it('工单不存在时不发请求', async () => {
    const store = storeWith()
    await store.updateStatus('NOT-EXIST', 'processing')
    expect(api.updateTicketStatus).not.toHaveBeenCalled()
  })

  it('成功后重新拉取活动流 —— 后端已记录，本地不插入避免重复条目', async () => {
    const store = storeWith(ticket())
    api.updateTicketStatus.mockResolvedValue(ticket({ status: 'processing', version: 1 }))

    await store.updateStatus('TKT-A', 'processing')

    expect(api.fetchTicketActivities).toHaveBeenCalledWith('TKT-A')
  })
})

// ==================== transferTicket ====================

describe('transferTicket — 乐观更新与回滚', () => {
  it('成功时负责人落定并同步 version（转派同样自增版本号）', async () => {
    const store = storeWith(ticket({ assignee: '张明', version: 1 }))
    api.transferTicket.mockResolvedValue(ticket({ assignee: '王芳', version: 2, updatedAt: '2026-08-23 12:00' }))

    await store.transferTicket('TKT-A', '王芳')
    const t = store.getById('TKT-A')!

    expect(t.assignee).toBe('王芳')
    expect(t.version).toBe(2)
  })

  it('失败时回滚负责人', async () => {
    const store = storeWith(ticket({ assignee: '张明' }))
    api.transferTicket.mockRejectedValue(new Error('500'))

    await expect(store.transferTicket('TKT-A', '王芳')).rejects.toThrow()

    expect(store.getById('TKT-A')!.assignee).toBe('张明')
  })

  it('负责人未变化时不发请求', async () => {
    const store = storeWith(ticket({ assignee: '张明' }))
    await store.transferTicket('TKT-A', '张明')
    expect(api.transferTicket).not.toHaveBeenCalled()
  })
})

// ==================== updateTicket ====================

describe('updateTicket — 快照回滚与后端值校准', () => {
  it('用编辑前的 version 做并发校验（6.11 乐观锁）', async () => {
    const store = storeWith(ticket({ version: 3 }))
    api.updateTicket.mockResolvedValue(ticket({ version: 4, title: '新标题' }))

    await store.updateTicket('TKT-A', { title: '新标题' })

    expect(api.updateTicket).toHaveBeenCalledWith(
      'TKT-A',
      expect.objectContaining({ version: 3 })
    )
  })

  it('采用后端返回的 tags —— 后端会归一化（去空/去重/截断/限量），用本地值会显示未归一化的原始输入（6.16）', async () => {
    const store = storeWith(ticket({ tags: [] }))
    api.updateTicket.mockResolvedValue(ticket({ version: 1, tags: ['K8s', '网络'] }))

    await store.updateTicket('TKT-A', { tags: ['  K8s  ', 'K8s', '', '网络'] })

    expect(store.getById('TKT-A')!.tags).toEqual(['K8s', '网络'])
  })

  it('采用后端返回的 slaProgress —— 用本地值会清掉后端刚算好的进度（6.16）', async () => {
    const store = storeWith(ticket({ slaProgress: 20 }))
    api.updateTicket.mockResolvedValue(ticket({ version: 1, slaProgress: 55 }))

    await store.updateTicket('TKT-A', { priority: 'urgent' })

    expect(store.getById('TKT-A')!.slaProgress).toBe(55)
  })

  it('采用后端返回的 version', async () => {
    const store = storeWith(ticket({ version: 1 }))
    api.updateTicket.mockResolvedValue(ticket({ version: 2 }))

    await store.updateTicket('TKT-A', { title: 'x' })

    expect(store.getById('TKT-A')!.version).toBe(2)
  })

  it('沿用已加载的回复/活动流/附件 —— 它们不在更新响应中返回，被覆盖会导致时间线清空', async () => {
    const existing = ticket() as FrontendTicket & { replies: Array<FrontendTicket['replies'][number]> }
    existing.replies = [{ role: 'creator', author: '张明', time: '2026-08-23 11:00', content: '已处理' }]
    // attachments 声明为 string[]（文件名数组），此处只验证「不因更新而覆盖」
    existing.attachments = ['log.txt']
    const store = storeWith(existing)
    api.updateTicket.mockResolvedValue(ticket({ version: 1 }))

    await store.updateTicket('TKT-A', { title: 'x' })
    const t = store.getById('TKT-A')!

    expect(t.replies).toHaveLength(1)
    expect(t.attachments).toHaveLength(1)
  })

  it('失败时整体回滚到快照', async () => {
    const store = storeWith(ticket({ title: '原标题', priority: 'high' }))
    api.updateTicket.mockRejectedValue(new Error('500'))

    await expect(store.updateTicket('TKT-A', { title: '新标题', priority: 'urgent' }))
      .rejects.toThrow()
    const t = store.getById('TKT-A')!

    expect(t.title).toBe('原标题')
    expect(t.priority).toBe('high')
  })

  it('版本冲突时刷新列表 —— 冲突后直接重试会覆盖他人修改，必须先让用户看到最新内容（6.11）', async () => {
    const store = storeWith(ticket({ title: '原标题', version: 0 }))
    api.updateTicket.mockRejectedValue(new errors.FakeVersionConflictError())
    // 冲突后 store 会调 refreshTickets 拉最新列表；此处让后端返回他人改后的版本
    api.fetchTickets.mockResolvedValue({
      tickets: [ticket({ title: '他人改的标题', version: 1 })],
      total: 1, page: 1, size: 10, totalPages: 1,
    })

    await expect(store.updateTicket('TKT-A', { title: '新标题' })).rejects.toThrow()

    // 关键：本地不保留「新标题」——用户的编辑被丢弃，看到的是他人的最新版本。
    // 若此处显示「新标题」，用户会以为自己改成功了，接着提交就覆盖他人修改
    expect(store.getById('TKT-A')!.title).toBe('他人改的标题')
    expect(api.fetchTickets).toHaveBeenCalled()
  })

  it('版本冲突时把编辑前的快照写回 —— 刷新前的中间态不得残留用户的未落库修改', async () => {
    const store = storeWith(ticket({ title: '原标题', version: 0 }))
    api.updateTicket.mockRejectedValue(new errors.FakeVersionConflictError())
    // 刷新返回空列表（如该工单已不在当前筛选页）：
    // 此时无法用后端值校准，回滚后的本地状态是唯一依据
    api.fetchTickets.mockResolvedValue({
      tickets: [], total: 0, page: 1, size: 10, totalPages: 0,
    })

    await expect(store.updateTicket('TKT-A', { title: '新标题' })).rejects.toThrow()

    // 列表被刷新为空是预期行为（服务端分页的权威结果），
    // 关键是过程中没有把「新标题」当作已保存的状态留下
    expect(store.getById('TKT-A')).toBeUndefined()
  })

  it('工单不存在时不发请求', async () => {
    const store = storeWith()
    await store.updateTicket('NOT-EXIST', { title: 'x' })
    expect(api.updateTicket).not.toHaveBeenCalled()
  })
})

// ==================== appendReply ====================

describe('appendReply — 乐观插入与回滚', () => {
  /** 结构对齐 TicketReply：后端返回带 id，本地乐观插入只有 time（服务端生成前为占位） */
  const reply = { role: 'creator' as const, author: '张明', content: '已重启服务', time: '' }

  it('成功时用后端返回值替换乐观插入项 —— 时间戳由服务端生成，避免客户端时钟偏差', async () => {
    const store = storeWith(ticket())
    const saved = { ...reply, id: 9, time: '2026-08-23 12:00' }
    api.addTicketReply.mockResolvedValue(saved as never)

    await store.appendReply('TKT-A', reply)
    const replies = store.getById('TKT-A')!.replies!

    expect(replies).toHaveLength(1)
    // 后端返回值含 id 与时间戳
    expect((replies[0] as TicketReply & { id: number }).id).toBe(9)
    expect(replies[0].time).toBe('2026-08-23 12:00')
  })

  it('失败时移除乐观插入项 —— 否则用户看到一条根本没落库的回复', async () => {
    const store = storeWith(ticket())
    api.addTicketReply.mockRejectedValue(new Error('40004 工单已关闭'))

    await expect(store.appendReply('TKT-A', reply)).rejects.toThrow()

    expect(store.getById('TKT-A')!.replies).toHaveLength(0)
  })

  it('回复成功后同步 version —— 回复会让后端自增版本号，漏同步则后续编辑误报冲突', async () => {
    const store = storeWith(ticket({ version: 1 }))
    api.addTicketReply.mockResolvedValue({ ...reply, id: 9 } as never)
    api.fetchTicketById.mockResolvedValue(ticket({ version: 2, slaProgress: 40, slaBreached: false }))

    await store.appendReply('TKT-A', reply)

    expect(store.getById('TKT-A')!.version).toBe(2)
  })

  it('version 同步失败不影响回复本身 —— 后续冲突由 40009 兜底', async () => {
    const store = storeWith(ticket({ version: 1 }))
    api.addTicketReply.mockResolvedValue({ ...reply, id: 9 } as never)
    api.fetchTicketById.mockRejectedValue(new Error('网络抖动'))

    await expect(store.appendReply('TKT-A', reply)).resolves.toBeUndefined()

    expect(store.getById('TKT-A')!.replies).toHaveLength(1)
  })

  it('replies 未初始化时先建数组，不抛错', async () => {
    const store = storeWith(ticket({ replies: undefined } as Partial<FrontendTicket>))
    api.addTicketReply.mockResolvedValue({ ...reply, id: 9 } as never)

    await expect(store.appendReply('TKT-A', reply)).resolves.toBeUndefined()
    expect(store.getById('TKT-A')!.replies).toHaveLength(1)
  })
})

// ==================== updateTags ====================

describe('updateTags — 乐观更新与静默失败检测', () => {
  it('成功时采用后端归一化后的标签', async () => {
    const store = storeWith(ticket({ tags: [] }))
    api.replaceTicketTags.mockResolvedValue(['K8s', '网络'])

    await store.updateTags('TKT-A', ['  K8s  ', 'K8s', '', '网络'])

    expect(store.getById('TKT-A')!.tags).toEqual(['K8s', '网络'])
  })

  it('提交了标签但一个都没存上时告警 —— 后端不抛异常，只能由前端比对才能发现（6.19）', async () => {
    const store = storeWith(ticket())
    api.replaceTicketTags.mockResolvedValue([])
    const { ElMessage } = await import('element-plus')

    await store.updateTags('TKT-A', ['主从延迟', '紧急排查'])

    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled()
  })

  it('提交空数组（清空标签）时不误报失败 —— 返回空是预期结果', async () => {
    const store = storeWith(ticket({ tags: ['旧标签'] }))
    api.replaceTicketTags.mockResolvedValue([])
    const { ElMessage } = await import('element-plus')
    vi.mocked(ElMessage.warning).mockClear()

    await store.updateTags('TKT-A', [])

    expect(vi.mocked(ElMessage.warning)).not.toHaveBeenCalled()
    expect(store.getById('TKT-A')!.tags).toEqual([])
  })

  it('失败时回滚到原标签', async () => {
    const store = storeWith(ticket({ tags: ['原标签'] }))
    api.replaceTicketTags.mockRejectedValue(new Error('500'))

    await expect(store.updateTags('TKT-A', ['新标签'])).rejects.toThrow()

    expect(store.getById('TKT-A')!.tags).toEqual(['原标签'])
  })

  it('成功后刷新热门标签 —— 新标签需立即出现在建议列表', async () => {
    const store = storeWith(ticket())
    api.replaceTicketTags.mockResolvedValue(['K8s'])

    await store.updateTags('TKT-A', ['K8s'])

    expect(api.fetchHotTags).toHaveBeenCalled()
  })
})

// ==================== deleteTicket / restoreTicket ====================

describe('deleteTicket — 先落库再移出列表', () => {
  it('成功时移出列表并返回快照供撤销', async () => {
    const store = storeWith(ticket({ id: 'TKT-A' }), ticket({ id: 'TKT-B' }))
    api.deleteTicket.mockResolvedValue(undefined)

    const snap = await store.deleteTicket('TKT-B')

    expect(snap?.ticket.id).toBe('TKT-B')
    expect(snap?.index).toBe(0)
    expect(store.getById('TKT-B')).toBeUndefined()
  })

  it('失败时不移出列表 —— 先落库再改本地，避免删除失败却从 UI 消失', async () => {
    const store = storeWith(ticket())
    api.deleteTicket.mockRejectedValue(new Error('403'))

    await expect(store.deleteTicket('TKT-A')).rejects.toThrow()

    expect(store.getById('TKT-A')).toBeDefined()
  })

  it('工单不在列表中时返回 null，不发请求', async () => {
    const store = storeWith()
    expect(await store.deleteTicket('NOT-EXIST')).toBeNull()
    expect(api.deleteTicket).not.toHaveBeenCalled()
  })
})

describe('restoreTicket — 以新工单号重建', () => {
  it('重建成功后插回原位置', async () => {
    const store = storeWith(ticket({ id: 'TKT-A' }))
    const removed = ticket({ id: 'TKT-B', title: '被删的工单' })
    api.createTicket.mockResolvedValue(ticket({ id: 'TKT-NEW', title: '被删的工单' }))

    const recreated = await store.restoreTicket(removed, 0)

    // 后端不支持指定 ID 插入，故重建得到新工单号（6.9 决策）
    expect(recreated?.id).toBe('TKT-NEW')
    expect(store.tickets[0].id).toBe('TKT-NEW')
  })

  it('保留原标签', async () => {
    const store = storeWith()
    const removed = ticket({ tags: ['主从延迟'] })
    api.createTicket.mockResolvedValue(ticket({ id: 'TKT-NEW', tags: [] }))

    const recreated = await store.restoreTicket(removed, 0)

    expect(recreated?.tags).toEqual(['主从延迟'])
  })

  it('插入位置越界时钳制到合法范围，不抛错', async () => {
    const store = storeWith(ticket({ id: 'TKT-A' }))
    api.createTicket.mockResolvedValue(ticket({ id: 'TKT-NEW' }))

    await store.restoreTicket(ticket(), 999)

    expect(store.tickets).toHaveLength(2)
  })

  it('重建失败时返回 null 而非抛错 —— 撤销失败不该让调用方崩', async () => {
    const store = storeWith()
    api.createTicket.mockRejectedValue(new Error('500'))

    expect(await store.restoreTicket(ticket(), 0)).toBeNull()
    expect(notify.handleServerError).toHaveBeenCalled()
  })
})

// ==================== 批量操作：部分成功如实上报 ====================

describe('bulkDelete — 单个失败不中断（6.9 部分成功如实上报）', () => {
  it('全部成功时返回全部快照', async () => {
    const store = storeWith(ticket({ id: 'A' }), ticket({ id: 'B' }), ticket({ id: 'C' }))
    api.deleteTicket.mockResolvedValue(undefined)

    const removed = await store.bulkDelete(['A', 'B', 'C'])

    expect(removed).toHaveLength(3)
    expect(store.tickets).toHaveLength(0)
  })

  it('部分失败时只返回成功的快照，不中断剩余项', async () => {
    const store = storeWith(ticket({ id: 'A' }), ticket({ id: 'B' }), ticket({ id: 'C' }))
    api.deleteTicket.mockImplementation((id: string) =>
      id === 'B' ? Promise.reject(new Error('403')) : Promise.resolve(undefined)
    )

    const removed = await store.bulkDelete(['A', 'B', 'C'])

    // 3 选 2 成功——调用方据此提示「2/3 条成功」而非谎报全部成功
    expect(removed).toHaveLength(2)
    expect(store.getById('B')).toBeDefined()
  })

  it('全部失败时返回空数组，列表不变', async () => {
    const store = storeWith(ticket({ id: 'A' }), ticket({ id: 'B' }))
    api.deleteTicket.mockRejectedValue(new Error('403'))

    expect(await store.bulkDelete(['A', 'B'])).toHaveLength(0)
    expect(store.tickets).toHaveLength(2)
  })
})

describe('bulkUpdateStatus — 返回成功数量', () => {
  it('部分失败时返回准确的成功数', async () => {
    const store = storeWith(ticket({ id: 'A' }), ticket({ id: 'B' }), ticket({ id: 'C' }))
    api.updateTicketStatus.mockImplementation((id: string) =>
      id === 'B'
        ? Promise.reject(new Error('403'))
        : Promise.resolve(ticket({ id, status: 'processing', version: 1 }))
    )

    expect(await store.bulkUpdateStatus(['A', 'B', 'C'], 'processing')).toBe(2)
  })

  it('失败项回滚到原状态', async () => {
    const store = storeWith(ticket({ id: 'A', status: 'pending' }), ticket({ id: 'B', status: 'pending' }))
    api.updateTicketStatus.mockImplementation((id: string) =>
      id === 'B'
        ? Promise.reject(new Error('403'))
        : Promise.resolve(ticket({ id, status: 'processing', version: 1 }))
    )

    await store.bulkUpdateStatus(['A', 'B'], 'processing')

    expect(store.getById('A')!.status).toBe('processing')
    expect(store.getById('B')!.status).toBe('pending')
  })
})

describe('bulkAssign — 返回成功数量', () => {
  it('部分失败时返回准确的成功数', async () => {
    const store = storeWith(ticket({ id: 'A', assignee: '张明' }), ticket({ id: 'B', assignee: '张明' }))
    api.transferTicket.mockImplementation((id: string) =>
      id === 'B'
        ? Promise.reject(new Error('403'))
        : Promise.resolve(ticket({ id, assignee: '王芳', version: 1 }))
    )

    expect(await store.bulkAssign(['A', 'B'], '王芳')).toBe(1)
    expect(store.getById('B')!.assignee).toBe('张明')
  })
})

describe('bulkRestore — 按原索引升序恢复', () => {
  it('返回成功恢复的数量', async () => {
    const store = storeWith()
    api.createTicket.mockImplementation((req: { title: string }) =>
      Promise.resolve(ticket({ id: `NEW-${req.title}`, title: req.title }))
    )

    const count = await store.bulkRestore([
      { ticket: ticket({ id: 'B', title: 'B' }), index: 1 },
      { ticket: ticket({ id: 'A', title: 'A' }), index: 0 },
    ])

    expect(count).toBe(2)
  })

  it('按索引升序插入，保持原有相对顺序', async () => {
    const store = storeWith()
    api.createTicket.mockImplementation((req: { title: string }) =>
      Promise.resolve(ticket({ id: `NEW-${req.title}`, title: req.title }))
    )

    await store.bulkRestore([
      { ticket: ticket({ id: 'B', title: 'B' }), index: 1 },
      { ticket: ticket({ id: 'A', title: 'A' }), index: 0 },
    ])

    expect(store.tickets.map(t => t.title)).toEqual(['A', 'B'])
  })

  it('部分失败时返回实际成功数', async () => {
    const store = storeWith()
    api.createTicket.mockImplementation((req: { title: string }) =>
      req.title === 'B'
        ? Promise.reject(new Error('500'))
        : Promise.resolve(ticket({ id: 'NEW', title: req.title }))
    )

    const count = await store.bulkRestore([
      { ticket: ticket({ title: 'A' }), index: 0 },
      { ticket: ticket({ title: 'B' }), index: 1 },
    ])

    expect(count).toBe(1)
  })
})
