package com.devops.agent.contract;

import com.devops.agent.domain.governance.AutomationPolicy;
import com.devops.agent.domain.governance.GovernanceViews;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 治理模块响应 record 的字段契约（P0-2b 第三步）。
 *
 * <h3>这组断言防什么</h3>
 * <p>
 * 治理模块配置的是「AI 能不能自动动生产系统」的边界。record 的字段名
 * <b>就是 JSON 的键</b>，前端 {@code governance.ts} 逐个读取——
 * 改名不会有编译错误（后端自己编译得过），<b>只是前端悄悄拿到 undefined</b>：
 * 「已启用的高危动作数」显示为 0 时，管理员看到的风险敞口是假的。
 * </p>
 *
 * <h3>两类保护</h3>
 * <ol>
 *   <li><b>字段名冻结</b>（反射断言）——跨端的名字约定；</li>
 *   <li><b>线上报文形态</b>（Jackson 序列化断言）——Map 时代
 *       「拒绝时不带约束字段」「未命中行不带 outcome」的条件省略
 *       由 {@code @JsonInclude(NON_NULL)} 精确复刻；
 *       而 {@code firstEffective} 的 null <b>不省略</b>。
 *       这些是 Map 时代就被前端依赖的报文形态，换 record 不许悄悄变。</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-09-07
 */
@DisplayName("治理响应 record 字段契约")
class GovernanceDtoContractTest {

    private static List<String> fieldNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    @Nested
    @DisplayName("字段名与顺序：与前端 governance.ts 的冻结约定")
    class FrozenFields {

        /**
         * 每一个 entry：record 类 → 前端逐字段读取的键。
         * 来源：{@code devops-platform-frontend/src/api/governance.ts} 的
         * {@code PagedResult / ActionStats / PolicyStats / RiskPolicyPage
         * / ActionFilterOptions / EvaluateResult / SimulatedRow / SimulateResult}。
         */
        static Stream<Map.Entry<Class<?>, List<String>>> frozenShapes() {
            return Stream.of(
                    Map.entry(GovernanceViews.ActionPage.class,
                            List.of("items", "total", "page", "size", "totalPages")),
                    Map.entry(GovernanceViews.AutomationPolicyPage.class,
                            List.of("items", "total", "page", "size", "totalPages")),
                    Map.entry(GovernanceViews.ActionStats.class,
                            List.of("total", "enabledCount", "highRiskEnabled", "prodEnabled")),
                    Map.entry(GovernanceViews.PolicyStats.class,
                            List.of("total", "enabledCount", "dryRunCount",
                                    "liveCount", "prodLiveCount")),
                    Map.entry(GovernanceViews.RiskPolicyOverview.class,
                            List.of("items", "approvalModes", "escalateTargets")),
                    Map.entry(GovernanceViews.ApprovalModeOption.class,
                            List.of("value", "label", "requiredApprovers")),
                    Map.entry(GovernanceViews.EscalateTargetOption.class,
                            List.of("value", "label")),
                    Map.entry(GovernanceViews.RiskLevelOption.class,
                            List.of("value", "label", "description")),
                    Map.entry(GovernanceViews.ActionFilterOptions.class,
                            List.of("categories", "riskLevels", "environments",
                                    "knownCategories")),
                    Map.entry(GovernanceViews.EvaluateResult.class,
                            List.of("actionKey", "environment", "allowed", "reason",
                                    "requiresApproval", "approvalMode",
                                    "blastRadiusCount", "cooldownSeconds")),
                    Map.entry(GovernanceViews.SimulateInput.class,
                            List.of("level", "module", "service", "alertName",
                                    "environment")),
                    Map.entry(GovernanceViews.SimulatedRow.class,
                            List.of("policyId", "policyName", "priority", "actionKey",
                                    "dryRun", "matched", "skipped", "reason",
                                    "outcome", "actionVerdict")),
                    Map.entry(GovernanceViews.SimulateResult.class,
                            List.of("input", "evaluated", "matchedCount",
                                    "firstEffective", "summary")),
                    Map.entry(GovernanceViews.DeleteResult.class,
                            List.of("id", "deleted"))
            );
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.MethodSource("frozenShapes")
        @DisplayName("record 字段序列化为前后端冻结的 JSON 键")
        void fieldNamesAreFrozen(Map.Entry<Class<?>, List<String>> shape) {
            assertThat(fieldNames(shape.getKey()))
                    .as("%s 的字段名就是响应 JSON 的键，前端 governance.ts 逐字段读取。"
                            + "改名不会有编译错误（后端自己编译得过），"
                            + "只是前端悄悄拿到 undefined——治理页显示的风险敞口是假的",
                            shape.getKey().getSimpleName())
                    .containsExactlyElementsOf(shape.getValue());
        }
    }

    @Nested
    @DisplayName("EvaluateResult：拒绝与放行的报文形态")
    class EvaluateWireShape {

        private final ObjectMapper om = new ObjectMapper();

        @Test
        @DisplayName("拒绝时省略四个约束字段 —— 不能暗示「补上审批就能放行」")
        void denyOmitsConstraintFields() {
            JsonNode deny = om.valueToTree(
                    GovernanceViews.EvaluateResult.deny("k8s.pod.restart", "prod", "未登记"));

            assertThat(deny.has("requiresApproval")).as("拒绝时报文不得带 requiresApproval").isFalse();
            assertThat(deny.has("approvalMode")).as("拒绝时报文不得带 approvalMode").isFalse();
            assertThat(deny.has("blastRadiusCount")).as("拒绝时报文不得带 blastRadiusCount").isFalse();
            assertThat(deny.has("cooldownSeconds")).as("拒绝时报文不得带 cooldownSeconds").isFalse();
            // 始终在场的四个键：前端 eval-result 面板靠它们渲染
            assertThat(deny.get("actionKey").asText()).isEqualTo("k8s.pod.restart");
            assertThat(deny.get("environment").asText()).isEqualTo("prod");
            assertThat(deny.get("allowed").asBoolean()).isFalse();
            assertThat(deny.get("reason").asText()).isEqualTo("未登记");
        }

        @Test
        @DisplayName("放行时必须带全约束字段 —— 前端详情面板逐个展示")
        void allowCarriesConstraintFields() {
            JsonNode allow = om.valueToTree(
                    GovernanceViews.EvaluateResult.allow(
                            "k8s.pod.restart", "prod", Boolean.TRUE, "SINGLE", 3, 60));

            assertThat(allow.get("allowed").asBoolean()).isTrue();
            assertThat(allow.get("reason").asText()).isEqualTo("允许自动执行");
            assertThat(allow.get("requiresApproval").asBoolean()).isTrue();
            assertThat(allow.get("approvalMode").asText()).isEqualTo("SINGLE");
            assertThat(allow.get("blastRadiusCount").asInt()).isEqualTo(3);
            assertThat(allow.get("cooldownSeconds").asInt()).isEqualTo(60);
        }

        @Test
        @DisplayName("deny 工厂不得产出 allowed=true —— 方向反了等于安全开关说谎")
        void denyFactoryNeverAllows() {
            GovernanceViews.EvaluateResult deny =
                    GovernanceViews.EvaluateResult.deny("any", "prod", "某原因");
            assertThat(deny.allowed())
                    .as("deny 工厂如果产出 allowed=true，前端会显示「允许自动执行」"
                            + "而原因文案是拒绝——安全配置页面公然撒谎")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("SimulatedRow：三种行的语义不得混用")
    class SimulatedRowShape {

        private final ObjectMapper om = new ObjectMapper();

        private AutomationPolicy policy() {
            AutomationPolicy p = new AutomationPolicy();
            p.setId(1L);
            p.setName("重启 Pod");
            p.setPriority(10);
            p.setActionKey("k8s.pod.restart");
            p.setDryRun(true);
            return p;
        }

        @Test
        @DisplayName("「未被求值」不是「未匹配」——排查方向完全不同的两件事")
        void skippedIsNotUnmatched() {
            GovernanceViews.SimulatedRow skipped =
                    GovernanceViews.SimulatedRow.skipped(policy(), "前序已命中即停");

            // matched=false/skipped=true 的分叉是页面的渲染依据：
            // 显示「未被求值」还是「未命中」全看这两个布尔
            assertThat(skipped.matched()).isFalse();
            assertThat(skipped.skipped()).isTrue();
        }

        @Test
        @DisplayName("未命中行不携带 outcome / actionVerdict —— 报文中省略")
        void unmatchedOmitsOutcomeAndVerdict() {
            JsonNode row = om.valueToTree(
                    GovernanceViews.SimulatedRow.unmatched(policy(), "级别要求 P3，实际 P0"));

            assertThat(row.has("outcome")).as("未命中行不得带 outcome").isFalse();
            assertThat(row.has("actionVerdict")).as("未命中行不得带 actionVerdict").isFalse();
            assertThat(row.get("matched").asBoolean()).isFalse();
            assertThat(row.get("skipped").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("命中行携带结论与动作判定")
        void matchedCarriesOutcomeAndVerdict() {
            GovernanceViews.EvaluateResult verdict =
                    GovernanceViews.EvaluateResult.allow(
                            "k8s.pod.restart", "dev", Boolean.FALSE, "NONE", 5, 60);
            JsonNode row = om.valueToTree(
                    GovernanceViews.SimulatedRow.matched(policy(), "策略命中，将直接自动执行",
                            "EXECUTE", verdict));

            assertThat(row.get("matched").asBoolean()).isTrue();
            assertThat(row.get("outcome").asText()).isEqualTo("EXECUTE");
            assertThat(row.path("actionVerdict").path("allowed").asBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("SimulateResult：firstEffective 的 null 不省略")
    class SimulateResultWireShape {

        private final ObjectMapper om = new ObjectMapper();

        @Test
        @DisplayName("无命中时 firstEffective 键保留为 null —— Map 时代就是这个形态")
        void nullFirstEffectiveKeyStays() {
            GovernanceViews.SimulateResult r = new GovernanceViews.SimulateResult(
                    new GovernanceViews.SimulateInput("P3", "K8S", "svc", "Alert", "dev"),
                    List.of(), 0L, null,
                    "没有任何启用中的策略匹配该告警，将走默认流程（自动建单，人工处理）");

            JsonNode n = om.valueToTree(r);

            // Map 时代 result.put("firstEffective", null) 会产出 "firstEffective": null。
            // 前端按 simResult.firstEffective ? ... : ... 判断，键缺失误判成「加载中」
            assertThat(n.has("firstEffective")).isTrue();
            assertThat(n.get("firstEffective").isNull()).isTrue();
            assertThat(n.get("matchedCount").asLong()).isEqualTo(0L);
        }
    }
}
