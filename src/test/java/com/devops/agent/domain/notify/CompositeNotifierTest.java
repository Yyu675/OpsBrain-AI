package com.devops.agent.domain.notify;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link CompositeNotifier} 测试 —— 守住「通知渠道可插拔」在<b>装配层面</b>成立。
 *
 * <h3>为什么已有 NotifierContractTest 还需要这一个</h3>
 * {@code NotifierContractTest} 守的是<b>行为</b>约定（不抛异常、null 安全、
 * 未配置降级），它从不启动 Spring。因此它抓不到本类要修的那类缺陷：
 *
 * <p>在 {@code CompositeNotifier} 出现之前，5 处业务类注入的是<b>单个</b>
 * {@code Notifier}，而唯一实现 {@code DingTalkNotifier} 是裸
 * {@code @Component}——没有 {@code @Primary} 也没有 {@code @Conditional}。
 * 于是「新增 SlackNotifier」这个本该零风险的动作，实际后果是
 * <b>Spring 启动即失败</b>（{@code NoUniqueBeanDefinitionException}），
 * 且报错指向 TicketService 的构造函数，排查方向会跑偏到工单模块去。</p>
 *
 * <p>纯单元测试永远发现不了这件事：它自己 new 对象，根本不经过依赖解析。
 * 所以下面的 {@link Assembly} 用真实（但极小）的 Spring 容器验证装配，
 * 而不是靠注释提醒后人。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-14
 */
@DisplayName("CompositeNotifier 多渠道分发")
class CompositeNotifierTest {

    /**
     * 可控的假渠道。
     *
     * <p>不用 Mockito：这里需要的是「记录收到了什么」+「按需抛异常」，
     * 手写十行比 mock 的 stubbing 更直观，失败时也更容易看出是哪个渠道。</p>
     */
    static class FakeNotifier implements Notifier {
        private final String channel;
        private final boolean available;
        private final boolean throwOnSend;
        final List<NotifyMessage> received = new ArrayList<>();

        FakeNotifier(String channel, boolean available, boolean throwOnSend) {
            this.channel = channel;
            this.available = available;
            this.throwOnSend = throwOnSend;
        }

        static FakeNotifier ready(String channel) {
            return new FakeNotifier(channel, true, false);
        }

        static FakeNotifier unconfigured(String channel) {
            return new FakeNotifier(channel, false, false);
        }

        static FakeNotifier broken(String channel) {
            return new FakeNotifier(channel, true, true);
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void send(NotifyMessage message) {
            if (throwOnSend) throw new IllegalStateException("模拟渠道故障: " + channel);
            received.add(message);
        }
    }

    private static final NotifyMessage MSG = NotifyMessage.normal("标题", "正文");

    @Nested
    @DisplayName("分发")
    class Dispatch {

        @Test
        @DisplayName("发到所有可用渠道，而不是只发第一个")
        void dispatchesToEveryAvailableChannel() {
            /*
             * 这是选 Composite 而非「给某个实现打 @Primary」的根本原因。
             * P0 告警既要进钉钉群也要进值班短信，二者不是替代关系——
             * 只发第一个渠道的话，另一条路的人永远收不到，
             * 而配置填得好好的、日志里也没有异常。
             */
            FakeNotifier a = FakeNotifier.ready("dingtalk");
            FakeNotifier b = FakeNotifier.ready("slack");

            new CompositeNotifier(List.of(a, b)).send(MSG);

            assertThat(a.received).containsExactly(MSG);
            assertThat(b.received).containsExactly(MSG);
        }

        @Test
        @DisplayName("跳过未配置的渠道，不影响已配置的")
        void skipsUnavailableChannels() {
            // 真实场景：钉钉配好了，Slack 只加了依赖还没填 webhook
            FakeNotifier configured = FakeNotifier.ready("dingtalk");
            FakeNotifier notConfigured = FakeNotifier.unconfigured("slack");

            new CompositeNotifier(List.of(notConfigured, configured)).send(MSG);

            assertThat(configured.received).containsExactly(MSG);
            assertThat(notConfigured.received).isEmpty();
        }

        @Test
        @DisplayName("单个渠道抛异常，其余渠道照常送达")
        void oneChannelFailureDoesNotBlockOthers() {
            /*
             * 逐个 try-catch 而非整体包一层的理由就在这条用例里。
             *
             * 整体包一层的话，排在前面的渠道抛异常会让后面的渠道
             * 一条都发不出去。而通知的全部价值在于「至少有一条路送达」——
             * 尤其在 P0 告警场景，钉钉挂了正是最需要短信的时候。
             */
            FakeNotifier broken = FakeNotifier.broken("dingtalk");
            FakeNotifier healthy = FakeNotifier.ready("sms");

            CompositeNotifier composite = new CompositeNotifier(List.of(broken, healthy));

            assertThatCode(() -> composite.send(MSG)).doesNotThrowAnyException();
            assertThat(healthy.received)
                    .as("前一个渠道故障不得阻断后续渠道")
                    .containsExactly(MSG);
        }

        @Test
        @DisplayName("异常不外抛——通知是旁路，不能带崩建单/告警主流程")
        void neverThrowsToCaller() {
            // Notifier 约定 1。本类是所有业务代码的唯一入口，
            // 这里漏一个异常，影响的是建单、告警、审批全部主链路
            CompositeNotifier onlyBroken = new CompositeNotifier(List.of(FakeNotifier.broken("x")));

            assertThatCode(() -> onlyBroken.send(MSG)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("无渠道 / 无可用渠道时安静降级，不抛异常")
        void degradesWhenNothingAvailable() {
            // 新环境刚部署、还没填任何 webhook 是常态
            assertThatCode(() -> new CompositeNotifier(List.of()).send(MSG))
                    .doesNotThrowAnyException();
            assertThatCode(() -> new CompositeNotifier(
                    List.of(FakeNotifier.unconfigured("slack"))).send(MSG))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null 入参安全——上游拼装失败不该带崩主流程")
        void nullMessageIsSafe() {
            FakeNotifier a = FakeNotifier.ready("dingtalk");
            CompositeNotifier composite = new CompositeNotifier(List.of(a));

            assertThatCode(() -> composite.send(null)).doesNotThrowAnyException();
            assertThat(a.received).as("null 消息不该被转发给渠道").isEmpty();
        }

        @Test
        @DisplayName("构造参数为 null 时退化为空渠道，不 NPE")
        void nullDelegatesTolerated() {
            assertThatCode(() -> new CompositeNotifier(null).send(MSG))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("元信息")
    class Meta {

        @Test
        @DisplayName("channel() 固定为 composite，不随配置变化")
        void channelIsStable() {
            /*
             * 刻意不拼接下游渠道名（如 "dingtalk+slack"）：
             * 本值会进审计与日志，拼接后它随配置变化，
             * 「按渠道统计发送量」这类查询会失去稳定的分组键。
             */
            assertThat(new CompositeNotifier(List.of(FakeNotifier.ready("dingtalk"))).channel())
                    .isEqualTo("composite");
            assertThat(new CompositeNotifier(List.of(
                    FakeNotifier.ready("dingtalk"), FakeNotifier.ready("slack"))).channel())
                    .isEqualTo("composite");
        }

        @Test
        @DisplayName("任一渠道可用即为可用；全不可用则为 false")
        void availabilityReflectsDelegates() {
            // 健康检查据此区分「一个都没配」（运维待办）与「配了但连不通」（故障）
            assertThat(new CompositeNotifier(List.of(
                    FakeNotifier.unconfigured("a"), FakeNotifier.ready("b"))).available())
                    .isTrue();
            assertThat(new CompositeNotifier(List.of(
                    FakeNotifier.unconfigured("a"), FakeNotifier.unconfigured("b"))).available())
                    .isFalse();
            assertThat(new CompositeNotifier(List.of()).available()).isFalse();
        }
    }

    /**
     * 装配测试 —— 本文件存在的主要理由。
     *
     * <p>用真实 Spring 容器（而非 {@code @SpringBootTest}，那会拉起数据库与
     * Redis，几秒起步且与本关注点无关）验证两件<b>只有容器才能证明</b>的事。</p>
     */
    @Nested
    @DisplayName("Spring 装配")
    class Assembly {

        @Configuration
        static class TwoChannels {
            @Bean
            CompositeNotifier compositeNotifier(List<Notifier> delegates) {
                return new CompositeNotifier(delegates);
            }

            @Bean
            FakeNotifier dingtalk() {
                return FakeNotifier.ready("dingtalk");
            }

            @Bean
            FakeNotifier slack() {
                return FakeNotifier.ready("slack");
            }
        }

        @Test
        @DisplayName("注入 List<Notifier> 不会把自己收进去（否则无限递归）")
        void doesNotSelfInject() {
            /*
             * CompositeNotifier 自身是 Notifier 实现，却注入 List<Notifier>。
             * 它不成环靠的是 Spring 4.3+ 在解析集合类型依赖时**排除自身**——
             * 这是框架行为，不是本类代码里能看出来的。
             *
             * 必须有测试兜住：一旦将来换 DI 框架、或有人改成手工
             * new CompositeNotifier(allNotifiers)，自引用会让 send() 无限递归
             * 直到 StackOverflowError。而那个报错栈是几千行同一个方法，
             * 看不出根因是「装配把自己收进去了」。
             */
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(TwoChannels.class)) {
                CompositeNotifier composite = ctx.getBean(CompositeNotifier.class);

                // 能正常发完即证明未成环（成环会 StackOverflowError）
                assertThatCode(() -> composite.send(MSG)).doesNotThrowAnyException();

                assertThat(ctx.getBean("dingtalk", FakeNotifier.class).received)
                        .containsExactly(MSG);
                assertThat(ctx.getBean("slack", FakeNotifier.class).received)
                        .containsExactly(MSG);
            }
        }

        @Test
        @DisplayName("多渠道并存时，注入单个 Notifier 仍能唯一解析")
        void singleNotifierInjectionStaysUnambiguous() {
            /*
             * 这正是 CompositeNotifier 要修的原始缺陷。
             *
             * 5 处业务类注入的是单个 Notifier。在没有 @Primary 的年代，
             * 只要存在第二个实现，这 5 个注入点会同时报
             * NoUniqueBeanDefinitionException——而错误信息指向业务类构造函数，
             * 让人以为是工单/告警模块的依赖有问题。
             *
             * 这里用两个假渠道 + 带 @Primary 的 Composite 模拟「多渠道并存」，
             * 断言按类型取单个 Notifier 拿到的是 Composite。
             */
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(PrimaryComposite.class)) {
                Notifier injected = ctx.getBean(Notifier.class);

                assertThat(injected)
                        .as("业务方注入单个 Notifier 应拿到 Composite（而非某个具体渠道）")
                        .isInstanceOf(CompositeNotifier.class);
                assertThat(injected.channel()).isEqualTo("composite");
            }
        }

        /**
         * 与 {@link TwoChannels} 的差别只在 {@code @Primary}。
         *
         * <p>分成两个配置类而非复用：这一条要验证的恰恰是 {@code @Primary}
         * 的存在使「按类型注入单个」不再有歧义。若与上面共用配置，
         * 把 {@code @Primary} 删掉后上一条用例仍会通过，
         * 这一条的保护作用就成了摆设。</p>
         */
        @Configuration
        static class PrimaryComposite {
            @Bean
            @org.springframework.context.annotation.Primary
            CompositeNotifier compositeNotifier(List<Notifier> delegates) {
                return new CompositeNotifier(delegates);
            }

            @Bean
            FakeNotifier dingtalk() {
                return FakeNotifier.ready("dingtalk");
            }

            @Bean
            FakeNotifier slack() {
                return FakeNotifier.ready("slack");
            }
        }
    }
}
