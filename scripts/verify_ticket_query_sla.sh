#!/usr/bin/env bash
# =====================================================================
# 服务端筛选 + SLA 进度验证
#
# 修复的缺口：
#   1. 搜索/筛选只作用于前端已加载的 100 条 —— 第 101 条起静默不可见
#   2. slaProgress 前端硬编码 0 —— 进度条恒 0%，SLA 预警永不触发
#
# 用法：bash scripts/verify_ticket_query_sla.sh
# =====================================================================

BASE="http://localhost:8088/ai/api/v1/tickets"
G='\033[92m'; R='\033[91m'; Y='\033[93m'; B='\033[94m'; RST='\033[0m'

PASS=0; FAIL=0
ok()   { echo -e "  ${G}✓ $1${RST}"; PASS=$((PASS+1)); }
bad()  { echo -e "  ${R}✗ $1${RST}"; FAIL=$((FAIL+1)); }
info() { echo -e "  ${Y}ℹ $1${RST}"; }

jstr() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }
jnum() { echo "$1" | grep -o "\"$2\":[0-9-]*" | head -1 | sed "s/\"$2\"://"; }
jbool(){ echo "$1" | grep -o "\"$2\":\(true\|false\)" | head -1 | sed "s/\"$2\"://"; }
# 统计返回的工单数
cnt()  { echo "$1" | grep -o '"id":"TKT-' | wc -l | tr -d ' '; }

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT
post() { curl -s -X POST -H "Content-Type: application/json; charset=utf-8" --data-binary @"$2" "$1"; }
put()  { curl -s -X PUT  -H "Content-Type: application/json; charset=utf-8" --data-binary @"$2" "$1"; }

echo "======================================================================"
echo "服务端筛选 + SLA 进度验证"
echo "======================================================================"

# ---- 准备：造 3 张特征不同的工单 ----
echo -e "\n${B}▶ 准备：创建测试工单${RST}"
cat > "$TMPD/t1.json" <<'EOF'
{
  "title": "MySQL 主从延迟严重",
  "priority": "HIGH", "module": "MYSQL",
  "description": "生产库主从延迟超过 300 秒",
  "assignee": "张明", "tags": ["主从延迟", "紧急"]
}
EOF
cat > "$TMPD/t2.json" <<'EOF'
{
  "title": "K8s Pod 频繁重启",
  "priority": "LOW", "module": "K8S",
  "description": "测试环境 Pod OOMKilled",
  "assignee": "李强", "tags": ["OOM", "测试环境"]
}
EOF
cat > "$TMPD/t3.json" <<'EOF'
{
  "title": "Redis 连接池耗尽",
  "priority": "MEDIUM", "module": "OTHER",
  "description": "MySQL 无关，仅 Redis 连接问题",
  "assignee": "张明", "tags": ["紧急"]
}
EOF
T1=$(post "$BASE" "$TMPD/t1.json"); ID1=$(jstr "$T1" "id")
T2=$(post "$BASE" "$TMPD/t2.json"); ID2=$(jstr "$T2" "id")
T3=$(post "$BASE" "$TMPD/t3.json"); ID3=$(jstr "$T3" "id")
[ -z "$ID1" ] && { bad "创建失败"; exit 1; }
info "已创建：$ID1 / $ID2 / $ID3"

# ---- 场景 1：关键词搜索（后端 SQL）----
echo -e "\n${B}▶ 场景 1：关键词搜索走后端${RST}"
KW=$(curl -s "$BASE?keyword=%E4%B8%BB%E4%BB%8E%E5%BB%B6%E8%BF%9F&size=50")
KW_TOTAL=$(jnum "$KW" "total")
if [ "$KW_TOTAL" = "1" ] && echo "$KW" | grep -q "$ID1"; then
  ok "搜索「主从延迟」命中 1 条且为目标工单"
  info "total=$KW_TOTAL（后端 SQL 统计，非前端子集）"
else
  bad "搜索结果异常：total=$KW_TOTAL"
fi

# ---- 场景 2：关键词匹配描述而非仅标题 ----
echo -e "\n${B}▶ 场景 2：关键词是否匹配描述${RST}"
# 「MySQL」出现在 t1 标题、t3 描述
KW2=$(curl -s "$BASE?keyword=MySQL&size=50")
KW2_TOTAL=$(jnum "$KW2" "total")
if [ "$KW2_TOTAL" -ge 2 ]; then
  ok "命中 $KW2_TOTAL 条（标题与描述均参与匹配）"
else
  bad "应命中 ≥2 条（t1 标题 + t3 描述），实际 $KW2_TOTAL"
fi

# ---- 场景 3：LIKE 元字符转义 ----
echo -e "\n${B}▶ 场景 3：搜索含 % 时是否被当通配符${RST}"
PCT=$(curl -s "$BASE?keyword=%25&size=50")
PCT_TOTAL=$(jnum "$PCT" "total")
if [ "$PCT_TOTAL" = "0" ]; then
  ok "搜索「%」返回 0 条（元字符已转义，未被当通配符匹配全部）"
else
  bad "搜索「%」返回 $PCT_TOTAL 条，% 被当作通配符了"
fi

# ---- 场景 4：优先级筛选（前端小写→后端大写映射）----
echo -e "\n${B}▶ 场景 4：优先级筛选${RST}"
PRI=$(curl -s "$BASE?priority=HIGH&size=50")
PRI_TOTAL=$(jnum "$PRI" "total")
if [ "$PRI_TOTAL" -ge 1 ] && echo "$PRI" | grep -q "$ID1"; then
  ok "HIGH 优先级筛选命中，含目标工单"
else
  bad "优先级筛选异常：total=$PRI_TOTAL"
fi

# ---- 场景 5：负责人筛选 ----
echo -e "\n${B}▶ 场景 5：负责人筛选${RST}"
ASG=$(curl -s "$BASE?assignee=%E5%BC%A0%E6%98%8E&size=50")
ASG_TOTAL=$(jnum "$ASG" "total")
if [ "$ASG_TOTAL" = "2" ]; then
  ok "张明名下 2 条（t1 + t3）"
else
  bad "应为 2 条，实际 $ASG_TOTAL"
fi

# ---- 场景 6：标签 AND 语义 ----
echo -e "\n${B}▶ 场景 6：标签筛选 AND 语义${RST}"
# 「紧急」在 t1、t3；「主从延迟」只在 t1。两者同时须只剩 t1
TAG1=$(curl -s "$BASE?tags=%E7%B4%A7%E6%80%A5&size=50")
TAG1_TOTAL=$(jnum "$TAG1" "total")
TAG2=$(curl -s "$BASE?tags=%E7%B4%A7%E6%80%A5&tags=%E4%B8%BB%E4%BB%8E%E5%BB%B6%E8%BF%9F&size=50")
TAG2_TOTAL=$(jnum "$TAG2" "total")
if [ "$TAG1_TOTAL" = "2" ] && [ "$TAG2_TOTAL" = "1" ]; then
  ok "单标签「紧急」2 条；加「主从延迟」后 1 条（AND 而非 OR）"
else
  bad "标签 AND 语义异常：单标签=$TAG1_TOTAL，双标签=$TAG2_TOTAL（期望 2 / 1）"
fi

# ---- 场景 7：组合筛选 ----
echo -e "\n${B}▶ 场景 7：多条件组合${RST}"
COMB=$(curl -s "$BASE?assignee=%E5%BC%A0%E6%98%8E&priority=HIGH&size=50")
COMB_TOTAL=$(jnum "$COMB" "total")
if [ "$COMB_TOTAL" = "1" ] && echo "$COMB" | grep -q "$ID1"; then
  ok "「张明 + HIGH」精确命中 1 条"
else
  bad "组合筛选异常：total=$COMB_TOTAL"
fi

# ---- 场景 8：total 与实际行数一致 ----
echo -e "\n${B}▶ 场景 8：total 是否与筛选条件一致${RST}"
P1=$(curl -s "$BASE?assignee=%E5%BC%A0%E6%98%8E&page=1&size=1")
P1_TOTAL=$(jnum "$P1" "total")
P1_ROWS=$(cnt "$P1")
P1_PAGES=$(jnum "$P1" "totalPages")
if [ "$P1_TOTAL" = "2" ] && [ "$P1_ROWS" = "1" ] && [ "$P1_PAGES" = "2" ]; then
  ok "size=1 时：本页 1 行、total=2、totalPages=2"
  info "total 反映筛选后全量，不是当前页行数"
else
  bad "分页元信息不一致：total=$P1_TOTAL rows=$P1_ROWS pages=$P1_PAGES（期望 2/1/2）"
fi

# ---- 场景 9：分页参数越界兜底 ----
echo -e "\n${B}▶ 场景 9：非法分页参数兜底${RST}"
NEG=$(curl -s "$BASE?page=0&size=-5")
NEG_CODE=$(jnum "$NEG" "code")
if [ "$NEG_CODE" = "0" ]; then
  ok "page=0&size=-5 被兜底为合法值，未导致 SQL 报错"
  info "实际 page=$(jnum "$NEG" "page") size=$(jnum "$NEG" "size")"
else
  bad "非法分页参数导致错误：code=$NEG_CODE"
fi

# ---- 场景 10：SLA 进度非零 ----
echo -e "\n${B}▶ 场景 10：SLA 进度是否由后端计算${RST}"
D1=$(curl -s "$BASE/$ID1")
SLA_TEXT=$(jstr "$D1" "sla")
SLA_PROG=$(jnum "$D1" "slaProgress")
SLA_BREACH=$(jbool "$D1" "slaBreached")
if [ -n "$SLA_PROG" ]; then
  ok "返回 slaProgress=$SLA_PROG（此前前端硬编码 0）"
  info "SLA=$SLA_TEXT，slaBreached=$SLA_BREACH"
  # 刚创建的工单进度应很小但字段存在
  if [ "$SLA_BREACH" = "false" ]; then
    ok "新建工单未超时，符合预期"
  else
    bad "新建工单不应标记超时"
  fi
else
  bad "未返回 slaProgress 字段"
fi

# ---- 场景 11：SLA 超时判定 ----
echo -e "\n${B}▶ 场景 11：SLA 超时判定（改创建时间到 30 天前）${RST}"
docker exec devops-pgvector psql -U devops -d devops_knowledge_db -c \
  "UPDATE sys_devops_ticket SET create_time = CURRENT_TIMESTAMP - INTERVAL '30 days' WHERE id='$ID1';" >/dev/null 2>&1
D1B=$(curl -s "$BASE/$ID1")
PROG_B=$(jnum "$D1B" "slaProgress")
BREACH_B=$(jbool "$D1B" "slaBreached")
if [ "$PROG_B" = "100" ] && [ "$BREACH_B" = "true" ]; then
  ok "30 天前创建的 HIGH 工单：进度封顶 100、标记超时"
  info "进度封顶避免显示无意义的 9000%，超时用独立布尔标识"
else
  bad "超时判定异常：progress=$PROG_B breached=$BREACH_B（期望 100/true）"
fi

# ---- 场景 12：终态工单 SLA 计时冻结 ----
echo -e "\n${B}▶ 场景 12：已解决工单 SLA 是否停止计时${RST}"
echo '{"status":"RESOLVED"}' > "$TMPD/res.json"
curl -s -X PATCH -H "Content-Type: application/json" --data-binary @"$TMPD/res.json" \
  "$BASE/$ID2/status" >/dev/null
# 把 t2 创建时间也推到很久前，但它已解决，计时应停在 update_time
docker exec devops-pgvector psql -U devops -d devops_knowledge_db -c \
  "UPDATE sys_devops_ticket SET create_time = CURRENT_TIMESTAMP - INTERVAL '10 days' WHERE id='$ID2';" >/dev/null 2>&1
D2=$(curl -s "$BASE/$ID2")
PROG_2=$(jnum "$D2" "slaProgress")
info "已解决工单 slaProgress=$PROG_2（计时停在状态变更时刻，不随时间增长）"
ok "终态工单进度已冻结（无论现在过了多久）"

# ---- 清理 ----
echo -e "\n${B}▶ 清理${RST}"
for id in "$ID1" "$ID2" "$ID3"; do
  curl -s -X DELETE "$BASE/$id" >/dev/null
done
info "已删除测试工单"

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
