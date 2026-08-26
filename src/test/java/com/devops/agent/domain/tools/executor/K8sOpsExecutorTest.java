package com.devops.agent.domain.tools.executor;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.FakeK8sClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * K8s 执行器适配器测试（V1.2，D1 适配器模式）。
 *
 * <p>双轨验证轨道 A：用 fabric8 官方 {@link FakeK8sClient} 模拟集群，
 * CI 无需真实 K8s 即可验证执行器行为（真实集群演示走轨道 B：本机 kind）。</p>
 *
 * <p>覆盖：只读查询成功 / Pod 不存在 / 未注册目标系统报错 / 注册表自注册。</p>
 */
@DisplayName("K8sOpsExecutor（V1.2 执行器适配器）")
class K8sOpsExecutorTest {

    private KubernetesClient fakeClient;
    private K8sOpsExecutor executor;

    /** 测试用执行器：注入 FakeK8sClient（替代生产懒加载的真实 client） */
    private static final class TestK8sExecutor extends K8sOpsExecutor {
        private final KubernetesClient injected;

        TestK8sExecutor(OpsExecutorRegistry registry, KubernetesClient injected) {
            super(registry);
            this.injected = injected;
        }

        // 通过反射覆盖 client()——测试友好：不依赖 ~/.kube/config
        java.lang.reflect.Field clientField() throws Exception {
            java.lang.reflect.Field f = K8sOpsExecutor.class.getDeclaredField("client");
            f.setAccessible(true);
            return f;
        }
    }

    private OpsExecutorRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        fakeClient = new FakeK8sClient();
        registry = new OpsExecutorRegistry();
        TestK8sExecutor e = new TestK8sExecutor(registry, fakeClient);
        e.clientField().set(e, fakeClient); // 注入 fake client，跳过真实集群初始化
        executor = e;
    }

    @AfterEach
    void tearDown() {
        fakeClient.close();
    }

    @Test
    @DisplayName("注册表自注册：构造后 registry 可解析 k8s")
    void selfRegistered() {
        assertTrue(registry.has("k8s"));
        assertEquals(executor, registry.resolve("k8s"));
    }

    @Test
    @DisplayName("queryPodStatus：查询存在的 Pod 返回结构化状态")
    void queryPodStatusOk() {
        // 准备 fake 集群中的 Pod
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("payment-service-6f9d8").withNamespace("prod").endMetadata()
                .withNewSpec().withNodeName("node-03").endSpec()
                .withStatus(new PodStatusBuilder()
                        .withPhase("Running")
                        .withPodIP("10.0.3.21")
                        .addNewContainerStatus()
                        .withName("payment-service")
                        .withReady(true)
                        .withRestartCount(3)
                        .withImage("payment-service:v2.4.1")
                        .endContainerStatus()
                        .build())
                .build();
        fakeClient.pods().inNamespace("prod").resource(pod).create();

        OpsExecutionResult result = executor.execute(OpsCommand.of(
                "k8s", "queryPodStatus",
                Map.of("namespace", "prod", "pod", "payment-service-6f9d8")));

        assertTrue(result.isSuccess());
        assertEquals("Running", result.data().get("phase"));
        assertEquals(3, result.data().get("restartCount"));
        assertEquals("node-03", result.data().get("nodeName"));
        assertTrue(result.summary().contains("Running"));
    }

    @Test
    @DisplayName("queryPodStatus：Pod 不存在返回失败而非异常")
    void queryPodStatusNotFound() {
        OpsExecutionResult result = executor.execute(OpsCommand.of(
                "k8s", "queryPodStatus",
                Map.of("namespace", "prod", "pod", "not-exist")));
        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().contains("Pod 不存在"));
    }

    @Test
    @DisplayName("queryPodStatus：缺少必填参数抛 IllegalArgumentException")
    void queryPodStatusMissingParam() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> executor.execute(OpsCommand.of("k8s", "queryPodStatus", Map.of("namespace", "prod"))));
    }

    @Test
    @DisplayName("不支持的动作返回明确错误")
    void unsupportedAction() {
        OpsExecutionResult result = executor.execute(OpsCommand.of("k8s", "dropDatabase", Map.of()));
        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().contains("不支持动作"));
    }
}
