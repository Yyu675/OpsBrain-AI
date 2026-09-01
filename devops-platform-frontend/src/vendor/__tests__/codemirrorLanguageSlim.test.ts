import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

import { languages } from '../codemirror-language-data-slim'

/**
 * CodeMirror 精简语言注册表的契约。
 *
 * ## 背景
 *
 * `md-editor-v3` 内部引用 `@codemirror/language-data`——CodeMirror 的全语言注册表，
 * 含 **136 个动态 import**。Rollup 把每个切成独立 chunk，实测产出
 * **148 个碎片 / 630 KB**，其中绝大多数是 z80 / yacas / xquery / verilog /
 * vbscript 这类运维手册永远用不到的语言。
 *
 * 用 Vite alias 换成本目录的精简注册表后：**4890 → 4324 KB，文件数 183 → 86**。
 *
 * ## 这组测试防什么
 *
 * 1. **有人删掉 vite.config.ts 里的 alias** —— 630 KB 碎片全部回来，
 *    而页面功能完全正常，没有任何信号；
 * 2. **精简过头** —— 把运维实际会用的语言（bash/yaml/sql…）也删了，
 *    用户写代码块时高亮失效，同样没有报错；
 * 3. **懒加载被破坏** —— 有人把 `load()` 里的动态 import 改成静态 import，
 *    所有语言解析器进主包，体积不降反升。
 */
describe('CodeMirror 精简语言注册表', () => {
  /** 运维文档里真正会出现的语言，缺一个都会让用户的代码块失去高亮 */
  const MUST_HAVE = [
    'Shell',       // 排查命令、运维脚本 —— 最高频
    'YAML',        // K8s 清单、Prometheus 规则、CI 配置
    'JSON',        // API 载荷、告警 payload
    'SQL',         // 数据库排查
    'Dockerfile',  // 镜像构建
    'Nginx',       // 反代与网关配置
    'Properties',  // Spring 配置
    'Java',        // 本项目后端
    'Python',
    'Go',
    'JavaScript',
    'TypeScript',
    'Markdown',
    'XML',
  ]

  /** 明确不该保留的：运维手册里不会出现，是 630 KB 碎片的主要来源 */
  const MUST_NOT_HAVE = [
    'Z80', 'Yacas', 'XQuery', 'Verilog', 'VBScript',
    'VHDL', 'WebIDL', 'Velocity', 'Fortran', 'COBOL',
  ]

  const names = languages.map((l) => l.name)

  it.each(MUST_HAVE)('保留了运维必需语言：%s', (lang) => {
    expect(
      names,
      `${lang} 是运维文档常用语言，删掉会让用户的代码块失去高亮且无任何报错`
    ).toContain(lang)
  })

  it.each(MUST_NOT_HAVE)('未引入冷门语言：%s', (lang) => {
    expect(
      names,
      `${lang} 不该出现在运维知识库里。加它会额外产出一个 chunk，` +
        `而这正是当初 148 个碎片的来源`
    ).not.toContain(lang)
  })

  it('语言数量控制在合理区间 —— 过多说明在往回退', () => {
    // 上游全量是 136 个。这里定 40 上限：足够覆盖扩展需求，
    // 又能在有人「顺手把全量加回来」时立刻失败
    expect(languages.length).toBeGreaterThanOrEqual(14)
    expect(
      languages.length,
      `语言数已达 ${languages.length}。若确实需要更多，请先确认它们是运维文档` +
        `真会用到的；不要为了「以防万一」把上游全量加回来`
    ).toBeLessThanOrEqual(40)
  })

  it('每个语言都通过 load() 懒加载，不在主包里', () => {
    // LanguageDescription 的 load 是必须的；若有人改成静态 import + 直接给 support，
    // 所有解析器会进主包，体积不降反升
    for (const lang of languages) {
      expect(
        typeof lang.load,
        `${lang.name} 缺少 load()，解析器会被打进主包`
      ).toBe('function')
    }
  })

  it('name 不重复 —— 重复会让 md-editor 的语言匹配取到非预期的那个', () => {
    expect(new Set(names).size).toBe(names.length)
  })

  describe('vite.config.ts 的 alias 必须在位', () => {
    const here = dirname(fileURLToPath(import.meta.url))
    const viteConfig = readFileSync(resolve(here, '../../../vite.config.ts'), 'utf-8')

    it('alias 指向本精简注册表', () => {
      // 这条是整个优化的开关。删掉 alias，md-editor 会重新引用上游全量注册表，
      // 630 KB 碎片全部回来 —— 而页面功能完全正常，不会有任何信号提示你
      expect(
        viteConfig,
        'vite.config.ts 里 @codemirror/language-data 的 alias 不见了。' +
          '没有它，md-editor-v3 会引用上游全量注册表（136 个动态 import），' +
          '产物重新膨胀 630 KB。若确实要退回全量，请连同本测试一起删并说明理由'
      ).toContain("'@codemirror/language-data'")
      expect(viteConfig).toContain('codemirror-language-data-slim')
    })
  })
})
