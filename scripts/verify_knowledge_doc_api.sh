#!/usr/bin/env bash
# =====================================================================
# 知识文档 CRUD + 生命周期 API 验证（6.21 / 6.22）
#
# 背景：6.22 前端去 mock 接入后端文档 CRUD 时联调发现并修复：
#   ① create 响应把 indexOutcome 塞进 status，缺 indexStatus（状态机混淆）
#   ② restore 恢复已废弃文档时因内容未变跳过重建向量 →
#      显示 PUBLISHED 实则 indexStatus=SKIPPED / chunkCount=0，不可检索
#   ③ versions 返回 snake_case / 被 PostgreSQL 折叠成小写，前端拿不到字段
#
# 用法：bash scripts/verify_knowledge_doc_api.sh
#       前置：后端已启动（建议 MOCK 模式），POSTGRES 可用
# =====================================================================

BASE="http://localhost:8088/ai/api/v1/knowledge/docs"
G='\033[92m'; R='\033[91m'; Y='\033[93m'; B='\033[94m'; RST='\033[0m'

PASS=0; FAIL=0
ok()   { echo -e "  ${G}✓ $1${RST}"; PASS=$((PASS+1)); }
bad()  { echo -e "  ${R}✗ $1${RST}"; FAIL=$((FAIL+1)); }
info() { echo -e "  ${Y}ℹ $1${RST}"; }

# JSON 字段提取（数组 data 内）
jstr()  { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }
jnum()  { echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed "s/\"$2\"://"; }
code()  { echo "$1" | grep -o '"code":[0-9]*' | head -1 | sed 's/"code"://'; }
hasc()  { echo "$1" | grep -q "\"$2\"" && echo yes || echo no; }
# GET 带查询参数：--data-urlencode 保证中文/特殊字符正确编码（否则 Tomcat 400）
getq()  { curl -s -G "$1" "${@:2}"; }

# 中文 tag 值必须经文件传递：Git Bash 下内联中文被转成 GBK 字节，
# 服务端按 UTF-8 解码成乱码 → 筛选恒不命中（与 6.12 内联 JSON 同款陷阱）。
# Windows curl 读不了 MSYS 绝对路径，故 cd 进 TMPD 用相对文件名（6.14 同款）。
tagq()  { # $1=tag 值，其余透传给 curl
  printf '%s' "$1" > "$TMPD/tagval.txt"
  ( cd "$TMPD" && curl -s -G "$BASE" --data-urlencode "tag@tagval.txt" "${@:2}" )
}

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT

post() { curl -s -X POST -H "Content-Type: application/json; charset=utf-8" --data-binary @"$2" "$1"; }
put()  { curl -s -X PUT  -H "Content-Type: application/json; charset=utf-8" --data-binary @"$2" "$1"; }
get()  { curl -s "$1"; }

echo "======================================================================"
echo "知识文档 CRUD + 生命周期 API 验证"
echo "======================================================================"

SUF=$RANDOM

# ---- 场景 1：分类聚合 / 热门标签 ----
echo -e "\n${B}▶ 场景 1：categories / tags/hot 端点可用${RST}"
C1=$(get "$BASE/categories")
[ "$(code "$C1")" = "0" ] && ok "categories 返回 code:0" || bad "categories 异常：$C1"
H1=$(get "$BASE/tags/hot?limit=20")
[ "$(code "$H1")" = "0" ] && ok "tags/hot 返回 code:0" || bad "tags/hot 异常：$H1"

# ---- 场景 2：新建发布，契约正确（status 与 indexStatus 分离）----
echo -e "\n${B}▶ 场景 2：新建发布 → status=PUBLISHED / indexStatus=INDEXED${RST}"
cat > "$TMPD/c2.json" <<EOF
{
  "title": "验证文档-$SUF",
  "category": "验证分类-$SUF",
  "content": "# 验证内容-$SUF\n\n用于 verify_knowledge_doc_api.sh 场景测试，内容唯一。",
  "tags": ["验证标签-$SUF"],
  "publish": true
}
EOF
C2=$(post "$BASE" "$TMPD/c2.json")
[ "$(code "$C2")" = "0" ] || { bad "创建失败：$C2"; exit 1; }
DID=$(jnum "$C2" "id")
STS=$(jstr "$C2" "status")
IXS=$(jstr "$C2" "indexStatus")
[ "$STS" = "PUBLISHED" ] && ok "status=PUBLISHED" || bad "status=$STS（期望 PUBLISHED，契约混淆）"
[ "$IXS" = "INDEXED" ] && ok "indexStatus=INDEXED" || bad "indexStatus=$IXS（期望 INDEXED）"
info "新文档 id=$DID"

# ---- 场景 3：tag 筛选 ----
echo -e "\n${B}▶ 场景 3：?tag=xx 筛选命中 / 不存在的标签为空${RST}"
C3=$(tagq "验证标签-$SUF" --data-urlencode "size=50")
[ "$(jnum "$C3" "totalElements")" -ge 1 ] && ok "命中带标签文档" || bad "未命中：$C3"
C4=$(tagq "不存在的标签-$SUF" --data-urlencode "size=50")
[ "$(jnum "$C4" "totalElements")" = "0" ] && ok "不存在的标签返回 0 条" || bad "应返回 0 条：$C4"

# ---- 场景 4：重复内容 → 40021 + duplicateDocId ----
echo -e "\n${B}▶ 场景 4：重复内容被拒 40021 且带 duplicateDocId${RST}"
C5=$(post "$BASE" "$TMPD/c2.json")
C5C=$(code "$C5")
[ "$C5C" = "40021" ] && ok "返回 40021" || bad "code=$C5C（期望 40021）"
if [ "$C5C" = "40021" ]; then
  DUPDOC=$(jnum "$C5" "duplicateDocId")
  [ "$DUPDOC" = "$DID" ] && ok "data.duplicateDocId=$DID 正确下发给前端" || bad "duplicateDocId=$DUPDOC（期望 $DID）"
fi

# ---- 场景 5：更新 → 版本自增 + 响应含 status ----
echo -e "\n${B}▶ 场景 5：更新（带 version CAS）→ version 自增 + status 字段${RST}"
cat > "$TMPD/c5.json" <<EOF
{"summary":"更新后的摘要-$SUF","version":1,"changeReason":"验证更新"}
EOF
C6=$(put "$BASE/$DID" "$TMPD/c5.json")
[ "$(code "$C6")" = "0" ] && ok "更新成功" || bad "更新失败：$C6"
[ "$(jnum "$C6" "version")" = "2" ] && ok "version 1→2" || bad "version=$(jnum "$C6" "version")（期望 2）"
[ "$(jstr "$C6" "status")" = "PUBLISHED" ] && ok "响应含 status=PUBLISHED" || bad "缺 status 或非 PUBLISHED：$C6"

# ---- 场景 6：废弃 → DEPRECATED + SKIPPED + chunkCount=0 ----
echo -e "\n${B}▶ 场景 6：废弃（默认删除语义）→ 留正文删向量${RST}"
printf '{}' > "$TMPD/c6.json"
C7=$(post "$BASE/$DID/deprecate" "$TMPD/c6.json")
[ "$(code "$C7")" = "0" ] && ok "废弃成功" || bad "废弃失败：$C7"
C7D=$(get "$BASE/$DID")
[ "$(jstr "$C7D" "status")" = "DEPRECATED" ] && ok "status=DEPRECATED" || bad "status=$(jstr "$C7D" "status")"
[ "$(jstr "$C7D" "indexStatus")" = "SKIPPED" ] && ok "indexStatus=SKIPPED" || bad "indexStatus=$(jstr "$C7D" "indexStatus")"
[ "$(jnum "$C7D" "chunkCount")" = "0" ] && ok "chunkCount=0（向量已删）" || bad "chunkCount=$(jnum "$C7D" "chunkCount")"

# ---- 场景 7：恢复 → 必须重建向量（回归：曾因 contentChanged=false 跳过）----
echo -e "\n${B}▶ 场景 7：restore → 重建向量 → PUBLISHED/INDEXED/chunkCount>0${RST}"
cat > "$TMPD/c7.json" <<EOF
{"version":1}
EOF
C8=$(post "$BASE/$DID/restore" "$TMPD/c7.json")
[ "$(code "$C8")" = "0" ] && ok "restore 成功" || bad "restore 失败：$C8"
C8D=$(get "$BASE/$DID")
[ "$(jstr "$C8D" "status")" = "PUBLISHED" ] && ok "status=PUBLISHED" || bad "status=$(jstr "$C8D" "status")"
[ "$(jstr "$C8D" "indexStatus")" = "INDEXED" ] && ok "indexStatus=INDEXED（重建向量回归修复）" || bad "indexStatus=$(jstr "$C8D" "indexStatus")——废弃后恢复必须重建索引"
[ "$(jnum "$C8D" "chunkCount")" -ge 1 ] && ok "chunkCount=$(jnum "$C8D" "chunkCount")" || bad "chunkCount=0，恢复后不可检索"

# ---- 场景 8：版本历史 camelCase 契约 ----
echo -e "\n${B}▶ 场景 8：versions 返回 camelCase 键（前端契约）${RST}"
C9=$(get "$BASE/$DID/versions")
[ "$(code "$C9")" = "0" ] && ok "versions 可用" || bad "versions 异常：$C9"
[ "$(hasc "$C9" 'changeType')" = "yes" ] && ok "changeType 为 camelCase" || bad "缺 changeType 或仍为 snake_case：$C9"
[ "$(hasc "$C9" 'createTime')" = "yes" ] && ok "createTime 为 camelCase" || bad "缺 createTime 或仍为 snake_case：$C9"

# ---- 场景 9：物理删除（合规理由）+ 级联清理 ----
echo -e "\n${B}▶ 场景 9：purge 需合规理由；无理由被拒${RST}"
C10=$(curl -s -X DELETE "$BASE/$DID/purge")
[ "$(code "$C10")" = "40001" ] && ok "无合规理由被拒 40001" || bad "应拒绝无理由删除：$C10"
cat > "$TMPD/c9.json" <<EOF
{"complianceReason":"verify 脚本清理测试数据"}
EOF
C11=$(curl -s -X DELETE -H "Content-Type: application/json; charset=utf-8" --data-binary @"$TMPD/c9.json" "$BASE/$DID/purge")
[ "$(code "$C11")" = "0" ] && ok "带理由删除成功，级联清理" || bad "删除失败：$C11"

echo "======================================================================"
echo -e "结果：${G}通过 $PASS${RST} / ${R}失败 $FAIL${RST}"
[ "$FAIL" = "0" ] && echo -e "${G}全部通过 ✔${RST}" || echo -e "${R}存在失败 ✘${RST}"
exit $FAIL
