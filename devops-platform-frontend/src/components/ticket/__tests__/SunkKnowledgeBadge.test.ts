/**
 * SunkKnowledgeBadge（#7 已沉淀徽标 + 反馈计数）组件测试。
 *
 * 重点测三类静默退化的边界——徽标是「次要信息」，坏在哪里都不会报错，
 * 只会显示错或显示不出来：
 * 1. 反馈计数缺失（后端无 boost 记录）→ 计数归 0 而非 undefined 或崩；
 * 2. 两个列表长度不一致/某一半请求失败 → 不拖垮另一半；
 * 3. 无回链文档 → 渲染空（不占布局）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SunkKnowledgeBadge from '../SunkKnowledgeBadge.vue'

vi.mock('@/api/knowledge', () => ({
  findDocsBySourceTicket: vi.fn(),
  fetchFeedbackStatsBySourceTicket: vi.fn(),
}))

import { findDocsBySourceTicket, fetchFeedbackStatsBySourceTicket } from '@/api/knowledge'

const mockedDocs = vi.mocked(findDocsBySourceTicket)
const mockedStats = vi.mocked(fetchFeedbackStatsBySourceTicket)

describe('SunkKnowledgeBadge', () => {
  beforeEach(() => {
    mockedDocs.mockReset()
    mockedStats.mockReset()
  })

  it('渲染回链文档 + 反馈计数（有帮助/没用）', async () => {
    mockedDocs.mockResolvedValue([
      { id: 11, title: '【故障复盘】连接池耗尽', category: null, author: null, summary: null, version: 1, status: 'PUBLISHED', indexStatus: 'INDEXED', chunkCount: 3, createTime: '', updateTime: '', tags: [], sourceTicketId: 'TKT-1', sourceType: 'TICKET' },
    ])
    mockedStats.mockResolvedValue([{ docId: 11, title: '【故障复盘】连接池耗尽', helpfulCount: 4, wrongCount: 1 }])

    const w = mount(SunkKnowledgeBadge, { props: { ticketId: 'TKT-1' } })
    await vi.waitFor(() => expect(w.text()).toContain('已沉淀为知识'))

    expect(w.text()).toContain('1 篇')
    expect(w.text()).toContain('👍 4')
    expect(w.text()).toContain('👎 1')
  })

  it('反馈计数缺失（无 boost 记录）时归 0 而非显示 undefined', async () => {
    mockedDocs.mockResolvedValue([
      { id: 12, title: '手册B', category: null, author: null, summary: null, version: 1, status: 'DRAFT', indexStatus: 'SKIPPED', chunkCount: 0, createTime: '', updateTime: '', tags: [], sourceTicketId: 'TKT-1', sourceType: 'TICKET' },
    ])
    mockedStats.mockResolvedValue([])

    const w = mount(SunkKnowledgeBadge, { props: { ticketId: 'TKT-1' } })
    await vi.waitFor(() => expect(w.text()).toContain('已沉淀为知识'))

    expect(w.text()).toContain('👍 0')
    expect(w.text()).toContain('👎 0')
  })

  it('无回链文档 → 渲染空（不占布局）', async () => {
    mockedDocs.mockResolvedValue([])
    mockedStats.mockResolvedValue([])

    const w = mount(SunkKnowledgeBadge, { props: { ticketId: 'TKT-1' } })
    await vi.waitFor(() => expect(mockedDocs).toHaveBeenCalled())

    expect(w.text()).not.toContain('已沉淀为知识')
  })

  it('空 ticketId → 不请求、渲染空', async () => {
    const w = mount(SunkKnowledgeBadge, { props: { ticketId: '' } })

    expect(mockedDocs).not.toHaveBeenCalled()
    expect(w.text()).not.toContain('已沉淀为知识')
  })

  it('点击文档跳转：emit goto-doc', async () => {
    mockedDocs.mockResolvedValue([
      { id: 13, title: '手册C', category: null, author: null, summary: null, version: 1, status: 'PUBLISHED', indexStatus: 'INDEXED', chunkCount: 0, createTime: '', updateTime: '', tags: [], sourceTicketId: 'TKT-1', sourceType: 'TICKET' },
    ])
    mockedStats.mockResolvedValue([])

    const w = mount(SunkKnowledgeBadge, { props: { ticketId: 'TKT-1' } })
    await vi.waitFor(() => expect(w.text()).toContain('已沉淀为知识'))

    await w.find('.badge-doc').trigger('click')
    expect(w.emitted('goto-doc')).toEqual([[13]])
  })
})