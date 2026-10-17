package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.biz.repository.DiagnosisEvidenceRepository;
import com.devops.agent.domain.biz.repository.DiagnosisHypothesisRepository;
import com.devops.agent.domain.biz.repository.DiagnosisSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
    @DisplayName("类级路径必须带 /api/v1——平台统一前缀，前端 config/api.ts 按此取值")
    void mappingPathIsVersioned() {
        var rm = DiagnosisController.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class);
        assertThat(rm).isNotNull();
        // 2026-10-01 回归钉子：此处曾为 "/api/diagnosis"（缺 v1），回放/反馈/boost
        // 回流对前端全部 404，而本类纯直调不看 URL 没拦住——路径契约必须有测试。
        assertThat(rm.value()).containsExactly("/api/v1/diagnosis");
    }

    @Test
    @DisplayName("完整链路：会话+证据+假设按 traceId 汇总出同一份回放视图")
    void fullReplayView() throws Exception {
        when(sessionRepository.findByTraceId("t-1")).thenReturn(Optional.of(
                new DiagnosisSessionRepository.SessionRow(5L, "t-1", 17L, null, "payments",
                        "COMPLETED", "SUFFICIENT", null, null, null)));
        when(evidenceRepository.findByTraceId("t-1")).thenReturn(List.of(
                new DiagnosisEvidenceRepository.EvidenceRow(1L, "t-1", "engine", "metrics",
                        "SUCCESS", "CPU", "{}", "promql", 0.9, null),
                new DiagnosisEvidenceRepository.EvidenceRow(2L, "t-1", "engine", "logs",
                        "SUCCESS", "Errors", "{}", "logql", 0.8, null)));
        when(hypothesisRepository.findBySessionTraceId("t-1")).thenReturn(List.of(
                new DiagnosisHypothesisRepository.HypothesisRow(3L, "t-1", 1, "变更回归",
                        "最近发布后发生", 0.95, "[1]", "[]", "回滚", null)));

        var resp = controller.detail("t-1");
        var body = resp.getData();
        assertThat(body.traceId()).isEqualTo("t-1");
        assertThat(body.session()).isInstanceOf(com.devops.agent.controller.dto.DiagnosisReplaySession.class);
        assertThat(((com.devops.agent.controller.dto.DiagnosisReplaySession) body.session()).sufficiency())
                .isEqualTo("SUFFICIENT");
        assertThat(body.evidences()).hasSize(2);
        assertThat(body.hypotheses()).hasSize(1);
        assertThat(body.evidences().get(0).evidenceType()).isEqualTo("metrics");
        assertThat(body.hypotheses().get(0).statement()).isEqualTo("变更回归");
        String json = new ObjectMapper().writeValueAsString(body);
        assertThat(json).contains("\"trace_id\"", "\"evidence_type\"", "\"session_trace_id\"")
                .doesNotContain("\"traceId\"", "\"evidenceType\"", "\"sessionTraceId\"");
    }

    @Test
    @DisplayName("会话不存在时不抛异常——空对象 + 孤证仍可见（不掩盖已落数据）")
    void orphanEvidenceStillVisible() throws Exception {
        when(sessionRepository.findByTraceId("t-x")).thenReturn(Optional.empty());
        when(evidenceRepository.findByTraceId("t-x")).thenReturn(List.of(
                new DiagnosisEvidenceRepository.EvidenceRow(9L, "t-x", "engine", "metrics",
                        "SUCCESS", "CPU", "{}", "promql", 0.9, null)));
        when(hypothesisRepository.findBySessionTraceId("t-x")).thenReturn(List.of());
        var resp = controller.detail("t-x");
        var body = resp.getData();
        assertThat(body.session()).isInstanceOf(com.devops.agent.controller.dto.EmptyDiagnosisSession.class);
        assertThat(new ObjectMapper().writeValueAsString(body.session())).isEqualTo("{}");
        assertThat(body.evidences()).hasSize(1);
    }

    @Test
    @DisplayName("传入告警去重键（工单 source_trace_id）时桥接到该告警的诊断会话")
    void dedupKeyResolvesToDiagnosisTrace() {
        String dedupKey = "3600dfab9a6c";
        when(sessionRepository.findByTraceId(dedupKey)).thenReturn(Optional.empty());
        when(sessionRepository.findTraceIdByAlertDedupKey(dedupKey)).thenReturn("diag-trace-1");
        when(sessionRepository.findByTraceId("diag-trace-1")).thenReturn(Optional.of(
                new DiagnosisSessionRepository.SessionRow(6L, "diag-trace-1", 8L, null, "payments",
                        "COMPLETED", "WEAK", null, null, null)));
        when(evidenceRepository.findByTraceId("diag-trace-1")).thenReturn(List.of(
                new DiagnosisEvidenceRepository.EvidenceRow(3L, "diag-trace-1", "engine", "knowledge",
                        "SUCCESS", "Docs", "{}", "kb:7", 0.7, null)));
        when(hypothesisRepository.findBySessionTraceId("diag-trace-1")).thenReturn(List.of(
                new DiagnosisHypothesisRepository.HypothesisRow(9L, "diag-trace-1", 1, "连接池耗尽",
                        "连接耗尽", 0.8, "[3]", "[]", "扩容", null)));

        var resp = controller.detail(dedupKey);
        var body = resp.getData();

        // 回放用的是诊断自己的 traceId，而不是工单带来的去重键
        assertThat(body.traceId()).isEqualTo("diag-trace-1");
        assertThat(((com.devops.agent.controller.dto.DiagnosisReplaySession) body.session()).sufficiency())
                .isEqualTo("WEAK");
        assertThat(body.evidences()).hasSize(1);
        assertThat(body.hypotheses()).hasSize(1);
    }

    @Test
    @DisplayName("2-3.5 反馈入口：合法判定回落假设 + 按 chunkIds 入库知识 boost")
    void feedbackEntryMarksAndBoosts() {
        when(hypothesisRepository.updateFeedback(42L, "HELPFUL")).thenReturn(1);
        var req = new DiagnosisController.FeedbackRequest(
                42L, "helpful", java.util.List.of(7L, 8L));
        var resp = controller.feedback(req);
        // ApiResponse 语义：code 0 = 成功，非 0 = 业务错误码（见 common/dto/ApiResponse）
        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().feedback()).isEqualTo("HELPFUL");
        assertThat(resp.getData().knowledgeBoosted()).isEqualTo(2);
        verify(knowledgeBoostRepository, times(2)).recordFeedback(anyLong(), eq("HELPFUL"));
    }

    @Test
    @DisplayName("2-3.5 反馈入口：hypothesis 不存在 → 404；非法判定 → 400")
    void feedbackEntryValidation() {
        when(hypothesisRepository.updateFeedback(99L, "HELPFUL")).thenReturn(0);
        var notFound = controller.feedback(
                new DiagnosisController.FeedbackRequest(99L, "helpful", null));
        assertThat(notFound.getCode()).isEqualTo(404);
        var bad = controller.feedback(
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
        var fallback = controller.feedback(
                new DiagnosisController.FeedbackRequest(42L, "wrong", null));
        assertThat(fallback.getCode()).isZero();
        assertThat(fallback.getData().knowledgeBoosted()).isEqualTo(1);
        verify(knowledgeBoostRepository).recordFeedback(11L, "WRONG");

        // 空数组（前端明确说本次没有可引用切片）：不反查、不回流
        var explicit = controller.feedback(
                new DiagnosisController.FeedbackRequest(42L, "wrong", List.of()));
        assertThat(explicit.getData().knowledgeBoosted()).isEqualTo(0);
        verify(knowledgeBoostRepository, times(1)).recordFeedback(anyLong(), anyString());
    }

}
