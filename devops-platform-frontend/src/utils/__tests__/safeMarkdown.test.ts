/**
 * Markdown 安全渲染测试。
 *
 * safeMarkdown 是全项目唯一的 v-html 内容来源（6.32 契约：
 * 结构化段与降级全量渲染都必须经此，不得为任一路径另开 unsanitized 通道）。
 * 这里的用例保护的是 XSS 白名单边界本身——一旦某个危险标签被放行，
 * AI 回答里的恶意内容就会在运维浏览器中执行。
 */
import { beforeEach, describe, expect, it } from 'vitest'

import { clearMarkdownCache, safeMarkdown } from '../safeMarkdown'

beforeEach(() => {
  clearMarkdownCache()
})

describe('Markdown 渲染', () => {
  it('渲染标题', () => {
    expect(safeMarkdown('## 可能原因')).toContain('<h2')
  })

  it('渲染有序与无序列表', () => {
    expect(safeMarkdown('1. 第一条\n2. 第二条')).toContain('<ol')
    expect(safeMarkdown('- 第一条\n- 第二条')).toContain('<ul')
  })

  it('渲染围栏代码块', () => {
    const html = safeMarkdown('```bash\nkubectl get pods\n```')
    expect(html).toContain('<pre')
    expect(html).toContain('kubectl get pods')
  })

  it('渲染行内代码', () => {
    expect(safeMarkdown('检查 `max_connections`')).toContain('<code')
  })

  it('渲染表格（gfm）', () => {
    const html = safeMarkdown('| 列A | 列B |\n| --- | --- |\n| 1 | 2 |')
    expect(html).toContain('<table')
    expect(html).toContain('<td')
  })

  it('单换行转 <br>（breaks: true）—— 运维日志常靠换行分隔', () => {
    expect(safeMarkdown('第一行\n第二行')).toContain('<br')
  })

  it('空内容返回空串，不产出 undefined 或 "null"', () => {
    expect(safeMarkdown('')).toBe('')
  })
})

describe('XSS 净化', () => {
  it('剥离 <script> 标签', () => {
    const html = safeMarkdown('正常文本<script>alert(1)</script>')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('alert(1)')
  })

  it('剥离 onerror 等事件处理器属性', () => {
    const html = safeMarkdown('<img src="x" onerror="alert(1)">')
    expect(html).not.toContain('onerror')
  })

  it('剥离 <iframe>', () => {
    expect(safeMarkdown('<iframe src="//evil.test"></iframe>')).not.toContain('<iframe')
  })

  it('剥离 <object> 与 <embed>', () => {
    expect(safeMarkdown('<object data="x"></object>')).not.toContain('<object')
    expect(safeMarkdown('<embed src="x">')).not.toContain('<embed')
  })

  it('剥离 <style>——可用于 UI 欺骗与数据外泄', () => {
    expect(safeMarkdown('<style>body{display:none}</style>')).not.toContain('<style')
  })

  it('剥离 <form> 与 <input>——防钓鱼表单', () => {
    const html = safeMarkdown('<form action="//evil.test"><input name="pwd"></form>')
    expect(html).not.toContain('<form')
    expect(html).not.toContain('<input')
  })

  it('剥离 javascript: 协议链接', () => {
    const html = safeMarkdown('[点我](javascript:alert(1))')
    expect(html).not.toContain('javascript:')
  })

  it('剥离 onclick 事件属性', () => {
    expect(safeMarkdown('<div onclick="alert(1)">x</div>')).not.toContain('onclick')
  })

  it('剥离 srcdoc 属性', () => {
    expect(safeMarkdown('<iframe srcdoc="<script>alert(1)</script>">')).not.toContain('srcdoc')
  })

  it('剥离不在白名单的 style 属性', () => {
    const html = safeMarkdown('<span style="position:fixed;top:0">x</span>')
    expect(html).not.toContain('style=')
  })
})

describe('链接安全（tabnabbing 防护）', () => {
  it('target=_blank 的链接被强制补 rel="noopener noreferrer"', () => {
    const html = safeMarkdown('<a href="https://example.test" target="_blank">链接</a>')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('普通链接保留 href', () => {
    expect(safeMarkdown('[文档](https://example.test)')).toContain('href="https://example.test"')
  })
})

describe('缓存行为', () => {
  it('同内容重复渲染结果一致', () => {
    const raw = '## 标题\n正文'
    expect(safeMarkdown(raw)).toBe(safeMarkdown(raw))
  })

  it('同 cacheKey 且内容相同时复用结果 —— 流式渲染避免每个 token 全量重解析', () => {
    const first = safeMarkdown('内容 A', 'msg-1')
    const second = safeMarkdown('内容 A', 'msg-1')
    expect(second).toBe(first)
  })

  it('同 cacheKey 但内容不同时必须重新渲染，不得返回旧结果', () => {
    // 本用例的前身断言的是「同 key 就复用，哪怕内容不同」——
    // 那锁住的是缺陷而非契约：cacheKey 曾是 `id + 内容长度`，
    // 运维文档把「延迟 30 秒」改成「90 秒」长度不变，用户会看到旧值。
    // 现 safeMarkdown 内部附加内容指纹，内容变则缓存自然失效。
    const first = safeMarkdown('内容 A', 'msg-1')
    const second = safeMarkdown('内容 B（不同内容，同 key）', 'msg-1')
    expect(second).not.toBe(first)
    expect(second).toContain('内容 B')
  })

  it('不同 cacheKey 各自渲染', () => {
    const a = safeMarkdown('内容 A', 'msg-1:5')
    const b = safeMarkdown('内容 B', 'msg-1:6')
    expect(b).not.toBe(a)
    expect(b).toContain('内容 B')
  })

  it('clearMarkdownCache 后同 key 重新渲染当前内容', () => {
    safeMarkdown('旧内容', 'k')
    clearMarkdownCache()
    expect(safeMarkdown('新内容', 'k')).toContain('新内容')
  })

  it('超出缓存上限后仍能正常渲染（淘汰不影响正确性）', () => {
    for (let i = 0; i < 250; i++) safeMarkdown(`内容 ${i}`, `key-${i}`)
    expect(safeMarkdown('最后一条', 'key-last')).toContain('最后一条')
  })
})

describe('畸形输入降级', () => {
  it('未闭合标签不抛错', () => {
    expect(() => safeMarkdown('<div><span>未闭合')).not.toThrow()
  })

  it('未闭合代码围栏不抛错（流式中途的常见形态）', () => {
    expect(() => safeMarkdown('```bash\nkubectl get pods')).not.toThrow()
  })

  it('深层嵌套不抛错', () => {
    expect(() => safeMarkdown('> '.repeat(200) + '文本')).not.toThrow()
  })

  it('纯文本原样保留可见内容', () => {
    expect(safeMarkdown('简单一句话')).toContain('简单一句话')
  })
})

describe('缓存键（内容指纹）', () => {
  it('同 cacheKey 但内容不同时不得复用旧结果', () => {
    // 真实场景：运维文档把「延迟 30 秒」改成「90 秒」，长度不变。
    // 修复前 key 是 `id-{length}`，两者撞 key，用户看到的仍是旧值——
    // 而这种数字在运维手册里是要照着执行的。
    const before = safeMarkdown('主从延迟 30 秒', 'doc-42')
    const after = safeMarkdown('主从延迟 90 秒', 'doc-42')

    expect(before).toContain('30')
    expect(after).toContain('90')
    expect(after).not.toContain('30')
  })

  it('同 cacheKey 且内容相同时复用缓存（指纹一致）', () => {
    const a = safeMarkdown('# 同样的内容', 'doc-7')
    const b = safeMarkdown('# 同样的内容', 'doc-7')

    expect(a).toBe(b)
  })

  it('不传 cacheKey 时按内容本身缓存，仍然正确', () => {
    expect(safeMarkdown('**A**')).toContain('<strong>A</strong>')
    expect(safeMarkdown('**B**')).toContain('<strong>B</strong>')
  })
})
