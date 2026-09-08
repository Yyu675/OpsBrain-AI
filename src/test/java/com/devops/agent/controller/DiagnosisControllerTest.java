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

}
