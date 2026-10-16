/**
 * ObservationStatsPanel —— 自愈观察窗面板测试。
 *
 * 要守住的契约：
 * - 观察窗关闭（enabled=false）→ 整块隐藏，不显示一排误导性的 0
 * - 分母为 0（还没有观察级告警走完过窗口）→ 自愈率显示「—」而非 0%
 *   （0% 会被读成「机制没用」，而真相是「还没数据」——6.38 口径纪律）
 * - 有数据时四个读数都在，自愈率按 自愈/(自愈+转单) 计算
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

const api = vi.hoisted(() => ({
  fetchObservationStats: vi.fn(),
}))
vi.mock('@/api/alerts', () => api)

import ObservationStatsPanel from '../ObservationStatsPanel.vue'

const mountPanel = async () => {
  const wrapper = mount(ObservationStatsPanel, {
    global: {
      plugins: [[VueQueryPlugin, {
        queryClient: new QueryClient({
          defaultOptions: { queries: { retry: false } },
        }),
      }]],
    },
  })
  await flushPromises()
  return wrapper
}

const stats = (over: Record<string, unknown> = {}) => ({
  enabled: true,
  windowMinutes: 10,
  levels: ['P2', 'P3'],
  observingNow: 3,
  selfHealed30d: 18,
  escalated30d: 6,
  ...over,
})

beforeEach(() => {
  vi.clearAllMocks()
})

describe('ObservationStatsPanel', () => {
  it('观察窗开启时渲染四个读数与自愈率', async () => {
    api.fetchObservationStats.mockResolvedValue(stats())

    const w = await mountPanel()

    expect(w.text()).toContain('自愈观察窗')
    expect(w.text()).toContain('观察中')
    expect(w.text()).toContain('18')          // 30 日自愈
    expect(w.text()).toContain('6')           // 30 日转单
    expect(w.text()).toContain('75.0%')       // 18 / (18+6)
  })

  it('观察窗关闭时整块隐藏 —— 不显示一排误导性的 0', async () => {
    api.fetchObservationStats.mockResolvedValue(stats({ enabled: false }))

    const w = await mountPanel()

    expect(w.find('.observation-panel').exists()).toBe(false)
    expect(w.text()).not.toContain('自愈观察窗')
  })

  it('分母为 0 时自愈率显示「—」而非 0%', async () => {
    api.fetchObservationStats.mockResolvedValue(stats({ selfHealed30d: 0, escalated30d: 0 }))

    const w = await mountPanel()

    expect(w.text()).toContain('—')
    expect(w.text()).not.toContain('0.0%')
  })

  it('接口失败降级为隐藏（统计读数不是主流程，不能拖垮大盘）', async () => {
    api.fetchObservationStats.mockResolvedValue(null)

    const w = await mountPanel()

    expect(w.find('.observation-panel').exists()).toBe(false)
  })
})
