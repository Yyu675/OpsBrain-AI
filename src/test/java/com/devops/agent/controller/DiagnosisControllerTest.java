package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.biz.repository.DiagnosisEvidenceRepository;
import com.devops.agent.domain.biz.repository.DiagnosisHypothesisRepository;
import com.devops.agent.domain.biz.repository.DiagnosisSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 诊断详情 API（S2-3）：零 Spring 装配，纯契约面验证。 */
@DisplayName("DiagnosisController（诊断回放 API）")
class DiagnosisControllerTest {

    private DiagnosisSessionRepository sessionRepository;
    private DiagnosisEvidenceRepository evidenceRepository;
    private DiagnosisHypothesisRepository hypothesisRepository;
    private com.devops.agent.domain.biz.repository.KnowledgeBoostRepository knowledgeBoostRepository;
    private DiagnosisController controller;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(DiagnosisSessionRepository.class);
        evidenceRepository = mock(DiagnosisEvidenceRepository.class);
        hypothesisRepository = mock(DiagnosisHypothesisRepository.class);
        knowledgeBoostRepository = mock(com.devops.agent.domain.biz.repository.KnowledgeBoostRepository.class);
        controller = new DiagnosisController(sessionRepository, evidenceRepository,
                hypothesisRepository, knowledgeBoostRepository);
    }

    @Test
    @DisplayName("完整链路：会话+证据+假设按 traceId 汇总出同一份回放视图")
    void fullReplayView() {
        when(sessionRepository.findByTraceId("t-1")).thenReturn(Map.of(
                "trace_id", "t-1", "status", "COMPLETED", "sufficiency", "SUFFICIENT"));
        when(evidenceRepository.findByTraceId("t-1")).thenReturn(List.of(
                Map.of("id", 1L, "evidence_type", "metrics"),
                Map.of("id", 2L, "evidence_type", "logs")));
        when(hypothesisRepository.findBySessionTraceId("t-1")).thenReturn(List.of(
                Map.of("rank", 1, "statement", "变更回归")));

        ApiResponse<Map<String, Object>> resp = controller.detail("t-1");
        Map<String, Object> body = resp.getData();
        assertThat(body.get("traceId")).isEqualTo("t-1");
        assertThat(((Map<?, ?>) body.get("session")).get("sufficiency")).isEqualTo("SUFFICIENT");
        assertThat(((List<?>) body.get("evidences"))).hasSize(2);
        assertThat(((List<?>) body.get("hypotheses"))).hasSize(1);
    }

    @Test
    @DisplayName("会话不存在时不抛异常——空对象 + 孤证仍可见（不掩盖已落数据）")
    void orphanEvidenceStillVisible() {
        when(sessionRepository.findByTraceId("t-x")).thenReturn(Map.of());
        when(evidenceRepository.findByTraceId("t-x")).thenReturn(List.of(
                Map.of("id", 9L, "evidence_type", "metrics")));
        when(hypothesisRepository.findBySessionTraceId("t-x")).thenReturn(List.of());
        ApiResponse<Map<String, Object>> resp = controller.detail("t-x");
        Map<String, Object> body = resp.getData();
        assertThat(((Map<?, ?>) body.get("session"))).isEmpty();
        assertThat(((List<?>) body.get("evidences"))).hasSize(1);
    }

    @Test
    @DisplayName("传入告警去重键（工单 source_trace_id）时桥接到该告警的诊断会话")
    void dedupKeyResolvesToDiagnosisTrace() {
        String dedupKey = "3600dfab9a6c";
        when(sessionRepository.findByTraceId(dedupKey)).thenReturn(Map.of());
        when(sessionRepository.findTraceIdByAlertDedupKey(dedupKey)).thenReturn("diag-trace-1");
        when(sessionRepository.findByTraceId("diag-trace-1")).thenReturn(Map.of(
                "trace_id", "diag-trace-1", "sufficiency", "WEAK"));
        when(evidenceRepository.findByTraceId("diag-trace-1")).thenReturn(List.of(
                Map.of("id", 3L, "evidence_type", "knowledge")));
        when(hypothesisRepository.findBySessionTraceId("diag-trace-1")).thenReturn(List.of(
                Map.of("rank", 1, "statement", "连接池耗尽")));

        ApiResponse<Map<String, Object>> resp = controller.detail(dedupKey);
        Map<String, Object> body = resp.getData();

        // 回放用的是诊断自己的 traceId，而不是工单带来的去重键
        assertThat(body.get("traceId")).isEqualTo("diag-trace-1");
        assertThat(((Map<?, ?>) body.get("session")).get("sufficiency")).isEqualTo("WEAK");
        assertThat(((List<?>) body.get("evidences"))).hasSize(1);
        assertThat(((List<?>) body.get("hypotheses"))).hasSize(1);
    }

    @Test
    @DisplayName("2-3.5 反馈入口：合法判定回落假设 + 按 chunkIds 入库知识 boost")
    void feedbackEntryMarksAndBoosts() {
        when(hypothesisRepository.updateFeedback(42L, "HELPFUL")).thenReturn(1);
        var req = new DiagnosisController.FeedbackRequest(
                42L, "helpful", java.util.List.of(7L, 8L));
        ApiResponse<Map<String, Object>> resp = controller.feedback(req);
        // ApiResponse 语义：code 0 = 成功，非 0 = 业务错误码（见 common/dto/ApiResponse）
        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().get("feedback")).isEqualTo("HELPFUL");
        assertThat(resp.getData().get("knowledgeBoosted")).isEqualTo(2);
        verify(knowledgeBoostRepository, times(2)).recordFeedback(anyLong(), eq("HELPFUL"));
    }

    @Test
    @DisplayName("2-3.5 反馈入口：hypothesis 不存在 → 404；非法判定 → 400")
    void feedbackEntryValidation() {
        when(hypothesisRepository.updateFeedback(99L, "HELPFUL")).thenReturn(0);
        ApiResponse<Map<String, Object>> notFound = controller.feedback(
                new DiagnosisController.FeedbackRequest(99L, "helpful", null));
        assertThat(notFound.getCode()).isEqualTo(404);
        ApiResponse<Map<String, Object>> bad = controller.feedback(
                new DiagnosisController.FeedbackRequest(1L, "helpful-ish", null));
        assertThat(bad.getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("2-3.5 反馈回流：chunkIds 缺失才走证据反查兜底，空数组是「本次无引用」不再兜底")
    void missingChunkIdsFallsBackButEmptyListDoesNot() {
        when(hypothesisRepository.updateFeedback(42L, "WRONG")).thenReturn(1);
        when(hypothesisRepository.findEvidenceIdsById(42L)).thenReturn(List.of(5L));
        when(evidenceRepository.findKnowledgeSourceRefs(List.of(5L)))
                .thenReturn(List.of("【来源：手册.md - 章节】"));
        when(knowledgeBoostRepository.resolveChunkIds(any())).thenReturn(java.util.Set.of(11L));

        // 字段缺失（旧客户端/未升级前端）：反查兜底，回流照做
        ApiResponse<Map<String, Object>> fallback = controller.feedback(
                new DiagnosisController.FeedbackRequest(42L, "wrong", null));
        assertThat(fallback.getCode()).isZero();
        assertThat(fallback.getData().get("knowledgeBoosted")).isEqualTo(1);
        verify(knowledgeBoostRepository).recordFeedback(11L, "WRONG");

        // 空数组（前端明确说本次没有可引用切片）：不反查、不回流
        ApiResponse<Map<String, Object>> explicit = controller.feedback(
                new DiagnosisController.FeedbackRequest(42L, "wrong", List.of()));
        assertThat(explicit.getData().get("knowledgeBoosted")).isEqualTo(0);
        verify(knowledgeBoostRepository, times(1)).recordFeedback(anyLong(), anyString());
    }

}
