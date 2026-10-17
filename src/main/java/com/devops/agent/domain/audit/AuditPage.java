package com.devops.agent.domain.audit;

import java.util.List;

/** Typed pagination envelope for fixed audit query results. */
public record AuditPage<T>(List<T> items, int total, int page, int size, int totalPages) {
}
