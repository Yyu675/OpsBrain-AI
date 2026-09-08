package com.devops.agent.application.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断假设「置信度 × 反馈」校准看板面（S4-2 / 4-2.1 数据面，批 35）。
 *
 * <h3>数据源</h3>
 * sys_diagnosis_hypothesis 的 {@code confidence}（0~1，ConfidenceEngine 钳制后终值）
 * × {@code feedback}（HELPFUL / PARTIAL / WRONG，2-3.5 反馈回流）——反馈闭环的
 * 第二读数，与工单 AI 根因准确率（报告 130）同族，但指标方向相反：
 * 那边越高越好，这边的 ECE 越低越好。
 *
 * <h3>判定集口径（反伪造第一戒）</h3>
 * ECE 的 correct 必须是布尔。反馈三态里只有 HELPFUL=true / WRONG=false
 * 可判定；<b>PARTIAL 豁免</b>——「一半对」塞进二值口径只会污染校准曲线，
 * 但它<b>计数可见</b>（excludedPartial），不许静默消失。
 * 未知反馈值与越界置信度同样「豁免但计数」——看板宁可缺一角，不可崩一页。
 *
 * <h3>空判定集纪律（与批 33 EceMetrics 同一条）</h3>
 * ECE 越低越好 ⇒ 空集报 0 就是把「还没人反馈」说成「完美校准」。
 * 判定集为空时 ece / empiricalAccuracy / meanConfidence 一律 {@code null}
 * （Dashboard 页祖传纪律：null ≠ 0），前端显示「—」。
 *
 * <h3>公式同步纪律</h3>
 * 桶数学与 test 侧 {@code eval.EceMetrics} 同谱（等宽 10 桶，1.0 归末桶，
 * ECE = Σ(|B|/N)·|acc−conf|）——此处是生产读数面，那边是评测骨架面；
 * 改公式必须两侧同步，测法以本类的确定性钉测为仲裁。
 */
final class HypothesisCalibrationBoard {

    private static final int BIN_COUNT = 10;

    private HypothesisCalibrationBoard() {
    }

    /**
     * @param feedbackRows SQL 直行 [{confidence: Number, feedback: String}]，
     *                     已按窗口过滤（feedback IS NOT NULL）
     * @return 校准看板载荷（键集固定，缺失语义全部用 null 而非缺键）
     */
    static Map<String, Object> compose(List<Map<String, Object>> feedbackRows) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<double[]> pairs = new ArrayList<>(); // [confidence, correct(1.0/0.0)]
        long helpful = 0, wrong = 0, partial = 0, unknown = 0, invalid = 0;

        for (Map<String, Object> r : feedbackRows) {
            String fb = String.valueOf(r.get("feedback"));
            boolean correct;
            if ("HELPFUL".equals(fb)) {
                correct = true;
            } else if ("WRONG".equals(fb)) {
                correct = false;
            } else if ("PARTIAL".equals(fb)) {
                partial++;
                continue;
            } else {
                unknown++; // 新反馈值入场：计数可见，不进判定集（不静默消失）
                continue;
            }
            Object c = r.get("confidence");
            if (!(c instanceof Number) || Double.isNaN(((Number) c).doubleValue())) {
                invalid++;
                continue;
            }
            double conf = ((Number) c).doubleValue();
            if (conf < 0.0 || conf > 1.0) {
                invalid++; // 生产面不抛——看板缺一角 ≠ 崩一页；评测面才硬拒
                continue;
            }
            if (correct) {
                helpful++;
            } else {
                wrong++;
            }
            pairs.add(new double[]{conf, correct ? 1.0 : 0.0});
        }

        int rated = pairs.size();
        out.put("ratedTotal", rated);
        out.put("helpful", helpful);
        out.put("wrong", wrong);
        out.put("excludedPartial", partial);
        out.put("excludedUnknown", unknown);
        out.put("excludedInvalid", invalid);

        if (rated == 0) {
            out.put("meanConfidence", null);
            out.put("empiricalAccuracy", null);
            out.put("ece", null);
            out.put("buckets", emptyBuckets());
            return out;
        }

        double confSum = 0, correctSum = 0;
        double[] binConfSum = new double[BIN_COUNT];
        double[] binCorrectSum = new double[BIN_COUNT];
        int[] binCount = new int[BIN_COUNT];
        for (double[] p : pairs) {
            confSum += p[0];
            correctSum += p[1];
            int idx = Math.min((int) (p[0] * BIN_COUNT), BIN_COUNT - 1);
            binCount[idx]++;
            binConfSum[idx] += p[0];
            binCorrectSum[idx] += p[1];
        }
        double eceSum = 0;
        List<Map<String, Object>> buckets = new ArrayList<>(BIN_COUNT);
        for (int i = 0; i < BIN_COUNT; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("index", i);
            b.put("count", binCount[i]);
            if (binCount[i] == 0) {
                b.put("meanConfidence", null);
                b.put("accuracy", null);
                b.put("gap", null);
            } else {
                double mc = binConfSum[i] / binCount[i];
                double acc = binCorrectSum[i] / binCount[i];
                double gap = Math.abs(acc - mc);
                b.put("meanConfidence", round(mc, 4));
                b.put("accuracy", round(acc, 4));
                b.put("gap", round(gap, 4));
                eceSum += (binCount[i] / (double) rated) * gap;
            }
            buckets.add(b);
        }
        out.put("meanConfidence", round(confSum / rated, 4));
        out.put("empiricalAccuracy", round(correctSum / rated, 4));
        out.put("ece", round(eceSum, 4));
        out.put("buckets", buckets);
        return out;
    }

    private static List<Map<String, Object>> emptyBuckets() {
        List<Map<String, Object>> buckets = new ArrayList<>(BIN_COUNT);
        for (int i = 0; i < BIN_COUNT; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("index", i);
            b.put("count", 0);
            b.put("meanConfidence", null);
            b.put("accuracy", null);
            b.put("gap", null);
            buckets.add(b);
        }
        return buckets;
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
