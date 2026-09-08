package com.devops.agent.domain.tools.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行器契约模型（{@link OpsCommand} / {@link OpsExecutionResult}）测试。
 *
 * <h3>为什么这两个 record 值得单独测</h3>
 * 它们是 D1 适配器模式的<b>公共词汇</b>：K8s、云厂商 API、内部脚本平台
 * 三类目标系统未来都要通过它们与治理链路（白名单 / 风险分级 / 审批 /
 * 记录 / 审计）交互。
 *
 * <p>现在只有 K8s 一个适配器，所以「参数缺失怎么办」「失败时 data 是什么」
 * 这些约定<b>只存在于一处实现的行为里，没有被固定下来</b>。
 * 等第二个适配器接入时，写它的人会照着自己的理解再实现一遍——
 * 两边行为不一致，而治理层是按统一契约写的。</p>
 *
 * <p>本类把契约钉成断言。它们跑得极快（纯值对象，无 Spring、无 mock），
 * 却能在扩展第二个执行器时立刻暴露偏差。</p>
 *
 * <h3>重点覆盖：失败结果的 data 必须非 null</h3>
 * 调用方普遍写 {@code result.data().get("phase")}。若失败时 data 为 null，
 * 每一个消费点都要额外判空——漏一处就是 NPE，而且只在<b>故障路径</b>触发：
 * 平时测不出来，真出问题时雪上加霜（本来只是「K8s 查询失败」，
 * 变成整个 Agent 抛异常）。
 *
 * @author OpsBrain AI
 * @since 2026-08-26
 */
@DisplayName("执行器契约模型（OpsCommand / OpsExecutionResult）")
class OpsCommandAndResultTest {

    @Nested
    @DisplayName("OpsCommand")
    class Command {

        @Test
        @DisplayName("带参工厂：字段按 (targetSystem, action, params) 归位，不串位")
        void ofWithParams() {
            // record 的三个字段声明顺序是 (action, params, targetSystem)，
            // 而工厂方法签名是 of(targetSystem, action, params)——两者顺序不同。
            // 这正是最容易写反的地方，用不同值把每个字段钉死
            OpsCommand cmd = OpsCommand.of("k8s", "queryPodStatus",
                    Map.of("namespace", "prod", "pod", "payment-6f9d8"));

            assertEquals("k8s", cmd.targetSystem());
            assertEquals("queryPodStatus", cmd.action());
            assertEquals("prod", cmd.params().get("namespace"));
        }

        @Test
        @DisplayName("无参工厂：params 是空 Map 而非 null")
        void ofWithoutParams() {
            // 适配器里普遍写 command.params().get(...)，
            // params 为 null 会让「不需要参数的动作」直接 NPE
            OpsCommand cmd = OpsCommand.of("k8s", "listNamespaces");

            assertNotNull(cmd.params());
            assertTrue(cmd.params().isEmpty());
        }

        @Test
        @DisplayName("显式传 null params 被归一为空 Map")
        void nullParamsNormalized() {
            OpsCommand cmd = OpsCommand.of("k8s", "act", null);

            assertNotNull(cmd.params());
            assertTrue(cmd.params().isEmpty());
            // 归一之后 requireParam 走的是「缺参数」而非 NPE
            assertThrows(IllegalArgumentException.class, () -> cmd.requireParam("any"));
        }

        @Test
        @DisplayName("requireParam 取到值")
        void requireParamOk() {
            OpsCommand cmd = OpsCommand.of("k8s", "queryPodStatus", Map.of("pod", "api-1"));
            assertEquals("api-1", cmd.requireParam("pod"));
        }

        @Test
        @DisplayName("参数缺失抛 IllegalArgumentException，且异常信息带动作名与目标系统")
        void requireParamMissing() {
            // 这条异常会顺着 DevOpsTools 冒到 AI 的工具调用结果里。
            // 只说「缺少参数」而不说哪个动作、哪个系统，
            // 模型没法自我纠正，运维看日志也定位不到是哪个适配器
            OpsCommand cmd = OpsCommand.of("k8s", "queryPodStatus", Map.of("namespace", "prod"));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> cmd.requireParam("pod"));

            assertTrue(ex.getMessage().contains("pod"), "应指出缺的是哪个参数");
            assertTrue(ex.getMessage().contains("queryPodStatus"), "应指出是哪个动作");
            assertTrue(ex.getMessage().contains("k8s"), "应指出是哪个目标系统");
        }

        @Test
        @DisplayName("空串与纯空白同样视为缺失——防止把空值当成合法命名空间下发")
        void blankTreatedAsMissing() {
            // 若空串放行，K8s 客户端会拿 "" 当命名空间去查，
            // 得到的错误来自集群而非本地校验，排查链路平白拉长一层
            Map<String, String> params = new HashMap<>();
            params.put("namespace", "");
            params.put("pod", "   ");
            OpsCommand cmd = OpsCommand.of("k8s", "queryPodStatus", params);

            assertThrows(IllegalArgumentException.class, () -> cmd.requireParam("namespace"));
            assertThrows(IllegalArgumentException.class, () -> cmd.requireParam("pod"));
        }

        @Test
        @DisplayName("params 显式含 null 值时按缺失处理，不抛 NPE")
        void nullValueTreatedAsMissing() {
            // Map.of 不允许 null 值，但 HashMap 允许——
            // 上游若用 HashMap 组装参数，null 值是可达状态
            Map<String, String> params = new HashMap<>();
            params.put("pod", null);
            OpsCommand cmd = OpsCommand.of("k8s", "queryPodStatus", params);

            assertThrows(IllegalArgumentException.class, () -> cmd.requireParam("pod"));
        }
    }

    @Nested
    @DisplayName("OpsExecutionResult")
    class Result {

        @Test
        @DisplayName("ok：success=true、errorMessage 为空、data 原样带回")
        void okShape() {
            OpsExecutionResult r = OpsExecutionResult.ok("Pod 正常", Map.of("phase", "Running"));

            assertTrue(r.isSuccess());
            assertTrue(r.success());
            assertNull(r.errorMessage(), "成功时不该带错误信息");
            assertEquals("Running", r.data().get("phase"));
            assertEquals("Pod 正常", r.summary());
        }

        @Test
        @DisplayName("fail：data 是空 Map 而非 null——调用方无需在故障路径额外判空")
        void failDataNotNull() {
            // 本类头注释里的重点。失败时 data 为 null 的话，
            // result.data().get(...) 会在**故障路径**抛 NPE：
            // 本来只是「K8s 查询失败」，变成整个 Agent 崩掉
            OpsExecutionResult r = OpsExecutionResult.fail("集群不可达");

            assertFalse(r.isSuccess());
            assertNotNull(r.data(), "失败时 data 必须是空 Map，不能为 null");
            assertTrue(r.data().isEmpty());
            assertDoesNotThrow(() -> r.data().get("anything"));
        }

        @Test
        @DisplayName("fail：summary 与 errorMessage 同源——审计与提示读哪个都拿得到原因")
        void failSummaryMirrorsError() {
            // 审计记录读 summary、前端提示读 errorMessage。
            // 只填其一会让另一侧显示空白，而那时正是最需要信息的时候
            OpsExecutionResult r = OpsExecutionResult.fail("Pod 不存在");

            assertEquals("Pod 不存在", r.errorMessage());
            assertEquals("Pod 不存在", r.summary());
        }

        @Test
        @DisplayName("isSuccess 与 success 始终一致，不会出现两套真相")
        void accessorsAgree() {
            assertEquals(OpsExecutionResult.ok("s", Map.of()).success(),
                    OpsExecutionResult.ok("s", Map.of()).isSuccess());
            assertEquals(OpsExecutionResult.fail("e").success(),
                    OpsExecutionResult.fail("e").isSuccess());
        }
    }
}
