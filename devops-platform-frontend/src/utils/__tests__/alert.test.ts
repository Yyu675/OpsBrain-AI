/**
 * 告警级别 / 状态展示映射测试。
 *
 * 这是「告警列表页 / 告警流模式 / 通知中心」三处共用的展示单一来源（6.35 契约：
 * 级别语义由后端下发，颜色归类是前端职责）。映射出错会让 P0 生产故障
 * 显示成低危蓝色 —— 运维据此判断优先级，属可致误判的缺陷。
 */
import { describe, expect, it } from 'vitest'

import {
  ALERT_LEVEL_OPTIONS,
  ALERT_STATUS_LABELS,
  ALERT_STATUS_OPTIONS,
  getAlertStatusLabel,
  levelGroup,
  levelTagType,
  statusTagType,
} from '../alert'

describe('levelGroup', () => {
  it('P0/P1 归为 high —— 生产故障与严重告警须用最高视觉权重', () => {
    expect(levelGroup('P0')).toBe('high')
    expect(levelGroup('P1')).toBe('high')
  })

  it('P2/P3 归为 medium', () => {
    expect(levelGroup('P2')).toBe('medium')
    expect(levelGroup('P3')).toBe('medium')
  })

  it('P4 归为 low', () => {
    expect(levelGroup('P4')).toBe('low')
  })

  it('小写输入同样命中 —— 不因大小写失配把 P0 降级显示为低危', () => {
    expect(levelGroup('p0')).toBe('high')
    expect(levelGroup('p2')).toBe('medium')
  })

  it('空值归为 low 而非抛错', () => {
    expect(levelGroup(null)).toBe('low')
    expect(levelGroup(undefined)).toBe('low')
    expect(levelGroup('')).toBe('low')
  })

  it('未知级别归为 low', () => {
    expect(levelGroup('CRITICAL')).toBe('low')
  })
})

describe('levelTagType', () => {
  it('high → danger、medium → warning、low → info', () => {
    expect(levelTagType('P0')).toBe('danger')
    expect(levelTagType('P2')).toBe('warning')
    expect(levelTagType('P4')).toBe('info')
  })

  it('与 levelGroup 结论一致 —— 二者不得漂移', () => {
    const expected = { high: 'danger', medium: 'warning', low: 'info' } as const
    for (const level of ['P0', 'P1', 'P2', 'P3', 'P4', 'unknown', '']) {
      expect(levelTagType(level)).toBe(expected[levelGroup(level)])
    }
  })
})

describe('状态标签', () => {
  it('三态各有中文标签 —— 面向用户不暴露英文枚举', () => {
    expect(ALERT_STATUS_LABELS.FIRING).toBe('触发中')
    expect(ALERT_STATUS_LABELS.ACKNOWLEDGED).toBe('已确认')
    expect(ALERT_STATUS_LABELS.RESOLVED).toBe('已恢复')
  })

  it('getAlertStatusLabel 空值给占位符而非空白', () => {
    expect(getAlertStatusLabel(null)).toBe('—')
    expect(getAlertStatusLabel('')).toBe('—')
  })

  it('未知状态原样返回 —— 后端新增枚举时不静默丢失信息', () => {
    expect(getAlertStatusLabel('SUPPRESSED')).toBe('SUPPRESSED')
  })
})

describe('statusTagType', () => {
  it('触发中红 / 已确认橙 / 已恢复绿', () => {
    expect(statusTagType('FIRING')).toBe('danger')
    expect(statusTagType('ACKNOWLEDGED')).toBe('warning')
    expect(statusTagType('RESOLVED')).toBe('success')
  })

  it('未知与空值回落 info', () => {
    expect(statusTagType('SUPPRESSED')).toBe('info')
    expect(statusTagType(null)).toBe('info')
  })
})

describe('筛选下拉选项与标签表一致性', () => {
  it('状态下拉的每个具体值都在标签表中有对应中文', () => {
    for (const opt of ALERT_STATUS_OPTIONS) {
      if (opt.value === '') continue
      expect(ALERT_STATUS_LABELS[opt.value]).toBe(opt.label)
    }
  })

  it('状态下拉首项是「全部状态」空值 —— 供清除筛选', () => {
    expect(ALERT_STATUS_OPTIONS[0]).toEqual({ value: '', label: '全部状态' })
  })

  it('级别下拉覆盖 P0~P4 全部五档', () => {
    const values = ALERT_LEVEL_OPTIONS.filter(o => o.value !== '').map(o => o.value)
    expect(values).toEqual(['P0', 'P1', 'P2', 'P3', 'P4'])
  })

  it('级别下拉每项都能被 levelGroup 正确归类，无落到 low 的意外项', () => {
    const grouped = ALERT_LEVEL_OPTIONS
      .filter(o => o.value !== '')
      .map(o => levelGroup(o.value))
    expect(grouped).toEqual(['high', 'high', 'medium', 'medium', 'low'])
  })
})
