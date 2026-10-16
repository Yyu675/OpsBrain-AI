package com.devops.agent.domain.evidence;

import java.time.Duration;

/**
 * 变更↔告警时间相关性评分（S1-2，路线图 §5.3 1-2.4）。
 * <p>
 * 分档而退化的理由（写死防漂移）：连续衰减函数（e 指数等）看着精致，
 * 但运维判断「刚发布 5 分钟」与「发布 1 小时」就是<b>档位</b>心智，
 * 且档位可读、可测试、可在证据里向人解释；引用路线图验收原文
 * 「刚发布 5 分钟 > 发布 2 小时」即档位语义。
 * </p>
 * <p>
 * 档位（delta = 取证时刻 - 变更时刻，负值=变更在取证时刻之后，视作 0 档）：
 * {@code ≤5m→1.0；≤30m→0.8；≤1h→0.6；≤2h→0.4；≤6h→0.2；>6h→0.1}。
 * 默认取证窗为 2h（工具层控制），>2h 的档位留给出窗复核与未来聚合器。
 * </p>
 */
public final class ChangeRelevanceScorer {

    private ChangeRelevanceScorer() {}

    public static double score(Duration delta) {
        long seconds = Math.max(0, delta.getSeconds());
        if (seconds <= 5 * 60)        return 1.0;
        if (seconds <= 30 * 60)       return 0.8;
        if (seconds <= 60 * 60)       return 0.6;
        if (seconds <= 2 * 3600)      return 0.4;
        if (seconds <= 6 * 3600)      return 0.2;
        return 0.1;
    }
}
