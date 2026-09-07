package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.evidence.EvidenceAggregator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 驱动的根因假设生成器（S2-2 批次 C，HypothesisGenerator 双轨的 LLM 一轨）。
 * <p>
 * 与规则基线（{@link RuleBasedHypothesisGenerator}）的关系：
 * <ul>
 *   <li><b>规则基线永远可用</b>：{@code RuleBasedHypothesisGenerator} 在任何
 *       时候都是 Spring Bean，LLM 失败时直接回落——接口不变，编排器零感知；</li>
 *   <li>{@code devops.ai.mode=REAL} 时 {@code ChatModel} 由 AiModelConfig 注入，
 *       LLM 驱动；MOCK/dev 下 {@code ChatModel} 为 null，
 *       {@link #generate} 立即回落规则版——LLM 停机不会让诊断产不出假设；</li>
 *   <li><b>三类回落触发点</b>：ChatModel 为 null / 调用或解析抛异常 /
 *       解析结果为空数组——任一触发即回落，绝不向外抛错。</li>
 * </ul>
 * </p>
 */
@Component
@Primary
public class LlmHypothesisGenerator implements HypothesisGenerator {

    /** MOCK/dev 下为 null（AiModelConfig 仅在 REAL 模式注册）；不强制注入。 */
    @Autowired(required = false)
    private ChatModel turboModel;

    private final RuleBasedHypothesisGenerator ruleBasedFallback;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 系统提示词：假设输出契约（JSON 数组，最多 3 条）。 */
    private static final String SYSTEM_PROMPT = """
            你是企业级运维诊断助手。
            根据给定的证据列表（含状态、标题、来源、内容摘要），推理最可能的根因，
            输出最多 3 条假设。

            每条假设必须包含字段：
            - statement：一句话根因陈述
            - reasoning：推理链（说明引用了哪些证据 id、如何推出）
            - confidence：自评置信度（0~1；证据不足时不超过 0.6）
            - evidenceIds：支撑证据 id 数组
            - contradictIds：冲突证据 id 数组（可为空）
            - suggestedAction：建议的下一步动作

            只输出 JSON 数组（按置信度从高到低排序），不要输出任何其他文字。""";

    public LlmHypothesisGenerator(RuleBasedHypothesisGenerator ruleBasedFallback) {
        this.ruleBasedFallback = ruleBasedFallback;
    }

    @Override
    public List<Hypothesis> generate(EvidenceAggregator.AggregateResult aggregated,
                                     List<RankedEvidence> evidenceWithIds) {
        // 回落触发点 1：ChatModel 未注入（MOCK/dev）——立即走规则基线
        if (turboModel == null) {
            return ruleBasedFallback.generate(aggregated, evidenceWithIds);
        }

        try {
            String evidenceJson = buildEvidenceJsonForPrompt(evidenceWithIds);
            String userPrompt = String.format(
                    "证据列表：%n%s%n%n聚合判定：充分性=%s；冲突数=%d；证据总数=%d。%n请输出根因假设 JSON 数组。",
                    evidenceJson, aggregated.sufficiency(),
                    aggregated.conflicts().size(), evidenceWithIds.size());

            var chatResponse = turboModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(SYSTEM_PROMPT),
                            UserMessage.from(userPrompt)))
                    .build());
            String rawText = chatResponse.aiMessage().text();

            List<Hypothesis> llmGenerated = parseAndValidate(rawText);

            // 回落触发点 2：解析失败或空结果——规则基线接手
            if (llmGenerated == null || llmGenerated.isEmpty()) {
                return ruleBasedFallback.generate(aggregated, evidenceWithIds);
            }
            return llmGenerated;
        } catch (Exception ex) {
            // 回落触发点 3：任何调用异常——静默回落，不污染上层
            return ruleBasedFallback.generate(aggregated, evidenceWithIds);
        }
    }

    /** 证据序列化为提示词内嵌 JSON；单条 content 截断到 2000 字符防爆 token。 */
    private String buildEvidenceJsonForPrompt(List<RankedEvidence> evidenceWithIds) {
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (RankedEvidence re : evidenceWithIds) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", re.id());
                item.put("type", re.evidence().evidenceType());
                item.put("status", re.evidence().status().name());
                item.put("title", re.evidence().title());
                item.put("sourceRef", re.evidence().sourceRef());
                String content = objectMapper.writeValueAsString(re.evidence().content());
                if (content != null && content.length() > 2000) {
                    content = content.substring(0, 2000) + "...(truncated)";
                }
                item.put("content", content);
                items.add(item);
            }
            return objectMapper.writeValueAsString(items);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    /**
     * 解析 + 校验 LLM 输出的 JSON 数组（容忍 ```json 围栏包裹）。
     *
     * @return 合法假设列表（最多 3 条）；解析失败返回 null（由调用方回落）
     */
    private List<Hypothesis> parseAndValidate(String rawText) {
        try {
            String json = stripCodeFence(rawText);
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return null;
            }
            List<Hypothesis> candidates = new ArrayList<>();
            for (int i = 0; i < root.size() && candidates.size() < 3; i++) {
                JsonNode node = root.get(i);
                String statement = node.path("statement").asText("");
                if (statement.isBlank()) {
                    continue; // 没有陈述的条目无意义，跳过
                }
                String reasoning = node.path("reasoning").asText("");
                double confidence = node.path("confidence").asDouble(0.5);
                String suggestedAction = node.path("suggestedAction").asText("");

                List<Long> evidenceIds = new ArrayList<>();
                for (JsonNode idNode : node.path("evidenceIds")) {
                    evidenceIds.add(idNode.asLong());
                }
                List<Long> contradictIds = new ArrayList<>();
                for (JsonNode idNode : node.path("contradictIds")) {
                    contradictIds.add(idNode.asLong());
                }

                candidates.add(new Hypothesis(
                        candidates.size() + 1, statement, reasoning, confidence,
                        evidenceIds, contradictIds, suggestedAction, Instant.now()));
            }
            return candidates;
        } catch (Exception ex) {
            return null; // 解析失败：当作「模型没给出结果」，交由规则基线
        }
    }

    /** 去掉 LLM 常见的 ```json ... ``` 围栏，提取其中的纯 JSON 文本。 */
    private static String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
