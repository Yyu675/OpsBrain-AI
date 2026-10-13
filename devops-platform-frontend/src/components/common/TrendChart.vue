<script setup lang="ts">
/**
 * TrendChart — 共享趋势折线/柱状图（ECharts 按需引入）
 *
 * 多处看板复用（Dashboard / TicketInsights / Monitoring / Effectiveness），一处实现避免各写一套配置漂移。
 *
 * 按需引入而非 `import * as echarts`：整包约 1MB，本项目只用折线+柱状+提示框+图例+网格，
 * tree-shaking 后仅几十 KB。vendor 包已有 2.9MB 告警，不宜再无谓增大。
 *
 * 双 Y 轴：成本（元）与命中率（%）量纲差两个数量级，共用单轴会让成本线压成一条贴底直线。
 * 工单数与命中率同为「计数/百分比」量级，放左轴；成本放右轴。
 */
import { ref, shallowRef, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { use } from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import {
  GridComponent, TooltipComponent, LegendComponent, DataZoomComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import * as echarts from 'echarts/core'
import { chartChromeColors, chartPalette, resolveChartColor, useThemeVersion } from '@/utils/chartTheme'

// 只注册用到的模块——注册缺失会在运行时报「Series xxx not exists」而非编译期发现
use([
  LineChart, BarChart,
  GridComponent, TooltipComponent, LegendComponent, DataZoomComponent,
  CanvasRenderer
])

/** 单条数据系列 */
export interface TrendSeries {
  /** 图例名称 */
  name: string
  /** 数据点，长度须与 labels 一致 */
  data: number[]
  /** 图形类型，默认折线 */
  type?: 'line' | 'bar'
  /** 挂右轴（用于量纲差异大的系列，如成本） */
  useRightAxis?: boolean
  /** 线/柱颜色：传主题令牌名（如 '--chart-1'，推荐）或任意 CSS 颜色；缺省用主题色板 */
  color?: string
  /** 数值后缀，进提示框（如 '%'、' 元'） */
  suffix?: string
  /** 折线是否平滑 */
  smooth?: boolean
  /** 是否填充面积 */
  area?: boolean
}

const props = withDefaults(defineProps<{
  /** 横轴标签（日期） */
  labels: string[]
  /** 数据系列 */
  series: TrendSeries[]
  /** 图表高度 */
  height?: string
  /** 左轴名称 */
  leftAxisName?: string
  /** 右轴名称，未提供且存在 useRightAxis 系列时不显示轴名 */
  rightAxisName?: string
  /** 是否显示图例 */
  showLegend?: boolean
  /** 数据点较多时启用横向缩放 */
  enableZoom?: boolean
  /** 目标阈值线（画在左轴上，如 SLA 目标 90%）；虚线 + 右端标注 */
  targetLine?: { value: number; label: string }
}>(), {
  height: '280px',
  showLegend: true,
  enableZoom: false
})

const emit = defineEmits<{
  /** 点击数据点：{ seriesName, label(横轴标签), value } */
  'chart-click': [payload: { seriesName: string; label: string; value: number }]
}>()

const chartEl = ref<HTMLDivElement | null>(null)
/** shallowRef：ECharts 实例是重对象，深响应式会递归代理导致性能骤降与内部状态异常 */
const chartInstance = shallowRef<echarts.ECharts | null>(null)

/** 是否存在挂右轴的系列——决定是否渲染第二根 Y 轴 */
const hasRightAxis = computed(() => props.series.some(s => s.useRightAxis))

/**
 * 主题版本（批92）：颜色是取色瞬间从 CSS 令牌抄的 canvas 快照
 * （ECharts canvas 不吃 var()，见 utils/chartTheme 头注），
 * 主题四轴一切换必须重新取色并重绘——把版本号纳入取色依赖即可。
 */
const themeVersion = useThemeVersion()

const buildOption = (): echarts.EChartsCoreOption => {
  // 每次重建 option 都重新取色：主题版本纳入依赖（见上方 themeVersion），
  // 明暗/配色轴切换后这里拿到的是新令牌解析值
  void themeVersion.value
  const chrome = chartChromeColors()
  const defaultColors = chartPalette()
  const yAxes: Record<string, unknown>[] = [
    {
      type: 'value',
      name: props.leftAxisName || '',
      nameTextStyle: { color: chrome.axisText, fontSize: 11 },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: chrome.axisText, fontSize: 11 },
      splitLine: { lineStyle: { color: chrome.splitLine, type: 'dashed' } }
    }
  ]

  if (hasRightAxis.value) {
    yAxes.push({
      type: 'value',
      name: props.rightAxisName || '',
      nameTextStyle: { color: chrome.axisText, fontSize: 11 },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: chrome.axisText, fontSize: 11 },
      // 右轴不画网格线，否则与左轴网格交错成密网
      splitLine: { show: false }
    })
  }

  return {
    color: defaultColors,
    grid: {
      left: 8,
      right: hasRightAxis.value ? 8 : 12,
      bottom: props.enableZoom ? 42 : 6,
      top: props.showLegend ? 38 : 16,
      containLabel: true
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: chrome.tooltipBg,
      borderColor: chrome.tooltipBorder,
      borderWidth: 1,
      textStyle: { color: chrome.tooltipText, fontSize: 12 },
      // 自定义格式化：带上各系列后缀，否则「0.42」无法区分是元还是百分比
      formatter: (params: unknown) => {
        const list = Array.isArray(params) ? params : [params]
        if (!list.length) return ''
        const head = (list[0] as { axisValue?: string }).axisValue ?? ''
        const rows = list.map(p => {
          const item = p as { seriesName?: string; value?: number; marker?: string }
          const s = props.series.find(x => x.name === item.seriesName)
          const suffix = s?.suffix ?? ''
          return `${item.marker ?? ''} ${item.seriesName}: <b>${item.value ?? 0}${suffix}</b>`
        })
        return `${head}<br/>${rows.join('<br/>')}`
      }
    },
    legend: props.showLegend
      ? {
          top: 4,
          right: 4,
          icon: 'roundRect',
          itemWidth: 10,
          itemHeight: 10,
          textStyle: { color: chrome.legendText, fontSize: 11 }
        }
      : { show: false },
    xAxis: {
      type: 'category',
      data: props.labels,
      boundaryGap: props.series.some(s => s.type === 'bar'),
      axisLine: { lineStyle: { color: chrome.axisLine } },
      axisTick: { show: false },
      axisLabel: {
        color: chrome.axisText,
        fontSize: 11,
        // 只显示 MM-DD：完整 yyyy-MM-dd 在 30 天窗口下会挤成一团
        formatter: (v: string) => (typeof v === 'string' && v.length >= 10 ? v.slice(5) : v)
      }
    },
    yAxis: yAxes,
    dataZoom: props.enableZoom
      ? [{ type: 'slider', height: 18, bottom: 8, borderColor: chrome.zoomBorder }]
      : undefined,
    series: props.series.map((s, i) => {
      // 系列色在此统一解析（令牌名或裸色均可，见 resolveChartColor）：
      // 消费方传 '--chart-1' 即随主题四轴自动换色，零样板
      const seriesColor = s.color
        ? resolveChartColor(s.color, defaultColors[i % defaultColors.length])
        : defaultColors[i % defaultColors.length]
      return {
        name: s.name,
        type: s.type ?? 'line',
        data: s.data,
        yAxisIndex: s.useRightAxis && hasRightAxis.value ? 1 : 0,
        smooth: s.smooth ?? true,
        showSymbol: props.labels.length <= 14,
        symbolSize: 5,
        lineStyle: { width: 2 },
        itemStyle: { color: seriesColor },
        barMaxWidth: 22,
        areaStyle: s.area ? { opacity: 0.12, color: seriesColor } : undefined,
        // 目标线只挂第一个系列：N 条数据线共用一根左轴，目标线画一次就够，
        // 每条都画会叠出 N 根重合的虚线
        markLine: i === 0 && props.targetLine
          ? {
              silent: true,
              symbol: 'none',
              lineStyle: { color: chrome.targetLine, type: 'dashed', width: 1.5 },
              label: {
                formatter: props.targetLine.label,
                position: 'insideEndTop',
                color: chrome.targetLine,
                fontSize: 11
              },
              data: [{ yAxis: props.targetLine.value }]
            }
          : undefined
      }
    })
  }
}

const render = () => {
  if (!chartEl.value) return
  if (!chartInstance.value) {
    chartInstance.value = echarts.init(chartEl.value)
  }
  // notMerge=true：系列数量变化时（如切换维度）旧系列必须清掉，
  // 否则 merge 语义会残留上一次的线
  chartInstance.value.setOption(buildOption(), true)

  // chart-click：点击数据点发出钻取事件（供父组件导航到筛选列表）
  chartInstance.value.off('click')
  // ECharts 事件参数是宽对象，这里只取三字段——用结构化类型而非 any，
  // 否则 emits 的 string/number 转换失去编译期校验
  chartInstance.value.on('click', (params: { seriesName?: unknown; name?: unknown; value?: unknown }) => {
    emit('chart-click', {
      seriesName: String(params.seriesName ?? ''),
      label: String(params.name ?? ''),
      value: Number(params.value ?? 0)
    })
  })
}

/** 容器尺寸变化时重绘——折叠面板展开、窗口缩放都会触发 */
let resizeObserver: ResizeObserver | null = null

onMounted(async () => {
  await nextTick()
  render()
  if (chartEl.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      chartInstance.value?.resize()
    })
    resizeObserver.observe(chartEl.value)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  // 必须 dispose：ECharts 实例持有 canvas 与事件监听，不释放会内存泄漏
  chartInstance.value?.dispose()
  chartInstance.value = null
})

watch(
  // themeVersion 入依赖：主题四轴切换后颜色必须重新从令牌取色重绘
  () => [themeVersion.value, props.labels, props.series] as const,
  () => { render() },
  { deep: true }
)
</script>

<template>
  <div ref="chartEl" class="trend-chart" :style="{ height }" />
</template>

<style scoped>
.trend-chart {
  width: 100%;
}
</style>
