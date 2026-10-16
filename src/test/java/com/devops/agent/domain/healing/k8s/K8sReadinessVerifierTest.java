package com.devops.agent.domain.healing.k8s;

import com.devops.agent.domain.healing.ExecutionResult;
import com.devops.agent.domain.healing.HealingAction;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * K8sReadinessVerifier（§7.4 3-4.2）契约测试：扩缩容看「目标副本数是否真的就绪
 * 到位」、重启看「替换 Pod 是否顶上」；离线时必须是 UNKNOWN 而不是
 * 谎报健康/病态（离线同族基调，与执行器共享轨道 A 接缝注入）。
 */
@DisplayName("K8s 就绪验证器（S3-3 欠账兑付：≥2 例）")
class K8sReadinessVerifierTest {

    /** 测试子类接缝：注入指定 client。 */
    private static K8sReadinessVerifier verifierWith(KubernetesClient client) {
        return new K8sReadinessVerifier() {
            @Override
            protected KubernetesClient resolveClient() {
                return client;
            }
        };
    }

    private static HealingAction scaleAction(int replicas) {
        return new HealingAction("k8s.deploy.scale", "staging", "ns:staging/app",
                Map.of("namespace", "staging", "deployment", "user-svc", "replicas", replicas),
                null, "auto", Instant.now());
    }

    private static HealingAction restartAction() {
        return new HealingAction("k8s.pod.restart", "staging", "ns:staging/app",
                Map.of("namespace", "staging", "pod", "user-abc"),
                null, "auto", Instant.now());
    }

    private static ExecutionResult restartExecution(Map<String, Object> preSnapshot) {
        return ExecutionResult.ok("k8s-fabric8", "k8s.pod.restart", "ok", preSnapshot, null);
    }

    // ---------------- 扩缩容 ----------------

    @SuppressWarnings("unchecked")
    private static KubernetesClient scaleStub(String ns, String name, int desired, int ready) {
        KubernetesClient client = mock(KubernetesClient.class);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<io.fabric8.kubernetes.api.model.apps.Deployment,
                io.fabric8.kubernetes.api.model.apps.DeploymentList,
                RollableScalableResource<io.fabric8.kubernetes.api.model.apps.Deployment>> ops =
                mock(MixedOperation.class);
        NonNamespaceOperation<io.fabric8.kubernetes.api.model.apps.Deployment,
                io.fabric8.kubernetes.api.model.apps.DeploymentList,
                RollableScalableResource<io.fabric8.kubernetes.api.model.apps.Deployment>> nsOps =
                mock(NonNamespaceOperation.class);
        RollableScalableResource<io.fabric8.kubernetes.api.model.apps.Deployment> res =
                mock(RollableScalableResource.class);
        when(client.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(ops);
        when(ops.inNamespace(ns)).thenReturn(nsOps);
        when(nsOps.withName(name)).thenReturn(res);
        when(res.get()).thenReturn(new DeploymentBuilder()
                .withNewSpec().withReplicas(desired).endSpec()
                .withNewStatus().withReadyReplicas(ready).endStatus()
                .build());
        return client;
    }

    @Test
    @DisplayName("扩缩容验证：ready 达到目标值 → healthy（含 before/after 指标）")
    void scaleReadyIsHealthy() {
        var out = verifierWith(scaleStub("staging", "user-svc", 5, 5))
                .verify(scaleAction(5), null);
        assertEquals("HEALTHY", out.status(), out.summary());
        assertEquals(5, out.after().get("readyReplicas"));
        assertEquals(5, out.after().get("desiredReplicas"));
    }

    @Test
    @DisplayName("扩缩容验证：ready 不足（3/5）→ unhealthy，绝不说「扩好了」")
    void scaleShortIsUnhealthy() {
        var out = verifierWith(scaleStub("staging", "user-svc", 5, 3))
                .verify(scaleAction(5), null);
        assertEquals("UNHEALTHY", out.status(), "就绪不足必须诚实报 UNHEALTHY");
        assertTrue(out.summary().contains("就绪不足"), out.summary());
    }

    // ---------------- 重启 ----------------

    @SuppressWarnings("unchecked")
    private static KubernetesClient restartStub(String ns, String app, List<io.fabric8.kubernetes.api.model.Pod> pods) {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<io.fabric8.kubernetes.api.model.Pod, PodList, PodResource> podsOps =
                mock(MixedOperation.class);
        NonNamespaceOperation<io.fabric8.kubernetes.api.model.Pod, PodList, PodResource> nsOps =
                mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<io.fabric8.kubernetes.api.model.Pod, PodList, PodResource> filtered =
                mock(FilterWatchListDeletable.class);
        when(client.pods()).thenReturn(podsOps);
        when(podsOps.inNamespace(ns)).thenReturn(nsOps);
        when(nsOps.withLabel("app", app)).thenReturn(filtered);
        PodList list = new PodList();
        list.setItems(pods);
        when(filtered.list()).thenReturn(list);
        return client;
    }

    @Test
    @DisplayName("重启验证：同应用标签下出现 uid≠快照 的 Running 新 Pod → healthy（替换就位）")
    void restartReplacementIsHealthy() {
        var oldUid = "uid-old-abc";
        var newPod = new PodBuilder()
                .withNewMetadata().withName("user-def").withUid("uid-new-def").endMetadata()
                .withNewStatus().withPhase("Running").endStatus().build();
        var keepPod = new PodBuilder()
                .withNewMetadata().withName("user-ghi").withUid("uid-ghi").endMetadata()
                .withNewStatus().withPhase("Running").endStatus().build();
        var out = verifierWith(restartStub("staging", "user-service", List.of(newPod, keepPod)))
                .verify(restartAction(), restartExecution(Map.of(
                        "uid", oldUid, "labels", Map.of("app", "user-service"))));
        assertEquals("HEALTHY", out.status(), out.summary());
        assertEquals("user-def", out.after().get("replacementPod"));
    }

    @Test
    @DisplayName("重启验证：快照无 app 标签 → UNKNOWN（不能乱猜替换对象）")
    void restartWithoutAppLabelIsUnknown() {
        var out = verifierWith(restartStub("staging", "user-service", List.of()))
                .verify(restartAction(), restartExecution(Map.of("uid", "uid-old", "labels", Map.of())));
        assertEquals("UNKNOWN", out.status(), "无 app 标签只能 UNKNOWN，绝不乱判");
    }

    @Test
    @DisplayName("离线基调：未配置集群 → UNKNOWN（同执行器离线同族，不谎报）")
    void noClusterIsUnknown() {
        var out = verifierWith(null).verify(scaleAction(5), null);
        assertEquals("UNKNOWN", out.status(), "连不上集群必须 UNKNOWN，不谎报健康/病态");
    }
}
