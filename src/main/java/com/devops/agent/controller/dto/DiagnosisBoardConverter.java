package com.devops.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 诊断看板强类型映射工具，将 Map 载荷安全转换为结构化 Record。
 */
public final class DiagnosisBoardConverter {

    private DiagnosisBoardConverter() {
    }

    @SuppressWarnings("unchecked")
    public static DiagnosisBoardResponse fromMap(Map<String, Object> map) {
        if (map == null) return null;

        int windowDays = map.get("windowDays") instanceof Number n ? n.intValue() : 7;

        // 1. sessions
        Map<String, Object> sessionsMap = (Map<String, Object>) map.get("sessions");
        DiagnosisSessionsStats sessions = null;
        if (sessionsMap != null) {
            long total = sessionsMap.get("total") instanceof Number n ? n.longValue() : 0L;
            Double avgDurationSeconds = sessionsMap.get("avgDurationSeconds") instanceof Number n ? n.doubleValue() : null;
            Double avgCostRmb = sessionsMap.get("avgCostRmb") instanceof Number n ? n.doubleValue() : null;

            List<Map<String, Object>> byStatusRaw = (List<Map<String, Object>>) sessionsMap.get("byStatus");
            List<DiagnosisStatusCount> byStatus = byStatusRaw == null ? List.of() : byStatusRaw.stream()
                    .map(r -> new DiagnosisStatusCount(
                            String.valueOf(r.get("status")),
                            r.get("count") instanceof Number n ? n.longValue() : 0L))
                    .toList();

            List<Map<String, Object>> suffRaw = (List<Map<String, Object>>) sessionsMap.get("sufficiency");
            List<DiagnosisSufficiencyStat> sufficiency = suffRaw == null ? List.of() : suffRaw.stream()
                    .map(r -> new DiagnosisSufficiencyStat(
                            String.valueOf(r.get("sufficiency")),
                            r.get("count") instanceof Number n ? n.longValue() : 0L))
                    .toList();

            sessions = new DiagnosisSessionsStats(total, byStatus, avgDurationSeconds, avgCostRmb, sufficiency);
        }

        // 2. sessionTrend
        Map<String, Object> trendMap = (Map<String, Object>) map.get("sessionTrend");
        DiagnosisSessionTrend sessionTrend = null;
        if (trendMap != null) {
            List<String> days = (List<String>) trendMap.get("days");
            List<?> createdRaw = (List<?>) trendMap.get("created");
            List<?> completedRaw = (List<?>) trendMap.get("completed");

            List<Long> created = createdRaw == null ? List.of() : createdRaw.stream()
                    .map(item -> item instanceof Number n ? n.longValue() : 0L).toList();
            List<Long> completed = completedRaw == null ? List.of() : completedRaw.stream()
                    .map(item -> item instanceof Number n ? n.longValue() : 0L).toList();

            sessionTrend = new DiagnosisSessionTrend(days == null ? List.of() : days, created, completed);
        }

        // 3. evidenceDirections
        List<Map<String, Object>> directionsRaw = (List<Map<String, Object>>) map.get("evidenceDirections");
        List<DiagnosisDirectionStat> evidenceDirections = directionsRaw == null ? List.of() : directionsRaw.stream()
                .map(d -> new DiagnosisDirectionStat(
                        String.valueOf(d.get("type")),
                        d.get("total") instanceof Number n ? n.longValue() : 0L,
                        d.get("success") instanceof Number n ? n.longValue() : 0L,
                        d.get("noData") instanceof Number n ? n.longValue() : 0L,
                        d.get("failed") instanceof Number n ? n.longValue() : 0L,
                        d.get("unavailable") instanceof Number n ? n.longValue() : 0L,
                        d.get("successRate") instanceof Number n ? n.doubleValue() : 0.0))
                .toList();

        // 4. attentionTypes
        List<String> attentionTypes = (List<String>) map.get("attentionTypes");

        // 5. calibration
        Map<String, Object> calibMap = (Map<String, Object>) map.get("calibration");
        HypothesisCalibration calibration = null;
        if (calibMap != null) {
            long ratedTotal = calibMap.get("ratedTotal") instanceof Number n ? n.longValue() : 0L;
            long helpful = calibMap.get("helpful") instanceof Number n ? n.longValue() : 0L;
            long wrong = calibMap.get("wrong") instanceof Number n ? n.longValue() : 0L;
            long excludedPartial = calibMap.get("excludedPartial") instanceof Number n ? n.longValue() : 0L;
            long excludedUnknown = calibMap.get("excludedUnknown") instanceof Number n ? n.longValue() : 0L;
            long excludedInvalid = calibMap.get("excludedInvalid") instanceof Number n ? n.longValue() : 0L;
            Double ece = calibMap.get("ece") instanceof Number n ? n.doubleValue() : null;
            Double empiricalAccuracy = calibMap.get("empiricalAccuracy") instanceof Number n ? n.doubleValue() : null;
            Double meanConfidence = calibMap.get("meanConfidence") instanceof Number n ? n.doubleValue() : null;

            List<Map<String, Object>> bucketsRaw = (List<Map<String, Object>>) calibMap.get("buckets");
            List<CalibrationBucket> buckets = bucketsRaw == null ? List.of() : bucketsRaw.stream()
                    .map(b -> new CalibrationBucket(
                            b.get("index") instanceof Number n ? n.intValue() : 0,
                            b.get("count") instanceof Number n ? n.longValue() : 0L,
                            b.get("meanConfidence") instanceof Number n ? n.doubleValue() : null,
                            b.get("accuracy") instanceof Number n ? n.doubleValue() : null,
                            b.get("gap") instanceof Number n ? n.doubleValue() : null))
                    .toList();

            calibration = new HypothesisCalibration(
                    ratedTotal, helpful, wrong, excludedPartial, excludedUnknown, excludedInvalid,
                    ece, empiricalAccuracy, meanConfidence, buckets);
        }

        return new DiagnosisBoardResponse(
                windowDays,
                sessions,
                sessionTrend,
                evidenceDirections,
                attentionTypes == null ? List.of() : attentionTypes,
                calibration
        );
    }
}
