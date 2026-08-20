package com.devops.agent.application.runtime;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 首响超时扫描（B1：闭环阶段 3）
 * <p>
 * <b>背景</b>：PRD §2.3 要求「超时自动升级」。B0 已按优先级冻结
 * {@code response_deadline}（P0 15min / P1 30min / P2 4h / P3 24h），
 * 但没有扫描就没人发现超时——deadline 只是个躺在库里的时间戳。
 * </p>
 *
 * <p><b>设计决策</b>：
 * <ul>
 *   <li><b>扫描频率 2 分钟</b>：P0 首响时限仅 15 分钟，若按常规的 5~10 分钟扫描，
 *       最坏情况下超时后 10 分钟才被发现，占了时限的 2/3，告警失去意义。
 *       2 分钟是「及时性」与「DB 压力」的折中（部分索引 + LIMIT，单次扫描极轻）。</li>
 *   <li><b>只标记不改优先级/不换负责人</b>：自动提优先级会连带改写 SLA 时限，
 *       绕过人的判断；自动换负责人可能把工单甩给不懂该系统的人。
 *       按 6.3 决策（高风险需人工审批），L1 阶段只记录 + 告警，
 *       升级动作由人通过 {@code POST /tickets/{id}/escalate} 显式发起。</li>
 *   <li><b>超时事实固化持久化</b>（{@code response_breached=TRUE}）而非每次实时算：
 *       超时是既成事实，一旦发生就该留痕。若实时判断，事后补了首响会让
 *       历史超时记录凭空消失，考核数据可被「补操作」洗白。</li>
 *   <li><b>幂等</b>：SQL 带 {@code response_breached = FALSE AND first_response_at IS NULL}
 *       条件，重复扫描不会重复告警。</li>
 *   <li><b>单批上限</b>：一次最多处理 {@code MAX_BATCH} 条。积压场景下避免
 *       单次事务过长阻塞其它写操作；剩余的下一轮（2 分钟后）继续。</li>
 * </ul>
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
@Component
public class FirstResponseBreachScheduler {

    private static final Logger log = LoggerFactory.getLogger(FirstResponseBreachScheduler.class);

    /** 单批处理上限，防止积压时单次事务过长 */
    private static final int MAX_BATCH = 100;

    private final DevOpsTicketRepository ticketRepository;
    private final TicketService ticketService;
    private final com.devops.agent.domain.notify.DingTalkNotifier dingTalkNotifier;

    /**
     * 开关：允许运维在数据迁移或压测期间临时停用，
     * 避免把大批历史工单一次性标成超时污染看板
     */
    @Value("${devops.ticket.response-breach-scan-enabled:true}")
    private boolean scanEnabled;

    public FirstResponseBreachScheduler(DevOpsTicketRepository ticketRepository,
                                        TicketService ticketService,
                                        com.devops.agent.domain.notify.DingTalkNotifier dingTalkNotifier) {
        this.ticketRepository = ticketRepository;
        this.ticketService = ticketService;
        this.dingTalkNotifier = dingTalkNotifier;
    }

    /**
     * 每 2 分钟扫描首响超时工单
     * <p>
     * 固定延迟（fixedDelay）而非固定频率（fixedRate）：上一轮未跑完时不叠加，
     * 避免积压场景下多轮并发扫描同一批数据。
     * </p>
     */
    @Scheduled(fixedDelayString = "${devops.ticket.response-breach-scan-interval-ms:120000}")
    public void scanResponseBreach() {
        if (!scanEnabled) {
            return;
        }
        try {
            List<DevOpsTicket> candidates = ticketRepository.findResponseBreachCandidates(MAX_BATCH);
            if (candidates.isEmpty()) {
                return;   // 常态无输出，避免每 2 分钟刷一条无用日志
            }

            int marked = 0;
            for (DevOpsTicket t : candidates) {
                try {
                    // 幂等：已标记的返回 0
                    if (ticketRepository.markResponseBreached(t.getId()) > 0) {
                        marked++;
                        recordAndNotify(t);
                    }
                } catch (Exception e) {
                    // 单条失败不中断整批——一张坏数据不应让其它超时工单也发现不了
                    log.error("❌ [FirstResponseScan] 标记超时失败 | ticketId={} | {}",
                            t.getId(), e.getMessage());
                }
            }

            if (marked > 0) {
                log.warn("⏰ [FirstResponseScan] 首响超时 {} 张（本批候选 {} 张）",
                        marked, candidates.size());
                if (candidates.size() >= MAX_BATCH) {
                    // 如实告知被截断，避免「扫描过了」的假象（6.25 无声上限契约）
                    log.warn("⚠️ [FirstResponseScan] 本批已达上限 {}，剩余将在下一轮处理", MAX_BATCH);
                }
            }
        } catch (Exception e) {
            // 定时任务异常不能向上抛——会导致 Spring 停止后续调度
            log.error("❌ [FirstResponseScan] 扫描异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 留痕 + 钉钉通知
     * <p>
     * 活动流由 Service 单点写（6.12 契约），此处调 Service 而非直写仓储。
     * </p>
     * <p>
     * <b>不走 WebSocket 推送</b>：{@code /ws/alerts} 的事件契约（6.35）固定为
     * 12 字段 {@code AlertPayload}，把工单数据塞进告警通道会破坏该契约。
     * 首响超时的可见性由 ① 活动流留痕 ② {@code GET /tickets/sla/at-risk} 清单
     * ③ 列表首响状态列 ④ <b>钉钉通知（方向二）</b> 四处提供。
     * </p>
     * <p>
     * 钉钉通知为旁路：{@code DingTalkNotifier} 内部异步 + 失败仅 WARN，
     * 不影响超时标记与活动流留痕主流程。P0/P1 工单超时 @所有人强提醒。
     * </p>
     */
    private void recordAndNotify(DevOpsTicket t) {
        Long overdue = t.getResponseRemainingMinutes();
        String detail = "优先级 " + t.getPriority()
                + "，首响时限 " + t.getSla()
                + (overdue != null ? "，已超时 " + Math.abs(overdue) + " 分钟" : "");
        try {
            ticketService.recordActivity(t.getId(), "warning", "首响超时", detail, "系统", true);
        } catch (Exception e) {
            log.warn("⚠️ [FirstResponseScan] 活动流留痕失败 | ticketId={} | {}", t.getId(), e.getMessage());
        }
        // L2 钉钉通知（方向二）：SLA 首响超时提醒。P0/P1 强提醒值班 SRE。
        try {
            String priority = t.getPriority();
            boolean high = "P0".equalsIgnoreCase(priority) || "P1".equalsIgnoreCase(priority);
            String title = "⏰ 首响超时 " + priority + " · " + t.getTitle();
            String md = "### " + title + "\n\n"
                    + "- **工单**：" + t.getId() + "\n"
                    + "- **优先级**：" + priority + "\n"
                    + "- **首响时限**：" + t.getSla() + "\n"
                    + (overdue != null ? "- **已超时**：" + Math.abs(overdue) + " 分钟\n" : "")
                    + "- **负责人**：" + (t.getAssignee() != null ? t.getAssignee() : "待分配") + "\n";
            dingTalkNotifier.send(high
                    ? com.devops.agent.domain.notify.NotifyMessage.urgent(title, md)
                    : com.devops.agent.domain.notify.NotifyMessage.normal(title, md));
        } catch (Exception e) {
            log.warn("⚠️ [FirstResponseScan] 钉钉通知构造失败（已忽略）| ticketId={} | {}", t.getId(), e.getMessage());
        }
    }
}
