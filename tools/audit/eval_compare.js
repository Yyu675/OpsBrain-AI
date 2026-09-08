#!/usr/bin/env node
/**
 * 评测基线对比（路线图 §8.3 4-3.2/4-3.3 的可自驱半部）。
 *
 * 对比 target/eval-metrics.json（本次）与基线 JSON（上次验收），
 * 任一共同指标劣化超阈值（默认 0.02 绝对值）即退出码 1。
 *
 * 用法：
 *   node tools/audit/eval_compare.js --current target/eval-metrics.json \
 *       --baseline tools/audit/eval_baseline.json [--threshold 0.02]
 *   node tools/audit/eval_compare.js --current ... --baseline ... --update
 *
 * 语义红线（防假绿/假红）：
 *   · 只比「双方都有的指标」——层缺失（env 门没开）只警告不判负，
 *     否则 RAG 层在纯契约层运行里会被永远误报劣化（与 EvalMetricsWriter 的
 *     合并语义互为表里）；
 *   · 比率类指标比绝对差（0.95 → 0.90 = -5pt）；计数类指标（整数且名含
 *     Total/leaked/blocked/annotated）同样比绝对差，但 leaked/blocked
 *     方向相反——漏拦截增多就是劣化；
 *   · --update 把当前文件整体另存为基线（4-3.4 手动更新基线的本地实现；
 *     CI 侧已由 .github/workflows/eval-baseline-update.yml 接线：人工派单、
 *     先亮账后回写、劣化即中止——本文件只负责「比」与「另存」，搬运编排在那里）。
 */

const fs = require('fs')

const args = process.argv.slice(2)
const opt = (name, dflt) => {
  const i = args.indexOf('--' + name)
  return i >= 0 ? args[i + 1] : dflt
}
const CURRENT = opt('current', 'target/eval-metrics.json')
const BASELINE = opt('baseline', 'tools/audit/eval_baseline.json')
const THRESHOLD = parseFloat(opt('threshold', '0.02'))
const UPDATE = args.includes('--update')

/** 计数类键（非比率）：方向各异，不能用同一套「下降=劣化」 */
// hallucinated 入计数域：整数百差显示、阈 0；注意必须写成完整词形
// ——「hallucinat」前缀留给 REVERSE 共用，放计数域会因子串重复反而掩盖域归属错误。
const COUNT_KEYS = /Total|annotated|leaked|blocked|hallucinated/i
// 「上升=劣化」键：leaked/blocked/hallucinated 属计数域（零容忍）；
// hallucinationRate 属比率域（阈 ±THRESHOLD）——幻觉率类指标方向与命中率相反。
// 词根用 hallucina：同时罩住 hallucinated（计数）与 hallucinationRate（比率），
// 夹具实证过「hallucinated 不含 hallucination 子串（差 io）」的误分类坑。
const REVERSE_COUNT_KEYS = /leaked|blocked|hallucina/i

const flat = (obj, prefix = '') =>
  Object.entries(obj).flatMap(([k, v]) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? flat(v, `${prefix}${k}.`)
      : [[`${prefix}${k}`, v]])

function main() {
  if (!fs.existsSync(CURRENT)) {
    console.error(`✗ 当前评测文件不存在：${CURRENT}（评测未跑或落盘失败）`)
    process.exit(1)
  }
  const current = JSON.parse(fs.readFileSync(CURRENT, 'utf-8'))

  if (UPDATE) {
    fs.mkdirSync(require('path').dirname(BASELINE), { recursive: true })
    fs.copyFileSync(CURRENT, BASELINE)
    console.log(`✓ 基线已更新：${BASELINE}（自 ${CURRENT}）`)
    return
  }

  if (!fs.existsSync(BASELINE)) {
    // 首跑无基线：这不是失败——4-3.2 约定「首次运行结果即 baseline」
    console.warn(`⚠ 基线不存在：${BASELINE}——首跑语义：请用 --update 落基线后再比较（本趟退出码 0）`)
    return
  }
  const baseline = JSON.parse(fs.readFileSync(BASELINE, 'utf-8'))

  const curMap = new Map(flat(current.layers ?? {}))
  const baseMap = new Map(flat(baseline.layers ?? {}))

  const rows = []
  const failures = []
  const skipped = []

  for (const [key, baseVal] of baseMap) {
    const curVal = curMap.get(key)
    if (curVal === undefined || typeof baseVal !== 'number' || typeof curVal !== 'number') {
      skipped.push(key)
      continue
    }
    const delta = curVal - baseVal
    const isCount = COUNT_KEYS.test(key)
    const degraded = REVERSE_COUNT_KEYS.test(key)
      ? delta > (isCount ? 0 : THRESHOLD)          // 漏拦截/误伤：增加即劣化（计数零容忍）
      : delta < -(isCount ? 0 : THRESHOLD)         // 比率与正例计数：下降超阈即劣化
    rows.push({ key, baseVal, curVal, delta, degraded })
    if (degraded) failures.push({ key, baseVal, curVal, delta })
  }
  // 只存在于 current 的新指标：记录但不当失败（新增指标首跑无基线可比）
  for (const key of curMap.keys()) {
    if (!baseMap.has(key)) skipped.push(key + '（新增）')
  }

  const fmt = (v) => (typeof v === 'number' && !Number.isInteger(v) ? (v * 100).toFixed(1) + '%' : String(v))
  console.log(`对比基线：${BASELINE}\n当前值：${CURRENT}\n阈值：±${(THRESHOLD * 100).toFixed(0)}pt（比率类）\n`)
  for (const r of rows) {
    const sign = r.delta > 0 ? '+' : ''
    const show = (n) => (COUNT_KEYS.test(r.key) ? String(n) : fmt(n))
    console.log(
      `${r.degraded ? '✗' : '✓'} ${r.key.padEnd(28)} ${show(r.baseVal)} → ${show(r.curVal)} (${sign}${COUNT_KEYS.test(r.key) ? r.delta : (r.delta * 100).toFixed(1) + 'pt'})`)
  }
  if (skipped.length) {
    console.log(`\n跳项（单侧缺席，不参评）：${skipped.join('、')}`)
  }

  if (failures.length) {
    console.error(`\n✗ ${failures.length} 项指标劣化超阈值——回归红线：`)
    failures.forEach((f) => console.error(`  - ${f.key}: ${f.baseVal} → ${f.curVal}`))
    process.exit(1)
  }
  console.log('\n✓ 无劣化超阈指标')
}

main()
