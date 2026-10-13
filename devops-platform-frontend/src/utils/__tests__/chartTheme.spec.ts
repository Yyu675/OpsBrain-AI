/**
 * chartTheme 单元测试（批92）
 *
 * jsdom 无 canvas 后端（getImageData 抛 Not Implemented），因此这里保护的重点是
 * **降级路径**：解析失败必须回落到调用方给的兜底色，绝不能把空串/undefined
 * 喂给 ECharts（会渲染成黑块）。真实浏览器下的令牌解析依赖 canvas 归一，
 * 由 /design-system 页面人工逐轴验收。
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { chartChromeColors, chartPalette, resolveChartColor, useThemeVersion } from '../chartTheme'

describe('resolveChartColor', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('jsdom 无 canvas 后端时回落到传入的兜底色（不产出空串）', () => {
    const c = resolveChartColor('--chart-1', '#409eff')
    expect(c).toBe('#409eff')
    expect(c).not.toBe('')
  })

  it('令牌值为空（getComputedStyle 拿不到 --xxx）同样走兜底', () => {
    // jsdom 默认没有主题样式表，getPropertyValue 返回 ''，等价于此路径
    expect(resolveChartColor('--no-such-token', '#123456')).toBe('#123456')
  })

  it('getComputedStyle 整个缺失（SSR 边界）不抛错，返回兜底', () => {
    vi.stubGlobal('getComputedStyle', undefined)
    expect(() => resolveChartColor('--chart-2', '#654321')).not.toThrow()
    expect(resolveChartColor('--chart-2', '#654321')).toBe('#654321')
  })
})

describe('chartPalette', () => {
  it('产出 6 槽且逐槽非空（缺 canvas 时全部落到兜底序）', () => {
    const p = chartPalette()
    expect(p).toHaveLength(6)
    p.forEach(c => expect(typeof c === 'string' && c.length).toBeTruthy())
  })
})

describe('chartChromeColors', () => {
  it('tooltipText 解析失败时给可读深灰而非空串（暗色可读性回归锚）', () => {
    const c = chartChromeColors()
    for (const key of ['axisText', 'splitLine', 'tooltipBg', 'tooltipText', 'targetLine'] as const) {
      expect(c[key]).toBeTruthy()
    }
    expect(c.tooltipText).toBe('#303133') // jsdom 降级 = 兜底值
  })
})

describe('useThemeVersion', () => {
  it('模块级单例：两次调用返回同一 ref（多图表共用一个 observer）', () => {
    expect(useThemeVersion()).toBe(useThemeVersion())
  })
})
