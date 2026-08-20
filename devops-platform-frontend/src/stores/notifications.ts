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
 * dismissedIds 有界上限：只保留最近 N 条已 dismiss 的告警 id，
 * 防止长时间运行后该数组无界增长（每次 dismiss 都 push）。
 */
const MAX_DISMISSED_IDS = 200

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
      if (!this.dismissedIds.includes(id)) {
        this.dismissedIds.push(id)
        // 有界：仅保留最近 MAX_DISMISSED_IDS 条，防止无界增长
        if (this.dismissedIds.length > MAX_DISMISSED_IDS) {
          this.dismissedIds = this.dismissedIds.slice(-MAX_DISMISSED_IDS)
        }
      }
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
