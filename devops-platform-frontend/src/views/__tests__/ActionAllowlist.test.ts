/**
 * 动作白名单页组件测试。
 *
 * 覆盖重点是**表单校验与二次确认的触发条件**，不是渲染快照。
 * 理由：这页的每一个提交都在调整「AI 能不能自动动生产系统」的边界。
 * 渲染错了用户一眼能看见；校验漏了则完全没有表现——
 * 直到某天引擎真的执行了一个本该被拦下的动作。
 *
 * 三条被专门守住的规则：
 *   1. 写操作留空目标模式必须报错（留空 = 对所有资源生效）
 *   2. 启用高危动作必须二次确认；**停用不确认**（止血动作不该有摩擦）
 *   3. 筛选变化要重置页码（否则「第 5 页 + 新筛选只有 2 页」= 空列表）
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  fetchActions: vi.fn(),
  fetchActionStats: vi.fn(),
  fetchActionFilterOptions: vi.fn(),
  createAction: vi.fn(),
  updateAction: vi.fn(),
  toggleAction: vi.fn(),
  evaluateAction: vi.fn(),
}))
vi.mock('@/api/governance', () => api)

const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock },
  ElMessage: Object.assign(vi.fn(), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
}))

const notifyMock = vi.hoisted(() => ({
  success: vi.fn(), warning: vi.fn(), error: vi.fn(),
  info: vi.fn(), clearCooldown: vi.fn(),
}))
vi.mock('@/utils/notify', () => ({
  notify: notifyMock,
  handleServerError: vi.fn(),
}))

import ActionAllowlist from '../ActionAllowlist.vue'

const ROW = {
  id: 1,
  actionKey: 'k8s.pod.restart',
  displayName: '优雅重启 Pod',
  description: null,
  category: 'k8s',
  riskLevel: 'CONTROLLED_WRITE' as const,
  targetPattern: 'ns:staging/*',
  environments: 'staging,dev',
  paramSchema: null,
  requiresApproval: null,
  maxBlastRadiusCount: null,
  enabled: false,
  version: 0,
  updatedBy: null,
  updateTime: null,
  effectiveRequiresApproval: true,
  effectiveBlastRadiusCount: 5,
}

const HIGH_RISK_ROW = {
  ...ROW,
  id: 2,
  actionKey: 'k8s.rollout.undo',
  displayName: '回滚发布',
  riskLevel: 'HIGH_RISK_EXECUTION' as const,
  environments: 'dev',
}

let router: Router

const mountPage = async (url = '/automation/action-allowlist', rows = [ROW]) => {
  api.fetchActions.mockResolvedValue({
    items: rows, total: rows.length, page: 1, size: 20, totalPages: 1,
  })
  api.fetchActionStats.mockResolvedValue({
    total: rows.length, enabledCount: 0, highRiskEnabled: 0, prodEnabled: 0,
  })
  api.fetchActionFilterOptions.mockResolvedValue({
    categories: ['k8s', 'host'],
    riskLevels: [
      { value: 'READ_ONLY', label: '只读查询', description: '' },
      { value: 'CONTROLLED_WRITE', label: '受控写操作', description: '' },
      { value: 'HIGH_RISK_EXECUTION', label: '高风险执行', description: '' },
    ],
    environments: ['prod', 'staging', 'dev'],
    knownCategories: ['k8s', 'host', 'cloud', 'database'],
  })

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/automation/action-allowlist', component: ActionAllowlist },
    ],
  })
  await router.push(url)
  await router.isReady()

  const wrapper = mount(ActionAllowlist, {
    global: {
      plugins: [router],
      stubs: {
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
        ServerPagination: true,
        DataStateBoundary: { template: '<div><slot /></div>' },
      },
    },
  })
  await vi.waitFor(() => expect(api.fetchActions).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  return wrapper
}

type Vm = {
  keyword: string
  category: string
  riskLevel: string
  enabledFilter: string
  currentPage: number
  form: Record<string, unknown>
  formError: string | null
  requiresTargetPattern: boolean
  formOpen: boolean
  editingId: number | null
  openCreate: () => void
  openEdit: (row: typeof ROW) => void
  toggleFormEnv: (env: string) => void
  formEnvList: string[]
  toggling: { run: (row: typeof ROW) => Promise<unknown> }
  submitting: { run: () => Promise<unknown> }
  evaluating: { run: () => Promise<unknown> }
  evalActionKey: string
  evalEnvironment: string
  evalResult: unknown
  rows: Array<typeof ROW>
}

const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

const settle = async (w: VueWrapper) => {
  await w.vm.$nextTick()
  await new Promise((r) => setTimeout(r, 0))
  await w.vm.$nextTick()
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
})

describe('ActionAllowlist — 表单校验', () => {
  it('写操作留空目标模式被拒绝——留空意味着对所有资源生效', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      actionKey: 'k8s.pod.restart',
      displayName: '重启',
      riskLevel: 'CONTROLLED_WRITE',
      environments: 'dev',
      targetPattern: '   ',
    })
    await w.vm.$nextTick()

    expect(vm.requiresTargetPattern).toBe(true)
    expect(vm.formError).toContain('目标匹配模式')
  })

  it('只读动作允许留空目标模式', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      actionKey: 'k8s.pod.describe',
      displayName: '查看',
      riskLevel: 'READ_ONLY',
      environments: 'dev',
      targetPattern: '',
    })
    await w.vm.$nextTick()

    expect(vm.requiresTargetPattern).toBe(false)
    expect(vm.formError).toBeNull()
  })

  it('动作标识必须是点分小写标识', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      actionKey: 'RestartPod',
      displayName: '重启',
      riskLevel: 'READ_ONLY',
      environments: 'dev',
    })
    await w.vm.$nextTick()
    expect(vm.formError).toContain('点分小写标识')

    vm.form.actionKey = 'k8s.pod.restart'
    await w.vm.$nextTick()
    expect(vm.formError).toBeNull()
  })

  it('至少要选一个环境', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      actionKey: 'k8s.pod.describe',
      displayName: '查看',
      riskLevel: 'READ_ONLY',
      environments: '',
    })
    await w.vm.$nextTick()
    expect(vm.formError).toContain('环境')
  })

  it('非法 JSON 的参数约束被拒绝——否则引擎读取时才炸', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      actionKey: 'k8s.pod.describe',
      displayName: '查看',
      riskLevel: 'READ_ONLY',
      environments: 'dev',
      paramSchema: '{ not json',
    })
    await w.vm.$nextTick()
    expect(vm.formError).toContain('JSON')

    vm.form.paramSchema = '{"lines":{"type":"int"}}'
    await w.vm.$nextTick()
    expect(vm.formError).toBeNull()
  })

  it('校验不通过时不发起提交请求', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, { actionKey: '', displayName: '' })
    await w.vm.$nextTick()

    await vm.submitting.run()
    expect(api.createAction).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalled()
  })
})

describe('ActionAllowlist — 环境勾选', () => {
  it('按固定顺序输出，避免勾选顺序不同造成的假「已改动」', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    vm.form.environments = ''
    vm.toggleFormEnv('dev')
    vm.toggleFormEnv('prod')
    await w.vm.$nextTick()

    // 勾选顺序是 dev → prod，但输出按 prod,staging,dev 的固定序
    expect(vm.form.environments).toBe('prod,dev')
  })

  it('再次点击取消勾选', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    vm.form.environments = 'prod,dev'
    vm.toggleFormEnv('prod')
    await w.vm.$nextTick()

    expect(vm.form.environments).toBe('dev')
  })
})

describe('ActionAllowlist — 启停的二次确认', () => {
  it('启用高危动作弹确认', async () => {
    const w = await mountPage('/automation/action-allowlist', [HIGH_RISK_ROW])
    const vm = vmOf(w)
    api.toggleAction.mockResolvedValue({ ...HIGH_RISK_ROW, enabled: true, version: 1 })

    await vm.toggling.run(HIGH_RISK_ROW)
    await settle(w)

    expect(confirmMock).toHaveBeenCalled()
    expect(api.toggleAction).toHaveBeenCalledWith(HIGH_RISK_ROW.id, true, HIGH_RISK_ROW.version)
  })

  it('用户取消确认时不发起请求', async () => {
    const w = await mountPage('/automation/action-allowlist', [HIGH_RISK_ROW])
    const vm = vmOf(w)
    confirmMock.mockRejectedValue('cancel')

    await vm.toggling.run(HIGH_RISK_ROW)
    await settle(w)

    expect(api.toggleAction).not.toHaveBeenCalled()
  })

  it('停用高危动作不弹确认——止血动作不该有摩擦', async () => {
    const enabled = { ...HIGH_RISK_ROW, enabled: true }
    const w = await mountPage('/automation/action-allowlist', [enabled])
    const vm = vmOf(w)
    api.toggleAction.mockResolvedValue({ ...enabled, enabled: false, version: 1 })

    await vm.toggling.run(enabled)
    await settle(w)

    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.toggleAction).toHaveBeenCalledWith(enabled.id, false, enabled.version)
  })

  it('启用非高危动作不弹确认', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.toggleAction.mockResolvedValue({ ...ROW, enabled: true, version: 1 })

    await vm.toggling.run(ROW)
    await settle(w)

    expect(confirmMock).not.toHaveBeenCalled()
  })

  it('启停后刷新统计——「已启用高危动作数」必须跟着变', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.toggleAction.mockResolvedValue({ ...ROW, enabled: true, version: 1 })
    api.fetchActionStats.mockClear()

    await vm.toggling.run(ROW)
    await settle(w)

    expect(api.fetchActionStats).toHaveBeenCalled()
  })

  it('提交时带上当前版本号，防止静默覆盖他人修改', async () => {
    const versioned = { ...ROW, version: 7 }
    const w = await mountPage('/automation/action-allowlist', [versioned])
    const vm = vmOf(w)
    api.toggleAction.mockResolvedValue({ ...versioned, enabled: true, version: 8 })

    await vm.toggling.run(versioned)
    await settle(w)

    expect(api.toggleAction).toHaveBeenCalledWith(versioned.id, true, 7)
  })
})

describe('ActionAllowlist — 筛选与页码', () => {
  it('改筛选时重置到第一页——否则「第 5 页 + 新筛选只有 2 页」= 空列表', async () => {
    const w = await mountPage('/automation/action-allowlist?page=5')
    const vm = vmOf(w)
    expect(vm.currentPage).toBe(5)

    vm.keyword = 'restart'
    await settle(w)

    expect(vm.currentPage).toBe(1)
  })

  it('筛选条件会进入请求参数', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.fetchActions.mockClear()

    vm.riskLevel = 'HIGH_RISK_EXECUTION'
    vm.enabledFilter = 'false'
    await settle(w)

    const lastCall = api.fetchActions.mock.calls.at(-1)?.[0]
    expect(lastCall).toMatchObject({
      riskLevel: 'HIGH_RISK_EXECUTION',
      enabled: false,
    })
  })

  it('「不限」状态不把 enabled 传给后端', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.enabledFilter = 'true'
    await settle(w)
    vm.enabledFilter = ''
    await settle(w)

    const lastCall = api.fetchActions.mock.calls.at(-1)?.[0]
    expect(lastCall?.enabled).toBeUndefined()
  })
})

describe('ActionAllowlist — 编辑与模拟校验', () => {
  it('编辑时回填全部字段，含 version', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openEdit({ ...ROW, version: 3 })
    await w.vm.$nextTick()

    expect(vm.editingId).toBe(ROW.id)
    expect(vm.form.actionKey).toBe('k8s.pod.restart')
    expect(vm.form.version).toBe(3)
    // null 表示「跟随策略」，不能被回填成 false
    expect(vm.form.requiresApproval).toBeNull()
  })

  it('模拟校验默认问 prod——用户最想确认的是生产环境', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    ;(w.vm as unknown as { openEvaluate: (r?: typeof ROW) => void }).openEvaluate(ROW)
    await w.vm.$nextTick()

    expect(vm.evalActionKey).toBe('k8s.pod.restart')
    expect(vm.evalEnvironment).toBe('prod')
  })

  it('模拟校验空标识时不发请求', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.evalActionKey = '   '
    await vm.evaluating.run()

    expect(api.evaluateAction).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalled()
  })

  it('模拟校验结果被保留供展示', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.evaluateAction.mockResolvedValue({
      actionKey: 'k8s.pod.restart',
      environment: 'prod',
      allowed: false,
      reason: '该动作未在 prod 环境开放',
    })

    vm.evalActionKey = 'k8s.pod.restart'
    vm.evalEnvironment = 'prod'
    await vm.evaluating.run()
    await settle(w)

    expect(vm.evalResult).toMatchObject({ allowed: false })
  })
})
