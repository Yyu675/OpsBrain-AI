package com.devops.agent.domain.healing.k8s;

import com.devops.agent.domain.healing.ExecutionResult;
import com.devops.agent.domain.healing.HealingAction;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fabric8 首批执行器（§7.2）契约测试：DEEP_STUBS mock KubernetesClient，
 * 测试子类经 resolveClient() 接缝注入——CI 不需要真集群（轨道 A）。
 */
@DisplayName("K8s 自愈执行器（S3-2/§7.2：重启 + 扩缩容）")
class K8sHealingExecutorsTest {

    private static HealingAction action(String key, Map<String, Object> params) {
        return new HealingAction(key, "staging", "ns:staging/app",
                params, null, "auto", Instant.now());
    }

    /** 测试子类接缝：注入指定 client。 */
    private static K8sRestartPodExecutor restartWith(KubernetesClient client) {
        return new K8sRestartPodExecutor() {
            @Override
            protected KubernetesClient resolveClient() {
                return client;
            }
        };
    }

    private static K8sScaleReplicasExecutor scaleWith(KubernetesClient client) {
        return new K8sScaleReplicasExecutor() {
            @Override
            protected KubernetesClient resolveClient() {
                return client;
            }
        };
    }

    private static Pod pod(String name, int restartCount) {
        return new PodBuilder()
                .withNewMetadata().withName(name).withUid("uid-" + name)
                .withLabels(Map.of("app", "user-service")).endMetadata()
                .withNewStatus().withPhase("Running").endStatus()
                .build();
    }

    // ---------------- 重启 ----------------

    @Test
    @DisplayName("重启 dryRun：输出目标/当前 phase/同应用总数/回滚诚实标注（不产生副作用）")
    void restartDryRunDescribesPlan() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        when(client.pods().inNamespace("staging").withName("user-abc").get())
                .thenReturn(pod("user-abc", 2));
        PodList siblings = new PodList();
        siblings.setItems(java.util.List.of(pod("user-abc", 2), pod("user-def", 0),
                pod("user-ghi", 0), pod("user-jkl", 0), pod("user-mno", 0)));
        when(client.pods().inNamespace("staging").withLabel("app", "user-service").list())
                .thenReturn(siblings);

        ExecutionResult dry = restartWith(client).dryRun(
                action("k8s.pod.restart", Map.of("namespace", "staging", "pod", "user-abc")));

        assertTrue(dry.success(), dry.error());
        assertTrue(dry.dryRun());
        assertTrue(dry.output().contains("user-abc") && dry.output().contains("总数=5"),
                dry.output());
    }

    @Test
    @DisplayName("重启 execute：删除 Pod 留全身份快照，undoToken 恒为 null（不可真正回滚的诚实标注）")
    void restartExecuteDeletesWithSnapshotAndNoUndoToken() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        when(client.pods().inNamespace("staging").withName("user-abc").get())
                .thenReturn(pod("user-abc", 2));

        ExecutionResult real = restartWith(client).execute(
                action("k8s.pod.restart", Map.of("namespace", "staging", "pod", "user-abc")));

        assertTrue(real.success(), real.error());
        verify(client.pods().inNamespace("staging").withName("user-abc"), times(1)).delete();
        assertEquals("uid-user-abc", real.preSnapshot().get("uid"),
                "快照必须留住原 Pod 全要素——这是审计能给的全部，行胜于言");
        assertNull(real.undoToken(), "重启不可回滚：undoToken 必须为 null，撤销入口前置拒绝");
    }

    @Test
    @DisplayName("命名空间硬护栏：prod 写请求在 executor 侧被拒（配置层配错也伤不到 prod）")
    void namespaceGuardRejectsProd() {
        ExecutionResult out = restartWith(mock(KubernetesClient.class, RETURNS_DEEP_STUBS))
                .execute(action("k8s.pod.restart",
                        Map.of("namespace", "prod", "pod", "user-abc")));
        assertFalse(out.success());
        assertTrue(out.error().contains("硬护栏"), out.error());
    }

    // ---------------- 扩缩容 ----------------

    private static Deployment deploy(String name, int replicas) {
        return new DeploymentBuilder()
                .withNewMetadata().withName(name).endMetadata()
                .withNewSpec().withReplicas(replicas).endSpec()
                .build();
    }

    @Test
    @DisplayName("扩缩容 dryRun 爆炸半径越限：3 -> 6 副本（影响 3 > 20% 上限）被拒")
    void scaleDryRunRejectsBlastOverflow() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        when(client.apps().deployments().inNamespace("staging").withName("user-svc").get())
                .thenReturn(deploy("user-svc", 3));

        ExecutionResult dry = scaleWith(client).dryRun(
                action("k8s.deploy.scale",
                        Map.of("namespace", "staging", "deployment", "user-svc", "replicas", 6)));

        assertFalse(dry.success());
        assertTrue(dry.error().contains("爆炸半径"), dry.error());
    }

    @Test
    @DisplayName("扩缩容 execute 记录原副本数快照 → undo 按快照恢复（3-2.4 的诚实回滚）")
    void scaleExecuteAndUndoRestoresPrevious() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        when(client.apps().deployments().inNamespace("staging").withName("user-svc").get())
                .thenReturn(deploy("user-svc", 4));

        K8sScaleReplicasExecutor executor = scaleWith(client);
        ExecutionResult real = executor.execute(
                action("k8s.deploy.scale",
                        Map.of("namespace", "staging", "deployment", "user-svc", "replicas", 5)));

        assertTrue(real.success(), real.error());
        assertEquals(4, real.preSnapshot().get("previousReplicas"));
        assertTrue(real.undoToken() != null && real.undoToken().startsWith("scale-back-"),
                "扩缩容可回滚：undoToken 必须非空");
        verify(client.apps().deployments().inNamespace("staging").withName("user-svc"),
                times(1)).scale(5);

        ExecutionResult undone = executor.undo(
                action("k8s.deploy.scale",
                        Map.of("namespace", "staging", "deployment", "user-svc", "replicas", 5)),
                real.undoToken(), real.preSnapshot());

        assertTrue(undone.success(), undone.error());
        verify(client.apps().deployments().inNamespace("staging").withName("user-svc"),
                times(1)).scale(4);
    }

    @Test
    @DisplayName("replicas 参数与白名单 schema 对齐：超出 [1,10] 直接拒绝")
    void scaleRejectsOutOfSchemaReplicas() {
        ExecutionResult out = scaleWith(mock(KubernetesClient.class, RETURNS_DEEP_STUBS))
                .execute(action("k8s.deploy.scale",
                        Map.of("namespace", "staging", "deployment", "user-svc", "replicas", 50)));
        assertFalse(out.success());
        assertTrue(out.error().contains("[1, 10]"), out.error());
    }

    @Test
    @DisplayName("未配置集群：两个执行器都返回明确错误而不崩溃（离线基调同族）")
    void noClusterFailsCleanly() {
        ExecutionResult restartOut = restartWith(null).execute(
                action("k8s.pod.restart", Map.of("namespace", "staging", "pod", "x")));
        ExecutionResult scaleOut = scaleWith(null).execute(
                action("k8s.deploy.scale", Map.of("namespace", "staging",
                        "deployment", "user-svc", "replicas", 2)));
        assertFalse(restartOut.success());
        assertFalse(scaleOut.success());
        assertTrue(restartOut.error().contains("未配置 K8s 集群"));
        assertTrue(scaleOut.error().contains("未配置 K8s 集群"));
    }

    @Test
    @DisplayName("注册表共栖：K8s 双执行器与 Mock 同注册不冲突，路由各归其位")
    void registryCoHostsK8sAndMock() {
        var registry = new com.devops.agent.domain.healing.ExecutorRegistry(
                java.util.List.of(new K8sRestartPodExecutor(), new K8sScaleReplicasExecutor(),
                        new com.devops.agent.domain.healing.MockActionExecutor()));
        assertEquals("k8s-fabric8",
                registry.locate("k8s.pod.restart").orElseThrow().executorKey());
        assertEquals("k8s-fabric8",
                registry.locate("k8s.deploy.scale").orElseThrow().executorKey());
        assertEquals("mock", registry.locate("mock.disk.cleanup").orElseThrow().executorKey());
    }
}
