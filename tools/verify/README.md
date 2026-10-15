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
| `ci_summary.py` | 演练证据 JSON → 一行 CI 通知 | 见下节「结论怎么变成可读证据」 |
| `grayscale_metrics.py` | 灰度台账 §三 六行的**取数口径**（DoD「真实值班质量数」） | 见下节「灰度台账取数」。配套 `test_grayscale_metrics.py`（50 例：口径 + 取数胶水 + 退出码）+ `test_grayscale_metrics_pg.py`（7 例真迁移库/HTTP/CLI 集成）。**已进 CI**（e2e 腿第 14 步，排在 RBAC 之后，对真迁移库跑） |
| `selftest_scrape_face.py` | 闭环演练 Z-监控面 哨兵的**自证** | 桩服务六态（正确/裸奔/凭据错/端点在但无业务指标/指标排在 4KB 之后/令牌文件缺）逐一核对判词，确认哨兵不会永远 PASS。**已进 CI**（e2e 腿，排在闭环演练之前）。2026-10-15 前是 `.local-verify/scripts/` 的本地件——沙箱重置即丢失、别人复现不了，故收进仓库 |

## 工作流引用、漏洞扫描与覆盖审计（批110–114）

- `workflow_refs.py`：`PyYAML==6.0.3` + 已授权 `gh`，只读 API 递归查 action/workflow 元数据，不执行远端代码。403/网络错误报红；运行 `python3 tools/verify/test_workflow_refs.py` 后可连接真实 API 自检。
- `trivy_summary.py --json <报告> [--log <CLI诊断>]`：文件名区分 raw/gate，缺/坏报告失败；不输出 Secret Match，不替代 Trivy 原始退出码。
- `trivy_policy.py`：生产只有一次 `fs` 生成 raw，按0.70.0官方 **Status=fixed** 规则离线派生候选，再交 `trivy convert` 判门禁。不是按 FixedVersion 是否为空；不裁剪其他发现/包/元数据。raw/candidate/gate/manifest/日志均留档。**不再有第二遍生产 fs。** 详见批113决策。
- `selftest_trivy_reports.py --json <文件>`：真实工具+漏洞DB+本地Maven仓库，注入429验证“重扫可能静默丢传递依赖，但同源convert仍红”。`--matrix` 才重跑历史在线矩阵，按逐包/路径发现与包清单比较，不只认指定CVE。夹具不降级生产依赖。
- `maven_scan_coverage.py`：后置独立 job 下载**同一次 workflow run**的 raw，用 Maven 真实 runtime tree 逐 GAV 对账，避免预热生产首扫。missing/版本差异/extra 分开，保留 type/classifier但不宣称字节级覆盖；排除根项目及test/provided/system子树，optional直依赖不能豁免。**合法差异退出0+warning/status=incomplete，工具/输入错误退出1；这不是覆盖放行闸。** `--strict` 可用于反例或未来显式门禁，不在当前CI默认启用。

复跑覆盖对照（需 Maven 已正常解析依赖；不是 fake CLI）：

```bash
./mvnw -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree \
  -Dscope=runtime -DoutputType=json -DoutputFile=/tmp/maven-runtime-tree.json
python3 tools/verify/maven_scan_coverage.py --tree /tmp/maven-runtime-tree.json \
  --report <同commit的原始trivy-report.json> --pom pom.xml \
  --checkout-sha "$(git rev-parse HEAD)" --json /tmp/maven-scan-coverage.json
```

CI 将 tree/raw/pom SHA256 与实际 checkout SHA 留档。完整审计 JSON/树/日志上传 artifact；GitHub 单注解实测约4KB截断，压缩JSON分片各带part/parts/sha256，按part拼接data→base64解码→zlib解压→校验SHA256才能作完整证据。超出6片时明确只在artifact，不把截断串当完整JSON。

本批运行 `test_maven_scan_coverage.py`、`test_maven_coverage_workflow.py` 以及既有5组 Trivy/引用测试；shell中的fake Maven/Trivy只证控制流。真实数据与方案决策见 `docs/09-decisions/2026-10-16-Maven首扫覆盖审计与后续输入决策.md`。修改检查工具/扫描工作流或pom触发限定PR真扫，普通业务push不额外下载漏洞库。**零漏洞≠覆盖完整；审计成功≠运行时安全或灰度放行。**

### 原生 runtime SBOM 与 Boot 库字节实验（批115）

审计后置job固定CycloneDX2.9.1，真实package并生成1.6 JSON（compile/runtime，不含provided/test/system）；Trivy0.70.0对SBOM一次扫描，同源convert保留原生失败码。`sbom_runtime_probe.py`核树variants→SBOM→扫描GAV，再以原生SBOM SHA256绑定实际Boot内嵌库，列出未打包组件、额外SBOM组件和未知Boot库。未知字节不按文件名猜身份；发现按包/版本标注是否有打包字节匹配，不能将未打包组件称部署漏洞。原始SBOM/报告/原码/日志/完整字节清单均artifact，超长取证仅发显式紧凑分片（不含完整库数组），分片sha与完整结果sha分开。

首实跑发现58条HC记录且native gate=1，Boot工具库未入原生SBOM，故**这是输入实验而非生产放行，不直接替换原前端/Secrets扫描**。`test_sbom_runtime_probe.py`、`test_sbom_workflow.py`分别验证语义与实际shell协议；fake可执行文件不冒充真实构建/扫描。完整分析/利弊/处置边界见 `docs/09-decisions/2026-10-16-原生SBOM实验与运行时漏洞处置决策.md`。

## 灰度台账取数（`grayscale_metrics.py`）

台账 §三 有六个数据槽位要按周填。**Week1（10-06~10-12）填不出来**，台账如实记了「不可回溯，属实际缺口」。
根因不是忘了记，而是**没有取数口径**——所以 Week2 若还靠手记，会变成第二个不可回溯的窗口。

```bash
python3 tools/verify/grayscale_metrics.py --from 2026-10-13 --to 2026-10-19 \
    --psql psql --pgurl postgresql://devops:***@localhost:25432/devops_knowledge_db \
    --base http://localhost:8088/ai --scrape-token-file /tmp/e2e-scrape-token \
    --json /tmp/grayscale.json
```

**时间边界（批109）**：上例是计划窗口，运行时自动将终点截到**运行机本地昨天**；
今天也不是完整日，不计为零误报日。若起始日尚未完整结束，退出 2 且不取数。
运行机本地日期须与数据库业务日期一致，输出 `collected_at`（含时区偏移）便于复核。
10-15 运行时实际窗口是 10-13~10-14，**不是 7 天**；10-20 才能取完整 Week2。

输出表格每行带**来源/口径**，不可不经审查直接填“达标”。六行的出处：

| 台账行 | 来源 | 要点 |
| --- | --- | --- |
| 连续 0-P1 天数 | `sys_alert.false_positive` + `level` | 按 `false_positive_marked_at` 落窗；只算已结束日期的**尾部连续段**；缺记录不等于无误伤，运行覆盖/标记流程须人工核验，不自动判 DoD |
| ACK 演练次数 | **人工登记** | DoD#3 要求真人 + 真渠道，系统不可派生——工具只留槽位并说明原因，不猜 |
| 通知送达率 | Prometheus `opsbrain_notify_delivery_total{result}` | **当前进程累计回执**，不是 `--from/--to` 增量，重启会重置；不是数据库表；真渠道需确认 |
| 告警转单率/诊断充分率 | `sys_effectiveness_snapshot` 增量 | 快照列是**累计值**；末读数必须在窗口内，缺任一端/负增量报失败，不从 0 猜算。每日 09:20/启动采样，仅近似增量（非日终），不是诊断启动率或报告生成率 |
| 修复时长对比 | `sys_devops_ticket.mitigated_at − create_time` | 工单创建列叫 `create_time`（不是 created_at） |
| 自愈执行次数 | `sys_healing_execution` | 按 `created_at` 计数，成功 = `status='SUCCEEDED'` |

**为什么配了口径单测与真库集成**：这个工具有两种错法，都得测——

1. **安静地给出错的数**（累计列被 SUM、连续天数算成总和、`_created` 序列混进计数）：比崩掉危险得多，台账要拿它关 DoD；
2. **走进「有库」分支就崩**：首轮 CI 正是崩在这儿——`Taker.q()` 返二元组、调用方按三元组解包（`ValueError: not enough values to unpack`）。它只在有库时才走，而沙箱没库、当时的单测又只覆盖纯函数，于是 **25 例全绿、CI 一上来就红**。所以取数胶水必须自己有测试，否则「本地全绿」这句话没有意义。

单测钉住的错误，全部**变异验证过**（注入错误实现 → 单测红）：

| 注入的错误 | 后果（若不测） | 变异结果 |
| --- | --- | --- |
| 累计列用 SUM | 数字翻好几倍，看着还挺像样 | 2 例红 |
| 连续天数算成「窗口内合格天数总和」 | 中间破功一天仍报 6 天，DoD 会被误判达标 | 3 例红 |
| Prometheus 指标名按前缀匹配 | `_created` 序列（1.76e9）混进分子，送达率变天文数字**且不报错** | 2 例红（见下注） |
| 分母 0 时报 100% | 一次通知都没发却显示送达率 100% | 1 例红 |
| `q()` 退回二元组（= 首轮 CI 的崩溃） | 有库即崩，且判词一行都打不出来 | 1 例红 |
| SQL 报错却仍判「完整」 | 台账少一行还没人发现 | 1 例红 |
| 告警分级列写成 `severity` | 真库上 `column does not exist`，纯函数测不到 | 1 例红 |
| 去掉 `main()` 的内部异常护栏 | 崩了只剩一个 exit code，根因隐形 | 1 例红 |

> 第三处是变异测试当场抓出来的**测试自身缺口**：初版断言用的夹具里没有同前缀序列，
> 删掉前缀守卫测试照样绿——等于没测。补了 `_created` 序列的夹具后才真正咬住。

**退出码**（CI 唯一读得到的判词，已逐个钉住）：`0` 该出的数都出来了（人工登记项不算失败）／
`1` 有本可算的行没取到（含缺快照基线、指标未暴露）／`2` 窗口非法或尚无完整日期／`3` 工具自己崩了（会打 `::error::取数器内部异常`）。

**JSON v2**：`requested_window` 是请求窗口，`window` 是实际完整日期范围；
`window_closed` 仅说明请求终日已过去，不代表观测覆盖完整。
`complete` 为兼容旧调用方保留，与 `collection_complete` 同义；两者只表示取数无错误。
`gate_passed=null`：ACK、真渠道、观测覆盖、样本充分性需要人工验收。
告警转单率为 `alert_sourced_tickets/alerts_total`，诊断充分率为 `diagnosis_sufficient/diagnosis_total`。
Windows 输出尝试 UTF-8（无 `reconfigure` 的流兼容）；psql 使用 `-d` 显式数据库、`-X` 禁用户配置、
`ON_ERROR_STOP=1` 不吞 SQL 错误、`PGCLIENTENCODING=UTF8` 与 Python 解码一致。
本轮无原生 Windows 实测，单测只覆盖调用契约/输出流防护，不声称所有终端兼容。

**真库集成（不可静默跳过）**：先在数据库应用仓内迁移，再执行：

```bash
POSTGRES_PG_URL='postgresql://<用户>@<地址>/<测试库>' \
GM_BASE='http://<后端>/ai' GM_SCRAPE_TOKEN_FILE='<令牌文件>' \
python3 tools/verify/test_grayscale_metrics_pg.py
```

`PSQL` 可显式指定可执行文件。缺库、SQL 错误、抓取面不可用均失败。
测试随机 schema 克隆已迁移的四张表，灌手算夹具，结束删除；显式 id 不消费业务序列。
覆盖正常增量、缺基线、缺窗口内快照、倒退、SQL 错误、六行采集、CLI/JSON。
CI 连接真实 Spring Boot 抓取面。**夹具通过≠灰度生产达标**。

### 本地怎么真跑 SQL（不用等 CI）

沙箱没有 psql/docker，但可以拉起一个**真 PostgreSQL 服务端**跑真迁移，SQL 写错当场就红：

```bash
# 1) 取预编译 PG（npm 源可达；PyPI 上的 postgresql-wheel 是 0 字节空壳，别用）
curl -sSL -o /tmp/pg.tgz https://registry.npmjs.org/@embedded-postgres/linux-x64/-/linux-x64-15.18.0-beta.17.tgz
tar xzf /tmp/pg.tgz -C /tmp/pgdl --one-top-level
# 2) 该发行版的 .so 只有 libicui18n.so.60.2 这种全名，要补软链才起得来
cd /tmp/pgdl/package/native/lib && for f in *.so.*.*; do ln -sf "$f" "${f%.*.*}.$(echo "$f"|sed -E 's/.*\.so\.([0-9]+)\..*/\1/')"; done
export LD_LIBRARY_PATH=/tmp/pgdl/package/native/lib
# 3) 建库、跑迁移、指过去（该发行版不带 psql，用 psycopg2 写个 -t -A -c 的桩即可）
/tmp/pgdl/package/native/bin/initdb -D /tmp/pgdata -U devops --auth=trust
/tmp/pgdl/package/native/bin/pg_ctl -D /tmp/pgdata -o "-p 25432 -c listen_addresses=localhost" -l /tmp/pg.log -w start
python3 tools/verify/grayscale_metrics.py --from 2026-10-13 --to 2026-10-19 \
    --psql <psql 桩> --pgurl postgresql://devops@localhost:25432/devops_knowledge_db
```

> 迁移里的 `CREATE EXTENSION vector` / `pg_trgm` 与 HNSW/三元组索引在该发行版上没有，
> 逐句跳过即可——**42 张表照样建得起来**，本工具碰的四张表（`sys_alert`/`sys_devops_ticket`/
> `sys_healing_execution`/`sys_effectiveness_snapshot`）与向量检索无关。
> 灌上手算得出的种子数据后，六行读数应与手算逐一对上（这一步已做过，见批104 返工记录）。

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

## 结论怎么变成可读证据（`ci_summary.py`）

托管 runner 的作业日志归档在 azure blob 域，开发沙箱 egress 不通
（`gh run view --log` 与 artifact 下载都是 EOF，实测）——**绿案的结论本来无处可读**，
只能靠「红案回帖」。`ci_summary.py` 把演练证据 JSON 压成一行 workflow 命令
（`::notice::` / `::error::`），落成 check-run annotations，
`gh api repos/.../check-runs/<id>/annotations` 即可读回。

```bash
python3 tools/verify/ci_summary.py --kind closed-loop --json /tmp/closed-loop.json --log /tmp/closed-loop.log
python3 tools/verify/ci_summary.py --kind rbac        --json /tmp/rbac.json        --log /tmp/rbac.log
# 退出码：0=证据显示全过；1=有失败**或缺证据**（缺证据按失败处理——静默跳过比红更坏）
```

CI 侧接在 `.github/workflows/ci.yml` 的 e2e 腿：演练步与走查步各跑一次，
失败行会带断点（`[阶段]跳名: 现场`），不必翻日志就知道断在哪。

## 发布回退实弹（`scripts/rollback-drill.sh`）

DoD#6 的「后端版本回退 <5min」：两种模式（沙箱无 Docker 时用 `artifact`，两个发布目录切换；
生产用 `docker`，双 tag + `compose up -d`）。版本身份**用契约差异证伪**（新版独有端点在新版必须存在、
旧版必须不存在）——只看 HTTP 200 会误判：旧版也有通配路由，两版都回 200。
| `closed_loop_drill.py` | 六条核心链路的端到端契约 | 真告警 → 归一化 → 建单 → 双向溯源 → 诊断（四路证据 + 充分度语义）→ 处置五连 → 恢复穿透 → 复盘沉淀；另含观察窗降噪与历史回放两个场景。44 跳逐跳点名（CI 本轮 PASS 42/44、硬失败 0）。方案说明见 `docs/09-operations/业务闭环能力验证方案.md` |
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

### 批116 两组运行时补丁验收

`verify_runtime_patches.py --sbom … --experiment … --scan-severity HIGH,CRITICAL --json …` 在原生实验之后验证Tika3.2.2全模块（core+standard-package双入口，不导入会覆盖Boot其他库的完整Tika BOM）、Netty4.1.137.Final（tcnative独立版本流，但不豁免其HC）；目标模块未绑定实际Boot字节、必需模块/扫描覆盖/同源SHA缺失、混版、残余目标HC都失败并删除陈旧输出。9例行为/CLI自测：`python3 tools/verify/test_verify_runtime_patches.py`。这是本批补丁验收，不替代全量漏洞门禁；其`full_security_passed=false`表示不签发整体安全证明。逐目标variant SHA256/实际字节绑定分片注解可恢复并验SHA，超容失败，不静默丢证。后续升级须同步调整本批精确版本验收约束。后端CI另有真实Tika3.0.0 StAX负对照（恰好1失败、0错误、旧core classpath），随后修补版全量clean verify，XML/log单独留档。Tomcat10.1.58候选在真实CI报Central缺core/el/websocket，已撤回不可交付覆盖；保留原实装漏洞，另批评估源码自建/可复现供应。
