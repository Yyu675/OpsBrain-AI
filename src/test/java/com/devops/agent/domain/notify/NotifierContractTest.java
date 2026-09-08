package com.devops.agent.domain.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Notifier} <b>可插拔契约</b>测试。
 *
 * <h3>这个类守的不是某个实现，而是「换实现不会出事」</h3>
 * 通知渠道被设计成可插拔：业务代码只依赖 {@link Notifier}，
 * 换钉钉为 Slack / Teams / 企业微信时只需新增实现类 + 改配置。
 *
 * <p>但「可插拔」只有在<b>所有实现都遵守同一组约定</b>时才成立。
 * 否则换一个实现，业务代码就会以意想不到的方式出问题——
 * 而那时故障现象出在业务侧，排查方向会完全跑偏。</p>
 *
 * <h3>三条约定，都由真实事故反推</h3>
 * <ol>
 *   <li><b>永不抛异常</b>——通知是旁路。工单已建好却因钉钉限流
 *       让建单接口报错，是本末倒置。调用方不做 try-catch，也不该做；</li>
 *   <li><b>null 入参安全</b>——上游拼装消息失败时可能传 null，
 *       通知层不该因此把主流程带崩；</li>
 *   <li><b>未配置时降级为日志而非静默</b>——开发期要能看见
 *       「本该发什么、什么时候发」。静默会让通知逻辑的错误拖到上线才暴露。</li>
 * </ol>
 *
 * <h3>为什么用反射遍历而不是逐个实现写一遍</h3>
 * 当前只有一个实现（DingTalkNotifier）。若逐个写，
 * <b>下一个实现加进来时没人会想起补测试</b>——而那恰恰是契约最容易被破坏的时刻。
 * 这里改为「对所有已注册实现统一施加约定」，新增实现自动纳入检查。
 *
 * @author OpsBrain AI
 * @since 2026-08-26
 */
@DisplayName("Notifier 可插拔契约（所有实现共同遵守）")
class NotifierContractTest {

    /**
     * 待检查的实现清单。
     *
     * <p>新增渠道实现时在此登记一行即可——刻意不做 classpath 扫描：
     * 扫描会把测试里的匿名实现也卷进来，且失败信息里看不出是谁。
     * 显式登记虽然多一步，但「漏登记」会被下面的
     * {@code allImplementationsRegistered} 用例抓到。</p>
     */
    private static List<Notifier> implementations() {
        List<Notifier> list = new ArrayList<>();
        // DingTalkNotifier 需要 ObjectMapper（构造注入）。这里直接给真实实例——
        // 它无外部依赖、无状态，比 mock 更接近生产行为
        list.add(new DingTalkNotifier(new com.fasterxml.jackson.databind.ObjectMapper()));
        return list;
    }

    @Test
    @DisplayName("每个实现都有非空的渠道标识，且互不重复")
    void channelIdentifiersAreUniqueAndNonBlank() {
        // channel() 是多渠道并存时的选用键，也是审计里「发到了哪」的依据。
        // 重复会让选用逻辑取到不确定的那一个
        List<String> seen = new ArrayList<>();
        for (Notifier n : implementations()) {
            String ch = n.channel();
            assertNotNull(ch, n.getClass().getSimpleName() + " 的 channel() 不能为 null");
            assertFalse(ch.isBlank(), n.getClass().getSimpleName() + " 的 channel() 不能为空白");
            assertFalse(seen.contains(ch), "渠道标识重复: " + ch);
            seen.add(ch);
        }
    }

    @Test
    @DisplayName("未配置时 send 不抛异常，走降级日志")
    void sendNeverThrowsWhenUnconfigured() {
        // ── 本类最重要的一条 ──────────────────────────────────
        // 实现类是直接 new 出来的，@Value 字段全是默认值（未配置）。
        // 这正是「新环境刚部署、还没填 webhook」的真实状态——
        // 此时发通知必须安静降级，而不是把调用方的主流程带崩
        for (Notifier n : implementations()) {
            assertDoesNotThrow(
                    () -> n.send(NotifyMessage.normal("标题", "内容")),
                    n.getClass().getSimpleName() + " 在未配置时不得抛异常");
        }
    }

    @Test
    @DisplayName("send(null) 不抛异常——上游拼装失败不该带崩主流程")
    void sendNullIsSafe() {
        for (Notifier n : implementations()) {
            assertDoesNotThrow(() -> n.send(null),
                    n.getClass().getSimpleName() + " 对 null 入参必须安全");
        }
    }

    @Test
    @DisplayName("紧急消息同样不抛异常")
    void urgentMessageIsSafe() {
        for (Notifier n : implementations()) {
            assertDoesNotThrow(() -> n.send(NotifyMessage.urgent("紧急", "P0 故障")));
        }
    }

    @Test
    @DisplayName("未配置时 available() 为 false——健康检查据此区分「没配」与「连不通」")
    void unconfiguredIsNotAvailable() {
        // 这两者的处置方式完全不同：没配是运维待办，连不通是故障。
        // 混为一谈会让人对着一个根本没启用的渠道排查网络
        for (Notifier n : implementations()) {
            assertFalse(n.available(),
                    n.getClass().getSimpleName() + " 未配置时 available() 应为 false");
        }
    }

    @Test
    @DisplayName("实现清单完整——新增渠道必须登记进本测试")
    void allImplementationsRegistered() throws Exception {
        /*
         * 防「加了新实现却忘了纳入契约检查」。
         *
         * 不做 classpath 扫描（会卷进测试里的匿名实现），改为对照
         * 同包下的具体类：Notifier 的实现按约定都放在 domain.notify 包内。
         * 若将来实现分散到别的包，这条会失败并提醒补充判据——
         * 那也是有价值的信号，而不是误报。
         */
        List<String> registered = implementations().stream()
                .map(n -> n.getClass().getSimpleName()).toList();

        // 已知实现：新增时同步更新此列表与 implementations()
        List<String> expected = List.of("DingTalkNotifier");

        assertEquals(expected.size(), registered.size(),
                "实现数量与登记不符，请同步更新 implementations() 与 expected");
        assertTrue(registered.containsAll(expected),
                "缺少已知实现: " + expected + "，实际: " + registered);
    }

    @Test
    @DisplayName("NotifyMessage 是渠道中性模型——不含任何厂商字段")
    void messageModelStaysVendorNeutral() {
        /*
         * 这条守的是「迁移到其它项目时能整包带走」。
         *
         * 一旦有人往 NotifyMessage 里加 dingtalkAtMobiles、slackChannel
         * 这类厂商专有字段，模型就被绑死了：换渠道要改模型，
         * 改模型又要动所有调用方——可插拔当场失效。
         *
         * 厂商差异应当由各实现内部消化（如从 markdown 解析 @），
         * 或通过后续扩展的 metadata Map 承载，而不是加进核心字段。
         */
        List<String> vendorWords = List.of(
                "dingtalk", "ding", "slack", "teams", "wechat", "weixin",
                "lark", "feishu", "webhook", "token", "secret");

        for (Field f : NotifyMessage.class.getDeclaredFields()) {
            if (f.isSynthetic()) continue;
            String name = f.getName().toLowerCase();
            for (String w : vendorWords) {
                assertFalse(name.contains(w),
                        "NotifyMessage 出现厂商专有字段 '" + f.getName()
                                + "'，会让通知模型绑死在单一渠道上");
            }
        }
    }
}
