package com.devops.agent.infrastructure.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prometheus 客户端的解析与边界测试。
 *
 * <p>不发真实 HTTP：网络相关的行为（超时、连接失败）由集成环境验证，
 * 这里覆盖的是<b>解析与判定逻辑</b>——它们出错时不会抛异常，
 * 只会让图表显示错误的数字，是最难发现的一类缺陷。</p>
 */
@DisplayName("Prometheus 客户端")
class PrometheusClientTest {

    @Nested
    @DisplayName("数值解析")
    class ValueParsing {

        @Test
        @DisplayName("正常数字按原值解析")
        void parsesPlainNumbers() {
            assertEquals(0.87, PrometheusClient.parseDouble("0.87"), 1e-9);
            assertEquals(0.0, PrometheusClient.parseDouble("0"), 1e-9);
            assertEquals(-1.5, PrometheusClient.parseDouble("-1.5"), 1e-9);
            assertEquals(1.23e9, PrometheusClient.parseDouble("1.23e9"), 1e-3);
        }

        @Test
        @DisplayName("NaN / Inf 转成 NaN 而非抛异常——它们表示「此刻无有效数据」")
        void specialValuesBecomeNaN() {
            // Prometheus 对刚重启的实例、除零的 rate 会返回这些值，
            // 是正常业务状态，不该让整个查询失败
            assertTrue(Double.isNaN(PrometheusClient.parseDouble("NaN")));
            assertTrue(Double.isNaN(PrometheusClient.parseDouble("+Inf")));
            assertTrue(Double.isNaN(PrometheusClient.parseDouble("-Inf")));
            assertTrue(Double.isNaN(PrometheusClient.parseDouble("Inf")));
        }

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            assertTrue(Double.isNaN(PrometheusClient.parseDouble("nan")));
            assertTrue(Double.isNaN(PrometheusClient.parseDouble("+inf")));
        }

        @Test
        @DisplayName("空值与非法字符串转 NaN，不抛异常")
        void invalidBecomesNaN() {
            assertTrue(Double.isNaN(PrometheusClient.parseDouble(null)));
            assertTrue(Double.isNaN(PrometheusClient.parseDouble("")));
            assertTrue(Double.isNaN(PrometheusClient.parseDouble("   ")));
            assertTrue(Double.isNaN(PrometheusClient.parseDouble("abc")));
        }

        @Test
        @DisplayName("首尾空格被容忍")
        void trimsWhitespace() {
            assertEquals(42.0, PrometheusClient.parseDouble("  42  "), 1e-9);
        }
    }

    @Nested
    @DisplayName("Sample 的取值判定")
    class SampleSemantics {

        @Test
        @DisplayName("hasValue 区分「有值」与「NaN」——NaN 参与运算会静默污染整条链")
        void hasValueDistinguishesNaN() {
            PromQuery.Sample ok = new PromQuery.Sample(
                    java.util.Map.of("instance", "node:9100"), 0.87, 1000L);
            PromQuery.Sample nan = new PromQuery.Sample(
                    java.util.Map.of("instance", "node:9100"), Double.NaN, 1000L);

            assertTrue(ok.hasValue());
            assertFalse(nan.hasValue());
        }

        @Test
        @DisplayName("label 缺失返回空串，不返回 null——避免每个展示点都判空")
        void missingLabelReturnsEmpty() {
            PromQuery.Sample s = new PromQuery.Sample(
                    java.util.Map.of("instance", "node:9100"), 1.0, 1000L);
            assertEquals("node:9100", s.label("instance"));
            assertEquals("", s.label("job"));
        }

        @Test
        @DisplayName("labels 为 null 时 label() 不抛异常")
        void nullLabelsSafe() {
            PromQuery.Sample s = new PromQuery.Sample(null, 1.0, 1000L);
            assertEquals("", s.label("anything"));
        }
    }

    @Nested
    @DisplayName("视图转换")
    class ViewConversion {

        @Test
        @DisplayName("NaN 转成 null——JSON 没有 NaN 字面量，直接序列化会产出非法 JSON")
        void nanBecomesNullInView() {
            PromQuery.Sample nan = new PromQuery.Sample(
                    java.util.Map.of("instance", "n1"), Double.NaN, 1234L);

            var view = PromQuery.toView(nan);
            assertEquals(null, view.get("value"));
            assertEquals(1234L, view.get("timestamp"));
        }

        @Test
        @DisplayName("有效值原样保留")
        void validValueKept() {
            PromQuery.Sample s = new PromQuery.Sample(
                    java.util.Map.of("instance", "n1"), 55.5, 1234L);
            assertEquals(55.5, (Double) PromQuery.toView(s).get("value"), 1e-9);
        }

        @Test
        @DisplayName("时间线里的 NaN 点同样转 null，且保留时间戳")
        void seriesNaNPointsBecomeNull() {
            PromQuery.Series series = new PromQuery.Series(
                    java.util.Map.of("instance", "n1"),
                    java.util.List.of(
                            new PromQuery.Point(1000L, 1.0),
                            new PromQuery.Point(2000L, Double.NaN)));

            var view = PromQuery.toView(series);
            @SuppressWarnings("unchecked")
            var points = (java.util.List<java.util.Map<String, Object>>) view.get("points");

            assertEquals(2, points.size());
            assertEquals(1.0, (Double) points.get(0).get("v"), 1e-9);
            // 断点保留时间戳而不是整点丢弃：图表需要知道「这里断了」，
            // 丢掉点会让折线把断层两端连成一条直线，掩盖故障区间
            assertEquals(null, points.get(1).get("v"));
            assertEquals(2000L, points.get(1).get("t"));
        }
    }

    @Nested
    @DisplayName("指标目录")
    class Catalog {

        @Test
        @DisplayName("未知指标 ID 明确报错，不回退到默认查询")
        void unknownIdRejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> MetricsCatalog.promqlOf("not.a.metric"));
            // 回退会让前端传错 ID 时看到一张「有数据但是错的」图表，比空图表危险
            assertTrue(ex.getMessage().contains("未知指标"));
            assertTrue(ex.getMessage().contains("可选"), "错误信息应列出可选值");
        }

        @Test
        @DisplayName("内置指标都能取到非空 PromQL")
        void allMetricsHavePromql() {
            for (var e : MetricsCatalog.all().entrySet()) {
                String promql = MetricsCatalog.promqlOf(e.getKey());
                assertFalse(promql == null || promql.isBlank(),
                        e.getKey() + " 的 PromQL 为空");
            }
        }

        @Test
        @DisplayName("目录描述包含前端渲染选择器所需的四个字段")
        void describeAllHasRequiredFields() {
            var all = MetricsCatalog.describeAll();
            assertFalse(all.isEmpty());
            for (var m : all) {
                assertTrue(m.containsKey("id"));
                assertTrue(m.containsKey("name"));
                assertTrue(m.containsKey("unit"));
                assertTrue(m.containsKey("describe"));
            }
        }

        @Test
        @DisplayName("CPU 查询排除了 idle 之外的模式反推口径")
        void cpuUsesIdleInversion() {
            // node-exporter 只给各模式累计秒数，没有现成的「使用率」。
            // 若哪天有人把它改成一个不存在的 node_cpu_usage，这条会失败
            String promql = MetricsCatalog.promqlOf("cpu.usage");
            assertTrue(promql.contains("node_cpu_seconds_total"));
            assertTrue(promql.contains("idle"));
        }

        @Test
        @DisplayName("内存用 MemAvailable 而非 MemFree")
        void memoryUsesAvailable() {
            String promql = MetricsCatalog.promqlOf("memory.usage");
            // MemFree 不含可回收 cache，在 Linux 上永远偏低，会误报内存不足
            assertTrue(promql.contains("MemAvailable"));
            assertFalse(promql.contains("MemFree"));
        }

        @Test
        @DisplayName("网络指标排除回环网卡")
        void networkExcludesLoopback() {
            // lo 的流量是进程间通信，混进来会让真实网络流量失真
            assertTrue(MetricsCatalog.promqlOf("network.receive").contains("device!=\"lo\""));
            assertTrue(MetricsCatalog.promqlOf("network.transmit").contains("device!=\"lo\""));
        }

        @Test
        @DisplayName("磁盘指标排除 tmpfs/overlay 这类虚拟文件系统")
        void diskExcludesVirtualFs() {
            String promql = MetricsCatalog.promqlOf("disk.usage");
            assertTrue(promql.contains("tmpfs"));
            assertTrue(promql.contains("overlay"));
        }
    }

    @Nested
    @DisplayName("未启用时的行为")
    class Disabled {

        @Test
        @DisplayName("enabled=false 时查询直接抛 MetricsUnavailable，不发请求")
        void queryFailsFastWhenDisabled() {
            PrometheusClient client = new PrometheusClient(
                    "http://localhost:29090", 5000, false,
                    new com.fasterxml.jackson.databind.ObjectMapper());

            assertFalse(client.isEnabled());
            MetricsUnavailableException ex = assertThrows(MetricsUnavailableException.class,
                    () -> client.query("up"));
            assertTrue(ex.getMessage().contains("未启用"));
        }

        @Test
        @DisplayName("health() 在未启用时如实报告，而不是抛异常")
        void healthReportsDisabled() {
            PrometheusClient client = new PrometheusClient(
                    "http://localhost:29090", 5000, false,
                    new com.fasterxml.jackson.databind.ObjectMapper());

            var health = client.health();
            // 接入管理页要能显示「未启用」这个状态本身，
            // 抛异常会让那个页面自己也打不开
            assertEquals(false, health.get("reachable"));
            assertEquals(false, health.get("enabled"));
            assertTrue(String.valueOf(health.get("error")).contains("未启用"));
        }

        @Test
        @DisplayName("baseUrl 尾斜杠被去掉，避免拼出 //api/v1/query")
        void trimsTrailingSlash() {
            PrometheusClient client = new PrometheusClient(
                    "http://localhost:29090/", 5000, true,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            assertEquals("http://localhost:29090", client.baseUrl());
        }
    }
}
