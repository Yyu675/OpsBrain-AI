import { describe, expect, it } from 'vitest'

import {
  appendHeading,
  appendMarkdownBlock,
  extractToc,
  hasMeaningfulContent,
  hasTag,
  HTML_BLOCKS,
  isHtmlContent,
  MARKDOWN_BLOCKS,
  MAX_TAGS,
  normalizeDraftState,
  normalizeTagList,
  removeHeadingIn,
  renameHeadingIn,
  toPlainText,
  toVisualContent,
  type BlockCommand,
  type EditorFormLike,
} from '../editorContent'

/**
 * 知识文档编辑器内容处理测试。
 *
 * 这些函数从 `KnowledgeEditor.vue`（2013 行）抽出，是拆分该文件的第一步——
 * 先让核心逻辑有测试托底，后续动组件结构时才有安全网。
 * （第 22 轮拆 TicketDetail 的教训：正是 74 例测试抓出了我自己引入的破坏。）
 *
 * 覆盖重点是两类「用户能直接感知、但没有任何报错」的问题：
 *   - 判空写错 → 写了一屏表格却被告知「请先输入文档内容」
 *   - 净化过头 → 粘贴来的表格/代码块变成一坨纯文本
 */
describe('editorContent', () => {
  describe('isHtmlContent', () => {
    it('识别 HTML 与 Markdown', () => {
      expect(isHtmlContent('<p>正文</p>')).toBe(true)
      expect(isHtmlContent('  \n  <div>缩进后仍是 HTML</div>')).toBe(true)
      expect(isHtmlContent('## Markdown 标题')).toBe(false)
      expect(isHtmlContent('纯文本')).toBe(false)
    })

    it('空串按 Markdown 处理，不误判为 HTML', () => {
      expect(isHtmlContent('')).toBe(false)
      expect(isHtmlContent('   ')).toBe(false)
    })

    it('正文中间出现的 < 不影响判断', () => {
      // 「响应时间 < 100ms」这种写法很常见，
      // 若因为含 < 就当 HTML 处理，Markdown 标题会全部渲染不出来
      expect(isHtmlContent('响应时间 < 100ms 属正常')).toBe(false)
    })
  })

  describe('hasMeaningfulContent —— 判空写错就是拦住用户提交', () => {
    it('纯文本有内容', () => {
      expect(hasMeaningfulContent('排查步骤')).toBe(true)
      expect(hasMeaningfulContent('')).toBe(false)
      expect(hasMeaningfulContent('   \n  ')).toBe(false)
    })

    it('富文本编辑器的「空白」痕迹不算内容', () => {
      // Quill 等编辑器在看起来空白时仍留下这些，
      // 直接 trim() 判空会让用户什么都没写却能提交空文档
      expect(hasMeaningfulContent('<p><br></p>')).toBe(false)
      expect(hasMeaningfulContent('<p></p>')).toBe(false)
      expect(hasMeaningfulContent('<div>\u200B</div>')).toBe(false)
    })

    it('有文字的 HTML 算有内容', () => {
      expect(hasMeaningfulContent('<p>连接池耗尽</p>')).toBe(true)
    })

    it('纯表格 / 纯图片 / 纯代码块 / 纯分隔线都算有内容', () => {
      // 这是最容易漏的一组：一篇全是架构图或全是配置表的文档没有文本，
      // 只看 textContent 会把它判成空——用户写了一屏却被告知「请先输入内容」
      expect(hasMeaningfulContent('<table><tr><td></td></tr></table>')).toBe(true)
      expect(hasMeaningfulContent('<p><img src="/arch.png" alt=""></p>')).toBe(true)
      expect(hasMeaningfulContent('<pre><code></code></pre>')).toBe(true)
      expect(hasMeaningfulContent('<hr>')).toBe(true)
    })

    it('只有空 p 包着的空 span 不算内容', () => {
      expect(hasMeaningfulContent('<p><span></span></p>')).toBe(false)
    })
  })

  describe('toVisualContent', () => {
    it('空内容返回编辑器可接受的空段落，而不是空串', () => {
      // 空串会让富文本编辑器初始化出异常光标位置
      expect(await0(toVisualContent(''))).resolves.toBe('<p><br></p>')
      expect(await0(toVisualContent('   '))).resolves.toBe('<p><br></p>')
    })

    it('Markdown 被渲染成 HTML', async () => {
      const html = await toVisualContent('## 标题\n\n正文段落')
      expect(html).toContain('<h2')
      expect(html).toContain('正文段落')
    })

    it('已是 HTML 的内容保持结构', async () => {
      const html = await toVisualContent('<h2>标题</h2><p>正文</p>')
      expect(html).toContain('<h2>标题</h2>')
      expect(html).toContain('<p>正文</p>')
    })

    it('保留表格与代码块 —— 知识库文档的主力结构', async () => {
      const table = '<table><thead><tr><th>项</th></tr></thead>'
        + '<tbody><tr><td>值</td></tr></tbody></table>'
      const html = await toVisualContent(table)

      // 白名单漏一个标签，粘贴进来的表格就塌成一行纯文本
      expect(html).toContain('<table>')
      expect(html).toContain('<thead>')
      expect(html).toContain('<th>')
      expect(html).toContain('<td>')
    })

    it('保留代码块的语言标记（data-language）', async () => {
      const html = await toVisualContent('<pre data-language="bash"><code>kubectl get pods</code></pre>')
      expect(html).toContain('data-language="bash"')
      expect(html).toContain('kubectl get pods')
    })

    it('剥掉 script —— 编辑者往往是管理员，一次 XSS 拿到的是最高权限会话', async () => {
      const html = await toVisualContent('<p>正文</p><script>alert(1)</script>')

      expect(html).not.toContain('<script')
      expect(html).not.toContain('alert(1)')
      // 但正常内容要留下，不能因为有脚本就把整篇清空
      expect(html).toContain('正文')
    })

    it('剥掉内联事件处理器与 javascript: 链接', async () => {
      const html = await toVisualContent(
        '<p onclick="steal()">点我</p><a href="javascript:evil()">链接</a>')

      expect(html).not.toContain('onclick')
      expect(html).not.toContain('javascript:')
      expect(html).toContain('点我')
    })

    it('剥掉 iframe / object 这类可嵌入外部内容的标签', async () => {
      const html = await toVisualContent('<iframe src="//evil.example"></iframe><p>正文</p>')

      expect(html).not.toContain('<iframe')
      expect(html).toContain('正文')
    })

    it('保留图片与链接的必要属性', async () => {
      const html = await toVisualContent(
        '<p><img src="/a.png" alt="架构图" title="t"><a href="/doc/1" target="_blank" rel="noopener">链接</a></p>')

      expect(html).toContain('src="/a.png"')
      expect(html).toContain('alt="架构图"')
      expect(html).toContain('href="/doc/1"')
      expect(html).toContain('target="_blank"')
    })
  })

  describe('toPlainText（自动摘要的输入）', () => {
    it('HTML 走 DOM 解析而非正则剥标签', () => {
      // 正则遇到属性里的 > 会剥错，摘要里会混进半截标签
      const text = toPlainText('<p title="a>b">连接池耗尽</p>')
      expect(text).toBe('连接池耗尽')
      expect(text).not.toContain('<')
    })

    it('Markdown 标记被去掉', () => {
      expect(toPlainText('## 标题\n\n- 项目 `code`')).toBe('标题 项目 code')
    })

    it('多余空白折叠为单个空格', () => {
      expect(toPlainText('第一行\n\n\n第二行    第三行')).toBe('第一行 第二行 第三行')
    })

    it('空内容返回空串而不是抛错', () => {
      expect(toPlainText('')).toBe('')
      expect(toPlainText('<p><br></p>')).toBe('')
    })
  })

  describe('extractToc', () => {
    it('从 HTML 提取 h2/h3，带 elementIndex', () => {
      const toc = extractToc('<h2>一级</h2><p>正文</p><h3>二级</h3>')

      expect(toc).toHaveLength(2)
      expect(toc[0]).toMatchObject({ text: '一级', level: 2, elementIndex: 0 })
      expect(toc[1]).toMatchObject({ text: '二级', level: 3, elementIndex: 1 })
      // HTML 路径不该有 lineIndex：两种定位方式混用会让点击目录跳错位置
      expect(toc[0].lineIndex).toBeUndefined()
    })

    it('从 Markdown 提取 ##/###，带 lineIndex', () => {
      const toc = extractToc('前言\n## 一级\n正文\n### 二级')

      expect(toc).toHaveLength(2)
      expect(toc[0]).toMatchObject({ text: '一级', level: 2, lineIndex: 1 })
      expect(toc[1]).toMatchObject({ text: '二级', level: 3, lineIndex: 3 })
      expect(toc[0].elementIndex).toBeUndefined()
    })

    it('忽略 h1 与 h4 —— 大纲只到两级，再深就没有导航价值了', () => {
      const toc = extractToc('<h1>标题</h1><h2>章</h2><h4>细节</h4>')
      expect(toc.map(t => t.text)).toEqual(['章'])
    })

    it('Markdown 的 # 与 #### 同样被忽略', () => {
      const toc = extractToc('# 标题\n## 章\n#### 细节')
      expect(toc.map(t => t.text)).toEqual(['章'])
    })

    it('空标题给出兜底文案，不产生空白目录项', () => {
      const toc = extractToc('<h2></h2><h2>正常</h2>')
      expect(toc[0].text).toBe('章节 1')
      expect(toc[1].text).toBe('正常')
    })

    it('无标题时返回空数组', () => {
      expect(extractToc('<p>只有正文</p>')).toEqual([])
      expect(extractToc('只有正文')).toEqual([])
    })

    it('Markdown 中不带空格的 ## 不算标题', () => {
      // 「##标签」这类写法在运维文档里常见（如 Shell 注释），不该被当成标题
      expect(extractToc('##没有空格')).toEqual([])
    })
  })

  describe('normalizeDraftState —— 兼容旧版草稿格式', () => {
    const form: EditorFormLike = {
      title: '标题', category: '', summary: '', tags: [], content: '正文',
    }

    it('新格式原样返回', () => {
      const state = {
        form, baseVersion: 3, publishOnCreate: true,
        changeReason: '补充步骤', editorMode: 'markdown' as const,
      }
      expect(normalizeDraftState(state)).toBe(state)
    })

    it('旧格式（直接存 form）被包装成新结构', () => {
      // 不做兼容的话，升级发版会让所有人的在编草稿读出来变成 undefined
      const state = normalizeDraftState(form)

      expect(state.form).toEqual(form)
      expect(state.editorMode).toBe('visual')
      expect(state.changeReason).toBe('')
      expect(state.publishOnCreate).toBe(false)
    })

    it('旧格式的 baseVersion 一律为 null —— 让它走版本冲突确认流程', () => {
      // 默认成「与服务器同版」更危险：旧草稿会直接覆盖他人的新修改
      expect(normalizeDraftState(form).baseVersion).toBeNull()
    })

    it('baseVersion=0 的新格式不被误判为旧格式', () => {
      const state = {
        form, baseVersion: 0, publishOnCreate: false,
        changeReason: '', editorMode: 'visual' as const,
      }
      // 靠 'form' in raw 判断而非 baseVersion 真值，
      // 否则 baseVersion=0 的草稿会被当成旧格式重置掉
      expect(normalizeDraftState(state).baseVersion).toBe(0)
    })
  })
})

/** 让 expect(...).resolves 在同一行可读 */
function await0<T>(p: Promise<T>): Promise<T> {
  return p
}

// ==========================================================================
// 目录标题增改删 / 块模板 / 标签
// 这三组都直接改写用户正文或提交数据，错了不会报错，只会「文档变得不对」。
// ==========================================================================

describe('目录标题操作 —— 改错一个索引就是改坏用户的文档', () => {
  describe('appendHeading', () => {
    it('HTML 模式追加 h2，并补一个空段落供继续输入', () => {
      const out = appendHeading('<p>正文</p>', '新章节', true)

      expect(out).toContain('<h2>新章节</h2>')
      // 不补空段落的话，用户点完「新增标题」会发现没法接着往下写
      expect(out).toMatch(/<h2>新章节<\/h2><p><\/p>$/)
    })

    it('Markdown 模式追加 ## 标题', () => {
      expect(appendHeading('正文', '新章节', false)).toBe('正文\n\n## 新章节\n\n')
    })

    it('空文档追加时不产生前导空行', () => {
      expect(appendHeading('', '开篇', false)).toBe('## 开篇\n\n')
      expect(appendHeading('   ', '开篇', false)).toBe('## 开篇\n\n')
    })

    it('标题为空白时原样返回，不插入空标题', () => {
      expect(appendHeading('<p>正文</p>', '   ', true)).toBe('<p>正文</p>')
      expect(appendHeading('正文', '', false)).toBe('正文')
    })

    it('标题两端空格被裁掉', () => {
      expect(appendHeading('', '  带空格  ', false)).toBe('## 带空格\n\n')
    })

    it('不改动原有内容', () => {
      const out = appendHeading('<h2>已有</h2><p>正文</p>', '新增', true)
      expect(out).toContain('<h2>已有</h2>')
      expect(out).toContain('<p>正文</p>')
    })
  })

  describe('renameHeadingIn', () => {
    const html = '<h2>第一章</h2><p>a</p><h3>小节</h3><p>b</p>'

    it('HTML 模式按 elementIndex 精确改名', () => {
      const toc = extractToc(html)
      const out = renameHeadingIn(html, toc[1], '改后小节', true)

      expect(out).toContain('改后小节')
      // 只改目标那一个，其余不动——这正是索引用错时会破的地方
      expect(out).toContain('<h2>第一章</h2>')
      expect(out).toContain('<p>a</p>')
    })

    it('Markdown 模式按 lineIndex 改名并保留原级别', () => {
      const md = '前言\n## 第一章\n正文\n### 小节'
      const toc = extractToc(md)
      const out = renameHeadingIn(md, toc[1], '改后', false)

      // 三级标题改名后仍是三级，不能被降成二级
      expect(out).toBe('前言\n## 第一章\n正文\n### 改后')
    })

    it('索引越界时原样返回 —— 宁可什么都不做，也不能改错一个标题', () => {
      const toc = extractToc(html)
      const bad = { ...toc[0], elementIndex: 99 }
      expect(renameHeadingIn(html, bad, '新名', true)).toBe(html)

      const md = '## 一'
      const badLine = { id: 'x', text: '一', level: 2 as const, lineIndex: 99 }
      expect(renameHeadingIn(md, badLine, '新名', false)).toBe(md)
    })

    it('新名为空白时原样返回', () => {
      const toc = extractToc(html)
      expect(renameHeadingIn(html, toc[0], '  ', true)).toBe(html)
    })

    it('两种索引都缺失时原样返回，不误伤', () => {
      const orphan = { id: 'x', text: '孤儿', level: 2 as const }
      expect(renameHeadingIn(html, orphan, '新名', true)).toBe(html)
    })
  })

  describe('removeHeadingIn', () => {
    it('HTML 模式只删标题，标题下正文保留', () => {
      const html = '<h2>第一章</h2><p>要保留的正文</p><h2>第二章</h2>'
      const toc = extractToc(html)
      const out = removeHeadingIn(html, toc[0], true)

      expect(out).not.toContain('第一章')
      // 连正文一起删会让用户点一下丢掉整段内容，且没有撤销
      expect(out).toContain('要保留的正文')
      expect(out).toContain('第二章')
    })

    it('Markdown 模式只删标题那一行', () => {
      const md = '## 第一章\n正文一\n## 第二章\n正文二'
      const toc = extractToc(md)
      const out = removeHeadingIn(md, toc[0], false)

      expect(out).toBe('正文一\n## 第二章\n正文二')
    })

    it('索引越界时原样返回', () => {
      const html = '<h2>一</h2>'
      const bad = { id: 'x', text: '一', level: 2 as const, elementIndex: 99 }
      expect(removeHeadingIn(html, bad, true)).toBe(html)
    })

    it('删除后剩余标题的索引会重排 —— 调用方必须重新提取 TOC', () => {
      const html = '<h2>一</h2><h2>二</h2><h2>三</h2>'
      const out = removeHeadingIn(html, extractToc(html)[0], true)

      const after = extractToc(out)
      // 删掉「一」之后，「二」的 elementIndex 从 1 变成 0。
      // 拿旧 TOC 连着删两次会删错目标，所以这条钉住重排行为
      expect(after.map(t => t.text)).toEqual(['二', '三'])
      expect(after[0].elementIndex).toBe(0)
    })
  })
})

describe('块模板 —— 两套模板必须语义一一对应', () => {
  it('HTML 与 Markdown 模板键完全一致', () => {
    // 少一个键，切换编辑模式时那种块就插不出来
    expect(Object.keys(HTML_BLOCKS).sort()).toEqual(Object.keys(MARKDOWN_BLOCKS).sort())
  })

  it('每个命令的两套模板都非空', () => {
    for (const key of Object.keys(HTML_BLOCKS) as BlockCommand[]) {
      expect(HTML_BLOCKS[key].trim()).not.toBe('')
      expect(MARKDOWN_BLOCKS[key].trim()).not.toBe('')
    }
  })

  it('表格模板两侧都真的是表格 —— 切模式时不能变形', () => {
    expect(HTML_BLOCKS.table).toContain('<table>')
    expect(HTML_BLOCKS.table).toContain('<th>')
    expect(MARKDOWN_BLOCKS.table).toContain('| --- |')
  })

  it('代码块与分隔线同理', () => {
    expect(HTML_BLOCKS.code).toContain('<pre>')
    expect(MARKDOWN_BLOCKS.code).toContain('```')
    expect(HTML_BLOCKS.divider).toContain('<hr>')
    expect(MARKDOWN_BLOCKS.divider.trim()).toBe('---')
  })

  it('HTML 模板都以空段落收尾，保证插入后光标有处可去', () => {
    for (const key of Object.keys(HTML_BLOCKS) as BlockCommand[]) {
      expect(HTML_BLOCKS[key]).toMatch(/<p><br><\/p>$/)
    }
  })

  it('appendMarkdownBlock 追加到文末且空文档无前导空行', () => {
    expect(appendMarkdownBlock('', 'divider')).toBe('---\n\n')
    expect(appendMarkdownBlock('正文', 'h2')).toBe('正文\n\n## 二级标题\n\n')
  })
})

describe('标签规整 —— 大小写不敏感是关键', () => {
  it('去空白并过滤空标签', () => {
    expect(normalizeTagList([' k8s ', '', '  ', 'mysql'])).toEqual(['k8s', 'mysql'])
  })

  it('K8s 与 k8s 视为同一个，保留先出现的写法', () => {
    // 当成两个会让标签云出现一堆看起来重复的项，
    // 而按标签筛选时又只能命中其中一个
    expect(normalizeTagList(['K8s', 'k8s', 'K8S'])).toEqual(['K8s'])
  })

  it('截断到上限 20 个', () => {
    const many = Array.from({ length: 30 }, (_, i) => `tag${i}`)
    expect(normalizeTagList(many)).toHaveLength(MAX_TAGS)
  })

  it('空数组返回空数组', () => {
    expect(normalizeTagList([])).toEqual([])
  })

  it('hasTag 不区分大小写与首尾空格', () => {
    const tags = ['K8s', 'MySQL']
    expect(hasTag(tags, 'k8s')).toBe(true)
    expect(hasTag(tags, '  mysql  ')).toBe(true)
    expect(hasTag(tags, 'redis')).toBe(false)
  })

  it('hasTag 对空标签返回 false，不误判为已存在', () => {
    expect(hasTag(['k8s'], '   ')).toBe(false)
  })
})
