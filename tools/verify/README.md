# tools/verify —— 无 Maven 的离线行为探针

CI 之外的第二条验证通道：沙箱/离线机器装不上 Maven 依赖（没有 maven central 网络），
但模板类、聚合器一类**纯函数**不需要 Spring 上下文就能验证。这里放的就是它们的离线探针。

## 为什么要有这个目录

- 改卡片/模板文案时，等 CI（约 6~9 分钟）才知道对错，反馈环太长；
- 离线环境没有 `./mvnw test`，但这不代表「只能靠肉眼看代码」；
- **探针不是测试的替代品**：同一批契约在 `src/test/java/**` 里有 JUnit 版本（CI 跑它）。
  改了一边必须同步另一边，否则会造出「CI 绿、本地红」或反过来的假象。

## 现有探针

| 探针 | 对应契约 | 覆盖 |
| --- | --- | --- |
| `AlertNotifyTemplateProbe.java` | `AlertNotifyTemplateTest` | 触发卡事实/链接/转义/估算标注、恢复卡三态收尾与回落取值、升级卡插入位置、诊断卡逐方向状态与恒 normal、行级与整卡两级截断 |
| `closed_loop_drill.py` | 六条核心链路的端到端契约 | 真告警 → 归一化 → 建单 → 双向溯源 → 诊断（四路证据 + 充分度语义）→ 处置五连 → 恢复穿透 → 复盘沉淀；另含观察窗降噪与历史回放两个场景。41 跳硬闸，逐跳点名。方案说明见 `docs/09-operations/业务闭环能力验证方案.md` |
| `rbac_walkthrough.py` | `OPS 写守卫 / ADMIN 专有面` 的实弹口径 | 14 个探针面 × N 账号（默认 ADMIN/OPS/VIEWER），判据**取自源码执法点**而非"跑出来什么抄什么"，不一致即红并逐行点名（DoD#7 第二列） |

**相关脚本**（住在别处，但与上面几件是一套验证面）：`scripts/rollback-drill.sh`（DoD#6 版本回退实弹，
`artifact`/`docker` 双模式，版本身份用契约差异证伪）、`scripts/e2e-business-flow.sh`（跨模块业务流直查库版）。

## 怎么跑

需要两样东西：一个 JDK 的 `java`（≥17 即可，探针只用 JDK 标准库），以及 ECJ（Eclipse 编译器，
单 jar 免依赖，用来在没有 Maven 的情况下编译 `src/main/java` 里的子集）。

```bash
JAVA=/path/to/java
ECJ=/path/to/ecj.jar

# 1) 编译（-sourcepath 让 ECJ 顺带编译模板依赖的实体/枚举类）
"$JAVA" -jar "$ECJ" -encoding UTF-8 -source 19 -target 19 -proc:none -nowarn \
    -d /tmp/tpl-probe -sourcepath src/main/java tools/verify/AlertNotifyTemplateProbe.java

# 2) 运行（-Dstdout.encoding 保证中文断言可读）
"$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -cp /tmp/tpl-probe com.devops.agent.domain.alert.AlertNotifyTemplateProbe
```

期望输出：`PASS: 合并模板 29 项行为校验`。断言失败会直接抛 `AssertionError` 并打印实际渲染结果。

## 局限（写清楚，免得当成 CI 用）

- 只覆盖**纯函数**；任何需要 Spring 容器、数据库、HTTP 的契约（`AlertServiceTest`、
  `DiagnosisOrchestratorTest`、Flyway 迁移链）只能在 CI 或本地 `./mvnw verify` 里跑。
- ECJ 的 `-source 19` 是它能接受的最高版本，与项目 `maven.compiler.release=21` 有差异；
  探针只用 17 以内的语法，因此不影响结论，但**不要**用它替代真实编译做发布判断。
