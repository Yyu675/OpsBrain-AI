# 115 S4-2 证据门计数器：双计数语义 · 连胜判定 · Spring 多构造器尸检

> 报告性质：路线图阶段 4 批次 9——报告 114 §六首推候选「证据门计数器」的落地记录，
> 兼记一次「多构造器 Spring Bean 未标 @Autowired」导致全 context 崩的尸检，
> 与「本地无 JDK 时代的推前 checklist」工具账扩充。

---

## 一、交付对照（S4-2 批次 9）

| # | 要求 | 落法 |
|---|---|---|
| 1 | 按 params 溯源键统计实绩 | ✅ `HealingExecutionRepository` +3：`countPolicyDryRunHits` / `recentAutoStatuses(50)` / `lastAutoFailureAt`，一律 `requested_by='auto'` + `params_json LIKE '%"__policyId":N%'`（人工触发的成功不记在策略头上） |
| 2 | 双计数器语义分离 | ✅ 演练命中场次（dryRun 阶段证明「匹配面符合预期」）与连续零误连胜（真执行阶段证明「动手可靠」）分开计数——阶段语义不串味 |
| 3 | 连胜判定边界 | ✅ `FAILED/UNDO_FAILED/UNDONE` 断连胜（撤销=执行产生后悔，同样非零误）；`REJECTED/PENDING_APPROVAL/RUNNING` 中立跳过；只看最近 50 条（策略一改配置，远古表现没有证明力） |
| 4 | 徽标判定放服务端 | ✅ `promotable = dryRun && hits≥门槛`、`evidenceReady = !dryRun && streak≥门槛`——前端只展示，各自重算必与引擎漂移（applyActionState 同款先例） |
| 5 | 阈值可配 | ✅ `devops.healing.policy-evidence.dryrun-promote-hits`(默认5)/`success-streak-goal`(默认3) 两个 @Value |
| 6 | 三表服务零破坏装配 | ✅ `AutomationGovernanceService` 字段注入 `@Autowired(required=false)`；两个直连三参构造的既有测试不装配→证据字段整组 null→前端显「—」降级，构造面零变更 |
| 7 | 异常降级 | ✅ 证据装填异常只 WARN——徽标列为空好过策略整页 500 |
| 8 | 前端徽标 | ✅ 策略列表状态格内嵌：演练 N 场 / 🎖可转正 / 连胜 N / 🎖证据达标；`api/governance.ts` 类型 +5 可选字段；本地 vue-tsc 0 错 + vitest 72 例全绿 |
| 9 | 测试 | ✅ `PolicyEvidenceServiceTest` 10 例：连胜断点三类/中立跳过/双阶段语义/空台账/阈值双边界/污点直通/LIKE 键捕获（钉 Jackson 数值序列化形）/空 id 哨兵 |

## 二、设计三取舍

1. **为什么 LIKE 而不拆列/jsonb 索引**：台账行基数极小（自愈是受控低频动作）、回放永远按执行维度取数；为两条统计 SQL 拆列是过度工程。`policyLikeKey` 公开供测试把「引号内数字不带引号」的序列化形钉死——Jackson 行为变了测试先红，不静默跑偏。
2. **为什么污点在两个计数器里口径不同**：演练场次不在乎污点（演练本来零执行）；连胜只看真执行 outcomes。语义各自单一，未来谁都不要向谁借字段。
3. **为什么 N+1 查询可接受**：策略分页 size ≤20，每行 3 条主键级 SQL；治理控制台不是热路径。若将来放量，先做页面缓存，不做 JOIN 大手术（证据门每一列都是独立口径，JOIN 会拧成语义麻绳）。

## 三、修红战役（本批唯一的红，但值得独享一节尸检）

**现象**：683e5e0 推送后 push 工作流后端腿全灭——`OpenApiSchemaIntegrationTest` 首个
`Failed to load ApplicationContext`，随后全部 @SpringBootTest 被
`ApplicationContext failure threshold exceeded` 连坐跳过。前端腿绿，DP2 另红（见 §五）。

**尸检路径**：受限外网=job logs 与 surefire artifact 双 EOF，annotations 通道只有
200 字截断的 XML 头。放弃拉栈，改白盒审计 7 个变更文件的**装配面**（本地无 JDK 时代
唯一可靠的验尸工具）。

**根因**：`PolicyEvidenceService` 同时存在 public 单参构造器与 package-private
三参测试构造器。Spring 对**多构造器且无一标 @Autowired** 的类回退寻找默认无参
构造器——不存在 → `BeanInstantiationException` → `AutomationGovernanceService`
装配链崩 → 全 context 崩。一个编辑器完全不报错、单测完全测不到的装配层裂缝。

**修复**：单参构造器显式 `@Autowired`（d45cd1c 之后批次 9 修复提交），注释钉因。

**工具账新增（本地无 JDK 推前 checklist，今后每批自查）**：
1. 新增 Spring Bean：构造器个数>1 必有 @Autowired 指向装配入口；
2. 新引用第三方 API：先源码核对（此前 T14 已记账）；
3. testCompile 引用：`argThat/times` 等静态导入齐全（批次 6 记账）；
4. record/方法参数量：调用点逐一数（批次 7 draft 15 参记账）；
5. 本批新增——**装配面审计优先于逻辑面**：本地编译器缺席时，
   「Bean 定义面 + 序列化面」是 CI 唯二能杀你而本地零感知的层面。

## 四、DP2 政策文档门

683e5e0 红单里还有一条独立失败：`policy-docs` 失败 3——台账层要求
「feat 提交的批次必须同日补记 PROGRESS」。本提交随修复一并补 T28 行 +
变更记录行，DP2 与双腿一并复验。

## 五、欠账

- ~~徽标渲染断言~~ **已清偿（批次 10）**：render.smoke +5 例（可转正点亮/未达标灰显/证据达标/连胜灰显/缺席降级），24/24 绿；
- 3-5.2 审批中心×执行入口联调窗口：仍等真实审批流窗口（借条不变）；
- Prom 错误率/P99 PRE-POST 验证器：排在联调成功后（借条不变）；
- `sys_healing_step` 子表：等报表放量（借条不变）。
