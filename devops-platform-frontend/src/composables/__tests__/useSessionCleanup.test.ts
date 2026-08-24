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

import { useAppStore } from '@/stores/app'
import { useChatStore } from '@/stores/chat'
import { useSessionCleanup } from '../useSessionCleanup'

const Host = defineComponent({
  setup() {
    useSessionCleanup()
    return () => h('div')
  },
})

beforeEach(() => {
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
    const wrapper = mount(Host)

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
    const wrapper = mount(Host)

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
    const wrapper = mount(Host)
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
    const wrapper = mount(Host)
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

    const wrapper = mount(Host)
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
