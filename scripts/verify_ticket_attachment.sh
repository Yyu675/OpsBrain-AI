#!/usr/bin/env bash
# =====================================================================
# 工单附件（MinIO）验证
#
# 背景：附件此前是纯前端占位——点击下载得到一个内容为
#   「此文件为占位内容」的 txt，属假功能，已在 6.13 移除。
#   本次接入 MinIO 真正落地。
#
# 重点验证安全控制：扩展名白名单、双扩展名绕过、路径穿越、
#   大小/数量上限、内容查重、预签名下载。
#
# 用法：bash scripts/verify_ticket_attachment.sh
# =====================================================================

BASE="http://localhost:8088/ai/api/v1/tickets"
G='\033[92m'; R='\033[91m'; Y='\033[93m'; B='\033[94m'; RST='\033[0m'

PASS=0; FAIL=0
ok()   { echo -e "  ${G}✓ $1${RST}"; PASS=$((PASS+1)); }
bad()  { echo -e "  ${R}✗ $1${RST}"; FAIL=$((FAIL+1)); }
info() { echo -e "  ${Y}ℹ $1${RST}"; }

jstr() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }
jnum() { echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed "s/\"$2\"://"; }

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT

post_json() { curl -s -X POST -H "Content-Type: application/json; charset=utf-8" --data-binary @"$2" "$1"; }

# 上传封装
#
# 注意：Windows 版 curl 无法解析 MSYS 绝对路径（/tmp/...），传绝对路径会
# 直接连接失败（HTTP 000）。故先 cd 进临时目录，只给 curl 相对文件名。
#   $1=URL  $2=临时目录内的文件名  $3=上传时声明的文件名
upload() {
  ( cd "$TMPD" && curl -s -X POST -F "file=@$2;filename=$3" "$1" )
}

# 列出 MinIO 桶内对象
#
# 注意 --entrypoint sh：minio/mc 镜像的 ENTRYPOINT 是 mc，
# 不覆盖则 `sh -c "..."` 会被当作 mc 的子命令参数，
# 报「`sh` is not a recognized command」而非执行 shell。
mc_ls() {
  docker run --rm --entrypoint sh --network devops-platform-backend_default \
    minio/mc:RELEASE.2024-09-16T17-43-14Z -c \
    "mc alias set l http://minio:9000 devops_minio devops_minio_pwd >/dev/null 2>&1 && mc ls -r l/devops-ticket-attachment" 2>/dev/null
}

echo "======================================================================"
echo "工单附件（MinIO 对象存储）验证"
echo "======================================================================"

# ---- 准备 ----
echo -e "\n${B}▶ 准备：创建工单${RST}"
cat > "$TMPD/create.json" <<'EOF'
{
  "title": "附件功能验证工单",
  "priority": "MEDIUM",
  "module": "K8S",
  "description": "验证 MinIO 附件上传下载与安全控制",
  "assignee": "张明"
}
EOF
CREATED=$(post_json "$BASE" "$TMPD/create.json")
TID=$(jstr "$CREATED" "id")
[ -z "$TID" ] && { bad "创建工单失败：$CREATED"; exit 1; }
info "工单号=$TID"

# 准备测试文件
echo "2026-08-09 ERROR Pod OOMKilled memory limit 512Mi exceeded" > "$TMPD/app.log"
echo '{"replicas":3,"image":"nginx:1.25"}' > "$TMPD/deploy.json"
printf '\x89PNG\r\n\x1a\n fake png content' > "$TMPD/shot.png"

# ---- 场景 1：正常上传 ----
echo -e "\n${B}▶ 场景 1：合法文件上传${RST}"
UP1=$(upload "$BASE/$TID/attachments" "app.log" "app.log")
AID1=$(jnum "$UP1" "id")
if [ -n "$AID1" ] && [ "$AID1" != "0" ]; then
  ok "日志文件上传成功（附件 id=$AID1）"
  info "对象键=$(jstr "$UP1" "objectKey")"
  info "原始名=$(jstr "$UP1" "originalName")"
else
  bad "上传失败：$(echo "$UP1" | head -c 250)"
fi

# ---- 场景 2：对象键不含原始文件名 ----
echo -e "\n${B}▶ 场景 2：对象键是否安全生成${RST}"
KEY1=$(jstr "$UP1" "objectKey")
if echo "$KEY1" | grep -qE '^[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}\.log$'; then
  ok "对象键为「日期分区 + UUID」，不含用户文件名"
else
  bad "对象键格式异常：$KEY1"
fi

# ---- 场景 3：文件真的存进了 MinIO ----
echo -e "\n${B}▶ 场景 3：文件是否真实存入 MinIO${RST}"
IN_MINIO=$(mc_ls | grep -c "$(basename "$KEY1")")
if [ "$IN_MINIO" -ge 1 ]; then
  ok "对象已存在于 MinIO 桶中（非本地伪造）"
else
  bad "MinIO 中未找到对象 $KEY1"
fi

# ---- 场景 4：扩展名白名单 ----
echo -e "\n${B}▶ 场景 4：可执行文件应拒绝${RST}"
echo '#!/bin/sh
rm -rf /' > "$TMPD/evil.sh"
UP_SH=$(upload "$BASE/$TID/attachments" "evil.sh" "evil.sh")
if [ "$(jnum "$UP_SH" "code")" = "40001" ]; then
  ok "shell 脚本被拒绝（40001）"
  info "$(jstr "$UP_SH" "message")"
else
  bad "shell 脚本未被拒绝：$(echo "$UP_SH" | head -c 200)"
fi

# ---- 场景 5：双扩展名绕过 ----
echo -e "\n${B}▶ 场景 5：双扩展名绕过应拒绝${RST}"
UP_BYPASS=$(upload "$BASE/$TID/attachments" "app.log" "shell.jsp.log")
MSG_BYPASS=$(jstr "$UP_BYPASS" "message")
if [ "$(jnum "$UP_BYPASS" "code")" = "40001" ] && echo "$MSG_BYPASS" | grep -q "可执行类型标识"; then
  ok "shell.jsp.log 被拦截（末位 .log 在白名单，但中间 .jsp 被识别）"
  info "$MSG_BYPASS"
else
  bad "双扩展名未被拦截：$(echo "$UP_BYPASS" | head -c 200)"
fi

# ---- 场景 6：路径穿越 ----
echo -e "\n${B}▶ 场景 6：路径穿越文件名应拒绝${RST}"
UP_TRAV=$(upload "$BASE/$TID/attachments" "app.log" "../../../etc/passwd.log")
if [ "$(jnum "$UP_TRAV" "code")" = "40001" ]; then
  ok "路径穿越文件名被拒绝"
  info "$(jstr "$UP_TRAV" "message")"
else
  bad "路径穿越未被拒绝：$(echo "$UP_TRAV" | head -c 200)"
fi

# ---- 场景 7：内容查重 ----
echo -e "\n${B}▶ 场景 7：相同内容重复上传应拒绝${RST}"
UP_DUP=$(upload "$BASE/$TID/attachments" "app.log" "app-copy.log")
if [ "$(jnum "$UP_DUP" "code")" = "40004" ]; then
  ok "相同内容（换名）被识别为重复"
  info "$(jstr "$UP_DUP" "message")"
else
  bad "重复内容未被识别：$(echo "$UP_DUP" | head -c 200)"
fi

# ---- 场景 8：不同类型正常上传 ----
echo -e "\n${B}▶ 场景 8：其他白名单类型${RST}"
UP2=$(upload "$BASE/$TID/attachments" "deploy.json" "deploy.json")
UP3=$(upload "$BASE/$TID/attachments" "shot.png" "shot.png")
N_OK=0
[ -n "$(jnum "$UP2" "id")" ] && N_OK=$((N_OK+1))
[ -n "$(jnum "$UP3" "id")" ] && N_OK=$((N_OK+1))
if [ "$N_OK" = "2" ]; then
  ok "json 与 png 均上传成功"
else
  bad "仅 $N_OK/2 成功"
fi

# ---- 场景 9：附件列表 ----
echo -e "\n${B}▶ 场景 9：附件列表查询${RST}"
LIST=$(curl -s "$BASE/$TID/attachments")
N_LIST=$(echo "$LIST" | grep -o '"originalName"' | wc -l | tr -d ' ')
if [ "$N_LIST" = "3" ]; then
  ok "列表返回 3 个附件"
  info "含 sizeText 字段：$(echo "$LIST" | grep -o '"sizeText":"[^"]*"' | head -1)"
else
  bad "列表应有 3 个，实际 $N_LIST"
fi

# ---- 场景 10：预签名下载 ----
echo -e "\n${B}▶ 场景 10：预签名 URL 下载${RST}"
DL=$(curl -s "$BASE/attachments/$AID1/download-url")
URL=$(echo "$DL" | grep -o '"url":"[^"]*"' | sed 's/"url":"//;s/"$//' | sed 's/\\u003d/=/g; s/\\u0026/\&/g')
if [ -n "$URL" ]; then
  ok "预签名 URL 已生成"
  # 从宿主机访问需把容器内 endpoint 换成 localhost:19000
  DL_URL=$(echo "$URL" | sed 's|http://minio:9000|http://localhost:19000|')
  CONTENT=$(curl -s --max-time 15 "$DL_URL")
  if echo "$CONTENT" | grep -q "OOMKilled"; then
    ok "通过预签名 URL 下载到**真实文件内容**（非占位文本）"
    info "内容片段：$(echo "$CONTENT" | head -c 60)"
  else
    bad "下载内容不符：$(echo "$CONTENT" | head -c 150)"
  fi
else
  bad "预签名 URL 生成失败：$(echo "$DL" | head -c 200)"
fi

# ---- 场景 11：桶为 private，无签名不可访问 ----
echo -e "\n${B}▶ 场景 11：无签名直接访问应被拒${RST}"
RAW_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 \
  "http://localhost:19000/devops-ticket-attachment/$KEY1")
if [ "$RAW_CODE" = "403" ] || [ "$RAW_CODE" = "404" ]; then
  ok "无签名访问被拒（HTTP $RAW_CODE），附件不可匿名遍历"
else
  bad "桶可能为 public，无签名访问返回 HTTP $RAW_CODE"
fi

# ---- 场景 12：上传留痕 ----
echo -e "\n${B}▶ 场景 12：上传是否记入活动流${RST}"
ACT=$(curl -s "$BASE/$TID/activities")
if echo "$ACT" | grep -q "上传附件"; then
  ok "上传附件已留痕"
  info "$(echo "$ACT" | grep -o '"text":"上传附件","detail":"[^"]*"' | head -1 | sed 's/.*"detail":"//;s/"$//')"
else
  bad "上传未留痕"
fi

# ---- 场景 13：删除附件 ----
echo -e "\n${B}▶ 场景 13：删除附件${RST}"
DEL=$(curl -s -X DELETE "$BASE/attachments/$AID1")
if [ "$(jnum "$DEL" "code")" = "0" ]; then
  ok "附件元数据已删除"
  sleep 1
  STILL=$(mc_ls | grep -c "$(basename "$KEY1")")
  if [ "$STILL" = "0" ]; then
    ok "MinIO 中的对象也已清理（无孤儿对象）"
  else
    bad "MinIO 中对象残留"
  fi
else
  bad "删除失败：$(echo "$DEL" | head -c 200)"
fi

# ---- 场景 14：删除工单级联清理 ----
echo -e "\n${B}▶ 场景 14：删除工单是否级联清理附件${RST}"
curl -s -X DELETE "$BASE/$TID" > /dev/null
sleep 1
LEFT_DB=$(docker exec devops-pgvector psql -U devops -d devops_knowledge_db -t -c \
  "SELECT COUNT(*) FROM sys_ticket_attachment WHERE ticket_id='$TID';" 2>/dev/null | tr -d ' \n')
LEFT_OBJ=$(mc_ls | grep -c . || true)
if [ "$LEFT_DB" = "0" ]; then
  ok "附件元数据已级联清理"
  info "桶内剩余对象数：$LEFT_OBJ（应为 0，若非 0 说明有历史遗留）"
else
  bad "附件元数据残留 $LEFT_DB 条"
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
