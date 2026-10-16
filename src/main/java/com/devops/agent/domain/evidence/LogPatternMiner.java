package com.devops.agent.domain.evidence;

import com.devops.agent.infrastructure.logs.LogQueryClient.LogEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 日志模式挖掘（S1-3，路线图 §5.4 1-3.3）：Drain 算法简化版。
 * <p>
 * 「简化」的边界写死在此：只做<b>变量掩码 + 模板聚类</b>，
 * 不做 Drain 的日志树逐 token 匹配。真实 Drain 靠前缀树把近似模板归一，
 * 在常见运维错误日志（结构统一的框架栈行）上，变量级掩码已能拿到
 * 80% 聚类收益，且行为完全可测试、可作 javadoc 逐条解释——
 * 复杂度的引入必须换来可验收的增量，这是本项目的一贯取舍。
 * </p>
 * <p>
 * 掩码顺序即优先级（先具体后一般，防止数字规则吃掉 IP/时间戳的局部）：
 * ISO 时间戳 → 纯日期/时间 → IP(+端口) → UUID → 长十六进制 → <br>
 * 时长(\d+ms/s/min/h) → 引号串 → 裸数字，全部统一为 {@code <*>}。
 * 连续空白压成单空格后作为模板键。
 * </p>
 */
public final class LogPatternMiner {

    /** 单模式样本行保留上限（路线图 1-3.4：原始样本限 3 条）。 */
    public static final int MAX_SAMPLES = 3;

    /** 一个聚合好的模式（record 字段名即 JSON 键名，逐字为准）。 */
    public record PatternHit(
            String template,
            int count,
            Instant firstSeen,
            Instant lastSeen,
            String worstLevel,
            List<String> samples) {}

    private static final List<Pattern> MASKS = List.of(
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+\\-]\\d{2}:?\\d{2})?"),
            Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
            Pattern.compile("\\b\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b"),
            Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b"),
            Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"),
            Pattern.compile("\\b[0-9a-fA-F]{16,}\\b"),
            Pattern.compile("\\b\\d+(?:ms|s|min|h)\\b"),
            Pattern.compile("\"[^\"]*\"|'[^']*'"),
            Pattern.compile("\\b\\d+\\b"));

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private LogPatternMiner() {}

    /** 单行掩码为模板（确定性、可单测）。 */
    public static String toTemplate(String line) {
        String t = line == null ? "" : line;
        for (Pattern mask : MASKS) {
            t = mask.matcher(t).replaceAll("<*>");
        }
        return WHITESPACE.matcher(t.trim()).replaceAll(" ");
    }

    /**
     * 聚类（保首次/末次出现、最差级别、样本≤{@value #MAX_SAMPLES} 条）。
     *
     * @return 按出现次数降序；count 并列时按最差级别优先级（ERROR&gt;WARN&gt;DEBUG&gt;INFO）
     */
    public static List<PatternHit> mine(List<LogEntry> entries) {
        Map<String, Cluster> clusters = new LinkedHashMap<>();
        for (LogEntry e : entries) {
            String tpl = toTemplate(e.message());
            Cluster c = clusters.computeIfAbsent(tpl, k -> new Cluster().withTemplate(k));
            c.count++;
            Instant ts = e.timestamp();
            if (c.firstSeen == null || ts.isBefore(c.firstSeen)) c.firstSeen = ts;
            if (c.lastSeen == null || ts.isAfter(c.lastSeen)) c.lastSeen = ts;
            if (levelRank(e.level()) > levelRank(c.worstLevel)) c.worstLevel = e.level();
            if (c.samples.size() < MAX_SAMPLES) c.samples.add(e.message());
        }
        List<Cluster> sorted = new ArrayList<>(clusters.values());
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(b.count, a.count);
            return cmp != 0 ? cmp : Integer.compare(levelRank(b.worstLevel), levelRank(a.worstLevel));
        });
        return sorted.stream()
                .map(c -> new PatternHit(c.template, c.count, c.firstSeen, c.lastSeen,
                        c.worstLevel == null ? "INFO" : c.worstLevel, List.copyOf(c.samples)))
                .toList();
    }

    private static int levelRank(String level) {
        if (level == null) return 0;
        return switch (level.toUpperCase()) {
            case "FATAL", "ERROR", "SEVERE" -> 3;
            case "WARN" -> 2;
            case "DEBUG", "TRACE" -> 1;
            default -> 0;
        };
    }

    private static final class Cluster {
        String template;
        int count;
        Instant firstSeen;
        Instant lastSeen;
        String worstLevel;
        final List<String> samples = new ArrayList<>();

        Cluster withTemplate(String t) { this.template = t; return this; }
    }
}
