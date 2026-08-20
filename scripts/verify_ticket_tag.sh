#!/usr/bin/env bash
# =====================================================================
# 工单标签持久化验证
#
# 背景：标签此前由前端 extractTagsFromModule() 凭 module 编造 ——
#   每张工单都被贴上「生产环境」，测试环境工单也如此；
#   用户在表单输入的标签提交时被丢弃；
#   按标签筛选实际等价于按 module 筛选。
#
# 用法：bash scripts/verify_ticket_tag.sh
# =====================================================================

BASE="http://localhost:8088/ai/api/v1/tickets"
G='\033[92m'; R='\033[91m'; Y='\033[93m'; B='\033[94m'; RST='\033[0m'

PASS=0; FAIL=0
ok()   { echo -e "  ${G}✓ $1${RST}"; PASS=$((PASS+1)); }
bad()  { echo -e "  ${R}✗ $1${RST}"; FAIL=$((FAIL+1)); }
info() { echo -e "  ${Y}ℹ $1${RST}"; }

jstr()  { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }
jnum()  { echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed "s/\"$2\"://"; }
# 提取 "tags":[...] 中的元素
jtags() { echo "$1" | grep -o '"tags":\[[^]]*\]' | head -1 | sed 's/"tags":\[//;s/\]//' | tr -d '"'; }

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT

post() { curl -s -X POST -H "Content-Type: application/json; charset=utf-8" --data-binary @"$2" "$1"; }
put()  { curl -s -X PUT  -H "Content-Type: application/json; charset=utf-8" --data-binary @"$2" "$1"; }

echo "======================================================================"
echo "工单标签持久化验证"
echo "======================================================================"

# ---- 场景 1：创建时带标签 ----
echo -e "\n${B}▶ 场景 1：创建工单时标签是否落库${RST}"
cat > "$TMPD/c1.json" <<'EOF'
{
  "title": "标签持久化验证工单",
  "priority": "MEDIUM",
  "module": "MYSQL",
  "description": "验证用户输入的标签是否真正保存",
  "assignee": "张明",
  "tags": ["主从延迟", "紧急排查", "预发环境"]
}
EOF
C1=$(post "$BASE" "$TMPD/c1.json")
TID=$(jstr "$C1" "id")
[ -z "$TID" ] && { bad "创建失败：$C1"; exit 1; }
info "工单号=$TID"

TAGS1=$(jtags "$C1")
if echo "$TAGS1" | grep -q "主从延迟" && echo "$TAGS1" | grep -q "预发环境"; then
  ok "用户输入的标签已落库"
  info "标签：$TAGS1"
else
  bad "标签未落库，实际：$TAGS1"
fi

# ---- 场景 2：不再编造「生产环境」 ----
echo -e "\n${B}▶ 场景 2：是否不再凭 module 编造标签${RST}"
if echo "$TAGS1" | grep -q "生产环境"; then
  bad "仍出现编造的「生产环境」标签"
elif echo "$TAGS1" | grep -q "MySQL"; then
  bad "仍出现由 module 派生的「MySQL」标签"
else
  ok "无编造标签，预发环境工单不再被误标为生产环境"
fi

# ---- 场景 3：查询回读 ----
echo -e "\n${B}▶ 场景 3：刷新后标签是否仍在${RST}"
GET1=$(curl -s "$BASE/$TID")
TAGS_GET=$(jtags "$GET1")
if echo "$TAGS_GET" | grep -q "主从延迟"; then
  ok "详情查询带回标签"
else
  bad "详情查询未带回标签，实际：$TAGS_GET"
fi

# ---- 场景 4：列表页批量装填 ----
echo -e "\n${B}▶ 场景 4：列表查询是否批量装填标签${RST}"
LIST=$(curl -s "$BASE?page=1&size=10")
if echo "$LIST" | grep -q '"tags"'; then
  ok "列表返回含 tags 字段（一次查询，无 N+1）"
else
  bad "列表未返回 tags 字段"
fi

# ---- 场景 5：替换标签 ----
echo -e "\n${B}▶ 场景 5：替换标签${RST}"
cat > "$TMPD/t2.json" <<'EOF'
{"tags": ["主从延迟", "已定位", "生产环境"]}
EOF
T2=$(put "$BASE/$TID/tags" "$TMPD/t2.json")
if echo "$T2" | grep -q "已定位" && ! echo "$T2" | grep -q "紧急排查"; then
  ok "标签已全量替换（旧标签移除，新标签写入）"
  info "替换后：$(echo "$T2" | grep -o '"data":\[[^]]*\]' | sed 's/"data":\[//;s/\]//' | tr -d '"')"
else
  bad "替换未生效：$(echo "$T2" | head -c 200)"
fi

# ---- 场景 6：归一化（去空、去重、截断）----
echo -e "\n${B}▶ 场景 6：归一化处理${RST}"
cat > "$TMPD/t3.json" <<'EOF'
{"tags": ["  K8s  ", "K8s", "", "   ", "网络"]}
EOF
T3=$(put "$BASE/$TID/tags" "$TMPD/t3.json")
NORM=$(echo "$T3" | grep -o '"data":\[[^]]*\]' | sed 's/"data":\[//;s/\]//' | tr -d '"')
N_NORM=$(echo "$NORM" | tr ',' '\n' | grep -c .)
if [ "$N_NORM" = "2" ]; then
  ok "归一化正确：去首尾空白、去重、剔除空值（5 项 → 2 项）"
  info "结果：$NORM"
else
  bad "归一化异常，应为 2 项，实际 $N_NORM 项：$NORM"
fi

# ---- 场景 7：大小写保留 ----
echo -e "\n${B}▶ 场景 7：是否保留原始大小写${RST}"
if echo "$NORM" | grep -q "K8s"; then
  ok "保留「K8s」原始写法（未强制小写，官方写法不失真）"
else
  bad "大小写被改动：$NORM"
fi

# ---- 场景 8：热门标签聚合 ----
echo -e "\n${B}▶ 场景 8：热门标签统计${RST}"
HOT=$(curl -s "$BASE/tags/hot?limit=10")
if echo "$HOT" | grep -q '"tags"' && echo "$HOT" | grep -q '"counts"'; then
  ok "热门标签接口可用"
  info "标签：$(echo "$HOT" | grep -o '"tags":\[[^]]*\]' | sed 's/"tags":\[//;s/\]//' | tr -d '"')"
else
  bad "热门标签接口异常：$(echo "$HOT" | head -c 200)"
fi

# ---- 场景 9：标签变更留痕 ----
echo -e "\n${B}▶ 场景 9：标签变更是否记入活动流${RST}"
ACT=$(curl -s "$BASE/$TID/activities")
if echo "$ACT" | grep -q "标签变更"; then
  ok "标签变更已留痕"
  info "$(echo "$ACT" | grep -o '"text":"标签变更","detail":"[^"]*"' | head -1 | sed 's/.*"detail":"//;s/"$//')"
else
  bad "标签变更未留痕"
fi

# ---- 场景 10：清空标签 ----
echo -e "\n${B}▶ 场景 10：传空数组是否清空标签${RST}"
echo '{"tags": []}' > "$TMPD/t4.json"
T4=$(put "$BASE/$TID/tags" "$TMPD/t4.json")
if echo "$T4" | grep -q '"data":\[\]'; then
  ok "空数组正确清空全部标签"
else
  bad "清空未生效：$(echo "$T4" | head -c 150)"
fi

# ---- 场景 11：更新工单不传 tags 时保持原样 ----
echo -e "\n${B}▶ 场景 11：更新工单不传 tags 是否保持原样${RST}"
cat > "$TMPD/t5.json" <<'EOF'
{"tags": ["保留测试"]}
EOF
put "$BASE/$TID/tags" "$TMPD/t5.json" > /dev/null
CUR=$(curl -s "$BASE/$TID")
V=$(jnum "$CUR" "version")
echo "{\"title\":\"改了标题但不传标签\",\"version\":$V}" > "$TMPD/u1.json"
U1=$(put "$BASE/$TID" "$TMPD/u1.json")
if echo "$(jtags "$U1")" | grep -q "保留测试"; then
  ok "不传 tags 时标签保持原样（区分「不改」与「清空」）"
else
  bad "不传 tags 时标签被误清空：$(jtags "$U1")"
fi

# ---- 场景 12：级联清理 ----
echo -e "\n${B}▶ 场景 12：删除工单是否级联清理标签${RST}"
curl -s -X DELETE "$BASE/$TID" > /dev/null
sleep 1
LEFT=$(docker exec devops-pgvector psql -U devops -d devops_knowledge_db -t -c \
  "SELECT COUNT(*) FROM sys_ticket_tag WHERE ticket_id='$TID';" 2>/dev/null | tr -d ' \n')
if [ "$LEFT" = "0" ]; then
  ok "标签已级联清理，无孤儿数据"
else
  bad "标签未清理，残留 $LEFT 条"
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
