package com.devops.agent.domain.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AlertKindClassifier} 分类逻辑单测（三级兜底：显式 label → 关键词推断 → 技术性兜底）。
 */
class AlertKindClassifierTest {

    @Test
    @DisplayName("显式 alert_kind label 优先于关键词推断")
    void explicitLabelWins() {
        AlertKind kind = AlertKindClassifier.classify(
                "SomeAlert", Map.of("alert_kind", "business"), "cpu 飙升");
        assertEquals(AlertKind.BUSINESS, kind);
    }

    @Test
    @DisplayName("关键词推断：工单积压 → 业务性")
    void backlogKeywordInfersBusiness() {
        AlertKind kind = AlertKindClassifier.classify(
                "OpsBrainUrgentPendingHigh", Map.of(), "P0/P1 高优工单积压（>10 张）");
        assertEquals(AlertKind.BUSINESS, kind);
    }

    @Test
    @DisplayName("关键词推断：SQL 注入攻击 → 安全型")
    void injectionKeywordInfersSecurity() {
        AlertKind kind = AlertKindClassifier.classify(
                "SqlInjectionDetected", Map.of(), "检测到恶意 SQL 注入攻击");
        assertEquals(AlertKind.SECURITY, kind);
    }

    @Test
    @DisplayName("关键词推断：磁盘将满 → 因果型")
    void diskFullKeywordInfersCausal() {
        AlertKind kind = AlertKindClassifier.classify(
                "DiskSpaceLow", Map.of(), "磁盘空间将满，预计 2 天后耗尽");
        assertEquals(AlertKind.CAUSAL, kind);
    }

    @Test
    @DisplayName("无特征词 → 兜底技术性（现状行为，向后兼容）")
    void noKeywordFallsBackToTechnical() {
        AlertKind kind = AlertKindClassifier.classify(
                "PodCrashLoopBackOff", Map.of(), "Pod 反复重启");
        assertEquals(AlertKind.TECHNICAL, kind);
    }

    @Test
    @DisplayName("null labels/描述不抛异常（边界兜底）")
    void nullSafe() {
        AlertKind kind = AlertKindClassifier.classify("SomeAlert", null, null);
        assertEquals(AlertKind.TECHNICAL, kind);
    }

    @Test
    @DisplayName("显式 label 值不识别时继续走关键词推断")
    void unrecognizedExplicitLabelFallsToKeyword() {
        AlertKind kind = AlertKindClassifier.classify(
                "Alert", Map.of("alert_kind", "unknown-value"), "sla 违约预警");
        assertEquals(AlertKind.BUSINESS, kind);
    }

    @Test
    @DisplayName("真实告警 OpsBrainUrgentPendingHigh + 积压描述 → 业务性")
    void realUrgentPendingAlertInfersBusiness() {
        AlertKind kind = AlertKindClassifier.classify(
                "OpsBrainUrgentPendingHigh", Map.of(),
                "与看板 KPI 同口径——先按 module/priority 分布拆积压构成");
        assertEquals(AlertKind.BUSINESS, kind);
    }
}
