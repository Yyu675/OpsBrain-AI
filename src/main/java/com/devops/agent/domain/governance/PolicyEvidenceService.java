package com.devops.agent.domain.governance;

import com.devops.agent.domain.healing.HealingExecution;
import com.devops.agent.domain.healing.HealingExecutionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 策略证据门（S4-2 批次 9）：信任阶梯第二级的序章——让「摘 dryRun」有数据撑腰。
 *
 * <h3>为什么是两个计数器而不是一个</h3>
 * 演练与真执行是两个阶段，证据语义不同：
 * <ul>
 *   <li><b>演练命中场次</b>（dryRun=true 阶段）：证明「策略匹配的告警面符合预期」——
 *       场次数够了，运维才知这条策略会打到什么，这时摘演练开关踩空的概率最小；</li>
 *   <li><b>连续零误连胜</b>（dryRun=false 阶段）：证明「这条策略真动手时可靠」——
 *       这是 PRD L5 可靠性证据门（连续 N 次低危自愈零误）的引用点，
 *       未来「免审批升级」必须读它，而不是拍脑袋。</li>
 * </ul>
 *
 * <h3>判定的边界（防过度声称）</h3>
 * <ul>
 *   <li>连胜只统计 {@code requested_by='auto'} 且 params 带 __policyId 溯源键的台账行——
 *       人工触发的成功不算在策略头上；</li>
 *   <li>污点={@code FAILED / UNDO_FAILED / UNDONE}：撤销过说明那次执行产生了后悔，
 *       同样是「非零误」的证据，一律断连胜；</li>
 *   <li>{@code REJECTED / PENDING_APPROVAL / RUNNING} 中立跳过：
 *       演练留痕与在途中既非成功证明也非失败，不断连败也不累计；</li>
 *   <li>只看最近 50 条——远古的成功对当下的策略配置没有证明力（策略改过就物是人非）。</li>
 * </ul>
 *
 * <h3>判定放服务端</h3>
 * 徽标布尔（promotable / evidenceReady）由本服务给出，前端只展示——
 * 各自重算必然与引擎漂移（applyActionState 同款先例）。
 */
@Service
public class PolicyEvidenceService {

    /** 连胜窗口：只看最近 N 条 auto 台账。 */
    private static final int STREAK_WINDOW = 50;

    private final HealingExecutionRepository executionRepository;

    /** 演练命中场次门槛：攒够几场才亮「可转正」。 */
    @Value("${devops.healing.policy-evidence.dryrun-promote-hits:5}")
    private int dryRunPromoteHits;

    /** 连胜门槛：连续零误执行几场算「自治证据达标」。 */
    @Value("${devops.healing.policy-evidence.success-streak-goal:3}")
    private int successStreakGoal;

    /**
     * 生产装配入口。多构造器类必须显式 @Autowired——否则 Spring 回退找
     * 默认无参构造器（不存在）整个 ApplicationContext 起不来
     * （683e5e0 后端全 context 崩的尸检结论，工具账见报告 115 §四）。
     */
    @Autowired
    public PolicyEvidenceService(HealingExecutionRepository executionRepository) {
        this.executionRepository = executionRepository;
    }

    /** 测试用构造：阈值显式给定，绕开 @Value 的空窗期。 */
    PolicyEvidenceService(HealingExecutionRepository executionRepository,
                          int dryRunPromoteHits, int successStreakGoal) {
        this.executionRepository = executionRepository;
        this.dryRunPromoteHits = dryRunPromoteHits;
        this.successStreakGoal = successStreakGoal;
    }

    /**
     * 给单条策略装填证据字段（列表/详情两挂点共用）。
     * <p>装填失败的兜底在调用方（策略列表不能因徽标装填失败而整页 500）。</p>
     */
    public void decorate(AutomationPolicy p) {
        if (p == null || p.getId() == null) {
            return;
        }
        List<String> recent = executionRepository.recentAutoStatuses(p.getId(), STREAK_WINDOW);
        int hits = executionRepository.countPolicyDryRunHits(p.getId());
        Optional<LocalDateTime> lastFail = executionRepository.lastAutoFailureAt(p.getId());
        int auditIncomplete = executionRepository.countIncompleteAuditAmongRecent(p.getId(), STREAK_WINDOW);

        int streak = computeSuccessStreak(recent);
        p.setSuccessStreak(streak);
        p.setDryRunHits(hits);
        p.setLastAutoFailureAt(lastFail.orElse(null));
        p.setAuditIncompleteRecent(auditIncomplete);
        p.setPromoteHitsGoal(dryRunPromoteHits);
        p.setStreakGoal(successStreakGoal);
        p.setPromotable(p.isDryRun() && hits >= dryRunPromoteHits);
        // 第三支柱：审计不完整的执行不构成自治证据——
        // 「跑成功了但说不出来怎么跑的」与失败同等不可信。
        p.setEvidenceReady(!p.isDryRun() && streak >= successStreakGoal && auditIncomplete == 0);
    }

    /**
     * 连续零误连胜计算（新→旧遍历）：
     * SUCCEEDED 累计；FAILED / UNDO_FAILED / UNDONE 立即断连胜；
     * 其余状态（REJECTED 演练留痕 / PENDING_APPROVAL / RUNNING）中立跳过。
     */
    static int computeSuccessStreak(List<String> recentDesc) {
        int streak = 0;
        for (String status : recentDesc) {
            if (HealingExecution.Status.SUCCEEDED.equals(status)) {
                streak++;
            } else if (HealingExecution.Status.FAILED.equals(status)
                    || HealingExecution.Status.UNDO_FAILED.equals(status)
                    || HealingExecution.Status.UNDONE.equals(status)) {
                break;
            }
        }
        return streak;
    }
}
