package com.devops.agent.domain.biz.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * boost 公式纯函数测试（地板/天花板，2026-09-24）。
 *
 * <h3>为什么单独测公式</h3>
 * 原始路线图公式 {@code 1 + ln(1+h) − 0.5×w} 无上下限：
 * <ul>
 *   <li>两条 WRONG 后 boost 已跌到 0，三条为负——score 乘负数后排序语义
 *       混乱，且该 chunk 事实上被永久埋没（后续 HELPFUL 需挣回 ×0.5 的
 *       负缺口才回正，几乎不可能）；</li>
 *   <li>反之高频点赞无上限，一篇老文档靠历史热度长期压制更相关的新文档。
 *       历史热度劫持检索排序，是比「埋没」更难察觉的偏置。</li>
 * </ul>
 * 这些偏置不会抛错、不会让任何测试红，只会在检索排序里缓慢累积——
 * 公式纯函数是唯一能精确锁住夹取边界的测试面。
 */
@DisplayName("boost 公式夹取（clamp [0.2, 5.0]）")
class KnowledgeBoostFormulaTest {

    @Test
    @DisplayName("中性 (0,0) = 1.0——刚入库/未反馈 chunk 不升不降")
    void neutralIsOne() {
        assertThat(KnowledgeBoostRepository.computeBoost(0, 0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("一条 HELPFUL = 1+ln2 ≈ 1.693——升权")
    void oneHelpfulLifts() {
        assertThat(KnowledgeBoostRepository.computeBoost(1, 0))
                .isEqualTo(1.0 + Math.log(2.0));
    }

    @Test
    @DisplayName("一条 WRONG = 0.5——降权一半，仍在正常区间")
    void oneWrongHalves() {
        assertThat(KnowledgeBoostRepository.computeBoost(0, 1)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("多条 WRONG 夹到地板 0.2——不再归零/为负，坏反馈 ≠ 永久埋没")
    void mountainWrongClampsToFloor() {
        for (int wrong : new int[]{2, 3, 10, 100}) {
            double boost = KnowledgeBoostRepository.computeBoost(0, wrong);
            assertThat(boost)
                    .as("wrong=%d 时 boost 应被地板夹到 0.2，而非 %s", wrong, raw(wrong))
                    .isEqualTo(0.2);
        }
    }

    @Test
    @DisplayName("高频 HELPFUL 夹到天花板 5.0——热度不无限霸榜")
    void manyHelpfulClampToCeil() {
        // 天花板 5.0 需 1+ln(1+h) > 5 → h ≥ e^4−1 ≈ 54；取 60 起确保越线，
        // 再配合一个「仍未越线」的对照值 20（1+ln21≈4.04——不该被误夹到地板之外）
        for (int helpful : new int[]{60, 100, 1000}) {
            assertThat(KnowledgeBoostRepository.computeBoost(helpful, 0)).isEqualTo(5.0);
        }
        assertThat(KnowledgeBoostRepository.computeBoost(20, 0))
                .as("20 条 helpful 尚未触及天花板，应保持真实值 1+ln21≈4.04 而非被夹到 5.0")
                .isEqualTo(1.0 + Math.log(21.0));
    }

    @Test
    @DisplayName("地板之上：1 正 1 负 = 1.5+ln2 ≈ 2.193——正反馈能覆盖单条误报")
    void oneHelpfulOneWrongCovered() {
        assertThat(KnowledgeBoostRepository.computeBoost(1, 1))
                .isEqualTo(1.0 + Math.log(2.0) - 0.5);
    }

    /** 无夹取的原始值（错误实现参考）：证明地板测试确实在防「未夹取」这种缺陷 */
    private static double raw(int wrong) {
        return 1.0 + Math.log1p(0) - 0.5 * wrong;
    }
}