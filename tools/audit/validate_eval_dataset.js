#!/usr/bin/env node
/**
 * eval_dataset.json 校验器（路线图 §8.1 4-1.6）。
 *
 * 两档模式：
 *   --mode=schema     结构校验：五核心字段齐、id 全局唯一、type 域合法。
 *                     这是当前数据集必须全通过的底线（CI 接入前本地先跑）。
 *   --mode=annotated  注记校验：在 schema 之上追加——
 *                     · 若条目带 expectedDocs，则必须是非空字符串数组（半成品注记视为错误）；
 *                     · 若条目带 expectedRootCause，则必须是非空字符串；
 *                     · 汇总报告注记覆盖率（供 4-1.5 扩集批次追踪进度）。
 *                     注记字段暂不强制每条必带（渐进注记制，欠账口径见报告 117 §四）。
 *
 * 退出码：0=通过，1=有结构错误。注记覆盖率为 0 在 annotated 模式下也只警告——
 * 防伪造优先于防空转：全量注记是内容工作，由人工/E​VAL_LLM 首跑回填，不由脚本代笔。
 *
 * 用法：node tools/audit/validate_eval_dataset.js [--mode=schema|annotated] [数据集路径]
 */

const fs = require('fs')

const args = process.argv.slice(2)
const modeArg = args.find((a) => a.startsWith('--mode='))
const mode = modeArg ? modeArg.split('=')[1] : 'schema'
const datasetPath = args.find((a) => !a.startsWith('--')) || 'src/test/resources/eval_dataset.json'

const VALID_TYPE = (t) => t === 'POSITIVE' || (typeof t === 'string' && t.startsWith('NEGATIVE_'))

function main() {
  const raw = fs.readFileSync(datasetPath, 'utf-8')
  const items = JSON.parse(raw)
  if (!Array.isArray(items) || items.length === 0) {
    console.error('✗ 数据集为空或不是数组')
    process.exit(1)
  }

  const errors = []
  const ids = new Set()
  let annotatedDocs = 0
  let annotatedRootCause = 0

  items.forEach((it, idx) => {
    const tag = `第 ${idx + 1} 条 (id=${it && it.id})`
    if (typeof it.id !== 'number' || !Number.isInteger(it.id)) errors.push(`${tag}：id 缺失或非整数`)
    if (ids.has(it.id)) errors.push(`${tag}：id 重复`)
    ids.add(it.id)
    if (!VALID_TYPE(it.type)) errors.push(`${tag}：type 非法「${it.type}」（须 POSITIVE 或 NEGATIVE_*）`)
    if (typeof it.query !== 'string' || it.query.trim() === '') errors.push(`${tag}：query 缺失或空`)
    if (!Array.isArray(it.expectedKeywords)) errors.push(`${tag}：expectedKeywords 缺失或非数组`)
    if (typeof it.shouldTriggerFallback !== 'boolean') errors.push(`${tag}：shouldTriggerFallback 缺失或非布尔`)

    if (mode === 'annotated') {
      if (it.expectedDocs !== undefined) {
        if (!Array.isArray(it.expectedDocs) || it.expectedDocs.length === 0
          || it.expectedDocs.some((d) => typeof d !== 'string' || d.trim() === '')) {
          errors.push(`${tag}：expectedDocs 已出现但为空/含非字符串——半成品注记视为错误`)
        } else {
          annotatedDocs++
        }
      }
      if (it.expectedRootCause !== undefined) {
        if (typeof it.expectedRootCause !== 'string' || it.expectedRootCause.trim() === '') {
          errors.push(`${tag}：expectedRootCause 已出现但为空——半成品注记视为错误`)
        } else {
          annotatedRootCause++
        }
      }
    }
  })

  const positives = items.filter((i) => i.type === 'POSITIVE')
  const negatives = items.length - positives.length

  console.log(`数据集：${datasetPath}`)
  console.log(`条目：${items.length}（正例 ${positives.length} / 负例 ${negatives}）`)
  if (mode === 'annotated') {
    console.log(`expectedDocs 注记：${annotatedDocs} 条（占正例 ${pct(annotatedDocs, positives.length)}）`)
    console.log(`expectedRootCause 注记：${annotatedRootCause} 条`)
    if (annotatedDocs === 0) {
      console.warn('⚠ 注记覆盖率为 0：排序指标无可评样本（留空拒伪造口径——见报告 117 §四）')
    }
  }

  if (errors.length) {
    console.error(`\n✗ ${errors.length} 处结构错误：`)
    errors.slice(0, 20).forEach((e) => console.error('  - ' + e))
    process.exit(1)
  }
  console.log(`✓ ${mode} 校验通过`)
}

const pct = (a, b) => (b === 0 ? '—' : ((a / b) * 100).toFixed(1) + '%')

main()
