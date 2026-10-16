package com.devops.agent.eval;

import com.devops.agent.domain.tools.DevOpsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * S1-1 评测驱动包（EVAL_LLM 专用，避免 dev 启动时每个服务都跑一遍）。
 * <p>
 * 用途：让「Agent 收到 order-service 响应慢→自主调 queryServiceMetrics→
 * 拿到指标数据」这条验收路径纳入既有 LLM 评测联播（报告 102 的 EVAL_LLM 口径）。
 * 数据源（deliver/TOOLS_EVAL.md）是 JSON Lines：
 * 每行一个对象，含 service/range/metrics 字段（缺省用 30m/空）。
 * </p>
 * <p>
 * 结果不落库、不写报告文件——由评测报告聚合器统一收集；
 * 本类只负责按行喂入工具并回传 payload（真实 LLM 编排层的判断由 EVAL_LLM
 * 的 AgentEvaluationTest 承担，这里只验证工具的连续可用性与参数鲁棒性）。
 * </p>
 */
@Component
@Profile("eval-metrics")
public class S1MetricsToolEvalCallback {

    private static final Logger log = LoggerFactory.getLogger(S1MetricsToolEvalCallback.class);
    private static final String DATASET = "deliver/TOOLS_EVAL.md";

    private final DevOpsTools devOpsTools;

    public S1MetricsToolEvalCallback(DevOpsTools devOpsTools) {
        this.devOpsTools = devOpsTools;
    }

    @Order(200) // 在种库/初始化之后
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        List<String[]> cases = loadCases();
        log.info("[S1-1评测] 数据集 {} 共 {} 行", DATASET, cases.size());
        int ok = 0;
        for (String[] c : cases) {
            String payload = devOpsTools.queryServiceMetrics(c[0], c[1], c[2]);
            boolean success = payload != null && !payload.startsWith("参数错误");
            if (success) ok++;
            log.info("[S1-1评测] service={} range={} metrics={} -> {}",
                    c[0], c[1], c[2], success ? "OK" : "REJECTED");
        }
        log.info("[S1-1评测] 汇总: {}/{} 行成功受理", ok, cases.size());
    }

    /** JSON Lines 最小解析（eval 数据格式固定，受控输入，不引 Jackson 增加复杂度）。 */
    static List<String[]> loadCases() {
        List<String[]> rows = new ArrayList<>();
        // 双重查找：优先 classpath（测试内嵌），再回退工作目录相对路径（仓库根 deliver/）
        java.io.InputStream in = null;
        ClassPathResource res = new ClassPathResource(DATASET);
        try {
            if (res.exists()) {
                in = res.getInputStream();
            } else {
                java.io.File f = new java.io.File(DATASET);
                if (f.exists()) in = new java.io.FileInputStream(f);
            }
        } catch (Exception ignore) { /* 由下方 null 分支统一记日志 */ }
        if (in == null) {
            log.warn("[S1-1评测] 数据集不存在: {}", DATASET);
            return rows;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("```") || !line.startsWith("{")) {
                    continue; // 兼容 markdown 围栏/注释行
                }
                rows.add(new String[]{
                        jsonField(line, "service", "order-service"),
                        jsonField(line, "range", "30m"),
                        jsonField(line, "metrics", "")});
            }
        } catch (Exception e) {
            log.error("[S1-1评测] 数据集读取失败: {}", e.getMessage());
        }
        return rows;
    }

    /** 极简 "key":"value" 提取（只支持字符串值、无转义——受控格式足够）。 */
    private static String jsonField(String json, String key, String dflt) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return dflt;
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return dflt;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return dflt;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return dflt;
        return json.substring(q1 + 1, q2);
    }
}
