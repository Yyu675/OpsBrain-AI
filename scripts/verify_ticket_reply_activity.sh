#!/usr/bin/env bash
# =====================================================================
# 工单回复与活动流持久化验证
#
# 背景：sys_ticket_reply / sys_ticket_activity 两张表存在于 DDL，
#       但后端零实现，前端 appendReply 只写 Pinia 内存 —— 刷新即丢失。
#
# 本脚本验证补齐后的持久化链路，以及既有写操作是否自动留痕。
#
# 用法：bash scripts/verify_ticket_reply_activity.sh
# =====================================================================

BASE="http://localhost:8088/ai/api/v1/tickets"
G='\033[92m'; R='\033[91m'; Y='\033[93m'; B='\033[94m'; RST='\033[0m'

PASS=0; FAIL=0
ok()   { echo -e "  ${G}✓ $1${RST}"; PASS=$((PASS+1)); }
bad()  { echo -e "  ${R}✗ $1${RST}"; FAIL=$((FAIL+1)); }
info() { echo -e "  ${Y}ℹ $1${RST}"; }

jstr() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }
jnum() { echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed "s/\"$2\"://"; }
# 统计 JSON 数组中某字段出现次数
jcount() { echo "$1" | grep -o "\"$2\":" | wc -l | tr -d ' '; }

echo "======================================================================"
echo "工单回复与活动流持久化验证"
echo "======================================================================"

# Windows Git Bash 内联中文会被转成非 UTF-8 字节，
# 必须写文件再用 --data-binary 传递
post_json() {
  local url="$1" file="$2"
  curl -s -X POST -H "Content-Type: application/json; charset=utf-8" \
    --data-binary @"$file" "$url"
}
patch_json() {
  local url="$1" file="$2"
  curl -s -X PATCH -H "Content-Type: application/json; charset=utf-8" \
    --data-binary @"$file" "$url"
}
put_json() {
  local url="$1" file="$2"
  curl -s -X PUT -H "Content-Type: application/json; charset=utf-8" \
    --data-binary @"$file" "$url"
}

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT

# ---- 准备 ----
echo -e "\n${B}▶ 准备：创建工单${RST}"
cat > "$TMPD/create.json" <<'EOF'
{
  "title": "回复与活动流持久化验证",
  "priority": "MEDIUM",
  "module": "MYSQL",
  "description": "验证回复落库、活动流自动留痕、级联清理",
  "assignee": "张明"
}
EOF
CREATED=$(post_json "$BASE" "$TMPD/create.json")
TID=$(jstr "$CREATED" "id")
[ -z "$TID" ] && { bad "创建失败：$CREATED"; exit 1; }
info "工单号=$TID"

# ---- 场景 1：创建即产生活动流 ----
echo -e "\n${B}▶ 场景 1：创建工单是否自动留痕${RST}"
ACT1=$(curl -s "$BASE/$TID/activities")
N_ACT1=$(jcount "$ACT1" "text")
if [ "$N_ACT1" -ge 2 ]; then
  ok "创建产生 $N_ACT1 条活动（工单创建 + 负责人分配）"
  info "$(echo "$ACT1" | grep -o '"text":"[^"]*"' | sed 's/"text":"//;s/"$//' | paste -sd' | ' -)"
else
  bad "创建应产生 ≥2 条活动，实际 $N_ACT1"
fi

# ---- 场景 2：回复落库 ----
echo -e "\n${B}▶ 场景 2：回复是否真正落库${RST}"
cat > "$TMPD/reply1.json" <<'EOF'
{
  "role": "agent",
  "author": "张明",
  "authorColor": "#3B82F6",
  "content": "已排查，确认为大事务导致，正在处理"
}
EOF
REPLY=$(post_json "$BASE/$TID/replies" "$TMPD/reply1.json")
RID=$(jnum "$REPLY" "id")
if [ -n "$RID" ] && [ "$RID" != "0" ]; then
  ok "回复已入库（id=$RID）"
else
  bad "回复入库失败：$(echo "$REPLY" | head -c 200)"
fi

# 再发一条提单人回复
cat > "$TMPD/reply2.json" <<'EOF'
{
  "role": "creator",
  "author": "王磊",
  "content": "收到，请尽快"
}
EOF
post_json "$BASE/$TID/replies" "$TMPD/reply2.json" > /dev/null

# ---- 场景 3：回复可查回（模拟刷新页面）----
echo -e "\n${B}▶ 场景 3：刷新后回复是否仍在（核心缺陷验证）${RST}"
REPLIES=$(curl -s "$BASE/$TID/replies")
N_REP=$(jcount "$REPLIES" "content")
if [ "$N_REP" = "2" ]; then
  ok "查回 2 条回复，刷新不再丢失"
  info "角色顺序：$(echo "$REPLIES" | grep -o '"role":"[^"]*"' | sed 's/"role":"//;s/"$//' | paste -sd' → ' -)"
else
  bad "应查回 2 条回复，实际 $N_REP"
fi

# ---- 场景 4：回复产生活动流 ----
echo -e "\n${B}▶ 场景 4：回复是否产生活动流${RST}"
ACT2=$(curl -s "$BASE/$TID/activities")
N_ACT2=$(jcount "$ACT2" "text")
if [ "$N_ACT2" -gt "$N_ACT1" ]; then
  ok "活动流由 $N_ACT1 增至 $N_ACT2 条（含 2 条回复记录）"
else
  bad "回复应产生活动流，数量未增加（$N_ACT1 → $N_ACT2）"
fi

# ---- 场景 5：状态变更自动留痕（中文标签）----
echo -e "\n${B}▶ 场景 5：状态变更是否自动留痕${RST}"
echo '{"status":"PROCESSING"}' > "$TMPD/st.json"
patch_json "$BASE/$TID/status" "$TMPD/st.json" > /dev/null
ACT3=$(curl -s "$BASE/$TID/activities")
if echo "$ACT3" | grep -q "状态变更"; then
  ok "状态变更已留痕"
  DETAIL=$(echo "$ACT3" | grep -o '"detail":"[^"]*→[^"]*"' | head -1 | sed 's/"detail":"//;s/"$//')
  info "详情：$DETAIL"
  if echo "$DETAIL" | grep -qE "待处理|处理中"; then
    ok "使用中文标签而非英文枚举"
  else
    bad "应使用中文标签，实际：$DETAIL"
  fi
else
  bad "状态变更未留痕"
fi

# ---- 场景 6：转派自动留痕且高亮 ----
echo -e "\n${B}▶ 场景 6：转派是否留痕并高亮${RST}"
cat > "$TMPD/assign.json" <<'EOF'
{"assignee": "李强"}
EOF
patch_json "$BASE/$TID/assignee" "$TMPD/assign.json" > /dev/null
ACT4=$(curl -s "$BASE/$TID/activities")
if echo "$ACT4" | grep -q "工单转派"; then
  ok "转派已留痕"
  if echo "$ACT4" | grep -o '"text":"工单转派"[^}]*' | grep -q '"highlight":true'; then
    ok "转派记录已高亮（责任转移需醒目）"
  else
    info "转派记录未高亮"
  fi
else
  bad "转派未留痕"
fi

# ---- 场景 7：编辑记录字段级变化 ----
echo -e "\n${B}▶ 场景 7：编辑是否记录具体变化字段${RST}"
CUR=$(curl -s "$BASE/$TID")
V=$(jnum "$CUR" "version")
echo "{\"priority\":\"HIGH\",\"version\":$V}" > "$TMPD/edit.json"
put_json "$BASE/$TID" "$TMPD/edit.json" > /dev/null
ACT5=$(curl -s "$BASE/$TID/activities")
if echo "$ACT5" | grep -q "工单编辑"; then
  EDIT_DETAIL=$(echo "$ACT5" | grep -o '"text":"工单编辑","detail":"[^"]*"' | head -1 | sed 's/.*"detail":"//;s/"$//')
  ok "编辑已留痕"
  info "变化描述：$EDIT_DETAIL"
  if echo "$EDIT_DETAIL" | grep -q "优先级"; then
    ok "记录了具体变化字段而非笼统「已更新」"
  else
    bad "应记录具体字段，实际：$EDIT_DETAIL"
  fi
else
  bad "编辑未留痕"
fi

# ---- 场景 8：已关闭工单拒绝回复 ----
echo -e "\n${B}▶ 场景 8：已关闭工单是否拒绝回复${RST}"
echo '{"status":"CLOSED"}' > "$TMPD/close.json"
patch_json "$BASE/$TID/status" "$TMPD/close.json" > /dev/null
cat > "$TMPD/reply3.json" <<'EOF'
{"role": "agent", "author": "张明", "content": "关闭后的回复"}
EOF
CLOSED_REPLY=$(post_json "$BASE/$TID/replies" "$TMPD/reply3.json")
CODE=$(jnum "$CLOSED_REPLY" "code")
if [ "$CODE" = "40004" ]; then
  ok "已关闭工单正确拒绝回复（40004）"
  info "$(jstr "$CLOSED_REPLY" "message")"
else
  bad "已关闭工单应拒绝回复，实际 code=$CODE"
fi

# ---- 场景 9：空回复拒绝 ----
echo -e "\n${B}▶ 场景 9：空内容是否拒绝${RST}"
cat > "$TMPD/empty.json" <<'EOF'
{"role": "agent", "author": "张明", "content": "   "}
EOF
EMPTY=$(post_json "$BASE/$TID/replies" "$TMPD/empty.json")
if [ "$(jnum "$EMPTY" "code")" = "40001" ]; then
  ok "空回复正确拒绝（40001）"
else
  bad "空回复应拒绝，实际 code=$(jnum "$EMPTY" "code")"
fi

# ---- 场景 10：删除工单级联清理 ----
echo -e "\n${B}▶ 场景 10：删除工单是否级联清理子表${RST}"
curl -s -X DELETE "$BASE/$TID" > /dev/null
sleep 1
R_AFTER=$(docker exec devops-pgvector psql -U devops -d devops_knowledge_db -t -c \
  "SELECT COUNT(*) FROM sys_ticket_reply WHERE ticket_id='$TID';" 2>/dev/null | tr -d ' \n')
A_AFTER=$(docker exec devops-pgvector psql -U devops -d devops_knowledge_db -t -c \
  "SELECT COUNT(*) FROM sys_ticket_activity WHERE ticket_id='$TID';" 2>/dev/null | tr -d ' \n')
if [ "$R_AFTER" = "0" ] && [ "$A_AFTER" = "0" ]; then
  ok "回复与活动流已级联清理（无孤儿数据）"
else
  bad "级联清理不完整：回复剩 $R_AFTER 条，活动剩 $A_AFTER 条"
fi

# ---- 汇总 ----
echo ""
echo "======================================================================"
TOTAL=$((PASS + FAIL))
if [ $FAIL -eq 0 ]; then
  echo -e "${G}验证结果: $PASS/$TOTAL 通过${RST}"
else
  echo -e "${R}验证结果: $PASS/$TOTAL 通过，$FAIL 项失败${RST}"
fi
echo "======================================================================"
exit $([ $FAIL -eq 0 ] && echo 0 || echo 1)
