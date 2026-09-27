package com.devops.agent.application.runtime;

import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketPostmortemRepository;
import com.devops.agent.domain.notify.Notifier;
import com.devops.agent.domain.notify.NotifyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 复盘缺失提醒（飞轮治理，BUG.md R3 的落点）。
 *
 * <p>
 * 背景：真实库里复盘完成率长期 0%——工单结了案，知识飞轮的入口却没人走。
 * 指标（效能大盘的复盘完成率卡）只是让这个数字可见，可见不等于被处理。
 * 本调度器把它变成主动提醒：有完结单但复盘为 0 时，每天提醒一次。
 * </p>
 *
 * <p>设计基调：
 * <ul>
 *   <li>只在「该有而没有」时提醒：无完结单（系统还很新）或已有复盘，都静默；</li>
 *   <li>固定日节奏而非每轮扫描都喊——提醒的价值在于被看见，刷屏等于没有提醒；</li>
 *   <li>失败静默（通知是旁路），扫描异常不外抛（保调度器存活）。</li>
 * </ul>
 */
@Component
public class PostmortemReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(PostmortemReminderScheduler.class);

    private final DevOpsTicketRepository ticketRepository;
    private final TicketPostmortemRepository postmortemRepository;
    private final Notifier notifier;

    /** 总开关：新部署环境（还没有完结单）时免提醒 */
    @Value("${devops.postmortem.reminder-enabled:true}")
    private boolean enabled;

    /** 复盘率低于该百分比才提醒（默认 0：有一份复盘就不再喊） */
    @Value("${devops.postmortem.reminder-below-percent:0}")
    private double belowPercent;

    public PostmortemReminderScheduler(DevOpsTicketRepository ticketRepository,
                                       TicketPostmortemRepository postmortemRepository,
                                       Notifier notifier) {
        this.ticketRepository = ticketRepository;
        this.postmortemRepository = postmortemRepository;
        this.notifier = notifier;
    }

    /** 每日一次（默认 09:10 本地时间，错开整点）：复盘缺失是慢病，不需要分钟级盯。 */
    @Scheduled(cron = "${devops.postmortem.reminder-cron:0 10 9 * * *}")
    public void remind() {
        if (!enabled) {
            return;
        }
        try {
            long finished = ticketRepository.countFinished();
            if (finished == 0) {
                return;   // 还没有完结单——系统很新，不是复盘缺失
            }
            long postmortems = postmortemRepository.countAll();
            double rate = postmortems * 100.0 / finished;
            if (rate > belowPercent) {
                return;   // 有人在复盘，不打扰
            }
            String title = "📝 复盘缺失提醒";
            String md = "### " + title + "\n\n"
                    + "- **已完结工单**：" + finished + " 张\n"
                    + "- **已复盘**：" + postmortems + " 份（完成率 " + String.format("%.1f", rate) + "%）\n"
                    + "- **影响**：处理经验没有沉淀，同样的故障下次还得从头排查\n"
                    + "- **入口**：效能大盘「复盘完成率」卡片可查看欠账清单\n";
            notifier.send(NotifyMessage.normal(title, md));
            log.warn("📝 [PostmortemReminder] 复盘缺失提醒已发送 | finished={} | postmortems={}", finished, postmortems);
        } catch (Exception e) {
            log.error("❌ [PostmortemReminder] 提醒扫描异常: {}", e.getMessage(), e);
        }
    }
}
