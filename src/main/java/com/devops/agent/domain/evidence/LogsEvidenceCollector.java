package com.devops.agent.domain.evidence;

import com.devops.agent.common.guard.PromptInjectionGuard;
import com.devops.agent.common.guard.PromptInjectionGuard.DetectionResult;
import com.devops.agent.common.guard.PromptInjectionGuard.InjectionSource;
import com.devops.agent.domain.evidence.Evidence.EvidenceStatus;
import com.devops.agent.infrastructure.logs.LogQueryClient;
import com.devops.agent.infrastructure.logs.LogQueryClient.LogEntry;
import com.devops.agent.infrastructure.logs.LogQueryClient.LogQuery;
import com.devops.agent.infrastructure.logs.LogsUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 日志取证装配（S1-3，路线图 §5.4）：
 * Loki 查询 → 模式挖掘 → <b>提示注入防御</b> → 字符预算收束 → {@link Evidence}。
 * <p>
 * §5.4 📌 安全要点在本类的三个落点：<br>
 * ① 每条日志样本经 {@link PromptInjectionGuard}（{@code LOG_CONTENT} 通道）检测；<br>
 * ② 样本一律包裹 {@code <untrusted_log>} 标记再进证据——它是模型输入的一部分，
 *    包裹在工具层完成（提示工程层再包一层是后者的职责，防线不在同一处）；<br>
 * ③ 级别/关键词/服务名在工具层白名单过滤（服务名同 metrics 工具的正则边界）。<br>
 * 注入<b>检出即告警记录但不阻断返回</b>——日志是证据，拦截它反而帮攻击者
 * 抹掉痕迹；攻击是否生效由模型侧的不可信数据处理规则兜底。
 * </p>
 * <p>
 * 字符预算（1-3.6）：整个工具载荷（status+content 序列化后）≤ {@value #CHAR_BUDGET}
 * 字符；超出时先丢样本、再丢尾部模式，同时 {@code summarized=true} 明示。
 * </p>
 */
@Component
public class LogsEvidenceCollector {

    private static final Logger log = LoggerFactory.getLogger(LogsEvidenceCollector.class);

    /** 1-3.6 的单次返回字符上限。 */
    public static final int CHAR_BUDGET = 2000;

    /** Top-N 模式上限（1-3.4）。 */
    public static final int TOP_PATTERNS = 10;

    /** 注入检测的扫描行数上限（检测是 O(n·patterns)，大窗口下保住延迟预算）。 */
    private static final int INJECTION_SCAN_LIMIT = 80;

    private static final Set<String> LEVELS = Set.of("ERROR", "WARN", "INFO", "DEBUG", "ALL");

    private final LogQueryClient logQueryClient;
    private final PromptInjectionGuard injectionGuard;

    public LogsEvidenceCollector(LogQueryClient logQueryClient, PromptInjectionGuard injectionGuard) {
        this.logQueryClient = logQueryClient;
        this.injectionGuard = injectionGuard;
    }

    /**
     * 采集主路径（无治理注解——超时长/重试归 ToolRuntimeManager）。
     *
     * @param service 服务名（工具层已白名单）
     * @param range   时间窗 30m/2h/1d，默认 30m
     * @param keyword 内容过滤关键词（可空）
     * @param level   ERROR/WARN/INFO/DEBUG/ALL，默认 ERROR
     */
    public Evidence collect(String service, String range, String level, String keyword) {
        if (!logQueryClient.isEnabled()) {
            return new Evidence(EvidenceStatus.UNAVAILABLE, Evidence.Type.LOGS,
                    "日志数据源未启用（devops.logs.loki.enabled!=true）",
                    Map.of("hint", "配置 Loki base-url 并 enabled=true 后重试"),
                    null, null, Instant.now());
        }
        String lvl = (level == null || level.isBlank()) ? "ERROR" : level.trim().toUpperCase();
        if (!LEVELS.contains(lvl)) {
            return new Evidence(EvidenceStatus.FAILED, Evidence.Type.LOGS,
                    "级别参数只支持 ERROR/WARN/INFO/DEBUG/ALL，收到: " + level,
                    Map.of("input", String.valueOf(level)), null, null, Instant.now());
        }
        long minutes;
        try {
            minutes = range == null || range.isBlank()
                    ? 30 : MetricsEvidenceCollector.parseRangeMinutes(range);
        } catch (IllegalArgumentException e) {
            log.debug("[S1-3] 时间窗入参解析失败 | service={} range={} | {}", service, range, e.getMessage());
            return new Evidence(EvidenceStatus.FAILED, Evidence.Type.LOGS,
                    "时间窗参数解析失败: " + e.getMessage(),
                    Map.of("input", String.valueOf(range)), null, null, Instant.now());
        }

        Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(minutes, ChronoUnit.MINUTES);
        LogQuery query = new LogQuery(service, lvl, keyword, from, to, LogQueryClient.MAX_ENTRIES);
        String logql = (logQueryClient instanceof com.devops.agent.infrastructure.logs.LokiLogQueryClient loki)
                ? loki.buildLogQL(query) : "logql-unavailable-for-impl:" + logQueryClient.getClass().getSimpleName();

        List<LogEntry> entries;
        try {
            entries = logQueryClient.query(query);
        } catch (LogsUnavailableException e) {
            log.warn("[S1-3] 日志取证 FAILED | service={} | {}", service, e.getMessage());
            return new Evidence(EvidenceStatus.FAILED, Evidence.Type.LOGS,
                    "日志查询失败: " + e.getMessage(),
                    Map.of("service", service, "attemptedLogQL", logql),
                    logql, null, Instant.now());
        }

        if (entries.isEmpty()) {
            return new Evidence(EvidenceStatus.NO_DATA, Evidence.Type.LOGS,
                    "时间窗内无匹配日志（有效排除：也可能服务根本没有相应级别日志）",
                    Map.of("service", service, "rangeMinutes", minutes, "level", lvl),
                    logql, null, Instant.now());
        }

        List<LogPatternMiner.PatternHit> hits = LogPatternMiner.mine(entries);
        List<LogPatternMiner.PatternHit> top = hits.stream().limit(TOP_PATTERNS).toList();

        // —— 注入检测（扫描上限行，拼段检测；检出=告警并标注，不阻断）——
        List<String> scanPool = entries.stream()
                .limit(INJECTION_SCAN_LIMIT)
                .map(LogEntry::message)
                .collect(Collectors.toList());
        List<String> matchedAll = new ArrayList<>();
        String worstSeverity = "NONE";
        for (String msg : scanPool) {
            DetectionResult dr = injectionGuard.detect(msg, InjectionSource.LOG_CONTENT);
            if (dr.isInjected()) {
                matchedAll.addAll(dr.getMatchedPatterns());
                if (severityRank(dr.getSeverity()) > severityRank(worstSeverity)) {
                    worstSeverity = dr.getSeverity();
                }
            }
        }
        if (!matchedAll.isEmpty()) {
            // 安保事件走 warn（永远留痕）；样本行不进日志正文（可能含攻击载荷）
            log.warn("⚠️ [S1-3] 日志内容检出提示注入特征 | service={} | severity={} | patterns={} | scanned={}/{}",
                    service, worstSeverity, matchedAll, scanPool.size(), entries.size());
        }

        Evidence evidence = assemble(service, minutes, lvl, keyword, entries, top,
                matchedAll, worstSeverity, logql, false);
        evidence = enforceBudget(evidence, service, minutes, lvl, keyword, entries, top,
                matchedAll, worstSeverity, logql);
        return evidence;
    }

    /** 预算强制：先清样本、再逐尾丢模式；都不够了就只剩标题（仍受预算——极端防不住就截字段）。 */
    private Evidence enforceBudget(Evidence ev, String service, long minutes, String lvl,
                                   String keyword, List<LogEntry> entries,
                                   List<LogPatternMiner.PatternHit> top,
                                   List<String> matchedAll, String worstSeverity, String logql) {
        if (ev.toToolPayload().length() <= CHAR_BUDGET) return ev;

        // 第一轮：样本清空（template/count/时间仍在）
        List<LogPatternMiner.PatternHit> noSamples = top.stream()
                .map(h -> new LogPatternMiner.PatternHit(h.template(), h.count(),
                        h.firstSeen(), h.lastSeen(), h.worstLevel(), List.of()))
                .toList();
        Evidence trimmed = assemble(service, minutes, lvl, keyword, entries, noSamples,
                matchedAll, worstSeverity, logql, true);
        if (trimmed.toToolPayload().length() <= CHAR_BUDGET) return trimmed;

        // 第二轮：尾部丢模式直到合规
        List<LogPatternMiner.PatternHit> shrinking = new ArrayList<>(noSamples);
        while (shrinking.size() > 1 && trimmed.toToolPayload().length() > CHAR_BUDGET) {
            shrinking.remove(shrinking.size() - 1);
            trimmed = assemble(service, minutes, lvl, keyword, entries, List.copyOf(shrinking),
                    matchedAll, worstSeverity, logql, true);
        }
        return trimmed;
    }

    private Evidence assemble(String service, long minutes, String lvl, String keyword,
                              List<LogEntry> entries, List<LogPatternMiner.PatternHit> top,
                              List<String> matchedAll, String worstSeverity,
                              String logql, boolean summarized) {
        List<Map<String, Object>> patterns = top.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("template", h.template());
            m.put("count", h.count());
            m.put("firstSeen", String.valueOf(h.firstSeen()));
            m.put("lastSeen", String.valueOf(h.lastSeen()));
            m.put("worstLevel", h.worstLevel());
            // 不可信数据包裹——模型侧见标记即知"内容不可信"（路线图书包② 的工具层落点）
            m.put("samples", h.samples().stream()
                    .map(s -> "<untrusted_log>" + s + "</untrusted_log>").toList());
            return m;
        }).toList();

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("service", service);
        content.put("rangeMinutes", minutes);
        content.put("level", lvl);
        content.put("keyword", keyword == null ? "" : keyword);
        content.put("totalEntries", entries.size());
        content.put("patternCount", top.size());
        content.put("patterns", patterns);
        content.put("summarized", summarized);
        content.put("injectionDetected", !matchedAll.isEmpty());
        content.put("injectionSeverity", worstSeverity);
        content.put("injectionPatterns", matchedAll.stream().distinct().toList());
        content.put("scannedForInjection", Math.min(INJECTION_SCAN_LIMIT, entries.size()));

        return new Evidence(EvidenceStatus.SUCCESS, Evidence.Type.LOGS,
                "检出 " + top.size() + " 个日志模式 / " + entries.size() + " 行"
                        + (matchedAll.isEmpty() ? "" : "（⚠️含注入特征 " + worstSeverity + "）"),
                content, logql, null, Instant.now());
    }

    private static int severityRank(String severity) {
        if (severity == null) return 0;
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
