package com.devops.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测指标持久化（路线图 §8.3 4-3.1）：各评测层把数值落成
 * {@code target/eval-metrics.json}，供 eval.compare 做基线对比。
 *
 * <h3>为什么是「分层合并」而不是整文件重写</h3>
 * 契约层常驻、RAG 层 env 门控、LLM 层手动 job——各层不一定同一次运行出现。
 * 合并语义让「这次只跑了契约层」不会把上次 RAG 层的数值抹成缺席；
 * 各层键自带来源，eval.compare 按层对层比，不会因缺层误报劣化。
 *
 * <h3>失败基调</h3>
 * 落盘失败只 WARN——红线断言在主流程里，指标文件是回归素材，
 * 不能反过来成为评测不通过的原因（与写入报告的既有基调一致）。
 */
final class EvalMetricsWriter {

    private static final Path FILE = Path.of("target", "eval-metrics.json");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private EvalMetricsWriter() {
    }

    /** 合并写入一层指标（同层覆盖、异层并留）。调用方给出原始数值，本类只管造型与落盘。 */
    static synchronized void mergeLayer(String layer, int datasetSize, Map<String, Object> metrics) {
        try {
            Map<String, Object> root = Files.exists(FILE)
                    ? MAPPER.readValue(FILE.toFile(), new TypeReference<Map<String, Object>>() { })
                    : new LinkedHashMap<>();
            root.put("generatedAt", Instant.now().toString());
            root.put("dataset", "eval_dataset.json");
            root.put("datasetSize", datasetSize);

            @SuppressWarnings("unchecked")
            Map<String, Object> layers = (Map<String, Object>) root
                    .computeIfAbsent("layers", k -> new LinkedHashMap<String, Object>());
            layers.put(layer, new LinkedHashMap<>(metrics));

            Files.createDirectories(FILE.getParent());
            MAPPER.writeValue(FILE.toFile(), root);
        } catch (Exception e) {
            LoggerFactory.getLogger(EvalMetricsWriter.class)
                    .warn("⚠️ eval-metrics.json 写入失败（不影响评测结论）: {}", e.getMessage());
        }
    }
}
