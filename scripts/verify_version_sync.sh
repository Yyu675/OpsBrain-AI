#!/usr/bin/env bash
# =====================================================================
# 版本号同步验证
#
# 修复的 bug：后端 updateStatus / updateAssignee 都会 version+1，
# 但前端只更新 updatedAt 未同步 version。后果是用户改完状态后
# 立即编辑，会带着过期版本号提交，**误报 40009 版本冲突**——
# 而实际上没有任何人在并发修改。
#
# 本脚本模拟该操作序列，确认后端每步返回的 version 均为最新值，
# 前端只要采用返回值即不会误报。
#
# 用法：bash scripts/verify_version_sync.sh
# =====================================================================

BASE="http://localhost:8088/ai/api/v1/tickets"
G='\033[92m'; R='\033[91m'; Y='\033[93m'; B='\033[94m'; RST='\033[0m'

PASS=0; FAIL=0
ok()   { echo -e "  ${G}✓ $1${RST}"; PASS=$((PASS+1)); }
bad()  { echo -e "  ${R}✗ $1${RST}"; FAIL=$((FAIL+1)); }
info() { echo -e "  ${Y}ℹ $1${RST}"; }

jstr() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }
jnum() { echo "$1" | grep -o "\"$2\":[0-9-]*" | head -1 | sed "s/\"$2\"://"; }

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT

echo "======================================================================"
echo "版本号同步验证（状态变更 → 立即编辑 不应误报冲突）"
echo "======================================================================"

# ---- 准备 ----
echo -e "\n${B}▶ 准备：创建工单${RST}"
cat > "$TMPD/c.json" <<'EOF'
{"title":"版本号同步验证","priority":"MEDIUM","module":"K8S",
 "description":"验证状态变更后 version 是否正确返回","assignee":"张明"}
EOF
C=$(curl -s -X POST -H "Content-Type: application/json" --data-binary @"$TMPD/c.json" "$BASE")
ID=$(jstr "$C" "id"); V=$(jnum "$C" "version")
[ -z "$ID" ] && { bad "创建失败"; exit 1; }
info "工单=$ID  version=$V"
[ "$V" = "0" ] && ok "创建返回 version=0" || bad "创建应返回 version=0，实际 $V"

# ---- 步骤 1：状态变更，检查返回的 version 是否自增 ----
echo -e "\n${B}▶ 步骤 1：变更状态为 PROCESSING${RST}"
echo '{"status":"PROCESSING"}' > "$TMPD/s.json"
S=$(curl -s -X PATCH -H "Content-Type: application/json" --data-binary @"$TMPD/s.json" "$BASE/$ID/status")
V_AFTER_STATUS=$(jnum "$S" "version")
if [ "$V_AFTER_STATUS" = "1" ]; then
  ok "状态变更响应返回 version=1（已自增）"
  info "前端须采用此值，否则下次编辑会带 version=0 误报冲突"
else
  bad "状态变更应返回 version=1，实际 $V_AFTER_STATUS"
fi

# ---- 步骤 2：用步骤 1 返回的 version 编辑，应成功 ----
echo -e "\n${B}▶ 步骤 2：用最新 version=$V_AFTER_STATUS 编辑${RST}"
printf '{"title":"编辑成功","version":%s}' "$V_AFTER_STATUS" > "$TMPD/u1.json"
U1=$(curl -s -X PUT -H "Content-Type: application/json" --data-binary @"$TMPD/u1.json" "$BASE/$ID")
U1_CODE=$(jnum "$U1" "code")
V_AFTER_EDIT=$(jnum "$U1" "version")
if [ "$U1_CODE" = "0" ]; then
  ok "编辑成功（version $V_AFTER_STATUS → $V_AFTER_EDIT）"
  info "这是修复后的行为：前端同步了 version"
else
  bad "编辑应成功，实际 code=$U1_CODE"
fi

# ---- 步骤 3：复现 bug——用过期 version 编辑，应被拒 ----
echo -e "\n${B}▶ 步骤 3：复现修复前的行为（带过期 version=0 编辑）${RST}"
echo '{"title":"这是修复前会发生的情况","version":0}' > "$TMPD/u2.json"
U2=$(curl -s -X PUT -H "Content-Type: application/json" --data-binary @"$TMPD/u2.json" "$BASE/$ID")
U2_CODE=$(jnum "$U2" "code")
if [ "$U2_CODE" = "40009" ]; then
  ok "带过期 version 被拒（40009）"
  info "修复前前端就是这样：状态变更后 version 未同步 → 编辑必然撞此错"
else
  bad "带过期 version 应返回 40009，实际 $U2_CODE"
fi

# ---- 步骤 4：转派也须返回最新 version ----
echo -e "\n${B}▶ 步骤 4：转派后 version 是否返回最新值${RST}"
echo '{"assignee":"李强"}' > "$TMPD/a.json"
A=$(curl -s -X PATCH -H "Content-Type: application/json" --data-binary @"$TMPD/a.json" "$BASE/$ID/assignee")
V_AFTER_ASSIGN=$(jnum "$A" "version")
EXPECTED=$((V_AFTER_EDIT + 1))
if [ "$V_AFTER_ASSIGN" = "$EXPECTED" ]; then
  ok "转派响应返回 version=$V_AFTER_ASSIGN（已自增）"
else
  bad "转派应返回 version=$EXPECTED，实际 $V_AFTER_ASSIGN"
fi

# ---- 步骤 5：连续操作后编辑仍应成功 ----
echo -e "\n${B}▶ 步骤 5：连续操作后用最新 version 编辑${RST}"
printf '{"description":"连续操作后编辑","version":%s}' "$V_AFTER_ASSIGN" > "$TMPD/u3.json"
U3=$(curl -s -X PUT -H "Content-Type: application/json" --data-binary @"$TMPD/u3.json" "$BASE/$ID")
U3_CODE=$(jnum "$U3" "code")
if [ "$U3_CODE" = "0" ]; then
  ok "连续「状态→编辑→转派→编辑」全程无误报冲突"
else
  bad "连续操作后编辑失败：code=$U3_CODE"
fi

# ---- 步骤 6：终态 SLA 冻结值是否随响应返回 ----
echo -e "\n${B}▶ 步骤 6：状态转终态时 SLA 派生值是否返回${RST}"
echo '{"status":"RESOLVED"}' > "$TMPD/r.json"
R=$(curl -s -X PATCH -H "Content-Type: application/json" --data-binary @"$TMPD/r.json" "$BASE/$ID/status")
R_PROG=$(jnum "$R" "slaProgress")
if [ -n "$R_PROG" ]; then
  ok "状态变更响应含 slaProgress=$R_PROG"
  info "前端须同步此值：终态后 SLA 计时冻结，不同步则 UI 仍显示增长值"
else
  bad "状态变更响应未含 slaProgress"
fi

# ---- 清理 ----
echo -e "\n${B}▶ 清理${RST}"
curl -s -X DELETE "$BASE/$ID" >/dev/null && info "已删除 $ID"

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
