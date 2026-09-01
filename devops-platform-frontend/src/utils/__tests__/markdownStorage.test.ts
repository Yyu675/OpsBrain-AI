import { describe, it, expect } from 'vitest'

import { toMarkdownForStorage, toVisualContent, isHtmlContent } from '../editorContent'

/**
 * 入库内容格式统一契约（F-6）。
 *
 * ## 要防住的缺陷
 *
 * `handleSave` 此前直接提交 `formData.content`——**用户停在哪个编辑模式
 * 就存哪个格式**。visual 模式存 HTML，而 `sys_knowledge_doc.content`
 * 的 schema 注释明写「Markdown 原文」。
 *
 * 三处后果，且全都没有报错：
 * 1. **RAG 切片错乱**：`ParentChildDocumentSplitter` 按 Markdown 标题层级切分，
 *    拿到 HTML 时切片边界跑偏 → 检索准确率下降；
 * 2. **详情页渲染不可控**：`safeMarkdown` 用 Markdown 渲染器处理 HTML，
 *    结果取决于 marked 对裸 HTML 的宽容度；
 * 3. **`content_hash` 去重失效**：同一篇文档两种模式保存哈希不同。
 *
 * ## 断言落点
 *
 * 落在**「转换后的内容到底是什么」**上，而不是「函数被调用了」——
 * 后者在「原样返回」的错误实现下照样成立。
 */
describe('入库内容统一为 Markdown', () => {
  describe('HTML 输入必须转成 Markdown', () => {
    it('标题转成 atx 风格（### 而非下划线）—— 后端按 # 层级切片', () => {
      // atx 风格是关键：ParentChildDocumentSplitter 靠 # 的个数识别层级，
      // setext 风格（下划线）它认不出来
      const out = toMarkdownForStorage('<h3>Pod 排查步骤</h3>')
      expect(out).toContain('### Pod 排查步骤')
      expect(out).not.toContain('<h3>')
    })

    it('代码块转成围栏式（```）—— 缩进式在嵌套列表里会被吞', () => {
      const out = toMarkdownForStorage('<pre><code>kubectl get pods</code></pre>')
      expect(out).toContain('```')
      expect(out).toContain('kubectl get pods')
      expect(out).not.toContain('<pre>')
    })

    it('列表、粗体、链接都转成 Markdown 记法', () => {
      const html =
        '<ul><li>先看 <strong>Pod 状态</strong></li>' +
        '<li>再查 <a href="https://k8s.io">文档</a></li></ul>'
      const out = toMarkdownForStorage(html)
      expect(out).toContain('**Pod 状态**')
      expect(out).toContain('[文档](https://k8s.io)')
      expect(out).not.toContain('<li>')
    })

    it('转换结果里不残留块级标签 —— 残留会让后端切片当成正文字符', () => {
      const html = '<h2>标题</h2><p>正文段落</p><ul><li>要点</li></ul>'
      const out = toMarkdownForStorage(html)
      for (const tag of ['<h2>', '<p>', '<ul>', '<li>']) {
        expect(out, `转换后仍残留 ${tag}`).not.toContain(tag)
      }
    })
  })

  describe('Markdown 输入必须原样返回（幂等）', () => {
    it('纯 Markdown 不被改动', () => {
      // 无条件跑 turndown 会破坏 Markdown：
      // 这正是「转换要判别输入格式」的理由
      const md = '## 标题\n\n- 要点一\n- 要点二\n\n```bash\nkubectl get pods\n```\n'
      expect(toMarkdownForStorage(md)).toBe(md)
    })

    it('含行内 HTML 的 Markdown 不被破坏 —— <br>、<details> 是合法 Markdown 用法', () => {
      // 运维手册常用 <details> 折叠长日志。若无条件转换，
      // 这些结构会被 turndown 改写甚至丢失
      const md = '正文<br>换行\n\n<details><summary>展开日志</summary>\n\n堆栈\n\n</details>\n'
      expect(toMarkdownForStorage(md)).toBe(md)
    })

    it('代码块里的 HTML 标签不被当成真 HTML', () => {
      // 运维文档里贴 nginx 配置、HTML 片段是常事。
      // 判别若用「含有 < 」而非「以 < 开头」，这段会被误转
      const md = '示例：\n\n```html\n<div class="x">hi</div>\n```\n'
      expect(toMarkdownForStorage(md)).toBe(md)
    })

    it('空内容原样返回，不产出占位符', () => {
      expect(toMarkdownForStorage('')).toBe('')
      expect(toMarkdownForStorage('   ')).toBe('   ')
    })
  })

  describe('与 toVisualContent 的判据必须一致', () => {
    it('两个方向用同一个 isHtmlContent 判据', () => {
      // 判据不一致会出现「转过去再转回来内容变了」：
      // 比如存储侧认为是 Markdown 不转，而 visual 侧认为是 HTML 直接塞进富文本，
      // 用户看到的就是一堆转义后的源码
      expect(isHtmlContent('<h1>x</h1>')).toBe(true)
      expect(isHtmlContent('# x')).toBe(false)
      expect(isHtmlContent('  <p>缩进后仍是 HTML</p>')).toBe(true)
    })

    it('HTML → Markdown → HTML 往返后语义不丢', async () => {
      const original = '<h2>排查</h2><p>先看 <strong>日志</strong></p>'
      const md = toMarkdownForStorage(original)
      const back = await toVisualContent(md)
      // 不断言字节相等（渲染器会规范化空白与属性顺序），
      // 断言语义元素还在——标题层级与强调都不能丢
      expect(back).toContain('<h2>')
      expect(back).toContain('排查')
      expect(back).toContain('<strong>')
      expect(back).toContain('日志')
    })
  })
})
