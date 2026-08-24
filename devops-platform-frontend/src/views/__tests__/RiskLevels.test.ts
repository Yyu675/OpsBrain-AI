/**
 * 风险等级配置页组件测试。
 *
 * ── 覆盖重点：「放宽 vs 收紧」的判定 ──────────────────────────
 * 这页最关键的逻辑不是表单，是 `loosenings()`——它决定哪些改动要弹二次确认。
 *
 * 这个判定必须**只对放宽方向触发**。若对收紧也弹确认，用户会对确认框脱敏，
 * 真正危险的那一次也一路点过去；若对放宽漏判，用户可能在毫无感知的情况下
 * 把「AI 不能自动执行」改成「AI 可以自动执行」。
 *
 * 两个方向的错误代价不对称，所以这组断言逐项覆盖了五种放宽方式
 * 与对应的收紧方式。
 *
 * ── 另一个重点：isDirty ────────────────────────────────────────
 * 没有它，用户点开编辑什么都没改也能提交，而每次提交 version+1，
 * 会把同事正在编辑的表单顶成冲突状态——一次空提交让别人白填一遍。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  fetchRiskPolicies: vi.fn(),
  updateRiskPolicy: vi.fn(),
}))
vi.mock('@/api/governance', () => api)

const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock },
  ElMessage: Object.assign(vi.fn(), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
}))

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

import RiskLevels from '../RiskLevels.vue'

const CONTROLLED = {
  riskLevel: 'CONTROLLED_WRITE' as const,
  displayName: '受控写操作',
  description: '有副作用但可控',
  approvalMode: 'SINGLE' as const,
  approvalTimeoutMinutes: 30,
  autoExecuteAllowed: false,
  maxBlastRadiusPercent: 20,
  maxBlastRadiusCount: 5,
  cooldownSeconds: 60,
  maxRetries: 1,
  escalateAfterMinutes: 15,
  escalateTarget: 'TICKET' as const,
  allowedEnvironments: 'staging,dev',
  version: 2,
  updatedBy: 'admin',
  updateTime: '2026-08-25 10:00:00',
}

const HIGH_RISK = {
  ...CONTROLLED,
  riskLevel: 'HIGH_RISK_EXECUTION' as const,
  displayName: '高风险执行',
  approvalMode: 'DUAL' as const,
  allowedEnvironments: 'dev',
  maxBlastRadiusPercent: 5,
  maxBlastRadiusCount: 1,
  version: 0,
}

let router: Router

const mountPage = async (policies = [CONTROLLED, HIGH_RISK]) => {
  api.fetchRiskPolicies.mockResolvedValue({
    items: policies,
    approvalModes: [
      { value: 'NONE', label: '免审批', requiredApprovers: 0 },
      { value: 'SINGLE', label: '单人审批', requiredApprovers: 1 },
      { value: 'DUAL', label: '双人审批', requiredApprovers: 2 },
    ],
    escalateTargets: [
      { value: 'NONE', label: '仅记录' },
      { value: 'TICKET', label: '自动开工单' },
      { value: 'ONCALL', label: '呼叫值班' },
    ],
  })

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div/>' }) },
      { path: '/automation/risk-levels', component: RiskLevels },
    ],
  })
  await router.push('/automation/risk-levels')
  await router.isReady()

  const wrapper = mount(RiskLevels, {
    global: {
      plugins: [router],
      stubs: {
        RelativeTime: true,
        DataStateBoundary: { template: '<div><slot /></div>' },
      },
    },
  })
  await vi.waitFor(() => expect(api.fetchRiskPolicies).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  return wrapper
}

type Draft = typeof CONTROLLED

type Vm = {
  policies: Draft[]
  drafts: Record<string, Draft>
  startEdit: (p: Draft) => void
  cancelEdit: (level: string) => void
  isEditing: (level: string) => boolean
  isDirty: (level: string) => boolean
  loosenings: (level: string) => string[]
  toggleEnvironment: (draft: Draft, env: string) => void
  hasEnvironment: (draft: Draft, env: string) => boolean
  saving: { run: (level: string) => Promise<unknown> }
  autonomousCount: number
  prodEnabledCount: number
}

const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

const settle = async (w: VueWrapper) => {
  await w.vm.$nextTick()
  await new Promise((r) => setTimeout(r, 0))
  await w.vm.$nextTick()
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
})

describe('RiskLevels — 放宽检测（决定是否二次确认）', () => {
  it('降低审批门槛算放宽', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.approvalMode = 'NONE'
    await w.vm.$nextTick()

    const reasons = vm.loosenings('CONTROLLED_WRITE')
    expect(reasons.some((r) => r.includes('审批门槛'))).toBe(true)
  })

  it('提高审批门槛不算放宽——收紧不该有确认摩擦', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.approvalMode = 'DUAL'
    await w.vm.$nextTick()

    expect(vm.loosenings('CONTROLLED_WRITE')).toHaveLength(0)
  })

  it('开启自动执行算放宽', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.autoExecuteAllowed = true
    await w.vm.$nextTick()

    expect(vm.loosenings('CONTROLLED_WRITE').some((r) => r.includes('自动执行'))).toBe(true)
  })

  it('关闭自动执行不算放宽', async () => {
    const autoOn = { ...CONTROLLED, autoExecuteAllowed: true }
    const w = await mountPage([autoOn])
    const vm = vmOf(w)

    vm.startEdit(autoOn)
    vm.drafts.CONTROLLED_WRITE.autoExecuteAllowed = false
    await w.vm.$nextTick()

    expect(vm.loosenings('CONTROLLED_WRITE')).toHaveLength(0)
  })

  it('提高爆炸半径（百分比与实例数）都算放宽', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.maxBlastRadiusCount = 20
    vm.drafts.CONTROLLED_WRITE.maxBlastRadiusPercent = 50
    await w.vm.$nextTick()

    const reasons = vm.loosenings('CONTROLLED_WRITE')
    expect(reasons.some((r) => r.includes('实例上限'))).toBe(true)
    expect(reasons.some((r) => r.includes('爆炸半径'))).toBe(true)
  })

  it('降低爆炸半径不算放宽', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.maxBlastRadiusCount = 1
    vm.drafts.CONTROLLED_WRITE.maxBlastRadiusPercent = 5
    await w.vm.$nextTick()

    expect(vm.loosenings('CONTROLLED_WRITE')).toHaveLength(0)
  })

  it('新增生效环境算放宽，且原因里点名新增了哪个', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    vm.toggleEnvironment(vm.drafts.CONTROLLED_WRITE, 'prod')
    await w.vm.$nextTick()

    const reasons = vm.loosenings('CONTROLLED_WRITE')
    expect(reasons.some((r) => r.includes('prod'))).toBe(true)
  })

  it('移除生效环境不算放宽', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    vm.toggleEnvironment(vm.drafts.CONTROLLED_WRITE, 'staging')
    await w.vm.$nextTick()

    expect(vm.loosenings('CONTROLLED_WRITE')).toHaveLength(0)
  })
})

describe('RiskLevels — 保存流程', () => {
  it('放宽时弹二次确认', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.updateRiskPolicy.mockResolvedValue({ ...CONTROLLED, version: 3 })

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.autoExecuteAllowed = true
    await w.vm.$nextTick()

    await vm.saving.run('CONTROLLED_WRITE')
    await settle(w)

    expect(confirmMock).toHaveBeenCalled()
    expect(api.updateRiskPolicy).toHaveBeenCalled()
  })

  it('纯收紧时不弹确认，直接保存', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.updateRiskPolicy.mockResolvedValue({ ...CONTROLLED, version: 3 })

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.maxBlastRadiusCount = 1
    await w.vm.$nextTick()

    await vm.saving.run('CONTROLLED_WRITE')
    await settle(w)

    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.updateRiskPolicy).toHaveBeenCalled()
  })

  it('用户取消确认时不提交', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    confirmMock.mockRejectedValue('cancel')

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.autoExecuteAllowed = true
    await w.vm.$nextTick()

    await vm.saving.run('CONTROLLED_WRITE')
    await settle(w)

    expect(api.updateRiskPolicy).not.toHaveBeenCalled()
  })

  it('提交时带上草稿的 version', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.updateRiskPolicy.mockResolvedValue({ ...CONTROLLED, version: 3 })

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.maxRetries = 0
    await w.vm.$nextTick()

    await vm.saving.run('CONTROLLED_WRITE')
    await settle(w)

    const [level, payload] = api.updateRiskPolicy.mock.calls[0]
    expect(level).toBe('CONTROLLED_WRITE')
    expect(payload.version).toBe(2)
  })

  it('保存成功后就地替换该行，不整表重拉——其他卡片可能正在编辑', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    api.updateRiskPolicy.mockResolvedValue({ ...CONTROLLED, maxRetries: 0, version: 3 })
    api.fetchRiskPolicies.mockClear()

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.maxRetries = 0
    await w.vm.$nextTick()

    await vm.saving.run('CONTROLLED_WRITE')
    await settle(w)

    expect(api.fetchRiskPolicies).not.toHaveBeenCalled()
    const updated = vm.policies.find((p) => p.riskLevel === 'CONTROLLED_WRITE')
    expect(updated?.version).toBe(3)
    expect(vm.isEditing('CONTROLLED_WRITE')).toBe(false)
  })
})

describe('RiskLevels — 编辑态', () => {
  it('无改动时 isDirty 为 false——防止空提交把别人的编辑顶成冲突', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    await w.vm.$nextTick()

    expect(vm.isDirty('CONTROLLED_WRITE')).toBe(false)

    vm.drafts.CONTROLLED_WRITE.cooldownSeconds = 120
    await w.vm.$nextTick()
    expect(vm.isDirty('CONTROLLED_WRITE')).toBe(true)
  })

  it('取消编辑真正回退，不改动原数据', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    vm.drafts.CONTROLLED_WRITE.approvalMode = 'NONE'
    await w.vm.$nextTick()

    vm.cancelEdit('CONTROLLED_WRITE')
    await w.vm.$nextTick()

    expect(vm.isEditing('CONTROLLED_WRITE')).toBe(false)
    // 草稿是副本，原对象不该被改动
    const original = vm.policies.find((p) => p.riskLevel === 'CONTROLLED_WRITE')
    expect(original?.approvalMode).toBe('SINGLE')
  })

  it('环境勾选按固定顺序输出，避免顺序差异造成的假「已改动」', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    const draft = vm.drafts.CONTROLLED_WRITE
    draft.allowedEnvironments = ''
    vm.toggleEnvironment(draft, 'dev')
    vm.toggleEnvironment(draft, 'prod')
    await w.vm.$nextTick()

    expect(draft.allowedEnvironments).toBe('prod,dev')
  })

  it('多张卡片可同时进入编辑，互不干扰', async () => {
    const w = await mountPage()
    const vm = vmOf(w)

    vm.startEdit(CONTROLLED)
    vm.startEdit(HIGH_RISK)
    await w.vm.$nextTick()

    expect(vm.isEditing('CONTROLLED_WRITE')).toBe(true)
    expect(vm.isEditing('HIGH_RISK_EXECUTION')).toBe(true)

    vm.drafts.CONTROLLED_WRITE.maxRetries = 3
    await w.vm.$nextTick()
    expect(vm.isDirty('CONTROLLED_WRITE')).toBe(true)
    expect(vm.isDirty('HIGH_RISK_EXECUTION')).toBe(false)
  })
})

describe('RiskLevels — 概览统计', () => {
  it('统计「允许自动执行」与「已开放生产环境」的等级数', async () => {
    const w = await mountPage([
      { ...CONTROLLED, autoExecuteAllowed: true, allowedEnvironments: 'prod,dev' },
      HIGH_RISK,
    ])
    const vm = vmOf(w)

    expect(vm.autonomousCount).toBe(1)
    expect(vm.prodEnabledCount).toBe(1)
  })
})
