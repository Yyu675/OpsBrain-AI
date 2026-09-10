package com.devops.agent.application.runtime;

import com.devops.agent.domain.approval.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批超时扫描（批 75 / P1-1：报告 174 审计接线件）。
 *
 * <p><b>背景</b>：{@code ApprovalService.expireOverdue()} 自 L3 建成以来
 * 全库无任何调用方（仅测试引用）——「超时未审批自动驳回（不放行）」的
 * 治理承诺实际从未执行过，审批单永久悬挂。这是双 agent 审计的 P1 首项：
 * 承诺的治理能力静默不存在。</p>
 *
 * <p><b>设计决策</b>：
 * <ul>
 *   <li><b>扫描频率 5 分钟</b>：审批时限默认 24h（approval.timeout-hours），
 *       与 P0 首响 15min 的紧急度不同——分钟级延迟无实质影响，
 *       5 分钟已足够及时且扫描成本可忽略（部分索引钉住 PENDING 子集）。</li>
 *   <li><b>超时 = 驳回（EXPIRED）而非放行</b>：与 L3 治理铁律一致——
 *       「审批超时策略：超时未审批 → 自动驳回（不放行）」（路线图 3-3.3 原案）。
 *       高危动作宁可不执行，不可默许执行。</li>
 *   <li><b>幂等</b>：markExpired 的 UPDATE 带 {@code status='PENDING'} 谓词，
 *       重复扫描零副作用；部分索引 {@code idx_approval_pending_expire}
 *       (expires_at WHERE status='PENDING') 扫描面恒小。</li>
 *   <li><b>开关</b>：默认开启——这是治理承诺件不是可选增强；运维在
 *       数据迁移/演练期可临时关停（挂 pending 单不希望在演练中被批量过期）。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-09-10（批 75，报告 174 P1-1）
 */
@Component
public class ApprovalExpireScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpireScheduler.class);

    private final ApprovalService approvalService;

    /** 开关默认开：治理承诺件的常态是「在岗」。关停仅限迁移/演练窗口。 */
    @Value("${devops.ai.approval.expire-scan-enabled:true}")
    private boolean enabled;

    public ApprovalExpireScheduler(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void scanAndExpire() {
        if (!enabled) {
            return;
        }
        try {
            int n = approvalService.expireOverdue();
            // n>0 时 expireOverdue 内部已记 WARN（含超时时限），此处不重复刷屏
            if (n == 0) {
                log.debug("⏰ [ApprovalExpire] 扫描完成：无超时待审批单");
            }
        } catch (Exception e) {
            // 扫描器自身故障不能静默（AGENTS 静默 catch 契约）——但也绝不能
            // 把 @Scheduled 线程炸掉：fixedDelay 下一次仍会执行，单轮失败留痕即可
            log.error("❌ [ApprovalExpire] 超时扫描失败（下一轮 5 分钟后重试）", e);
        }
    }
}
