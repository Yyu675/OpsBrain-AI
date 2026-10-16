package com.devops.agent.domain.tools.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行器注册表单元测试（D1 适配器模式骨架，2026-08-26）。
 *
 * <p>覆盖：注册/解析/未注册报错/空命令参数校验/结果工厂。</p>
 */
@DisplayName("OpsExecutorRegistry（D1 执行器适配器骨架）")
class OpsExecutorRegistryTest {

    /** 测试用假执行器（模拟 K8s 只读动作） */
    private static final class FakeK8sExecutor implements OpsExecutor {
        @Override public String targetSystem() { return "k8s"; }
        @Override public Set<String> supportedActions() { return Set.of("queryPodStatus"); }
        @Override public OpsExecutionResult execute(OpsCommand command) {
            if (!supportedActions().contains(command.action())) {
                return OpsExecutionResult.fail("不支持动作: " + command.action());
            }
            return OpsExecutionResult.ok(
                    "Pod 状态查询完成",
                    Map.of("phase", "Running", "restartCount", 3, "age", "2d"));
        }
    }

    @Test
    @DisplayName("注册与解析：按 targetSystem 取回对应执行器")
    void registerAndResolve() {
        OpsExecutorRegistry registry = new OpsExecutorRegistry();
        FakeK8sExecutor k8s = new FakeK8sExecutor();
        registry.register(k8s);

        assertTrue(registry.has("k8s"));
        assertEquals(k8s, registry.resolve("k8s"));
        assertEquals(Set.of("k8s"), registry.registeredSystems());
    }

    @Test
    @DisplayName("未注册目标系统：抛明确错误而非静默")
    void resolveUnregisteredThrows() {
        OpsExecutorRegistry registry = new OpsExecutorRegistry();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.resolve("aliyun"));
        assertTrue(e.getMessage().contains("未配置目标系统执行器: aliyun"));
    }

    @Test
    @DisplayName("注册空 targetSystem：拒绝")
    void registerBlankTargetThrows() {
        OpsExecutorRegistry registry = new OpsExecutorRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(new OpsExecutor() {
            @Override public String targetSystem() { return " "; }
            @Override public Set<String> supportedActions() { return Set.of(); }
            @Override public OpsExecutionResult execute(OpsCommand command) { return null; }
        }));
    }

    @Test
    @DisplayName("后注册同 targetSystem 覆盖（测试可注入 Fake）")
    void reRegisterOverrides() {
        OpsExecutorRegistry registry = new OpsExecutorRegistry();
        registry.register(new FakeK8sExecutor());
        OpsExecutor replacement = new FakeK8sExecutor();
        registry.register(replacement);
        assertEquals(replacement, registry.resolve("k8s"));
    }

    @Test
    @DisplayName("OpsCommand 必填参数校验")
    void commandRequireParam() {
        OpsCommand cmd = OpsCommand.of("k8s", "queryPodStatus", Map.of("namespace", "prod"));
        assertEquals("prod", cmd.requireParam("namespace"));
        assertThrows(IllegalArgumentException.class, () -> cmd.requireParam("pod"));
        assertFalse(cmd.params().containsKey("pod"));
    }

    @Test
    @DisplayName("OpsExecutionResult 工厂与执行器集成")
    void resultFactory() {
        OpsExecutor k8s = new FakeK8sExecutor();
        OpsExecutionResult ok = k8s.execute(OpsCommand.of("k8s", "queryPodStatus", Map.of("namespace", "prod")));
        assertTrue(ok.isSuccess());
        assertEquals("Running", ok.data().get("phase"));

        OpsExecutionResult fail = k8s.execute(OpsCommand.of("k8s", "dropDatabase"));
        assertFalse(fail.isSuccess());
        assertTrue(fail.errorMessage().contains("不支持动作"));
    }
}
