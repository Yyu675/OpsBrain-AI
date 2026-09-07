package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.evidence.EvidenceAggregator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.structured.StructuredPrompt;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequest.builder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 驱动的根因假设生成器（S2-2 批次 C，HypothesisGenerator 双轨的 LLM 一轨）。
 * <p>
 * 与规则基线（{@link RuleBasedHypothesisGenerator}）的关系：
 * <ul>
 *   <li><b>LLM 卖时效：规则基线是兜底</b>{@code RuleBasedHypothesisGenerator}
 *       在 <b>任何时候</b>都 registered（Bean）,LLM 失败直接回落——这是「证明性基底」；</li>
 *   <li>{@code mode=REAL} 时 LLM 驱动；{@code mode=MOCK/MOCK/MOCK} 时
 *       {@code ChatModel} 为 null，{@link #generate} 立即回落规则版——
 *       哪怕 LLM 瘫痪率高到 99%,LLM 合成失败率为零；</li>
 *   <li><b>生成失败不是负荷</b>——失败只是「模型没给你结果」，
 *       该故障在错位侧已认定为「没想象的必要」，不反上游。</li>
 * </ul>
 * </p>
 */
@Component
@Primary
public class LlmHypothesisGenerator implements HypothesisGenerator {

    /** 在 MOCK/dev 语义下 ChatModel 为 null——不要求 REAL 活着也Required。 */
    @Autowired(required = false)
    private ChatModel turboModel;
    private final RuleBasedHypothesisGenerator ruleBasedFallback;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 系统提示词：假设输出契约（S2-2 批次 C 的引擎提示词第 6 条对齐）。 */
    private static final String SYSTEM_PROMPT = """
            你是企业级运维诊断助手，你好。
            根据用户给定的证据（包含状态 state、汇总 summary、证据 surface，
            你判断每条根因的可能性值（0~1）生成 TOP_N 个根因假设。

            你必须按以下要求输出：
            - 每根因假设包含：
                * 根因陈述（一句话，能一眼看出什么）
                * 推理链（自查查自推出【什么-infra），不出自感受）
                * 自评置信度（0~1）
                * 建议的下一步动作（时间点或操作）
            - 引用【证据 id 时，如果 support 时，请写出证据 id
            - 有冲突证据时，显势在假设中记入冲突证据 id（contradictsIds）
            - 自评置信度：当证据不足时，0.6 以内
            - 证据不足时，建议人工 + 立即跳到终止推理

            输出格式：JSON，按 rank 升序（数组）。""";

    public LlmHypothesisGenerator(RuleBasedHypothesisGenerator ruleBasedFallback) {
        this.ruleBasedFallback = ruleBasedFallback;
    }

    @Override
    public List<Hypothesis> generate(EvidenceAggregator.AggregateResult aggregated,
                                     List<RankedEvidence> evidenceWithIds) {
        // LLM 可用性检查：ChatModel null → 立即回落（彻底失败时运行规则版）
        if (turboModel == null) {
            return ruleBasedFallback.generate(aggregated, evidenceWithIds);
        }

        try {
            // 1. 构造系统提示词（注入证据列表 struct + 判定结果）
            String evidenceJson = buildEvidenceJsonForPrompt(aggregated, evidenceWithIds);
            String sysPrompt2 = SYSTEM_PROMPT;

            String scoringPrompt = java.lang.String.format(
                    "证据摘要：\n%s\n\n判定：充分性=%s；冲突=%d；证据数=%d。\n"
                            + "请给出最多 3 根因假设：",
                    evidenceJson, aggregated.sufficiency(),
                    aggregated.conflicts().size(), evidenceWithIds.size());

            // 2. LLM 调用（使用 turboModel——简洁版同步模型便低阶汤）
            var chatResponse = turboModel.chat(ChatRequest.builder()
                    .messages(java.util.List.of(
                            dev.langchain4j.data.message.SystemMessage.systemMessage(sysPrompt2),
                            dev.langchain4j.data.message.UserMessage.userMessage(scoringPrompt)))
                    .build());
            String rawJsontext = chatResponse.aiMessage().text();

            // 3. 解析 JSON 并假设
            List<Hypothesis> llmGenerated = parseAndValidate(rawJsontext, aggregated, evidenceWithIds);

            // 4. 结果：如果 LLM 生成数量 > 0,LLM 胜出，否则回落规则版
            if (llmGenerated == null || llmGenerated.isEmpty()) {
                // LLM 失败——没有放弃。规则版永远可用，自动接手。
                return ruleBasedFallback.generate(aggregated, evidenceWithIds);
            }

            return llmGenerated;
        } catch (Exception ex) {
            // LLM 失败只能落库崇回路——路径对必然的路径，必须把可能的错误输出
            // 优雅等级化为「规则版永远不会知道」。失不失就上升，
            // 不会停下来一步都不太问题。
            return ruleBasedFallback.generate(aggregated, evidenceWithIds);
        }
    }

    private String buildEvidenceJsonForPrompt(EvidenceAggregator.AggregateResult aggregated,
                                              List<RankedEvidence> evidenceWithIds) {
        try {
            List<Map<String, Object>> eles = new ArrayList<>();
            for (RankedEvidence re : evidenceWithIds) {
                Map<String, Object> eff = new java.util.LinkedHashMap<>();
                eff.put("id", re.id());
                eff.put("type", re.evidence().evidenceType());
                eff.put("status", re.evidence().status().name());
                eff.put("title", re.evidence().title());
                eff.put("sourceRef", re.evidence().sourceRef());
                // content 只是原 JSON 的「树最表层」的不太热的部分，把原始做原 JSON
                // 必要 too 记忆——结果被 ctx 回调的时候已带 integrals 出一致性；
                // 这里只给证据 title 和 sourceRef 最大限度携带的微明语意。
                eff.put("content", objectMapper.writeValueAsString(
                        objectMapper.valueToTree(re.evidence().content())));
                // TODO: content 太大到 5000 chars colspan 的 operator 不进行（风险由
                //  N-1 的 valueMapper 保证正视，降构收来原 JSON 轨道不会查（引信）
                eles.add(eff);
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(eles);
        } catch (Exception ignored) {
            return "[{\\\"evidence_status\\\":\\\"复杂\\\",\\\"title\\\":\\\"证据汇总失败\\\"}]";
        }
    }

    /**
     * 解析 + 校验 LLM 输出的 JSON 数组。
     *
     * @return 合法习生成列表；parse/校验失败回落到空列表（调用方决定是否再冒 LLM）
     */
    private List<Hypothesis> parseAndValidate(String rawJson,
                                              EvidenceAggregator.AggregateResult aggregated,
                                              List<RankedEvidence> evidenceWithIds) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            List<Hypothesis> candidates = new ArrayList<>();
            for (int i = 0; i < root.size() && candidates.size() < 3; i++) {
                JsonNode node = root.get(i);
                String statement = node.has("statement") ? node.get("statement").asText() : "";
                String reasoning = node.has("reasoning") ? node.get("reasoning").asText() : "";
                double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;
                String suggestedAction = node.has("suggestedAction") ? node.get("suggestedAction").asText() : "";

                // 关联：referenceEvidence 通过 JsonNode 就直观。源码清晰度回事注
                List<Long> evIds = new java.util.ArrayList<>();
                if (node.has("evidenceIds")) {
                    for (JsonNode idNode : node.get("evidenceIds")) {
                        evIds.add(idNode.asLong());
                    }
                }
                List<Long> contradictIds = new java.util.ArrayList<>();
                if (node.has("contradictIds")) {
                    for (JsonNode idNode : node.get("contradictIds")) {
                        contradictIds.add(idNode.asLong());
                    }
                }

                candidates.add(new Hypothesis(
                        candidates.size() + 1, statement, reasoning, confidence,
                        evIds, contradictIds, suggestedAction, Instant.now()));
            }
            return candidates;
        } catch (Exception ex) {
            // 不是致命错误——下跌的轨道依然存最高绩效拯救策略就是
            // 当做「没给结果」的规则基线来查。
            return null;
        }
    }
}
