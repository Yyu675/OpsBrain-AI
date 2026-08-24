/**
 * B4 复盘归档 composable 测试。
 *
 * 重点保护本次拆分时修掉的缺陷：
 * - 改进项必须按工单过滤 —— listActionItems() 返回全系统改进项
 *   （ActionItemBoard 看板需要跨工单全量），复盘抽屉若不过滤会把别的工单的
 *   改进项显示成本工单的（同 6.39「把 A 的数据当作 B 的呈现」家族）
 * - 切换工单必须 reset —— 否则上一张工单的复盘内容残留
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useTicketPostmortem } from '../useTicketPostmortem'
import type { ActionItemData, PostmortemData } from '@/api/tickets'

const mocks = vi.hoisted(() => ({
  getPostmortem: vi.fn(),
  savePostmortem: vi.fn(),
  generateTimelineDraft: vi.fn(),
  listActionItems: vi.fn(),
  addActionItem: vi.fn(),
  updateActionItem: vi.fn(),
}))

vi.mock('@/api/tickets', () => mocks)
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}))
vi.mock('@/utils/notify', () => ({ handleServerError: vi.fn() }))

/** 构造复盘记录 */
function pmRecord(overrides: Partial<PostmortemData> = {}): PostmortemData {
  return {
    id: 1,
    ticketId: 'TKT-A',
    timeline: '10:00 告警\n10:05 定位',
    impactScope: '支付服务',
    impactDuration: 30,
    lessons: '连接池配置过低',
    ...overrides,
  } as PostmortemData
}

/** 构造改进项 */
function actionItem(ticketId: string, id: number, content = '改进项'): ActionItemData {
  return { id, postmortemId: 1, ticketId, content, status: 'OPEN' }
}

function setup(ticketId: string | null = 'TKT-A', onSaved?: (id: string) => void) {
  return useTicketPostmortem({
    getTicketId: () => ticketId,
    getOperator: () => '王芳',
    onSaved,
  })
}

beforeEach(() => {
  Object.values(mocks).forEach(m => m.mockReset())
  mocks.listActionItems.mockResolvedValue([])
  mocks.getPostmortem.mockResolvedValue(null)
})

describe('初始状态', () => {
  it('无复盘记录、抽屉关闭、表单为空', () => {
    const pm = setup()
    expect(pm.postmortem.value).toBeNull()
    expect(pm.drawerVisible.value).toBe(false)
    expect(pm.form.value).toEqual({
      timeline: '', impactScope: '', impactDuration: null, lessons: '',
    })
    expect(pm.actionItems.value).toEqual([])
  })
})

describe('load — 改进项按工单过滤（核心缺陷）', () => {
  it('只保留当前工单的改进项 —— 否则会把别的工单的改进项显示成本工单的', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    mocks.listActionItems.mockResolvedValue([
      actionItem('TKT-A', 1, '本工单改进项'),
      actionItem('TKT-B', 2, '别的工单改进项'),
      actionItem('TKT-A', 3, '本工单改进项2'),
    ])

    const pm = setup('TKT-A')
    await pm.load()

    expect(pm.actionItems.value.map(i => i.id)).toEqual([1, 3])
    expect(pm.actionItems.value.every(i => i.ticketId === 'TKT-A')).toBe(true)
  })

  it('当前工单无改进项时为空数组，不误取别的工单的', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    mocks.listActionItems.mockResolvedValue([actionItem('TKT-B', 2)])

    const pm = setup('TKT-A')
    await pm.load()

    expect(pm.actionItems.value).toEqual([])
  })

  it('无复盘记录时不拉改进项 —— 改进项挂在复盘下，无复盘则必然没有', async () => {
    mocks.getPostmortem.mockResolvedValue(null)

    const pm = setup()
    await pm.load()

    expect(mocks.listActionItems).not.toHaveBeenCalled()
  })
})

describe('load — 表单回填', () => {
  it('把复盘记录回填到表单', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())

    const pm = setup()
    await pm.load()

    expect(pm.form.value).toEqual({
      timeline: '10:00 告警\n10:05 定位',
      impactScope: '支付服务',
      impactDuration: 30,
      lessons: '连接池配置过低',
    })
  })

  it('后端字段为 null 时回填空串，避免模板渲染出 "null"', async () => {
    mocks.getPostmortem.mockResolvedValue(
      pmRecord({ timeline: null, impactScope: null, lessons: null } as Partial<PostmortemData>)
    )

    const pm = setup()
    await pm.load()

    expect(pm.form.value.timeline).toBe('')
    expect(pm.form.value.impactScope).toBe('')
    expect(pm.form.value.lessons).toBe('')
  })

  it('加载失败不抛错，仅告警 —— 复盘是增值功能，不该阻塞详情页', async () => {
    mocks.getPostmortem.mockRejectedValue(new Error('网络不通'))

    const pm = setup()
    await expect(pm.load()).resolves.toBeUndefined()
    expect(pm.postmortem.value).toBeNull()
  })

  it('无工单号时直接返回，不发请求', async () => {
    const pm = setup(null)
    await pm.load()
    expect(mocks.getPostmortem).not.toHaveBeenCalled()
  })

  it('加载中标记在完成后复位', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    const pm = setup()
    await pm.load()
    expect(pm.postmortemLoading.value).toBe(false)
  })
})

describe('open — 首次打开自动生成草稿', () => {
  it('已有复盘记录时不重复加载', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    const pm = setup()
    await pm.load()
    mocks.getPostmortem.mockClear()

    await pm.open()
    expect(mocks.getPostmortem).not.toHaveBeenCalled()
    expect(pm.drawerVisible.value).toBe(true)
  })

  it('无复盘记录时自动生成时间线草稿', async () => {
    mocks.getPostmortem.mockResolvedValue(null)
    mocks.generateTimelineDraft.mockResolvedValue('10:00 建单\n10:30 首响')

    const pm = setup()
    await pm.open()

    expect(pm.form.value.timeline).toBe('10:00 建单\n10:30 首响')
  })

  it('草稿生成失败时留空表单供手工填写，不阻塞抽屉打开', async () => {
    mocks.getPostmortem.mockResolvedValue(null)
    mocks.generateTimelineDraft.mockRejectedValue(new Error('AI 不可用'))

    const pm = setup()
    await pm.open()

    expect(pm.drawerVisible.value).toBe(true)
    expect(pm.form.value.timeline).toBe('')
  })

  it('已有复盘记录时不生成草稿 —— 会覆盖用户已保存的时间线', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())

    const pm = setup()
    await pm.open()

    expect(mocks.generateTimelineDraft).not.toHaveBeenCalled()
    expect(pm.form.value.timeline).toBe('10:00 告警\n10:05 定位')
  })
})

describe('generateDraft（手动点「生成草稿」）', () => {
  it('覆盖当前时间线', async () => {
    mocks.generateTimelineDraft.mockResolvedValue('新草稿')
    const pm = setup()
    pm.form.value.timeline = '旧内容'

    await pm.generateDraft()
    expect(pm.form.value.timeline).toBe('新草稿')
  })

  it('失败时保留原内容，不清空用户已写的', async () => {
    mocks.generateTimelineDraft.mockRejectedValue(new Error('x'))
    const pm = setup()
    pm.form.value.timeline = '用户手写的内容'

    await pm.generateDraft()
    expect(pm.form.value.timeline).toBe('用户手写的内容')
  })
})

describe('save', () => {
  it('提交表单并回填后端返回的记录', async () => {
    const saved = pmRecord({ id: 9 })
    mocks.savePostmortem.mockResolvedValue(saved)

    const pm = setup()
    pm.form.value = { timeline: 'T', impactScope: 'S', impactDuration: 5, lessons: 'L' }
    await pm.save()

    expect(mocks.savePostmortem).toHaveBeenCalledWith(
      'TKT-A',
      { ticketId: 'TKT-A', timeline: 'T', impactScope: 'S', impactDuration: 5, lessons: 'L' },
      '王芳'
    )
    // 用深比较而非 toBe：ref 会把对象包成响应式代理，引用不再相等
    expect(pm.postmortem.value).toEqual(saved)
  })

  it('保存成功后触发 onSaved —— 后端会记活动流，需刷新时间线', async () => {
    mocks.savePostmortem.mockResolvedValue(pmRecord())
    const onSaved = vi.fn()

    const pm = setup('TKT-A', onSaved)
    await pm.save()

    expect(onSaved).toHaveBeenCalledWith('TKT-A')
  })

  it('保存中重复调用被忽略 —— 防重复提交', async () => {
    let resolveFn!: (v: PostmortemData) => void
    mocks.savePostmortem.mockReturnValue(new Promise(r => { resolveFn = r }))

    const pm = setup()
    const first = pm.save()
    await pm.save()

    expect(mocks.savePostmortem).toHaveBeenCalledTimes(1)
    resolveFn(pmRecord())
    await first
  })

  it('保存失败后 saving 复位，用户可重试', async () => {
    mocks.savePostmortem.mockRejectedValue(new Error('x'))
    const pm = setup()
    await pm.save()
    expect(pm.saving.value).toBe(false)
  })

  it('无工单号时不提交', async () => {
    const pm = setup(null)
    await pm.save()
    expect(mocks.savePostmortem).not.toHaveBeenCalled()
  })
})

describe('addItem', () => {
  it('复盘未保存（无 postmortemId）时不提交 —— 改进项无处挂载', async () => {
    const pm = setup()
    pm.newActionItem.value = { content: '加监控', owner: '', dueDate: '' }

    await pm.addItem()
    expect(mocks.addActionItem).not.toHaveBeenCalled()
  })

  it('内容为空时不提交', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    const pm = setup()
    await pm.load()
    pm.newActionItem.value = { content: '   ', owner: '', dueDate: '' }

    await pm.addItem()
    expect(mocks.addActionItem).not.toHaveBeenCalled()
  })

  it('添加成功后追加到列表并清空输入', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    const pm = setup()
    await pm.load()

    const created = actionItem('TKT-A', 7, '加监控')
    mocks.addActionItem.mockResolvedValue(created)
    pm.newActionItem.value = { content: '加监控', owner: '李强', dueDate: '2026-09-01' }

    await pm.addItem()

    // 深比较：ref 的响应式代理使引用相等失效
    expect(pm.actionItems.value).toEqual([created])
    expect(pm.newActionItem.value).toEqual({ content: '', owner: '', dueDate: '' })
  })

  it('内容前后空白被去除', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    const pm = setup()
    await pm.load()
    mocks.addActionItem.mockResolvedValue(actionItem('TKT-A', 8))
    pm.newActionItem.value = { content: '  加监控  ', owner: '  ', dueDate: '' }

    await pm.addItem()

    expect(mocks.addActionItem).toHaveBeenCalledWith('TKT-A', {
      postmortemId: 1,
      content: '加监控',
      owner: undefined,
      dueDate: undefined,
    })
  })

  it('添加失败时不污染列表', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    const pm = setup()
    await pm.load()
    mocks.addActionItem.mockRejectedValue(new Error('x'))
    pm.newActionItem.value = { content: '加监控', owner: '', dueDate: '' }

    await pm.addItem()
    expect(pm.actionItems.value).toEqual([])
  })
})

describe('updateItemStatus', () => {
  it('乐观更新本地状态', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    mocks.listActionItems.mockResolvedValue([actionItem('TKT-A', 1)])
    const pm = setup()
    await pm.load()

    mocks.updateActionItem.mockResolvedValue(undefined)
    await pm.updateItemStatus(1, 'DONE')

    expect(pm.actionItems.value[0].status).toBe('DONE')
  })

  it('只改目标项，不影响其他项', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    mocks.listActionItems.mockResolvedValue([
      actionItem('TKT-A', 1), actionItem('TKT-A', 2),
    ])
    const pm = setup()
    await pm.load()

    mocks.updateActionItem.mockResolvedValue(undefined)
    await pm.updateItemStatus(2, 'DONE')

    expect(pm.actionItems.value.find(i => i.id === 1)?.status).toBe('OPEN')
    expect(pm.actionItems.value.find(i => i.id === 2)?.status).toBe('DONE')
  })

  it('失败时不改本地状态 —— 避免 UI 显示未落库的状态', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    mocks.listActionItems.mockResolvedValue([actionItem('TKT-A', 1)])
    const pm = setup()
    await pm.load()

    mocks.updateActionItem.mockRejectedValue(new Error('x'))
    await pm.updateItemStatus(1, 'DONE')

    expect(pm.actionItems.value[0].status).toBe('OPEN')
  })
})

describe('reset（切换工单时必须调用）', () => {
  it('清空全部复盘状态 —— 否则上一张工单的内容残留', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    mocks.listActionItems.mockResolvedValue([actionItem('TKT-A', 1)])
    const pm = setup()
    await pm.open()

    pm.reset()

    expect(pm.postmortem.value).toBeNull()
    expect(pm.actionItems.value).toEqual([])
    expect(pm.form.value).toEqual({
      timeline: '', impactScope: '', impactDuration: null, lessons: '',
    })
    expect(pm.newActionItem.value).toEqual({ content: '', owner: '', dueDate: '' })
    expect(pm.drawerVisible.value).toBe(false)
  })

  it('reset 后可正常加载新工单的复盘', async () => {
    mocks.getPostmortem.mockResolvedValue(pmRecord())
    const pm = setup()
    await pm.load()
    pm.reset()

    mocks.getPostmortem.mockResolvedValue(pmRecord({ timeline: '新工单时间线' }))
    await pm.load()

    expect(pm.form.value.timeline).toBe('新工单时间线')
  })
})
