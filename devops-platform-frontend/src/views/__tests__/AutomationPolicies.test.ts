/**
 * 自动化策略页组件测试。
 *
 * ── 覆盖重点 ──────────────────────────────────────────────────
 * 1. **「至少一个匹配条件」校验**。全部留空 = 对所有告警执行该动作，
 *    影响面是全站范围的。而 `*` 也算「无限制」——这个等价关系
 *    很容易在实现时漏掉，导致用户填了 `*` 就绕过了校验。
 *
 * 2. **关闭演练要确认、切回演练不确认**。关掉演练是本页风险最高的
 *    单个操作（策略从「只记录」变成「真动手」）；而切回演练是回到
 *    更安全的状态，不该有摩擦。
 *
 * 3. **动作下拉只列已启用的**。引用停用动作会被后端拒绝，
 *    列出来只是让用户白填一遍。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  fetchPolicies: vi.fn(),
  fetchPolicyStats: vi.fn(),
  fetchActions: vi.fn(),
  fetchActionFilterOptions: vi.fn(),
  createPolicy: vi.fn(),
  updatePolicy: vi.fn(),
  togglePolicy: vi.fn(),
  togglePolicyDryRun: vi.fn(),
  deletePolicy: vi.fn(),
  simulatePolicies: vi.fn(),
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

import AutomationPolicies from '../AutomationPolicies.vue'

const ROW = {
  id: 1,
  name: 'P3 Pod 崩溃自动重启',
  description: null,
  matchAlertLevels: 'P3',
  matchModule: 'K8S',
  matchServicePattern: '*',
  matchAlertNamePattern: 'PodCrashLoopBackOff',
  actionKey: 'k8s.pod.restart',
  actionParams: null,
  environment: 'staging',
  priority: 20,
  stopOnMatch: true,
  cooldownMinutes: 30,
  maxExecutionsPerDay: 10,
  dryRun: true,
  enabled: true,
  version: 0,
  updatedBy: null,
  updateTime: null,
  actionDisplayName: '优雅重启 Pod',
  actionRiskLevel: 'CONTROLLED_WRITE' as const,
  actionEnabled: true,
  effective: true,
  ineffectiveReason: null,
}

const ACTION = {
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
  enabled: true,
  version: 0,
  updatedBy: null,
  updateTime: null,
  effectiveRequiresApproval: true,
  effectiveBlastRadiusCount: 5,
}

let router: Router

const mountPage = async (url = '/automation/policies', rows = [ROW]) => {
  api.fetchPolicies.mockResolvedValue({
    items: rows, total: rows.length, page: 1, size: 20, totalPages: 1,
  })
  api.fetchPolicyStats.mockResolvedValue({
    total: rows.length, enabledCount: 1, dryRunCount: 1, liveCount: 0, prodLiveCount: 0,
  })
  api.fetchActions.mockResolvedValue({
    items: [ACTION], total: 1, page: 1, size: 200, totalPages: 1,
  })
  api.fetchActionFilterOptions.mockResolvedValue({
    categories: ['k8s'],
    riskLevels: [{ value: 'CONTROLLED_WRITE', label: '受控写操作', description: '' }],
    environments: ['prod', 'staging', 'dev'],
    knownCategories: ['k8s'],
  })

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/automation/policies', component: AutomationPolicies },
    ],
  })
  await router.push(url)
  await router.isReady()

  const wrapper = mount(AutomationPolicies, {
    global: {
      plugins: [router],
      stubs: {
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
        ServerPagination: true,
        DataStateBoundary: { template: '<div><slot /></div>' },
      },
    },
  })
  await vi.waitFor(() => expect(api.fetchPolicies).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  return wrapper
}

type Vm = {
  keyword: string
  environment: string
  enabledFilter: string
  currentPage: number
  rows: Array<typeof ROW>
  actions: Array<typeof ACTION>
  form: Record<string, unknown>
  formError: string | null
  formLevels: string[]
  formOpen: boolean
  editingId: number | null
  openCreate: () => void
  openEdit: (r: typeof ROW) => void
  toggleFormLevel: (lv: string) => void
  describeMatch: (r: typeof ROW) => string
  toggling: { run: (r: typeof ROW) => Promise<unknown> }
  togglingDryRun: { run: (r: typeof ROW) => Promise<unknown> }
  removing: { run: (r: typeof ROW) => Promise<unknown> }
  submitting: { run: () => Promise<unknown> }
  simulating: { run: () => Promise<unknown> }
  simInput: Record<string, string>
  simResult: unknown
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

describe('AutomationPolicies — 匹配条件校验', () => {
  it('全部条件留空被拒绝——那等于对所有告警执行该动作', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      name: '通配一切',
      actionKey: 'k8s.pod.restart',
      environment: 'staging',
      matchAlertLevels: '',
      matchModule: '',
      matchServicePattern: '',
      matchAlertNamePattern: '',
    })
    await w.vm.$nextTick()

    expect(vm.formError).toContain('至少要指定一个匹配条件')
  })

  it('星号等价于「无限制」，不能靠填 * 绕过校验', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      name: '通配一切',
      actionKey: 'k8s.pod.restart',
      environment: 'staging',
      matchAlertLevels: '',
      matchModule: '',
      matchServicePattern: '*',
      matchAlertNamePattern: '*',
    })
    await w.vm.$nextTick()

    expect(vm.formError).toContain('至少要指定一个匹配条件')
  })

  it('只要有一个实质条件就放行', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      name: '仅按级别',
      actionKey: 'k8s.pod.restart',
      environment: 'staging',
      matchAlertLevels: 'P3',
      matchModule: '',
      matchServicePattern: '*',
      matchAlertNamePattern: '*',
    })
    await w.vm.$nextTick()

    expect(vm.formError).toBeNull()
  })

  it('非法 JSON 参数被拒绝', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      name: '测试',
      actionKey: 'k8s.pod.restart',
      environment: 'staging',
      matchAlertLevels: 'P3',
      actionParams: '{ bad json',
    })
    await w.vm.$nextTick()
    expect(vm.formError).toContain('JSON')

    vm.form.actionParams = '{"gracePeriodSeconds":30}'
    await w.vm.$nextTick()
    expect(vm.formError).toBeNull()
  })

  it('环境超出动作开放范围时提前提示，省一次往返', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, {
      name: '测试',
      actionKey: 'k8s.pod.restart',
      // ACTION 只开放 staging,dev
      environment: 'prod',
      matchAlertLevels: 'P3',
    })
    await w.vm.$nextTick()

    expect(vm.formError).toContain('prod')
  })

  it('校验不通过时不发请求', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    Object.assign(vm.form, { name: '', actionKey: '' })
    await w.vm.$nextTick()

    await vm.submitting.run()
    expect(api.createPolicy).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalled()
  })
})

describe('AutomationPolicies — 演练模式开关', () => {
  it('关闭演练弹二次确认——策略将从「只记录」变成「真动手」', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.togglePolicyDryRun.mockResolvedValue({ ...ROW, dryRun: false, version: 1 })

    await vm.togglingDryRun.run(ROW)
    await settle(w)

    expect(confirmMock).toHaveBeenCalled()
    expect(api.togglePolicyDryRun).toHaveBeenCalledWith(ROW.id, false, ROW.version)
  })

  it('用户取消时不提交', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    confirmMock.mockRejectedValue('cancel')

    await vm.togglingDryRun.run(ROW)
    await settle(w)

    expect(api.togglePolicyDryRun).not.toHaveBeenCalled()
  })

  it('切回演练不弹确认——回到更安全的状态不该有摩擦', async () => {
    const live = { ...ROW, dryRun: false }
    const w = await mountPage('/automation/policies', [live])
    const vm = vmOf(w)
    api.togglePolicyDryRun.mockResolvedValue({ ...live, dryRun: true, version: 1 })

    await vm.togglingDryRun.run(live)
    await settle(w)

    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.togglePolicyDryRun).toHaveBeenCalledWith(live.id, true, live.version)
  })

  it('确认框里说明该策略会做什么，而不是笼统警告', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.togglePolicyDryRun.mockResolvedValue({ ...ROW, dryRun: false })

    await vm.togglingDryRun.run(ROW)
    await settle(w)

    const message = String(confirmMock.mock.calls[0][0])
    expect(message).toContain('优雅重启 Pod')
    expect(message).toContain('staging')
  })

  it('切换后刷新统计——「真实执行」数必须跟着变', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.togglePolicyDryRun.mockResolvedValue({ ...ROW, dryRun: false, version: 1 })
    api.fetchPolicyStats.mockClear()

    await vm.togglingDryRun.run(ROW)
    await settle(w)

    expect(api.fetchPolicyStats).toHaveBeenCalled()
  })
})

describe('AutomationPolicies — 启停与删除', () => {
  it('启停带上版本号', async () => {
    const versioned = { ...ROW, version: 4 }
    const w = await mountPage('/automation/policies', [versioned])
    const vm = vmOf(w)
    api.togglePolicy.mockResolvedValue({ ...versioned, enabled: false, version: 5 })

    await vm.toggling.run(versioned)
    await settle(w)

    expect(api.togglePolicy).toHaveBeenCalledWith(versioned.id, false, 4)
  })

  it('删除需确认', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.deletePolicy.mockResolvedValue(undefined)

    await vm.removing.run(ROW)
    await settle(w)

    expect(confirmMock).toHaveBeenCalled()
    expect(api.deletePolicy).toHaveBeenCalledWith(ROW.id, ROW.version)
  })

  it('取消删除时不发请求', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    confirmMock.mockRejectedValue('cancel')

    await vm.removing.run(ROW)
    await settle(w)

    expect(api.deletePolicy).not.toHaveBeenCalled()
  })
})

describe('AutomationPolicies — 动作选择', () => {
  it('只拉取已启用的动作——引用停用动作会被后端拒绝', async () => {
    await mountPage()
    expect(api.fetchActions).toHaveBeenCalledWith(
      expect.objectContaining({ enabled: true })
    )
  })

  it('新建默认演练模式开启、策略未启用', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    await w.vm.$nextTick()

    expect(vm.form.dryRun).toBe(true)
    expect(vm.form.enabled).toBe(false)
  })

  it('编辑时回填全部字段含 version', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openEdit({ ...ROW, version: 3 })
    await w.vm.$nextTick()

    expect(vm.editingId).toBe(ROW.id)
    expect(vm.form.actionKey).toBe('k8s.pod.restart')
    expect(vm.form.version).toBe(3)
    expect(vm.form.priority).toBe(20)
  })
})

describe('AutomationPolicies — 级别勾选', () => {
  it('按 P0..P4 固定序输出，避免勾选顺序造成的假「已改动」', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    vm.form.matchAlertLevels = ''
    vm.toggleFormLevel('P3')
    vm.toggleFormLevel('P0')
    await w.vm.$nextTick()

    expect(vm.form.matchAlertLevels).toBe('P0,P3')
  })

  it('再次点击取消勾选', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.openCreate()
    vm.form.matchAlertLevels = 'P0,P3'
    vm.toggleFormLevel('P0')
    await w.vm.$nextTick()

    expect(vm.form.matchAlertLevels).toBe('P3')
  })
})

describe('AutomationPolicies — 匹配条件摘要', () => {
  it('把四个条件汇成一句人话', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    const text = vm.describeMatch(ROW)
    expect(text).toContain('P3')
    expect(text).toContain('K8S')
    expect(text).toContain('PodCrashLoopBackOff')
  })

  it('星号不出现在摘要里——它表示不限制，列出来是噪音', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    // ROW 的 matchServicePattern 是 '*'
    expect(vm.describeMatch(ROW)).not.toContain('服务 *')
  })

  it('全部无限制时显式说明', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    const text = vm.describeMatch({
      ...ROW,
      matchAlertLevels: null,
      matchModule: null,
      matchServicePattern: '*',
      matchAlertNamePattern: '*',
    })
    expect(text).toContain('无限制')
  })
})

describe('AutomationPolicies — 匹配预演', () => {
  it('提交预演输入并保留结果', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.simulatePolicies.mockResolvedValue({
      input: {},
      evaluated: [
        { policyId: 1, policyName: 'P3 重启', priority: 20, actionKey: 'k8s.pod.restart',
          dryRun: true, matched: true, skipped: false, reason: '演练中', outcome: 'DRY_RUN' },
      ],
      matchedCount: 1,
      firstEffective: null,
      summary: '将由策略「P3 重启」处理',
    })

    vm.simInput.level = 'P3'
    await vm.simulating.run()
    await settle(w)

    expect(api.simulatePolicies).toHaveBeenCalledWith(
      expect.objectContaining({ level: 'P3' })
    )
    expect(vm.simResult).toMatchObject({ matchedCount: 1 })
  })

  it('预演默认问 prod——用户最想确认生产环境的行为', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    expect(vm.simInput.environment).toBe('prod')
  })
})

describe('AutomationPolicies — 筛选与页码', () => {
  it('改筛选重置到第一页', async () => {
    const w = await mountPage('/automation/policies?page=4')
    const vm = vmOf(w)
    expect(vm.currentPage).toBe(4)

    vm.keyword = '重启'
    await settle(w)

    expect(vm.currentPage).toBe(1)
  })

  it('「不限」状态不把 enabled 传给后端', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.enabledFilter = 'false'
    await settle(w)
    expect(api.fetchPolicies.mock.calls.at(-1)?.[0]?.enabled).toBe(false)

    vm.enabledFilter = ''
    await settle(w)
    expect(api.fetchPolicies.mock.calls.at(-1)?.[0]?.enabled).toBeUndefined()
  })
})
