package com.devops.agent.domain.audit;

import java.util.List;

/** Typed filter option collections sourced from audit data. */
public record AuditFilterOptionsData(
        List<String> models,
        List<String> operationTypes,
        List<String> actions,
        List<String> targetTypes) {
}
