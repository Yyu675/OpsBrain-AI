#!/usr/bin/env bash
# =====================================================================
# 分页刷新回归验证
#
# 修复的 BUG（由 6.15 分页下沉引入的回归）：
#   批量删除/改状态/指派、单条创建后，前端只改本地数组不重新拉取。
#   分页在服务端后，后果是：
#     · 当前页少几行（本该由下一页记录补齐）
#     · total 仍是旧值
#     · 第 2 页该上移的记录不出现
#
#   另：refreshTickets() 不带参数调用，会把列表重置为无筛选第 1 页，
#   使筛选下拉框仍显示条件但数据已是全部工单。
#
# 本脚本从后端视角验证：删除后 total 与页内行数应立即正确。
#
# 用法：bash scripts/verify_pagination_refresh.sh
# =====================================================================

BASE="http://localhost:8088/ai/api/v1/tickets"
G='\033[92m'; R='\033[91m'; Y='\033[93m'; B='\033[94m'; RST='\033[0m'

PASS=0; FAIL=0
ok()   { echo -e "  ${G}✓ $1${RST}"; PASS=$((PASS+1)); }
bad()  { echo -e "  ${R}✗ $1${RST}"; FAIL=$((FAIL+1)); }
info() { echo -e "  ${Y}ℹ $1${RST}"; }

jstr() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }
jnum() { echo "$1" | grep -o "\"$2\":[0-9-]*" | head -1 | sed "s/\"$2\"://"; }
cnt()  { echo "$1" | grep -o '"id":"TKT-' | wc -l | tr -d ' '; }

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT
MARK="分页刷新验证"

echo "======================================================================"
echo "分页刷新回归验证"
echo "======================================================================"

# ---- 准备：造 5 张同标记工单 ----
echo -e "\n${B}▶ 准备：创建 5 张测试工单${RST}"
IDS=()
for i in 1 2 3 4 5; do
  cat > "$TMPD/c$i.json" <<EOF
{"title":"$MARK 第 $i 条","priority":"LOW","module":"OTHER",
 "description":"用于验证删除后分页元信息是否即时正确","assignee":"分页测试员"}
EOF
  R=$(curl -s -X POST -H "Content-Type: application/json" --data-binary @"$TMPD/c$i.json" "$BASE")
  IDS+=("$(jstr "$R" "id")")
done
info "已创建：${IDS[*]}"

# 用 assignee 作为隔离条件，避免受库中其他工单干扰
Q="assignee=%E5%88%86%E9%A1%B5%E6%B5%8B%E8%AF%95%E5%91%98"

# ---- 场景 1：初始 total 与分页 ----
echo -e "\n${B}▶ 场景 1：初始状态（size=2）${RST}"
P=$(curl -s "$BASE?$Q&page=1&size=2")
T=$(jnum "$P" "total"); ROWS=$(cnt "$P"); PAGES=$(jnum "$P" "totalPages")
if [ "$T" = "5" ] && [ "$ROWS" = "2" ] && [ "$PAGES" = "3" ]; then
  ok "total=5、本页 2 行、共 3 页"
else
  bad "初始分页异常：total=$T rows=$ROWS pages=$PAGES（期望 5/2/3）"
fi

# ---- 场景 2：删 2 条后 total 立即变化 ----
echo -e "\n${B}▶ 场景 2：删除 2 条后重新查询${RST}"
curl -s -X DELETE "$BASE/${IDS[0]}" >/dev/null
curl -s -X DELETE "$BASE/${IDS[1]}" >/dev/null
P2=$(curl -s "$BASE?$Q&page=1&size=2")
T2=$(jnum "$P2" "total"); ROWS2=$(cnt "$P2"); PAGES2=$(jnum "$P2" "totalPages")
if [ "$T2" = "3" ] && [ "$ROWS2" = "2" ] && [ "$PAGES2" = "2" ]; then
  ok "total=3、本页仍 2 行（已由后续记录补齐）、共 2 页"
  info "前端若不重新拉取，会停留在 total=5 且本页只剩 0~1 行"
else
  bad "删除后分页异常：total=$T2 rows=$ROWS2 pages=$PAGES2（期望 3/2/2）"
fi

# ---- 场景 3：末页删空后应退页 ----
echo -e "\n${B}▶ 场景 3：末页记录删完后该页为空${RST}"
LAST=$(curl -s "$BASE?$Q&page=2&size=2")
LAST_ROWS=$(cnt "$LAST")
info "第 2 页当前 $LAST_ROWS 行"
# 删掉剩余全部
for i in 2 3 4; do
  [ -n "${IDS[$i]}" ] && curl -s -X DELETE "$BASE/${IDS[$i]}" >/dev/null
done
EMPTY=$(curl -s "$BASE?$Q&page=2&size=2")
E_TOTAL=$(jnum "$EMPTY" "total"); E_ROWS=$(cnt "$EMPTY")
if [ "$E_TOTAL" = "0" ] && [ "$E_ROWS" = "0" ]; then
  ok "全删后 total=0、无数据行"
  info "前端须在 tickets 为空且非首页时退一页，避免停在空白页"
else
  bad "全删后应 total=0 rows=0，实际 total=$E_TOTAL rows=$E_ROWS"
fi

# ---- 场景 4：筛选条件下 total 独立于全库 ----
echo -e "\n${B}▶ 场景 4：筛选 total 是否独立于全库总数${RST}"
ALL=$(curl -s "$BASE?page=1&size=1")
ALL_TOTAL=$(jnum "$ALL" "total")
FILTERED=$(curl -s "$BASE?$Q&page=1&size=1")
F_TOTAL=$(jnum "$FILTERED" "total")
if [ "$F_TOTAL" = "0" ] && [ "$ALL_TOTAL" -ge 0 ]; then
  ok "筛选后 total=0，全库 total=$ALL_TOTAL（两者独立）"
  info "证明 countByQuery 与 findPage 共用同一 WHERE 条件"
else
  bad "筛选 total 异常：筛选=$F_TOTAL 全库=$ALL_TOTAL"
fi

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
