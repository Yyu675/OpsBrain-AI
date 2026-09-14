package com.devops.agent.domain.notify;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 多渠道通知分发器 —— 让 {@link Notifier} 的「可插拔」从名义变成事实。
 *
 * <h3>它修的是什么</h3>
 * {@link Notifier} 的接口注释承诺「换渠道只需新增一个实现类 + 改一行配置，
 * 业务代码一行不动」。但在本类出现之前，这个承诺是<b>装配层面不成立的</b>：
 *
 * <ul>
 *   <li>5 处业务类（TicketService / AlertService / ApprovalOrchestrator /
 *       FirstResponseBreachScheduler / DiagnosisOrchestrator）注入的是
 *       <b>单个</b> {@code Notifier}；</li>
 *   <li>唯一实现 {@code DingTalkNotifier} 是裸 {@code @Component}，
 *       既没有 {@code @Primary} 也没有 {@code @ConditionalOnProperty}。</li>
 * </ul>
 *
 * 于是「新增一个 SlackNotifier」这个本该零风险的动作，实际后果是
 * <b>Spring 启动即失败</b>：{@code NoUniqueBeanDefinitionException}，
 * 5 个注入点同时报错。而错误信息指向业务类的构造函数，
 * 排查方向会跑到「TicketService 依赖有问题」上去，
 * 而真正的原因是通知层多了一个实现。
 *
 * <p><b>更隐蔽的一种失败</b>：如果有人为了让它启动而给某个实现随手加
 * {@code @Primary}，那么另一个渠道就永远收不到任何通知——
 * 配置填得好好的，日志里也没有任何异常，只是消息不到。</p>
 *
 * <h3>为什么用 Composite 而不是 @Primary + @Qualifier</h3>
 * 给单个实现打 {@code @Primary} 只能解决「启动不了」，解决不了
 * 「同时发到多个渠道」——而这恰恰是真实需求：P0 告警既要进钉钉群
 * 也要进值班短信，二者不是替代关系。让业务方用 {@code @Qualifier}
 * 逐个点名，等于把「发到哪」这个运维决策硬编码进业务代码，
 * 换渠道又要改 5 处，回到了抽接口之前的状态。
 *
 * <p>Composite 把「有哪些渠道、哪些当前可用」收敛到本类一处。
 * 业务方依旧注入单个 {@code Notifier}（拿到的是本类），
 * <b>5 个注入点一行都不用改</b>。</p>
 *
 * <h3>自引用不会成环</h3>
 * 本类自身是 {@code Notifier} 实现，却注入 {@code List<Notifier>}。
 * Spring 4.3 起在解析集合类型依赖时会<b>排除自身</b>，
 * 因此 {@code delegates} 里不会含本类，不会无限递归。
 * 这一点由 {@code CompositeNotifierTest} 的装配用例守着——
 * 它是靠框架行为成立的，值得有测试兜住而不是靠注释提醒。
 *
 * <h3>约定的承接</h3>
 * {@link Notifier} 的三条约定（永不抛异常 / 异步 / 未配置降级为日志）
 * 在本类同样成立，且本类还多担一条：<b>单个渠道失败不影响其它渠道</b>。
 * 逐个 try-catch 而非整体包一层——整体包一层的话，
 * 第一个渠道抛异常会让后面的渠道一条都发不出去，
 * 而通知的价值恰恰在于「至少有一条路送达」。
 *
 * @author OpsBrain AI
 * @since 2026-09-14
 */
@Component
@Primary
public class CompositeNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(CompositeNotifier.class);

    /**
     * 所有已注册的具体渠道实现（不含本类，见类注释「自引用不会成环」）。
     *
     * <p>不做 {@code enabled} 过滤：渠道是否启用由各实现的
     * {@link Notifier#available()} 在<b>每次发送时</b>回答。
     * 启动时过滤一次的话，运行期改配置（如补上 webhook）不会生效，
     * 而运维会以为「配了但没用」。</p>
     */
    private final List<Notifier> delegates;

    public CompositeNotifier(List<Notifier> delegates) {
        this.delegates = delegates == null ? List.of() : List.copyOf(delegates);
        log.info("📣 [Notify] 通知渠道已注册 {} 个: {}",
                this.delegates.size(),
                this.delegates.stream().map(Notifier::channel).toList());
    }

    /**
     * 渠道标识固定为 composite。
     *
     * <p>不拼接下游渠道名（如 {@code "dingtalk+slack"}）：本值会进审计与日志，
     * 拼接后它会随配置变化，让「按渠道统计发送量」这类查询失去稳定的分组键。
     * 真正发到哪由各 delegate 自己的 {@code channel()} 在发送日志里标明。</p>
     */
    @Override
    public String channel() {
        return "composite";
    }

    /**
     * 只要有任一渠道可用即为可用。
     *
     * <p>用于健康检查区分「一个渠道都没配」与「配了但连不通」——
     * 前者是运维待办，后者是故障，处置方式完全不同。</p>
     */
    @Override
    public boolean available() {
        return delegates.stream().anyMatch(Notifier::available);
    }

    /**
     * 分发到所有当前可用的渠道。
     *
     * <p>各 delegate 自身已保证异步且不抛异常（{@link Notifier} 约定 1、2），
     * 本方法仍逐个 try-catch：约定靠的是实现方自觉，
     * 而本类是<b>所有业务代码的唯一入口</b>——这里漏一个异常，
     * 影响的是建单、告警、审批全部主流程。防御的成本是三行，
     * 不防的代价是主链路被旁路能力带崩。</p>
     *
     * <p>无可用渠道时打一条 INFO 而非静默丢弃（约定 3）：
     * 新环境刚部署、还没填 webhook 是常态，此时需要能看见
     * 「本该发什么、什么时候发」，否则通知逻辑的错误会一直藏到上线。</p>
     */
    @Override
    public void send(NotifyMessage message) {
        if (message == null) return;

        boolean dispatched = false;
        for (Notifier delegate : delegates) {
            if (!delegate.available()) continue;
            dispatched = true;
            try {
                delegate.send(message);
            } catch (RuntimeException e) {
                // 单渠道失败不阻断其余渠道：通知的价值在「至少一条路送达」
                log.warn("⚠️ [Notify] 渠道发送异常（已隔离，其余渠道继续）| channel={} | {}",
                        delegate.channel(), e.getMessage());
            }
        }

        if (!dispatched) {
            log.info("🔕 [Notify] 无可用通知渠道，降级为日志 | title={} | urgent={}",
                    message.title(), message.urgent());
        }
    }
}
