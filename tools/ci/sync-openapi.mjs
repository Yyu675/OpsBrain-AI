#!/usr/bin/env node
/**
 * 项3（2026-10-03）：OpenAPI 快照同步——把 springdoc 反射出的契约（含每个 path 的
 * HTTP 方法）固化成仓内工件，供前端 apiContract 测试做「方法级」交叉验证。
 *
 * 批90 跨平台化（Node 版取代 bash 版）：本机 WSL interop 损坏时
 * `bash tools/ci/sync-openapi.sh` 会**静默空跑**（实测 2026-10-12），
 * 快照同步退回人肉纪律——正是要消灭的那类静默漂移。node 全平台一致。
 *
 * 用法（dev 后端在 8088 活着时）：node tools/ci/sync-openapi.mjs
 * 产物：openapi/api-docs.snapshot.json（提交入库——控制器或前端端点变动后重跑）
 *
 * 口径（与 .sh 版逐字对齐）：
 * - context-path=/ai → 快照取 /ai/v3/api-docs；
 * - 空壳(<1KB)/非 JSON/paths<20/缺 HTTP 方法 一律硬失败，宁缺毋假。
 */
import { mkdirSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const URL = process.env.OPENAPI_URL || 'http://localhost:8088/ai/v3/api-docs'
const OUT = join(ROOT, 'openapi', 'api-docs.snapshot.json')
const TMP = `${OUT}.${process.pid}.tmp`

const die = (msg) => {
  console.error(`❌ ${msg}`)
  process.exit(1)
}

let res
try {
  res = await fetch(URL, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(15_000),
  })
} catch (e) {
  die(`拉取 ${URL} 失败：${e.message}（dev 后端 8088 活着吗？）`)
}
if (!res.ok) die(`HTTP ${res.status} ${res.statusText}——非契约体（${URL}）`)

const buf = Buffer.from(await res.arrayBuffer())
if (buf.length <= 1024) die(`快照仅 ${buf.length}B，疑似非契约体（${URL}）`)

let doc
try {
  doc = JSON.parse(buf.toString('utf8'))
} catch {
  die('响应非 JSON——springdoc 未启用或网关返回了错误页')
}

const paths = Object.keys(doc.paths ?? {})
if (paths.length < 20) die(`paths 仅 ${paths.length} 条，springdoc 反射异常`)
const VERBS = ['get', 'post', 'put', 'delete', 'patch']
const verbful = paths.filter((p) =>
  Object.keys(doc.paths[p]).some((m) => VERBS.includes(m.toLowerCase()))
)
if (verbful.length !== paths.length) {
  die(`${paths.length - verbful.length} 条 path 无任何 HTTP 方法`)
}

// 规范化：把每个 schema 的 properties 键按字典序重排后再落盘。
//
// 为什么（2026-10-15 实测）：springdoc 的 properties 顺序来自 JVM 反射顺序，
// **同一套代码在不同运行间会给出不同顺序**。于是同一份契约两次同步出的文件
// 不逐字相等 → `npm run gen:api` 的产物（orval 按 properties 顺序生成字段）
// 也跟着抖 → 开发者跑 api:refresh 会看到一堆纯顺序的假 diff，真漂移被埋在里面。
// 规范化只改顺序不改内容：CI 的「快照新鲜度」门禁按 path/method + 属性**集合**
// 对账（见 ci.yml 该步注释），因此与本排序相容。
const sortSchemaProperties = (node) => {
  if (Array.isArray(node)) {
    node.forEach(sortSchemaProperties)
    return
  }
  if (node && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) {
      if (k === 'properties' && v && typeof v === 'object' && !Array.isArray(v)) {
        const sorted = {}
        for (const key of Object.keys(v).sort()) sorted[key] = v[key]
        node.properties = sorted
      }
      sortSchemaProperties(node[k] === node.properties ? node.properties : node[k])
    }
  }
}
sortSchemaProperties(doc.components?.schemas ?? {})
// 保持单行紧凑格式（与历史快照逐字对齐——格式化差异会掩盖真漂移）
const normalized = Buffer.from(JSON.stringify(doc), 'utf8')

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(TMP, normalized)
renameSync(TMP, OUT)
console.log(`✓ paths=${paths.length} 全带方法`)
console.log(`✓ 快照落盘 openapi/api-docs.snapshot.json（${normalized.length}B，schema 属性已字典序规范化）`)
console.log('  ——记得 git add 提交')
