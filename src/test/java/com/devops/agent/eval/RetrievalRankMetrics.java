package com.devops.agent.eval;

import java.util.List;
import java.util.Set;

/**
 * 排序质量指标（路线图 §8.1 步骤 4-1.2）：Recall@K 与 MRR 的纯计算面。
 *
 * <h3>为什么独立成纯函数类</h3>
 * 排序指标的<b>计算</b>与它的<b>数据源</b>（pgvector/MOCK/真实嵌入）必须拆开：
 * MOCK 嵌入下排名本身无语义（报告 102 §三已定论），但「指标算得对不对」
 * 与「向量真不真」毫无关系——前者必须能被常驻 CI 的确定性单测钉死，
 * 后者交给 EVAL_LLM 手动 job。混在一起的下场就是两次教训里的
 * 「0.73 语义门槛测 MOCK 正交向量」式无效测量。
 *
 * <h3>匹配键是 docTitle 而不是 docId</h3>
 * 检索口暴露的稳定标识是 {@code doc_title}（classpath 摄取=文件名，见
 * {@code RetrievedChunk} 与 {@code KnowledgeIngestionService}），
 * 评测注记直接写文件名，人类可读、无需查库。
 */
final class RetrievalRankMetrics {

    private RetrievalRankMetrics() {
    }

    /** 单条查询的排序面：检索返回的文档标题序列（有序）+ 期望命中的标题集合。 */
    record QueryRanking(List<String> rankedDocTitles, Set<String> expectedDocTitles) {
    }

    /**
     * 首个期望文档在排序序列中的名次（从 1 计）。
     *
     * @return 1-based 名次；未命中 / 排序为空 / 期望为空时返回 -1
     */
    static int firstHitRank(QueryRanking q) {
        if (q == null || q.rankedDocTitles() == null || q.expectedDocTitles() == null
                || q.expectedDocTitles().isEmpty()) {
            return -1;
        }
        List<String> ranked = q.rankedDocTitles();
        for (int i = 0; i < ranked.size(); i++) {
            if (q.expectedDocTitles().contains(ranked.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Recall@K：首个期望文档落在前 K 名内的查询占比。
     * 空集返回 0（「没有可评的查询」不该报 100%——S0-4「留空拒伪造」同族口径）。
     */
    static double recallAtK(List<QueryRanking> rankings, int k) {
        if (rankings == null || rankings.isEmpty() || k <= 0) {
            return 0.0;
        }
        long hits = rankings.stream()
                .filter(q -> {
                    int rank = firstHitRank(q);
                    return rank >= 1 && rank <= k;
                })
                .count();
        return (double) hits / rankings.size();
    }

    /**
     * MRR（Mean Reciprocal Rank）：首个期望文档名次倒数的均值，未命中计 0。
     */
    static double mrr(List<QueryRanking> rankings) {
        if (rankings == null || rankings.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (QueryRanking q : rankings) {
            int rank = firstHitRank(q);
            if (rank > 0) {
                sum += 1.0 / rank;
            }
        }
        return sum / rankings.size();
    }
}
