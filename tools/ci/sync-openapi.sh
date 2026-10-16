#!/usr/bin/env bash
# ⚠ 已退役（批90，2026-10-12）：请用 node tools/ci/sync-openapi.mjs。
# 退役原因：Windows/WSL interop 损坏时本脚本**静默空跑**（实测）——快照同步
# 这类契约闸门绝不能静默失败；node 版逐字保留三重硬拒口径且全平台一致。
# 保留本文件仅因历史文档引用可达；不再维护。
#
# 项3（2026-10-03）：OpenAPI 快照同步——把 springdoc 反射出的契约（含每个 path 的
# HTTP 方法）固化成仓内工件，供前端 apiContract 测试做「方法级」交叉验证。
#
# 用法（dev 后端在 8088 活着时）：bash tools/ci/sync-openapi.sh
# 产物：openapi/api-docs.snapshot.json（提交入库——控制器或前端端点变动后重跑本脚本）
#
# 口径：
# - dev 后端 SPRINGDOC_ENABLED 默认 true（application.yml 注释：生产 application-prod.yml 关闭）；
# - context-path=/ai → 快照取 /ai/v3/api-docs；
# - 空壳/非 JSON 一律硬失败，防把 404 页提交成契约。
set -euo pipefail
cd "$(dirname "$0")/../.."

URL="http://localhost:8088/ai/v3/api-docs"
OUT="openapi/api-docs.snapshot.json"
TMP=$(mktemp)

mkdir -p openapi
curl -fsS --max-time 15 "$URL" -o "$TMP"

# 空体/非 JSON/缺 paths 全拒——快照是契约工件，宁缺毋假
BYTES=$(wc -c < "$TMP")
[ "$BYTES" -gt 1024 ] || { echo "❌ 快照仅 ${BYTES}B，疑似非契约体（$URL）" >&2; rm -f "$TMP"; exit 1; }
node -e "
const s = require('fs').readFileSync(process.argv[1], 'utf8');
const j = JSON.parse(s);
const paths = Object.keys(j.paths || {});
if (paths.length < 20) { console.error('❌ paths 仅 ' + paths.length + ' 条，springdoc 反射异常'); process.exit(1); }
const verbful = paths.filter(p => Object.keys(j.paths[p]).some(m => ['get','post','put','delete','patch'].includes(m.toLowerCase())));
if (verbful.length !== paths.length) { console.error('❌ ' + (paths.length - verbful.length) + ' 条 path 无任何 HTTP 方法'); process.exit(1); }
console.log('✓ paths=' + paths.length + ' 全带方法');
" "$TMP"

mv "$TMP" "$OUT"
echo "✓ 快照落盘 $OUT（$(wc -c < "$OUT")B）——记得 git add 提交"
