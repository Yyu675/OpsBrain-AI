/**
 * 使用日志页组件测试。
 *
 * 这页是新加的，且我写得快——先补测试再谈别的。重点覆盖两类：
 *
 * 1. **切 Tab 时的筛选残留**（本轮发现的真缺陷）
 *    两个 Tab 筛选维度完全不同却共用同一批 ref。在 AI 调用页筛了
 *    `model=qwen-max` 后切到操作审计，modelName 仍会被拼进请求，
 *    但操作审计的筛选栏没有「模型」控件、hasFilters 也不算它，
 *    于是「清除筛选」按钮不显示 —— 用户看到一份被悄悄过滤过的列表，
 *    界面上没有任何筛选生效的迹象，而且无从清除。
 *
 * 2. **日期边界**
 *    `?from=2026-08-24` 若只传日期，后端按 00:00:00 解析，
 *    「查 8 月 24 日」会漏掉当天全部记录。必须补 23:59:59。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  fetchOperationAudit: vi.fn(),
  fetchAiCallLogs: vi.fn(),
  fetchTraceDetail: vi.fn(),
  fetchAuditFilterOptions: vi.fn(),
}))
vi.mock('@/api/auditLogs', () => api)

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

import AuditLogs from '../AuditLogs.vue'

const emptyAiPage = {
  items: [], total: 0, page: 1, size: 20, totalPages: 0,
  stats: { totalCalls: 0, cacheHits: 0, cacheHitRate: 0, totalCost: 0, avgLatencyMs: 0 },
}
const emptyOpPage = { items: [], total: 0, page: 1, size: 20, totalPages: 0 }

let router: Router

const mountAt = async (url = '/governance/audit-logs') => {
  api.fetchAiCallLogs.mockResolvedValue(emptyAiPage)
  api.fetchOperationAudit.mockResolvedValue(emptyOpPage)
  api.fetchAuditFilterOptions.mockResolvedValue({
    models: ['qwen-max'], operationTypes: ['CHAT'],
    actions: ['ticket.create'], targetTypes: ['TICKET'],
  })

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/governance/audit-logs', component: AuditLogs },
    ],
  })
  await router.push(url)
  await router.isReady()

  const wrapper = mount(AuditLogs, {
    global: {
      plugins: [router],
      stubs: {
        'el-table': true, 'el-table-column': true, 'el-dialog': true,
        RelativeTime: true, ServerPagination: true,
        DataStateBoundary: { template: '<div><slot /></div>' },
      },
    },
  })
  await vi.waitFor(() =>
    expect(api.fetchAiCallLogs.mock.calls.length + api.fetchOperationAudit.mock.calls.length)
      .toBeGreaterThan(0)
  )
  await wrapper.vm.$nextTick()
  return wrapper
}

const vmOf = (w: VueWrapper) =>
  w.vm as unknown as {
    activeTab: 'ai-calls' | 'operations'
    modelName: string
    operationType: string
    cachedFilter: string
    minLatency: string
    actorId: string
    actionPrefix: string
    targetType: string
    successFilter: string
    dateFrom: string
    dateTo: string
    dense: boolean
  }

/** 等到 watch 触发的异步加载完成 */
const settle = async (w: VueWrapper) => {
  await w.vm.$nextTick()
  await new Promise(r => setTimeout(r, 0))
  await w.vm.$nextTick()
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('AuditLogs — 切 Tab 不残留另一 Tab 的筛选', () => {
  it('从 AI 调用切到操作审计时，模型等筛选被清空', async () => {
    const w = await mountAt()
    const vm = vmOf(w)

    vm.modelName = 'qwen-max'
    vm.cachedFilter = 'true'
    vm.minLatency = '1000'
    await w.vm.$nextTick()

    vm.activeTab = 'operations'
    await settle(w)

    expect(vm.modelName).toBe('')
    expect(vm.cachedFilter).toBe('')
    expect(vm.minLatency).toBe('')
  })

  it('切 Tab 后的请求不再携带上一个 Tab 的筛选参数', async () => {
    const w = await mountAt()
    const vm = vmOf(w)

    vm.modelName = 'qwen-max'
    await w.vm.$nextTick()

    api.fetchOperationAudit.mockClear()
    vm.activeTab = 'operations'
    await settle(w)

    // 操作审计的请求参数里根本不该出现模型维度
    const params = api.fetchOperationAudit.mock.calls.at(-1)?.[0] ?? {}
    expect(params).not.toHaveProperty('modelName')
    expect(params.actorId).toBeUndefined()
  })

  it('从操作审计切回 AI 调用时，操作者等筛选被清空', async () => {
    const w = await mountAt('/governance/audit-logs?tab=operations')
    const vm = vmOf(w)

    vm.actorId = 'u-1001'
    vm.successFilter = 'false'
    await w.vm.$nextTick()

    vm.activeTab = 'ai-calls'
    await settle(w)

    expect(vm.actorId).toBe('')
    expect(vm.successFilter).toBe('')
  })

  it('时间范围跨 Tab 保留 —— 查「今天」的调用后再看「今天」的操作是合理预期', async () => {
    const w = await mountAt()
    const vm = vmOf(w)

    vm.dateFrom = '2026-08-01'
    vm.dateTo = '2026-08-24'
    await w.vm.$nextTick()

    vm.activeTab = 'operations'
    await settle(w)

    expect(vm.dateFrom).toBe('2026-08-01')
    expect(vm.dateTo).toBe('2026-08-24')
  })
})

describe('AuditLogs — 日期边界', () => {
  it('结束日期补到 23:59:59，否则漏掉当天全部记录', async () => {
    const w = await mountAt()
    const vm = vmOf(w)

    api.fetchAiCallLogs.mockClear()
    vm.dateFrom = '2026-08-24'
    vm.dateTo = '2026-08-24'
    await w.vm.$nextTick()
    // 触发一次查询
    await (w.vm as unknown as { applyFilters: () => Promise<void> }).applyFilters()

    const params = api.fetchAiCallLogs.mock.calls.at(-1)?.[0] ?? {}
    expect(params.from).toBe('2026-08-24T00:00:00')
    expect(params.to).toBe('2026-08-24T23:59:59')
  })

  it('未填日期时不传时间参数 —— 空串会让后端收到无意义的值', async () => {
    const w = await mountAt()
    api.fetchAiCallLogs.mockClear()
    await (w.vm as unknown as { applyFilters: () => Promise<void> }).applyFilters()

    const params = api.fetchAiCallLogs.mock.calls.at(-1)?.[0] ?? {}
    expect(params.from).toBeUndefined()
    expect(params.to).toBeUndefined()
  })
})

describe('AuditLogs — URL 状态', () => {
  it('从 URL 恢复 Tab', async () => {
    const w = await mountAt('/governance/audit-logs?tab=operations')
    expect(vmOf(w).activeTab).toBe('operations')
    expect(api.fetchOperationAudit).toHaveBeenCalled()
  })

  it('非法 tab 值回落默认', async () => {
    const w = await mountAt('/governance/audit-logs?tab=nonexistent')
    expect(vmOf(w).activeTab).toBe('ai-calls')
  })

  it('从 URL 恢复筛选并用于首次请求', async () => {
    await mountAt('/governance/audit-logs?model=qwen-max&cached=true')

    const params = api.fetchAiCallLogs.mock.calls[0][0]
    expect(params.modelName).toBe('qwen-max')
    expect(params.cached).toBe(true)
  })

  it('cached=false 被正确解析为布尔 false，而非当成空值忽略', async () => {
    await mountAt('/governance/audit-logs?cached=false')

    const params = api.fetchAiCallLogs.mock.calls[0][0]
    expect(params.cached).toBe(false)
  })
})

describe('AuditLogs — 降级与容错', () => {
  it('筛选选项加载失败不阻塞主列表', async () => {
    api.fetchAiCallLogs.mockResolvedValue(emptyAiPage)
    api.fetchOperationAudit.mockResolvedValue(emptyOpPage)
    api.fetchAuditFilterOptions.mockRejectedValue(new Error('500'))

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/governance/audit-logs', component: AuditLogs }],
    })
    await router.push('/governance/audit-logs')
    await router.isReady()

    const w = mount(AuditLogs, {
      global: {
        plugins: [router],
        stubs: {
          'el-table': true, 'el-table-column': true, 'el-dialog': true,
          RelativeTime: true, ServerPagination: true,
          DataStateBoundary: { template: '<div><slot /></div>' },
        },
      },
    })
    await vi.waitFor(() => expect(api.fetchAiCallLogs).toHaveBeenCalled())

    // 主列表仍然拉到了
    expect(api.fetchAiCallLogs).toHaveBeenCalled()
    w.unmount()
  })

  it('清除筛选后重新查询且回到第 1 页', async () => {
    const w = await mountAt('/governance/audit-logs?model=qwen-max&page=3')
    const vm = vmOf(w)

    api.fetchAiCallLogs.mockClear()
    await (w.vm as unknown as { clearFilters: () => Promise<void> }).clearFilters()

    expect(vm.modelName).toBe('')
    const params = api.fetchAiCallLogs.mock.calls.at(-1)?.[0] ?? {}
    expect(params.page).toBe(1)
    expect(params.modelName).toBeUndefined()
  })
})
