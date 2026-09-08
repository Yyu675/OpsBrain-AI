/**
 * KnowledgeDetail 写操作路径测试。
 *
 * ── 为什么单测这几个方法而不是整页 ────────────────────────────
 * `KnowledgeDetail.vue` 1730 行，绝大部分是只读渲染与目录/滚动联动。
 * 真正「错了会出事」的是这五个写操作：
 * 发布、回滚版本、废弃、恢复、**彻底删除（不可逆）**。
 *
 * 它们有一组共同的形状：
 *   二次确认 → 置 loading → 调 store → 成功提示 → 刷新/跳转 → finally 复位
 *
 * 每一环都有各自的失败模式，且都**不会报错**：
 * - 确认被取消却继续执行 → 用户点了「取消」，文档照样被删；
 * - loading 未在 finally 复位 → 一次失败之后按钮永久转圈，只能刷新页面；
 * - 失败却弹成功提示 → 用户以为发布了，实际 AI 检索里根本没有这篇；
 * - 物理删除的理由未校验 → 合规审计拿不到举证依据。
 *
 * ── mock 边界 ─────────────────────────────────────────────────
 * mock store 与 ElMessageBox，组件逻辑真实执行。
 * store 自身的行为由 knowledge store 的测试覆盖，这里只关心
 * 「视图有没有按正确的顺序和条件去调它」。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const notifyMock = vi.hoisted(() => ({
  notify: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  handleServerError: vi.fn(),
}))
vi.mock('@/utils/notify', () => notifyMock)

const msgBox = vi.hoisted(() => ({
  confirm: vi.fn(() => Promise.resolve('confirm')),
  prompt: vi.fn(() => Promise.resolve({ value: '内容违规' })),
}))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('element-plus')
  return { ...actual, ElMessageBox: msgBox }
})

const storeMock = vi.hoisted(() => ({
  detail: null as unknown,
  detailStatus: 'success',
  versions: [] as unknown[],
  loadDetail: vi.fn(),
  loadVersions: vi.fn(),
  publishDoc: vi.fn(),
  deprecateDoc: vi.fn(),
  undoDeprecate: vi.fn(),
  restoreVersion: vi.fn(),
  purgeDoc: vi.fn(),
  listDocs: vi.fn(),
}))
vi.mock('@/stores/knowledge', () => ({ useKnowledgeStore: () => storeMock }))

// 只替换会发请求的函数，保留 statusLabel / indexStatusLabel 等纯展示函数。
// 整模块 mock 会把它们一并抹掉，模板里 statusLabel(doc.status) 直接抛错，
// 表现为「所有有文档的用例全挂」——而错误信息指向渲染，很容易误判成组件缺陷。
vi.mock('@/api/knowledge', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/knowledge')
  return {
    ...actual,
    fetchKnowledgeDocs: vi.fn(() => Promise.resolve({ docs: [], total: 0 })),
    fetchDocVersions: vi.fn(() => Promise.resolve([])),
    diffDocVersion: vi.fn(() => Promise.resolve({ additions: [], deletions: [] })),
  }
})

import KnowledgeDetail from '../KnowledgeDetail.vue'

const makeDoc = (over: Record<string, unknown> = {}) => ({
  id: 7,
  title: 'Redis 主从延迟处置 SOP',
  content: '# 标题\n\n正文内容',
  status: 'DRAFT',
  version: 3,
  category: '中间件',
  tags: [],
  indexStatus: 'SUCCESS',
  createTime: '2026-08-24T10:00:00',
  updateTime: '2026-08-24T10:00:00',
  ...over,
})

let router: Router

async function mountDetail(doc: Record<string, unknown> | null = makeDoc()) {
  storeMock.detail = doc
  storeMock.detailStatus = 'success'
  storeMock.versions = []

  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge', component: defineComponent({ template: '<div/>' }) },
      { path: '/knowledge/:id', component: KnowledgeDetail },
      { path: '/knowledge/:id/edit', component: defineComponent({ template: '<div/>' }) },
    ],
  })
  await router.push('/knowledge/7')
  await router.isReady()

  const wrapper = mount(KnowledgeDetail, {
    global: {
      plugins: [router],
      stubs: {
        'el-drawer': true, 'el-dialog': true, 'el-tag': true,
        'el-tooltip': true, 'el-skeleton': true,
        CollapsiblePanel: { template: '<div><slot /></div>' },
        DataStateBoundary: { template: '<div><slot /></div>' },
        RelativeTime: true,
      },
      directives: { permission: {} },
    },
  })
  await wrapper.vm.$nextTick()
  return wrapper
}

type DetailVm = {
  publishDoc: () => Promise<void>
  removeDoc: () => Promise<void>
  restoreDeprecated: () => Promise<void>
  purgeDoc: () => Promise<void>
  rollbackVersion: (v: number) => Promise<void>
  actionLoading: string | null
  restoringVersion: number | null
}
const vmOf = (w: VueWrapper) => w.vm as unknown as DetailVm

beforeEach(() => {
  vi.clearAllMocks()
  msgBox.confirm.mockResolvedValue('confirm')
  msgBox.prompt.mockResolvedValue({ value: '内容违规' })
  storeMock.publishDoc.mockResolvedValue({ indexStatus: 'SUCCESS' })
  storeMock.purgeDoc.mockResolvedValue(undefined)
  storeMock.restoreVersion.mockResolvedValue(undefined)
  storeMock.undoDeprecate.mockResolvedValue(undefined)
  storeMock.loadDetail.mockResolvedValue(undefined)
  storeMock.loadVersions.mockResolvedValue(undefined)
})

describe('发布文档', () => {
  it('二次确认后才发布', async () => {
    const w = await mountDetail()
    await vmOf(w).publishDoc()

    expect(msgBox.confirm).toHaveBeenCalled()
    expect(storeMock.publishDoc).toHaveBeenCalledWith(7)
  })

  it('取消确认时不发布——点了「取消」却照样执行是最严重的一类缺陷', async () => {
    msgBox.confirm.mockRejectedValue(new Error('cancel'))
    const w = await mountDetail()

    await vmOf(w).publishDoc()

    expect(storeMock.publishDoc).not.toHaveBeenCalled()
    // 取消是用户的正常选择，不该弹错误提示
    expect(notifyMock.handleServerError).not.toHaveBeenCalled()
  })

  it('向量化失败时给 warning 而非 success', async () => {
    storeMock.publishDoc.mockResolvedValue({ indexStatus: 'FAILED' })
    const w = await mountDetail()

    await vmOf(w).publishDoc()

    // 「已发布」但索引失败 = AI 检索里根本没有这篇。
    // 报成功会让作者以为大功告成，直到有人抱怨「问 AI 它不知道」
    expect(notifyMock.notify.success).not.toHaveBeenCalled()
    expect(notifyMock.notify.warning).toHaveBeenCalledWith(
      expect.stringContaining('向量化失败')
    )
  })

  it('发布成功后重新加载详情，界面状态与后端一致', async () => {
    const w = await mountDetail()
    storeMock.loadDetail.mockClear()

    await vmOf(w).publishDoc()

    expect(notifyMock.notify.success).toHaveBeenCalled()
    expect(storeMock.loadDetail).toHaveBeenCalled()
  })

  it('失败时走统一错误处理，且 loading 复位', async () => {
    storeMock.publishDoc.mockRejectedValue(new Error('后端不可用'))
    const w = await mountDetail()

    await vmOf(w).publishDoc()

    expect(notifyMock.handleServerError).toHaveBeenCalled()
    // 不在 finally 复位的话，一次失败后按钮永久转圈，只能刷新页面
    expect(vmOf(w).actionLoading).toBeNull()
  })

  it('无文档时直接返回，不调后端', async () => {
    const w = await mountDetail(null)

    await vmOf(w).publishDoc()

    expect(storeMock.publishDoc).not.toHaveBeenCalled()
  })
})

describe('版本回滚', () => {
  it('确认后回滚，并同时刷新详情与版本列表', async () => {
    const w = await mountDetail()

    await vmOf(w).rollbackVersion(2)

    expect(storeMock.restoreVersion).toHaveBeenCalledWith(7, 2)
    expect(notifyMock.notify.success).toHaveBeenCalledWith(expect.stringContaining('v2'))
    // 只刷详情不刷版本列表的话，历史里看不到刚产生的那条新版本
    expect(storeMock.loadDetail).toHaveBeenCalled()
    expect(storeMock.loadVersions).toHaveBeenCalled()
  })

  it('取消确认时不回滚', async () => {
    msgBox.confirm.mockRejectedValue(new Error('cancel'))
    const w = await mountDetail()

    await vmOf(w).rollbackVersion(2)

    expect(storeMock.restoreVersion).not.toHaveBeenCalled()
  })

  it('失败时 restoringVersion 复位——否则那一行永久显示「回滚中」', async () => {
    storeMock.restoreVersion.mockRejectedValue(new Error('冲突'))
    const w = await mountDetail()

    await vmOf(w).rollbackVersion(2)

    expect(notifyMock.handleServerError).toHaveBeenCalled()
    expect(vmOf(w).restoringVersion).toBeNull()
  })
})

describe('恢复已废弃文档', () => {
  it('仅对 DEPRECATED 状态生效', async () => {
    const w = await mountDetail(makeDoc({ status: 'PUBLISHED' }))

    await vmOf(w).restoreDeprecated()

    // 对已发布文档执行「恢复」没有意义，放行会产生一次无谓的版本自增
    expect(storeMock.undoDeprecate).not.toHaveBeenCalled()
  })

  it('DEPRECATED 时确认后恢复，并带上 version 做乐观锁', async () => {
    const w = await mountDetail(makeDoc({ status: 'DEPRECATED', version: 5 }))

    await vmOf(w).restoreDeprecated()

    // 不带 version 就绕过了并发保护，会覆盖他人的修改
    expect(storeMock.undoDeprecate).toHaveBeenCalledWith(7, 5)
  })

  it('失败时 loading 复位', async () => {
    storeMock.undoDeprecate.mockRejectedValue(new Error('x'))
    const w = await mountDetail(makeDoc({ status: 'DEPRECATED' }))

    await vmOf(w).restoreDeprecated()

    expect(vmOf(w).actionLoading).toBeNull()
  })
})

describe('彻底删除（不可逆）', () => {
  it('必须输入理由，且理由传给后端用于审计举证', async () => {
    const w = await mountDetail()

    await vmOf(w).purgeDoc()

    expect(msgBox.prompt).toHaveBeenCalled()
    // 合规场景下「谁、为什么删的」是审计的核心证据，丢了等于没记
    expect(storeMock.purgeDoc).toHaveBeenCalledWith(7, '内容违规')
  })

  it('理由两侧空白被裁剪', async () => {
    msgBox.prompt.mockResolvedValue({ value: '  涉密内容  ' })
    const w = await mountDetail()

    await vmOf(w).purgeDoc()

    expect(storeMock.purgeDoc).toHaveBeenCalledWith(7, '涉密内容')
  })

  it('理由为纯空白时不执行删除', async () => {
    msgBox.prompt.mockResolvedValue({ value: '   ' })
    const w = await mountDetail()

    await vmOf(w).purgeDoc()

    // 空理由等于没有审计依据，这类不可逆操作宁可不做
    expect(storeMock.purgeDoc).not.toHaveBeenCalled()
  })

  it('取消输入时不删除', async () => {
    msgBox.prompt.mockRejectedValue(new Error('cancel'))
    const w = await mountDetail()

    await vmOf(w).purgeDoc()

    expect(storeMock.purgeDoc).not.toHaveBeenCalled()
  })

  it('删除成功后跳回列表——留在已删除文档的详情页只会看到报错', async () => {
    const w = await mountDetail()

    await vmOf(w).purgeDoc()
    // 组件里 router.push 未被 await，导航是挂在微任务上的。
    // isReady() 在路由已就绪时立即 resolve，等不到这次跳转——
    // 用 flushPromises 把待处理的微任务队列排空才可靠。
    await flushPromises()

    expect(notifyMock.notify.success).toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/knowledge')
  })

  it('删除失败时不跳转，留在原页让用户看到原因', async () => {
    storeMock.purgeDoc.mockRejectedValue(new Error('无权限'))
    const w = await mountDetail()

    await vmOf(w).purgeDoc()

    expect(notifyMock.handleServerError).toHaveBeenCalled()
    // 失败还跳走的话，用户看不到失败原因，会以为删成功了
    expect(router.currentRoute.value.path).toBe('/knowledge/7')
    expect(vmOf(w).actionLoading).toBeNull()
  })
})
