package com.devops.agent.domain.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具反射装配测试
 * <p>
 * 背景：{@code DevOpsTools} 的 @Tool 方法把实际逻辑委托给
 * {@code *Internal} 方法，再由 {@code ToolRuntimeManager} 反射调用，
 * 以便在中间插入幂等/超时/重试/熔断/审计等治理逻辑。
 * </p>
 * <p>
 * 这套装配有个隐蔽的失效模式：反射查找方法时若用
 * {@code getMethod()}，而目标方法是包级私有，会抛
 * {@code NoSuchMethodException} 并被包装成「工具执行失败」。
 * 后果是模型连续重试数次全部失败，最终给出不含知识库内容的
 * 泛泛回答——<b>用户完全无从察觉检索根本没跑通</b>。
 * </p>
 * <p>
 * 这类缺陷不会被直接调 Service 的集成测试发现（那绕过了工具层），
 * 只在真实对话中暴露。本测试专门锁定装配契约。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@DisplayName("工具反射装配：Internal 方法必须可被反射调用")
class DevOpsToolsReflectionTest {

    /** ToolRuntimeManager 反射调用的内部方法及其签名 */
    private static final Object[][] INTERNAL_METHODS = {
            {"searchDevOpsKnowledgeInternal", new Class<?>[]{String.class}}
    };

    @Test
    @DisplayName("所有 Internal 方法都能被 getDeclaredMethod 找到")
    void internalMethodsMustBeDiscoverable() {
        for (Object[] spec : INTERNAL_METHODS) {
            String name = (String) spec[0];
            Class<?>[] params = (Class<?>[]) spec[1];

            assertDoesNotThrow(
                    () -> DevOpsTools.class.getDeclaredMethod(name, params),
                    "找不到方法 " + name + "，ToolRuntimeManager 反射调用会失败，"
                            + "并被包装成「工具执行失败」——模型会重试数次全失败，"
                            + "最终给出不含知识库内容的回答且用户无从察觉");
        }
    }

    @Test
    @DisplayName("Internal 方法应为 public——getMethod 与 getDeclaredMethod 都能找到")
    void internalMethodsShouldBePublic() {
        for (Object[] spec : INTERNAL_METHODS) {
            String name = (String) spec[0];
            Class<?>[] params = (Class<?>[]) spec[1];

            // 即使调用方已改用 getDeclaredMethod，仍要求 public：
            // 这些方法是跨包被反射调用的协作点，可见性应与用途一致
            assertDoesNotThrow(
                    () -> DevOpsTools.class.getMethod(name, params),
                    name + " 不是 public。它被跨包反射调用，"
                            + "可见性应与用途一致，避免调用方改回 getMethod 时静默失效");
        }
    }

    @Test
    @DisplayName("@Tool 方法必须同时带 @ToolMeta——否则治理属性走默认值")
    void toolMethodsMustCarryMeta() {
        int toolCount = 0;
        for (Method m : DevOpsTools.class.getDeclaredMethods()) {
            if (m.getAnnotation(dev.langchain4j.agent.tool.Tool.class) == null) {
                continue;
            }
            toolCount++;
            ToolMeta meta = m.getAnnotation(ToolMeta.class);
            assertNotNull(meta,
                    "@Tool 方法 " + m.getName() + " 缺 @ToolMeta，"
                            + "风险等级/幂等/超时/补偿等治理属性会全部走默认值");
            assertFalse(meta.name().isBlank(), "@ToolMeta.name 不能为空，Saga 步骤登记依赖它");
        }
        assertTrue(toolCount >= 2, "应至少有检索与建单两个工具，实际 " + toolCount);
    }

    @Test
    @DisplayName("声明了 compensationAction 的工具，其补偿方法必须存在且签名正确")
    void compensationMethodsMustExist() {
        for (Method m : DevOpsTools.class.getDeclaredMethods()) {
            ToolMeta meta = m.getAnnotation(ToolMeta.class);
            if (meta == null || meta.compensationAction().isBlank()) {
                continue;
            }
            String action = meta.compensationAction();

            // SagaCompensationManager 约定：补偿方法签名为 (String) -> String
            Method compensation = assertDoesNotThrow(
                    () -> DevOpsTools.class.getMethod(action, String.class),
                    "工具 " + meta.name() + " 声明补偿动作 " + action
                            + "，但找不到该方法（签名须为 " + action + "(String)）。"
                            + "Saga 补偿会失败并标记 MANUAL_INTERVENTION_REQUIRED");

            assertEquals(String.class, compensation.getReturnType(),
                    "补偿方法 " + action + " 应返回 String（补偿结果描述）");
        }
    }
}