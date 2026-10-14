# 上线放行 Runbook（DoD #6/#7 五件套：回滚 / 密钥轮换 / 看板入口 / HMAC 双通道 / RBAC 走查）

> 版本：v1.0 ｜ 日期：2026-10-05 ｜ 状态：✅ 成文（#6/#7「成文」侧闭）
> **来源**：ROI 清单 #2（DoD#6 回滚演练 + DoD#7 密钥/HMAC/RBAC 走查成文）。
> **复用声明**（不重复造）：AI Key 轮换 → `API-Key轮换SOP.md`；密钥分级/加密钥 → `部署运维手册 §3`；
> 备份恢复/DB → `部署运维手册 §4` + `backup-restore-演练记录.md`；值班排障 → `值班SOP.md`。
> **本页只做两件事**：补齐上述文档出生后才出现的缺口（HMAC 双通道、RBAC 矩阵、镜像回滚纪律），
> 以及把 DoD#6#7 拆到「照着做就能关」的颗粒度。

---

## 1. 回滚（DoD#6：两个 <5min）

### 1.1 自愈台账回滚 <5min —— ✅ 已实弹证明（2026-10-05）

```bash
# 前置：台账有 undoToken（scale 类有、restart 类恒无——见 1.1.3）
curl -X POST "$BASE/ai/api/v1/healing/executions/{id}/undo" -H "satoken: $T" -d '{}'
# 预期：<5s 返回 status=UNDONE，文案「已按执行前快照把…恢复为 N」
```

1. **已实证**：exec#15 `k8s.deploy.scale` 2→1→undo→**replicas=2 复原**，`undoSuccessRate=1.0`
   （证据：`docs/06-implementation-progress/2026-10-05-项5-L4白名单重启演练与回滚实证.md`）。
2. **restart 类无 undo 是设计非缺陷**：`undo_token` 恒空 → undo 请求得 `40001 无撤销凭据`（前置拒绝）。
   Pod 级重启的「回滚」= 让 Deployment 按期望态重建（本来就自动完成），不再造删除前快照的幻觉。
3. **K8s 发布级回滚**（改副本/改镜像后的回退）走 `k8s.rollout.undo`（HIGH_RISK_EXECUTION、
   白名单默认 disabled、四眼审批）——生产放行前**不动**，灰度期评审再开。

### 1.2 后端版本回退 <5min —— 规程已立，双 tag 实弹随灰度首部署

```bash
# 部署纪律（每次上线前必做，否则回滚无货可回）：
docker tag opsbrain-ai:latest opsbrain-ai:prev-$(date +%Y%m%d)
# 回滚 = 本地镜像切换，无网络拉取：
docker tag opsbrain-ai:prev-<YYYYMMDD> opsbrain-ai:latest
APP_PORT=18088 AI_MODE=MOCK docker compose -f docker-compose.yml --env-file .env -p opsbrain-prod up -d --no-build app
# 预期：<5min 内 healthy（HEALTHCHECK start-period 60s + ping 端点）
```

- **当前诚实状态**：仓库只有单 `latest` tag，**双 tag 回滚尚未实弹**（无货可演＝不假演）。
  灰度期首次上线即执行上面的 tag 纪律 + 回滚演练一次，把 DoD#6 的「后端版本回退」一栏关掉。
- DB 回滚不走 Flyway 反向（前滚制）→ **恢复=备份还原**，照 `部署运维手册 §4`（已有演练记录）。

### 1.3 意外态回滚（演练期自救）

- 白名单/策略改坏：`sys_action_allowlist` 有 `version` 乐观锁，DB 直改 `enabled=FALSE` 即刻停手；
  治理门是 DB 热读，改完下一发生效，无需重启。
- 前端/后端版本整体回退到 git：`git revert <sha> && 重新构建`（构建已一条命令，`项1` 文档 §二）。

## 2. 密钥轮换（DoD#7 第一列）

| 密钥 | 注入链 | 轮换动作 | 回归验证 |
|---|---|---|---|
| 模型/渠道 Key（百炼等） | `.env` → `API-Key轮换SOP.md` 全流程 | 照 SOP（含通知/CI/验证段） | `/ai/api/v1/health/ai-model` 正常 |
| `MODEL_KEY_CRYPT_SECRET`（AES-GCM） | `.env` → compose `app.environment` 透传（批89 补上，此前漏传=容器必明文） → `部署运维手册 §3` | **批89 实证七步**（旧说法「重启让 `AiChannelMirror` 重写密文」已证伪：镜像不重写，REAL 重启直接 `AEADBadTagException` 拒启）：① 旧钥运行时配置页「查看明文」备份全渠道密钥 → ② 停服 → ③ `openssl rand -hex 32` 换值 → ④ `AI_MODE=MOCK` 起（MOCK 不建 embedding bean 不解密）→ ⑤ 配置页逐页重存 → ⑥ 恢复 REAL 重启 → ⑦ 回归 | 各渠道「查看明文」全解得开 + `keyCryptConfigured=true` + 无 needsReEntry 黄标；**换代验证以 reveal 成功反证——health=200 不算数（可能还是旧进程，批89 实测踩过）** |
| **Webhook Token 通道**（本页新补） | `.env:ALERT_WEBHOOK_SECRET` → yml `devops.alert.webhook.secret: ${ALERT_WEBHOOK_SECRET:}` | **两处成对改**：① `.env`；② `monitoring/alertmanager.yml` `authorization.credentials` → `docker compose … up -d alertmanager` | 见下方「轮换三连」 |
| **指标抓取令牌**（2026-10-15 补） | `scripts/monitoring-init-token.sh` 生成 `monitoring/secrets/scrape_token` → app 读 `SCRAPE_TOKEN_FILE`（compose 挂 `/run/secrets/scrape_token`）+ Prometheus 读同一文件（`credentials_file`） | `bash scripts/monitoring-init-token.sh --rotate` ——**应用与 Prometheus 都不用重启**（应用每次请求重读文件、Prometheus 下轮抓取生效） | 裸抓 `/ai/actuator/prometheus` 应 401、带新令牌应 200 且含 `opsbrain_*`；同令牌打 `/ai/actuator/info` 必须仍 401（最小权限）。**不轮换的代价**：抓不到的指标让 `up{job=opsbrain}` 恒 0 → 宕机规则常亮误报（详见 `部署运维手册 §9.5`） |
| **Webhook HMAC 通道**（本页新补，dev 现未配=token 通道在岗） | `.env:ALERT_WEBHOOK_HMAC_SECRET` → yml `hmac-secret`（空=不验签） | 换 `.env` → `up -d app`；自定义源侧同步 secret | 签名探针 200 / 伪签名 40101 |

**轮换三连（换完必须跑）**：
```bash
curl -s $BASE/ai/api/v1/health/ping                          # 1. 进程活着
curl -s -X POST $BASE/ai/api/v1/alerts/webhook \
  -H "X-Webhook-Token: <新值>" -H 'Content-Type: application/json' \
  --data-binary @ascii-sample.json                            # 2. 新钥 200；旧钥 40101
# 3. 观察 10 分钟：Prometheus 无 40104 风暴、告警链心跳 OpsBrainPipelineWatchdog 持续跳动
```
> alertmanager.yml 注释里存着实测教训：**漏同步 credentials = 每条推送 40104、retry 一次后丢**——
> 「告警无声消失」的头号成因。轮换后第 3 步不可省。

## 3. 看板与健康总入口（一张表，prod/本地通用）

| 看什么 | 入口 | 用途 |
|---|---|---|
| 平台存活 | `GET :18088/ai/api/v1/health/ping` | 存活探针（不探下游） |
| 通知送达账 | `GET :18088/ai/api/v1/alerts/notify-health`（登录） | `configured/delivered/deliverFailed/lastDeliveredAt`（ROI#1） |
| RED 指标 | `GET :18088/ai/actuator/prometheus`（Prom `job=opsbrain` 抓） | `notify_delivery_total` 等 5 组计时器/计数器 |
| 告警规则/状态 | Prometheus `:9090`（rules `/api/v1/rules`，活告警 `/api/v1/alerts`） | 15 条规则含心跳与送达失败元告警 |
| 通知路由/静音 | Alertmanager `:9093` | silence 管理（值班 SOP §3 用） |
| 宿主指标 | node-exporter `:9100/metrics` | 907 条 node_*（rslave 挂载实证） |
| 自愈台账 | `GET :18088/ai/api/v1/healing/stats` + 处置中心页 | `undoSuccessRate/verifyFailRate/autoClosedLoop` |
| 效能/趋势 | 前端 `/effectiveness` `/monitoring`；Grafana 面板 JSON = `monitoring/grafana/opsbrain-effectiveness-dashboard.json`（7 面板，含「告警链 RED 自监控」送达块） | 周审视（灰度期照《生产部署评审》§四盯数） |
| 日志取证 | Loki `:23100`（logs profile 起）/ 宿主 `logs/dev-run-canary.log` | 诊断日志取证同源 |

## 4. HMAC / Token 双通道运维面（七项加固⑤收口）

**三通道判定序**（`WebhookGuard`，从上到下先命中先返回）：
1. 带 `X-Webhook-Signature` + `X-Webhook-Timestamp`（epoch 秒，±300s 窗）且
   `hex(hmacsha256(hmac-secret, ts+"."+rawBody))` 匹配 → **放行**（无需 token）；
2. 带签名头但 `hmac-secret` 未配 → **落回 token 通道**比较（当前 dev 形态）；
3. 无签名头 → `X-Webhook-Token` 或 `Authorization: Bearer` 原链比较。
- **恒定时间比较**；限流 `devops.alert.webhook.rate-limit`（默认 300/窗）；
  `require-secret=true` 生产必开（未配密钥=拒收而非放行，是设计）。
- **生产推荐配法**：Alertmanager v0.27 只支持 `http_config.authorization`（Bearer）——
  它走通道 3；**自定义接入源一律推荐通道 1 HMAC**（密钥不随请求暴露、带时间窗防重放）。
- rawBody 走 `WebhookBodyCachingFilter`（ContentCachingRequestWrapper）——签的是**原始字节**，
  重放/代理改字节即失配；中文体务必原字节 UTF-8 发送（GBK 坑，值班 SOP 同族）。

## 5. RBAC 矩阵走查（DoD#7 第二列）

**角色真值（`sys_user` 实测）**：`ADMIN`（admin、sse_b0ec8d63）、`OPS`（ops-tester）。

| 面 | ADMIN | OPS | 执法点（后端为准，前端 roles 只是体验） |
|---|:---:|:---:|---|
| 审批（approve/reject） | ✅ | ❌ | `ApprovalController` 类级 `@SaCheckRole("ADMIN")` |
| 审计日志 | ✅ | ❌ | `AuditLogController` 类级 ADMIN |
| 治理配置（白名单/风险/自动化策略） | ✅ | ❌ | `AutomationGovernanceController` 类级 ADMIN |
| 自愈执行/回滚 | ✅ | ❌ | `HealingController` 类级 ADMIN |
| 模型渠道 | ✅ | ❌ | `ModelChannelController` 类级 ADMIN |
| Agent 轨迹 / Saga / 负责人名录 | ✅ | ❌ | 三个 Controller 类级 ADMIN |
| 知识库编辑/删除（不可逆除外） | ✅ | ✅ | `KnowledgeWriteGuard` **显式 hasRole**（刻意不用 `SaMode`——注解写错会静默失效，见类注释）；OPS 不可执行不可逆操作 |
| 告警/工单/诊断/知识读 | ✅ | ✅ | 登录即可 |
| 前端处置中心入口 | ✅ | ❌ | `navigation.ts` `roles: ['admin']`（后端闸兜底） |

**走查清单（每角色 5 分钟，灰度期对两个账号各跑一遍）**：
```bash
# 1) 用该账号登录取 token 后：
curl -s -H "satoken: $T" $BASE/ai/api/v1/approvals/pending/count   # ADMIN=200；OPS 期望 403/无权限
curl -s -H "satoken: $T" $BASE/ai/api/v1/healing/stats            # ADMIN=200；OPS 期望拒
curl -s -H "satoken: $T" $BASE/ai/api/v1/knowledge/docs?size=1     # 两角色皆 200（读面）
# 2) 前端：OPS 登录应看不到「处置中心」入口；ADMIN 可见。
```
> **诚实占位**：以上矩阵 = 源码执法点静态实证；**双账号实弹走查**随灰度（当前仅 admin 凭据在手，
> ops-tester 密码未知——不要为走查弱造口令，灰度开跑时与真账号一起过）。

## 6. DoD 对账（本页关了哪两栏）

| DoD | 关单条件 | 本页后状态 |
|---|---|---|
| #6 回滚演练 | 自愈台账 <5min ✅ **已实弹**；后端版本回退 <5min | 规程+tag 纪律 ✅ 成文；**双 tag 实弹 = 灰度首部署当天必做**（§1.2） |
| #7 密钥/HMAC/RBAC 成文 | 三者成文可照做 | ✅ 全部成文（§2 §4 §5）；双账号走查实弹随灰度（§5 诚实占位） |

**下一步不变**：灰度试运行开跑（开跑日=DoD 起算日），当天动作清单 = §1.2 tag 纪律 + §5 双账号走查
+ 值班 ACK 演练 ×3（15 分钟口径）+ 首批 runbook 导入后的检索命中抽查。
