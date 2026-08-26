/**
 * 知识沉淀抽屉（工单 → 知识库闭环）测试。
 *
 * ── 为什么补这一个 ────────────────────────────────────────────
 * 880 行，是当前**最大的零测试组件**。而它承载的是 PRD 里被列为
 * 核心价值的那条闭环：故障处理完 → AI 整理成 RCA 复盘 → 一键入库 →
 * 下次同类故障可被检索到。
 *
 * ── 这里出错的后果不是「不好用」，是知识丢失 ──────────────────
 * <ul>
 *   <li><b>校验漏掉</b> → 空标题/空正文的文档进库，检索时命中一篇空壳；</li>
 *   <li><b>重复内容被静默吞掉</b> → 用户以为发布成功，实际什么都没存；</li>
 *   <li><b>向量化状态没如实说</b> → 索引 FAILED 却提示「已发布」，
 *       用户以为下次能检索到，实际永远搜不出来；</li>
 *   <li><b>流未清理</b> → 抽屉关了 AI 还在写，token 继续烧钱。</li>
 * </ul>
 *
 * ── 断言口径 ──────────────────────────────────────────────────
 * 重点放在**「提示词与真实结果一致」**这条线上。这类缺陷不会抛异常、
 * 不会让页面崩，只会让用户对系统状态产生错误认知——
 * 而知识库的价值恰恰建立在「我存进去了，下次能搜到」这个信任上。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock, prompt: vi.fn() },
  ElMessage: Object.assign(vi.fn(), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
}))

const notifyMock = vi.hoisted(() => ({
  success: vi.fn(), warning: vi.fn(), error: vi.fn(),
  info: vi.fn(), clearCooldown: vi.fn(),
}))
const handleServerErrorMock = vi.hoisted(() => vi.fn())
vi.mock('@/utils/notify', () => ({
  notify: notifyMock,
  handleServerError: handleServerErrorMock,
}))

const chatStreamMock = vi.hoisted(() => vi.fn())
vi.mock('@/api/chat', () => ({ chatStream: chatStreamMock }))

const knowledgeApi = vi.hoisted(() => ({
  createKnowledgeDoc: vi.fn(),
  fetchKnowledgeCategories: vi.fn(),
  fetchHotTags: vi.fn(),
}))
// 用 importActual 保留 DuplicateContentError 等真实类——
// 整模块 mock 会把它替换成 undefined，`instanceof` 判断恒为 false，
// 「重复内容」那条分支就永远走不到，测试变成假绿
vi.mock('@/api/knowledge', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/knowledge')
  return { ...actual, ...knowledgeApi }
})

import { DuplicateContentError } from '@/api/knowledge'
import KnowledgeSinkDrawer from '../KnowledgeSinkDrawer.vue'

const baseProps = {
  modelValue: false,
  ticketId: '1024',
  ticketTitle: 'order-service Pod CrashLoopBackOff',
  ticketService: 'order-service',
  ticketDescription: '容器启动后 30 秒退出',
  ticketReplies: [
    { role: 'user', author: '李强', time: '09:00', content: '已确认是配置错误' },
  ],
  ticketActivities: [
    { text: '状态变更', detail: '待处理 → 处理中', user: '张明', time: '09:05' },
  ],
}

const okResult = (over: Record<string, unknown> = {}) => ({
  id: 88,
  retrievable: true,
  indexStatus: 'SUCCESS',
  nearDuplicates: [],
  ...over,
})

const mountDrawer = async (open = true) => {
  knowledgeApi.fetchKnowledgeCategories.mockResolvedValue([])
  knowledgeApi.fetchHotTags.mockResolvedValue([])
  // 默认不产生流，避免每个用例都要处理 SSE
  chatStreamMock.mockResolvedValue(undefined)

  const wrapper = mount(KnowledgeSinkDrawer, {
    props: { ...baseProps, modelValue: false },
    global: {
      stubs: {
        'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' },
        'el-tabs': { template: '<div><slot /></div>' },
        'el-tab-pane': { template: '<div><slot /></div>' },
        'el-input': true, 'el-select': true, 'el-option': true,
        'el-tag': true, 'el-button': true, 'el-alert': true,
      },
    },
  })
  if (open) {
    // 走 watch(modelValue) 这条真实路径，而不是直接改内部状态——
    // 「打开时重置表单」的逻辑就挂在这个 watch 上
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
  }
  return wrapper
}

type Vm = {
  formTitle: string
  formContent: string
  formCategory: string
  formTags: string[]
  formSummary: string
  publishing: boolean
  streaming: boolean
  handlePublish: () => Promise<void>
  closeDrawer: () => void
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
})

describe('打开抽屉：表单预填', () => {
  it('标题预填为「【故障复盘】+ 工单标题」', async () => {
    // 复盘文档的标题格式统一，检索时才能一眼认出是哪类文档
    const w = await mountDrawer()
    expect(vmOf(w).formTitle).toBe('【故障复盘】order-service Pod CrashLoopBackOff')
  })

  it('分类预填为工单所属服务', async () => {
    const w = await mountDrawer()
    expect(vmOf(w).formCategory).toBe('order-service')
  })

  it('每次打开都重置标签与摘要——不残留上一张工单的内容', async () => {
    // 抽屉是复用的。不重置的话，从工单 A 沉淀完再打开工单 B，
    // B 的文档会带着 A 的标签发布出去
    const w = await mountDrawer()
    const vm = vmOf(w)

    vm.formTags = ['遗留标签']
    vm.formSummary = '遗留摘要'
    await w.setProps({ modelValue: false })
    await w.setProps({ modelValue: true })
    await flushPromises()

    expect(vm.formTags).toEqual([])
    expect(vm.formSummary).toBe('')
  })

  it('打开时触发 AI 整理与建议加载', async () => {
    const w = await mountDrawer()
    expect(chatStreamMock).toHaveBeenCalled()
    expect(w.exists()).toBe(true)
  })
})

describe('发布前校验', () => {
  it('空标题被拒绝，不发请求', async () => {
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '   '
    vm.formContent = '正文'

    await vm.handlePublish()

    expect(knowledgeApi.createKnowledgeDoc).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('标题'))
  })

  it('空正文被拒绝——空壳文档进库会污染检索结果', async () => {
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '   '

    await vm.handlePublish()

    expect(knowledgeApi.createKnowledgeDoc).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('正文'))
  })

  it('校验失败不置 publishing——否则按钮永久禁用', async () => {
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = ''

    await vm.handlePublish()

    expect(vm.publishing).toBe(false)
  })
})

describe('发布请求的载荷', () => {
  it('携带来源工单，形成「已沉淀为知识」的回链', async () => {
    // sourceTicketId 是工单详情页显示徽标的依据。
    // 漏了它，闭环只完成一半：知识存进去了，但工单上看不出来
    knowledgeApi.createKnowledgeDoc.mockResolvedValue(okResult())
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = ' 标题 '
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(knowledgeApi.createKnowledgeDoc).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceTicketId: 1024,
        sourceType: 'TICKET',
        knowledgeSource: 'ticket-sink',
        publish: true,
      })
    )
  })

  it('标题去首尾空白后提交', async () => {
    knowledgeApi.createKnowledgeDoc.mockResolvedValue(okResult())
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '  带空格的标题  '
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(knowledgeApi.createKnowledgeDoc).toHaveBeenCalledWith(
      expect.objectContaining({ title: '带空格的标题' })
    )
  })

  it('空分类/空摘要提交 undefined 而非空串', async () => {
    // 空串会被后端当成「显式设置为空」存进去，
    // 而 undefined 才是「不提供，走默认」
    knowledgeApi.createKnowledgeDoc.mockResolvedValue(okResult())
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'
    vm.formCategory = '   '
    vm.formSummary = ''

    await vm.handlePublish()
    await flushPromises()

    const payload = knowledgeApi.createKnowledgeDoc.mock.calls[0][0]
    expect(payload.category).toBeUndefined()
    expect(payload.summary).toBeUndefined()
  })
})

describe('向量化状态必须如实告知', () => {
  const publish = async (result: Record<string, unknown>) => {
    knowledgeApi.createKnowledgeDoc.mockResolvedValue(result)
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'
    await vm.handlePublish()
    await flushPromises()
    return String(notifyMock.success.mock.calls[0]?.[0] ?? '')
  }

  it('已向量化：明说下次可被 AI 引用', async () => {
    const msg = await publish(okResult({ retrievable: true }))
    expect(msg).toContain('已向量化')
  })

  it('索引处理中：说明稍后可检索，不谎称已完成', async () => {
    const msg = await publish(okResult({ retrievable: false, indexStatus: 'PENDING' }))
    expect(msg).toContain('处理中')
  })

  it('索引失败：必须说出来——否则用户永远搜不到却不知情', async () => {
    // 这条是本组的核心。文档确实存进去了，但向量化失败意味着
    // 检索命中不了。只报「已发布」会让用户以为闭环完成了
    const msg = await publish(okResult({ retrievable: false, indexStatus: 'FAILED' }))
    expect(msg).toContain('失败')
  })

  it('近似重复：发布但同时告警，不阻断', async () => {
    // 近似不等于重复，阻断会让合理的相似文档无法沉淀；
    // 但不提示又会让知识库慢慢长出一堆雷同文档
    knowledgeApi.createKnowledgeDoc.mockResolvedValue(
      okResult({ nearDuplicates: [{ id: 1 }, { id: 2 }] })
    )
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('2'))
    // 仍然算发布成功
    expect(notifyMock.success).toHaveBeenCalled()
  })
})

describe('发布成功后的收尾', () => {
  it('抛出 published 事件带上 docId 与标题', async () => {
    knowledgeApi.createKnowledgeDoc.mockResolvedValue(okResult({ id: 999 }))
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '复盘文档'
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(w.emitted('published')?.[0]).toEqual([999, '复盘文档'])
  })

  it('关闭抽屉', async () => {
    knowledgeApi.createKnowledgeDoc.mockResolvedValue(okResult())
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    const events = w.emitted('update:modelValue') ?? []
    expect(events[events.length - 1]).toEqual([false])
  })

  it('无论成败都复位 publishing', async () => {
    knowledgeApi.createKnowledgeDoc.mockRejectedValue(new Error('network'))
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(vm.publishing).toBe(false)
  })
})

describe('重复内容（40021）不得静默吞掉', () => {
  it('弹确认框并给出已存在文档的标题', async () => {
    knowledgeApi.createKnowledgeDoc.mockRejectedValue(
      new DuplicateContentError('duplicate', 42, '已有的复盘文档')
    )
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(confirmMock).toHaveBeenCalled()
    expect(String(confirmMock.mock.calls[0][0])).toContain('已有的复盘文档')
  })

  it('用户选择跳转 → 抛 goto-doc 并关闭抽屉', async () => {
    knowledgeApi.createKnowledgeDoc.mockRejectedValue(
      new DuplicateContentError('duplicate', 42, '已有文档')
    )
    confirmMock.mockResolvedValue('confirm')
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(w.emitted('goto-doc')?.[0]).toEqual([42])
  })

  it('用户选择留下 → 不跳转、不关闭，草稿保留', async () => {
    // 用户可能想改一改再发。此时把抽屉关掉等于丢掉他刚编辑的内容
    knowledgeApi.createKnowledgeDoc.mockRejectedValue(
      new DuplicateContentError('duplicate', 42, '已有文档')
    )
    confirmMock.mockRejectedValue('cancel')
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '我编辑过的正文'

    await vm.handlePublish()
    await flushPromises()

    expect(w.emitted('goto-doc')).toBeUndefined()
    expect(vm.formContent).toBe('我编辑过的正文')
  })

  it('重复错误不走 handleServerError——它有专门的交互', async () => {
    knowledgeApi.createKnowledgeDoc.mockRejectedValue(
      new DuplicateContentError('duplicate', 42, '已有文档')
    )
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(handleServerErrorMock).not.toHaveBeenCalled()
  })
})

describe('其它错误走统一映射', () => {
  it('普通失败交给 handleServerError，而不是裸 notify.error', async () => {
    // 裸 notify.error 会直接透传后端 message，丢掉业务码映射：
    // 40009（他人已修改）会显示成一句技术描述，却不告诉用户「刷新后重试」
    knowledgeApi.createKnowledgeDoc.mockRejectedValue(new Error('boom'))
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(handleServerErrorMock).toHaveBeenCalledWith(
      expect.any(Error),
      expect.objectContaining({ action: '沉淀为知识' })
    )
  })

  it('失败时不抛 published 事件——父组件不该以为成功了', async () => {
    knowledgeApi.createKnowledgeDoc.mockRejectedValue(new Error('boom'))
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.formTitle = '标题'
    vm.formContent = '正文'

    await vm.handlePublish()
    await flushPromises()

    expect(w.emitted('published')).toBeUndefined()
  })
})

describe('关闭与卸载：流必须停', () => {
  it('关闭抽屉抛出 update:modelValue=false', async () => {
    const w = await mountDrawer()
    vmOf(w).closeDrawer()

    const events = w.emitted('update:modelValue') ?? []
    expect(events[events.length - 1]).toEqual([false])
  })

  it('卸载时不残留 streaming 状态——否则 AI 还在写、token 继续烧', async () => {
    const w = await mountDrawer()
    const vm = vmOf(w)
    vm.streaming = true

    w.unmount()

    expect(vm.streaming).toBe(false)
  })
})
