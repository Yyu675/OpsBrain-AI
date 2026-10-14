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
| `closed_loop_drill.py` | 六条核心链路的端到端契约 | 见下节「业务闭环实弹」 |
| `rbac_walkthrough.py` | `OpsWriteGuard` + 类级 RBAC 门控 | 见下节「权限矩阵实弹」 |
| `monitoring_audit.py` | 指标名 / 规则 / 看板 / 抓取面四方对账 | 见下节「监控面静态对账」 |
| `selftest_scrape_face.py`（本地件，见 `.local-verify/scripts/`） | 闭环演练的 Z-监控面 判据自测 | 用桩服务模拟「正确/裸奔/凭据错/文件缺」四种后端，确认这个哨兵本身不会永远 PASS |

## 业务闭环实弹（`closed_loop_drill.py`）

三场景、42 跳断言（其中 1 跳是 RAG 语义软闸），把「告警进来 → 建单 → 诊断 → 处置 → 恢复 → 复盘沉淀」走一遍并逐跳断言：

| 场景 | 验的是什么 |
| --- | --- |
| A 高危 P1 | 主链 + 时间穿透（事件墙钟不是收到时刻）+ 知识飞轮沉淀 |
| B P2 抖动 | 观察窗降噪（窗口内自愈**不建单**）真的生效 |
| C 历史回放 | 非预期时序下的行为可解释，且**页面显式标注「不会再自动建单」**（F2） |

```bash
python3 tools/verify/closed_loop_drill.py --base http://localhost:8088/ai \
    --account admin:admin123 [--psql <psql> --pgurl <dsn>] [--require-rag-hit] \
    [--scrape-token-file monitoring/secrets/scrape_token] [--json /tmp/cl.json]
# 退出码：0=硬闸全过；1=有硬闸失败（逐跳点名）；2=环境/前置不满足
```

`--scrape-token-file` 给的是**监控面**校验（Z-监控面 组）：无凭据抓取必须 401、带凭据必须 200 且含 `opsbrain_*`——抓取面一旦不通，`up{job=opsbrain}` 恒 0 会让宕机规则常亮误报，"闭环没问题"这个结论本身就失去依据。不给该参数时该项记 **SKIPPED**（不是静默跳过）。

口径提醒：`AI_MODE=MOCK` 下知识检索命中是**桩向量**决定的（无语义），所以「RAG 命中」默认是
软闸；REAL 模式再加 `--require-rag-hit` 转硬闸。方案与实跑记录见
`docs/09-operations/业务闭环能力验证方案.md`。

## 监控面静态对账（`monitoring_audit.py`）

监控有三个「各自都对、拼起来是空」的失效方式，且**都不报错**：指标名对不上（空面板）、
规则查的指标没人供给（永不触发）、抓取面被鉴权拦掉（面板全空 + `up=0` 让宕机规则恒亮）。
人眼审 yml 抓不住这类漂移，把判据固化成脚本：

```bash
python3 tools/verify/monitoring_audit.py [--probe http://localhost:8088/ai/actuator/prometheus] [--json /tmp/audit.json]
# 退出码：0=无硬缺口；1=有硬缺口（逐条点名）；2=环境不满足
```

检查面：A 指标名对账（引用 vs 代码声明/框架自动/部署侧三类供给）· B 抓取面鉴权
（`/actuator/**` 若需登录，prometheus.yml 就必须带凭据）· C 外部供给（exporter 服务 + 抓取 job 两件齐）
· D CI 是否上闸 · E 实测抓取面状态码 · F 无人消费的指标（提示）。

**上闸顺序**：先修硬缺口，再接进 CI——红门禁会被无视，比没有门禁更坏。

**CI 侧的镜像**：`MonitoringConfigContractTest`（后端测试腿，随 `编译与测试` 一起跑）用同一套判据做静态对账；它跑在既有测试腿里，所以**不需要改 workflow**。两边读同一份前缀表与提取规则，改判据必须同步修改（否则就是「核对另一份规则」）。

## 权限矩阵实弹（`rbac_walkthrough.py`）

14 个探针面 × 3 个账号（42 格），判据**取自源码执法点**（类级 `@SaCheckRole`、
`OpsWriteGuard` 资源级守卫），不一致即红并逐行点名——这正是 DoD#7 第二列要的
「双账号实弹走查」，比人工点页面更可复现。

```bash
python3 tools/verify/rbac_walkthrough.py --base http://localhost:8088/ai \
    --account admin:admin123:ADMIN [--account ops:<pwd>:OPS] [--account viewer:<pwd>:VIEWER] [--json /tmp/rbac.json]
```

## 发布回退实弹（`scripts/rollback-drill.sh`）

DoD#6 的「后端版本回退 <5min」：两种模式（沙箱无 Docker 时用 `artifact`，两个发布目录切换；
生产用 `docker`，双 tag + `compose up -d`）。版本身份**用契约差异证伪**（新版独有端点在新版必须存在、
旧版必须不存在）——只看 HTTP 200 会误判：旧版也有通配路由，两版都回 200。
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
