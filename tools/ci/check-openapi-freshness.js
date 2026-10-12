#!/usr/bin/env node
/**
 * OpenAPI 快照新鲜度对账（批88 · 契约三闸的「底」之闸）。
 *
 * 对账双方：
 *   A = target/openapi.json —— OpenApiSchemaIntegrationTest 每次 mvn verify
 *       导出的**当次代码** springdoc 活体反射（后端真身）；
 *   B = openapi/api-docs.snapshot.json —— 随库快照（apiContract 前端↔快照
 *       闸、fieldContract 手写↔生成闸、orval 生成 全部以它为底）。
 *
 * 两维结构对账（红只认结构差，描述/示例文案变更不红——已知边界入批88档）：
 *   1. METHOD+path 集合双向差集（端点增删改名）；
 *   2. components.schemas 属性名集合双向差集（字段增删——全 schema 覆盖，
 *      正是 fieldContract 29 对之外的盲区补面）。
 *
 * 触发场景：后端改了契约（加端点/加字段）没跑 `npm run api:refresh` → 红。
 * 修法：node tools/ci/sync-openapi.mjs（或手动同步快照）→ npm run gen:api →
 * 提交快照+生成物；手写类型若有漂移，typecheck 的 fieldContract 会点名。
 *
 * A 缺失 = 硬失败（测试没导出 = 链路坏，宁红不静默跳过）。
 * 用法：node tools/ci/check-openapi-freshness.js   （cwd = 仓库根）
 */
'use strict';
const fs = require('fs');
const path = require('path');

const LIVE = path.join(__dirname, '..', '..', 'target', 'openapi.json');
const SNAPSHOT = path.join(__dirname, '..', '..', 'openapi', 'api-docs.snapshot.json');
const VERBS = ['get', 'post', 'put', 'delete', 'patch', 'head', 'options'];

function load(p, name) {
  if (!fs.existsSync(p)) {
    console.error(`✗ ${name} 不存在：${p}`);
    console.error(name.startsWith('LIVE')
      ? '  上一步 OpenApiSchemaIntegrationTest 未导出——测试被跳过或 mvn verify 没跑全量。'
      : '  快照缺失——先 node tools/ci/sync-openapi.mjs（需 dev 后端 8088 活着）。');
    process.exit(1);
  }
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

function endpoints(doc) {
  const set = new Set();
  for (const [p, item] of Object.entries(doc.paths || {})) {
    for (const m of Object.keys(item)) {
      if (VERBS.includes(m.toLowerCase())) set.add(`${m.toUpperCase()} ${p}`);
    }
  }
  return set;
}

function schemaProps(doc) {
  const map = new Map();
  for (const [name, schema] of Object.entries((doc.components || {}).schemas || {})) {
    map.set(name, Object.keys(schema.properties || {}).sort().join(','));
  }
  return map;
}

function diffSets(a, b) {
  const onlyInA = [...a].filter(x => !b.has(x)).sort();
  const onlyInB = [...b].filter(x => !a.has(x)).sort();
  return { onlyInA, onlyInB };
}

const live = load(LIVE, 'LIVE(target/openapi.json)');
const snap = load(SNAPSHOT, 'SNAPSHOT(api-docs.snapshot.json)');

let failed = false;

// ── 维度一：端点集 ──
const ep = diffSets(endpoints(live), endpoints(snap));
if (ep.onlyInA.length || ep.onlyInB.length) {
  failed = true;
  console.error('✗ 端点集漂移（后端当次代码 vs 随库快照）：');
  for (const x of ep.onlyInA) console.error(`    后端有、快照缺：${x}`);
  for (const x of ep.onlyInB) console.error(`    快照有、后端已无：${x}`);
}

// ── 维度二：schema 属性集（全 components 覆盖，不只 fieldContract 的对）──
const lp = schemaProps(live);
const sp = schemaProps(snap);
const names = [...new Set([...lp.keys(), ...sp.keys()])].sort();
let propDrift = 0;
for (const n of names) {
  if (!lp.has(n)) { failed = true; propDrift++; console.error(`    schema 快照有、后端已无：${n}`); continue; }
  if (!sp.has(n)) { failed = true; propDrift++; console.error(`    schema 后端有、快照缺：${n}`); continue; }
  const d = diffSets(new Set(lp.get(n) ? lp.get(n).split(',').filter(Boolean) : []),
                    new Set(sp.get(n) ? sp.get(n).split(',').filter(Boolean) : []));
  if (d.onlyInA.length || d.onlyInB.length) {
    failed = true; propDrift++;
    console.error(`    schema 属性漂移 ${n}：后端多[${d.onlyInA.join(',')}] 快照多[${d.onlyInB.join(',')}]`);
  }
}
if (propDrift) console.error('  （字段增删=fieldContract 会同步点名手写类型）');

if (failed) {
  console.error('');
  console.error('✗ 快照过期：后端契约已变，随库快照没跟。修法（仓库根）：');
  console.error('    node tools/ci/sync-openapi.mjs  # 需 dev 后端 8088 活着');
  console.error('    npm run gen:api                 # 刷新生成物');
  console.error('    # typecheck 红=手写类型漂移（fieldContract 点名字段），补/修后一并提交');
  process.exit(1);
}
console.log(`✓ 快照新鲜：端点 ${endpoints(live).size} 条、schema ${lp.size} 个（path/method + 属性集双维一致）`);
