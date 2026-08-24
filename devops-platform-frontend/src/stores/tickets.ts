import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
// 只保留 clearPersisted：用于清理旧版本写入的工单列表缓存。
// 列表数据本身不再持久化（原因见下方 clearPersisted 调用处注释）。
import { clearPersisted } from '@/utils/persist'
import {
  fetchTickets,
  fetchTicketById,
  fetchTicketStats,
  createTicket as apiCreateTicket,
  updateTicket as apiUpdateTicket,
  updateTicketStatus as apiUpdateStatus,
  transferTicket as apiTransferTicket,
  deleteTicket as apiDeleteTicket,
  addTicketReply,
  fetchTicketReplies,
  fetchTicketActivities,
  replaceTicketTags,
  fetchHotTags
} from '@/api/tickets'
import { VersionConflictError } from '@/api/services/ticket.service'
// A2：负责人名录改由后端下发，不再硬编码编造名单
import { fetchTeamMembers } from '@/api/users'
import { errorMessage } from '@/utils/errors'
import { notify, handleServerError } from '@/utils/notify'
import type { TeamMember } from '@/api/types'
import type { FrontendTicket } from '@/api/types/ticket'
import {
  UNASSIGNED,
  type TicketStatus,
  type TicketPriority
} from '@/constants/ticket'

// 仅用于清理旧版本遗留的缓存，不再写入
const PERSIST_KEY = 'tickets'

// void 为 Saga 补偿作废态，与 closed（正常关闭）语义不同
export type { TicketStatus, TicketPriority } from '@/constants/ticket'
export { getStatusLabel, getPriorityLabel } from '@/constants/ticket'

export interface TicketReply {
  role: 'creator' | 'agent' | 'ai' | 'system'
  author: string
  authorColor?: string
  time: string
  content: string
}

export interface TicketActivity {
  color: 'success' | 'primary' | 'gray' | 'warning'
  text: string
  detail?: string
  user: string
  time: string
  highlight?: boolean
}

export type Ticket = FrontendTicket

// 与后端 TicketEnums.Module.ALL 对齐（5 个合法值）
export const SERVICE_OPTIONS = [
  '生产集群-K8s',
  '生产环境-MySQL',
  '生产环境-Nginx',
  '网络',
  '未分类'
]

export const CATEGORY_OPTIONS = [
  '数据库',
  '服务器',
  '网络',
  '中间件',
  '容器/K8s',
  '存储',
  '应用异常',
  '性能',
  '安全',
  '其他'
]

/**
 * 「未指派」哨兵值（从 constants 层再导出，便于视图统一从 store 引入）
 *
 * 真实定义在 @/constants/ticket——api/utils/dto-converter.ts 也要用它，
 * 若定义在本 store 会形成 store → api → store 的循环依赖。
 */
export { UNASSIGNED }

/**
 * 负责人候选名单（兜底）
 *
 * 仅在 GET /api/v1/users 不可用时使用——此时至少要让用户能选「待分配」，
 * 而不是面对一个空白下拉框无法提交表单。
 *
 * 注意：此前这里是 ['张明','李四','王五','赵六','孙七','周八','待分配'] 硬编码七人名单，
 * 而库里只有「张明」一个真实负责人，工单会被指派给不存在的人。
 * 真实名单现由 useTicketsStore().assignees 从后端加载。
 */
export const ASSIGNEE_FALLBACK = [UNASSIGNED]

export const TAG_OPTIONS = [
  '生产环境', '测试环境', '预发环境',
  '性能', '故障', '安全', '优化',
  'MySQL', 'Redis', 'Nginx', 'K8s', 'Docker', 'Prometheus'
]

/**
 * 优先级 SLA 提示（与后端 TicketEnums.Sla 时限表一致）
 *
 * B0 前这里写的是「urgent: 2h 响应 / 4h 解决」，而后端派生的是别的值——
 * 表单提示与实际落库的 SLA 不一致，用户按提示预期 2h 响应，实际按后端时限计时。
 * 现对齐 PRD §2.3：P0 15m / P1 30m / P2 4h / P3 24h 响应。
 *
 * 注意：这是**提示文案**，真实 SLA 由后端派生并回传（6.15 契约：派生字段在后端算）。
 * 此处仅供建单表单预览，不参与任何计算。
 */
export const PRIORITY_HINTS: Record<TicketPriority, string> = {
  urgent: '15m 响应 / 4h 解决',
  high: '30m 响应 / 8h 解决',
  medium: '4h 响应 / 24h 解决',
  low: '24h 响应 / 72h 解决'
}

export const useTicketsStore = defineStore('tickets', () => {
  const tickets = ref<Ticket[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  const sanitize = (list: Ticket[]) => {
    list.forEach(t => {
      if (!Array.isArray(t.tags)) t.tags = []
      if (!Array.isArray(t.attachments)) t.attachments = []
      if (typeof t.category !== 'string') t.category = '其他'
      if (typeof t.description !== 'string') t.description = ''
      if (!Array.isArray(t.replies)) t.replies = []
      if (!Array.isArray(t.activities)) t.activities = []
    })
  }

  // 不再从 localStorage 恢复工单列表。
  //
  // 原因：筛选与分页已下沉到后端（见 CLAUDE.md 6.15），
  // localStorage 里存的是「上次浏览的某一页 + 某组筛选」的子集。
  // 恢复它会造成三种不一致：
  //   ① total/totalPages 不在持久化范围内，恢复后显示「N 行数据」
  //      但「共 0 条，第 1/1 页」，自相矛盾；
  //   ② 若首屏请求失败，用户面对的是上次某页的子集却当作完整列表；
  //   ③ 与当前筛选框显示的条件不符。
  //
  // 该机制在「全量数据都在前端」时是合理的缓存，分页下沉后成了错误来源。
  // 清理历史遗留数据，避免旧版本写入的缓存继续生效。
  clearPersisted(PERSIST_KEY)

  /**
   * 从后端加载工单列表
   */
  /** 后端返回的匹配总数（按当前筛选条件），用于分页 */
  const total = ref(0)
  const totalPages = ref(1)

  /** 列表查询参数 */
  interface TicketQueryParams {
    page?: number
    size?: number
    keyword?: string
    priority?: string
    status?: string
    service?: string
    category?: string
    assignee?: string
    createdFrom?: string
    createdTo?: string
    tags?: string[]
    /** 排序字段（后端执行，本地排序只能排当前页） */
    sortBy?: string
    /** true=升序 */
    sortAsc?: boolean
  }

  /**
   * 上次查询参数
   * <p>供 {@link refreshTickets} 沿用，避免刷新时丢掉筛选条件。</p>
   */
  const lastQuery = ref<TicketQueryParams | null>(null)

  /** 请求序号：防止快速切筛选时慢响应覆盖快响应 */
  let listRequestSequence = 0

  /**
   * 从后端加载工单列表
   * <p>
   * <b>筛选与分页均由后端执行</b>。此前只拉 100 条到前端本地过滤，
   * 第 101 条起的工单对搜索静默不可见，分页数字也基于裁剪后的子集。
   * </p>
   */
  const loadTicketsFromBackend = async (params?: TicketQueryParams) => {
    const requestSequence = ++listRequestSequence
    loading.value = true
    error.value = null
    // 记住本次条件，供 refreshTickets 沿用
    lastQuery.value = params ? { ...params } : null

    try {
      const result = await fetchTickets({
        page: params?.page || 1,
        size: params?.size || 10,
        keyword: params?.keyword,
        // 前端小写枚举，API 层负责映射为后端大写值
        priority: params?.priority as TicketPriority | undefined,
        status: params?.status as TicketStatus | undefined,
        service: params?.service,
        category: params?.category,
        assignee: params?.assignee,
        createdFrom: params?.createdFrom,
        createdTo: params?.createdTo,
        tags: params?.tags,
        sortBy: params?.sortBy,
        sortAsc: params?.sortAsc
      })

      // 只接受最后一次请求的结果，防止快速切筛选时慢响应覆盖快响应
      if (requestSequence !== listRequestSequence) return result

      tickets.value = result.tickets
      total.value = result.total
      totalPages.value = result.totalPages
      sanitize(tickets.value)

      // 不再写 localStorage：存的只是当前页+当前筛选的子集，
      // 恢复它会与 total/筛选框状态不一致（详见 store 顶部说明）

      return result
    } catch (e: unknown) {
      if (requestSequence !== listRequestSequence) return
      error.value = errorMessage(e, '加载工单列表失败')
      notify.error(error.value)
      throw e
    } finally {
      if (requestSequence === listRequestSequence) loading.value = false
    }
  }

  /**
   * 刷新工单列表（用户手动触发）
   */
  /**
   * 刷新工单列表
   * <p>
   * 沿用上次的查询参数，而非重置为无筛选第 1 页——
   * 后者会让调用方的筛选条件与显示数据不符（下拉框显示条件、
   * 列表却是全部工单）。
   * </p>
   */
  const refreshTickets = async () => {
    await loadTicketsFromBackend(lastQuery.value ?? undefined)
    notify.success('工单列表已刷新')
  }

  // 已移除工单列表的持久化与跨标签页同步。
  //
  // 跨页同步在服务端分页下会造成串页：
  //   A 标签页在第 3 页筛选「张明」，B 标签页在第 1 页无筛选。
  //   A 翻页 → 广播 → B 的列表被替换成 A 的第 3 页数据，
  //   而 B 的筛选框与页码显示的还是自己的状态。
  //
  // 每次翻页都触发 watch 写盘也是无意义的抖动。
  //
  // 工单数据的权威来源是后端；需要最新数据时调 refreshTickets()
  // （已支持沿用当前筛选条件）。

  const getById = (id: string) => tickets.value.find(t => t.id === id)

  /** 后端统计（含今日新增与紧急待处理，前端算不出来） */
  const serverStats = ref<{
    total: number
    todayNew: number
    pending: number
    processing: number
    resolved: number
    urgentPending: number
    byPriority?: Record<string, number>
  } | null>(null)

  /**
   * 统计数据
   * <p>优先用后端统计（全量准确），后端不可用时退化为当前页本地计算。</p>
   * <p>
   * 注意 urgentPending 的降级值为 <b>null 而非 0</b>：
   * 本地只能看到当前页，算出的 0 无法区分「确实没有紧急工单」与
   * 「本页没有但别页有」。用 null 表示「未知」，由视图显式呈现为
   * 「统计不可用」而不是谎报「暂无紧急工单」。
   * </p>
   */
  const stats = computed(() => {
    if (serverStats.value) return serverStats.value
    return {
      total: tickets.value.length,
      pending: tickets.value.filter(t => t.status === 'pending').length,
      processing: tickets.value.filter(t => t.status === 'processing').length,
      resolved: tickets.value.filter(t => t.status === 'resolved').length,
      todayNew: 0,
      urgentPending: null as number | null,
      byPriority: undefined as Record<string, number> | undefined
    }
  })

  /** 拉取后端统计，失败静默降级到本地计算 */
  const loadStats = async () => {
    try {
      serverStats.value = await fetchTicketStats()
    } catch (e) {
      console.warn('[TicketsStore] 获取统计失败，降级为本地计算', e)
      serverStats.value = null
    }
  }

  /**
   * 仅本地插入（不落库）
   * <p>供 AI 建单后回显等场景使用——工单已由后端创建，此处只同步内存。</p>
   */
  const addTicket = (t: Ticket) => {
    sanitize([t])
    tickets.value.unshift(t)
  }

  /**
   * 创建工单（落库）
   * <p>后端生成工单号，成功后插入列表头部。</p>
   *
   * @returns 创建后的工单，失败抛异常由调用方处理
   */
  const createTicket = async (payload: {
    title: string
    description: string
    priority: TicketPriority
    service: string
    category?: string
    assignee?: string
    sla?: string
    creator?: string
    /** 标签列表，随创建一并落库 */
    tags?: string[]
  }): Promise<Ticket> => {
    const created = await apiCreateTicket(payload)
    sanitize([created])
    tickets.value.unshift(created)
    await loadStats()
    return created
  }

  /**
   * 追加工单回复（落库）
   * <p>
   * 乐观更新：先插入本地使输入框立即清空，失败则移除并提示。
   * 此前此方法只写 Pinia 内存，回复刷新即丢失。
   * </p>
   */
  const appendReply = async (id: string, reply: TicketReply) => {
    const t = getById(id)
    if (!t) return

    if (!Array.isArray(t.replies)) t.replies = []
    // 乐观插入，记录索引供失败回滚
    t.replies.push(reply)
    const optimisticIndex = t.replies.length - 1

    try {
      const saved = await addTicketReply(id, {
        role: reply.role,
        author: reply.author,
        authorColor: reply.authorColor,
        content: reply.content
      })
      // 用后端返回值校准（时间戳由服务端生成，避免客户端时钟偏差）
      t.replies[optimisticIndex] = saved
      t.updatedAt = new Date().toISOString().slice(0, 16).replace('T', ' ')

      // 回复会产生活动流记录，重新拉取以保持时间线一致
      await loadActivities(id)

      // 回复后后端会自增 version，重新拉取工单以同步 version
      // 避免后续编辑/升级误报 40009 版本冲突
      try {
        const refreshed = await fetchTicketById(id)
        if (refreshed) {
          t.version = refreshed.version
          t.slaProgress = refreshed.slaProgress
          t.slaBreached = refreshed.slaBreached
        }
      } catch {
        // version 同步失败不阻塞主流程，后续冲突由 40009 兜底
      }
    } catch (e) {
      // 回滚：移除乐观插入的那条。
      //
      // 不能用 `t.replies[optimisticIndex] === reply` 判断——
      // t.replies 是 Pinia 响应式数组，push 原始对象后按索引取回的是**代理**，
      // 永不等于原对象，导致回滚条件恒为 false、**回滚从未执行**。
      // 后果正是本段要防的：没落库的回复留在时间线上，用户以为发送成功了。
      //
      // 改为按索引边界校验：期间若有其它回复插入（如后台拉取），
      // 长度会变化，此时按索引删除可能误删他人的回复，故仅在长度未变时删除。
      if (t.replies.length === optimisticIndex + 1) {
        t.replies.splice(optimisticIndex, 1)
      }
      console.error('[TicketsStore] 回复提交失败，已回滚', e)
      handleServerError(e, { action: '提交回复' })
      throw e
    }
  }

  /**
   * 加载工单回复（落库数据）
   */
  const loadReplies = async (id: string) => {
    const t = getById(id)
    if (!t) return
    try {
      t.replies = await fetchTicketReplies(id)
    } catch (e) {
      // 回复加载失败不应阻塞详情页展示
      console.warn('[TicketsStore] 加载回复失败', e)
    }
  }

  /**
   * 加载工单活动流（落库数据）
   */
  const loadActivities = async (id: string) => {
    const t = getById(id)
    if (!t) return
    try {
      t.activities = await fetchTicketActivities(id)
    } catch (e) {
      console.warn('[TicketsStore] 加载活动流失败', e)
    }
  }

  /**
   * 加载工单完整详情（回复 + 活动流）
   * <p>详情页进入时调用，两者并行拉取。</p>
   */
  const loadTicketDetail = async (id: string) => {
    await Promise.all([loadReplies(id), loadActivities(id)])
  }

  /**
   * 变更工单状态（落库）
   * <p>乐观更新：先改本地，失败则回滚，保证 UI 与库一致。</p>
   */
  const updateStatus = async (id: string, status: TicketStatus) => {
    const t = getById(id)
    if (!t) return
    const oldStatus = t.status
    if (oldStatus === status) return

    // 乐观更新状态字段。
    // 活动流不再本地插入——后端已在 updateStatus 中记录，
    // 本地插入会导致重新拉取后出现重复条目。
    t.status = status

    try {
      const updated = await apiUpdateStatus(id, status)
      t.updatedAt = updated.updatedAt
      // 必须同步 version：后端状态变更会自增版本号，
      // 不同步会导致用户接着编辑时带过期版本提交，误报 40009 冲突
      t.version = updated.version
      // SLA 派生值也要同步：状态转为终态后后端会冻结计时，
      // 不同步则 UI 仍显示旧的增长值
      t.slaProgress = updated.slaProgress
      t.slaBreached = updated.slaBreached
      // 拉取后端生成的活动流记录
      await loadActivities(id)
      await loadStats()
    } catch (e) {
      // 回滚，避免 UI 显示未落库的状态
      t.status = oldStatus
      console.error('[TicketsStore] 状态变更失败，已回滚', e)
      handleServerError(e, { action: '变更状态' })
      throw e
    }
  }

  /**
   * 更新工单（落库）
   * <p>乐观更新 + 失败回滚。</p>
   */
  const updateTicket = async (id: string, patch: Partial<Ticket>) => {
    const t = getById(id)
    if (!t) return

    // 快照用于回滚
    const snapshot = JSON.parse(JSON.stringify(t)) as Ticket

    Object.assign(t, patch)
    t.updatedAt = new Date().toISOString().slice(0, 16).replace('T', ' ')

    try {
      const updated = await apiUpdateTicket(id, {
        title: patch.title,
        description: patch.description,
        priority: patch.priority,
        service: patch.service,
        status: patch.status,
        category: patch.category,
        assignee: patch.assignee,
        sla: patch.sla,
        version: snapshot.version   // P1-4：用编辑前的版本号做并发校验
      })
      // 用后端返回值校准。
      // 后端会：① 重算 SLA 派生字段 ② 自增 version ③ 归一化标签
      //         （去空/去重/截断超长/限量 20）
      // 故 tags 与 slaProgress 必须采用后端值——用本地值覆盖会导致
      // 显示的标签是未归一化的原始输入，且刚算好的 SLA 进度被清掉。
      const idx = tickets.value.findIndex(x => x.id === id)
      if (idx >= 0) {
        tickets.value[idx] = {
          ...updated,
          // 回复与活动流不在更新响应中返回（需单独接口），沿用已加载的值。
          // 活动流会因本次编辑新增记录，故下面显式重新拉取。
          replies: t.replies,
          activities: t.activities,
          // 附件同理：不随工单更新返回，沿用已加载列表
          attachments: t.attachments
        }
      }

      // 编辑操作会在后端产生活动流记录（字段级变化描述），需重新拉取
      await Promise.all([loadStats(), loadActivities(id)])
    } catch (e) {
      const idx = tickets.value.findIndex(x => x.id === id)
      if (idx >= 0) tickets.value[idx] = snapshot

      // 版本冲突与普通失败要区别对待：
      // 冲突时重试仍会覆盖他人修改，必须先刷新看到最新内容
      if ((e as VersionConflictError)?.isVersionConflict) {
        console.warn('[TicketsStore] 版本冲突，本地修改已回滚', e)
        notify.warning((e as Error).message, { duration: 6000, showClose: true })
        // 拉取最新数据，让用户看到他人改了什么
        await refreshTickets()
        // 详情页可能不在当前列表页内，额外拉取单条工单确保 version 同步
        try {
          const refreshed = await fetchTicketById(id)
          if (refreshed) {
            const existing = getById(id)
            if (existing) {
              // 保留已加载的子表数据，避免详情页时间线/附件被静默清空
              const savedReplies = existing.replies
              const savedActivities = existing.activities
              const savedAttachments = existing.attachments
              Object.assign(existing, refreshed)
              existing.replies = savedReplies
              existing.activities = savedActivities
              existing.attachments = savedAttachments
            } else {
              addTicket(refreshed)
            }
          }
        } catch {
          // 单条拉取失败不阻塞，列表刷新已让用户看到最新数据
        }
      } else {
        console.error('[TicketsStore] 工单更新失败，已回滚', e)
        handleServerError(e, { action: '更新工单' })
      }
      throw e
    }
  }

  /**
   * 转派工单（落库）
   */
  const transferTicket = async (id: string, assignee: string) => {
    const t = getById(id)
    if (!t) return
    const oldAssignee = t.assignee
    if (oldAssignee === assignee) return

    // 活动流由后端 transferTicket 记录，此处不本地插入避免重复
    t.assignee = assignee

    try {
      const updated = await apiTransferTicket(id, assignee)
      t.updatedAt = updated.updatedAt
      // 同理需同步 version：后端转派会自增版本号
      t.version = updated.version
      await loadActivities(id)
    } catch (e) {
      t.assignee = oldAssignee
      console.error('[TicketsStore] 转派失败，已回滚', e)
      handleServerError(e, { action: '转派工单' })
      throw e
    }
  }

  /**
   * 删除工单（落库）
   * <p>
   * 先调后端删除，成功后移出列表。返回快照供「撤销」重建。
   * 注意：撤销会以新工单号重建（后端不支持指定 ID 插入）。
   * </p>
   */
  const deleteTicket = async (id: string): Promise<{ ticket: Ticket; index: number } | null> => {
    const idx = tickets.value.findIndex(t => t.id === id)
    if (idx < 0) return null

    try {
      await apiDeleteTicket(id)
    } catch (e) {
      console.error('[TicketsStore] 删除失败', e)
      handleServerError(e, { action: '删除工单' })
      throw e
    }

    const [removed] = tickets.value.splice(idx, 1)
    await loadStats()
    return { ticket: removed, index: idx }
  }

  /**
   * 恢复已删除的工单（重新落库）
   * <p>
   * ⚠️ 后端不支持指定 ID 插入，故重建会获得<b>新工单号</b>。
   * 调用方应向用户说明这一点，避免误以为原号恢复。
   * </p>
   *
   * @returns 重建后的工单（新 ID），失败返回 null
   */
  const restoreTicket = async (ticket: Ticket, index: number): Promise<Ticket | null> => {
    try {
      const recreated = await apiCreateTicket({
        title: ticket.title,
        description: ticket.description,
        priority: ticket.priority,
        service: ticket.service,
        category: ticket.category,
        assignee: ticket.assignee,
        sla: ticket.sla,
        creator: ticket.creator,
        tags: ticket.tags ?? []
      })
      // 恢复后状态可能不再是 pending（后端按 priority 重算 SLA），
      // 同步已有字段 + 保留原标签
      recreated.tags = ticket.tags ?? recreated.tags
      sanitize([recreated])
      const safeIdx = Math.max(0, Math.min(index, tickets.value.length))
      tickets.value.splice(safeIdx, 0, recreated)
      await loadStats()
      return recreated
    } catch (e) {
      console.error('[TicketsStore] 恢复工单失败', e)
      handleServerError(e, { action: '恢复工单' })
      return null
    }
  }

  /**
   * 批量删除（逐个落库）
   * <p>单个失败不中断，返回成功删除的快照。</p>
   */
  const bulkDelete = async (ids: string[]): Promise<Array<{ ticket: Ticket; index: number }>> => {
    const removed: Array<{ ticket: Ticket; index: number }> = []
    for (const id of ids) {
      try {
        const r = await deleteTicket(id)
        if (r) removed.push(r)
      } catch {
        // 已在 deleteTicket 内提示，此处继续处理剩余项
      }
    }
    return removed
  }

  /**
   * 批量恢复
   * @returns 成功恢复的数量
   */
  const bulkRestore = async (snapshots: Array<{ ticket: Ticket; index: number }>): Promise<number> => {
    let ok = 0
    const sorted = [...snapshots].sort((a, b) => a.index - b.index)
    for (const s of sorted) {
      const r = await restoreTicket(s.ticket, s.index)
      if (r) ok++
    }
    return ok
  }

  /**
   * 批量变更状态（逐个落库）
   * @returns 成功数量
   */
  const bulkUpdateStatus = async (ids: string[], status: TicketStatus): Promise<number> => {
    let ok = 0
    for (const id of ids) {
      try {
        await updateStatus(id, status)
        ok++
      } catch {
        // 单个失败不中断
      }
    }
    return ok
  }

  /**
   * 批量转派（逐个落库）
   * @returns 成功数量
   */
  const bulkAssign = async (ids: string[], assignee: string): Promise<number> => {
    let ok = 0
    for (const id of ids) {
      try {
        await transferTicket(id, assignee)
        ok++
      } catch {
        // 单个失败不中断
      }
    }
    return ok
  }

  /**
   * 全局热门标签（后端聚合，按使用次数降序）
   * <p>
   * 由后端跨全表统计。此前是从 tickets.value 提取，
   * 只能拿到当前页的标签——筛选选项会随分页变化。
   * </p>
   */
  const hotTags = ref<string[]>([])

  /**
   * 加载热门标签
   * <p>供筛选面板与标签输入建议使用。</p>
   */
  const loadHotTags = async () => {
    try {
      hotTags.value = await fetchHotTags(30)
    } catch (e) {
      // 标签建议不可用不应影响主流程，降级为当前页标签
      console.warn('[TicketsStore] 加载热门标签失败，降级为当前页标签', e)
      const set = new Set<string>()
      tickets.value.forEach(t => (t.tags ?? []).forEach(tag => set.add(tag)))
      hotTags.value = Array.from(set)
    }
  }

  /**
   * 全部标签（兼容旧调用名）
   * <p>优先用后端热门标签，为空时回退到当前页标签。</p>
   */
  const allTags = computed(() => {
    if (hotTags.value.length > 0) return hotTags.value
    const set = new Set<string>()
    tickets.value.forEach(t => (t.tags ?? []).forEach(tag => set.add(tag)))
    return Array.from(set)
  })

  /**
   * 更新工单标签（落库）
   * <p>乐观更新 + 失败回滚。</p>
   */
  const updateTags = async (id: string, tags: string[]) => {
    const t = getById(id)
    if (!t) return

    const snapshot = [...(t.tags ?? [])]
    t.tags = tags   // 乐观更新

    try {
      // 用后端返回值校准（后端会去空、去重、截断超长、限量 20）
      const saved = await replaceTicketTags(id, tags)
      t.tags = saved

      // 请求了标签但一个都没存上 = 写入失败，须告知用户。
      // 后端此时不抛异常（标签是附属元数据，不因其失败而中断主流程），
      // 故必须由前端比对提交值与返回值才能发现
      if (tags.length > 0 && saved.length === 0) {
        notify.warning('标签保存失败，请稍后重试', { duration: 6000, showClose: true })
      }

      // 标签变更会产生活动流记录
      await loadActivities(id)
      // 刷新热门标签，使新标签立即出现在建议列表
      await loadHotTags()

      // 标签替换后后端可能自增 version，重新拉取以同步
      try {
        const refreshed = await fetchTicketById(id)
        if (refreshed) {
          t.version = refreshed.version
          t.slaProgress = refreshed.slaProgress
          t.slaBreached = refreshed.slaBreached
        }
      } catch {
        // version 同步失败不阻塞主流程
      }
    } catch (e) {
      t.tags = snapshot
      console.error('[TicketsStore] 标签更新失败，已回滚', e)
      handleServerError(e, { action: '更新标签' })
      throw e
    }
  }

  // ==================== 负责人名录（A2：后端下发，不再硬编码） ====================

  /** 成员名录原始数据（含角色、职位、负载） */
  const teamMembers = ref<TeamMember[]>([])
  const membersLoaded = ref(false)

  /**
   * 可选负责人名单（供下拉框直接 v-for）
   *
   * 「待分配」置于末位：它是「未指派」哨兵而非人，放在真实成员之后
   * 才不会让用户误选。名录为空时（后端不可用）至少保留它，
   * 否则下拉框空白导致表单无法提交。
   */
  const assignees = computed<string[]>(() => {
    const names = teamMembers.value
      .map(m => m.name)
      .filter(n => typeof n === 'string' && n.trim() !== '' && n !== UNASSIGNED)
    return [...names, UNASSIGNED]
  })

  /**
   * 加载成员名录
   *
   * 失败时降级为仅「待分配」并告警——不编造姓名填充。
   * 名单已加载过则跳过（force=true 可强制刷新）。
   */
  const loadTeamMembers = async (force = false) => {
    if (membersLoaded.value && !force) return
    try {
      teamMembers.value = await fetchTeamMembers()
      membersLoaded.value = true
    } catch (e) {
      // 名单不可用时不伪造成员：宁可只给「待分配」，也不让用户
      // 把工单指派给一个不存在的人
      console.warn('[TicketsStore] 加载成员名录失败，降级为仅「待分配」', e)
      teamMembers.value = []
      membersLoaded.value = false
      notify.warning('负责人名单加载失败，暂时只能选择「待分配」', { duration: 5000, showClose: true })
    }
  }

  return {
    tickets,
    loading,
    error,
    getById,
    stats,
    loadStats,
    loadTicketsFromBackend,
    refreshTickets,
    addTicket,
    createTicket,
    appendReply,
    loadReplies,
    loadActivities,
    loadTicketDetail,
    updateStatus,
    updateTicket,
    transferTicket,
    deleteTicket,
    restoreTicket,
    bulkDelete,
    bulkRestore,
    bulkUpdateStatus,
    bulkAssign,
    allTags,
    hotTags,
    loadHotTags,
    updateTags,
    // 负责人名录（后端下发）
    teamMembers,
    assignees,
    loadTeamMembers,
    // 分页元信息（来自后端，按当前筛选条件）
    total,
    totalPages
  }
})
