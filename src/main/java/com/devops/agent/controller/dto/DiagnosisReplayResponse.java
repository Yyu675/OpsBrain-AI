package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "诊断链回放详情响应")
public record DiagnosisReplayResponse(
        @Schema(description = "诊断链路追踪 ID")
        String traceId,

        @Schema(description = "诊断会话详情")
        DiagnosisReplaySessionView session,

        @Schema(description = "证据采集项列表")
        List<DiagnosisEvidenceItem> evidences,

        @Schema(description = "假设推导项列表")
        List<DiagnosisHypothesisItem> hypotheses,

        @Schema(description = "AI 执行模式")
        String aiMode
) {
}
