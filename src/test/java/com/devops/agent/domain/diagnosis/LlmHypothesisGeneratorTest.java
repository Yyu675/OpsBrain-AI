package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.diagnosis.HypothesisGenerator.RankedEvidence;
import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;
import com.devops.agent.infrastructure.AiModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** LlmHypothesisGenerator 的四个核心行为：真实 JSON 断言、MOCK 落空回落规则版、解析失败回落、ChatModel null 直接回落。 */
@DisplayName("LlmHypothesisGenerator（S2-2 批次 C：LLM 合成版）")
class LlmHypothesisGeneratorTest {

    private RuleBasedHypothesisGenerator ruleFallback;
    private LlmHypothesisGenerator llmGenerator;

    private static EvidenceAggregator.AggregateResult aggSufficient() {
        return new EvidenceAggregator.AggregateResult(
                EvidenceAggregator.Sufficiency.SUFFICIENT,
                new LinkedHashMap<>(Map.of("metrics", 1, "changes", 1, "logs", 1)),
                Map.of(), Map.of(), 0, List.of(), List.of(), "ok");
    }

    private static RankedEvidence mkEv(long id, Evidence.EvidenceStatus st, String type,
                                       Map<String, Object> content, Double rel) {
        return new RankedEvidence(id, new Evidence(
                st, type, "t", content, "ref", rel, Instant.now()));
    }

    @BeforeEach
    void setUp() {
        ruleFallback = mock(RuleBasedHypothesisGenerator.class);
        // 规则基线的 stub：每次调用都返回一个来自「规则」的假设（MK 应该是「不是 LLM」）
        when(ruleFallback.generate(any(), any())).thenAnswer(inv -> List.of(
                new Hypothesis(1, "规则基线假设", "rule-based", 0.72,
                        List.of(10L), List.of(), "回滚", Instant.now())));
    }

    @Test
    @DisplayName("LLM 合法 JSON 返回 → 假设包含期望字段，LLM 胜出")
    void validLlmJsonWins() {
        String validJson = "[{\"statement\":\"变更回归\",\"reasoning\":\"rel=0.9\",\"confidence\":0.78,\"evidenceIds\":[10],\"suggestedAction\":\"回滚\"}]";
        ChatModel mockModel = mock(ChatModel.class);
        when(mockModel.chat(any())).thenReturn(ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from(validJson)).build());
        llmGenerator = new LlmHypothesisGenerator(mockModel, ruleFallback);

        RankedEvidence ev = mkEv(10L, Evidence.EvidenceStatus.SUCCESS, "changes",
                Map.of("count", 2), 0.9);
        var out = llmGenerator.generate(aggSufficient(), List.of(ev));
        assertTrue(out.size() >= 1);
        assertTrue(out.get(0).statement().contains("变更"), out.get(0).statement());
        assertTrue(out.get(0).confidence() > 0.5);
        assertTrue(out.get(0).evidenceIds().contains(10L));
        verify(ruleFallback, never()).generate(any(), any());
    }

    @Test
    @DisplayName("LLM 返回非 JSON（如测试 MOCK 响应）→ 自动回落规则基线，不落神经病")
    void invalidLlmFallsBackToRule() {
        ChatModel mockModel = mock(ChatModel.class);
        when(mockModel.chat(any())).thenReturn(ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from("这是模拟的 AI 回复，不是 JSON")).build());
        llmGenerator = new LlmHypothesisGenerator(mockModel, ruleFallback);

        var out = llmGenerator.generate(aggSufficient(), List.of());
        assertEquals(1, out.size());
        assertTrue(out.get(0).statement().contains("规则"), "解析失败必须回落规则版自动生成：" + out.get(0).statement());
        verify(ruleFallback, times(1)).generate(any(), any());
    }

    @Test
    @DisplayName("保底再一层：ChatModel 为 nul l → 直接回落规则基线（不顶天到 LLM 活着才 Required）")
    void nullChatModelFallsBackDirectly() {
        llmGenerator = new LlmHypothesisGenerator(ruleFallback);
        // turboModel 为 null（默认、MOCK 模式、未注入时自然如此）——立即回落
        var out = llmGenerator.generate(aggSufficient(), List.of());
        assertEquals(1, out.size());
        assertTrue(out.get(0).statement().contains("规则"));
        verify(ruleFallback, times(1)).generate(any(), any());
    }

    @Test
    @DisplayName("LLM 检出但 EMPTY 数组 → 也是回落（LLM 数 0 = 「给 Mall 的机会没赢得」）")
    void emptyLlmOutputFallsBack() {
        ChatModel mockModel = mock(ChatModel.class);
        when(mockModel.chat(any())).thenReturn(ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from("[]")).build());
        llmGenerator = new LlmHypothesisGenerator(mockModel, ruleFallback);

        var out = llmGenerator.generate(aggSufficient(), List.of());
        assertEquals(1, out.size());
        assertTrue(out.get(0).statement().contains("规则"));
        verify(ruleFallback, times(1)).generate(any(), any());
    }
}
