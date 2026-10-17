package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Polymorphic session JSON view: preserve the legacy empty-object response when no row exists. */
@Schema(oneOf = {DiagnosisReplaySession.class, EmptyDiagnosisSession.class})
public sealed interface DiagnosisReplaySessionView
        permits DiagnosisReplaySession, EmptyDiagnosisSession {
}
