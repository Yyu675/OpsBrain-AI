package com.devops.agent.domain.healing;

/** Fixed, persisted step-timeline item shared by automatic and manual healing flows. */
public record HealingStep(String name, String status, String detail, String at) {
}
