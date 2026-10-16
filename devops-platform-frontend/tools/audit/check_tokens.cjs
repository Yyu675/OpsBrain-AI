#!/usr/bin/env node
/**
 * CSS 令牌对账（2026-09-27）
 *
 * 防的缺陷类：「用了但从未定义」的幽灵令牌。
 * 真实案例：--color-danger / --color-warning / --color-fill-light 等 6 个令牌
 * 在视图里被 var() 引用，但 theme.css / theme-bridge.css / variables.css
 * 从未定义它们——浏览器把 var(未定义) 按「保证无效值」处理，样式静默回退，
 * 看起来「一切正常」直到有人发现某些颜色根本没生效。
 *
 * 做法：扫 src 下所有 var(--xxx) 引用，与样式文件里的 --xxx: 定义集合对账。
 * 引用存在而定义缺席 → 报错并退出码 1（可挂 CI）。
 *
 * 例外：--el-* 是 Element Plus 的运行时变量（组件库自带定义），不在对账范围。
 */
'use strict'

const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '../..')
const SRC = path.join(ROOT, 'src')

const SCAN_EXT = new Set(['.vue', '.ts', '.css', '.scss'])
/** 生成物与测试夹具不参与（测试里的令牌名是断言数据，不是真实引用） */
const SKIP_DIRS = new Set(['node_modules', 'dist', '__tests__'])

function* walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_DIRS.has(entry.name)) continue
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) yield* walk(full)
    else if (SCAN_EXT.has(path.extname(entry.name))) yield full
  }
}

/** 去掉注释后再扫——桥接文件的注释里写着「2494 处 var(--color-*)」这类字样 */
function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|\s)\/\/[^\n]*/g, '$1')
}

/** 收集样式文件里定义的全部令牌（--xxx: ...）——定义点不限于 assets/styles */
function collectDefined() {
  const defined = new Set()
  for (const file of walk(SRC)) {
    if (!file.endsWith('.css') && !file.endsWith('.scss')) continue
    const text = stripComments(fs.readFileSync(file, 'utf8'))
    for (const m of text.matchAll(/(--[a-zA-Z][\w-]*)\s*:/g)) {
      defined.add(m[1])
    }
  }
  return defined
}

/** 收集源码里全部 var(--xxx) 引用（含 fallback 链里的嵌套引用） */
function collectReferenced() {
  /** @type {Map<string, string[]>} token → 引用它的文件列表 */
  const referenced = new Map()
  for (const file of walk(SRC)) {
    if (file.endsWith('.css') || file.endsWith('.scss')) {
      // 样式文件既可能是定义方也可能是引用方，同样要查
    }
    const text = stripComments(fs.readFileSync(file, 'utf8'))
    for (const m of text.matchAll(/var\(\s*([^)]+)\)/g)) {
      const inner = m[1]
      // 动态拼接（var(--chart-${i})）无法静态对账，跳过
      if (inner.includes('${')) continue
      const tok = inner.match(/^(--[a-zA-Z][\w-]*)/)
      if (!tok) continue
      const list = referenced.get(tok[1]) ?? []
      list.push(path.relative(ROOT, file))
      referenced.set(tok[1], list)
    }
  }
  return referenced
}

const defined = collectDefined()
const referenced = collectReferenced()

const ghosts = []
for (const [token, files] of referenced) {
  if (defined.has(token)) continue
  if (token.startsWith('--el-')) continue // Element Plus 运行时变量
  ghosts.push({ token, files: [...new Set(files)] })
}

console.log(`[tokens] 定义 ${defined.size} 个 / 引用 ${referenced.size} 个（已豁免 --el-*）`)
if (ghosts.length === 0) {
  console.log('[tokens] ✅ 无幽灵令牌：所有 var() 引用都有定义')
  process.exit(0)
}

console.error(`[tokens] ❌ 发现 ${ghosts.length} 个「引用了但未定义」的幽灵令牌：`)
for (const g of ghosts) {
  console.error(`  ${g.token}`)
  for (const f of g.files.slice(0, 5)) console.error(`      ${f}`)
  if (g.files.length > 5) console.error(`      … 另 ${g.files.length - 5} 处`)
}
console.error('[tokens] 修复：在 theme.css 定义它（走语义令牌），或把引用改成已有令牌')
process.exit(1)
