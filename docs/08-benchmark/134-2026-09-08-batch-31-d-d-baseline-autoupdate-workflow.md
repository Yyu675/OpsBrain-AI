# 批次 31（D-D）：评测基线自动更新 workflow_dispatch 接线——增强件落地

日期：2026-09-08 ｜ 状态：完成待派单实证 ｜ 承接：报告 118(S4-3 基线门禁）、eval_compare.js 头注挂账（「CI 内 workflow_dispatch 接线挂账——同 T12 先例」)

---

## 一、何为 D-D（决策纪要补录）

4-3.2 首跑即基线的 CI 侧回写：主线门禁（S4-3 回归对比）只**读** `tools/audit/eval_baseline.json`;
基线从哪来——此前只有「本地真窗首跑 + 人工 --update 提交」一条路。D-D 决策：**在 CI 装一条
workflow_dispatch 手动派单的自动更新通道**，定位增强件，**不动主线门禁一根指头**。

## 二、文件

- `.github/workflows/eval-baseline-update.yml`（新增）：派单 → 镜像主线 backend 环境重跑
  `mvnw verify`（AI_MODE=MOCK 契约层常驻落盘）→ `eval_compare` 先**亮账**（劣化 rc=1 整步失败，
  后续 update/commit 不执行——回归物理上无法混进基线）→ 无劣化才 `--update` 成物
  → diff 比对，有变化才以 bot 身份提交回派单分支。
- `tools/audit/eval_compare.js` 头注挂账转已办，指向新工作流。

## 三、四道守护设计（防假绿/防自激/防漂基线）

1. **亮账先于回写**:compare 不过 = 工作流红 = 零提交。派单人必须先在 run 日志里看清账本。
2. **环境同口径**:services/schema init/redis 密码/verify 命令逐字段镜像主线 backend job——
   「轻量环境造基线、全量环境查基线」会系统性误红，禁止。
3. **`[skip ci]` 防自激**:bot 提交的基线是纯数据文件，同 SHA 代码再跑主线无新增信号，跳过。
4. **审计链**：提交信息强制携带派单理由（inputs.reason 必填）;target/eval-metrics.json 与
   eval-compare.txt 上传 artifact 留档 30 天。
5. 权限隔离：主线 `ci.yml` contents:read 不变；本工作流独立声明 contents:write，两门不相通。

## 四、边界声明（别指望它干不在职的事）

- **EVAL_RAG/EVAL_LLM 真窗基线不在其能力域**:runner 无真模型凭证，MOCK 窗只产契约层指标。
  真窗首跑基线仍走 4-3.2 原定路径（本地真窗 + 人工 --update)——S4-2 ECE 红线欠账不变。
- 无历史基线时 compare 按首跑语义 rc=0 放行（引导语义与主线一致），本工作流即可承担
  「 MOCK 契约层基线首跑」的搬运工角色。

## 五、验证方案

推送后立即 workflow_dispatch 派单一次端到端实证（含 bot 回写 + [skip ci] 语义），
结果在批 31 报账中给出。
