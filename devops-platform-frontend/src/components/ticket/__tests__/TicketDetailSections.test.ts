/**
 * 工单详情本次拆出的两个子组件的渲染与事件契约测试。
 *
 * 拆分前这些 DOM 由 TicketDetail.vue 直接渲染，而 TicketDetail 的四份测试
 * 对「阶段条点了抛哪个阶段」「动作类型徽标显示中文还是枚举值」「验证弹窗
 * 勾选跳过后按钮文案切换」一条断言都没有——搬运中把事件接反或把 v-for
 * 的值写成外层变量，现有用例照样全绿。这里把那道缺口补上。
 *
 * 断言只取「正确实现与错误实现答案不同」的点：事件载荷断的是具体阶段值，
 * 徽标断的是中文标签而非原始枚举，跳过分支断的是按钮文案与表单切换同时发生。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import type { TicketActionRecord } from '@/api/tickets'

import TicketActionSection from '../TicketActionSection.vue'
import TicketFormDialogs from '../TicketFormDialogs.vue'

const action = (over: Partial<TicketActionRecord> = {}): TicketActionRecord => ({
  ticketId: 'TKT-1',
  actionType: 'FIX',
  summary: '调大内存限制',
  operator: '张明',
  createTime: '10:30',
  ...over,
})

describe('TicketActionSection', () => {
  const mountSection = (over: Record<string, unknown> = {}) =>
    mount(TicketActionSection, {
      props: {
        showStageSwitcher: true,
        currentStage: 'TRIAGE',
        actions: [],
        ...over,
      },
    })

  it('处理中显示四个阶段按钮，当前阶段高亮', () => {
    const w = mountSection()
    const buttons = w.findAll('.stage-btn')

    expect(buttons.map(b => b.text())).toEqual(['排查中', '已止损', '修复中', '验证中'])
    expect(buttons[0].classes()).toContain('active')
    expect(buttons[1].classes()).not.toContain('active')
  })

  it('非处理中状态不渲染阶段条', () => {
    const w = mountSection({ showStageSwitcher: false })
    expect(w.find('.stage-switcher').exists()).toBe(false)
  })

  it('点普通阶段抛出该阶段值，点「已止损」改走标记止损', async () => {
    const w = mountSection()
    const buttons = w.findAll('.stage-btn')

    await buttons[2].trigger('click')
    expect(w.emitted('update-stage')).toEqual([['FIXING']])
    expect(w.emitted('mark-mitigated')).toBeUndefined()

    await buttons[1].trigger('click')
    expect(w.emitted('mark-mitigated')).toHaveLength(1)
    // 「已止损」不能同时冒出普通阶段切换——那会把止损记成一次阶段变更
    expect(w.emitted('update-stage')).toEqual([['FIXING']])
  })

  it('动作列表把枚举值翻成中文徽标，并按有效性分色', () => {
    const w = mountSection({
      actions: [
        action({ id: 1, actionType: 'ROLLBACK', effective: false }),
        action({ id: 2, actionType: 'MYSTERY_TYPE', effective: true, summary: '未知动作' }),
      ],
    })
    const items = w.findAll('.action-item')

    expect(items[0].find('.action-type-badge').text()).toBe('回滚')
    expect(items[0].classes()).toContain('action-ineffective')
    expect(items[0].find('.eff-no').text()).toBe('无效')
    // 未知枚举原样显示而不是空徽标——丢了类型信息比显示英文更难排查
    expect(items[1].find('.action-type-badge').text()).toBe('MYSTERY_TYPE')
    expect(items[1].find('.eff-ok').text()).toBe('有效')
  })

  it('没有处置动作时不渲染动作列表', () => {
    const w = mountSection({ actions: [] })
    expect(w.find('.action-list-section').exists()).toBe(false)
  })
})

describe('TicketFormDialogs', () => {
  // el-dialog 在 jsdom 下遮罩恒为 display:none 且文本不进 textContent，
  // 不能靠「可见文本」断言。表单标签与按钮是原生元素，直接按选择器取。
  // el-dialog 在 jsdom 下内容被其内部 transition 丢弃（遮罩渲染为空），
  // 换成透传插槽的桩后，断言的就是组件自己的表单模板。
  const dialogStub = { template: '<div><slot /><slot name="footer" /></div>' }
  const buttonStub = { name: 'ElButton', template: '<button :disabled="disabled"><slot /></button>', props: ['disabled'] }
  const mountDialogs = (over: Record<string, unknown> = {}) =>
    mount(TicketFormDialogs, {
      global: { stubs: { 'el-dialog': dialogStub, 'el-button': buttonStub } },
      props: {
        actionDialogVisible: true,
        actionForm: { actionType: 'INVESTIGATE', summary: '', detail: '', effective: null },
        verifyDialogVisible: true,
        verifyForm: { method: 'MONITOR', conclusion: '', skip: false, skipReason: '' },
        rootCauseDialogVisible: true,
        rootCauseForm: { rootCause: '', category: 'UNKNOWN' },
        actionSubmitting: false,
        verifySubmitting: false,
        rootCauseSubmitting: false,
        ...over,
      },
    })

  const labels = (w: ReturnType<typeof mountDialogs>) =>
    w.findAll('.form-row label').map(l => l.text())
  // 按钮是 el-button 组件：桩后的文本落在组件根节点上，按组件文本匹配
  const button = (w: ReturnType<typeof mountDialogs>, text: string) =>
    w.findAllComponents({ name: 'ElButton' }).find(b => b.text() === text)

  it('三个弹窗的表单标签都渲染出来', () => {
    const w = mountDialogs()
    const all = labels(w)
    expect(all).toEqual(expect.arrayContaining(['动作类型', '摘要', '详情', '是否有效']))
    expect(all).toEqual(expect.arrayContaining(['验证方式', '验证结论']))
    expect(all).toEqual(expect.arrayContaining(['根因分类', '根因描述']))
    w.unmount()
  })

  it('点提交抛 add-action，提交中按钮禁用', async () => {
    const w = mountDialogs()
    await button(w, '提交')!.trigger('click')
    expect(w.emitted('add-action')).toHaveLength(1)

    await w.setProps({ actionSubmitting: true })
    expect(button(w, '提交')!.attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('勾选跳过验证后：验证表单换成跳过理由，按钮文案变为「跳过并解决」', async () => {
    const w = mountDialogs()
    expect(labels(w)).toContain('验证方式')
    expect(labels(w)).not.toContain('跳过理由')
    expect(button(w, '验证通过')).toBeTruthy()

    await w.setProps({
      verifyForm: { method: 'MONITOR', conclusion: '', skip: true, skipReason: '' },
    })

    expect(labels(w)).not.toContain('验证方式')
    expect(labels(w)).toContain('跳过理由')
    await button(w, '跳过并解决')!.trigger('click')
    expect(w.emitted('submit-verification')).toHaveLength(1)
    w.unmount()
  })

  it('根因弹窗点确认抛 confirm-root-cause', async () => {
    const w = mountDialogs()
    await button(w, '确认根因')!.trigger('click')
    expect(w.emitted('confirm-root-cause')).toHaveLength(1)
    w.unmount()
  })
})
