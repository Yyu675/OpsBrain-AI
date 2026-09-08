/**
 * 指标 API 层测试。
 *
 * ── 覆盖重点是格式化与判定，不是「调通了没」 ──────────────────
 * 这层最危险的不是请求发错（那会立刻报错），而是**把 null 当成数字用**：
 * Prometheus 对刚重启的实例、除零的 rate 返回 NaN，后端已转成 null。
 * 若前端不判空，会渲染出字面量 "null"，或让 `null + 1 === 1` 这类
 * 静默错误污染整条计算链——用户看到的是一个**看起来合理但是错的数字**。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/utils/http', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/utils/http')
  return { ...actual, http: httpMock }
})

import {
  fetchDatasources,
  fetchInstant,
  fetchMetricCatalog,
  fetchOverview,
  fetchRange,
  formatBytes,
  formatMetricValue,
  labelOf,
  severityOf,
} from '../metrics'

const ok = (data: unknown) => ({ code: 0, message: 'ok', data })

beforeEach(() => {
  httpMock.get.mockReset()
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('请求构造', () => {
  it('instant 对指标 ID 做 URL 编码', async () => {
    httpMock.get.mockResolvedValue(ok({ metric: 'cpu.usage', samples: [] }))
    await fetchInstant('cpu.usage')

    const url = String(httpMock.get.mock.calls[0][0])
    expect(url).toContain('/metrics/instant')
    expect(url).toContain('metric=cpu.usage')
  })

  it('range 带上 hours 与 step', async () => {
    httpMock.get.mockResolvedValue(
      ok({ metric: 'cpu.usage', from: 0, to: 1, step: 60, hours: 6, series: [] })
    )
    await fetchRange('cpu.usage', 6, 120)

    const url = String(httpMock.get.mock.calls[0][0])
    expect(url).toContain('hours=6')
    expect(url).toContain('step=120')
  })

  it('range 有默认值（1 小时 / 60 秒）', async () => {
    httpMock.get.mockResolvedValue(
      ok({ metric: 'cpu.usage', from: 0, to: 1, step: 60, hours: 1, series: [] })
    )
    await fetchRange('cpu.usage')

    const url = String(httpMock.get.mock.calls[0][0])
    expect(url).toContain('hours=1')
    expect(url).toContain('step=60')
  })

  it('overview / catalog / datasource 走各自端点', async () => {
    httpMock.get.mockResolvedValue(ok({ cards: {}, timestamp: 0 }))
    await fetchOverview()
    expect(String(httpMock.get.mock.calls[0][0])).toMatch(/\/metrics\/overview$/)

    httpMock.get.mockClear()
    httpMock.get.mockResolvedValue(ok({ metrics: [], enabled: true }))
    await fetchMetricCatalog()
    expect(String(httpMock.get.mock.calls[0][0])).toMatch(/\/metrics\/catalog$/)

    httpMock.get.mockClear()
    httpMock.get.mockResolvedValue(ok({ datasources: [], total: 0 }))
    await fetchDatasources()
    expect(String(httpMock.get.mock.calls[0][0])).toMatch(/\/metrics\/datasource$/)
  })

  it('后端 50020（数据源不可用）会抛出，不被静默吞掉', async () => {
    httpMock.get.mockResolvedValue({
      code: 50020,
      message: '无法连接 Prometheus',
      data: null,
    })

    // 吞掉会让页面显示空图表，用户以为「没数据」而不是「数据源挂了」
    await expect(fetchInstant('cpu.usage')).rejects.toThrow()
  })
})

describe('formatMetricValue — null 必须显示为占位符', () => {
  it('null / undefined / NaN 都渲染成 —', () => {
    // 无数据是正常状态（实例刚重启、目标下线），
    // 显示成 "null"/"NaN" 会让用户以为系统出错
    expect(formatMetricValue(null, 'percent')).toBe('—')
    expect(formatMetricValue(undefined as unknown as null, 'percent')).toBe('—')
    expect(formatMetricValue(NaN, 'percent')).toBe('—')
  })

  it('percent 保留一位小数并带 %', () => {
    expect(formatMetricValue(87.456, 'percent')).toBe('87.5%')
    expect(formatMetricValue(0, 'percent')).toBe('0.0%')
  })

  it('0 不能被当成空值——它是有效读数', () => {
    // 若用 `if (!value)` 判空，0 会被误判为无数据，
    // 「CPU 0%」和「取不到 CPU」是完全不同的两件事
    expect(formatMetricValue(0, 'percent')).toBe('0.0%')
    expect(formatMetricValue(0, 'count')).toBe('0')
    expect(formatMetricValue(0, 'bytes')).toBe('0 B/s')
  })

  it('count 整数不补小数，小数保留两位', () => {
    expect(formatMetricValue(3, 'count')).toBe('3')
    expect(formatMetricValue(1.234, 'count')).toBe('1.23')
  })

  it('seconds 保留两位并带单位', () => {
    expect(formatMetricValue(1.5, 'seconds')).toBe('1.50s')
  })
})

describe('formatBytes — 速率换算', () => {
  it('按 1024 进位，与运维工具惯例一致', () => {
    expect(formatBytes(512)).toBe('512 B/s')
    expect(formatBytes(2048)).toBe('2.0 KB/s')
    expect(formatBytes(5 * 1024 ** 2)).toBe('5.0 MB/s')
    expect(formatBytes(3 * 1024 ** 3)).toBe('3.00 GB/s')
  })

  it('负值（异常计数器回绕）也能正确显示量级', () => {
    expect(formatBytes(-2048)).toBe('-2.0 KB/s')
  })
})

describe('severityOf — 阈值着色', () => {
  it('percent 按 75/90 分档', () => {
    expect(severityOf(50, 'percent')).toBe('normal')
    expect(severityOf(80, 'percent')).toBe('warn')
    expect(severityOf(95, 'percent')).toBe('danger')
  })

  it('边界值归入更严的一档', () => {
    expect(severityOf(75, 'percent')).toBe('warn')
    expect(severityOf(90, 'percent')).toBe('danger')
  })

  it('非 percent 一律 normal——负载 8 不能按百分比阈值判危', () => {
    // 「负载 8」在 16 核机器上很正常，用 percent 的阈值会误报红色告警
    expect(severityOf(8, 'count')).toBe('normal')
    expect(severityOf(99, 'count')).toBe('normal')
    expect(severityOf(1e9, 'bytes')).toBe('normal')
  })

  it('null 不着色', () => {
    expect(severityOf(null, 'percent')).toBe('normal')
  })
})

describe('labelOf — 展示名选取', () => {
  it('优先 instance（能定位到具体机器）', () => {
    expect(labelOf({ instance: 'node:9100', job: 'node' })).toBe('node:9100')
  })

  it('无 instance 时依次回退 device / mountpoint / job', () => {
    expect(labelOf({ device: 'eth0', job: 'node' })).toBe('eth0')
    expect(labelOf({ mountpoint: '/data', job: 'node' })).toBe('/data')
    expect(labelOf({ job: 'node' })).toBe('node')
  })

  it('全无标签时给「默认」而非空串', () => {
    // 空串会让表格出现一列空白，用户不知道那行代表什么
    expect(labelOf({})).toBe('默认')
    expect(labelOf(undefined as unknown as Record<string, string>)).toBe('默认')
  })
})
