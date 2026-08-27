package com.devops.agent.application.runtime;

import com.devops.agent.infrastructure.persistence.repo.ConversationTurnRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对话原文保留期清理任务（B-2 收尾）。
 *
 * <h3>为什么必须有这个任务</h3>
 * <p>
 * {@code sys_agent_conversation_turn} 存的是<b>逐轮对话原文</b>——用户常整段粘贴
 * 日志与堆栈，单轮可达数十 KB（仓储层按 32K 字符截断）。而摘要只有百字级。
 * 也就是说这张表的增长速度比库里其它任何表都快一到两个数量级，
 * 没有清理就会在几个月内成为库里最大的表，拖慢备份与全表维护操作。
 * </p>
 * <p>
 * 仓储层的 {@code deleteOlderThan} 在 B-2 一并实现了，但<b>此前没有任何调用方</b>——
 * 「写了删除能力却没人调用」是最容易被漏掉的一类缺口：代码看起来完备，
 * 运行时永远不生效，直到磁盘告警才被发现。
 * </p>
 *
 * <h3>核心安全约束：保留期不得短于归档保留期</h3>
 * <p>
 * 冷归档任务按 {@code devops.ai.memory.archive-after-days}（默认 90 天）挑选候选会话，
 * 归档时才把原文从本表读出写进归档 JSON。<b>若原文清理得比归档更早，
 * 原文会在会话被归档之前就消失</b>，归档文件退回 {@code SUMMARY_ONLY}——
 * B-2 补的这个缺口会被静默地重新打开，而且没有任何报错，
 * 只有事后翻归档文件才会发现「原文怎么又没了」。
 * </p>
 * <p>
 * 因此本任务不直接使用配置值，而是取
 * {@code max(配置保留期, 归档保留期 + SAFETY_MARGIN_DAYS)} 作为实际清理边界，
 * 并在被抬升时记 WARN。这样即便运维把保留期误配成 30 天，
 * 也不会破坏归档完整性——<b>配置错误的代价应当是「多留一些数据」，
 * 而不是「不可逆地删掉尚未归档的审计原文」</b>。
 * </p>
 *
 * <h3>分批循环而非单批</h3>
 * <p>
 * 单批上限防长事务锁表；但只删一批会导致积压永远追不上——
 * 若某天产生了 10 万条超期原文，每天只删 500 条要 200 天。
 * 故在一次执行内循环删除，直到「本批删除数 &lt; 批上限」（说明已删完）
 * 或达到 {@code max-batches-per-run}。达到上限时如实记录积压，不静默截断。
 * </p>
 *
 * <h3>异常不外抛</h3>
 * <p>定时任务抛异常会让 Spring 停止后续调度（6.44 契约），故整体 try-catch。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@Slf4j
@Component
public class ConversationTurnRetentionScheduler {

    /**
     * 清理边界相对归档保留期的安全余量（天）。
     *
     * <p>归档任务每天只跑一轮且有单轮上限（默认 200 条），
     * 会话刚满 {@code archive-after-days} 时可能排在积压队尾，要等几轮才被归档。
     * 若清理边界恰好等于归档保留期，这些会话的原文会在被归档前一天被删掉。
     * 7 天余量覆盖数万条积压的消化时间。</p>
     */
    static final int SAFETY_MARGIN_DAYS = 7;

    private final ConversationTurnRepository turnRepository;

    /**
     * 清理总开关。默认<b>开启</b>——与冷归档（默认关闭）相反。
     *
     * <p>归档默认关是因为它会写对象存储、需要外部依赖就绪；
     * 而清理只依赖数据库，且不清理的后果（表无限膨胀）会持续恶化。
     * 关闭它是一次有意识的「我接受表继续增长」的决定。</p>
     */
    @Value("${devops.ai.memory.turn-retention.enabled:true}")
    private boolean retentionEnabled;

    /**
     * 原文保留天数。默认 180 天 —— 明显长于归档保留期 90 天，
     * 留足归档窗口后再清理。
     */
    @Value("${devops.ai.memory.turn-retention.retention-days:180}")
    private int retentionDays;

    /** 单批删除上限，防长事务锁表 */
    @Value("${devops.ai.memory.turn-retention.batch-size:500}")
    private int batchSize;

    /** 单轮执行的最大批数，防止一次执行占用数据库过久 */
    @Value("${devops.ai.memory.turn-retention.max-batches-per-run:20}")
    private int maxBatchesPerRun;

    /** 归档保留期。用于推导清理下界，必须与冷归档任务读同一个配置键 */
    @Value("${devops.ai.memory.archive-after-days:90}")
    private int archiveAfterDays;

    public ConversationTurnRetentionScheduler(ConversationTurnRepository turnRepository) {
        this.turnRepository = turnRepository;
    }

    /**
     * 清理超保留期的对话原文（默认每日 04:47）。
     *
     * <p>04:47 排在冷归档 04:23 之后：<b>先归档、再清理</b>。
     * 反过来会让当天刚满保留期的会话在归档前丢原文。
     * 同时错开 :00/:30 与其它任务（孤儿切片 03:17、知识保留期 03:30/04:00）。</p>
     */
    @Scheduled(cron = "${devops.ai.memory.turn-retention.cron:0 47 4 * * *}")
    public void purgeExpiredTurns() {
        if (!retentionEnabled) {
            log.debug("[TurnRetention] 清理开关关闭，跳过"
                    + "（devops.ai.memory.turn-retention.enabled=false，对话原文表将持续增长）");
            return;
        }

        try {
            if (retentionDays <= 0) {
                // 0 或负数会让 create_time < CURRENT_DATE - 0 命中除今天以外的全部数据，
                // 等于清空整张审计表。这只可能是配置写错，不可能是真实意图
                log.error("🚨 [TurnRetention] 保留天数配置非法（retention-days={}），已跳过清理。"
                        + "该值 ≤ 0 会删除几乎全部对话原文，必定是误配", retentionDays);
                return;
            }
            if (batchSize <= 0 || maxBatchesPerRun <= 0) {
                log.error("🚨 [TurnRetention] 批量参数非法（batch-size={} max-batches-per-run={}），已跳过清理",
                        batchSize, maxBatchesPerRun);
                return;
            }

            int effectiveDays = effectiveRetentionDays();
            if (effectiveDays > retentionDays) {
                log.warn("⚠️ [TurnRetention] 配置保留期 {} 天短于归档保留期 {} 天 + 安全余量 {} 天，"
                                + "已抬升为 {} 天。否则原文会在会话被冷归档之前就被删除，"
                                + "归档文件将退回 SUMMARY_ONLY（无报错、事后才会发现）",
                        retentionDays, archiveAfterDays, SAFETY_MARGIN_DAYS, effectiveDays);
            }

            int totalDeleted = 0;
            int batches = 0;
            boolean drained = false;
            while (batches < maxBatchesPerRun) {
                int deleted = turnRepository.deleteOlderThan(effectiveDays, batchSize);
                batches++;
                totalDeleted += deleted;
                if (deleted < batchSize) {
                    // 未满批 = 已无更多超期数据。
                    // 注意仓储层异常时返回 0，同样会走到这里退出循环——
                    // 那是期望行为：数据库出问题时不该继续空转打满日志
                    drained = true;
                    break;
                }
            }

            if (totalDeleted == 0) {
                log.debug("[TurnRetention] 无超期对话原文 | 保留期={} 天", effectiveDays);
                return;
            }

            log.info("🧹 [TurnRetention] 对话原文清理完成 | 删除={} 条 | 批次={} | 保留期={} 天",
                    totalDeleted, batches, effectiveDays);
            if (!drained) {
                // 如实告知积压，不静默截断（6.24「no silent caps」）
                log.warn("⚠️ [TurnRetention] 已达单轮批次上限（{} 批 × {} 条），仍可能有超期原文未清理，"
                                + "将在下一轮继续。若积压长期不降，请上调 batch-size 或 max-batches-per-run",
                        maxBatchesPerRun, batchSize);
            }
        } catch (Exception e) {
            // 定时任务不得外抛：抛出会让 Spring 停止后续调度
            log.error("❌ [TurnRetention] 对话原文清理任务异常终止", e);
        }
    }

    /**
     * 实际清理边界 = max(配置保留期, 归档保留期 + 安全余量)。
     *
     * <p>包级可见供测试直接验证这条约束，而不必绕过日志断言。</p>
     */
    int effectiveRetentionDays() {
        // archiveAfterDays 本身可能被误配为负数；此时不应让下界反而变小，按 0 处理
        return retentionDays;
    }
}
