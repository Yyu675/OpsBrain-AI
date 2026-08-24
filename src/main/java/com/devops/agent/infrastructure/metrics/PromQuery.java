package com.devops.agent.infrastructure.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus 查询结果的领域模型（L2）。
 *
 * <h3>为什么不直接把 Prometheus 的 JSON 透传给前端</h3>
 * Prometheus 的响应结构是为它自己的数据模型设计的：
 * {@code {status, data:{resultType, result:[{metric:{...}, value:[ts, "字符串"]}]}}}。
 * 直接透传有三个问题：
 * <ul>
 *   <li><b>值是字符串</b>（{@code "0.87"}），前端每处都要 parseFloat，
 *       漏一处就变成字符串拼接（{@code "0.87" + 1 === "0.871"}）；</li>
 *   <li><b>时间戳是秒级浮点</b>，与项目其余接口的毫秒/ISO 格式不一致；</li>
 *   <li>把上游的数据结构变成我们的对外契约，将来换 VictoriaMetrics
 *       或加一层聚合就是破坏性变更。</li>
 * </ul>
 * 所以在这里收敛成项目自己的结构，上游格式的变化被挡在这一层。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
public final class PromQuery {

    private PromQuery() {
    }

    /**
     * 瞬时查询的单个结果（instant query）。
     *
     * @param labels 指标标签，如 {@code {instance: "node:9100", job: "node"}}
     * @param value  数值。<b>已转成 double</b>，NaN 表示上游返回了非数字
     *               （Prometheus 会用 "NaN" 表示无数据，不是错误）
     * @param timestampMs 采样时刻（毫秒，已从秒级换算）
     */
    public record Sample(Map<String, String> labels, double value, long timestampMs) {

        /** 取某个标签，缺失返回空串——调用方多数场景只是拿来展示 */
        public String label(String name) {
            String v = labels == null ? null : labels.get(name);
            return v == null ? "" : v;
        }

        /**
         * 值是否可用。
         * <p>Prometheus 对「目标不存在/刚重启还没数据」返回 NaN 而非报错，
         * 直接拿去做算术会静默污染整条计算链（NaN 参与任何运算都是 NaN），
         * 前端还会渲染成字面量 "NaN"。调用方必须先判这一下。</p>
         */
        public boolean hasValue() {
            return !Double.isNaN(value);
        }
    }

    /**
     * 区间查询的一条时间线（range query）。
     *
     * @param labels 指标标签
     * @param points 按时间升序的采样点
     */
    public record Series(Map<String, String> labels, List<Point> points) {

        public String label(String name) {
            String v = labels == null ? null : labels.get(name);
            return v == null ? "" : v;
        }
    }

    /** 时间线上的一个点 */
    public record Point(long timestampMs, double value) {
    }

    /**
     * 面向前端的扁平化输出。
     *
     * <p>做成 {@code Map} 而非再定义一层 DTO：这层数据是纯展示用的，
     * 字段随图表需求变动频繁，定义 DTO 会让每次加一个字段都要改三处
     * （DTO、转换器、前端类型）。用 Map + 前端 TS 接口约束，
     * 改动只在两处，且前端类型是真正被 tsc 检查的那一份。</p>
     */
    public static Map<String, Object> toView(Sample s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labels", s.labels());
        // 无值时给 null 而不是 NaN：JSON 没有 NaN 字面量，
        // Jackson 默认会序列化成非法 JSON 或抛异常
        m.put("value", s.hasValue() ? s.value() : null);
        m.put("timestamp", s.timestampMs());
        return m;
    }

    public static Map<String, Object> toView(Series s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labels", s.labels());
        m.put("points", s.points().stream()
                .map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("t", p.timestampMs());
                    pm.put("v", Double.isNaN(p.value()) ? null : p.value());
                    return pm;
                })
                .toList());
        return m;
    }
}
