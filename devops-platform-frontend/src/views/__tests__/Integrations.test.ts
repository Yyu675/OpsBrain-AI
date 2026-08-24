/**
 * 接入管理页组件测试。
 *
 * ── 覆盖重点 ──────────────────────────────────────────────────
 * 1. **排查建议按错误类型分流**。这页的核心价值是把
 *    `ConnectException: Connection refused` 翻译成「下一步做什么」。
 *    分流错了用户就会照着错误的方向查（比如连接被拒时去调超时参数）。
 *
 * 2. **轮询在卸载时必须停**。不停会在离开页面后继续打接口，
 *    登出后仍在请求——本项目已经因为类似的残留踩过越权读取的坑。
 *
 * 3. **目录接口失败不能拖垮整页**。数据源挂了时目录仍应展示
 *    「本系统支持哪些指标」，用 allSettled 而非 all。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  fetchDatasources: vi.fn(),
  fetchMetricCatalog: vi.fn(),
}))
vi.mock('@/api/metrics', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/metrics')
  return { ...actual, ...api }
})

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

import Integrations from '../Integrations.vue'

const HEALTHY = {
  type: 'prometheus',
  name: 'Prometheus',
  baseUrl: 'http://localhost:29090',
  enabled: true,
  reachable: true,
  latencyMs: 12,
}

const METRICS = [
  { id: 'cpu.usage', name: 'CPU 使用率', unit: 'percent' as const, describe: '主机 CPU 繁忙程度' },
]

let router: Router

const mountPage = async (ds = [HEALTHY], metrics = METRICS) => {
  api.fetchDatasources.mockResolvedValue({ datasources: ds, total: ds.length })
  api.fetchMetricCatalog.mockResolvedValue({ metrics, enabled: true })

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/integrations', component: Integrations },
    ],
  })
  await router.push('/integrations')
  await router.isReady()

  const wrapper = mount(Integrations, {
    global: {
      plugins: [router],
      stubs: { DataStateBoundary: { template: '<div><slot /></div>' } },
    },
  })
  await vi.waitFor(() => expect(api.fetchDatasources).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  await wrapper.vm.$nextTick()
  return wrapper
}

type Vm = {
  datasources: Array<typeof HEALTHY>
  metrics: typeof METRICS
  loading: boolean
  loadError: unknown
  lastCheckedAt: number | null
  healthyCount: number
  totalCount: number
  allHealthy: boolean
  latencyLevel: (ms?: number) => string
  troubleshoot: (d: Record<string, unknown>) => string[]
  recheck: { run: () => Promise<unknown> }
}

const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.useRealTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Integrations — 加载与汇总', () => {
  it('展示数据源与指标目录', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    expect(vm.datasources).toHaveLength(1)
    expect(vm.metrics).toHaveLength(1)
    expect(vm.lastCheckedAt).not.toBeNull()
  })

  it('全部可达时 allHealthy 为 true', async () => {
    const vm = vmOf(await mountPage())
    expect(vm.allHealthy).toBe(true)
    expect(vm.healthyCount).toBe(1)
  })

  it('有不可达数据源时 allHealthy 为 false', async () => {
    const bad = { ...HEALTHY, reachable: false, error: 'Connection refused' }
    const vm = vmOf(await mountPage([bad]))

    expect(vm.allHealthy).toBe(false)
    expect(vm.healthyCount).toBe(0)
    expect(vm.totalCount).toBe(1)
  })

  it('目录接口失败不影响数据源展示', async () => {
    api.fetchDatasources.mockResolvedValue({ datasources: [HEALTHY], total: 1 })
    api.fetchMetricCatalog.mockRejectedValue(new Error('boom'))

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/integrations', component: Integrations }],
    })
    await router.push('/integrations')
    await router.isReady()
    const w = mount(Integrations, {
      global: {
        plugins: [router],
        stubs: { DataStateBoundary: { template: '<div><slot /></div>' } },
      },
    })
    await vi.waitFor(() => expect(api.fetchDatasources).toHaveBeenCalled())
    await w.vm.$nextTick()
    await w.vm.$nextTick()

    const vm = vmOf(w)
    // 数据源挂了时目录仍应展示「本系统支持哪些指标」
    expect(vm.datasources).toHaveLength(1)
    expect(vm.metrics).toEqual([])
    expect(vm.loadError).toBeNull()
  })

  it('数据源接口本身失败时如实报错，不伪造「不可达」', async () => {
    api.fetchDatasources.mockRejectedValue(new Error('后端 500'))
    api.fetchMetricCatalog.mockResolvedValue({ metrics: [], enabled: true })

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/integrations', component: Integrations }],
    })
    await router.push('/integrations')
    await router.isReady()
    const w = mount(Integrations, {
      global: {
        plugins: [router],
        stubs: { DataStateBoundary: { template: '<div><slot /></div>' } },
      },
    })
    await vi.waitFor(() => expect(api.fetchDatasources).toHaveBeenCalled())
    await w.vm.$nextTick()
    await w.vm.$nextTick()

    // 该端点后端保证不抛（连不上也返回 reachable:false），
    // 真失败说明是后端自身问题，不该显示成「Prometheus 不可达」
    expect(vmOf(w).loadError).toBeTruthy()
  })
})

describe('Integrations — 排查建议按错误类型分流', () => {
  it('可达时不给建议', async () => {
    const vm = vmOf(await mountPage())
    expect(vm.troubleshoot(HEALTHY)).toEqual([])
  })

  it('未启用：引导改 PROMETHEUS_ENABLED，而不是让他去查网络', async () => {
    const vm = vmOf(await mountPage())
    const tips = vm.troubleshoot({
      ...HEALTHY, reachable: false, enabled: false, error: '集成未启用',
    })
    expect(tips.join(' ')).toContain('PROMETHEUS_ENABLED')
  })

  it('连接被拒：引导起容器与 curl 验证', async () => {
    const vm = vmOf(await mountPage())
    const tips = vm.troubleshoot({
      ...HEALTHY, reachable: false, error: 'ConnectException: Connection refused',
    })
    const text = tips.join(' ')
    expect(text).toContain('docker compose')
    expect(text).toContain('/-/healthy')
  })

  it('超时：引导看 targets 与调 TIMEOUT，而不是让他去起容器', async () => {
    const vm = vmOf(await mountPage())
    const tips = vm.troubleshoot({
      ...HEALTHY, reachable: false, error: 'HttpTimeoutException: request timed out',
    })
    const text = tips.join(' ')
    // 分流错了用户会照错误方向查——超时说明服务是通的，起容器毫无意义
    expect(text).toContain('/targets')
    expect(text).toContain('PROMETHEUS_TIMEOUT_MS')
    expect(text).not.toContain('docker compose')
  })

  it('非 JSON 响应：提示 base-url 可能指错服务', async () => {
    const vm = vmOf(await mountPage())
    const tips = vm.troubleshoot({
      ...HEALTHY, reachable: false, error: 'Prometheus 返回了非 JSON 响应',
    })
    expect(tips.join(' ')).toContain('base-url')
  })

  it('未知错误：至少给出原始错误与手工验证命令', async () => {
    const vm = vmOf(await mountPage())
    const tips = vm.troubleshoot({
      ...HEALTHY, reachable: false, error: '某种没见过的错误',
    })
    const text = tips.join(' ')
    expect(text).toContain('某种没见过的错误')
    expect(text).toContain('curl')
  })
})

describe('Integrations — 延迟分档', () => {
  it('按 200ms / 1000ms 分三档', async () => {
    const vm = vmOf(await mountPage())
    expect(vm.latencyLevel(12)).toBe('good')
    expect(vm.latencyLevel(500)).toBe('warn')
    expect(vm.latencyLevel(2000)).toBe('bad')
  })

  it('无延迟数据时归入 warn 而非 good——不能假装正常', async () => {
    const vm = vmOf(await mountPage())
    expect(vm.latencyLevel(undefined)).toBe('warn')
  })
})

describe('Integrations — 轮询与清理', () => {
  it('卸载时清掉定时器，不再继续请求', async () => {
    vi.useFakeTimers()
    api.fetchDatasources.mockResolvedValue({ datasources: [HEALTHY], total: 1 })
    api.fetchMetricCatalog.mockResolvedValue({ metrics: [], enabled: true })

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/integrations', component: Integrations }],
    })
    await router.push('/integrations')
    await router.isReady()
    const w = mount(Integrations, {
      global: {
        plugins: [router],
        stubs: { DataStateBoundary: { template: '<div><slot /></div>' } },
      },
    })
    await vi.advanceTimersByTimeAsync(0)

    const before = api.fetchDatasources.mock.calls.length
    w.unmount()
    await vi.advanceTimersByTimeAsync(120_000)

    // 不清会在离开页面后继续打接口，登出后仍在请求
    expect(api.fetchDatasources.mock.calls.length).toBe(before)
  })

  it('手动重新检查会再次请求', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.fetchDatasources.mockClear()

    await vm.recheck.run()
    await w.vm.$nextTick()

    expect(api.fetchDatasources).toHaveBeenCalled()
  })
})
