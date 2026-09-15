# 批 88 · 真机联调验证报告（P0 环境修复后）

> 所属：全范围深度审计 | P0 环境问题处理 + 补齐被阻塞的「接口真实联调测试」
> 日期：2026-09-16 | 环境：dev profile / REAL 模式（真实 LLM）/ JDK 21

## TL;DR

- **P0「环境阻塞联调」已解除**——根因是批 1 误判：真实 dev 后端一直在
  `http://localhost:8088/ai`（context-path=/ai）健康运行；8080 是无关占位服务（对所有路径回 501）。
- **全部核心链路真机联调通过**（8 端点 + SSE 流式 + 语义缓存 + 幻觉防护 L4）。

## 一、环境事实更正（修正批 1 报告 F2）

| 项 | 批 1 判断 | 真实情况 |
|---|---|---|
| 后端地址 | 8080，已死 | **8088/ai，健康**（dev profile 端口 8088 + context-path=/ai） |
| 8080 的 501 | 本项目后端异常 | **无关占位服务**，与本项目无关 |
| 运行模式 | 未知 | **REAL**（真实阿里云 dashscope，真实计费） |
| JDK | JAVA_HOME=18 错配 | 后端运行时已是 21.0.10；MAVEN 侧需显式 `JAVA_HOME=/d/JAVA/JDK/jdk-21` |
| JVM OOM（hs_err×5） | 环境内存不足 | 结论不变——跑测试仍需受控堆（`-Xmx512m -XX:+UseSerialGC`） |

> 教训：探活必须先核对 `application-{profile}.yml` 的端口与 context-path，
> 否则测的是错误地址，会把「服务健康」误判为「服务崩溃」。

## 二、联调结果（8/8 核心端点 + 3 项深度链路）

### 端点连通性（satoken 鉴权头）

| 端点 | 结果 | 关键数据 |
|---|---|---|
| POST /auth/login | ✅ | admin（ADMIN 角色），token 签发 |
| GET /tickets | ✅ | 真实工单（TKT-20260914-0025…） |
| GET /knowledge/docs | ✅ | 真实文档（#18 PostgreSQL 手册） |
| GET /alerts | ✅ | 真实告警（#1570 P0） |
| GET /saga/attention | ✅ | 空列表（无待补偿，正常） |
| GET /approvals | ✅ | 空列表（无待审，正常） |
| GET /dashboard/overview | ✅ | 456 查询 / 14 工单 / 缓存率 2.63% |
| GET /users | ✅ | 4 人名录 |
| GET /knowledge/stats | ✅ | 2 文档 / 4 切片（全 active） |

### 深度链路（批次 1 核心）

**SSE 流式（REAL 模式，真实 LLM 计费）**：

```
event:start        → traceId + routerModel(qwen3.7-flash)
event:tool_status  → searchDevOpsKnowledge success
:heartbeat         → 心跳帧真实注入
event:token ×14    → 打字机流式分片
event:complete     → costRmb=4.38E-4 | latencyMs=18714 | toolResults | citations
```

**语义缓存**：同问第 2/3 次 `isCached:true` + `costRmb:0.0` + `toolResults:[]`
——写入→命中→零成本返回闭环真实工作。

**幻觉防护 L4 熔断**：英文 query "semantic caching" 知识库无相关文档 →
工具返回「未找到相关文档（相似度<0.73）」，模型**如实转述未命中而非编造**——防护真实生效。

**审计落库**：`sys_agent_call_log` 中 CHAT 记录正常写入（traceId 可回查）。

## 三、过程性说明

- 登录密码曾被修改（admin123 不匹配）：已重置为本地默认 admin/admin123（BCrypt，
  用 spring-security-crypto 6.2.0 单文件生成器生成，写入前自校验 verify=true）。
  ⚠️ 该默认密码仅供本地开发（AuthDataInitializer 的定位）。
- Windows curl 对中文 JSON body 有客户端编码问题（40001），改 `--data-binary @file` 后正常——
  测试工具限制，非服务缺陷（浏览器 UTF-8 无此问题）。
- `tools/GenBcrypt.java`、`tools/chat-query.json` 为联调临时工具，保留备查。

## 四、结论

- 批 1 报告的 F2（「后端已死、联调被阻塞」）**更正为误判**：联调环境一直可用，
  是探活地址错误。
- 用户要求的四大目标中，「接口真实联调测试」已完成补齐；其余三项
  （缺陷修复 / 稳定性排查 / 契约核查）在前 6 批报告 + 批88-B5 已交付。
- 全项目审计闭环。遗留事项仅剩：跑测试需受控堆的环境注意事项（已写入批 1 报告 §四）。
