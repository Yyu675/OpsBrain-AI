# 批次 1 审计报告：对话 / Agent 编排 / 语义缓存 / 工具运行时

> 所属：全范围深度审计（分阶段逐批） | 批次 1（共 6 批）
> 审计日期：2026-09-16
> 覆盖模块：M1 对话接入（SSE）/ M2 Agent 编排 / M3 意图路由 / M4 语义缓存 / M6 工具运行时（含 Saga 补偿）
> 检查维度：静态缺陷 · 契约一致性 · 并发正确性 · 测试覆盖 · 运行稳定性

## TL;DR

- 批次 1 核心链路**代码工程质量高**，多轮迭代成熟，**未发现新的 P0/P1 逻辑缺陷**。
- 但审计暴露三类**环境/工程问题**，其中「本机 3.5GB 内存致 JVM 反复 OOM 崩溃、后端 8080 已死」是**阻塞"接口真实联调测试"目标的 P0 环境问题**。
- 批 88-B4 刚提交的 **3 个并发正确性修复全部零测试覆盖**（最易回归的时序/并发逻辑）。

---

## 一、静态审计结论（F4）——代码质量高，无新 P0/P1

| 文件 | 结论 | 亮点 |
|---|---|---|
| `DevOpsChatController` | ✅ 正确 | SSE 心跳穿透代理、时段取消、限流（按 userId 优先）、空查询/限流均落 REJECTED_* 审计 |
| `DevOpsAgentServiceImpl` | ✅ 正确 | 预算裁剪结果真正使用（修过"空转"）；缓存按权限域隔离；ThreadLocal 跨线程（quotaKey/knowledgeScope）必须在请求线程解析；异常审计带业务栈帧 |
| `streamAgent` | ✅ 正确 | 跨线程集合用 CopyOnWriteArrayList/StringBuffer；Saga 状态机时序（先 TOOLS_RUNNING 后 WAITING_APPROVAL）；取消标记清理权移交流终点 |
| `WebConfig`（鉴权） | ✅ 正确 | chat 白名单外确实需登录；authEnabled 开关有生产防护 + 启动告警；CORS 生产通配符拒启 |
| `AuthController` | ✅ 正确 | 登录限长防 BCrypt DoS；me 踢残留 token；视图剥离密码 |

**结论**：批次 1 主链路（M1/M2/M3/M4/M6 的编排核心）静态扫描**未发现新的 P0/P1 缺陷**。此前的多轮审计（批 85/86/87/88）已将主要正确性问题收敛。

---

## 二、测试缺口（F3）——批 88-B4 新增并发修复零测试覆盖

批 88-B4 提交了 3 个并发正确性修复，**均无任何测试**：

| 新逻辑 | 位置 | 风险 |
|---|---|---|
| `releaseIdempotencyLock()`（失败释放幂等锁 + Lua CAS，防"一次失败冻结工具 24h"） | `ToolRuntimeManager` | 最容易回归的时序逻辑 |
| 超时 `future.cancel(true)`（中断后台任务，防副作用堆积） | `ToolRuntimeManager` | 并发/时序 |
| `markCompensating()` CAS 抢占（防并发重复补偿副作用）+ 反射白名单 | `SagaCompensationManager` | 并发 + 安全 |

`ToolRuntimeManagerTest`（411 行）覆盖了幂等/熔断/超时/重试/解包，**但未覆盖上述批 88-B4 新增路径**。Saga 侧同样缺 CAS 抢占断言。

---

## 三、运行环境问题（F1/F2/F5）——P0，阻塞联调

### F1 · 本机内存严重不足，JVM 反复 OOM 崩溃

- 5 个 `hs_err_pid*.log` **全部是** `There is insufficient memory for the Java Runtime Environment to continue`（进程级/原生内存分配失败，非堆内 OOM）。
- 崩溃触发点（最后一个 `hs_err_pid34664`，Sep 15 23:41）：`mvn test -Dtest=ToolRuntimeManagerTest -Xmx2g`。
- 本次审计复现：即便 `-Xmx512m -XX:+UseSerialGC`，Maven 测试仍触发 `Native memory allocation (malloc) failed` —— **不是堆设得太大，是整机可用物理内存不足**。
- 根因：批 88-A 记录本机仅 **3.5GB 物理内存**，同时承载全栈 Docker（pgvector/Redis/MinIO/Prometheus/Alertmanager/node-exporter/adminer）+ vite 前端 node + 后端 JVM，内存被耗尽。

### F2 · 后端 8080 已死（连接拒绝）

- 审计开始时 8080 有响应（`/api/v1/health` 200 等），随后逐步退化（501→连接拒绝），现**彻底 000**。
- `/api/v1/chat/stream`（GET/POST）返回 501 "请求的接口暂未实现"，但源码 `DevOpsChatController` 明明有实现且 class 已编译 —— 实例与源码不一致，判定为**正在崩溃循环/不健康的进程**。
- 前端 5173 正常（vite dev server 未受内存影响）。

### F5 · JAVA_HOME 错配为 JDK 18，项目要求 JDK 21

- `JAVA_HOME=D:\JAVA\JDK\jdk-18.0.2.1`（JDK 18），但 pom 编译 release 21 → `不支持发行版本 21`。
- 本机已有 JDK 21：`D:\JAVA\JDK\jdk-21`（21.0.10 LTS），**切换后即可正常编译**。

### 影响

- 「接口真实联调测试」目标被 F2 阻塞：8080 后端不可用。
- 「运行稳定性」目标已被 F1 充分证明：**这是一台无法同时承载全栈 + 测试的机器**。

---

## 四、建议

1. **环境治理（用户决策）**：释放内存（停部分 Docker 容器）后重启后端做真机联调；或换一台内存充足的环境。JDK 错配建议将 `JAVA_HOME` 切至 jdk-21（低风险，不影响业务）。
2. **补测试（建议本批或批 5 执行）**：为批 88-B4 的 `releaseIdempotencyLock` / `future.cancel(true)` / `markCompensating` CAS 补单元测试，防回归。
3. 批次 2~6 继续推进：代码审计不依赖内存环境，可先完成全部静态审计；联调测试集中在环境修复后执行。

## 附：本批涉及文件

- `src/main/java/com/devops/agent/controller/DevOpsChatController.java`
- `src/main/java/com/devops/agent/application/impl/DevOpsAgentServiceImpl.java`
- `src/main/java/com/devops/agent/application/runtime/ToolRuntimeManager.java`
- `src/main/java/com/devops/agent/application/runtime/SagaCompensationManager.java`
- `src/main/java/com/devops/agent/controller/config/WebConfig.java`
- `src/main/java/com/devops/agent/controller/AuthController.java`
- `src/test/java/com/devops/agent/application/runtime/ToolRuntimeManagerTest.java`
