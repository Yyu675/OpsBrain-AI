package com.devops.agent.infrastructure.logs;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 日志查询客户端抽象（S1-3，路线图 §5.4 1-3.1）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>接口先行、实现可替换</b>：ELK/Loki/VictoriaLogs 各有 API，
 *       工具层只跟本接口对话——首实现为 Loki（业内 SaaS/自建最常见）；</li>
 *   <li><b>未启用与故障分离</b>：isEnabled()==false 是部署形态
 *       （采集器译作 UNAVAILABLE），查询抛 {@link LogsUnavailableException}
 *       是运行故障（FAILED）——位置完全复刻 metrics/prometheus 谱系；</li>
 *   <li><b>上限硬编码</b>：单次最多 {@value #MAX_ENTRIES} 行原始日志，
 *       防止模型传 limit=100000 把响应体撑爆——超出的摘要化归
 *       LogsEvidenceCollector 管。</li>
 * </ul>
 * </p>
 */
public interface LogQueryClient {

    int MAX_ENTRIES = 200;

    /** 一条日志行。message 为原始文本（未经注入防御处理，消费方必须按不可信数据对待）。 */
    record LogEntry(Instant timestamp, String level, String message, Map<String, String> labels) {}

    /** 查询条件。level=null 表全级别；keyword=null 表不做内容过滤。 */
    record LogQuery(String service, String level, String keyword,
                    Instant from, Instant to, int maxEntries) {

        public LogQuery {
            if (maxEntries <= 0 || maxEntries > MAX_ENTRIES) {
                maxEntries = Math.min(Math.max(maxEntries, 1), MAX_ENTRIES);
            }
        }
    }

    /** 数据源是否已配置启用（false=部署形态，工具层应回 UNAVAILABLE）。 */
    boolean isEnabled();

    /**
     * 执行查询。返回按时间排序的日志行（实现可保序或交付后由采集器排序）。
     *
     * @throws LogsUnavailableException 数据源不可达/超时/返回错误（含熔断打开）
     */
    List<LogEntry> query(LogQuery query);
}
