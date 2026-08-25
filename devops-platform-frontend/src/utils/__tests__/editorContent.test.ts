import { describe, expect, it } from 'vitest'

import {
  extractToc,
  hasMeaningfulContent,
  isHtmlContent,
  normalizeDraftState,
  toPlainText,
  toVisualContent,
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
