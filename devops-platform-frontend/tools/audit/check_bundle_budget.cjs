#!/usr/bin/env node
/**
 * 构建预算门（F-4 P1 兜底）
 *
 * 用法：npm run build 之后
 *   node tools/audit/check_bundle_budget.js
 *
 * 读取 tools/audit/bundle_budget.json 的规则，
 * 对照 dist/ 目录的实际产物尺寸输出通过/警告/阻断。
 * exit code 0=通过 1=警告 2=阻断（CI 按 exit code 判失败）。
 */
const fs = require('fs')
const path = require('path')

const distDir = path.resolve(__dirname, '../../dist')
const budgetFile = path.resolve(__dirname, 'bundle_budget.json')
const budget = JSON.parse(fs.readFileSync(budgetFile, 'utf8'))

let violations = 0
let errors = 0

function walkDir(dir, suffix = '') {
  const results = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) { results.push(...walkDir(full, suffix + '/' + entry.name)) }
    else { results.push({ path: suffix + '/' + entry.name, size: fs.statSync(full).size }) }
  }
  return results
}

function fmt(n) { return (n / 1024).toFixed(1) + ' kB' }

if (!fs.existsSync(distDir)) {
  console.log('⚠️ dist/ 目录不存在——请先 npm run build')
  process.exit(0)
}

const files = walkDir(distDir)
const totalSize = files.reduce((s, f) => s + f.size, 0)
console.log(`📦 构建产物扫描：${files.length} 个文件，合计 ${fmt(totalSize)}`)

for (const rule of budget.rules) {
  let actual = 0
  if (rule.type === 'total') { actual = totalSize }
  else if (rule.type === 'chunk') {
    const match = files.filter(f => f.path.includes(rule.resource))
    actual = match.reduce((s, f) => s + f.size, 0)
    if (match.length === 0) continue
  } else if (rule.type === 'entry') {
    const match = files.filter(f => f.path.match(/\/index-[A-Za-z0-9]+\.js$/))
    actual = match.reduce((s, f) => s + f.size, 0)
  }

  const maxBytes = rule.maxKB * 1024
  if (actual > maxBytes) {
    const flag = rule.level === 'error' ? '❌ 阻断' : '⚠️ 警告'
    console.log(`${flag}  ${rule.resource}: ${fmt(actual)} > 上限 ${fmt(maxBytes)} — ${rule.note}`)
    violations++
    if (rule.level === 'error') errors++
  }
}

if (errors > 0) {
  console.log(`\n❌ ${errors} 项阻断，${violations - errors} 项警告。构建未通过预算门。`)
  process.exit(2)
} else if (violations > 0) {
  console.log(`\n⚠️ ${violations} 项警告。构建通过但需关注。`)
  process.exit(1)
} else {
  console.log('\n✅ 全部在预算内。')
  process.exit(0)
}