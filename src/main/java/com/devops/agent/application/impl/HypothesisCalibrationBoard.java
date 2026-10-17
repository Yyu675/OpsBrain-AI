package com.devops.agent.application.impl;

import com.devops.agent.controller.dto.CalibrationBucket;
import com.devops.agent.controller.dto.HypothesisCalibration;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊断假设「置信度 × 反馈」校准看板面。
 * 空判定集指标保持 null；PARTIAL、未知反馈与非法置信度豁免但显式计数。
 */
final class HypothesisCalibrationBoard {

    record FeedbackRow(Double confidence, String feedback) {}

    private static final int BIN_COUNT = 10;

    private HypothesisCalibrationBoard() {}

    static HypothesisCalibration compose(List<FeedbackRow> feedbackRows) {
        List<double[]> pairs = new ArrayList<>(); // [confidence, correct(1.0/0.0)]
        long helpful = 0, wrong = 0, partial = 0, unknown = 0, invalid = 0;

        for (FeedbackRow row : feedbackRows) {
            String feedback = String.valueOf(row.feedback());
            boolean correct;
            if ("HELPFUL".equals(feedback)) {
                correct = true;
            } else if ("WRONG".equals(feedback)) {
                correct = false;
            } else if ("PARTIAL".equals(feedback)) {
                partial++;
                continue;
            } else {
                unknown++;
                continue;
            }
            Double confidence = row.confidence();
            if (confidence == null || Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
                invalid++;
                continue;
            }
            if (correct) helpful++; else wrong++;
            pairs.add(new double[]{confidence, correct ? 1.0 : 0.0});
        }

        int rated = pairs.size();
        if (rated == 0) {
            return new HypothesisCalibration(0, helpful, wrong, partial, unknown, invalid,
                    null, null, null, emptyBuckets());
        }

        double confidenceSum = 0, correctSum = 0;
        double[] binConfidenceSum = new double[BIN_COUNT];
        double[] binCorrectSum = new double[BIN_COUNT];
        int[] binCount = new int[BIN_COUNT];
        for (double[] pair : pairs) {
            confidenceSum += pair[0];
            correctSum += pair[1];
            int index = Math.min((int) (pair[0] * BIN_COUNT), BIN_COUNT - 1);
            binCount[index]++;
            binConfidenceSum[index] += pair[0];
            binCorrectSum[index] += pair[1];
        }
        double eceSum = 0;
        List<CalibrationBucket> buckets = new ArrayList<>(BIN_COUNT);
        for (int index = 0; index < BIN_COUNT; index++) {
            if (binCount[index] == 0) {
                buckets.add(new CalibrationBucket(index, 0, null, null, null));
            } else {
                double meanConfidence = binConfidenceSum[index] / binCount[index];
                double accuracy = binCorrectSum[index] / binCount[index];
                double gap = Math.abs(accuracy - meanConfidence);
                buckets.add(new CalibrationBucket(index, binCount[index], round(meanConfidence, 4),
                        round(accuracy, 4), round(gap, 4)));
                eceSum += (binCount[index] / (double) rated) * gap;
            }
        }
        return new HypothesisCalibration(rated, helpful, wrong, partial, unknown, invalid,
                round(eceSum, 4), round(correctSum / rated, 4), round(confidenceSum / rated, 4), buckets);
    }

    private static List<CalibrationBucket> emptyBuckets() {
        List<CalibrationBucket> buckets = new ArrayList<>(BIN_COUNT);
        for (int index = 0; index < BIN_COUNT; index++) {
            buckets.add(new CalibrationBucket(index, 0, null, null, null));
        }
        return buckets;
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
