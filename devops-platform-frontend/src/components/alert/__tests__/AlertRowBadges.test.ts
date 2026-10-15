/**
 * AlertRowBadges（观察中 / 未建单 / 误报）单测。
 *
 * 为什么单独测这个小件：三个徽标都是「有则显示」的低频元素，
 * 出错时**不会报错**，只会静静少一个徽标或多一个徽标：
 * - 「未建单」徽标该显不显 → 历史回放告警看去就是系统漏单（F2 要防的正是这个）；
 * - 该隐不隐 → 每行都挂一个「未建单」，值班人从此不看徽标（噪声淹掉信号）；
 * - tooltip 文案若不来自后端 → 前端自己拼阈值口径，与后端必然漂移
 *   （后端说的是「早于补单回溯窗 24 小时」，前端若写死「历史事件」就丢了口径）。
 *
 * 断言取向：只钉「有无 + 文案来源」，不钉样式。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AlertRowBadges from '../AlertRowBadges.vue'

/** el-tooltip 在 jsdom 里走 teleport，桩成「把 content 挂到 data-content」便于断言来源 */
const stubs = {
  ElTooltip: {
    props: ['content'],
    template: '<span class="tip-stub" :data-content="content"><slot /></span>',
  },
}

const mountBadges = (props: Record<string, unknown>) =>
  mount(AlertRowBadges, { props, global: { stubs } })

describe('AlertRowBadges · 未建单徽标（F2）', () => {
  it('unticketedReason 非空 → 渲染「未建单」徽标', () => {
    const wrapper = mountBadges({ unticketedReason: '历史事件：早于补单回溯窗 24 小时，不会再自动建单' })
    const badge = wrapper.find('.unticketed-badge')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('未建单')
  })

  it('tooltip 文案逐字来自后端入参（前端不自己拼阈值口径）', () => {
    const reason = '历史事件：早于补单回溯窗 24 小时，不会再自动建单；如需处置请手工建单'
    const wrapper = mountBadges({ unticketedReason: reason })
    expect(wrapper.find('.tip-stub').attributes('data-content')).toBe(reason)
  })

  it('unticketedReason 缺省 / 空串 → 不渲染徽标（避免每行都挂一个，徽标就不值钱了）', () => {
    expect(mountBadges({}).find('.unticketed-badge').exists()).toBe(false)
    expect(mountBadges({ unticketedReason: '' }).find('.unticketed-badge').exists()).toBe(false)
  })

  it('与「观察中」可共存：一个说「系统在等它自愈」，一个说「不会再自动建单」', () => {
    const wrapper = mountBadges({ observing: true, unticketedReason: '历史事件：早于补单回溯窗 24 小时' })
    expect(wrapper.find('.observing-badge').exists()).toBe(true)
    expect(wrapper.find('.unticketed-badge').exists()).toBe(true)
  })

  it('误报徽标（V17）独立成立，不被未建单影响', () => {
    const wrapper = mountBadges({ falsePositive: true })
    expect(wrapper.find('.fp-badge').exists()).toBe(true)
    expect(wrapper.find('.unticketed-badge').exists()).toBe(false)
  })
})
