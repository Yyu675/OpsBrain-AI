import { defineStore } from 'pinia'
import { loadPersisted, savePersisted, type Migrator } from '@/utils/persist'
import { fetchAlerts } from '@/api/alerts'
import { formatAbsolute } from '@/utils/time'

export interface AppNotification {
  id: number
  title: string
  time: string
  read: boolean
  linkTo?: string
}

const KEY = 'notifications'
const VERSION = 1

const MIGRATIONS: Record<number, Migrator> = {}

/**
 * dismissedIds / readIds 的有界上限。
 *
 * 两者都必须有界。此前只有 dismissedIds 做了截断，readIds 是纯 push——
 * 而「已读」比「已忽略」高频得多（点一次「全部已读」就批量灌入 20 条），
 * 值守人员长期使用同一浏览器，这个数组只增不减地写进 localStorage。
 *
 * 后果不只是占空间：persist 的 safeSet 对 QuotaExceededError 是**静默吞掉**的，
 * 超限后所有偏好（列宽、主题、通知状态）都会看似保存成功实则丢失，
 * 用户完全无从察觉。上限设为「显著大于一屏通知数」即可——
 * 更早的告警早已不在通知列表里，记它的已读状态没有意义。
 */
const MAX_DISMISSED_IDS = 200
const MAX_READ_IDS = 500

/** 只保留数组尾部（最近）的 max 项 */
const capTail = (ids: number[], max: number): number[] =>
  ids.length > max ? ids.slice(-max) : ids

/** 挂载时从后端拉取的告警数（通知中心展示最近告警，非全量）。 */
const BACKEND_PULL_SIZE = 20

interface PersistedState {
  readIds: number[]
  dismissedIds: number[]
}

const loadState = (): PersistedState => {
  const saved = loadPersisted<Partial<PersistedState>>(KEY, VERSION, { migrations: MIGRATIONS })
  return {
    readIds: saved?.readIds ?? [],
    dismissedIds: saved?.dismissedIds ?? []
  }
}

const persist = (state: PersistedState) => savePersisted(KEY, state, VERSION)

export const useNotificationsStore = defineStore('notifications', {
  state: () => {
    const persisted = loadState()
    return {
      items: [] as AppNotification[],
      readIds: persisted.readIds,
      dismissedIds: persisted.dismissedIds
    }
  },
  getters: {
    unreadCount(state): number {
      return state.items.filter(n => !n.read).length
    }
  },
  actions: {
    _persist() {
      // 在写盘这一个收口点做截断：各 action 只管 push，不必各自记得限长
      // （此前 dismiss 记得、markRead/markAllRead 忘了，正是分散处理的代价）
      this.readIds = capTail(this.readIds, MAX_READ_IDS)
      this.dismissedIds = capTail(this.dismissedIds, MAX_DISMISSED_IDS)
      persist({
        readIds: this.readIds,
        dismissedIds: this.dismissedIds
      })
    },
    /**
     * 挂载时从后端拉取最近告警，重建通知列表（修复会话级刷新丢失）。
     *
     * items 本身不持久化——权威来源是后端 `GET /alerts`；
     * 刷新后由本方法重新拉取重建，已读 / 已 dismiss 状态仍由 readIds / dismissedIds 保留。
     * 拉取失败时保留现有通知并告警，不阻塞 UI。
     */
    async loadFromBackend() {
      try {
        const { alerts } = await fetchAlerts({ page: 1, size: BACKEND_PULL_SIZE })
        const incoming = alerts
          .filter(a => !this.dismissedIds.includes(a.id))
          .map<AppNotification>(a => ({
            id: a.id,
            title: a.title || a.alertName || '告警',
            time: formatAbsolute(a.lastOccurredAt),
            read: this.readIds.includes(a.id),
            // 无关联工单时跳该告警详情页（此前落到列表页 /alerts，用户还得自己找是哪条）
            linkTo: a.ticketId ? `/tickets/${a.ticketId}` : `/alerts/${a.id}`
          }))
        const incomingIds = new Set(incoming.map(n => n.id))
        // 保留 WebSocket 实时推入、且后端尚未返回的项（如刚 NEW 的告警），避免被覆盖丢失
        const liveItems = this.items.filter(n => !incomingIds.has(n.id))
        this.items = [...incoming, ...liveItems]
        this._persist()
      } catch (e) {
        console.warn('[notifications] 后端告警拉取失败，保留现有通知:', e)
      }
    },
    /**
     * 清空内存中的通知列表（登出时调用）。
     *
     * 只清 items，不清 readIds / dismissedIds——后两者是"用户已处理过哪些告警"的
     * 状态记忆，同一浏览器重新登录后仍应生效，不该被登出抹掉。
     * items 本身不持久化，登录后由 loadFromBackend 重新拉取重建。
     */
    clearItems() {
      this.items = []
    },
    markRead(id: number) {
      const n = this.items.find(x => x.id === id)
      if (!n) return
      if (!n.read) {
        n.read = true
        if (!this.readIds.includes(id)) this.readIds.push(id)
        this._persist()
      }
    },
    markAllRead() {
      let changed = false
      this.items.forEach(n => {
        if (!n.read) {
          n.read = true
          if (!this.readIds.includes(n.id)) this.readIds.push(n.id)
          changed = true
        }
      })
      if (changed) this._persist()
    },
    dismiss(id: number) {
      const idx = this.items.findIndex(n => n.id === id)
      if (idx >= 0) this.items.splice(idx, 1)
      // 截断统一在 _persist 里做（见其注释），此处只管登记
      if (!this.dismissedIds.includes(id)) this.dismissedIds.push(id)
      this._persist()
    },
    /**
     * 推入一条通知（WebSocket NEW 告警事件）。
     *
     * @param id 稳定 id——优先使用告警实体 id（与后端拉取共用同一 id 空间），
     *           实现 WS 实时推入与后端拉取的去重；未传则回退时间戳。
     */
    addNotification(n: Omit<AppNotification, 'id'>, id?: number) {
      const finalId = id ?? Date.now()
      if (this.items.some(x => x.id === finalId)) return
      this.items.unshift({ ...n, id: finalId })
      this._persist()
    }
  }
})
