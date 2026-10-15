package com.devops.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监控配置的机械对账（2026-10-15）。
 *
 * <h3>为什么监控配置需要门禁</h3>
 * 监控栈有三种"各自都对、拼起来是空"的失效，且**都不报错**：
 * <ol>
 *   <li><b>指标名对不上</b>：看板查 {@code devops_diagnosis_seconds}，代码里叫
 *       {@code opsbrain_diagnosis_seconds} → 面板永远空白（本仓真实发生过，
 *       而且空面板比没面板更坏：它让人以为已经监控到了）；</li>
 *   <li><b>规则没人供给</b>：{@code pg_up}/{@code redis_up} 没有 exporter →
 *       比较运算对空序列恒不成立，不误报，也**永远不响**；</li>
 *   <li><b>抓取面被鉴权拦掉</b>：应用要登录、Prometheus 不会登录 → 恒 401 →
 *       {@code up=0} 让宕机规则常亮误报（本仓历史上那条反复出现的 OpsBrainDown）。</li>
 * </ol>
 * 三者都靠人眼审 yml 抓不住——改指标名时没人会想起看板。
 *
 * <h3>与 Python 版的关系（同一判据的两个执行体）</h3>
 * 本地/排障用 {@code tools/verify/monitoring_audit.py}（还能实测抓取面状态码、列 CI 上闸情况）；
 * 这里这份是为了**进 CI 门禁**——它跑在既有的后端测试腿里，不需要改 workflow。
 * 改动判据（前缀表、提取规则）时两边必须同步，否则就是"核对另一份规则"。
 */
@DisplayName("监控配置契约：指标名 / 供给 / 抓取凭据")
class MonitoringConfigContractTest {

    /** 框架自动注册（Boot Actuator / Micrometer 自带）：被抓取面覆盖就在，指不上=抓取面没通。 */
    private static final List<String> FRAMEWORK_PREFIXES = List.of(
            "jvm_", "http_server_requests_", "process_", "system_", "tomcat_", "executor_",
            "hikaricp_", "spring_", "logback_events_", "resilience4j_", "cache_", "application_", "up");

    /** 部署侧供给（exporter / 中间件自带）：需要 compose 服务 + 抓取 job 两件齐。 */
    private static final List<String> EXTERNAL_PREFIXES = List.of(
            "node_", "pg_", "pgbouncer_", "redis_", "minio_", "kube_");

    /** PromQL 函数/关键字与常用标签名：不是指标。 */
    private static final Set<String> PROMQL_TOKENS = Set.of(
            "sum", "min", "max", "avg", "count", "stddev", "stdvar", "topk", "bottomk", "quantile",
            "group", "by", "without", "on", "ignoring", "group_left", "group_right", "bool", "offset",
            "and", "or", "unless", "absent", "absent_over_time", "rate", "irate", "increase", "delta",
            "idelta", "deriv", "predict_linear", "histogram_quantile", "changes", "resets",
            "avg_over_time", "sum_over_time", "min_over_time", "max_over_time", "count_over_time",
            "last_over_time", "present_over_time", "vector", "scalar", "time", "timestamp", "clamp",
            "clamp_max", "clamp_min", "round", "abs", "ceil", "floor", "exp", "ln", "log2", "log10",
            "sqrt", "sort", "sort_desc", "label_replace", "label_join", "le", "instance", "job",
            "mode", "result", "status", "uri", "method", "area", "id", "mountpoint", "service",
            "module", "severity", "alertname", "alert_kind", "field", "kind", "true", "false");

    private static Path root;

    @BeforeAll
    static void locateProjectRoot() {
        // surefire 的工作目录是模块根；但为稳妥（IDE 直跑 / 多模块）按 marker 文件向上找
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve("monitoring/prometheus.yml"))) {
                root = dir;
                return;
            }
        }
        throw new IllegalStateException("找不到项目根（含 monitoring/prometheus.yml）");
    }

    // ------------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(String relative) throws Exception {
        try (InputStream in = Files.newInputStream(root.resolve(relative))) {
            return new Yaml().loadAs(in, LinkedHashMap.class);
        }
    }

    /** 从 PromQL 里剥掉字符串/标签选择器/区间，只留标识符。 */
    private static Set<String> identifiers(String expr) {
        String skeleton = expr
                .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", " ")
                .replaceAll("'(?:[^'\\\\]|\\\\.)*'", " ")
                .replaceAll("\\{[^}]*}", " ")
                .replaceAll("\\[[^]]*]", " ");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b").matcher(skeleton);
        while (m.find()) {
            String token = m.group();
            if (PROMQL_TOKENS.contains(token)) {
                continue;
            }
            out.add(token);
        }
        return out;
    }

    /** 代码里声明的指标：{@code Counter/Timer/Gauge.builder("a.b.c"…)} → {@code a_b_c}（与 python 版同一正则）。 */
    private static Set<String> codeDeclaredMetrics() throws Exception {
        Pattern builder = Pattern.compile(
                "(?:Counter|Timer|Gauge|DistributionSummary|LongTaskTimer)\\.builder\\(\\s*\"([A-Za-z0-9_.\\-]+)\"");
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = builder.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    names.add(m.group(1).replace('.', '_').replace('-', '_'));
                }
            }
        }
        return names;
    }

    /** 引用到的指标（规则 + 看板）与出处，用于失败时点名。 */
    private static Map<String, List<String>> referencedMetrics() throws Exception {
        Map<String, List<String>> referenced = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        Map<String, Object> rules = readYaml("monitoring/alert.rules.yml");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) rules.get("groups");
        for (Map<String, Object> group : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rulesOfGroup = (List<Map<String, Object>>) group.get("rules");
            for (Map<String, Object> rule : rulesOfGroup) {
                String name = String.valueOf(rule.get("alert"));
                add(referenced, "规则:" + name, String.valueOf(rule.get("expr")));
            }
        }

        JsonNode dashboard = new ObjectMapper()
                .readTree(Files.readString(root.resolve("monitoring/grafana/opsbrain-effectiveness-dashboard.json"),
                        StandardCharsets.UTF_8));
        for (JsonNode panel : dashboard.path("panels")) {
            String title = panel.path("title").asText("(无标题)");
            for (JsonNode target : panel.path("targets")) {
                if (target.hasNonNull("expr")) {
                    add(referenced, "看板:" + title, target.get("expr").asText());
                }
            }
        }
        return referenced;
    }

    private static void add(Map<String, List<String>> referenced, String where, String expr) {
        boolean skip = expr == null || expr.isBlank() || "null".equals(expr);
        // 看板里少数面板用 vector()/动态表达式，无指标名可对
        for (String metric : skip ? Set.<String>of() : identifiers(expr)) {
            referenced.computeIfAbsent(metric, k -> new ArrayList<>()).add(where);
        }
    }

    // ------------------------------------------------------------------ 断言

    @Test
    @DisplayName("规则与看板引用的每个指标都得有人供给（代码声明 / 框架自动 / 部署侧 exporter）")
    void everyReferencedMetricHasASupplier() throws Exception {
        Set<String> codeDeclared = codeDeclaredMetrics();
        List<String> unknown = new ArrayList<>();
        StringBuilder detail = new StringBuilder();

        for (Map.Entry<String, List<String>> entry : referencedMetrics().entrySet()) {
            String metric = entry.getKey();
            String base = stripDerivedSuffix(metric, codeDeclared);
            boolean supplied = codeDeclared.contains(base)
                    || FRAMEWORK_PREFIXES.stream().anyMatch(metric::startsWith)
                    || EXTERNAL_PREFIXES.stream().anyMatch(metric::startsWith);
            if (!supplied) {
                unknown.add(metric);
                detail.append("\n  - ").append(metric)
                        .append("  ← ").append(String.join("、", entry.getValue()));
            }
        }

        assertTrue(unknown.isEmpty(),
                "有 " + unknown.size() + " 个指标" + "既不在代码里声明、也不属框架/部署侧供给面——"
                        + "这会表现为「面板永远空白」或「规则永远不触发」：" + detail
                        + "\n  代码里已声明的指标（供对账）：" + codeDeclared);
    }

    /** Micrometer 的 Timer/Counter 会派生 _count/_sum/_bucket，按基名归类。 */
    private static String stripDerivedSuffix(String metric, Set<String> codeDeclared) {
        for (String suffix : List.of("_bucket", "_count", "_sum", "_max", "_min")) {
            if (metric.endsWith(suffix)) {
                String base = metric.substring(0, metric.length() - suffix.length());
                if (codeDeclared.contains(base)) {
                    return base;
                }
            }
        }
        return metric;
    }

    @Test
    @DisplayName("抓取面必须带凭据：/actuator/** 要登录，Prometheus 就必须带令牌（否则恒 401）")
    void scrapeJobCarriesCredentials() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> prometheus = readYaml("monitoring/prometheus.yml");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs = (List<Map<String, Object>>) prometheus.get("scrape_configs");

        Map<String, Object> appJob = null;
        for (Map<String, Object> job : configs) {
            if ("opsbrain".equals(job.get("job_name"))) {
                appJob = job;
            }
        }
        assertTrue(appJob != null, "prometheus.yml 里必须保留 job_name=opsbrain（应用自身指标的唯一入口）");

        Object authorization = appJob.get("authorization");
        assertTrue(authorization instanceof Map,
                "opsbrain job 必须配 authorization——WebConfig 对 /actuator/** 做了登录校验，"
                        + "不带凭据的抓取恒 401：面板全空 + up{job=opsbrain}==0 让宕机规则常亮误报");
        @SuppressWarnings("unchecked")
        Map<String, Object> auth = (Map<String, Object>) authorization;
        assertTrue(auth.get("credentials_file") != null,
                "凭据必须来自文件（credentials_file）而不是内联明文：与应用侧 SCRAPE_TOKEN_FILE 指向同一份，"
                        + "轮换只改文件、两侧都不重启");

        // 依赖类规则的供给：三条 exporter job 必须存在（否则规则永不触发）
        Set<String> jobs = new LinkedHashSet<>();
        configs.forEach(j -> jobs.add(String.valueOf(j.get("job_name"))));
        for (String required : List.of("postgres", "redis", "minio")) {
            assertTrue(jobs.contains(required),
                    "缺少 job=" + required + "：对应的依赖告警规则（pg_up/redis_up/minio_*）"
                            + "将永远没有数据——不误报，也永远不响。当前 job：" + jobs);
        }
    }

    @Test
    @DisplayName("令牌文件不得入库：.gitignore 必须忽略它的目录（秘密只在本机/卷里）")
    void tokenFileIsGitIgnored() throws Exception {
        String ignore = Files.readString(root.resolve(".gitignore"), StandardCharsets.UTF_8);
        assertTrue(ignore.contains("monitoring/secrets"),
                "monitoring/secrets/ 必须在 .gitignore 里——抓取令牌是凭据，"
                        + "一旦入库就只能靠历史重写来收拾");

        String prometheus = Files.readString(root.resolve("monitoring/prometheus.yml"), StandardCharsets.UTF_8);
        assertTrue(prometheus.contains("/etc/prometheus/secrets/scrape_token"),
                "prometheus.yml 的 credentials_file 应指向 compose 挂载点；"
                        + "若改成别的路径，compose 的卷挂载要同步（否则抓取恒 401）");
    }
}
