package com.devops.agent.domain.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险策略领域逻辑测试。
 *
 * <p>重点覆盖爆炸半径计算与环境匹配——这两处的错误不会抛异常，
 * 只会让安全约束「看起来生效了但实际算错了」，是最难发现的一类缺陷。</p>
 */
@DisplayName("风险等级策略")
class RiskPolicyTest {

    private RiskPolicy policy(int percent, int count) {
        RiskPolicy p = new RiskPolicy();
        p.setMaxBlastRadiusPercent(percent);
        p.setMaxBlastRadiusCount(count);
        return p;
    }

    @Nested
    @DisplayName("爆炸半径")
    class BlastRadius {

        @Test
        @DisplayName("百分比与绝对值取较小值——只按百分比会让大集群一次挂太多")
        void takesSmallerOfPercentAndCount() {
            // 1000 实例 * 5% = 50，但绝对值上限 1 → 取 1
            assertEquals(1, policy(5, 1).resolveBlastRadius(1000));
            // 20 实例 * 20% = 4，绝对值上限 10 → 取 4
            assertEquals(4, policy(20, 10).resolveBlastRadius(20));
        }

        @Test
        @DisplayName("算出 0 时兜底为 1——静默空转比执行 1 个更糟")
        void neverZeroWhenInstancesExist() {
            // 10 实例 * 5% = 0.5，向下取整为 0
            assertEquals(1, policy(5, 5).resolveBlastRadius(10));
            assertEquals(1, policy(1, 5).resolveBlastRadius(3));
        }

        @Test
        @DisplayName("不超过实例总数——3 个实例不能重启 5 个")
        void cappedByTotalInstances() {
            assertEquals(3, policy(100, 9999).resolveBlastRadius(3));
        }

        @Test
        @DisplayName("实例数为 0 或负数时返回 0，不兜底为 1")
        void zeroWhenNoInstances() {
            assertEquals(0, policy(100, 9999).resolveBlastRadius(0));
            assertEquals(0, policy(100, 9999).resolveBlastRadius(-1));
        }

        @Test
        @DisplayName("绝对值上限为 0 时按 1 处理，不让配置错误变成永不执行")
        void treatsZeroCountAsOne() {
            assertEquals(1, policy(100, 0).resolveBlastRadius(50));
        }
    }

    @Nested
    @DisplayName("环境匹配")
    class Environments {

        private RiskPolicy withEnvironments(String envs) {
            RiskPolicy p = new RiskPolicy();
            p.setAllowedEnvironments(envs);
            return p;
        }

        @Test
        @DisplayName("大小写与空格不影响判定——多打一个空格不该让安全限制失效")
        void toleratesWhitespaceAndCase() {
            RiskPolicy p = withEnvironments(" Prod , staging ");
            assertTrue(p.allowsEnvironment("prod"));
            assertTrue(p.allowsEnvironment("PROD"));
            assertTrue(p.allowsEnvironment(" staging "));
        }

        @Test
        @DisplayName("不做前缀匹配——prod 不应命中 production 这类相近名")
        void requiresExactMatch() {
            RiskPolicy p = withEnvironments("prod");
            assertFalse(p.allowsEnvironment("production"));
            assertFalse(p.allowsEnvironment("pro"));
        }

        @Test
        @DisplayName("空配置 / null 输入一律拒绝，不默认放行")
        void deniesOnEmpty() {
            assertFalse(withEnvironments("").allowsEnvironment("prod"));
            assertFalse(withEnvironments(null).allowsEnvironment("prod"));
            assertFalse(withEnvironments("prod").allowsEnvironment(null));
            assertFalse(withEnvironments("prod").allowsEnvironment("  "));
        }
    }

    @Nested
    @DisplayName("完全自治判定")
    class FullyAutonomous {

        @Test
        @DisplayName("需同时满足「免审批」与「允许自动执行」")
        void requiresBothConditions() {
            RiskPolicy p = new RiskPolicy();
            p.setApprovalMode(ApprovalMode.NONE);
            p.setAutoExecuteAllowed(true);
            assertTrue(p.isFullyAutonomous());

            p.setAutoExecuteAllowed(false);
            assertFalse(p.isFullyAutonomous(), "禁止自动执行时不算自治");

            p.setAutoExecuteAllowed(true);
            p.setApprovalMode(ApprovalMode.SINGLE);
            assertFalse(p.isFullyAutonomous(), "需要审批时不算自治");
        }

        @Test
        @DisplayName("审批模式缺失时判定为非自治，不因空值放行")
        void deniesOnNullMode() {
            RiskPolicy p = new RiskPolicy();
            p.setAutoExecuteAllowed(true);
            p.setApprovalMode(null);
            assertFalse(p.isFullyAutonomous());
        }
    }
}
