package com.devops.agent.domain.governance;

import com.devops.agent.domain.healing.HealingExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/**
 * 策略证据门（S4-2 批次 9）：连胜语义/转正徽标/自治达标三判定 + 溯源键直通仓储。
 * 阈值走包私有构造显式给定（promoteHits=3, streakGoal=2），绕开 @Value 空窗期。
 */
@DisplayName("PolicyEvidenceService（S4-2 证据门计数器）")
class PolicyEvidenceServiceTest {

    private HealingExecutionRepository repository;
    private PolicyEvidenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(HealingExecutionRepository.class);
        service = new PolicyEvidenceService(repository, 3, 2);
    }

    private static AutomationPolicy policy(long id, boolean dryRun) {
        AutomationPolicy p = new AutomationPolicy();
        p.setId(id);
        p.setActionKey("mock.disk.cleanup");
        p.setEnvironment("prod");
        p.setDryRun(dryRun);
        return p;
    }

    private void stub(List<String> statusesDesc, int dryRunHits) {
        when(repository.recentAutoStatuses(anyLong(), anyInt())).thenReturn(statusesDesc);
        when(repository.countPolicyDryRunHits(anyLong())).thenReturn(dryRunHits);
        when(repository.lastAutoFailureAt(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("真执行策略：连胜达标 → evidenceReady=true，连胜数如实回填")
    void livePolicyStreakReady() {
        stub(List.of("SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "FAILED", "SUCCEEDED"), 0);
        AutomationPolicy p = policy(42L, false);

        service.decorate(p);

        assertEquals(3, p.getSuccessStreak(), "FAILED 之后的旧绩不计——连胜只看新→旧连续段");
        assertEquals(Boolean.TRUE, p.getEvidenceReady());
        assertEquals(Boolean.FALSE, p.getPromotable(), "非演练策略永不可转正（已经在线上）");
    }

    @Test
    @DisplayName("真执行策略：最新一条就是 FAILED → 连胜归零，evidenceReady=false")
    void latestFailureZeroesStreak() {
        stub(List.of("FAILED", "SUCCEEDED", "SUCCEEDED"), 0);
        AutomationPolicy p = policy(43L, false);

        service.decorate(p);

        assertEquals(0, p.getSuccessStreak());
        assertEquals(Boolean.FALSE, p.getEvidenceReady());
    }

    @Test
    @DisplayName("UNDO 与 UNDO_FAILED 同样断连胜：执行后被回滚就是「非零误」")
    void undoBreaksStreak() {
        stub(List.of("SUCCEEDED", "UNDONE", "SUCCEEDED", "SUCCEEDED", "SUCCEEDED"), 0);
        AutomationPolicy p = policy(44L, false);

        service.decorate(p);

        assertEquals(1, p.getSuccessStreak(), "UNDONE 断点之后的远古连胜清零");
    }

    @Test
    @DisplayName("演练留痕与在途是中立项：REJECTED/PENDING_APPROVAL/RUNNING 不断连败也不累计")
    void neutralStatusesAreSkipped() {
        stub(List.of("REJECTED", "PENDING_APPROVAL", "SUCCEEDED", "RUNNING", "SUCCEEDED", "FAILED"), 6);
        AutomationPolicy p = policy(45L, false);

        service.decorate(p);

        assertEquals(2, p.getSuccessStreak(), "两条 SUCCEEDED 之间隔着中立项仍连续");
        assertEquals(Boolean.TRUE, p.getEvidenceReady());
        assertEquals(6, p.getDryRunHits());
    }

    @Test
    @DisplayName("空台账：连胜 0 / 命中 0 → 双徽标皆 false（新策略没有证据，只有空白）")
    void emptyLedgerMeansNoEvidence() {
        stub(List.of(), 0);
        AutomationPolicy p = policy(46L, true);

        service.decorate(p);

        assertEquals(0, p.getSuccessStreak());
        assertEquals(0, p.getDryRunHits());
        assertEquals(Boolean.FALSE, p.getPromotable());
        assertEquals(Boolean.FALSE, p.getEvidenceReady());
    }

    @Test
    @DisplayName("演练策略：命中场次达门槛 → promotable=true；未达 → false")
    void dryRunPromoteByHits() {
        stub(List.of("REJECTED", "REJECTED", "REJECTED"), 3);
        AutomationPolicy ready = policy(47L, true);
        service.decorate(ready);
        assertEquals(Boolean.TRUE, ready.getPromotable());

        stub(List.of("REJECTED"), 2);
        AutomationPolicy notYet = policy(48L, true);
        service.decorate(notYet);
        assertEquals(Boolean.FALSE, notYet.getPromotable());
    }

    @Test
    @DisplayName("演练策略永不亮 evidenceReady：演练行是 REJECTED，连胜恒 0——阶段语义不串味")
    void dryRunNeverEvidenceReady() {
        stub(List.of("REJECTED", "REJECTED", "REJECTED", "REJECTED", "REJECTED"), 5);
        AutomationPolicy p = policy(49L, true);

        service.decorate(p);

        assertEquals(0, p.getSuccessStreak());
        assertEquals(Boolean.FALSE, p.getEvidenceReady());
        assertEquals(Boolean.TRUE, p.getPromotable(), "场次够了该有的还是有的");
    }

    @Test
    @DisplayName("污点时间直通（最后一次 FAILED/UNDO 行的创建时间，供徽标 title 展示）")
    void lastFailureFlowThrough() {
        java.time.LocalDateTime t = java.time.LocalDateTime.of(2026, 9, 8, 3, 30);
        stub(List.of("SUCCEEDED"), 0);
        when(repository.lastAutoFailureAt(50L)).thenReturn(Optional.of(t));
        AutomationPolicy p = policy(50L, false);

        service.decorate(p);

        assertEquals(t, p.getLastAutoFailureAt());
    }

    @Test
    @DisplayName("溯源键直通仓储：params_json LIKE 键就是引擎写入的 __policyId 数值形")
    void policyLikeKeyPassesThrough() {
        stub(List.of(), 0);
        AutomationPolicy p = policy(77L, true);

        service.decorate(p);

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(repository).recentAutoStatuses(idCaptor.capture(), org.mockito.ArgumentMatchers.eq(50));
        assertEquals(77L, idCaptor.getValue());
        assertEquals("%\"__policyId\":77%",
                com.devops.agent.domain.healing.HealingExecutionRepository.policyLikeKey(77L),
                "LIKE 键必须与 Jackson 数值序列化形完全一致（引号内数字不带引号）");
    }

    @Test
    @DisplayName("第三支柱：审计不完整卡住 evidenceReady——「跑成功了但说不出怎么跑的」不是证据")
    void incompleteAuditBlocksEvidenceReady() {
        stub(List.of("SUCCEEDED", "SUCCEEDED", "SUCCEEDED"), 0);
        when(repository.countIncompleteAuditAmongRecent(60L, 50)).thenReturn(1);
        AutomationPolicy p = policy(60L, false);

        service.decorate(p);

        assertEquals(3, p.getSuccessStreak(), "连胜本身仍达标——是第三支柱卡住，不是连胜重新算");
        assertEquals(1, p.getAuditIncompleteRecent());
        assertEquals(Boolean.FALSE, p.getEvidenceReady());
    }

    @Test
    @DisplayName("审计完整（0 缺失）时第三支柱放行——批次 9 既有语义不回归")
    void completeAuditKeepsEvidenceReady() {
        stub(List.of("SUCCEEDED", "SUCCEEDED"), 0);
        // countIncompleteAuditAmongRecent 未 stub → Mockito int 默认 0（完整）
        AutomationPolicy p = policy(61L, false);

        service.decorate(p);

        assertEquals(0, p.getAuditIncompleteRecent());
        assertEquals(Boolean.TRUE, p.getEvidenceReady());
    }

    @Test
    @DisplayName("阈值随派生字段下发，前端不再把达标线写死（promoteHitsGoal/streakGoal 透传）")
    void thresholdsFlowThrough() {
        stub(List.of("REJECTED", "REJECTED", "REJECTED"), 3);
        AutomationPolicy p = policy(62L, true);

        service.decorate(p);

        assertEquals(3, p.getPromoteHitsGoal());
        assertEquals(2, p.getStreakGoal());
    }

    @Test
    @DisplayName("promotable 只看演练场次，不看审计完整性——阶段语义各自单一，互不借字段")
    void promotableIgnoresAuditCompleteness() {
        stub(List.of("REJECTED", "REJECTED", "REJECTED"), 3);
        when(repository.countIncompleteAuditAmongRecent(63L, 50)).thenReturn(2);
        AutomationPolicy p = policy(63L, true);

        service.decorate(p);

        assertEquals(Boolean.TRUE, p.getPromotable());
    }

    @Test
    @DisplayName("空 id 哨兵：未落库的策略对象不查仓储（新建表单预览态不炸）")
    void nullIdIsNoop() {
        AutomationPolicy p = new AutomationPolicy();

        service.decorate(p);

        verify(repository, never()).recentAutoStatuses(anyLong(), anyInt());
        assertNull(p.getSuccessStreak());
    }
}
