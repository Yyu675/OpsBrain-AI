package com.devops.agent.domain.evidence;

import com.devops.agent.infrastructure.logs.LogQueryClient.LogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Drain-lite 模式挖掘：掩码口径与聚类行为逐条钉死（S1-3）。 */
class LogPatternMinerTest {

    private static LogEntry entry(String msg) {
        return new LogEntry(Instant.ofEpochSecond(1_700_000_000), "ERROR", msg, Map.of());
    }

    @Test
    @DisplayName("时间戳/IP/UUID/数字/引号串掩码成 <*>，模板可聚合")
    void masksVariables() {
        String tpl = LogPatternMiner.toTemplate(
                "2026-09-07T14:30:12.345Z ERROR conn 10.0.0.8:5432 timeout after 3000ms, txn 8f14e45fceea167a5b36c1d3 \"deadlock victim\" retry 3");
        assertEquals("<*> ERROR conn <*> timeout after <*>, txn <*> <*> retry <*>", tpl);
    }

    @Test
    @DisplayName("变量不同的同类日志聚成同一模式（count 合并，首末件时间正确）")
    void clustersSameShape() {
        Instant t1 = Instant.ofEpochSecond(1_700_000_000);
        Instant t2 = t1.plusSeconds(60);
        var hits = LogPatternMiner.mine(List.of(
                new LogEntry(t1, "ERROR", "db connection failed for host 10.0.0.1", Map.of()),
                new LogEntry(t2, "WARN", "db connection failed for host 10.0.0.2", Map.of()),
                new LogEntry(t1, "ERROR", "unrelated startup error", Map.of())));
        assertEquals(2, hits.size());
        var main = hits.get(0);
        assertEquals(2, main.count());
        assertEquals("db connection failed for host <*>", main.template());
        assertEquals(t1, main.firstSeen());
        assertEquals(t2, main.lastSeen());
        assertEquals("ERROR", main.worstLevel(), "聚类内最差级别优先（ERROR>WARN）");
        var rest = hits.get(1);
        assertEquals(1, rest.count());
    }

    @Test
    @DisplayName("样本限 3 条；count 并列时最差级别优先排前")
    void samplesCappedAndSeverityBreaksTies() {
        var entries = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> entry("same error shape number " + i))
                .toList();
        var hits = LogPatternMiner.mine(entries);
        assertEquals(1, hits.size());
        assertEquals(5, hits.get(0).count());
        assertEquals(3, hits.get(0).samples().size(), "样本必须限 3 条（路线图 1-3.4）");

        var mixed = LogPatternMiner.mine(List.of(
                entry("aaa 1"), entry("aaa 2"),
                new LogEntry(Instant.now(), "WARN", "bbb 1", Map.of()),
                new LogEntry(Instant.now(), "WARN", "bbb 2", Map.of())));
        assertEquals(2, mixed.get(0).count());
        assertEquals("ERROR", mixed.get(0).worstLevel(), "并列时 ERROR 模式应在 WARN 前");
    }

    @Test
    @DisplayName("空输入与 null 消息不崩（证据鲁棒性底线）")
    void robustness() {
        assertTrue(LogPatternMiner.mine(List.of()).isEmpty());
        assertEquals("", LogPatternMiner.toTemplate(null));
        var h = LogPatternMiner.mine(List.of(new LogEntry(Instant.now(), null, "x", Map.of())));
        assertEquals("INFO", h.get(0).worstLevel(), "无级别标 INFO 兜底");
    }
}
