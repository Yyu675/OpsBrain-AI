/**
 * notifications store 测试。
 *
 * 重点保护「持久化状态必须有界」这条契约。它看起来像洁癖，实际是真实故障：
 * readIds / dismissedIds 都写进 localStorage，而 persist 层对
 * QuotaExceededError 是**静默吞掉**的（隐私模式 / 配额满都不报错）。
 * 一旦这两个数组无界增长撑爆配额，用户的列宽、主题、通知已读状态
 * 会全部「看似保存成功、刷新后复原」，且控制台没有任何线索。
 *
 * 值守场景下这不是极端假设：一天几十条告警，点几次「全部已读」，
 * 同一台机器用上一年就是万级数组。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/alerts', () => ({
  fetchAlerts: vi.fn(async () => ({ alerts: [], total: 0 })),
}))

import { useNotificationsStore } from '../notifications'

const seedItems = (store: ReturnType<typeof useNotificationsStore>, count: number) => {
  store.items = Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    title: `告警 ${i + 1}`,
    time: '2026-08-24 10:00',
    read: false,
  }))
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('notifications — 持久化状态有界', () => {
  it('readIds 超过上限后被截断，保留的是最近的 id', () => {
    const store = useNotificationsStore()

    // 分批灌入 1200 条已读——远超 500 上限
    for (let batch = 0; batch < 12; batch++) {
      seedItems(store, 100)
      store.items.forEach((n, i) => { n.id = batch * 100 + i + 1 })
      store.markAllRead()
    }

    expect(store.readIds.length).toBeLessThanOrEqual(500)
    // 截断保留尾部：最新的 id 必须还在，否则刚读过的告警下次又变未读
    expect(store.readIds).toContain(1200)
    expect(store.readIds).not.toContain(1)
  })

  it('markRead 逐条累积也受同一上限约束', () => {
    const store = useNotificationsStore()
    seedItems(store, 600)
    store.items.forEach(n => store.markRead(n.id))

    expect(store.readIds.length).toBeLessThanOrEqual(500)
    expect(store.readIds).toContain(600)
  })

  it('dismissedIds 保持原有 200 上限', () => {
    const store = useNotificationsStore()
    seedItems(store, 300)
    // dismiss 会改 items，先拷一份 id 列表
    const ids = store.items.map(n => n.id)
    ids.forEach(id => store.dismiss(id))

    expect(store.dismissedIds.length).toBeLessThanOrEqual(200)
    expect(store.dismissedIds).toContain(300)
  })

  it('截断后的结果确实落到了 localStorage，而非只改了内存', () => {
    const store = useNotificationsStore()
    seedItems(store, 700)
    store.markAllRead()

    const raw = localStorage.getItem('__store__:notifications')
    expect(raw).toBeTruthy()
    const parsed = JSON.parse(raw as string)
    expect(parsed.value.readIds.length).toBeLessThanOrEqual(500)
  })
})

describe('notifications — 基本行为', () => {
  it('unreadCount 只数未读项', () => {
    const store = useNotificationsStore()
    seedItems(store, 5)
    store.markRead(1)
    store.markRead(2)
    expect(store.unreadCount).toBe(3)
  })

  it('addNotification 用告警 id 去重 —— WS 推入与后端拉取不重复', () => {
    const store = useNotificationsStore()
    store.addNotification({ title: 'A', time: 't', read: false }, 42)
    store.addNotification({ title: 'A 重复', time: 't', read: false }, 42)
    expect(store.items.filter(n => n.id === 42)).toHaveLength(1)
  })

  it('clearItems 只清列表，保留已读/已忽略记忆 —— 登出后重登不该全部变未读', () => {
    const store = useNotificationsStore()
    seedItems(store, 3)
    store.markAllRead()
    store.dismiss(1)

    const readBefore = [...store.readIds]
    store.clearItems()

    expect(store.items).toHaveLength(0)
    expect(store.readIds).toEqual(readBefore)
    expect(store.dismissedIds).toContain(1)
  })
})
