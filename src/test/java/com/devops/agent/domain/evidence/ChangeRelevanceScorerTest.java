package com.devops.agent.domain.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** S1-2 相关性分档：档位边界逐档钉死（路线图验收「刚发布 5 分钟 > 发布 2 小时」）。 */
class ChangeRelevanceScorerTest {

    @Test
    @DisplayName("分档边界：5m/30m/1h/2h/6h 边界值取本档")
    void tiersAtExactBoundaries() {
        assertEquals(1.0, ChangeRelevanceScorer.score(Duration.ofMinutes(5)));
        assertEquals(0.8, ChangeRelevanceScorer.score(Duration.ofMinutes(30)));
        assertEquals(0.6, ChangeRelevanceScorer.score(Duration.ofHours(1)));
        assertEquals(0.4, ChangeRelevanceScorer.score(Duration.ofHours(2)));
        assertEquals(0.2, ChangeRelevanceScorer.score(Duration.ofHours(6)));
        assertEquals(0.1, ChangeRelevanceScorer.score(Duration.ofHours(6).plusMinutes(1)));
    }

    @Test
    @DisplayName("验收用例：5 分钟前发布(1.0) 严格大于 2 小时前发布(0.4)")
    void roadmapAcceptanceCase() {
        assertEquals(1.0, ChangeRelevanceScorer.score(Duration.ofMinutes(5)));
        assertEquals(0.4, ChangeRelevanceScorer.score(Duration.ofHours(2)));
        org.junit.jupiter.api.Assertions.assertTrue(
                ChangeRelevanceScorer.score(Duration.ofMinutes(5))
                        > ChangeRelevanceScorer.score(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("负值 delta（变更在取证时刻之后）按 0 档处理不越界")
    void negativeDeltaTreatedAsZero() {
        assertEquals(1.0, ChangeRelevanceScorer.score(Duration.ofMinutes(-3)));
    }

    @Test
    @DisplayName("档位单调不增：时间拉长分不升")
    void monotonicNonIncreasing() {
        double prev = 1.0;
        for (long m : new long[]{5, 30, 60, 120, 360, 361, 24 * 60}) {
            double cur = ChangeRelevanceScorer.score(Duration.ofMinutes(m));
            org.junit.jupiter.api.Assertions.assertTrue(cur <= prev, "档位必须单调不增");
            prev = cur;
        }
    }
}
