/**
 * 契约测试：Element Plus 精简入口必须覆盖模板里真实用到的组件
 *
 * 背景见 `src/vendor/element-plus-slim.ts`：resolver 生成的导入路径是 barrel
 * `element-plus/es`，其顶层 `makeInstaller([...全部组件])` 让 tree-shaking 失效
 * （实测产物体积 966KB、含零引用组件）。vite.config.ts 用精确别名把它换成显式
 * 列名的精简入口后，只有真正用到的组件进依赖图（实测降到 452KB）。
 *
 * 代价是「新增组件用法」需要在精简入口里补一行。本测试把这个隐性约定变成显性
 * 断言：漏补时给出可操作的失败信息，而不是等到构建或运行期才发现。
 */
import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import * as slimEntry from '../element-plus-slim'

const SRC = join(__dirname, '..', '..')

/** 递归收集 src 下的 .vue 源文件（跳过测试目录） */
function vueFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      return name === '__tests__' ? [] : vueFiles(full)
    }
    return name.endsWith('.vue') ? [full] : []
  })
}

/** `el-table-column` → `ElTableColumn` */
const toPascal = (tag: string) =>
  tag
    .split('-')
    .map((seg) => seg.charAt(0).toUpperCase() + seg.slice(1))
    .join('')

describe('Element Plus 精简入口契约（vendor/element-plus-slim）', () => {
  const exported = new Set(Object.keys(slimEntry))

  it('模板用到的每个 <el-*> 组件都能在精简入口解析', () => {
    const used = new Map<string, string>()
    for (const file of vueFiles(SRC)) {
      const text = readFileSync(file, 'utf-8')
      for (const m of text.matchAll(/<el-([a-z0-9-]+)/g)) {
        const tag = `el-${m[1]}`
        if (!used.has(tag)) used.set(tag, relative(SRC, file))
      }
    }

    expect(used.size, '应当至少扫到一个 <el-*> 用法（扫描逻辑可能失效）').toBeGreaterThan(0)

    const missing = [...used.entries()]
      .filter(([tag]) => !exported.has(toPascal(tag)))
      .map(([tag, file]) => `${tag}（${file}）→ 需在 element-plus-slim.ts 导出 ${toPascal(tag)}`)

    expect(missing, `以下组件未在精简入口导出：\n${missing.join('\n')}`).toEqual([])
  })

  it('v-loading 指令名与 resolver 生成的 ElLoadingDirective 对齐', () => {
    const usesLoading = vueFiles(SRC).some((f) =>
      readFileSync(f, 'utf-8').includes('v-loading')
    )
    if (usesLoading) {
      expect(
        exported.has('ElLoadingDirective'),
        'resolver 对 v-loading 生成的是 ElLoadingDirective，精简入口必须导出该名字'
      ).toBe(true)
    }
  })

  it('精简入口不得回退为 barrel 转发（否则整包体积回归）', () => {
    const source = readFileSync(join(__dirname, '..', 'element-plus-slim.ts'), 'utf-8')
    expect(
      /from\s+'element-plus\/es'/.test(source),
      '精简入口里出现 `element-plus/es`（barrel）会把整包重新拖回来'
    ).toBe(false)
  })
})
