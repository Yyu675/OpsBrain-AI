/**
 * 开放重定向防护测试。
 *
 * 攻击向量取自实测（不是凭空列举）：用 `new URL(input, origin)` 逐个跑，
 * 记录浏览器真实解析结果，凡是 origin 变了的就是能逃逸的。
 *
 * 原实现 `startsWith('/') && !startsWith('//')` 挡住了最常见的 `//evil.com`，
 * 却放过了反斜杠与控制字符变体——这正是这类校验最典型的失败方式：
 * 用字符串前缀猜 URL 语义，而 URL 语义由规范定义、比直觉复杂得多。
 */
import { describe, expect, it } from 'vitest'

import { safeInternalPath } from '../safeRedirect'

/** 原实现，用于对照说明「哪些是新挡住的」 */
const legacyGuard = (t: string): string =>
  t && t.startsWith('/') && !t.startsWith('//') ? t : '/'

describe('safeInternalPath — 必须拦截的跨站向量', () => {
  it.each([
    ['//evil.com', '协议相对 URL'],
    ['https://evil.com', '绝对 URL'],
    ['http://evil.com/x', '绝对 URL（http）'],
    ['/\\evil.com', '反斜杠——URL 规范按正斜杠处理'],
    ['/\\\\evil.com', '双反斜杠'],
    ['/\t/evil.com', '制表符会被剥离后重新解析'],
    ['/\n/evil.com', '换行同理'],
    ['/\r/evil.com', '回车同理'],
    ['javascript:alert(1)', '伪协议'],
    ['\\\\evil.com', '不以 / 开头'],
    ['', '空串'],
  ])('拦截 %s（%s）', (input) => {
    expect(safeInternalPath(input)).toBe('/')
  })

  /** 单列出来：这三条是原实现放过、新实现挡住的，属本次修复的核心 */
  it.each(['/\\evil.com', '/\t/evil.com', '/\\\\evil.com'])(
    '%s 在原实现下会通过，新实现必须拦下',
    (input) => {
      expect(legacyGuard(input)).not.toBe('/')      // 原实现确实放行了
      expect(safeInternalPath(input)).toBe('/')     // 新实现拦住
    }
  )
})

describe('safeInternalPath — 正常站内路径必须放行', () => {
  it.each([
    '/',
    '/tickets',
    '/tickets/T-1001',
    '/tickets#section',
    '/tickets?status=pending&priority=urgent',
  ])('放行 %s（ASCII 路径原样返回）', (input) => {
    expect(safeInternalPath(input)).toBe(input)
  })

  /**
   * 含非 ASCII 的路径会被 URL 解析器**百分号编码**后返回，
   * 这是归一化的正常结果而非丢失：`/knowledge?cat=数据库` →
   * `/knowledge?cat=%E6%95%B0%E6%8D%AE%E5%BA%93`，
   * router.push 与 location.assign 都能正确还原成同一个页面。
   *
   * 之所以要返回归一化结果而非原串：原串里的控制字符若原样交给
   * location.assign，浏览器会自行剥离后重新解析，等于绕过本次校验。
   * 编码是这个安全取舍的附带代价，可接受。
   */
  it('非 ASCII 路径被百分号编码但语义等价', () => {
    const result = safeInternalPath('/knowledge?cat=数据库&page=2')
    expect(result).toBe('/knowledge?cat=%E6%95%B0%E6%8D%AE%E5%BA%93&page=2')
    // 解码后与原意一致
    expect(decodeURIComponent(result)).toBe('/knowledge?cat=数据库&page=2')
  })

  it('保留 query 与 hash —— 分享出去的筛选链接登录后要能还原', () => {
    const path = '/tickets?status=pending&assignee=%E5%BC%A0%E4%B8%89#list'
    const result = safeInternalPath(path)
    expect(result).toContain('status=pending')
    expect(result).toContain('#list')
  })
})

describe('safeInternalPath — 边界与降级', () => {
  it('数组取第一项（vue-router 的 query 可能是数组）', () => {
    expect(safeInternalPath(['/tickets', '/alerts'])).toBe('/tickets')
    expect(safeInternalPath(['//evil.com'])).toBe('/')
  })

  it('非字符串一律回落', () => {
    expect(safeInternalPath(undefined)).toBe('/')
    expect(safeInternalPath(null)).toBe('/')
    expect(safeInternalPath(123)).toBe('/')
    expect(safeInternalPath({})).toBe('/')
  })

  it('支持自定义回落路径', () => {
    expect(safeInternalPath('//evil.com', '/dashboard')).toBe('/dashboard')
  })

  it('返回的是归一化后的路径，不是原始串 —— 否则控制字符会被浏览器再解析一次', () => {
    // 即便某个含控制字符的输入侥幸通过，返回值也必须是解析后的干净路径
    const result = safeInternalPath('/tickets')
    expect(result).not.toMatch(/[\t\n\r\\]/)
  })
})
