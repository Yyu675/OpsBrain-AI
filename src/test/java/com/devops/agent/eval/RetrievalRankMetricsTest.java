package com.devops.agent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 排序指标的确定性钉测（常驻 CI，不依赖 pgvector / LLM / MOCK 嵌入）。
 *
 * <p>指标计算面的缺陷与检索质量无关却同样致命：公式写错一格，
 * 看板上的「提升」就是编出来的（计算与数据源分层，见
 * {@link RetrievalRankMetrics} 类注释）。</p>
 */
@DisplayName("检索排序指标（Recall@K / MRR 纯计算面）")
class RetrievalRankMetricsTest {

    private static RetrievalRankMetrics.QueryRanking q(Set<String> expected, String... ranked) {
        return new RetrievalRankMetrics.QueryRanking(List.of(ranked), expected);
    }

    // ==================== firstHitRank ====================

    @Test
    @DisplayName("首名即命中 → 1；期望集合多文档时任一命中即停")
    void firstRankIsOne() {
        assertEquals(1, RetrievalRankMetrics.firstHitRank(
                q(Set.of("B.md", "A.md"), "A.md", "C.md")));
    }

    @Test
    @DisplayName("命中在第三名 → 3（1-based，不是数组下标）")
    void thirdRankIsThree() {
        assertEquals(3, RetrievalRankMetrics.firstHitRank(
                q(Set.of("A.md"), "X.md", "Y.md", "A.md")));
    }

    @Test
    @DisplayName("排序序列里没有期望文档 → -1")
    void missIsMinusOne() {
        assertEquals(-1, RetrievalRankMetrics.firstHitRank(
                q(Set.of("A.md"), "X.md", "Y.md")));
    }

    @Test
    @DisplayName("空排序 / 空期望 / null 输入一律 -1（哨兵而非异常——评测循环不该被单条脏数据炸断）")
    void degenerateInputsAreMinusOne() {
        assertEquals(-1, RetrievalRankMetrics.firstHitRank(
                new RetrievalRankMetrics.QueryRanking(List.of(), Set.of("A.md"))));
        assertEquals(-1, RetrievalRankMetrics.firstHitRank(
                new RetrievalRankMetrics.QueryRanking(List.of("A.md"), Set.of())));
        assertEquals(-1, RetrievalRankMetrics.firstHitRank(
                new RetrievalRankMetrics.QueryRanking(null, Set.of("A.md"))));
        assertEquals(-1, RetrievalRankMetrics.firstHitRank(null));
    }

    // ==================== recallAtK ====================

    @Test
    @DisplayName("Recall@1 只认第一名的命中；Recall@3 放宽到前三——同一数据两种读数")
    void recallAtKWindows() {
        List<RetrievalRankMetrics.QueryRanking> rankings = List.of(
                q(Set.of("A.md"), "A.md", "X.md"),     // rank 1
                q(Set.of("B.md"), "X.md", "B.md"),     // rank 2
                q(Set.of("C.md"), "X.md", "Y.md", "Z.md", "C.md"), // rank 5
                q(Set.of("D.md"), "X.md", "Y.md"));    // miss

        assertEquals(0.25, RetrievalRankMetrics.recallAtK(rankings, 1), 1e-9);
        assertEquals(0.50, RetrievalRankMetrics.recallAtK(rankings, 3), 1e-9);
        // K 大到覆盖名次 5 才计入第三条
        assertEquals(0.75, RetrievalRankMetrics.recallAtK(rankings, 5), 1e-9);
    }

    @Test
    @DisplayName("空查询集 / k<=0 → 0.0（无可评对象不报满分，拒伪造口径）")
    void emptyRankingsAreZeroNotPerfect() {
        assertEquals(0.0, RetrievalRankMetrics.recallAtK(List.of(), 3));
        assertEquals(0.0, RetrievalRankMetrics.recallAtK(null, 3));
        assertEquals(0.0, RetrievalRankMetrics.recallAtK(
                List.of(q(Set.of("A.md"), "A.md")), 0));
    }

    // ==================== MRR ====================

    @Test
    @DisplayName("MRR：rank1→1.0、rank2→0.5、未命中→0，取均值")
    void mrrAveragesReciprocalRanks() {
        List<RetrievalRankMetrics.QueryRanking> rankings = List.of(
                q(Set.of("A.md"), "A.md"),             // 1.0
                q(Set.of("B.md"), "X.md", "B.md"),     // 0.5
                q(Set.of("C.md"), "X.md", "Y.md", "Z.md", "C.md"), // 0.2
                q(Set.of("D.md"), "X.md"));            // 0.0

        assertEquals((1.0 + 0.5 + 0.2 + 0.0) / 4, RetrievalRankMetrics.mrr(rankings), 1e-9);
    }

    @Test
    @DisplayName("全未命中 MRR=0；空集 MRR=0——与「全部答错」区分「没有可评的题」交给调用方读参与数")
    void mrrFloor() {
        assertEquals(0.0, RetrievalRankMetrics.mrr(List.of(
                q(Set.of("A.md"), "X.md"),
                q(Set.of("B.md"), "Y.md"))));
        assertEquals(0.0, RetrievalRankMetrics.mrr(List.of()));
        assertEquals(0.0, RetrievalRankMetrics.mrr(null));
    }
}
