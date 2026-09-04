/**
 * 时间格式化测试。
 *
 * relativeTime 接受可注入的 now 参数，因此无需 mock 系统时钟——
 * 测试用固定基准点，不依赖执行时刻（new-api AGENTS.md §3.14：
 * 异步/时间相关测试不得依赖执行耗时或制造竞态）。
 */
import { describe, expect, it } from 'vitest'

import { formatAbsolute, formatDate, nowAsBackendTime, parseDate, relativeTime } from '../time'

/** 固定基准：2026-08-23 12:00:00 本地时间 */
const NOW = new Date(2026, 7, 23, 12, 0, 0).getTime()

/** 相对基准偏移若干毫秒的时刻 */
const ago = (ms: number) => new Date(NOW - ms)
const later = (ms: number) => new Date(NOW + ms)

const SECOND = 1000
const MINUTE = 60 * SECOND
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

describe('parseDate', () => {
  it('Date 实例原样返回', () => {
    const d = new Date(2026, 0, 1)
    expect(parseDate(d)).toBe(d)
  })

  it('ISO 字符串被解析', () => {
    expect(parseDate('2026-08-23T10:30:00')).toBeInstanceOf(Date)
  })

  it('时间戳数字被解析', () => {
    expect(parseDate(NOW)?.getTime()).toBe(NOW)
  })

  it('空值返回 null 而非 Invalid Date', () => {
    expect(parseDate(null)).toBeNull()
    expect(parseDate(undefined)).toBeNull()
    expect(parseDate('')).toBeNull()
  })

  it('非法日期字符串返回 null —— 不让 Invalid Date 流入渲染层', () => {
    expect(parseDate('不是日期')).toBeNull()
  })

  it('Invalid Date 实例也返回 null', () => {
    expect(parseDate(new Date('bogus'))).toBeNull()
  })
})

describe('relativeTime — 过去时刻', () => {
  it('30 秒内显示「刚刚」', () => {
    expect(relativeTime(ago(5 * SECOND), NOW)).toBe('刚刚')
    expect(relativeTime(ago(29 * SECOND), NOW)).toBe('刚刚')
  })

  it('30 秒到 1 分钟按秒显示', () => {
    expect(relativeTime(ago(45 * SECOND), NOW)).toBe('45 秒前')
  })

  it('分钟级', () => {
    expect(relativeTime(ago(5 * MINUTE), NOW)).toBe('5 分钟前')
    expect(relativeTime(ago(59 * MINUTE), NOW)).toBe('59 分钟前')
  })

  it('小时级', () => {
    expect(relativeTime(ago(3 * HOUR), NOW)).toBe('3 小时前')
    expect(relativeTime(ago(23 * HOUR), NOW)).toBe('23 小时前')
  })

  it('天级', () => {
    expect(relativeTime(ago(2 * DAY), NOW)).toBe('2 天前')
    expect(relativeTime(ago(6 * DAY), NOW)).toBe('6 天前')
  })

  it('周级', () => {
    expect(relativeTime(ago(10 * DAY), NOW)).toBe('1 周前')
    expect(relativeTime(ago(21 * DAY), NOW)).toBe('3 周前')
  })

  it('月级', () => {
    expect(relativeTime(ago(45 * DAY), NOW)).toBe('1 个月前')
  })

  it('年级', () => {
    expect(relativeTime(ago(400 * DAY), NOW)).toBe('1 年前')
  })

  it('阈值边界向下取整，不出现「60 分钟前」这类越界表述', () => {
    expect(relativeTime(ago(HOUR - SECOND), NOW)).toBe('59 分钟前')
    expect(relativeTime(ago(DAY - SECOND), NOW)).toBe('23 小时前')
  })
})

describe('relativeTime — 未来时刻', () => {
  it('未来时刻用「后」而非「前」—— SLA 截止时间等场景需要', () => {
    expect(relativeTime(later(5 * MINUTE), NOW)).toBe('5 分钟后')
    expect(relativeTime(later(3 * HOUR), NOW)).toBe('3 小时后')
    expect(relativeTime(later(2 * DAY), NOW)).toBe('2 天后')
  })

  it('未来 30 秒内同样显示「刚刚」', () => {
    expect(relativeTime(later(10 * SECOND), NOW)).toBe('刚刚')
  })
})

describe('relativeTime — 空值', () => {
  it('空值给占位符而非「NaN 分钟前」', () => {
    expect(relativeTime(null, NOW)).toBe('—')
    expect(relativeTime(undefined, NOW)).toBe('—')
    expect(relativeTime('', NOW)).toBe('—')
    expect(relativeTime('不是日期', NOW)).toBe('—')
  })
})

describe('formatAbsolute', () => {
  it('输出 yyyy-MM-dd HH:mm，月日时分补零', () => {
    expect(formatAbsolute(new Date(2026, 0, 5, 9, 7))).toBe('2026-01-05 09:07')
  })

  it('两位数月日时分不额外补零', () => {
    expect(formatAbsolute(new Date(2026, 10, 23, 14, 30))).toBe('2026-11-23 14:30')
  })

  it('空值给占位符', () => {
    expect(formatAbsolute(null)).toBe('—')
    expect(formatAbsolute('bogus')).toBe('—')
  })
})

describe('formatDate', () => {
  it('只输出 yyyy-MM-dd', () => {
    expect(formatDate(new Date(2026, 7, 23, 14, 30))).toBe('2026-08-23')
  })

  it('月日补零', () => {
    expect(formatDate(new Date(2026, 0, 5))).toBe('2026-01-05')
  })

  it('空值给占位符', () => {
    expect(formatDate(null)).toBe('—')
  })
})

describe('时区处理（后端 LocalDateTime 无时区后缀）', () => {
  it('无时区字符串按服务器时区(+08:00)解析，而非浏览器本地时区', () => {
    // 后端 Java LocalDateTime 序列化就是这个形态
    const d = parseDate('2026-08-24T10:30:00')

    // 不论测试机在哪个时区，都应等于北京时间 10:30
    expect(d?.getTime()).toBe(Date.parse('2026-08-24T10:30:00+08:00'))
  })

  it('空格分隔的时间同样按服务器时区解析', () => {
    expect(parseDate('2026-08-24 10:30:00')?.getTime())
      .toBe(Date.parse('2026-08-24T10:30:00+08:00'))
  })

  it('已带时区的输入不被改写', () => {
    expect(parseDate('2026-08-24T10:30:00Z')?.getTime())
      .toBe(Date.parse('2026-08-24T10:30:00Z'))
    expect(parseDate('2026-08-24T10:30:00+09:00')?.getTime())
      .toBe(Date.parse('2026-08-24T10:30:00+09:00'))
  })

  it('相对时间不再因时区错算 —— 修复前跨时区可差 12 小时', () => {
    // 服务器时间 11:30 时，看 10:30 创建的工单
    const now = Date.parse('2026-08-24T11:30:00+08:00')

    expect(relativeTime('2026-08-24T10:30:00', now)).toBe('1 小时前')
  })

  it('毫秒数与 Date 对象输入不受影响', () => {
    const ms = Date.parse('2026-08-24T10:30:00Z')
    expect(parseDate(ms)?.getTime()).toBe(ms)
    expect(parseDate(new Date(ms))?.getTime()).toBe(ms)
  })
})

describe('nowAsBackendTime — 乐观更新的时间戳格式', () => {
  it('格式与后端字段一致（YYYY-MM-DD HH:mm，无时区后缀）', () => {
    expect(nowAsBackendTime()).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
  })

  /**
   * 这是本函数存在的唯一理由，也是它替换掉的那行代码的缺陷所在。
   *
   * 旧写法 `new Date().toISOString().slice(0,16).replace('T',' ')` 产出 UTC，
   * 而该字段的约定是「服务器本地时间（+08:00）不带后缀」，parseDate 按 +08:00 读。
   * 写 UTC、读 +08:00 → 差 8 小时。实测：北京时间 10:30 编辑工单，
   * 列表「更新时间」立刻显示「8 小时前」。
   */
  it('产出的时间回读后接近当前时刻，不出现 8 小时漂移', () => {
    const before = Date.now()
    const parsed = parseDate(nowAsBackendTime())
    expect(parsed).not.toBeNull()

    // 允许 1 分钟误差（函数截断到分钟）
    const drift = Math.abs(parsed!.getTime() - before)
    expect(drift).toBeLessThan(60_000)
  })

  it('对比：旧的 toISOString 写法确实有 8 小时偏差 —— 锁住不要改回去', () => {
    const legacy = new Date().toISOString().slice(0, 16).replace('T', ' ')
    const legacyParsed = parseDate(legacy)!
    const correctParsed = parseDate(nowAsBackendTime())!

    // 服务器时区固定 +08:00，两种写法必然相差 8 小时
    const diffHours = Math.round((correctParsed.getTime() - legacyParsed.getTime()) / 3600_000)
    expect(diffHours).toBe(8)
  })

  /**
   * 不断言「刚刚」——本函数截断到分钟，产出的时刻最多可能比"现在"早 59 秒，
   * 而 relativeTime 的「刚刚」阈值是 30 秒。断言字面量会让用例在
   * 每分钟的后半段随机失败（我第一版就这么写，实测三次跑挂两次）。
   *
   * 真正要保证的是「不出现 8 小时漂移」：只要落在 1 分钟内的相对描述里即可。
   */
  it('相对时间不出现小时级漂移', () => {
    const label = relativeTime(nowAsBackendTime())
    expect(label === '刚刚' || /^\d+ 秒前$/.test(label)).toBe(true)
  })
})
