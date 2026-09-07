package com.devops.agent.domain.evidence;

import com.devops.agent.domain.biz.entity.ChangeEvent;
import com.devops.agent.domain.biz.repository.ChangeEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变更取证装配（S1-2）：查库 → 时间相关性分档 → {@link Evidence}。
 * <p>
 * NO_DATA 与 FAILED 语义边界（路线图 §5.3 📌）：
 * 空结果 = NO_DATA（是<b>有效排除证据</b>，可提升其他方向置信度）；
 * 只有查询本身抛异常才 = FAILED（证据缺口）。二者在推理层权重不同。
 * </p>
 * <p>
 * sourceRef：本工具的「可下钻」锚 = 查询谓词的可读转述
 * （服务/时间窗/行数），不含 SQL 全文——change 表是库内数据，无谓 PromQL；
 * 回放靠 S1-5 的落库 traceId，不占此字段。
 * </p>
 */
@Component
public class ChangesEvidenceCollector {

    private static final Logger log = LoggerFactory.getLogger(ChangesEvidenceCollector.class);

    /** 单次返回行数上限：变更密集期防上下文刷屏（路线图表内 2000 字符同类约束）。 */
    public static final int MAX_ROWS = 20;

    private final ChangeEventRepository repository;

    public ChangesEvidenceCollector(ChangeEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 无治理注解——治理归工具层 @ToolMeta（与 S1-1 同边线）。
     *
     * @param service 服务名（工具层已白名单校验）
     * @param range   时间窗 30m/2h/1d，默认 2h（路线图默认口径），上限 7d
     */
    public Evidence collect(String service, String range) {
        long minutes;
        try {
            minutes = range == null || range.isBlank()
                    ? 120 : MetricsEvidenceCollector.parseRangeMinutes(range);
        } catch (IllegalArgumentException e) {
            return new Evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.CHANGES,
                    "时间窗参数解析失败: " + e.getMessage(),
                    Map.of("input", String.valueOf(range)), null, null, Instant.now());
        }

        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        List<ChangeEvent> rows;
        try {
            rows = repository.findRecent(service, since, MAX_ROWS);
        } catch (Exception e) {
            log.error("[S1-2] 变更取证 FAILED | service={} | {}", service, e.toString());
            return new Evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.CHANGES,
                    "变更事件查询失败: " + e.getMessage(),
                    Map.of("service", service), null, null, Instant.now());
        }

        String ref = "sys_change_event where service_name='" + service
                + "' and change_time>=now()-" + minutes + "m (limit " + MAX_ROWS + ")";
        if (rows.isEmpty()) {
            return new Evidence(Evidence.EvidenceStatus.NO_DATA, Evidence.Type.CHANGES,
                    "时间窗内无变更记录（有效排除：可暂排变更因素，但注意上报源覆盖度）",
                    Map.of("service", service, "rangeMinutes", minutes),
                    ref, null, Instant.now());
        }

        Duration topDelta = Duration.between(rows.get(0).changeTime(), LocalDateTime.now());
        double topScore = ChangeRelevanceScorer.score(topDelta);
        List<Map<String, Object>> changes = rows.stream().map(e -> {
            double score = ChangeRelevanceScorer.score(
                    Duration.between(e.changeTime(), LocalDateTime.now()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("changeTime", e.changeTime().toString());
            m.put("reportedAt", e.reportedAt().toString());
            m.put("changeType", e.changeType());
            m.put("operator", e.operator());
            m.put("summary", e.summary());
            m.put("source", e.source());
            m.put("externalId", e.externalId());
            m.put("relevanceScore", score);
            return m;
        }).toList();

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("service", service);
        content.put("rangeMinutes", minutes);
        content.put("count", rows.size());
        content.put("truncated", rows.size() >= MAX_ROWS);
        content.put("changes", changes);
        return new Evidence(Evidence.EvidenceStatus.SUCCESS, Evidence.Type.CHANGES,
                "检出 " + rows.size() + " 条变更（最近距今 "
                        + (topDelta.toMinutes()) + " 分钟，相关性 " + String.format(java.util.Locale.ROOT, "%.1f", topScore) + "）",
                content, ref, topScore, Instant.now());
    }
}
