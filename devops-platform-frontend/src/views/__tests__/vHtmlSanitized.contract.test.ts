/**
 * 全项目 `v-html` 净化契约测试。
 *
 * ── 为什么是全项目扫描而不是逐组件测试 ──────────────────────
 * `v-html` 是前端唯一能把字符串变成可执行 DOM 的入口。本项目里它渲染的
 * 恰恰全是**不可信来源**：知识库正文（用户可编辑）、AI 模型输出、
 * 工单分析结论。任何一处漏净化就是存储型 XSS——
 * 攻击者把 `<img onerror>` 写进知识库文档，此后每个打开该文档的运维
 * 都会在自己的登录态下执行它。
 *
 * 逐个组件写测试守不住这条线：**风险不在已有的那几处，而在下一处**。
 * 明天有人加一个 `v-html="item.desc"`，所有既有测试照样全绿。
 * 所以这里扫描全部 .vue 源码，强制每个 `v-html` 的绑定表达式
 * 都来自已知的净化通道。
 *
 * ── 已知的净化通道 ────────────────────────────────────────
 * - `safeMarkdown(...)` —— marked + DOMPurify，共享白名单策略；
 * - `DOMPurify.sanitize(..., sanitizeConfig())` —— 同一份策略；
 * - 组件内名为 `renderMarkdown` 的函数（其实现必须走上面二者之一，
 *   本测试一并校验）；
 * - 名字以 `safe` 开头的 ref/computed（如 `safeHtml`），
 *   其赋值来源同样被校验。
 *
 * 这条约定与「三套 HTML 净化白名单已统一到 htmlSanitizePolicy」是配套的：
 * 那次统一了**净化规则**，这里守住**是否净化**。
 */
import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

const SRC = resolve(process.cwd(), 'src')

/** 递归收集所有 .vue 文件 */
function vueFiles(dir: string, acc: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      if (name === 'node_modules' || name === '__tests__') continue
      vueFiles(full, acc)
    } else if (name.endsWith('.vue')) {
      acc.push(full)
    }
  }
  return acc
}

interface VHtmlSite {
  file: string
  expr: string
}

/** 抽出所有 v-html 绑定点及其表达式 */
function collectVHtmlSites(): VHtmlSite[] {
  const sites: VHtmlSite[] = []
  for (const file of vueFiles(SRC)) {
    const src = readFileSync(file, 'utf-8')
    for (const m of src.matchAll(/v-html\s*=\s*"([^"]*)"/g)) {
      sites.push({ file: relative(SRC, file), expr: m[1].trim() })
    }
  }
  return sites
}

/** 表达式是否来自已知净化通道 */
function isSanitizedExpr(expr: string): boolean {
  return (
    expr.startsWith('safeMarkdown(') ||
    expr.startsWith('renderMarkdown(') ||
    /^safe[A-Z_]/.test(expr) ||
    expr.includes('DOMPurify.sanitize(')
  )
}

const sites = collectVHtmlSites()

describe('v-html 净化契约', () => {
  it('扫描确实抓到了 v-html 绑定点（防止正则失配导致空集合假通过）', () => {
    // 若锚点或正则失效，sites 会是空数组，下面「全部已净化」
    // 就退化成 [] 上的 forEach——一个永远绿的假测试。
    // 这条断言是整个文件的前提。
    expect(sites.length).toBeGreaterThanOrEqual(4)
  })

  it('每一个 v-html 都绑定到已净化的来源', () => {
    const unsafe = sites.filter(s => !isSanitizedExpr(s.expr))

    expect(
      unsafe.map(s => `${s.file}: v-html="${s.expr}"`),
      '这些 v-html 绑定的表达式不在已知净化通道内。' +
        'v-html 渲染的是知识库正文、AI 输出这类不可信内容，' +
        '漏净化即存储型 XSS——攻击者写进去的脚本会在每个查看者的登录态下执行。' +
        '请改用 safeMarkdown() 或 DOMPurify.sanitize(x, sanitizeConfig())'
    ).toEqual([])
  })

  it('绝不直接把原始文档内容塞进 v-html', () => {
    // 单列一条是因为这是最容易犯、后果最重的写法：
    // doc.content / msg.content 是原始存储值，直接渲染等于零防护
    const raw = sites.filter(s => /^(doc|item|msg|row|form)\??\./.test(s.expr))

    expect(
      raw.map(s => `${s.file}: v-html="${s.expr}"`),
      '直接绑定原始字段，未经任何净化'
    ).toEqual([])
  })
})

describe('净化通道自身的实现', () => {
  /** 所有定义了 renderMarkdown 的文件必须真的调用净化函数 */
  it('组件内的 renderMarkdown 实现必须走 safeMarkdown 或 DOMPurify', () => {
    const offenders: string[] = []

    for (const file of vueFiles(SRC)) {
      const src = readFileSync(file, 'utf-8')
      // 只看「定义」而非「作为 prop 接收」——
      // AnalysisCard 通过 prop 拿 renderMarkdown，净化责任在调用方，
      // 它自己没有实现，不该被误判
      const defined = /const\s+renderMarkdown\s*=\s*\(/.test(src)
      if (!defined) continue

      const sanitized =
        src.includes('safeMarkdown') || src.includes('DOMPurify.sanitize')
      if (!sanitized) offenders.push(relative(SRC, file))
    }

    expect(
      offenders,
      '这些文件自己实现了 renderMarkdown 却没有引入净化——' +
        '名字叫 render 不代表内容安全'
    ).toEqual([])
  })

  it('KnowledgeDetail 的 safeHtml 由 safeMarkdown 赋值', () => {
    const src = readFileSync(resolve(SRC, 'views/KnowledgeDetail.vue'), 'utf-8')

    // 知识库正文是用户可编辑的富文本，是本项目最主要的存储型 XSS 面。
    // safeHtml 这个名字只是约定，真正的保证在于它的赋值来源
    expect(src).toMatch(/safeHtml\.value\s*=\s*safeMarkdown\(/)
    // 且不能有「先赋原始值再净化」的中间态——
    // Vue 的响应式会把那一瞬的原始值渲染出去
    expect(src).not.toMatch(/safeHtml\.value\s*=\s*raw\b/)
  })

  it('AnalysisCard 通过 prop 接收 renderMarkdown，其提供方已净化', () => {
    const composable = readFileSync(
      resolve(SRC, 'composables/useTicketAnalysis.ts'),
      'utf-8'
    )

    // AnalysisCard 有 3 处 v-html 但自身不实现净化——责任在 useTicketAnalysis。
    // 这条断言把那个隐式契约显式化：谁提供 renderMarkdown，谁负责净化
    expect(composable).toMatch(/const\s+renderMarkdown\s*=[^\n]*safeMarkdown\(/)
  })
})
