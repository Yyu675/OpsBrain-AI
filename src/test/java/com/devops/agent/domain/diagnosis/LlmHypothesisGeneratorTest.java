package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.diagnosis.HypothesisGenerator.RankedEvidence;
import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmHypothesisGenerator 四个核心行为：合法 JSON 时 LLM 胜出、
 * 非 JSON 回落规则版、ChatModel null 直接回落、空数组回落。
 * <p>
 * 注意：生产类通过 {@code @Autowired(required=false)} 注入 ChatModel，
 * 测试不走 Spring 容器，用 {@link ReflectionTestUtils#setField} 手工注入。
 * </p>
 */
@DisplayName("LlmHypothesisGenerator（S2-2 批次 C：LLM 一轨）")
class LlmHypothesisGeneratorTest {

    private RuleBasedHypothesisGenerator ruleFallback;

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

    private static ChatModel stubModelReturning(String text) {
        ChatModel mockModel = mock(ChatModel.class);
        // 生产类走 ChatRequest 变体（langchain4j 1.2.0 kestra 重构后
        // ChatResponse 移到 model.chat.response 包）——stub 必须精确对齐，
        // 否则 any() 匹配到旧 String 重载导致生产调用返回 null
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(text)).build());
        return mockModel;
    }

    private static LlmHypothesisGenerator withModel(RuleBasedHypothesisGenerator fallback,
                                                    ChatModel model) {
        LlmHypothesisGenerator generator = new LlmHypothesisGenerator(fallback);
        ReflectionTestUtils.setField(generator, "turboModel", model);
        return generator;
    }

    @BeforeEach
    void setUp() {
        ruleFallback = mock(RuleBasedHypothesisGenerator.class);
        // 规则基线 stub：每次调用返回一条标志性假设（statement 含「规则」二字用于区分来源）
        when(ruleFallback.generate(any(), any())).thenAnswer(inv -> List.of(
                new Hypothesis(1, "规则基线假设", "rule-based", 0.72,
                        List.of(10L), List.of(), "回滚", Instant.now())));
    }

    @Test
    @DisplayName("LLM 返回合法 JSON → 假设解析成功，LLM 胜出，规则基线不被调用")
    void validLlmJsonWins() {
        String validJson = "[{\"statement\":\"变更回归\",\"reasoning\":\"rel=0.9\",\"confidence\":0.78,"
                + "\"evidenceIds\":[10],\"suggestedAction\":\"回滚\"}]";
        LlmHypothesisGenerator generator = withModel(ruleFallback, stubModelReturning(validJson));

        RankedEvidence ev = mkEv(10L, Evidence.EvidenceStatus.SUCCESS, "changes",
                Map.of("count", 2), 0.9);
        var out = generator.generate(aggSufficient(), List.of(ev));

        assertTrue(out.size() >= 1);
        assertTrue(out.get(0).statement().contains("变更"), out.get(0).statement());
        assertTrue(out.get(0).confidence() > 0.5);
        assertTrue(out.get(0).evidenceIds().contains(10L));
        verify(ruleFallback, never()).generate(any(), any());
    }

    @Test
    @DisplayName("LLM 返回非 JSON（如 MOCK 的中文闲聊）→ 解析失败，回落规则基线")
    void invalidLlmFallsBackToRule() {
        LlmHypothesisGenerator generator = withModel(ruleFallback,
                stubModelReturning("这是模拟的 AI 回复，不是 JSON"));

        var out = generator.generate(aggSufficient(), List.of());

        assertEquals(1, out.size());
        assertTrue(out.get(0).statement().contains("规则"),
                "解析失败必须回落规则版：" + out.get(0).statement());
        verify(ruleFallback, times(1)).generate(any(), any());
    }

    @Test
    @DisplayName("ChatModel 为 null（MOCK/dev 未注入）→ 直接回落规则基线，不触碰 LLM")
    void nullChatModelFallsBackDirectly() {
        LlmHypothesisGenerator generator = new LlmHypothesisGenerator(ruleFallback);

        var out = generator.generate(aggSufficient(), List.of());

        assertEquals(1, out.size());
        assertTrue(out.get(0).statement().contains("规则"));
        verify(ruleFallback, times(1)).generate(any(), any());
    }

    @Test
    @DisplayName("LLM 返回空数组 → 视同未给出结果，回落规则基线")
    void emptyLlmOutputFallsBack() {
        LlmHypothesisGenerator generator = withModel(ruleFallback, stubModelReturning("[]"));

        var out = generator.generate(aggSufficient(), List.of());

        assertEquals(1, out.size());
        assertTrue(out.get(0).statement().contains("规则"));
        verify(ruleFallback, times(1)).generate(any(), any());
    }
}
