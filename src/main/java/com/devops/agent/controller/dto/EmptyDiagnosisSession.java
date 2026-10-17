package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Empty object used when no session row exists, matching the longstanding API behavior. */
@Schema(name = "EmptyDiagnosisSession", description = "无会话时返回的空对象")
public record EmptyDiagnosisSession() implements DiagnosisReplaySessionView {
}
