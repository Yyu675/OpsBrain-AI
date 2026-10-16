package com.devops.agent.infrastructure.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置指标目录（L2）。
 *
 * <h3>为什么不让前端直接传 PromQL</h3>
 * 那会把 PromQL 变成对外 API 的一部分，带来三个问题：
 * <ul>
 *   <li><b>安全</b>——任意 PromQL 意味着任意高基数聚合，
 *       一条 {@code count by (__name__)({__name__=~".+"})} 就能把
 *       Prometheus 拖垮，而它是整个监控体系的根基；</li>
 *   <li><b>契约</b>——前端各页各写一份查询语句，指标改名时要改十几处，
 *       且改漏的地方只表现为「图表空白」，没有任何报错；</li>
 *   <li><b>可维护</b>——PromQL 是专业知识，散落在 Vue 文件里无人敢动。</li>
 * </ul>
 *
 * <p>所以对外只暴露<b>指标 ID</b>（如 {@code cpu.usage}），
 * 语句集中在这里。前端要新图表就在这里加一条，评审时能一眼看全。</p>
 *
 * <h3>为什么用 node-exporter 的指标名</h3>
 * 与 {@code monitoring/prometheus.yml} 里已配置的抓取目标一致——
 * 那是本项目 docker-compose 实际起的 exporter。
 * 换成其他 exporter 时只需改这一个文件。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
public final class MetricsCatalog {

    private MetricsCatalog() {
    }

    /**
     * 一条内置指标的定义。
     *
     * @param id       对外 ID，前端只认这个
     * @param name     展示名
     * @param unit     单位：percent / bytes / count / seconds
     * @param promql   查询语句
     * @param describe 这条指标在回答什么问题（给运维看，不是给开发看）
     */
    public record Metric(String id, String name, String unit, String promql, String describe) {
    }

    private static final Map<String, Metric> METRICS = new LinkedHashMap<>();

    private static void put(String id, String name, String unit, String promql, String describe) {
        METRICS.put(id, new Metric(id, name, unit, promql, describe));
    }

    static {
        // ── 主机资源（node-exporter）────────────────────────────────
        // CPU 用「非 idle 占比」而不是直接找 cpu_usage：node-exporter 只给
        // 各模式的累计秒数，必须用 rate + 1-idle 反推。
        // irate 在这里不合适——它只取最后两个点，抖动大到无法看趋势。
        put("cpu.usage", "CPU 使用率", "percent",
                "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)",
                "主机 CPU 繁忙程度。持续 >80% 说明算力已成瓶颈");

        // 内存用 MemAvailable 而非 MemFree：后者不含可回收的 cache，
        // 在 Linux 上永远偏低，会让人误以为内存快满了
        put("memory.usage", "内存使用率", "percent",
                "(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100",
                "主机内存占用。基于 MemAvailable，已扣除可回收缓存");

        put("disk.usage", "磁盘使用率", "percent",
                "(1 - (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\"} "
                        + "/ node_filesystem_size_bytes{fstype!~\"tmpfs|overlay\"})) * 100",
                "各挂载点磁盘占用。已排除 tmpfs/overlay 这类虚拟文件系统");

        put("load.avg1", "1 分钟负载", "count",
                "node_load1",
                "运行队列长度。与 CPU 核数比较才有意义——超过核数说明有排队");

        // ── 网络 ────────────────────────────────────────────────────
        // 排除 lo（回环）：它的流量是进程间通信，混进来会让真实网络流量失真
        put("network.receive", "网络接收速率", "bytes",
                "sum by (instance) (rate(node_network_receive_bytes_total{device!=\"lo\"}[5m]))",
                "入向带宽（字节/秒），已排除回环网卡");

        put("network.transmit", "网络发送速率", "bytes",
                "sum by (instance) (rate(node_network_transmit_bytes_total{device!=\"lo\"}[5m]))",
                "出向带宽（字节/秒），已排除回环网卡");

        // ── 可用性 ──────────────────────────────────────────────────
        put("target.up", "抓取目标存活", "count",
                "up",
                "各抓取目标是否存活。1=正常 0=抓不到，是判断「监控自身是否可信」的第一指标");
    }

    public static Map<String, Metric> all() {
        return Map.copyOf(METRICS);
    }

    public static Metric get(String id) {
        return METRICS.get(id);
    }

    /**
     * 按 ID 取 PromQL。
     *
     * @throws IllegalArgumentException ID 未登记——<b>明确拒绝</b>而不是回退到
     *         某个默认查询。回退会让前端传错 ID 时看到一张「有数据但是错的」图表，
     *         比空图表危险得多。
     */
    public static String promqlOf(String id) {
        Metric m = METRICS.get(id);
        if (m == null) {
            throw new IllegalArgumentException(
                    "未知指标 ID：" + id + "。可选：" + String.join(" / ", METRICS.keySet()));
        }
        return m.promql();
    }

    /** 供前端渲染指标选择器 */
    public static List<Map<String, String>> describeAll() {
        return METRICS.values().stream()
                .map(m -> Map.of(
                        "id", m.id(),
                        "name", m.name(),
                        "unit", m.unit(),
                        "describe", m.describe()))
                .toList();
    }
}
