/**
 * chartTheme 单元测试（批92 建降级路径；批93 补 canvas 归一路径 + 主题版本递增）
 *
 * 三层合同：
 * 1. 降级：jsdom 无 canvas 后端 / 令牌缺失 / getComputedStyle 缺失 → 回落调用方兜底色，
 *    绝不产出空串（ECharts 拿到空色会整块消失）。
 * 2. 归一（批93）：用 spy 假 canvas 模拟真浏览器——令牌值过 canvas 解析成 rgb/rgba；
 *    非法色赋值被浏览器拒收（fillStyle 保持上次值）时必须回落兜底而不是黑块。
 *    Chrome/Firefox 把 '#000' 规范化成 '#000000'，哨兵比较必须用规范化后的值。
 * 3. 响应：主题属性（class/data-theme/data-density）变化递增版本号——图表重绘的触发源。
 *
 * 真浏览器四轴视觉仍由 /design-system 人工验收（AGENTS.md 五之二）。
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { chartChromeColors, chartPalette, resolveChartColor, useThemeVersion } from '../chartTheme'

let savedGetContext: typeof HTMLCanvasElement.prototype.getContext | null = null

/**
 * 装一个「会按 Chrome 语义规范化」的假 canvas 2d 后端：
 * - 只有 map 里登记过的颜色才赋得进 fillStyle，其余赋值被忽略（非法色 = 保持旧值）
 * - '#000' 规范化成 '#000000'（对齐真实浏览器序列化）
 * - getImageData 按当前 fillStyle 的 hex 返回像素
 */
function patchCanvas(extra: Record<string, string> = {}): void {
  const map: Record<string, string> = { '#000': '#000000', ...extra }
  let fill = ''
  const fake = {
    set fillStyle(v: string) { const n = map[v]; if (n !== undefined) fill = n },
    get fillStyle() { return fill },
    clearRect() { /* noop */ },
    fillRect() { /* noop */ },
    getImageData() {
      const h = fill.slice(1)
      const r = parseInt(h.slice(0, 2), 16)
      const g = parseInt(h.slice(2, 4), 16)
      const b = parseInt(h.slice(4, 6), 16)
      const a = h.length === 8 ? parseInt(h.slice(6, 8), 16) : 255
      return { data: new Uint8ClampedArray([r, g, b, a]) }
    },
  }
  if (!savedGetContext) savedGetContext = HTMLCanvasElement.prototype.getContext
  HTMLCanvasElement.prototype.getContext =
    (() => fake) as unknown as typeof HTMLCanvasElement.prototype.getContext
}

function restoreCanvas(): void {
  if (savedGetContext) {
    HTMLCanvasElement.prototype.getContext = savedGetContext
    savedGetContext = null
  }
}

describe('resolveChartColor', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    restoreCanvas()
  })

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

  it('工作 canvas 下令牌过归一返回 rgb()，不落兜底（ECharts 主路径）', () => {
    patchCanvas({ 'oklch(0.55 0.15 258)': '#409eff' })
    vi.stubGlobal('getComputedStyle', () => ({
      getPropertyValue: (n: string) => (n === '--chart-1' ? 'oklch(0.55 0.15 258)' : ''),
    }))
    expect(resolveChartColor('--chart-1', '#dead00')).toBe('rgb(64, 158, 255)')
  })

  it('浏览器拒收非法色（fillStyle 保持哨兵）时回落兜底而非黑块（批93 回归锚）', () => {
    // 假 canvas 不认 'not-a-color' → fillStyle 保持规范化哨兵 '#000000'。
    // 旧实现拿原始值 '#000' 比较判不出来，会把 rgb(0, 0, 0) 黑块喂给 ECharts。
    patchCanvas()
    vi.stubGlobal('getComputedStyle', () => ({
      getPropertyValue: (n: string) => (n === '--chart-1' ? 'not-a-color' : ''),
    }))
    expect(resolveChartColor('--chart-1', '#dead00')).toBe('#dead00')
  })

  it('半透明色归一成 rgba(...) 且保留三位小数（透明度层：面积图/描边）', () => {
    patchCanvas({ '#ff000080': '#ff000080' })
    expect(resolveChartColor('#ff000080', '#unused')).toBe('rgba(255, 0, 0, 0.502)')
  })

  it('令牌值为空但 canvas 可用：在取像素前即回落（不读空 fill）', () => {
    patchCanvas()
    vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: () => '' }))
    expect(resolveChartColor('--chart-4', '#f56c6c')).toBe('#f56c6c')
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

  it('html 主题属性变化递增版本（图表重绘的触发源）', async () => {
    const v = useThemeVersion()
    document.documentElement.classList.remove('dark')
    const before = v.value

    document.documentElement.classList.add('dark')
    await Promise.resolve()
    await Promise.resolve()
    expect(v.value).toBe(before + 1)

    document.documentElement.classList.remove('dark')
    await Promise.resolve()
    await Promise.resolve()
    expect(v.value).toBe(before + 2) // 复原也是一次变化，环境不残留
  })
})
