#!/usr/bin/env bash
# =====================================================================
# P1-4 乐观锁验证脚本
#
# 模拟真实并发场景：两人同时打开同一工单编辑，A 先提交，B 后提交。
# 期望：B 被拒绝（40009）而非静默覆盖 A 的修改。
#
# 用法：bash scripts/verify_optimistic_lock.sh
# =====================================================================

BASE="http://localhost:8088/ai/api/v1/tickets"
G='\033[92m'; R='\033[91m'; Y='\033[93m'; B='\033[94m'; RST='\033[0m'

PASS=0; FAIL=0

# 注意用 PASS=$((PASS+1)) 而非 ((PASS++))：
# 后者在 PASS=0 时表达式值为 0，bash 判为失败退出码，
# 会让 `cond && ok ... || bad ...` 的 bad 分支也执行
ok()   { echo -e "  ${G}✓ $1${RST}"; PASS=$((PASS+1)); }
bad()  { echo -e "  ${R}✗ $1${RST}"; FAIL=$((FAIL+1)); }
info() { echo -e "  ${Y}ℹ $1${RST}"; }

jget() { echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed "s/\"$2\"://"; }
jstr() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }

echo "======================================================================"
echo "P1-4 乐观锁 / 版本号验证"
echo "======================================================================"

# ---- 准备：创建测试工单 ----
echo -e "\n${B}▶ 准备：创建测试工单${RST}"
cat > /tmp/lock_create.json <<'EOF'
{
  "title": "乐观锁并发验证工单",
  "priority": "MEDIUM",
  "module": "K8S",
  "description": "验证两人同时编辑时后提交者是否被正确拒绝",
  "assignee": "tester"
}
EOF

CREATED=$(curl -s -X POST -H "Content-Type: application/json" \
  --data-binary @/tmp/lock_create.json "$BASE")
TID=$(jstr "$CREATED" "id")
V0=$(jget "$CREATED" "version")

if [ -z "$TID" ]; then
  bad "创建失败，无法继续：$CREATED"
  exit 1
fi
info "工单号=$TID  初始 version=$V0"
[ "$V0" = "0" ] && ok "新建工单 version 为 0" || bad "新建工单 version 应为 0，实际 $V0"

# ---- 场景 1：A 与 B 同时读到 version=0 ----
echo -e "\n${B}▶ 场景 1：A 先提交（持 version=$V0）${RST}"
RESP_A=$(curl -s -X PUT -H "Content-Type: application/json" \
  -d "{\"title\":\"A modified the title\",\"version\":$V0}" "$BASE/$TID")
CODE_A=$(jget "$RESP_A" "code")
V_AFTER_A=$(jget "$RESP_A" "version")
TITLE_AFTER_A=$(jstr "$RESP_A" "title")

if [ "$CODE_A" = "0" ]; then
  ok "A 提交成功"
  info "标题=$TITLE_AFTER_A  version=$V0 → $V_AFTER_A"
  [ "$V_AFTER_A" = "1" ] && ok "version 已自增至 1" || bad "version 应为 1，实际 $V_AFTER_A"
else
  bad "A 提交应成功，实际 code=$CODE_A"
fi

# ---- 场景 2：B 用过期版本提交 ----
echo -e "\n${B}▶ 场景 2：B 后提交（仍持过期的 version=$V0）${RST}"
RESP_B=$(curl -s -X PUT -H "Content-Type: application/json" \
  -d "{\"title\":\"B overwrote A\",\"version\":$V0}" "$BASE/$TID")
CODE_B=$(jget "$RESP_B" "code")
MSG_B=$(jstr "$RESP_B" "message")

if [ "$CODE_B" = "40009" ]; then
  ok "B 被正确拒绝（40009 版本冲突）"
  info "提示：$MSG_B"
else
  bad "B 应被拒绝返回 40009，实际 code=$CODE_B"
  info "响应：$(echo "$RESP_B" | head -c 200)"
fi

# ---- 场景 3：确认 A 的修改未被覆盖 ----
echo -e "\n${B}▶ 场景 3：确认 A 的修改未被 B 覆盖${RST}"
CURRENT=$(curl -s "$BASE/$TID")
TITLE_NOW=$(jstr "$CURRENT" "title")

if [ "$TITLE_NOW" = "A modified the title" ]; then
  ok "标题仍是 A 的修改，未被覆盖"
else
  bad "A 的修改被覆盖了！当前标题=$TITLE_NOW"
fi

# ---- 场景 4：B 刷新后用新版本重试 ----
echo -e "\n${B}▶ 场景 4：B 刷新拿到 version=$V_AFTER_A 后重试${RST}"
RESP_B2=$(curl -s -X PUT -H "Content-Type: application/json" \
  -d "{\"title\":\"B modified after refresh\",\"version\":$V_AFTER_A}" "$BASE/$TID")
CODE_B2=$(jget "$RESP_B2" "code")
V_AFTER_B=$(jget "$RESP_B2" "version")

if [ "$CODE_B2" = "0" ]; then
  ok "B 刷新后提交成功"
  info "version=$V_AFTER_A → $V_AFTER_B"
  [ "$V_AFTER_B" = "2" ] && ok "version 已自增至 2" || bad "version 应为 2，实际 $V_AFTER_B"
else
  bad "B 刷新后应能提交，实际 code=$CODE_B2"
fi

# ---- 场景 5：状态变更也自增版本 ----
echo -e "\n${B}▶ 场景 5：状态变更是否自增版本${RST}"
curl -s -X PATCH -H "Content-Type: application/json" \
  -d '{"status":"PROCESSING"}' "$BASE/$TID/status" > /dev/null
AFTER_STATUS=$(curl -s "$BASE/$TID")
V_AFTER_STATUS=$(jget "$AFTER_STATUS" "version")

if [ "$V_AFTER_STATUS" = "3" ]; then
  ok "状态变更后 version 自增至 3"
  info "并发的全量更新能据此感知状态已变"
else
  bad "状态变更后 version 应为 3，实际 $V_AFTER_STATUS"
fi

# ---- 场景 6：不传 version 退化为无锁覆盖（兼容旧客户端）----
echo -e "\n${B}▶ 场景 6：不传 version 时退化为无锁覆盖${RST}"
RESP_NOVER=$(curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"title":"Legacy client no version"}' "$BASE/$TID")
CODE_NOVER=$(jget "$RESP_NOVER" "code")

if [ "$CODE_NOVER" = "0" ]; then
  ok "不传 version 仍可更新（兼容旧客户端）"
  info "注意：此路径无并发保护，前端应始终回传 version"
else
  bad "不传 version 应能更新，实际 code=$CODE_NOVER"
fi

# ---- 清理 ----
echo -e "\n${B}▶ 清理测试数据${RST}"
curl -s -X DELETE "$BASE/$TID" > /dev/null && info "已删除 $TID"
rm -f /tmp/lock_create.json

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
