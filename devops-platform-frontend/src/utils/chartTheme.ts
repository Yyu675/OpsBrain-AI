/**
 * ECharts 色板的主题桥（批92）
 *
 * 为什么需要这一层：ECharts 画在 canvas 上？**canvas 不认 CSS 变量**——
 * `color: 'var(--chart-1)'` 会被当成非法颜色静默回落成黑色，暗色下就是一片糊。
 * 此前 TrendChart / Dashboard 因此各自硬编码了一套 Element 默认色（#409eff…），
 * 后果是主题四轴（明暗/配色预设）对图表完全不生效：卡片变了、图里轴文字
 * #909399 配 tooltip #303133 在深底上直接瞎掉。
 *
 * 本模块的解法：**取色时把令牌值过一遍 canvas**（1×1 fillRect 再读回），
 * 得到规范化的 rgb/rgba 字符串喂给 ECharts。这样
 * - 明暗/配色轴切换后重新取色即拿到新值（消费方 watch 主题变化重绘图表）；
 * - 支持 `color-mix()` 等浏览器端才能求值的写法；
 * - 令牌缺失/无法解析时回落调用方给的兜底值，不让图表整块消失。
 *
 * 浏览器基线 Chrome 111+（见 theme.css 头注），oklch 在 canvas fillStyle 中可用；
 * 若极端环境下解析失败（fillStyle 回写为空串），同样走回落。
 */

import { ref, type Ref } from 'vue'

/** 主题签名的取样属性——任一变化都意味着令牌集换了一套 */
const THEME_ATTRS = ['class', 'data-theme', 'data-density'] as const

type Canvas2d = CanvasRenderingContext2D

let probeCanvas: HTMLCanvasElement | null = null

function ctx(): Canvas2d | null {
  if (typeof document === 'undefined') return null
  if (!probeCanvas) probeCanvas = document.createElement('canvas')
  probeCanvas.width = 1
  probeCanvas.height = 1
  try {
    return probeCanvas.getContext('2d', { willReadFrequently: true })
  } catch {
    return null
  }
}

/**
 * 读 CSS 自定义属性的当前值（不做 canvas 归一）。
 * jsdom / SSR 下 getComputedStyle 可能拿不到值，返回 ''。
 */
function cssTokenValue(name: string): string {
  if (typeof document === 'undefined' || typeof window === 'undefined') return ''
  try {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  } catch {
    return ''
  }
}

/**
 * 把一个 CSS 颜色（可以是 var/oklch/color-mix 写法）解析成 canvas 安全色。
 *
 * @param value 颜色表达式，例如 `var(--brand)`、`oklch(0.42 0.13 258)`
 * @param fallback 解析失败时的返回值（建议给同语义的十六进制，保证图表永不黑块）
 */
function resolveCssColor(value: string, fallback: string): string {
  const c = ctx()
  if (!c || !value) return fallback
  try {
    // 先看浏览器是否认这个颜色：非法值会被忽略、fillStyle 保持上一次的值
    c.fillStyle = '#000'
    c.fillStyle = value
    if (c.fillStyle === '#000' || !c.fillStyle) return fallback
    c.clearRect(0, 0, 1, 1)
    c.fillRect(0, 0, 1, 1)
    const [r, g, b, a] = c.getImageData(0, 0, 1, 1).data
    if (a === 0) return fallback
    return a === 255
      ? `rgb(${r}, ${g}, ${b})`
      : `rgba(${r}, ${g}, ${b}, ${(a / 255).toFixed(3)})`
  } catch {
    // jsdom 无 canvas 后端 / 隐私模式取像素失败：交回原值由调用方兜底
    return fallback
  }
}

/** 读主题令牌并解析成 canvas 安全色（内部：对外统一走 resolveChartColor） */
function chartToken(token: string, fallbackColor: string): string {
  return resolveCssColor(cssTokenValue(token), fallbackColor)
}

/**
 * 图表系列色解析：值可以是**主题令牌名**（`--chart-1`，推荐——主题四轴切换后
 * TrendChart 重绘时自动重新取色）或裸 CSS 颜色（原样透传/归一）。
 * 消费方（Dashboard/Effectiveness/TicketInsights…）因此零 import、零响应式样板，
 * 主题响应单点收在 TrendChart 的 buildOption。
 */
export function resolveChartColor(value: string, fallbackColor: string): string {
  if (value.startsWith('--')) return chartToken(value, fallbackColor)
  return resolveCssColor(value, value)
}

/** 图表默认色板（对应 --chart-1..6）；解析失败的槽位用 Element 经典色兜底 */
const PALETTE_FALLBACKS = [
  '#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#62666a',
]

export function chartPalette(): string[] {
  return PALETTE_FALLBACKS.map((fb, i) => chartToken(`--chart-${i + 1}`, fb))
}

/**
 * 图表非数据色（轴文字/网格/tooltip/图例/目标线）。
 * 命名刻意贴合 ECharts 配置位，消费处可直接展开。
 */
export function chartChromeColors() {
  return {
    /** 轴名/刻度文字 */
    axisText: chartToken('--text-3', '#909399'),
    /** 图例文字 */
    legendText: chartToken('--text-2', '#606266'),
    /** 网格分隔线 */
    splitLine: chartToken('--border-1', '#ebeef5'),
    /** X 轴基线 */
    axisLine: chartToken('--border-2', '#dcdfe6'),
    /** tooltip 容器 */
    tooltipBg: chartToken('--surface-3', '#ffffff'),
    tooltipBorder: chartToken('--border-1', '#e4e7ed'),
    tooltipText: chartToken('--text-1', '#303133'),
    /** 目标阈值线（用 warning 而非旧硬编码 #e6a23c，语义=「越线要当回事」） */
    targetLine: chartToken('--warning', '#e6a23c'),
    /** dataZoom 控件描边 */
    zoomBorder: chartToken('--border-1', '#e4e7ed'),
  }
}

/* ── 响应式主题版本 ────────────────────────────────────────────────
   颜色是从 CSS 抄的快照，主题一换就必须重绘。这里用 MutationObserver
   盯 <html> 的主题属性，维护一个全局自增版本号（单例：多个图表共用一个
   observer，不各建一个），组件把它当依赖即可。 */

let themeVersion: Ref<number> | null = null

export function useThemeVersion(): Ref<number> {
  if (themeVersion) return themeVersion
  themeVersion = ref(0)
  if (typeof document !== 'undefined' && typeof MutationObserver !== 'undefined') {
    const obs = new MutationObserver(() => { themeVersion!.value++ })
    obs.observe(document.documentElement, {
      attributes: true,
      attributeFilter: [...THEME_ATTRS],
    })
  }
  return themeVersion
}

