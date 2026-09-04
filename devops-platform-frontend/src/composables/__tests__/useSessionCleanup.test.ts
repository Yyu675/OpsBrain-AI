/**
 * 登出清理测试。
 *
 * 保护一条越权读取的防线：AI 对话历史持久化在 localStorage 且不按用户隔离，
 * 会话里的 citations 是知识库原文片段，而知识库有可见性分级。
 * 登出不清 = 下一个登录的人能读到本不该看到的内部文档内容，
 * 绕过后端的权限域隔离。
 *
 * 之所以要专门测「冷启动不清理」：watch 在 isAuthenticated 初始化时
 * 也会触发一次，写漏 wasAuthed 判断就会在每次刷新页面时把对话抹掉——
 * 这是修复本身很容易引入的回归。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'

import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

import { useAppStore } from '@/stores/app'
import { useChatStore } from '@/stores/chat'
import { useSessionCleanup } from '../useSessionCleanup'

const Host = defineComponent({
  setup() {
    useSessionCleanup()
    return () => h('div')
  },
})

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

const seedConversation = (chat: ReturnType<typeof useChatStore>) => {
  chat.ensureSession('T-1001')
  chat.pushUserMessage('生产库主从延迟怎么查')
  const msg = chat.startAssistantMessage('生产库主从延迟怎么查')
  chat.appendToken('先看 seconds_behind_master')
  chat.mergeMetadata({ citations: ['内部runbook：主从延迟处置 SOP 第 3 节'] })
  chat.finishStreaming()
  return msg
}

describe('useSessionCleanup', () => {
  it('登出后清空所有会话桶 —— 下一个登录的人看不到上一个人的对话', async () => {
    const app = useAppStore()
    const chat = useChatStore()

    app.isAuthenticated = true
    const wrapper = mountHost()

    seedConversation(chat)
    chat.ensureSession('global')
    chat.pushUserMessage('另一条')
    expect(Object.keys(chat.buckets).length).toBeGreaterThan(1)

    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    expect(Object.keys(chat.buckets)).toHaveLength(0)
  })

  it('登出会抹掉 localStorage 里的持久化会话，而非只清内存', async () => {
    const app = useAppStore()
    const chat = useChatStore()

    app.isAuthenticated = true
    const wrapper = mountHost()

    seedConversation(chat)
    // 绕过 400ms 防抖，确保磁盘上确实有东西可清
    localStorage.setItem(
      '__store__:chat-sessions',
      JSON.stringify({ version: 2, value: chat.buckets, savedAt: Date.now() })
    )
    expect(localStorage.getItem('__store__:chat-sessions')).toBeTruthy()

    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    expect(localStorage.getItem('__store__:chat-sessions')).toBeNull()
  })

  it('引用原文（citations）随会话一并清除 —— 这才是越权读取的载体', async () => {
    const app = useAppStore()
    const chat = useChatStore()

    app.isAuthenticated = true
    const wrapper = mountHost()
    seedConversation(chat)

    const dump = () => JSON.stringify(chat.buckets)
    expect(dump()).toContain('主从延迟处置 SOP')

    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    expect(dump()).not.toContain('主从延迟处置 SOP')
  })

  it('冷启动（从未登录）不触发清理 —— 否则每次刷新都会抹掉对话', async () => {
    const chat = useChatStore()
    const app = useAppStore()

    // 模拟「上次会话留在本地、本次尚未恢复登录态」
    const wrapper = mountHost()
    seedConversation(chat)
    const before = Object.keys(chat.buckets).length

    // isAuthenticated 保持 false（初值），不应触发清理
    app.isAuthenticated = false
    await wrapper.vm.$nextTick()

    expect(Object.keys(chat.buckets)).toHaveLength(before)
  })

  it('重新登录不会清理 —— 清理只发生在 true → false', async () => {
    const app = useAppStore()
    const chat = useChatStore()

    const wrapper = mountHost()
    app.isAuthenticated = true
    await wrapper.vm.$nextTick()

    seedConversation(chat)
    const before = Object.keys(chat.buckets).length

    // 再次置 true（如 restoreSession 重复调用）不应清空
    app.isAuthenticated = true
    await wrapper.vm.$nextTick()

    expect(Object.keys(chat.buckets)).toHaveLength(before)
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
