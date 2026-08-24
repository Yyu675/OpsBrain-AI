/**
 * SLA 展示派生工具测试。
 *
 * 保护 6.38 契约：降级值必须可区分于真实值 ——
 * `slaRemainingMinutes` 为 null 意为「无法计算」，为 0 意为「刚好用完」，
 * 二者混同会让「未知」被渲染成「即将超时」这类确定性断言。
 * 以及 B1 首响契约：firstResponseMinutes 为 null 是「未首响」而非「0 分钟响应」。
 */
import { describe, expect, it } from 'vitest'

import {
  firstResponseDurationText,
  firstResponseLabel,
  firstResponseTagType,
  firstResponseText,
  firstResponseTitle,
  formatMinutes,
  slaRemainText,
  slaSeverity,
} from '../sla'

describe('formatMinutes', () => {
  it('小于 1 小时按分钟', () => {
    expect(formatMinutes(45)).toBe('45 分钟')
    expect(formatMinutes(59)).toBe('59 分钟')
  })

  it('整小时不带零分钟尾巴', () => {
    expect(formatMinutes(60)).toBe('1 小时')
    expect(formatMinutes(120)).toBe('2 小时')
  })

  it('小时带余数时同时给出分钟', () => {
    expect(formatMinutes(90)).toBe('1 小时 30 分钟')
  })

  it('整天不带零小时尾巴', () => {
    expect(formatMinutes(1440)).toBe('1 天')
    expect(formatMinutes(2880)).toBe('2 天')
  })

  it('天带余数时同时给出小时', () => {
    expect(formatMinutes(1500)).toBe('1 天 1 小时')
  })

  it('负数取绝对值 —— 正负号语义由调用方表达', () => {
    expect(formatMinutes(-45)).toBe('45 分钟')
    expect(formatMinutes(-1500)).toBe('1 天 1 小时')
  })

  it('0 分钟如实输出，不当作缺失值', () => {
    expect(formatMinutes(0)).toBe('0 分钟')
  })

  it('小数分钟四舍五入', () => {
    expect(formatMinutes(45.4)).toBe('45 分钟')
    expect(formatMinutes(45.6)).toBe('46 分钟')
  })
})

describe('slaRemainText', () => {
  it('剩余时间为正数时给「还剩」', () => {
    expect(slaRemainText({ slaRemainingMinutes: 200 })).toBe('还剩 3 小时 20 分钟')
  })

  it('剩余时间为负数时给「已超时」并带时长', () => {
    expect(slaRemainText({ slaRemainingMinutes: -2940 })).toBe('已超时 2 天 1 小时')
  })

  it('剩余 0 分钟视为「还剩 0 分钟」而非未知 —— 0 意为刚好用完', () => {
    expect(slaRemainText({ slaRemainingMinutes: 0 })).toBe('还剩 0 分钟')
  })

  it('null 退回百分比口径，不编造时间', () => {
    expect(slaRemainText({ slaRemainingMinutes: null, slaProgress: 65 })).toBe('已消耗 65%')
  })

  it('undefined 同样退回百分比口径', () => {
    expect(slaRemainText({ slaProgress: 30 })).toBe('已消耗 30%')
  })

  it('null 且已超时时直说「已超时」，不显示「已消耗 100%」', () => {
    expect(slaRemainText({ slaRemainingMinutes: null, slaBreached: true })).toBe('已超时')
  })

  it('null 且无进度数据时退回 0% 而非空白', () => {
    expect(slaRemainText({})).toBe('已消耗 0%')
  })
})

describe('slaSeverity', () => {
  it('已超时归为 breached（最高优先级，压过进度百分比）', () => {
    expect(slaSeverity({ slaBreached: true, slaProgress: 10 })).toBe('breached')
  })

  it('进度 ≥70% 归为 warning', () => {
    expect(slaSeverity({ slaProgress: 70 })).toBe('warning')
    expect(slaSeverity({ slaProgress: 99 })).toBe('warning')
  })

  it('进度 <70% 归为 normal', () => {
    expect(slaSeverity({ slaProgress: 69 })).toBe('normal')
    expect(slaSeverity({ slaProgress: 0 })).toBe('normal')
  })

  it('无进度数据时归为 normal，不误报预警', () => {
    expect(slaSeverity({})).toBe('normal')
  })
})

describe('firstResponseLabel', () => {
  it('四态各有中文标签', () => {
    expect(firstResponseLabel('RESPONDED')).toBe('已首响')
    expect(firstResponseLabel('BREACHED')).toBe('首响超时')
    expect(firstResponseLabel('AT_RISK')).toBe('即将超时')
    expect(firstResponseLabel('WAITING')).toBe('待首响')
  })

  it('空值默认「待首响」', () => {
    expect(firstResponseLabel(null)).toBe('待首响')
    expect(firstResponseLabel(undefined)).toBe('待首响')
    expect(firstResponseLabel('')).toBe('待首响')
  })

  it('未知状态原样返回 —— 后端新增枚举时不静默丢失', () => {
    expect(firstResponseLabel('SUPPRESSED')).toBe('SUPPRESSED')
  })
})

describe('firstResponseTagType', () => {
  it('超时红 / 即将超时橙 / 已首响绿 / 待首响灰', () => {
    expect(firstResponseTagType('BREACHED')).toBe('danger')
    expect(firstResponseTagType('AT_RISK')).toBe('warning')
    expect(firstResponseTagType('RESPONDED')).toBe('success')
    expect(firstResponseTagType('WAITING')).toBe('info')
  })

  it('空值与未知回落 info', () => {
    expect(firstResponseTagType(null)).toBe('info')
    expect(firstResponseTagType('BOGUS')).toBe('info')
  })
})

describe('firstResponseText（行内文案含 MTTA）', () => {
  it('已首响时展示响应耗时 —— 比单说「已响应」信息量更大', () => {
    expect(firstResponseText({ firstResponseState: 'RESPONDED', firstResponseMinutes: 12 }))
      .toBe('12 分钟响应')
  })

  it('已首响但耗时为 null 时退为「已响应」，不显示「0 分钟响应」', () => {
    expect(firstResponseText({ firstResponseState: 'RESPONDED', firstResponseMinutes: null }))
      .toBe('已响应')
  })

  it('已首响且耗时为 0 时如实显示 0 分钟 —— 秒级响应是真实情况', () => {
    expect(firstResponseText({ firstResponseState: 'RESPONDED', firstResponseMinutes: 0 }))
      .toBe('0 分钟响应')
  })

  it('未首响时走状态标签', () => {
    expect(firstResponseText({ firstResponseState: 'BREACHED' })).toBe('首响超时')
    expect(firstResponseText({ firstResponseState: 'WAITING' })).toBe('待首响')
  })

  it('长耗时用天/小时表述而非累积分钟', () => {
    expect(firstResponseText({ firstResponseState: 'RESPONDED', firstResponseMinutes: 1500 }))
      .toBe('1 天 1 小时响应')
  })
})

describe('firstResponseTitle（tooltip）', () => {
  it('已首响且有首响人时给出姓名', () => {
    expect(firstResponseTitle({ firstResponseState: 'RESPONDED', firstResponder: '王芳' }))
      .toBe('首响人：王芳')
  })

  it('已首响但无首响人时给通用文案', () => {
    expect(firstResponseTitle({ firstResponseState: 'RESPONDED', firstResponder: null }))
      .toBe('已首响')
  })

  it('未首响且剩余时间为正数时给出距截止时长', () => {
    expect(firstResponseTitle({ firstResponseState: 'WAITING', responseRemainingMinutes: 20 }))
      .toBe('距首响截止还剩 20 分钟')
  })

  it('未首响且已超时时给出超时时长', () => {
    expect(firstResponseTitle({ firstResponseState: 'BREACHED', responseRemainingMinutes: -90 }))
      .toBe('首响已超时 1 小时 30 分钟')
  })

  it('剩余时间未知时给「待首响」，不编造时长', () => {
    expect(firstResponseTitle({ firstResponseState: 'WAITING', responseRemainingMinutes: null }))
      .toBe('待首响')
  })
})

describe('firstResponseDurationText', () => {
  it('有耗时时格式化', () => {
    expect(firstResponseDurationText(90)).toBe('1 小时 30 分钟')
  })

  it('null 给占位符 —— 未首响不是「0 分钟」，否则 MTTA 展示会失真', () => {
    expect(firstResponseDurationText(null)).toBe('—')
    expect(firstResponseDurationText(undefined)).toBe('—')
  })

  it('0 分钟如实显示，与 null 区分', () => {
    expect(firstResponseDurationText(0)).toBe('0 分钟')
  })
})
