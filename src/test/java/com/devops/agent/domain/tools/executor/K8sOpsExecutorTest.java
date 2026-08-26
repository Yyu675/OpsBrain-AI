package com.devops.agent.domain.tools.executor;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * K8s 执行器适配器测试（V1.2，D1 适配器模式）。
 *
 * <p>双轨验证轨道 A（CI）：Mockito 深度 mock {@link KubernetesClient} 调用链
 * （pods → inNamespace → withName → get），不依赖真实集群、零额外测试依赖。</p>
 *
 * <p>轨道 B（演示）：本机 kind + 真实 kubeconfig 走同一执行器，验证真实集群行为。</p>
 *
 * <p>覆盖：只读查询成功 / Pod 不存在 / 参数缺失 / 不支持动作 / 注册表自注册。</p>
 */
@DisplayName("K8sOpsExecutor（V1.2 执行器适配器）")
class K8sOpsExecutorTest {

    private KubernetesClient fakeClient;
    private OpsExecutorRegistry registry;
    private K8sOpsExecutor executor;

    /** 测试用执行器：注入 mock client（跳过生产懒加载的真实集群初始化） */
    private static final class TestK8sExecutor extends K8sOpsExecutor {
        private final KubernetesClient injected;

        TestK8sExecutor(OpsExecutorRegistry registry, KubernetesClient injected) {
            super(registry);
            this.injected = injected;
        }

        @Override
        protected KubernetesClient resolveClient() {
            return injected;
        }
    }

    @BeforeEach
    void setUp() {
        fakeClient = mock(KubernetesClient.class);
        registry = new OpsExecutorRegistry();
        executor = new TestK8sExecutor(registry, fakeClient);
    }

    // ==================== Mock 链装配 ====================

    @SuppressWarnings("unchecked")
    private void stubPodLookup(Pod pod) {
        MixedOperation<Pod, ?, ?, ?, ?> pods = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, ?, ?> ns = mock(NonNamespaceOperation.class);
        PodResource<Pod> resource = mock(PodResource.class);

        when(fakeClient.pods()).thenReturn(pods);
        when(pods.inNamespace(anyString())).thenReturn(ns);
        when(ns.withName(anyString())).thenReturn(resource);
        when(resource.get()).thenReturn(pod);
    }

    private Pod runningPod() {
        return new PodBuilder()
                .withNewMetadata().withName("payment-service-6f9d8").withNamespace("prod").endMetadata()
                .withSpec(new PodSpecBuilder().withNodeName("node-03").build())
                .withStatus(new PodStatusBuilder()
                        .withPhase("Running")
                        .withPodIP("10.0.3.21")
                        .withContainerStatuses(new ContainerStatusBuilder()
                                .withName("payment-service")
                                .withReady(true)
                                .withRestartCount(3)
                                .withImage("payment-service:v2.4.1")
                                .build())
                        .build())
                .build();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("注册表自注册：构造后 registry 可解析 k8s")
    void selfRegistered() {
        assertTrue(registry.has("k8s"));
        assertEquals(executor, registry.resolve("k8s"));
    }

    @Test
    @DisplayName("queryPodStatus：查询存在的 Pod 返回结构化状态")
    void queryPodStatusOk() {
        stubPodLookup(runningPod());

        OpsExecutionResult result = executor.execute(OpsCommand.of(
                "k8s", "queryPodStatus",
                Map.of("namespace", "prod", "pod", "payment-service-6f9d8")));

        assertTrue(result.isSuccess());
        assertEquals("Running", result.data().get("phase"));
        assertEquals(3, result.data().get("restartCount"));
        assertEquals("node-03", result.data().get("nodeName"));
        assertTrue(result.summary().contains("Running"));
        // 容器状态列表
        @SuppressWarnings("unchecked")
        var containers = (java.util.List<Map<String, Object>>) result.data().get("containers");
        assertEquals(1, containers.size());
        assertEquals("payment-service", containers.get(0).get("name"));
    }

    @Test
    @DisplayName("queryPodStatus：Pod 不存在返回失败而非异常")
    void queryPodStatusNotFound() {
        stubPodLookup(null);

        OpsExecutionResult result = executor.execute(OpsCommand.of(
                "k8s", "queryPodStatus",
                Map.of("namespace", "prod", "pod", "not-exist")));
        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().contains("Pod 不存在"));
    }

    @Test
    @DisplayName("queryPodStatus：缺少必填参数抛 IllegalArgumentException")
    void queryPodStatusMissingParam() {
        assertThrows(IllegalArgumentException.class,
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
