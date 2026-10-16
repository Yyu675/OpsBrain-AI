package com.devops.agent.domain.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略匹配逻辑测试。
 *
 * <h3>为什么这组测试格外重要</h3>
 * 匹配逻辑错了<b>不会抛异常、不会有任何界面表现</b>，只会让策略
 * 匹配到不该匹配的告警——然后 AI 对着一台不相干的服务器执行了重启。
 *
 * 而且两个方向的错误都很糟：
 * <ul>
 *   <li>匹配过宽 → 对不该动的目标动手（事故）</li>
 *   <li>匹配过窄 → 该自愈的没自愈，且没人知道（漏报）</li>
 * </ul>
 *
 * 所以下面对每个条件都同时覆盖「该匹配」与「不该匹配」两侧。
 */
@DisplayName("自动化策略匹配")
class AutomationPolicyMatchTest {

    private AutomationPolicy policy(String levels, String module,
                                    String servicePattern, String alertNamePattern) {
        AutomationPolicy p = new AutomationPolicy();
        p.setMatchAlertLevels(levels);
        p.setMatchModule(module);
        p.setMatchServicePattern(servicePattern);
        p.setMatchAlertNamePattern(alertNamePattern);
        return p;
    }

    @Nested
    @DisplayName("告警级别")
    class Levels {

        @Test
        @DisplayName("多级别逗号分隔，命中其一即可")
        void matchesAnyOfList() {
            AutomationPolicy p = policy("P0,P1", null, null, null);
            assertTrue(p.matchesLevel("P0"));
            assertTrue(p.matchesLevel("P1"));
            assertFalse(p.matchesLevel("P2"));
        }

        @Test
        @DisplayName("大小写与空格容错——多打一个空格不该让策略静默失效")
        void toleratesWhitespaceAndCase() {
            AutomationPolicy p = policy(" p2 , P3 ", null, null, null);
            assertTrue(p.matchesLevel("P2"));
            assertTrue(p.matchesLevel("p3"));
        }

        @Test
        @DisplayName("留空视为通配")
        void blankMeansWildcard() {
            assertTrue(policy(null, null, null, null).matchesLevel("P4"));
            assertTrue(policy("", null, null, null).matchesLevel("P4"));
        }

        @Test
        @DisplayName("有条件但告警级别为 null 时不匹配，不放行")
        void nullLevelDoesNotMatchWhenConstrained() {
            assertFalse(policy("P0", null, null, null).matchesLevel(null));
        }
    }

    @Nested
    @DisplayName("通配匹配（服务名 / 告警名）")
    class Wildcard {

        @Test
        @DisplayName("前缀通配 order-*")
        void prefixWildcard() {
            AutomationPolicy p = policy(null, null, "order-*", null);
            assertTrue(p.matchesService("order-service"));
            assertTrue(p.matchesService("order-api"));
            assertFalse(p.matchesService("payment-service"));
        }

        @Test
        @DisplayName("中缀与后缀通配")
        void midAndSuffixWildcard() {
            assertTrue(policy(null, null, "*-service", null).matchesService("order-service"));
            assertTrue(policy(null, null, "ns:*/pod", null).matchesService("ns:prod/pod"));
        }

        @Test
        @DisplayName("纯 * 与留空都是通配")
        void starMeansAll() {
            assertTrue(policy(null, null, "*", null).matchesService("anything"));
            assertTrue(policy(null, null, null, null).matchesService("anything"));
            assertTrue(policy(null, null, "  ", null).matchesService("anything"));
        }

        @Test
        @DisplayName("点号与短横线不被当成正则元字符")
        void escapesRegexMetaCharacters() {
            // 若不转义，'.' 会匹配任意字符，order.svc 就会误命中 orderXsvc
            AutomationPolicy p = policy(null, null, "order.svc", null);
            assertTrue(p.matchesService("order.svc"));
            assertFalse(p.matchesService("orderXsvc"),
                    "点号必须被转义，否则匹配范围远超预期");
        }

        @Test
        @DisplayName("正则元字符不会让匹配崩溃或意外放宽")
        void regexInjectionIsNeutralized() {
            AutomationPolicy p = policy(null, null, "a+b", null);
            assertTrue(p.matchesService("a+b"));
            assertFalse(p.matchesService("aaab"));
        }

        @Test
        @DisplayName("必须整串匹配，不做包含匹配")
        void requiresFullMatch() {
            AutomationPolicy p = policy(null, null, "order", null);
            assertTrue(p.matchesService("order"));
            assertFalse(p.matchesService("order-service"),
                    "不带 * 时应整串相等，否则用户以为限定了却圈进一批服务");
        }

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            assertTrue(policy(null, null, "Order-*", null).matchesService("order-svc"));
        }

        @Test
        @DisplayName("有模式但值为 null 时不匹配")
        void nullValueDoesNotMatch() {
            assertFalse(policy(null, null, "order-*", null).matchesService(null));
        }
    }

    @Nested
    @DisplayName("模块")
    class Module {

        @Test
        @DisplayName("精确匹配，大小写不敏感")
        void exactIgnoreCase() {
            AutomationPolicy p = policy(null, "K8S", null, null);
            assertTrue(p.matchesModule("k8s"));
            assertTrue(p.matchesModule(" K8S "));
            assertFalse(p.matchesModule("MYSQL"));
        }

        @Test
        @DisplayName("留空通配")
        void blankWildcard() {
            assertTrue(policy(null, null, null, null).matchesModule("ANYTHING"));
        }
    }

    @Nested
    @DisplayName("整体匹配：四个条件是「与」关系")
    class Combined {

        @Test
        @DisplayName("全部命中才算匹配")
        void allMustMatch() {
            AutomationPolicy p = policy("P3", "K8S", "order-*", "PodCrashLoopBackOff");

            assertTrue(p.matches("P3", "K8S", "order-service", "PodCrashLoopBackOff"));
            // 逐个破坏一个条件，都应不匹配
            assertFalse(p.matches("P2", "K8S", "order-service", "PodCrashLoopBackOff"));
            assertFalse(p.matches("P3", "MYSQL", "order-service", "PodCrashLoopBackOff"));
            assertFalse(p.matches("P3", "K8S", "payment-svc", "PodCrashLoopBackOff"));
            assertFalse(p.matches("P3", "K8S", "order-service", "OOMKilled"));
        }

        @Test
        @DisplayName("多加一个条件只会收窄，不会放宽——这是「与」而非「或」的意义")
        void addingConditionNarrows() {
            AutomationPolicy loose = policy("P3", null, null, null);
            AutomationPolicy tight = policy("P3", "K8S", null, null);

            assertTrue(loose.matches("P3", "MYSQL", "svc", "X"));
            assertFalse(tight.matches("P3", "MYSQL", "svc", "X"),
                    "用「或」会让加条件反而变宽，是配置系统里最反直觉的错误");
        }

        @Test
        @DisplayName("全部留空则匹配一切（Service 层会拒绝这种配置）")
        void allBlankMatchesEverything() {
            AutomationPolicy p = policy(null, null, null, null);
            assertTrue(p.matches("P0", "ANY", "any-svc", "AnyAlert"));
        }
    }
}
