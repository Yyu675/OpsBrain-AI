# 本机真实环境验证清单（S4-2/S4-3 手动窗，批次 20 交付）

> 用法：从上到下一一打勾。**每步给出期望输出与失败判据**——看到「非期望」
> 就停，不要带着红灯继续。全程约 5 分钟（不含 LLM 全量跑的 20-40 分钟）。
> 前置：本机 Docker 已启动；仓库根目录执行；JDK 21 + node 18+。

## 0. 环境钥匙

- [ ] **0-1** `docker info | head -3` 正常输出（Testcontainers 容器运行基座）
- [ ] **0-2** `./mvnw -B -ntp --version` 输出 JVM 21.*
- [ ] **0-3** `node --version` ≥ 18
- [ ] **0-4**（仅 LLM 窗需要）`echo ${ALIBABA_API_KEY:0:8}` 非空
      ——阿里云百炼控制台申请，形如 `sk-xxxxx`

## 1. RAG 覆盖层（免 key，约 2-4 分钟）

```bash
tools/eval/run_local_eval.sh rag
```

- [ ] **1-1** 进程中出现 `✅ 完成。指标：target/eval-metrics.json（rag 层）`
- [ ] **1-2** `cat target/eval-metrics.json | grep -A8 '"rag"'` 含
      `hitRate / posTotal / annotated / recallAt1 / recallAt3 / mrr`
- [ ] **1-3** ⚠ MOCK 口径注意：本层只证「检索管道连通」（命中 100% 红线），
      **排名数值无语义**——勿据此调检索参数（报告 102 §三/报告 117 口径）
- [ ] **1-4** `target/eval-report.md` 存在且含「RAG 覆盖层」小节

## 2. LLM 端到端层（需 key，先试抽样窗）

```bash
export ALIBABA_API_KEY=sk-xxxxx
tools/eval/run_local_eval.sh llm 5        # 先 5 正+5 负抽样，约 3-8 分钟
```

- [ ] **2-1** 第一行不是「拒绝用 MOCK 造评测假账」——若出现，
      说明 `EVAL_AI_MODE=LIVE` 没生效（检查是否用了脚本而非手敲 mvn）
- [ ] **2-2** `target/eval-report.md` 含「LLM 端到端层」汇总指标三行：
      有源答案事实正确率 / 负例诚实拒答率 / 幻觉率
- [ ] **2-3** `target/eval-metrics.json` 的 `llm` 层七键齐全
      （posTotal / negTotal / posScorable / answerAccuracy /
      honestRejectRate / hallucinated / hallucinationRate）
- [ ] **2-4** 失败明细（若有）在报表「未通过正例/未拒答负例」小节，
      每条带期望关键词与实际回答截断
- [ ] **2-5** 抽样窗指标正常后，全量跑（20-40 分钟，烧正式额度）：
      `tools/eval/run_local_eval.sh llm`

## 3. 基线建账与回归对比（4-3.2 首跑即基线）

```bash
tools/eval/run_local_eval.sh baseline     # 人工建账（首次）
tools/eval/run_local_eval.sh compare      # 此后每次对比
```

- [ ] **3-1** 首次 `baseline` 子命令输出「基线已写入」+ `git status` 出现
      `tools/audit/eval_baseline.json`
- [ ] **3-2** review 基线数字是人类认可的真指标（非 MOCK/非抽样残影）后提交
- [ ] **3-3** 此后任一跑（rag/llm）再 `compare`：
      无劣化 rc=0；劣化 rc=1 且点名键名（比率 ±2pt / 计数方向零容忍 /
      幻觉率上升即劣化）
- [ ] **3-4** ⚠ 抽样窗（EVAL_LLM_SAMPLE>0）的指标**不要**建账/对比
      ——posTotal/negTotal 被抽样截短会制造假劣化。建账只认全量窗。

## 4. 把我这边的章也验一遍（批次 20 的自证交接）

- [ ] **4-1** `node tools/audit/eval_compare.js --current /tmp/evaldemo2/cur_bad.json --baseline /tmp/evaldemo2/base.json`（若 /tmp 已被清理，用 3-3 的报表现象旁证）——幻觉率上升应 rc=1
- [ ] **4-2** CI 双腿绿（无需操作，批次 20 push 后 GitHub Actions）

## 常见失败速查

| 现象 | 根因 | 解法 |
|---|---|---|
| `拒绝用 MOCK 造评测假账` | EVAL_AI_MODE 未设 LIVE | 用脚本跑，或手敲时补 `EVAL_AI_MODE=LIVE` |
| `种子文档摄取为 0` | Docker/testcontainers 未就绪 | `docker info` 排障后重跑 |
| LLM 层一直超时（180s/条） | base-url/key 无效或限流 | 核对 ALIBABA_* 覆写、额度页 |
| 幻觉率显著>0 | 拒答话术词面漂移或模型换版 | 把失败明细贴回报告 123 的欠账段，词组拓集属 4-1.4 欠账 |
