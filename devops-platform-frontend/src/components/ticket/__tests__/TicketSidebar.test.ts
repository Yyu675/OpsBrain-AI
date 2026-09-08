/**
 * 工单详情右侧栏四个子组件的渲染与事件契约测试。
 *
 * ── 为什么把四个组件放在同一个文件 ────────────────────────────
 * 它们是同一次拆分的产物、共享同一批断言口径（画对了没有 / 交互抛回去了没有），
 * 且每个都只有几十行。拆成四个文件会让「这批组件整体是否安全」
 * 分散到四处，反而看不清覆盖面。
 *
 * ── 为什么必须补这一批 ────────────────────────────────────────
 * 拆分前，右侧栏的 DOM 由 `TicketDetail.vue` 直接渲染，
 * 而 `TicketDetail.render.smoke.test.ts` 对右栏**一条断言都没有**——
 * 它守的是「四种页面状态互斥」和左栏关键信息。
 *
 * 也就是说：搬运过程中把整个右栏画错（少一行属性、事件接反、
 * 标签删除按钮点了没反应），80 例现有用例照样全绿。
 * 这批用例就是把那道缺口补上。
 *
 * ── 断言选择的原则 ────────────────────────────────────────────
 * 只断言「正确实现与错误实现会给出不同答案」的东西：
 *  - `emit('remove', tag)` 断言载荷是**哪个** tag，不只是「触发了」——
 *    v-for 里把 `tag` 写成外层变量是最常见的搬运错误，只断触发抓不到；
 *  - SLA 超时断言文案是「已超时」**且**不含百分比——两者只断一个都会漏：
 *    超时分支写成 `slaProgress + '% 已消耗'` 时前者失败、
 *    正常分支写死「已超时」时后者失败。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import type { TicketAttachmentMeta } from '@/api/tickets'
import type { TicketProperty } from '@/composables/useTicketClosure'
import type { TicketActivity } from '@/stores/tickets'

import TicketActivityLog from '../TicketActivityLog.vue'
import TicketAttachmentPanel from '../TicketAttachmentPanel.vue'
import TicketPropsPanel from '../TicketPropsPanel.vue'
import TicketTagEditor from '../TicketTagEditor.vue'

// ==================== TicketPropsPanel ====================

const props: TicketProperty[] = [
  { label: '工单编号', value: 'TKT-20260825-0001', mono: true },
  { label: '优先级', value: '紧急', type: 'priority-urgent' },
  { label: '状态', value: '处理中', type: 'status' },
  { label: '所属服务', value: 'order-service' },
]

const mountProps = (over: Partial<InstanceType<typeof TicketPropsPanel>['$props']> = {}) =>
  mount(TicketPropsPanel, {
    props: {
      properties: props,
      slaProgress: 40,
      slaBreached: false,
      slaBarClass: 'progress-fill-normal',
      ...over,
    },
  })

describe('TicketPropsPanel', () => {
  it('每一行属性都渲染出标签与值', () => {
    const w = mountProps()
    const rows = w.findAll('.prop-row')

    expect(rows).toHaveLength(4)
    // 断言配对而非「总文本包含」——后者在标签与值错位时照样通过
    expect(rows[0].find('.prop-label').text()).toBe('工单编号')
    expect(rows[0].find('.prop-value').text()).toBe('TKT-20260825-0001')
    expect(rows[3].find('.prop-label').text()).toBe('所属服务')
    expect(rows[3].find('.prop-value').text()).toBe('order-service')
  })

  it('mono / 优先级 / 状态各自带上对应的着色 class', () => {
    const w = mountProps()
    const values = w.findAll('.prop-value')

    expect(values[0].classes()).toContain('prop-mono')
    expect(values[1].classes()).toContain('prop-priority-urgent')
    expect(values[2].classes()).toContain('prop-status')
    // 无 type 的普通行不该被误着色
    expect(values[3].classes()).toEqual(['prop-value'])
  })

  it('只有 urgent / high 画优先级圆点，medium / low 不画', () => {
    // 这条守的是那串「看起来啰嗦」的判定。写成 startsWith('priority-')
    // 会让四档全画点，而右栏那个点的意义正是「一眼找出要紧的单」
    const w = mountProps({
      properties: [
        { label: 'A', value: '紧急', type: 'priority-urgent' },
        { label: 'B', value: '高', type: 'priority-high' },
        { label: 'C', value: '中', type: 'priority-medium' },
        { label: 'D', value: '低', type: 'priority-low' },
      ],
    })

    const dotted = w.findAll('.prop-row').map((r) => r.find('.prop-dot').exists())
    expect(dotted).toEqual([true, true, false, false])
  })

  it('SLA 未超时：显示百分比，进度条宽度与 class 都跟着走', () => {
    const w = mountProps({ slaProgress: 40, slaBreached: false, slaBarClass: 'progress-fill-normal' })

    expect(w.find('.sla-value').text()).toBe('40% 已消耗')
    expect(w.find('.sla-value').classes()).not.toContain('sla-value-breached')

    const fill = w.find('.progress-fill')
    expect(fill.attributes('style')).toContain('width: 40%')
    expect(fill.classes()).toContain('progress-fill-normal')
  })

  it('SLA 已超时：文案换成「已超时」且不再显示百分比', () => {
    const w = mountProps({ slaProgress: 130, slaBreached: true, slaBarClass: 'progress-fill-error' })

    const text = w.find('.sla-value').text()
    expect(text).toBe('已超时')
    // 两条一起断：漏掉后者时，「已超时 130% 已消耗」这种拼接也能过前者
    expect(text).not.toContain('%')
    expect(w.find('.sla-value').classes()).toContain('sla-value-breached')
    expect(w.find('.progress-fill').classes()).toContain('progress-fill-error')
  })
})

// ==================== TicketTagEditor ====================

const mountTags = (over: Record<string, unknown> = {}) =>
  mount(TicketTagEditor, {
    props: {
      tags: ['k8s', '数据库'],
      hotTags: ['网络', '存储'],
      busy: false,
      draft: '',
      ...over,
    },
  })

describe('TicketTagEditor', () => {
  it('已有标签逐个渲染成 chip', () => {
    const w = mountTags()
    expect(w.findAll('.tag-chip').map((c) => c.text().replace('×', '').trim()))
      .toEqual(['k8s', '数据库'])
  })

  it('点某个 chip 的 × 抛出的是那一个标签，不是最后一个', async () => {
    // v-for 里误用外层变量时，删任何一个都会抛最后一个——
    // 用户表现是「删 A 结果 B 没了」。只断「触发了」抓不到这个
    const w = mountTags()
    await w.findAll('.tag-remove')[0].trigger('click')

    expect(w.emitted('remove')).toEqual([['k8s']])
  })

  it('输入框回车与点「添加」都抛 add', async () => {
    const w = mountTags({ draft: '新标签' })

    await w.find('.tag-input').trigger('keydown.enter')
    await w.find('.tag-add-btn').trigger('click')

    expect(w.emitted('add')).toHaveLength(2)
  })

  it('draft 为空白时「添加」按钮禁用——避免提交空标签', async () => {
    // 断 '   ' 而非 ''：产品代码用的是 trim()，
    // 只测空串的话把 .trim() 删掉照样通过
    expect(mountTags({ draft: '   ' }).find('.tag-add-btn').attributes('disabled')).toBeDefined()
    expect(mountTags({ draft: 'k8s' }).find('.tag-add-btn').attributes('disabled')).toBeUndefined()
  })

  it('busy 期间全部交互禁用——防止连点产生并发写', () => {
    const w = mountTags({ busy: true, draft: 'x' })

    expect(w.find('.tag-input').attributes('disabled')).toBeDefined()
    expect(w.find('.tag-add-btn').attributes('disabled')).toBeDefined()
    expect(w.find('.tag-remove').attributes('disabled')).toBeDefined()
    expect(w.find('.tag-suggest').attributes('disabled')).toBeDefined()
  })

  it('热门标签最多展示 5 个，且已有的那个禁用', async () => {
    const w = mountTags({
      hotTags: ['a', 'b', 'c', 'd', 'e', 'f', 'g'],
      tags: ['c'],
    })
    const chips = w.findAll('.tag-suggest')

    expect(chips).toHaveLength(5)
    expect(chips.map((c) => c.text())).toEqual(['a', 'b', 'c', 'd', 'e'])
    // 'c' 已在标签里 —— 再点一次只会得到「标签已存在」的警告，直接禁掉更诚实
    expect(chips[2].attributes('disabled')).toBeDefined()
    expect(chips[0].attributes('disabled')).toBeUndefined()

    await chips[0].trigger('click')
    expect(w.emitted('addSuggested')).toEqual([['a']])
  })

  it('没有热门标签时整块建议区不渲染，不留空白', () => {
    expect(mountTags({ hotTags: [] }).find('.tag-suggestions').exists()).toBe(false)
  })
})

// ==================== TicketAttachmentPanel ====================

const att = (id: number, name: string): TicketAttachmentMeta => ({
  id,
  ticketId: 'TKT-20260825-0001',
  originalName: name,
  sizeBytes: 1024,
  sizeText: '1.0 KB',
  createTime: '2026-08-25 10:00:00',
})

const mountAttach = (over: Record<string, unknown> = {}) =>
  mount(TicketAttachmentPanel, {
    props: {
      attachments: [att(1, 'pod-describe.log'), att(2, '火焰图.svg')],
      loading: false,
      uploading: false,
      ...over,
    },
  })

describe('TicketAttachmentPanel', () => {
  it('三种状态互斥：加载中 / 空 / 有列表', () => {
    const loadingW = mountAttach({ loading: true, attachments: [] })
    expect(loadingW.find('.attach-empty').text()).toBe('加载中…')
    expect(loadingW.find('.attach-list').exists()).toBe(false)

    const emptyW = mountAttach({ attachments: [] })
    expect(emptyW.find('.attach-empty').text()).toBe('暂无附件')

    const listW = mountAttach()
    expect(listW.find('.attach-empty').exists()).toBe(false)
    expect(listW.findAll('.attach-item')).toHaveLength(2)
  })

  it('加载中优先于空态——否则用户会以为附件真的没有', () => {
    // 两个分支顺序写反时，慢网络下会先闪一下「暂无附件」，
    // 用户可能因此以为文件丢了而重新上传
    const w = mountAttach({ loading: true, attachments: [] })
    expect(w.text()).toContain('加载中')
    expect(w.text()).not.toContain('暂无附件')
  })

  it('每条附件渲染文件名与大小，文件名带 title 供悬停看全称', () => {
    const w = mountAttach()
    const first = w.findAll('.attach-item')[0]

    expect(first.find('.attach-name').text()).toBe('pod-describe.log')
    // 名字被 ellipsis 截断时，title 是用户看到全称的唯一途径
    expect(first.find('.attach-name').attributes('title')).toBe('pod-describe.log')
    expect(first.find('.attach-size').text()).toBe('1.0 KB')
  })

  it('下载与删除各自抛出对应那一条附件', async () => {
    const w = mountAttach()
    const second = w.findAll('.attach-item')[1]

    await second.findAll('.attach-op')[0].trigger('click')
    await second.findAll('.attach-op')[1].trigger('click')

    // 断 id 而非「触发了」：删除是不可逆动作，抛错对象等于删错文件
    expect(w.emitted('download')?.[0]?.[0]).toMatchObject({ id: 2 })
    expect(w.emitted('remove')?.[0]?.[0]).toMatchObject({ id: 2 })
  })

  it('上传中按钮禁用并改文案', () => {
    const idle = mountAttach()
    expect(idle.find('.attach-upload').text()).toContain('上传附件')
    expect(idle.find('.attach-upload').attributes('disabled')).toBeUndefined()

    const busy = mountAttach({ uploading: true })
    expect(busy.find('.attach-upload').text()).toContain('上传中')
    expect(busy.find('.attach-upload').attributes('disabled')).toBeDefined()
  })

  it('选择文件后把原生 change 事件原样抛给父组件', async () => {
    const w = mountAttach()
    await w.find('input[type="file"]').trigger('change')

    // 父组件的 onAttachmentSelected 要从 event.target.files 取文件，
    // 中途包装成自定义载荷会让 composable 那套逻辑失效
    expect(w.emitted('select')).toHaveLength(1)
    expect(w.emitted('select')?.[0]?.[0]).toBeInstanceOf(Event)
  })
})

// ==================== TicketActivityLog ====================

const act = (over: Partial<TicketActivity> = {}): TicketActivity => ({
  color: 'primary',
  text: '创建工单',
  user: '李强',
  time: '2026-08-25 09:00',
  ...over,
})

describe('TicketActivityLog', () => {
  it('空数组显示占位文案，不渲染任何行', () => {
    const w = mount(TicketActivityLog, { props: { activities: [] } })

    expect(w.find('.activity-empty').text()).toBe('暂无操作记录')
    expect(w.findAll('.activity-row')).toHaveLength(0)
  })

  it('逐条渲染文本、操作人与时间', () => {
    const w = mount(TicketActivityLog, {
      props: { activities: [act(), act({ text: '转派', user: '张明', time: '2026-08-25 10:30' })] },
    })
    const rows = w.findAll('.activity-row')

    expect(rows).toHaveLength(2)
    expect(rows[0].find('.activity-text').text()).toBe('创建工单')
    expect(rows[1].find('.activity-meta').text()).toContain('张明')
    expect(rows[1].find('.activity-meta').text()).toContain('2026-08-25 10:30')
  })

  it('color 决定圆点 class——时间轴靠颜色区分动作性质', () => {
    const w = mount(TicketActivityLog, {
      props: {
        activities: [
          act({ color: 'success' }),
          act({ color: 'warning' }),
          act({ color: 'gray' }),
        ],
      },
    })

    expect(w.findAll('.activity-dot').map((d) => d.classes()))
      .toEqual([
        ['activity-dot', 'activity-dot-success'],
        ['activity-dot', 'activity-dot-warning'],
        ['activity-dot', 'activity-dot-gray'],
      ])
  })

  it('有 detail 时追加冒号与详情，无 detail 时不留下孤零零的冒号', () => {
    const withDetail = mount(TicketActivityLog, {
      props: { activities: [act({ text: '状态变更', detail: '待处理 → 处理中' })] },
    })
    expect(withDetail.find('.activity-text').text()).toBe('状态变更: 待处理 → 处理中')
    expect(withDetail.find('.activity-detail').text()).toBe('待处理 → 处理中')

    const without = mount(TicketActivityLog, { props: { activities: [act({ text: '状态变更' })] } })
    expect(without.find('.activity-text').text()).toBe('状态变更')
    expect(without.find('.activity-detail').exists()).toBe(false)
  })

  it('highlight 的详情额外加高亮 class', () => {
    const w = mount(TicketActivityLog, {
      props: {
        activities: [
          act({ detail: '普通', highlight: false }),
          act({ detail: '重要', highlight: true }),
        ],
      },
    })
    const details = w.findAll('.activity-detail')

    expect(details[0].classes()).not.toContain('activity-detail-highlight')
    expect(details[1].classes()).toContain('activity-detail-highlight')
  })

  it('同一时刻同一人的三条记录都渲染出来，不被当成重复项丢弃', () => {
    // 批量转派会在同一秒产生多条同人记录。
    //
    // 注：本例**不是**在测 key。我起初以为「key 去掉序号会导致节点复用出错」，
    // 注入验证后发现抓不到——纯静态列表下重复 key 无可观测差异（见组件注释）。
    // 它真正守的是：将来若有人在这里加「按 time+user 去重」之类的优化，
    // 三条会塌成一条，而运维会因此以为只操作过一次。
    const same = { time: '2026-08-25 11:00', user: '李强', color: 'gray' as const }
    const w = mount(TicketActivityLog, {
      props: {
        activities: [
          act({ ...same, text: '转派 A' }),
          act({ ...same, text: '转派 B' }),
          act({ ...same, text: '转派 C' }),
        ],
      },
    })

    expect(w.findAll('.activity-row')).toHaveLength(3)
    expect(w.findAll('.activity-text').map((t) => t.text()))
      .toEqual(['转派 A', '转派 B', '转派 C'])
  })
})
