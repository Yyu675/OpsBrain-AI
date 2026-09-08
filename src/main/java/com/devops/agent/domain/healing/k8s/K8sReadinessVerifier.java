package com.devops.agent.domain.healing.k8s;

import com.devops.agent.domain.healing.ActionVerifier;
import com.devops.agent.domain.healing.ExecutionResult;
import com.devops.agent.domain.healing.HealingAction;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K8s 就绪验证器（S3-3/§7.4 3-4.2）：execution 发出去了，Pod 真的就绪了吗。
 * <p>
 * 覆盖两个 fabric8 执行器：
 * <ul>
 *   <li>{@code k8s.deploy.scale}：Deployment 的 readyReplicas 达到目标值即健康；</li>
 *   <li>{@code k8s.pod.restart}：同应用标签下出现 uid ≠ 快照 uid 的 Running 新 Pod
 *       即视为替换就位。</li>
 * </ul>
 * 与执行器同款离线基调：未配置集群时返回 {@code UNKNOWN}（验证不了），
 * 绝不把「连不上集群」谎报成「健康」或「病态」。
 * 错误率/P99 的业务指标对比待 Prometheus 接线（3-4.2 的后半步）。
 * </p>
 */
@Component
public class K8sReadinessVerifier implements ActionVerifier {

    private static final Logger log = LoggerFactory.getLogger(K8sReadinessVerifier.class);

    /** 懒加载 client；未配置集群时为 null。 */
    private volatile KubernetesClient client;

    @Override
    public String verifierKey() {
        return "k8s-readiness";
    }

    @Override
    public boolean supports(String actionKey) {
        return K8sRestartPodExecutor.ACTION_KEY.equals(actionKey)
                || K8sScaleReplicasExecutor.ACTION_KEY.equals(actionKey);
    }

    @Override
    public VerificationResult verify(HealingAction action, ExecutionResult execution) {
        KubernetesClient k8s = resolveClient();
        if (k8s == null) {
            return VerificationResult.unknown(
                    "未配置 K8s 集群，无法采集就绪指标——验证不了不等于失败");
        }
        try {
            if (K8sScaleReplicasExecutor.ACTION_KEY.equals(action.actionKey())) {
                return verifyScale(k8s, action);
            }
            return verifyRestart(k8s, action, execution);
        } catch (Exception e) {
            log.warn("[K8sVerify] 采集失败 | action={} err={}", action.actionKey(),
                    e.getClass().getSimpleName());
            return VerificationResult.unknown(
                    "K8s 就绪采集失败（" + e.getClass().getSimpleName() + "）——按未知处理");
        }
    }

    /** 扩缩容：readyReplicas 达到目标即健康。 */
    private VerificationResult verifyScale(KubernetesClient k8s, HealingAction action) {
        String ns = (String) action.params().get("namespace");
        String deploymentName = (String) action.params().get("deployment");
        Integer target = action.params().get("replicas") instanceof Number n ? n.intValue() : null;

        Deployment deployment = k8s.apps().deployments().inNamespace(ns).withName(deploymentName).get();
        if (deployment == null) {
            return VerificationResult.unhealthy(
                    "Deployment 已不存在: " + ns + "/" + deploymentName, Map.of(), Map.of());
        }
        int desired = deployment.getSpec() != null && deployment.getSpec().getReplicas() != null
                ? deployment.getSpec().getReplicas() : 0;
        int ready = deployment.getStatus() != null && deployment.getStatus().getReadyReplicas() != null
                ? deployment.getStatus().getReadyReplicas() : 0;

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("executionTarget", target);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("desiredReplicas", desired);
        after.put("readyReplicas", ready);

        if (ready >= desired && desired == (target == null ? desired : target)) {
            return VerificationResult.healthy(
                    "就绪 " + ready + "/" + desired + " 达标", before, after);
        }
        return VerificationResult.unhealthy(
                "就绪不足：ready=" + ready + " / desired=" + desired, before, after);
    }

    /** 重启：同应用标签下出现了 uid 不同于快照的 Running 新 Pod 即替换就位。 */
    private VerificationResult verifyRestart(KubernetesClient k8s, HealingAction action,
                                             ExecutionResult execution) {
        String ns = (String) action.params().get("namespace");
        Object oldUid = execution.preSnapshot().get("uid");
        @SuppressWarnings("unchecked")
        Map<String, String> labels = execution.preSnapshot().get("labels") instanceof Map<?, ?> m
                ? (Map<String, String>) m : Map.of();
        String appKey = labels.containsKey("app") ? "app"
                : labels.containsKey("app.kubernetes.io/name") ? "app.kubernetes.io/name" : null;
        if (appKey == null) {
            return VerificationResult.unknown("快照无 app 标签，无法定位替换 Pod");
        }
        String appValue = labels.get(appKey);
        List<Pod> pods = k8s.pods().inNamespace(ns).withLabel(appKey, appValue).list().getItems();

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("deletedPodUid", oldUid);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("siblingPods", pods == null ? 0 : pods.size());

        if (pods != null) {
            for (Pod p : pods) {
                String uid = p.getMetadata() != null ? p.getMetadata().getUid() : null;
                String phase = p.getStatus() != null ? p.getStatus().getPhase() : null;
                if (uid != null && !uid.equals(oldUid) && "Running".equals(phase)) {
                    after.put("replacementPod", p.getMetadata().getName());
                    return VerificationResult.healthy(
                            "替换 Pod " + p.getMetadata().getName() + " 已 Running", before, after);
                }
            }
        }
        return VerificationResult.unhealthy(
                "尚未观测到替换 Pod 进入 Running（可能仍在调度或已 CrashLoop）", before, after);
    }

    /** 测试子类注入 mock client 的接缝（与执行器基座同一轨道 A 模式）。 */
    protected KubernetesClient resolveClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    try {
                        client = new KubernetesClientBuilder().build();
                    } catch (Throwable t) {
                        client = null;
                        log.warn("[K8sVerify] KubeConfig 未就绪 | {}", t.getClass().getSimpleName());
                    }
                }
            }
        }
        return client;
    }
}
