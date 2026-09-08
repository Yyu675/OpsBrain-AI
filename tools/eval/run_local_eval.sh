#!/usr/bin/env bash
# OpsBrain 本地真实环境评测一键脚本（S4-2 / S4-3 窗口，批次 20 / 报告 123）
#
# 用法：
#   tools/eval/run_local_eval.sh rag        # RAG 覆盖层（MOCK 嵌入，连通性口径，免 key）
#   tools/eval/run_local_eval.sh llm        # LLM 端到端层（需 ALIBABA_API_KEY，全量约 20-40min）
#   tools/eval/run_local_eval.sh llm 5      # LLM 抽样窗（EVAL_LLM_SAMPLE=5，校准期省额度）
#   tools/eval/run_local_eval.sh compare    # 本次 vs 基线对比（劣化 rc=1）
#   tools/eval/run_local_eval.sh baseline   # 人工建账/更新基线（首跑即基线，4-3.2）
#
# 环境依赖：Docker（Testcontainers 自起 pgvector 容器）+ JDK 21 + node 18+。
# 真实模型：export ALIBABA_API_KEY=sk-...（阿里云百炼，OpenAI 兼容协议；
#            可选 ALIBABA_BASE_URL / ALIBABA_TURBO_MODEL / ALIBABA_REASONER_MODEL 覆写）
set -euo pipefail
cd "$(dirname "$0")/../.."

die() { echo "❌ $*" >&2; exit 1; }

check_docker() { docker info >/dev/null 2>&1 || die "Docker 不可用——Testcontainers 需要它起 pgvector 容器"; }
check_java()   { ./mvnw -B -ntp --version >/dev/null 2>&1 || die "JDK 21 不可用（./mvnw --version 失败）"; }

METRICS=target/eval-metrics.json
BASELINE=tools/audit/eval_baseline.json

cmd="${1:-}"
case "$cmd" in
  rag)
    check_docker; check_java
    echo "==> RAG 覆盖层（MOCK 嵌入 / 连通性口径，红线：命中率 100%）"
    EVAL_RAG=true ./mvnw -B -ntp test -Dtest='AgentEvaluationTest#ragCoverageEvaluation'
    echo "✅ 完成。指标：$METRICS（rag 层）；报表：target/eval-report.md"
    ;;

  llm)
    check_docker; check_java
    [ -n "${ALIBABA_API_KEY:-}" ] || die "ALIBABA_API_KEY 未设置——本层调用真实模型，没有 key 就没有窗"
    sample="${2:-0}"
    echo "==> LLM 端到端层（真实模型，${sample:-0||全量}${sample:+ 抽样}）"
    EVAL_AI_MODE=LIVE EVAL_LLM=true EVAL_LLM_SAMPLE="$sample" \
      ./mvnw -B -ntp test -Dtest='AgentEvaluationTest#llmEndToEndEvaluation'
    echo "✅ 完成。指标：$METRICS（llm 层）；报表：target/eval-report.md（含逐条失败明细）"
    ;;

  compare)
    [ -f "$METRICS" ] || die "$METRICS 不存在——先跑 rag 或 llm 子命令"
    node tools/audit/eval_compare.js --current "$METRICS" --baseline "$BASELINE"
    ;;

  baseline)
    [ -f "$METRICS" ] || die "$METRICS 不存在——先跑 rag 或 llm 子命令"
    echo "==> 人工建账：$METRICS → $BASELINE（首跑即基线，4-3.2）"
    node tools/audit/eval_compare.js --current "$METRICS" --baseline "$BASELINE" --update
    echo "✅ 基线已写入。请 review 后提交：git add $BASELINE && git commit"
    ;;

  *)
    sed -n '2,20p' "$0"; exit 1 ;;
esac
