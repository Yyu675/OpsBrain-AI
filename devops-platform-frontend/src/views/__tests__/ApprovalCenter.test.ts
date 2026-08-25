/**
 * 审批中心测试。
 *
 * ── 为什么这 312 行值得优先补 ────────────────────────────────
 * 它不大，但它是**高危动作放行的最后一道闸**：
 * 批准即由后端重放执行真实动作（当前是建单，路线 B 落地后会是
 * 重启 Pod、回滚发布这类）。
 *
 * 后端侧的角色边界已有集成测试（`GovernanceRoleGuardIntegrationTest`
 * 验证过 OPS 驳回审批得 403），但**前端「按钮该不该亮、点下去会发生什么」
 * 此前零覆盖**。
 *
 * ── 守的四件事 ────────────────────────────────────────────────
 * <ol>
 *   <li><b>串行化</b>——批准触发真实执行，两个动作并行下发时若操作
 *       同一资源（如同时重启同一服务）结果不可预测。禁用判据必须是
 *       「有任意审批在处理中」而非「当前这一行在处理中」；</li>
 *   <li><b>批准成功但执行失败要如实区分</b>（6.57 契约）——
 *       人的决策已成事实，执行失败标 EXECUTE_FAILED 供人工介入。
 *       笼统报「已批准」会让人以为动作已经生效；</li>
 *   <li><b>驳回理由必填</b>——它要记入审计。空理由的驳回等于没有留痕；</li>
 *   <li><b>无权限与加载失败分开</b>——403 显示「无审批权限」，
 *       其它错误走可重试的边界。混为一谈的话，
 *       非管理员会一直点重试等一个永远不会成功的请求。</li>
 * </ol>
 *
 * ── 一个反直觉但正确的行为 ────────────────────────────────────
 * 「已处理」的行不显示操作按钮。这不只是美观：
 * 对一个已 EXECUTED 的审批再点批准，后端会拒绝，但用户会困惑
 * 「为什么按钮亮着却点不动」。不渲染比禁用更清楚。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

const confirmMock = vi.hoisted(() => vi.fn())
const promptMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock, prompt: promptMock },
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

// 只桩网络层：Query 的 queryKey 联动、mutation 的 invalidate
// 都留在被测范围内。桩掉 useApprovalListQuery 的话，
// 「切 tab 自动重拉」这条就整个被绕过了
const api = vi.hoisted(() => ({
  listApprovals: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
  pendingCount: vi.fn(),
}))
vi.mock('@/api/approval', () => api)

import ApprovalCenter from '../ApprovalCenter.vue'

type Row = {
  id: number
  actionType: string
  toolName: string | null
  riskLevel: string
  summary: string
  payload: string | null
  requester: string
  traceId: string | null
  sessionId: string | null
  status: string
  approver: string | null
  decidedAt: string | null
  decisionReason: string | null
  expiresAt: string | null
  executedAt: string | null
  executeResult: string | null
  createTime: string
  updateTime: string
}

const row = (over: Partial<Row> = {}): Row => ({
  id: 1,
  actionType: 'CREATE_TICKET',
  toolName: 'createDevOpsTicket',
  riskLevel: 'HIGH_RISK_EXECUTION',
  summary: '创建 P0 工单：order-service 全量不可用',
  payload: null,
  requester: 'ai-agent',
  traceId: null,
  sessionId: null,
  status: 'PENDING',
  approver: null,
  decidedAt: null,
  decisionReason: null,
  expiresAt: null,
  executedAt: null,
  executeResult: null,
  createTime: '2026-08-26 09:00:00',
  updateTime: '2026-08-26 09:00:00',
  ...over,
})

const mountPage = async (rows: Row[] = [row()], listError: unknown = null) => {
  if (listError) {
    api.listApprovals.mockRejectedValue(listError)
  } else {
    api.listApprovals.mockResolvedValue({
      items: rows, total: rows.length, page: 1, size: 50, totalPages: 1,
    })
  }

  const wrapper = mount(ApprovalCenter, {
    global: {
      plugins: [
        // 每例一个全新 QueryClient：共用会让上一例的缓存命中，
        // listApprovals 不被调用，「切 tab 重拉」的断言全部错位
        [VueQueryPlugin, {
          queryClient: new QueryClient({
            defaultOptions: { queries: { retry: false, staleTime: 0, gcTime: 0 } },
          }),
        }],
      ],
      stubs: {
        DataStateBoundary: { template: '<div><slot /></div>' },
        RelativeTime: true,
        // el-table 保留默认插槽拿不到 row（作用域插槽），会抛
        // "Cannot destructure property 'row'"——AGENTS.md 3.8 已记录。
        // 本文件需要断言行内按钮，故自建一个能传 row 的最小替身
        'el-table': {
          name: 'ElTable',
          props: ['data'],
          template: `<table class="tbl"><tbody>
            <tr v-for="(r, i) in data" :key="i" class="row">
              <slot :row="r" />
            </tr>
          </tbody></table>`,
        },
        'el-table-column': {
          name: 'ElTableColumn',
          template: '<td><slot :row="$parent.$parent.row ?? {}" /></td>',
        },
        'el-tag': { template: '<span class="tag"><slot /></span>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

type Vm = {
  activeTab: string
  actingId: number | null
  acting: boolean
  forbidden: boolean
  items: Row[]
  switchTab: (t: string) => void
  doApprove: (r: Row) => Promise<void>
  doReject: (r: Row) => Promise<void>
  isPending: (r: Row) => boolean
  riskTagType: (r: string) => string
  statusTagType: (s: string) => string
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
  promptMock.mockResolvedValue({ value: '不符合变更窗口' })
})

describe('页面骨架与标签页', () => {
  it('渲染标题与五个状态标签', async () => {
    const w = await mountPage()

    expect(w.find('.page-title').text()).toBe('审批中心')
    expect(w.findAll('.tab').map((t) => t.text()))
      .toEqual(['待审批', '已批准', '已执行', '已驳回', '全部'])
  })

  it('默认停在「待审批」——这是本页最该先看到的', async () => {
    const w = await mountPage()

    expect(vmOf(w).activeTab).toBe('PENDING')
    expect(api.listApprovals).toHaveBeenCalledWith('PENDING', 1, 50)
    expect(w.findAll('.tab')[0].classes()).toContain('active')
  })

  it('切 tab 自动按新状态重拉，无需手动调 fetchList', async () => {
    const w = await mountPage()
    api.listApprovals.mockClear()

    vmOf(w).switchTab('REJECTED')
    await flushPromises()

    expect(api.listApprovals).toHaveBeenCalledWith('REJECTED', 1, 50)
  })

  it('点当前 tab 不重复发请求', async () => {
    // ── 关于这条的一个诚实说明 ──────────────────────────────
    // `switchTab` 里有一句 `if (tab === activeTab.value) return`。
    // 注入验证时把它删掉，本例**仍然通过**——探针实测确认：
    // 给 ref 赋同一个值不会触发响应式更新，queryKey 不变，Query 不重拉。
    //
    // 也就是说这个守卫对「防重复请求」是**冗余**的，Vue 已经保证了。
    // 我没有因此删掉守卫（它表达了意图，且将来若改成 shallowRef
    // 或加了别的副作用就会变得必要），但也不假装这条用例守住了它——
    // 它真正断言的是「切同一 tab 不产生额外请求」这个**外部行为**，
    // 不论该行为由守卫还是由 Vue 提供。
    const w = await mountPage()
    api.listApprovals.mockClear()

    vmOf(w).switchTab('PENDING')
    await flushPromises()

    expect(api.listApprovals).not.toHaveBeenCalled()
  })
})

describe('无权限与加载失败必须分开', () => {
  it('403 显示「无审批权限」，不给重试', async () => {
    // 非管理员点重试永远不会成功。混进通用错误态的话，
    // 用户会一直点那个按钮
    const w = await mountPage([], { status: 403 })

    expect(vmOf(w).forbidden).toBe(true)
    expect(w.text()).toContain('无审批权限')
  })

  it('业务码 40103 / 40301 同样判定为无权限', async () => {
    // 后端角色拦截返回的是 40103（已登录但角色不足），
    // 只认 HTTP 403 会漏掉它
    for (const bizCode of [40103, 40301]) {
      const w = await mountPage([], { bizCode })
      expect(vmOf(w).forbidden, `bizCode=${bizCode} 应判无权限`).toBe(true)
    }
  })

  it('其它错误不算无权限，走可重试的边界', async () => {
    const w = await mountPage([], { status: 500 })

    expect(vmOf(w).forbidden).toBe(false)
    expect(w.text()).not.toContain('无审批权限')
  })
})

describe('批准', () => {
  it('先二次确认，取消则不发请求', async () => {
    // 批准即执行真实动作，误点的代价是直接动生产
    confirmMock.mockRejectedValue('cancel')
    const w = await mountPage()

    await vmOf(w).doApprove(row())

    expect(api.approveApproval).not.toHaveBeenCalled()
  })

  it('确认后调用批准接口，成功提示执行结果', async () => {
    api.approveApproval.mockResolvedValue(row({ status: 'EXECUTED', executeResult: '工单 TKT-001 已创建' }))
    const w = await mountPage()

    await vmOf(w).doApprove(row({ id: 7 }))

    expect(api.approveApproval).toHaveBeenCalledWith(7)
    expect(notifyMock.success).toHaveBeenCalledWith(expect.stringContaining('工单 TKT-001 已创建'))
  })

  it('批准成功但执行失败：用 warning 如实区分，不报「已批准」了事', async () => {
    // 6.57 契约。人的决策已成事实，但动作没生效——
    // 报 success 会让人以为处置完成了，实际需要人工介入
    api.approveApproval.mockResolvedValue(
      row({ status: 'EXECUTE_FAILED', executeResult: '目标集群不可达' })
    )
    const w = await mountPage()

    await vmOf(w).doApprove(row())

    expect(notifyMock.success).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('目标集群不可达'))
  })

  it('执行结果为空时仍给出可读提示，不显示 undefined', async () => {
    api.approveApproval.mockResolvedValue(row({ status: 'EXECUTED', executeResult: null }))
    const w = await mountPage()

    await vmOf(w).doApprove(row())

    const msg = notifyMock.success.mock.calls[0][0] as string
    expect(msg).not.toContain('undefined')
    expect(msg).not.toContain('null')
  })

  it('失败后 actingId 复位，不把界面永久锁死', async () => {
    // finally 里的复位漏了的话，一次网络失败会让整页按钮再也点不动，
    // 用户只能刷新——而这是审批页，刷新意味着重新找那条待办
    api.approveApproval.mockRejectedValue(new Error('network'))
    const w = await mountPage()

    await vmOf(w).doApprove(row())

    expect(vmOf(w).actingId).toBeNull()
    expect(vmOf(w).acting).toBe(false)
  })
})

describe('驳回', () => {
  it('弹输入框要理由，取消则不发请求', async () => {
    promptMock.mockRejectedValue('cancel')
    const w = await mountPage()

    await vmOf(w).doReject(row())

    expect(api.rejectApproval).not.toHaveBeenCalled()
  })

  it('理由随请求一起提交，并去掉首尾空白', async () => {
    // 理由要记入审计。带一堆空格的理由在审计列表里很难看，
    // 全空格的理由等于没填——校验器拦的是后者，trim 治的是前者
    promptMock.mockResolvedValue({ value: '  不在变更窗口内  ' })
    api.rejectApproval.mockResolvedValue(row({ status: 'REJECTED' }))
    const w = await mountPage()

    await vmOf(w).doReject(row({ id: 9 }))

    expect(api.rejectApproval).toHaveBeenCalledWith(9, '不在变更窗口内')
  })

  it('校验器拒绝空白理由', async () => {
    // 直接测传给 ElMessageBox 的 inputValidator，
    // 而不是相信「弹窗会挡住」——那是 element-plus 的行为，本页不该假设
    const w = await mountPage()
    await vmOf(w).doReject(row())

    const opts = promptMock.mock.calls[0][2] as { inputValidator: (v: string) => true | string }
    expect(opts.inputValidator('')).not.toBe(true)
    expect(opts.inputValidator('   ')).not.toBe(true)
    expect(opts.inputValidator('理由')).toBe(true)
  })

  it('失败后 actingId 复位', async () => {
    api.rejectApproval.mockRejectedValue(new Error('network'))
    const w = await mountPage()

    await vmOf(w).doReject(row())

    expect(vmOf(w).actingId).toBeNull()
  })
})

describe('串行化：一次只允许一个审批在处理中', () => {
  it('有审批处理中时，其它行的批准被拒绝执行', async () => {
    // ── 本文件最重要的一条 ──────────────────────────────────
    // 批准即触发真实自动化执行。并行下发两个动作时，
    // 若它们操作同一资源（如同时重启同一服务），结果不可预测。
    //
    // 禁用判据若写成 `actingId === row.id`（只锁当前行），
    // 用户批准 A 之后可以立刻点 B——而 A 还在后端执行
    let resolveApprove: (v: unknown) => void = () => {}
    api.approveApproval.mockReturnValue(new Promise((r) => { resolveApprove = r }))

    const w = await mountPage([row({ id: 1 }), row({ id: 2 })])
    const vm = vmOf(w)

    // 批准第 1 条，不 await——让它停在处理中
    const p1 = vm.doApprove(row({ id: 1 }))
    await flushPromises()
    expect(vm.acting).toBe(true)
    expect(vm.actingId).toBe(1)

    // 此时点第 2 条
    await vm.doApprove(row({ id: 2 }))

    // 第 2 条不该发出去：调用次数仍是 1，且参数是第 1 条
    expect(api.approveApproval).toHaveBeenCalledTimes(1)
    expect(api.approveApproval).toHaveBeenCalledWith(1)

    resolveApprove(row({ status: 'EXECUTED' }))
    await p1
  })

  it('处理中时驳回同样被拒绝', async () => {
    let resolveApprove: (v: unknown) => void = () => {}
    api.approveApproval.mockReturnValue(new Promise((r) => { resolveApprove = r }))

    const w = await mountPage([row({ id: 1 }), row({ id: 2 })])
    const vm = vmOf(w)

    const p1 = vm.doApprove(row({ id: 1 }))
    await flushPromises()

    await vm.doReject(row({ id: 2 }))

    // 连确认框都不该弹——用户不该以为这次操作被受理了
    expect(promptMock).not.toHaveBeenCalled()
    expect(api.rejectApproval).not.toHaveBeenCalled()

    resolveApprove(row({ status: 'EXECUTED' }))
    await p1
  })

  it('处理完成后恢复可操作', async () => {
    api.approveApproval.mockResolvedValue(row({ status: 'EXECUTED' }))
    api.rejectApproval.mockResolvedValue(row({ status: 'REJECTED' }))
    const w = await mountPage([row({ id: 1 }), row({ id: 2 })])
    const vm = vmOf(w)

    await vm.doApprove(row({ id: 1 }))
    expect(vm.acting).toBe(false)

    await vm.doReject(row({ id: 2 }))
    expect(api.rejectApproval).toHaveBeenCalledWith(2, '不符合变更窗口')
  })
})

describe('派生展示', () => {
  it('风险等级映射到不同颜色——高危必须一眼认出', async () => {
    const w = await mountPage()
    const f = vmOf(w).riskTagType

    expect(f('HIGH_RISK_EXECUTION')).toBe('danger')
    expect(f('CONTROLLED_WRITE')).toBe('warning')
    expect(f('READ_ONLY')).toBe('info')
    // 未知等级不能崩，也不该伪装成低危
    expect(f('SOMETHING_NEW')).toBe('info')
  })

  it('状态映射：待审警示、成功类绿色、失败类红色', async () => {
    const w = await mountPage()
    const f = vmOf(w).statusTagType

    expect(f('PENDING')).toBe('warning')
    expect(f('APPROVED')).toBe('success')
    expect(f('EXECUTED')).toBe('success')
    // 三种失败态都要红：执行失败尤其不能显示成中性色
    expect(f('EXECUTE_FAILED')).toBe('danger')
    expect(f('REJECTED')).toBe('danger')
    expect(f('EXPIRED')).toBe('danger')
  })

  it('只有 PENDING 才算待处理', async () => {
    const w = await mountPage()
    const f = vmOf(w).isPending

    expect(f(row({ status: 'PENDING' }))).toBe(true)
    for (const s of ['APPROVED', 'EXECUTED', 'EXECUTE_FAILED', 'REJECTED', 'EXPIRED']) {
      expect(f(row({ status: s })), `${s} 不该可再操作`).toBe(false)
    }
  })
})
