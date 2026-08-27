import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import DOMPurify from 'dompurify'
import { describe, expect, it } from 'vitest'

import { ALLOWED_ATTR, ALLOWED_TAGS } from '../editorContent'
import {
  SANITIZE_ALLOWED_ATTR,
  SANITIZE_ALLOWED_TAGS,
  SANITIZE_FORBIDDEN_TAGS,
  sanitizeConfig,
} from '../htmlSanitizePolicy'

/**
 * HTML 净化策略一致性测试。
 *
 * ── 这组测试的价值大于它保护的那个修复 ────────────────────────
 * 修复本身很小（把三份白名单合成一份）。真正重要的是**防止再次漂移**：
 * 本会话已经查出三次「两份真相并存」——业务码词表零调用方、
 * 终态定义一套变两套、以及这次的净化白名单。
 *
 * 它们的共同点是：**编译期无信号、运行期无报错，只有用户会发现**。
 * 白名单漂移的表现是「排版发布后就没了」，用户会以为自己没保存成功。
 *
 * 所以这里不只测「当前是对的」，而是测「三处必须引用同一份」——
 * 任何一处偷偷改回本地常量，测试立刻红。
 *
 * ── 为什么要读源码做交叉校验 ──────────────────────────────────
 * 只断言导出值相等的话，有人复制粘贴一份一模一样的本地常量也能通过，
 * 而那正是漂移的起点（三份最初也是一样的）。
 * 所以额外扫描源码，确认三处**确实 import 了共享策略**。
 * 这与 `bizCode.contract.test.ts` 直接读后端 Java 枚举是同一思路。
 */

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = resolve(HERE, '../..')

const read = (rel: string) => readFileSync(resolve(SRC, rel), 'utf-8')

/**
 * 自行调用 DOMPurify 的地方（2026-08-27 由三处收敛为两处）。
 *
 * `KnowledgeSinkDrawer.vue` 原本也自行组合 marked + DOMPurify，
 * 现已改为调用 `safeMarkdown()` 统一入口——它不再是净化调用点，
 * 因此从本清单移除。**这是收敛而非放宽**：调用点越少，
 * 策略漂移的入口就越少。
 *
 * 由 `__tests__/errorBoundary.contract.test.ts` 的
 * 「Markdown 渲染单一入口」用例保证不会再有新的自行净化出现。
 */
const CONSUMERS = [
  'utils/editorContent.ts',
  'utils/safeMarkdown.ts',
] as const

describe('净化策略：单一真相', () => {
  it('三处调用点都引用共享策略，而不是各自维护常量', () => {
    for (const file of CONSUMERS) {
      const src = read(file)
      expect(src, `${file} 应从 htmlSanitizePolicy 引入白名单`)
        .toMatch(/from ['"](@\/utils\/|\.\/)htmlSanitizePolicy['"]/)
    }
  })

  it('三处调用点都不再自带内联标签白名单', () => {
    // 判据：出现 ALLOWED_TAGS 的同时紧跟一个含引号标签名的数组字面量，
    // 说明又写回了本地常量。转发写法（= SANITIZE_ALLOWED_TAGS）不会命中
    const inlineList = /ALLOWED_TAGS\s*:?\s*=?\s*\[\s*\n?\s*'/
    for (const file of CONSUMERS) {
      expect(inlineList.test(read(file)), `${file} 不应内联白名单数组`).toBe(false)
    }
  })

  it('editorContent 转发的白名单与共享策略是同一份', () => {
    // 用 toBe 而非 toEqual：必须是同一个引用，
    // 复制一份内容相同的数组同样是两份真相
    expect(ALLOWED_TAGS).toBe(SANITIZE_ALLOWED_TAGS)
    expect(ALLOWED_ATTR).toBe(SANITIZE_ALLOWED_ATTR)
  })

  it('sanitizeConfig 返回的是副本 —— DOMPurify 可能就地改写传入数组', () => {
    const a = sanitizeConfig()
    a.ALLOWED_TAGS.push('script')

    // 若返回的是共享数组本身，上面这行会把 script 加进全局白名单，
    // 之后所有净化都会放行脚本
    expect(sanitizeConfig().ALLOWED_TAGS).not.toContain('script')
    expect(SANITIZE_ALLOWED_TAGS).not.toContain('script')
  })
})

describe('白名单内容：修复的是真实的内容丢失', () => {
  it('包含 u / s —— 此前只有编辑器允许，发布后就消失', () => {
    // 用户排好版、保存成功、编辑态看得见，一发布到详情页就没了，
    // 且全程零报错。他会以为是自己没保存
    expect(SANITIZE_ALLOWED_TAGS).toContain('u')
    expect(SANITIZE_ALLOWED_TAGS).toContain('s')
  })

  it('包含 img / figure / tfoot —— 此前知识沉淀抽屉会剥掉', () => {
    // 同一篇文档在抽屉里预览时图片与表尾直接消失，
    // 用户会以为是 AI 整理时把内容弄丢了
    expect(SANITIZE_ALLOWED_TAGS).toContain('img')
    expect(SANITIZE_ALLOWED_TAGS).toContain('figure')
    expect(SANITIZE_ALLOWED_TAGS).toContain('tfoot')
  })

  it('包含 src / alt —— 只放行 img 标签而不放行 src，图片照样渲染不出来', () => {
    expect(SANITIZE_ALLOWED_ATTR).toContain('src')
    expect(SANITIZE_ALLOWED_ATTR).toContain('alt')
  })

  it('包含 rel —— safeMarkdown 的防 tabnabbing hook 依赖它', () => {
    // hook 给 target=_blank 的链接补 rel="noopener noreferrer"，
    // 但 rel 不在白名单的话，补完会被后续净化剥掉，防护等于没做
    expect(SANITIZE_ALLOWED_ATTR).toContain('rel')
    expect(SANITIZE_ALLOWED_ATTR).toContain('target')
  })

  it('包含表格全套 —— 运维手册的主力结构', () => {
    for (const tag of ['table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td']) {
      expect(SANITIZE_ALLOWED_TAGS, `缺 ${tag} 会让表格塌成一行文字`).toContain(tag)
    }
  })
})

describe('安全边界：白名单不能被误放宽', () => {
  it('危险标签一个都不在白名单里', () => {
    for (const tag of SANITIZE_FORBIDDEN_TAGS) {
      expect(SANITIZE_ALLOWED_TAGS, `${tag} 绝不能放行`).not.toContain(tag)
    }
  })

  it('不允许任何 on* 事件属性', () => {
    const onAttrs = SANITIZE_ALLOWED_ATTR.filter(a => a.toLowerCase().startsWith('on'))
    expect(onAttrs).toEqual([])
  })

  it('不允许 style 属性 —— 可用于覆盖页面布局做钓鱼', () => {
    expect(SANITIZE_ALLOWED_ATTR).not.toContain('style')
  })

  it('实际净化会剥掉 script 与内联事件', () => {
    const dirty = '<p onclick="steal()">正文</p><script>alert(1)</script>'
    const clean = DOMPurify.sanitize(dirty, sanitizeConfig())

    expect(clean).not.toContain('<script')
    expect(clean).not.toContain('onclick')
    // 正常内容要留下，不能因为有脚本就整篇清空
    expect(clean).toContain('正文')
  })

  it('实际净化会剥掉 iframe，但保留同一段里的合法内容', () => {
    const clean = DOMPurify.sanitize(
      '<iframe src="//evil.example"></iframe><p>保留我</p>', sanitizeConfig())

    expect(clean).not.toContain('<iframe')
    expect(clean).toContain('保留我')
  })

  it('实际净化保留下划线、删除线与表格', () => {
    const clean = DOMPurify.sanitize(
      '<p><u>下划线</u><s>删除线</s></p><table><tfoot><tr><td>合计</td></tr></tfoot></table>',
      sanitizeConfig())

    expect(clean).toContain('<u>')
    expect(clean).toContain('<s>')
    expect(clean).toContain('<tfoot>')
  })

  it('白名单无重复项 —— 重复通常是合并时手抖的痕迹', () => {
    expect(new Set(SANITIZE_ALLOWED_TAGS).size).toBe(SANITIZE_ALLOWED_TAGS.length)
    expect(new Set(SANITIZE_ALLOWED_ATTR).size).toBe(SANITIZE_ALLOWED_ATTR.length)
  })
})
