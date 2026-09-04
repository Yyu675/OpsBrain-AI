package com.devops.agent.domain.notify;

/**
 * 通知发送统一接口（可插拔通知渠道，2026-08-26）。
 *
 * <h3>为什么要有这层接口</h3>
 * 此前调用方直接注入 {@code DingTalkNotifier} 这个<b>具体厂商实现</b>，
 * 共 4 处（TicketService / AlertService / ApprovalOrchestrator /
 * FirstResponseBreachScheduler）。后果是「换个通知渠道」这件小事，
 * 要改 4 个与通知无关的业务类——而它们本该只关心「发一条通知」，
 * 不关心发到钉钉还是 Slack。
 *
 * <p>抽出接口后，换渠道只需新增一个实现类 + 改一行配置，
 * 业务代码一行不动。这正是「模块可插拔」在本项目里的最小实证。</p>
 *
 * <h3>迁移到其它项目时怎么用</h3>
 * <ul>
 *   <li><b>技术栈一致（Spring Boot）</b>：直接复制
 *       {@code Notifier} + {@code NotifyMessage} + 具体实现类，
 *       接口无任何本项目专有依赖（不依赖工单、告警、用户等领域概念）；</li>
 *   <li><b>技术栈不一致</b>：迁移的是<b>这三条设计约定</b>，
 *       而不是代码——见下方「实现约定」。它们是本项目踩坑后固化的结论，
 *       用任何语言重写都适用。</li>
 * </ul>
 *
 * <h3>实现约定（三条，都由真实事故反推）</h3>
 * <ol>
 *   <li><b>永不抛异常</b>。通知是旁路能力，发失败不能影响主流程。
 *       工单已经建好了，却因为钉钉限流而让整个建单接口报错，
 *       是本末倒置；</li>
 *   <li><b>异步发送</b>。调用方常是告警回调线程或定时扫描线程，
 *       同步发送会让一次 8 秒的网络超时拖住整批告警处理；</li>
 *   <li><b>未配置时降级为日志</b>，而不是静默丢弃。
 *       开发期需要看见「本该发出什么内容、在什么时机发」，
 *       静默会让通知逻辑的错误一直到上线才暴露。</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-08-26
 */
public interface Notifier {

    /**
     * 渠道标识，如 dingtalk / slack / teams / webhook。
     *
     * <p>用途：多渠道并存时按配置选用；日志与审计里标明「发到了哪」。</p>
     */
    String channel();

    /**
     * 发送通知。
     *
     * <p><b>实现必须保证本方法不抛异常</b>（约定 1）——
     * 调用方不做 try-catch，也不该做。</p>
     *
     * @param message 通知内容（与渠道无关的中性模型）
     */
    void send(NotifyMessage message);

    /**
     * 该渠道当前是否真正可用（已开启且配置完整）。
     *
     * <p>默认 true。用于「配了多个渠道，挑一个能用的」这类场景；
     * 也便于健康检查区分「没配」与「配了但连不通」。</p>
     */
    default boolean available() {
        return true;
    }
}
