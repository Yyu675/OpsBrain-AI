/**
 * 登出清理测试。
 *
 * 保护一条越权读取的防线：上一个用户的本地数据（遗留 AI 对话持久键、
 * Query 缓存）不能留给下一个在同一台机器登录的人。
 * 遗留键 `__store__:chat-sessions` 来自已下线的独立 AI 对话页
 * （2026-09-27 方案乙），其中 citations 是知识库原文片段，
 * 而知识库有可见性分级——登出不清 = 越权读取的载体。
 *
 * 之所以要专门测「冷启动不清理」：watch 在 isAuthenticated 初始化时
 * 也会触发一次，写漏 wasAuthed 判断就会在每次刷新页面时误清——
 * 这是修复本身很容易引入的回归。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'

import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

import { useAppStore } from '@/stores/app'
import { useSessionCleanup } from '../useSessionCleanup'

const Host = defineComponent({
  setup() {
    useSessionCleanup()
    return () => h('div')
  },
})

/** 已下线的 AI 对话页留在老用户机器上的持久化键 */
const LEGACY_CHAT_KEY = '__store__:chat-sessions'

/**
 * 每个用例独立的 QueryClient。
 *
 * useSessionCleanup 现在要清 Query 缓存，故必须在有 QueryClient 的
 * 上下文里挂载。共用一个实例会让用例之间通过缓存互相影响。
 */
let queryClient: QueryClient

const mountHost = () =>
  mount(Host, { global: { plugins: [[VueQueryPlugin, { queryClient }]] } })

beforeEach(() => {
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 5 * 60_000 } },
  })
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('useSessionCleanup', () => {
  it('登出后抹掉遗留的 AI 对话持久键 —— 下一个登录的人看不到上一个人的对话', async () => {
    const app = useAppStore()
    app.isAuthenticated = true
    const wrapper = mountHost()

    localStorage.setItem(LEGACY_CHAT_KEY, JSON.stringify({ version: 2, value: { global: {} } }))

    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    expect(localStorage.getItem(LEGACY_CHAT_KEY)).toBeNull()
  })

  it('冷启动（从未登录）不触发清理 —— 否则每次刷新都会误清本地数据', async () => {
    const app = useAppStore()
    const wrapper = mountHost()

    localStorage.setItem(LEGACY_CHAT_KEY, JSON.stringify({ version: 2, value: {} }))

    // isAuthenticated 保持 false（初值），不应触发清理
    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    expect(localStorage.getItem(LEGACY_CHAT_KEY)).toBeTruthy()
  })

  it('重新登录不会清理 —— 清理只发生在 true → false', async () => {
    const app = useAppStore()
    const wrapper = mountHost()

    app.isAuthenticated = true
    await wrapper.vm.$nextTick()
    localStorage.setItem(LEGACY_CHAT_KEY, JSON.stringify({ version: 2, value: {} }))

    // 再次置 true（如 restoreSession 重复调用）不应清空
    app.isAuthenticated = true
    await wrapper.vm.$nextTick()

    expect(localStorage.getItem(LEGACY_CHAT_KEY)).toBeTruthy()
  })
})

describe('useSessionCleanup — Query 缓存', () => {
  /**
   * 与对话历史同一类问题，只是载体不同。
   *
   * Query 的 gcTime 是 5 分钟，期间工单列表、告警、审批队列、审计日志
   * 都原样留在内存。下一个用户在同一标签页登录后若命中相同 queryKey，
   * **会先看到上一个人的数据**——stale-while-revalidate 的默认行为是
   * 先渲染缓存再后台刷新。
   *
   * 对只读用户尤其严重：他本无权看到的工单标题、审批摘要、审计里的
   * AI 问答，会在刷新完成前完整呈现。
   */
  it('登出后 Query 缓存被清空 —— 下一个登录者读不到上一个人的数据', async () => {
    const app = useAppStore()
    app.isAuthenticated = true
    const wrapper = mountHost()

    await queryClient.fetchQuery({
      queryKey: ['tickets', 'list', { page: 1 }],
      queryFn: async () => ({ items: [{ id: 'TKT-1', title: '支付链路熔断阈值调整' }] }),
    })
    expect(queryClient.getQueryData(['tickets', 'list', { page: 1 }])).toBeTruthy()

    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    expect(queryClient.getQueryData(['tickets', 'list', { page: 1 }])).toBeUndefined()
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
  })

  it('用 clear 而非 invalidate —— 后者只标过期，仍会先渲染旧数据', async () => {
    const app = useAppStore()
    app.isAuthenticated = true
    const wrapper = mountHost()

    await queryClient.fetchQuery({
      queryKey: ['approvals', 'pending'],
      queryFn: async () => ({ items: [{ id: 9, summary: '重启支付网关' }] }),
    })

    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    // invalidate 的话数据还在（只是 stale），这里必须是彻底移除
    expect(queryClient.getQueryData(['approvals', 'pending'])).toBeUndefined()
  })

  it('冷启动不清 Query 缓存 —— 否则每次刷新都白拉一遍', async () => {
    const app = useAppStore()
    const wrapper = mountHost()

    await queryClient.fetchQuery({
      queryKey: ['dashboard', 'overview'],
      queryFn: async () => ({ totalTickets: 42 }),
    })

    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    expect(queryClient.getQueryData(['dashboard', 'overview'])).toBeTruthy()
  })
})
