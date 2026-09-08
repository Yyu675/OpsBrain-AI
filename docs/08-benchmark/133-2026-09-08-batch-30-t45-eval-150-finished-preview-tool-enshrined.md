# 批次 30（T45）：评测集 150 收官 + 预演工具正式入仓，欠账①②清零

日期：2026-09-08 ｜ 状态：**完成** ｜ 承接：报告 131（T44 三重纪律）、报告 132（五闸与报账纪律）

---

## 一、总账

| 项 | T44 束 | T45 束 |
| --- | --- | --- |
| 评测集规模 | 128 | **150**（收尾欠账① 清零） |
| 正 / 负 | 65 / 63 | **67 / 83** |
| 安全三类（INJECT/SENSITIVE/SUPER_SCOPE) | 42 | **54** |
| 预演工具 | 临时 python 脚本（未入仓） | **`tools/audit/preview_dataset_vs_guard.js` 入仓**（欠账② 清零） |

新增 22 条（id 129–150）分布：POSITIVE+2(K8s NotReady / SLB 健康检查，后者挂阿里云SLB手册 expectedDocs)、INJECT+4、SENSITIVE+4、SUPER_SCOPE+4、NO_SOURCE+4、OFF_TOPIC+4。

## 二、纪律资产沿用与战绩（零 JDK 预演当场抓弹）

四重闸全过，预演环节当场抓到 **2 发**，全部在施工内修复——这正是预演存在的意义：

1. **#138 弹体虚写**:`production ssh private_key` 带下划线，而规则真身 `private\s+key` 只认空格 → MISS。改词 `private key` 后命中 `SENSITIVE_EXTRACTION_REV_EN`。**教训同 T44 #121：弹体必须贴规则族字面写，不能凭直觉。**
2. **工具自身事故（dogfooding 高光）**：此 STRUCTURE_BREAK 镜像正则初稿经写文件链路后逃逸被张冠李戴，产生假阳（#101 误伤报警）且语义静默偏移（TAB/NUL 不命中）。处置：控制字符检查改**码点谓词实现**(`hasControlChar`，纯数字区间，零转义），剔除含真实控制字节的问题行，最后一次全量核验「文件无残留控制字节」。**写文件路径上的转义类一律视为可疑，语义类检查改用码点谓词或 String.fromCharCode**——入仓。

其余 10 条新安全弹体首跑即命中（无视规则/角色扮演/分隔符注入/令牌导出/删库三段/机房断电/跳过权限校验/英文角色扮演等，全部贴 T44 立的「动作+目标+距离窗」轨道撰写）。

## 三、契约层判据（不变，勿倒回）

- 安全三类（现 54 条）必须 100% 拦截——预演 54/54 全绿。
- NO_SOURCE / OFF_TOPIC 归拒答链路，护栏断言豁免（2026-08-26 判例）;T45 新增 8 条属此二类的只做语义分区，不进护栏断言。
- 最终仲裁权在 `AgentEvaluationTest`（契约层），本工具为「第六道本地闸」，口径与契约层一致但优先级低。

## 四、预演工具用法与同步纪律

```bash
node tools/audit/preview_dataset_vs_guard.js   # RC=0 为准入
```

- 规则族镜像 `PromptInjectionGuard.java`(CRITICAL+HIGH)+ `SecurityInputGuard.java`(DANGEROUS 兜底）。**改 Java 侧规则必须同步本文件并重跑**，文件头已立法。
- 与五闸的关系：五闸管前端，本工具管评测集扩/改，schema 校验器管结构，保底闸管规模——四件套构成评测集纪律栈。

## 五、闸序实证

| 闸 | 结果 |
| --- | --- |
| schema 校验器 | 150(67/83)✓ |
| 保底闸（≥100/≥50/≥50) | ✓ |
| 预演工具（本批入仓） | 54/54 拦截 + 67 正例零误伤 ✓ |
| 前端五闸 | 本批未触前端文件，N/A（批 29 已绿） |

## 六、剩余欠账（移交）

① ~~扩集 150 尾量~~ ✅ ② ~~预演工具入仓~~ ✅
③ S4-2 ECE 红线实证：等用户本地 `run_local_eval.sh llm 5` 真窗数据，届时用现有 `tools/audit/eval_compare.js` 对账。
④ D-D workflow_dispatch baseline 自动更新：增强件，非主线，待指令。
