package com.devops.agent.application.runtime;

import com.devops.agent.infrastructure.persistence.repo.ConversationTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 对话原文保留期清理任务契约（B-2 收尾）。
 *
 * <h3>这组用例要防住什么</h3>
 * <ol>
 *   <li><b>清理边界早于归档保留期</b> —— 最危险的一条。原文若在会话被冷归档之前
 *       就被删掉，归档文件会静默退回 {@code SUMMARY_ONLY}，
 *       B-2 补的缺口重新打开且<b>没有任何报错</b>；</li>
 *   <li><b>只删一批就收工</b> —— 积压永远追不上（每天 500 条 vs 十万条积压）；</li>
 *   <li><b>非法配置直接执行</b> —— {@code retention-days=0} 会命中除今天以外的
 *       全部数据，等于清空整张审计表，且不可逆；</li>
 *   <li><b>异常外抛</b> —— 会让 Spring 停止后续所有调度。</li>
 * </ol>
 *
 * <h3>断言落点</h3>
 * 全部断言落在<b>传给仓储的参数值</b>与<b>调用次数</b>的组合上，
 * 而非只验「被调用过」——写死天数、少删一批这两类缺陷都会照常产生调用。
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@DisplayName("对话原文保留期清理")
class ConversationTurnRetentionSchedulerTest {

    private ConversationTurnRepository turnRepo;
    private ConversationTurnRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        turnRepo = mock(ConversationTurnRepository.class);
        scheduler = new ConversationTurnRetentionScheduler(turnRepo);
        // 以下字段全是 @Value 注入的，直接 new 时为默认值 0/false，
        // 不设夹具会走进「参数非法直接返回」分支，让每条用例都以无关原因通过
        setConfig(true, 180, 500, 20, 90);
    }

    private void setConfig(boolean enabled, int retentionDays, int batchSize,
                           int maxBatches, int archiveAfterDays) {
        ReflectionTestUtils.setField(scheduler, "retentionEnabled", enabled);
        ReflectionTestUtils.setField(scheduler, "retentionDays", retentionDays);
        ReflectionTestUtils.setField(scheduler, "batchSize", batchSize);
        ReflectionTestUtils.setField(scheduler, "maxBatchesPerRun", maxBatches);
        ReflectionTestUtils.setField(scheduler, "archiveAfterDays", archiveAfterDays);
    }

    @Nested
    @DisplayName("清理边界不得早于归档保留期")
    class RetentionFloor {

        @Test
        @DisplayName("配置 180 天 > 归档 90+7 天：按配置值 180 清理")
        void usesConfiguredValueWhenAboveFloor() {
            setConfig(true, 180, 500, 20, 90);
            when(turnRepo.deleteOlderThan(anyInt(), anyInt())).thenReturn(0);

            scheduler.purgeExpiredTurns();

            // 断言落在天数这个参数值上：写死成其它天数会被抓到
            verify(turnRepo).deleteOlderThan(eq(180), eq(500));
        }

        @Test
        @DisplayName("配置 30 天 < 归档 90+7 天：抬升为 97 天，绝不按 30 天删")
        void raisesToFloorWhenShorterThanArchiveWindow() {
            // 这正是最危险的误配：原文会在会话被归档前 67 天就消失
            setConfig(true, 30, 500, 20, 90);
            when(turnRepo.deleteOlderThan(anyInt(), anyInt())).thenReturn(0);

            scheduler.purgeExpiredTurns();

            assertThat(scheduler.effectiveRetentionDays()).isEqualTo(97);
            verify(turnRepo).deleteOlderThan(eq(97), anyInt());
            verify(turnRepo, never()).deleteOlderThan(eq(30), anyInt());
        }

        @Test
        @DisplayName("归档保留期被上调到 365 时，清理边界随之抬到 372")
        void floorTracksArchiveWindow() {
            // 边界随归档配置联动，而非写死常量——写死 97 的实现会在这里失败
            setConfig(true, 180, 500, 20, 365);

            assertThat(scheduler.effectiveRetentionDays()).isEqualTo(372);
        }

        @Test
        @DisplayName("归档保留期误配为负数时，下界按 0 计算，不得反向缩小")
        void negativeArchiveWindowDoesNotShrinkFloor() {
            // 取 retentionDays=3（小于安全余量 7）才能区分两种实现：
            // 不夹 0 的实现算出 -100+7=-93，max(3,-93)=3；
            // 夹 0 的实现算出 0+7=7，max(3,7)=7。若用 retentionDays=10，
            // 两种实现都得 10，用例形同虚设
            setConfig(true, 3, 500, 20, -100);

            assertThat(scheduler.effectiveRetentionDays()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("分批循环直到删空")
    class BatchLoop {

        @Test
        @DisplayName("满批说明还有剩余，必须继续删；未满批才收工")
        void loopsUntilPartialBatch() {
            setConfig(true, 180, 500, 20, 90);
            // 两个满批 + 一个未满批 → 应恰好调用 3 次
            when(turnRepo.deleteOlderThan(anyInt(), anyInt()))
                    .thenReturn(500, 500, 137);

            scheduler.purgeExpiredTurns();

            // 只删一批的实现在这里只会调 1 次；不设终止条件的实现会调满 20 次
            verify(turnRepo, times(3)).deleteOlderThan(eq(180), eq(500));
        }

        @Test
        @DisplayName("首批就未满：只调用一次，不空转")
        void stopsImmediatelyWhenFirstBatchPartial() {
            setConfig(true, 180, 500, 20, 90);
            when(turnRepo.deleteOlderThan(anyInt(), anyInt())).thenReturn(12);

            scheduler.purgeExpiredTurns();

            verify(turnRepo, times(1)).deleteOlderThan(anyInt(), anyInt());
        }

        @Test
        @DisplayName("持续满批时受 max-batches-per-run 封顶，不会无限循环")
        void cappedByMaxBatches() {
            setConfig(true, 180, 500, 3, 90);
            when(turnRepo.deleteOlderThan(anyInt(), anyInt())).thenReturn(500);

            scheduler.purgeExpiredTurns();

            verify(turnRepo, times(3)).deleteOlderThan(anyInt(), anyInt());
        }

        @Test
        @DisplayName("仓储异常兜底返回 0 时立即退出，不空转打满日志")
        void exitsWhenRepositoryReturnsZero() {
            setConfig(true, 180, 500, 20, 90);
            when(turnRepo.deleteOlderThan(anyInt(), anyInt())).thenReturn(0);

            scheduler.purgeExpiredTurns();

            verify(turnRepo, times(1)).deleteOlderThan(anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("非法配置与开关")
    class GuardRails {

        @Test
        @DisplayName("开关关闭：一次删除都不发生")
        void disabledSkipsEntirely() {
            setConfig(false, 180, 500, 20, 90);

            scheduler.purgeExpiredTurns();

            verifyNoInteractions(turnRepo);
        }

        @Test
        @DisplayName("retention-days=0 会清空整张审计表，必须拒绝执行")
        void zeroRetentionRefused() {
            setConfig(true, 0, 500, 20, 90);

            scheduler.purgeExpiredTurns();

            // 注意：若实现只做 max(0, 97) 抬升而不校验，这里会以 97 天执行——
            // 那反而是"安全"的。但配置为 0 是明确的误配信号，
            // 静默按 97 天跑会让运维以为配置生效了。必须拒绝并报错
            verifyNoInteractions(turnRepo);
        }

        @Test
        @DisplayName("retention-days 为负数同样拒绝")
        void negativeRetentionRefused() {
            setConfig(true, -1, 500, 20, 90);

            scheduler.purgeExpiredTurns();

            verifyNoInteractions(turnRepo);
        }

        @Test
        @DisplayName("batch-size <= 0 拒绝执行：LIMIT 0 会死循环空删")
        void nonPositiveBatchSizeRefused() {
            setConfig(true, 180, 0, 20, 90);

            scheduler.purgeExpiredTurns();

            verifyNoInteractions(turnRepo);
        }

        @Test
        @DisplayName("max-batches-per-run <= 0 拒绝执行")
        void nonPositiveMaxBatchesRefused() {
            setConfig(true, 180, 500, 0, 90);

            scheduler.purgeExpiredTurns();

            verifyNoInteractions(turnRepo);
        }
    }

    @Nested
    @DisplayName("异常不外抛")
    class ExceptionContainment {

        @Test
        @DisplayName("仓储抛异常时任务自行吞掉——外抛会让 Spring 停止后续所有调度")
        void swallowsRepositoryException() {
            setConfig(true, 180, 500, 20, 90);
            when(turnRepo.deleteOlderThan(anyInt(), anyInt()))
                    .thenThrow(new RuntimeException("connection reset"));

            assertThatCode(() -> scheduler.purgeExpiredTurns()).doesNotThrowAnyException();
        }
    }
}
