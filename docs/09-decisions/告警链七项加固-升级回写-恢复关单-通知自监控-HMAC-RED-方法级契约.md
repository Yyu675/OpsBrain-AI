# 决策：告警链七项加固（升级回写 / 恢复关单提示 / 通知自监控 / HMAC 签名 / RED 指标 / 方法级契约）

**日期**：2026-10-03（执行跨 10-02/03 两日）
**状态**：✅ 已全量落地
**来源**：用户「资深角度评估全链路 → 7 项全做，先给方案再执行」；唯一业务分叉②拍板 = **提示关单（hint 默认，auto/off 可配）**。

---

## 一、七项落地

| # | 方案 | 要点 | 落点 |
| - | - | - | - |
| ① | 告警升级回写 | `ON CONFLICT DO UPDATE` 刷源真值 level/description/labels/annotations（severity 不在去重键→同键升级）；advisory 预读旧级只作升级判定（写路径仍单语句原子）；工单**只升不降**：`raisePriorityFromAlert` → `applySlaDeadlines`（建单时刻基准单源）→ 乐观锁 update → 活动流「告警级别升级」+ P0/P1 强提醒；降级只刷告警 | AlertRepository / AlertService / TicketService |
| ② | 恢复关单提示（拍板 hint） | `resolve-close-policy: hint\|auto\|off`；hint=活动流「已恢复…可关单」不改状态；auto=**仅当该单最后一条活跃告警恢复**才自动 RESOLVED（`countOtherActiveByTicket` 守卫组单不误关）；`resolved_at = endsAt` 真值（year>1 判零回退 now） | AlertService.applyResolveClosePolicy |
| ③ | 通知渠道自监控 | BusinessMetrics 原子账 + lastSuccessAt；`NotifySilentWatchdogScheduler` 5min delta（attempts>0 且 success==0 **且 available()** 才报，未配置=未知不误报）→ `OpsBrainNotifySilent` 元告警 + 恢复自愈；`GET /api/v1/alerts/notify-health` 快照（与 logs-heartbeat 同族） | application/runtime / AlertController |
| ④ | endsAt 穿透 | `AlertSignal.endsAt` → `resolve(id, resolvedAt)`——MTTR 用真实恢复时刻而非到达时刻 | AlertSignal / Adapter / AlertService / AlertRepository |
| ⑤ | Webhook HMAC 签名 | `X-Webhook-Timestamp`(epoch s, ±300s) + `X-Webhook-Signature=hex(hmacsha256(secret, ts+"."+rawBody))`；rawBody 走 `WebhookBodyCachingFilter`(ContentCachingRequestWrapper 标准件)；三通道：签名有效→过；有签名头但 secret 空→落 token 通道；无签名头→token/bearer 原链（**零破坏**） | WebhookGuard / 新 Filter / AlertWebhookController |
| ⑥ | 平台 RED 指标 | `opsbrain.alert_received/alert_dedup/ticket_autocreate_seconds/diagnosis_seconds/notify_total` → 原生 `/ai/actuator/prometheus`（exposure 已含）+ `monitoring/prometheus.yml` job=opsbrain（`metrics_path:/ai/actuator/prometheus`、host.docker.internal 模式——9-25 修正沿用） | BusinessMetrics / 调用点 |
| ⑦ | 方法级契约测试 | 前端 `apiContract.test.ts`：扫后端 controller 源（剥注释/FQN/命名参）× `API_ENDPOINTS`（裸调用 vs `${}`前缀定性、模板 `}` 后再看一位）段级比对——与后端类级 v1 契约互补 | devops-platform-frontend/src/__tests__/apiContract.test.ts |

**现成件复用声明（不自造轮子）**：ContentCachingRequestWrapper（Spring 标准）/ ON CONFLICT（PG 原生）/ NotifySilentWatchdog 复刻 LogsWatchdog 模式 / 快照端点复刻 logs-heartbeat 形态 / `applySlaDeadlines`·`ReservedAlertNames`·`safeMarkdown`·html 白名单全走既有单源 / 源扫描测试家族（SilentCatch→KnowledgeWriteGuard→ApiPrefix→apiContract→AlertRepositorySql 同构）。

## 二、过程中揪出并修掉的三枚真缺陷

1. **双 ON CONFLICT 静默吞告警**（最重）：改①时 `ON CONFLICT` 前缀插重 → SQL 语法错 → **所有真实告警入库持续失败**，逐条护身 catch 吞成 200、Alertmanager 视 200 为成功不重投 = **静默丢告警**。单测 mock JdbcTemplate 执行不到真实 SQL，66 绿拦不住一条语法错——真机钻 E 第一发撞出。修复 + `AlertRepositorySqlContractTest` 源码钉子（SQL 字面量 `"ON CONFLICT` 恰好 1 次 + 谓词/DO UPDATE 连续断言）。
2. **文件 NUL 污染**：`apiContract.test.ts` 两次写入混入 0x00（一次 143B 截断、一次 join 分隔位）→ vitest 拒收。Buffer 字节手术修复；NUL 扫描成为该文件隐式检查。
3. **旧孤儿双实例假阴性**：8088 上 10-01 11:58 起的孤儿跑旧代码 → 「notify-health 40001 / 计数器不出现」两轮假阴性。教训：重启后验进程 CreationDate + canary「Started DevOpsPlatform…」行 + 指标 grep 别自带错锚（`alert_received\{` 漏 `_total`）。Docker WSL 恢复手册第 4 次实证（vhdx 卡死 → 五步复起 15s）。

## 三、验证证据（全部真机/活体）

- **升级链钻 E**：E1(P1)→alert 19477 P1、工单 P1（响应 19:38/解决 10-03 03:08）→ E2 同键 P0 → **同行刷新** level=P0 occurrence=2、工单 P0（19:23/23:08 SLA 重算）、活动流「告警级别升级 ## 告警「LevelUpgradeDrillE」级别 P1 → P0（工单优先级 P1 → P0）」
- **恢复链钻 F**：`resolved_at=2026-10-02 19:05:00` **= endsAt 原值**；hint 活动流「已恢复（19:05）可关单」；工单仍 PENDING（hint 不抢关）✅
- **HMAC 四态**：伪签名→40101；无签名头无 token→40101；无签名头纯 token（secret 已配）→200；**仅 HMAC 合法签名（无 token 头）→200 入库**，19896 即时+30s 持久。单测 17/17（含 token/HMAC 双密钥共存新测）
- **通知**：`/notify-health` 活体 attempts=6/degraded=6/configured=false/silent=false 真实对账
- **RED**：`/ai/actuator/prometheus` 含 5 组计时器/计数器（alert_received=8 起步）；**Prometheus `job=opsbrain health=up lastError=""` lastScrape 实时**
- **测试**：后端目标集 88/88 + WebhookGuard 17/17；前端全量 **100 文件 1812/1812 exit 0**；截图技能两页 **console errors: none**
- **演练治理**：0004 唯一演示样例；0002/0005 及钻 A/A2 演练告警全 RESOLVED、演练单全 VOID

## 四点五、ROI 第 1/2 项落地追记（2026-10-03，拍板后执行）

| 项 | 落点 | 关键语义 |
| - | - | - |
| **ROI#1 送达级监控** | `BusinessMetrics.notifyDelivery` + `DingTalkNotifier.doSend` 三出口埋点（ok/error/异常）；快照 +`delivered`/`deliverFailed`/`lastDeliveredAt`；Grafana「告警链 RED 自监控」第 7 面板（y=24，7 系含送达/回退） | **受理 ≠ 送达**：`notify_total{result}` = 渠道接口受理；`notify_delivery_total{result=ok\|error}` = 渠道 HTTP 亲口回执（2xx + errcode=0）。看门狗 silent 判定仍用受理账（送达失败语义不同，注释已声明） |
| **ROI#2 时间回退可见** | `processSignal` 入口守 startsAt（<2000/空）；`handleResolvedAlert` 守 endsAt → WARN + `opsbrain.alert_time_fallback_total{field=startsAt\|endsAt}` | 回退 now 只能是兜底——静默回退会把时序/MTTR 全算错，「源没给时间」必须可计数可查 |

**真机证据**：假钉钉（127.0.0.1:19099 固定 `{"errcode":0}`）→ 5 条通知全部
`delivery ok=5`、快照 `delivered=5 + lastDeliveredAt 有值 + configured=true`；
钻 I 无 startsAt 建单 `first=now` 且 `fallback startsAt=1 + WARN 1`、
无 endsAt 恢复 `resolved_at=now` 且 `fallback endsAt=1`。测试 8 类 **104/104**
（BusinessMetricsTest 增送达/回退/快照断言，AlertServiceTest 增双守卫用例）。

**本批工程教训**（防复发写死）：① Edit 多次「工具报成功实未落盘」+ 并行验证抢跑
→ 一律**串行 grep 验证后再编译**；② `Stop-Process` 被防护层 255 静默拦 → 用
`taskkill //F //T //PID`；③ playwright 残留 42 个 chromium 吃 commit 内存致
后端 test-compile OOM——收尾先清浏览器进程。

## 四、边界与后补（防循环恶化，详见 ROI 清单）

- notify `success` = 「至少一渠道**受理**」≠渠道 HTTP 送达（Alertmanager v0.27 发不了自定义签名头是硬约束）→ 送达码落点 + Grafana 面板 = 下一批 P1
- `startsAt` 非 Z 格式偶发回退 now（Drill 用 +08:00 观察到；Alertmanager 发 Z 无碍）→ 自定义源接入前补解析 WARN
- ⑦ 只校验 path 不校验 method（API_ENDPOINTS 无 method 字段）→ openapi 生成方案一并解决
- 通知健康前端卡片 P2（/notify-health + 元告警已在告警页可见，不加新 UI 面）
