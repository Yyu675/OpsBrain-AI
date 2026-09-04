import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TicketTimeline from '@/components/ticket/TicketTimeline.vue'

const analysis = {
  content: '', streaming: false, done: false,
  structured: { reasons: [], commands: [], other: '', structured: false, confidence: null },
  useStructuredRender: false, confidenceClass: '', citations: [], cost: 0,
  fromArchive: false, archivedAt: undefined, feedback: null, id: null,
  onFeedback: () => {}, renderMarkdown: (s: string) => s,
  onCopyCommand: () => {}, onCopyAnalysis: () => {}, onRegenerate: () => {}, onStop: () => {},
} as never

const ticket = { id: 'T1', sla: '4h', slaBreached: false, slaProgress: 10 } as never

describe('TicketTimeline 冒烟', () => {
  it('渲染回复气泡，null author 不崩溃', () => {
    const w = mount(TicketTimeline, {
      props: {
        ticket,
        visibleReplies: [
          { role: 'creator', author: null, time: '10:00', content: '用户描述' },
          { role: 'agent', author: '张明', time: '10:05', content: '已处理' },
        ] as never,
        analysis, showSlaAlert: false,
      },
      global: { stubs: { AnalysisCard: true } },
    })
    const html = w.html()
    expect(html).toContain('用户描述')
    expect(html).toContain('已处理')
    expect(html).toContain('?')      // null author 的首字母占位
    expect(html).toContain('张')
  })

  it('showSlaAlert 控制 SLA 节点显隐', () => {
    const base = { ticket, visibleReplies: [] as never, analysis }
    expect(mount(TicketTimeline, { props: { ...base, showSlaAlert: false }, global: { stubs: { AnalysisCard: true } } }).html())
      .not.toContain('SLA 预警')
    expect(mount(TicketTimeline, { props: { ...base, showSlaAlert: true }, global: { stubs: { AnalysisCard: true } } }).html())
      .toContain('SLA 预警')
  })
})
