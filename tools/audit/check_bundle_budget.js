/**
 * check_bundle_budget.js —— F-4 构建体积预算门禁（报告 135，批次 32）
 *
 * 语义：防止 F-2 语言包裁剪（4890→4227KB）的成果静默回弹。
 *   - 总量超预算 → RC=1
 *   - 任一单件超单件预算 → RC=1
 *   - --update 用量弹意：把当前实测写成新预算文件（抬预算必须随 commit 写理由，
 *     与 eval_compare.js 的 --update 同一条审计戒律，预算不自动跟随）
 *
 * 口径红线：未压缩字节（与构建产物一致），勿改成 gzip——gzip 换一种压缩器就变，
 * 未压缩字节是唯一与构建工具同源的稳定口径。只统计 JS/CSS/字体三类（图片/HTML
 * 不进本账本——它们有自己别的瘦身议程，别把两类债搅在一个闸里）。
 *
 * 用法：node tools/audit/check_bundle_budget.js [--update]
 * 运行环境：dist 已构建（CI 中接在「生产构建」之后；本地五闸升级为六闸时的第六闸）。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const DIST = path.join(ROOT, 'devops-platform-frontend', 'dist');
const BUDGET_PATH = path.join(ROOT, 'tools', 'audit', 'bundle_budget.json');
const UPDATE = process.argv.includes('--update');

const walk = (d) =>
  fs.readdirSync(d, { withFileTypes: true }).flatMap((f) =>
    f.isDirectory() ? walk(path.join(d, f.name)) : path.join(d, f.name));

function main() {
  if (!fs.existsSync(DIST)) {
    console.error(`✗ dist 不存在：${DIST}——先 npm run build 再量体积（别拿空气当绿灯）`);
    process.exit(1);
  }
  const budget = JSON.parse(fs.readFileSync(BUDGET_PATH, 'utf-8'));
  const exts = new Set(budget.extensions);

  const assets = walk(DIST).filter((f) => exts.has(path.extname(f).toLowerCase()));
  const rows = assets
    .map((f) => ({ file: path.relative(DIST, f), kb: fs.statSync(f).size / 1024 }))
    .sort((a, b) => b.kb - a.kb);
  const totalKb = rows.reduce((a, r) => a + r.kb, 0);
  const maxAsset = rows[0] ?? { file: '(无)', kb: 0 };

  if (UPDATE) {
    const next = {
      ...budget,
      totalKb: Math.ceil(totalKb * 1.09),
      maxAssetKb: Math.ceil(maxAsset.kb * 1.15),
      anchor: {
        date: new Date().toISOString().slice(0, 10),
        totalKb: Math.round(totalKb),
        maxAssetKb: Math.round(maxAsset.kb),
        maxAsset: maxAsset.file.replace(/-[A-Za-z0-9_-]{6,}\.(\w+)$/, '-*.$1'),
        baselineNote: budget.anchor.baselineNote,
      },
    };
    fs.writeFileSync(BUDGET_PATH, JSON.stringify(next, null, 2) + '\n');
    console.log(`✓ 预算已回写：${BUDGET_PATH}（总量 ⌈实测×1.09⌉ / 单件 ⌈实测×1.15⌉）`);
    console.log('⚠ 抬预算是审计事件：commit 信息必须写明理由，否则预算闸形同虚设。');
    return;
  }

  console.log(`体积量纲：未压缩字节（JS/CSS/字体），dist=${path.relative(ROOT, DIST)}`);
  console.log(`总量：${totalKb.toFixed(0)}KB / 预算 ${budget.totalKb}KB（锚点 ${budget.anchor.date}：${budget.anchor.totalKb}KB）`);
  console.log(`单件峰值：${maxAsset.file} ${maxAsset.kb.toFixed(0)}KB / 预算 ${budget.maxAssetKb}KB`);
  console.log('TOP8：');
  rows.slice(0, 8).forEach((r) => console.log(`  ${r.kb.toFixed(0).padStart(5)}KB  ${r.file}`));

  const failures = [];
  if (totalKb > budget.totalKb) failures.push(`总量超阈：${totalKb.toFixed(0)}KB > ${budget.totalKb}KB`);
  const overAssets = rows.filter((r) => r.kb > budget.maxAssetKb);
  if (overAssets.length) failures.push(`单件超阈 ${overAssets.length} 个：${overAssets.map((r) => `${r.file}(${r.kb.toFixed(0)}KB)`).join('、')}`);

  if (failures.length) {
    console.error('\n✗ 体积预算红线：');
    failures.forEach((f) => console.error(`  - ${f}`));
    console.error('处置二选一：拆包/裁剪回预算内；或确属新工艺增量时用 --update 抬预算并写理由。');
    process.exit(1);
  }
  console.log('\n✓ 体积预算通过');
}

main();
