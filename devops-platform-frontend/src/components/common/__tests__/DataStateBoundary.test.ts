/**
 * DataStateBoundary 四态优先级测试。
 *
 * 这个组件承载的是**五个列表页共用的状态机**，一旦优先级写错，
 * 五个页面同时出问题。而它要修的原始缺陷恰恰来自各页各写一份时的漂移：
 *
 *   - ActionItemBoard 的 `v-if="loading"` 漏了 length 判断
 *     → 改筛选时已有列表整个消失再出现，内容闪断
 *   - ApprovalCenter / KnowledgeBase 的错误分支漏了 length 判断
 *     → 翻页失败时手上的数据被换成错误页
 *
 * 所以「有数据优先」不是风格偏好，是这些用例要锁死的契约。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import DataStateBoundary from '../DataStateBoundary.vue'

const CONTENT = '<ul class="real-content"><li>一条真实数据</li></ul>'

const mountBoundary = (props: Record<string, unknown> = {}) =>
  mount(DataStateBoundary, {
    props,
    slots: { default: CONTENT },
    global: { stubs: { ApiErrorState: true, AppEmpty: true, SkeletonRows: true } },
  })

describe('DataStateBoundary — 首屏（无数据）', () => {
  it('加载中显示骨架，不显示内容', () => {
    const w = mountBoundary({ loading: true, count: 0 })
    expect(w.findComponent({ name: 'SkeletonRows' }).exists()).toBe(true)
    expect(w.find('.real-content').exists()).toBe(false)
  })

  it('有错误显示错误态（可重试）', () => {
    const w = mountBoundary({ loading: false, count: 0, error: new Error('boom') })
    expect(w.findComponent({ name: 'ApiErrorState' }).exists()).toBe(true)
    expect(w.findComponent({ name: 'SkeletonRows' }).exists()).toBe(false)
  })

  it('无错误无数据显示空态', () => {
    const w = mountBoundary({ loading: false, count: 0 })
    expect(w.findComponent({ name: 'AppEmpty' }).exists()).toBe(true)
  })

  it('加载中优先于错误 —— 重试进行中不该还显示上一次的失败', () => {
    const w = mountBoundary({ loading: true, count: 0, error: new Error('旧错误') })
    expect(w.findComponent({ name: 'SkeletonRows' }).exists()).toBe(true)
    expect(w.findComponent({ name: 'ApiErrorState' }).exists()).toBe(false)
  })
})

describe('DataStateBoundary — 有数据时的「就地刷新」', () => {
  it('有数据 + 加载中：保留内容，不换骨架', () => {
    const w = mountBoundary({ loading: true, count: 10 })
    // 这是 ActionItemBoard 原缺陷的回归防线
    expect(w.find('.real-content').exists()).toBe(true)
    expect(w.findComponent({ name: 'SkeletonRows' }).exists()).toBe(false)
  })

  it('有数据 + 加载中：显示顶部细进度条', () => {
    const w = mountBoundary({ loading: true, count: 10 })
    expect(w.find('.data-state__progress').exists()).toBe(true)
  })

  it('有数据 + 不加载：不显示进度条', () => {
    const w = mountBoundary({ loading: false, count: 10 })
    expect(w.find('.data-state__progress').exists()).toBe(false)
  })

  it('有数据 + 刷新失败：保留内容并给可重试的提示条', () => {
    // ApprovalCenter / KnowledgeBase 原缺陷的回归防线：
    // 不能因为刷新失败就把用户手上的数据清空
    const w = mountBoundary({ loading: false, count: 10, error: new Error('刷新失败') })
    expect(w.find('.real-content').exists()).toBe(true)
    expect(w.find('.data-state__stale').exists()).toBe(true)
    expect(w.findComponent({ name: 'ApiErrorState' }).exists()).toBe(false)
  })

  it('有数据 + 加载中 + 有旧错误：不显示失败提示条（正在重试中）', () => {
    const w = mountBoundary({ loading: true, count: 10, error: new Error('旧错误') })
    expect(w.find('.data-state__stale').exists()).toBe(false)
    expect(w.find('.data-state__progress').exists()).toBe(true)
  })
})

describe('DataStateBoundary — 事件与空态语义', () => {
  it('错误态重试按钮派发 retry', async () => {
    const w = mount(DataStateBoundary, {
      props: { loading: false, count: 0, error: new Error('x') },
      slots: { default: CONTENT },
    })
    await w.findComponent({ name: 'ApiErrorState' }).vm.$emit('retry')
    expect(w.emitted('retry')).toHaveLength(1)
  })

  it('陈旧数据提示条的重试按钮同样派发 retry', async () => {
    const w = mountBoundary({ loading: false, count: 5, error: new Error('x') })
    await w.find('.data-state__stale-retry').trigger('click')
    expect(w.emitted('retry')).toHaveLength(1)
  })

  it('filtered=true 时空态用 search 语义 —— 「筛选没命中」与「还没有数据」是两回事', () => {
    const w = mount(DataStateBoundary, {
      props: { loading: false, count: 0, filtered: true, filteredDescription: '换个关键词试试' },
      slots: { default: CONTENT },
    })
    const empty = w.findComponent({ name: 'AppEmpty' })
    expect(empty.props('kind')).toBe('search')
    expect(empty.props('description')).toBe('换个关键词试试')
  })

  it('filtered=false 时用 default 语义与对应文案', () => {
    const w = mount(DataStateBoundary, {
      props: { loading: false, count: 0, filtered: false, emptyDescription: '还没有数据' },
      slots: { default: CONTENT },
    })
    const empty = w.findComponent({ name: 'AppEmpty' })
    expect(empty.props('kind')).toBe('default')
    expect(empty.props('description')).toBe('还没有数据')
  })

  it('空态主按钮派发 empty-action', async () => {
    const w = mount(DataStateBoundary, {
      props: { loading: false, count: 0, emptyActionText: '新建' },
      slots: { default: CONTENT },
    })
    await w.findComponent({ name: 'AppEmpty' }).vm.$emit('action')
    expect(w.emitted('empty-action')).toHaveLength(1)
  })

  it('自定义 empty 插槽可覆盖默认空态', () => {
    const w = mount(DataStateBoundary, {
      props: { loading: false, count: 0 },
      slots: { default: CONTENT, empty: '<p class="custom-empty">自定义空态</p>' },
    })
    expect(w.find('.custom-empty').exists()).toBe(true)
    expect(w.findComponent({ name: 'AppEmpty' }).exists()).toBe(false)
  })
})
