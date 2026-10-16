package com.devops.agent.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断看板合并面（S4-4.2 批次 16 + S4-4.3 批次 17）：方向口径与趋势口径的确定性钉测。
 * SQL 层的「查得对不对」归集成测试闸，本类管「合得对不对」。
 */
@DisplayName("DiagnosisBoardComposer（诊断区看板合并面）")
class DiagnosisBoardComposerTest {

    /** 全程注入固定右端点：日历语义不进运行时，断言永不漂移 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);

    private static Map<String, Object> row(String k1, Object v1, String k2, Object v2) {
        return Map.of(k1, v1, k2, v2);
    }

    private static Map<String, Object> ev(String type, String status, long n) {
        return Map.of("evidence_type", type, "status", status, "n", n);
    }

    private static Map<String, Object> day(String iso, long total, long completed) {
        return Map.of("day", Date.valueOf(iso), "total", total, "completed", completed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sessions(Map<String, Object> board) {
        return (Map<String, Object>) board.get("sessions");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> directions(Map<String, Object> board) {
        return (List<Map<String, Object>>) board.get("evidenceDirections");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> trend(Map<String, Object> board) {
        return (Map<String, Object>) board.get("sessionTrend");
    }

    // ---- 会话与方向口径（批次 16） ----

    @Test
    @DisplayName("全空输入：total=0、方向空、点名空、耗时 null，趋势恒为窗口长全零（空窗口不报幻象数字，也不塌图）")
    void emptyEverything() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(), List.of(), List.of(), null, 7, List.of(), TODAY, 0.0);

        assertEquals(0L, sessions(board).get("total"));
        assertNull(sessions(board).get("avgDurationSeconds"));
        assertTrue(directions(board).isEmpty());
        assertTrue(((List<?>) board.get("attentionTypes")).isEmpty());
        assertEquals(7, board.get("windowDays"));
        assertEquals(7, ((List<?>) trend(board).get("days")).size());
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L), trend(board).get("created"));
    }

    @Test
    @DisplayName("状态分布求 total：不动 SQL 给的字符串状态，原样透传")
    void statusRowsSumTotal() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(row("status", "COMPLETED", "n", 40L),
                        row("status", "ERROR", "n", 5L),
                        row("status", "REJECTED", "n", 3L)),
                List.of(), List.of(), 12.345678, 7, List.of(), TODAY, 0.0);

        assertEquals(48L, sessions(board).get("total"));
        List<?> byStatus = (List<?>) sessions(board).get("byStatus");
        assertEquals(3, byStatus.size());
        assertEquals(12.35, sessions(board).get("avgDurationSeconds"),
                "耗时按两位小数收口（展示面不要 15 位浮点噪音）");
    }

    @Test
    @DisplayName("无完成会话 → 平均耗时透传 null 而非编造 0")
    void nullAvgStaysNull() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(row("status", "REJECTED", "n", 2L)),
                List.of(), List.of(), null, 7, List.of(), TODAY, 0.0);

        assertNull(sessions(board).get("avgDurationSeconds"));
    }

    @Test
    @DisplayName("方向四态聚合 + successRate 口径：NO_DATA 计入分母不豁免")
    void directionMergingAndRate() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(), List.of(),
                List.of(ev("metrics", "SUCCESS", 30),
                        ev("metrics", "NO_DATA", 10),
                        ev("metrics", "FAILED", 5),
                        ev("topology", "UNAVAILABLE", 4)),
                null, 7, List.of(), TODAY, 0.0);

        List<Map<String, Object>> dirs = directions(board);
        assertEquals(2, dirs.size());
        Map<String, Object> metrics = dirs.get(0);
        assertEquals(45L, metrics.get("total"));
        assertEquals(30L, metrics.get("success"));
        assertEquals(10L, metrics.get("noData"));
        assertEquals(5L, metrics.get("failed"));
        assertEquals(0.6667, metrics.get("successRate"),
                "30/45 四位小数——NO_DATA 不豁免分母");
    }

    @Test
    @DisplayName("点名只认 FAILED/UNAVAILABLE：NO_DATA 不是源故障，点名=假警")
    void attentionNamesOnlyRealFailures() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(), List.of(),
                List.of(ev("metrics", "SUCCESS", 20), ev("metrics", "FAILED", 2),
                        ev("logs", "NO_DATA", 8),
                        ev("topology", "UNAVAILABLE", 1),
                        ev("changes", "SUCCESS", 6)),
                null, 7, List.of(), TODAY, 0.0);

        assertEquals(List.of("metrics", "topology"), board.get("attentionTypes"),
                "logs 全是 NO_DATA——源健康没事可报，不点名；changes 全成功");
    }

    @Test
    @DisplayName("未知状态值：计入方向 total 但不进四态桶（新状态不静默消失）")
    void unknownStatusCountsInTotalOnly() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(), List.of(),
                List.of(ev("metrics", "SUCCESS", 5), ev("metrics", "DEGRADED", 3)),
                null, 7, List.of(), TODAY, 0.0);

        Map<String, Object> m = directions(board).get(0);
        assertEquals(8L, m.get("total"));
        assertEquals(5L, m.get("success"));
        assertEquals(0.625, m.get("successRate"));
    }

    @Test
    @DisplayName("sufficiency 只来自 COMPLETED 会话（SQL 已过滤），合并面原样透传不发明分类")
    void sufficiencyPassThrough() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(), List.of(
                        row("sufficiency", "SUFFICIENT", "n", 30L),
                        row("sufficiency", "PARTIAL", "n", 6L)),
                List.of(), null, 7, List.of(), TODAY, 0.0);

        List<?> suff = (List<?>) sessions(board).get("sufficiency");
        assertEquals(2, suff.size());
    }

    @Test
    @DisplayName("successRate 除零保护：total=0 的方向（理论防御）报 0.0 不炸")
    void zeroTotalGuardsDivision() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(), List.of(),
                List.of(ev("metrics", "GHOST", 0)), // 理论构造：分组行 count 为 0
                null, 7, List.of(), TODAY, 0.0);

        assertEquals(0.0, directions(board).get(0).get("successRate"));
    }

    // ---- 逐日趋势口径（批次 17，S4-4.3） ----

    @Test
    @DisplayName("趋势补零：空日画 0 点而不让折线跨坑跳接，标签=MM-dd 与既有趋势图同款")
    void trendFillsZeroDays() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(), List.of(), List.of(), null, 4,
                List.of(day("2026-09-06", 3, 2), day("2026-09-08", 1, 0)),
                TODAY, 0.0);

        Map<String, Object> t = trend(board);
        assertEquals(List.of("09-05", "09-06", "09-07", "09-08"), t.get("days"));
        assertEquals(List.of(0L, 3L, 0L, 1L), t.get("created"),
                "09-05/09-07 无数据补 0——闲日画 0 点，不跨坑跳接");
        assertEquals(List.of(0L, 2L, 0L, 0L), t.get("completed"));
    }

    @Test
    @DisplayName("越窗行被丢弃：序列长度恒等于窗口，秩序由端点定、不认行自带范围")
    void trendDropsOutOfWindowRows() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(), List.of(), List.of(), null, 3,
                List.of(day("2026-08-01", 99, 88),  // 越窗左侧
                        day("2026-09-07", 5, 4)),
                TODAY, 0.0);

        Map<String, Object> t = trend(board);
        assertEquals(List.of("09-06", "09-07", "09-08"), t.get("days"));
        assertEquals(List.of(0L, 5L, 0L), t.get("created"),
                "08-01 属于另一窗口的数据，哪怕行递进来也不能污染本窗");
        assertEquals(List.of(0L, 4L, 0L), t.get("completed"));
    }

    // ---- 单次诊断均价口径（批次 23，S4-4.3） ----

    @Test
    @DisplayName("均价=归因总成本/全部完成会话：1 元 3 次完成 → 0.3333，四位小数同前端成本口径")
    void avgCostRoundsToFourDecimals() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(row("status", "COMPLETED", "n", 3L)),
                List.of(), List.of(), null, 7, List.of(), TODAY, 1.0);

        assertEquals(0.3333, sessions(board).get("avgCostRmb"),
                "1/3 四位小数收口——前端 ¥0.3333 展示与后端同一份四舍五入");
    }

    @Test
    @DisplayName("有成本但零完成会话 → null 不编造（分母保护：成本不硬挂到零分母上）")
    void avgCostNullWhenNoCompletedSession() {
        Map<String, Object> board = DiagnosisBoardComposer.compose(
                List.of(row("status", "ERROR", "n", 2L)),
                List.of(), List.of(), null, 7, List.of(), TODAY, 5.0);

        assertNull(sessions(board).get("avgCostRmb"),
                "5 元成本 + 0 次完成——均价无从谈起（ERROR 会话归因成本仍在账上，只是不平均）");
    }
}