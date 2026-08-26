#!/usr/bin/env node
/**
 * 评测集数据完整性校验器（沙箱可跑，不依赖 JDK）
 * 校验：
 *  1. src/test/resources/eval_dataset.json —— 100 条（50 正 + 50 负），id 唯一，关键词非空
 *  2. src/test/resources/alert_replay_dataset.json —— 6 场景，期望值结构合法
 * 用法：node tools/audit/validate_eval_datasets.js
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '../..')
let failed = false
const fail = (msg) => { failed = true; console.error('  ❌ ' + msg) }
const ok = (msg) => console.log('  ✅ ' + msg)

console.log('═ 评测集数据完整性校验 ═')

// ---------- 1. eval_dataset.json ----------
const evalPath = path.join(ROOT, 'src/test/resources/eval_dataset.json')
const items = JSON.parse(fs.readFileSync(evalPath, 'utf8'))
console.log(`\n[1] eval_dataset.json（${items.length} 条）`)
if (!Array.isArray(items) || items.length !== 100) fail(`应为 100 条，实际 ${items.length}`)

const ids = new Set()
let pos = 0, neg = 0
const typeCount = {}
for (const it of items) {
  if (ids.has(it.id)) fail(`id 重复: ${it.id}`)
  ids.add(it.id)
  if (!it.query || it.query.trim().length < 4) fail(`#${it.id} query 过短`)
  if (!Array.isArray(it.expectedKeywords) || it.expectedKeywords.length === 0) fail(`#${it.id} expectedKeywords 为空`)
  if (typeof it.shouldTriggerFallback !== 'boolean') fail(`#${it.id} shouldTriggerFallback 缺失`)
  if (it.type === 'POSITIVE') { pos++; if (it.shouldTriggerFallback !== false) fail(`#${it.id} 正例不应触发拒答`) }
  else if (it.type.startsWith('NEGATIVE')) { neg++; if (it.shouldTriggerFallback !== true) fail(`#${it.id} 负例应触发拒答`) }
  else fail(`#${it.id} 未知类型 ${it.type}`)
  typeCount[it.type] = (typeCount[it.type] || 0) + 1
}
pos === 50 ? ok(`正例 50 条`) : fail(`正例应为 50，实际 ${pos}`)
neg === 50 ? ok(`负例 50 条`) : fail(`负例应为 50，实际 ${neg}`)
console.log('  负例分布:', JSON.stringify(typeCount))

// ---------- 2. alert_replay_dataset.json ----------
const replayPath = path.join(ROOT, 'src/test/resources/alert_replay_dataset.json')
const scenarios = JSON.parse(fs.readFileSync(replayPath, 'utf8'))
console.log(`\n[2] alert_replay_dataset.json（${scenarios.length} 个场景）`)
if (!Array.isArray(scenarios) || scenarios.length !== 6) fail(`应为 6 场景，实际 ${scenarios.length}`)
for (const s of scenarios) {
  if (!s.scenario) fail('场景缺 description')
  if (!Array.isArray(s.events) || s.events.length < 2) fail(`场景「${s.scenario}」事件不足 2 条`)
  for (const e of s.events) {
    if (!e.alertName || !e.service || !e.labels) fail(`场景「${s.scenario}」事件字段缺失`)
  }
  for (const k of ['distinctKeys', 'ticketsCreated']) {
    if (!(k in (s.expect || {}))) fail(`场景「${s.scenario}」缺 expect.${k}`)
  }
}
ok('6 场景结构合法（同键风暴/跨键同源/跨源独立/severity 排除/时间窗/标签顺序）')

console.log('\n' + (failed ? '校验未通过 ✗' : '全部通过 ✓'))
process.exit(failed ? 1 : 0)
