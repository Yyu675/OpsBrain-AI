package com.devops.agent.domain.healing.k8s;

import com.devops.agent.domain.healing.ActionPermissionLevel;
import com.devops.agent.domain.healing.ExecutionResult;
import com.devops.agent.domain.healing.HealingAction;
import io.fabric8.kubernetes.api.model.Pod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优雅重启 Pod 执行器（§7.2 3-2.1，actionKey 对齐白名单种子 {@code k8s.pod.restart}）。
 * <p>
 * 语义：删除 Pod，由其 Owner（Deployment 等）按期望状态重建——
 * 不直接"重启进程"，这是 K8s 原语层面唯一诚实的重启方式。
 * </p>
 * <p>
 * 回滚立场（3-2.4 的诚实标注）：重启<b>不可真正回滚</b>——原 Pod 已被删除，
 * 快照里只留下它的身份与状态供审计追踪，undo_token 恒为 null，
 * 编排器的撤销入口会因「无凭据」前置拒绝。这不是缺陷：
 * 重建产生的新 Pod 若不健康是<b>新的故障</b>，应走新一轮诊断而非
 * 假装可以把已删除的 Pod 变回来。
 * </p>
 */
@Component
public class K8sRestartPodExecutor extends AbstractK8sHealingExecutor {

    public static final String ACTION_KEY = "k8s.pod.restart";
    public static final String EXECUTOR_KEY = "k8s-fabric8";

    @Override
    public String executorKey() {
        return EXECUTOR_KEY;
    }

    @Override
    public boolean supports(String actionKey) {
        return ACTION_KEY.equals(actionKey);
    }

    @Override
    public ActionPermissionLevel permissionLevel(String actionKey) {
        return ActionPermissionLevel.SAFE_AUTO_HEALING;
    }

    @Override
    public ExecutionResult dryRun(HealingAction action) {
        KubernetesClient k8s = resolveClient();
        if (k8s == null) {
            return noCluster(action.actionKey(), true);
        }
        String ns = (String) action.params().get("namespace");
        String pod = (String) action.params().get("pod");
        String guard = guardNamespace(ns, true);
        if (guard != null) {
            return ExecutionResult.fail(executorKey(), action.actionKey(), true, guard);
        }
        try {
            Pod target = k8s.pods().inNamespace(ns).withName(pod).get();
            if (target == null) {
                return ExecutionResult.fail(executorKey(), action.actionKey(), true,
                        "Pod 不存在: namespace=" + ns + ", pod=" + pod + "（请核对名称）");
            }
            int siblings = countSiblingPods(k8s, ns, target);
            return ExecutionResult.dryRunOk(executorKey(), action.actionKey(),
                    "将删除 Pod " + ns + "/" + pod + "（当前 phase="
                            + phaseOf(target) + "，重启次数=" + restartCountOf(target) + "），"
                            + "由 Deployment 重建；同应用 Pod 总数=" + siblings
                            + "，本次影响 1 个（爆炸半径比率上限 "
                            + Math.round(maxBlastRatio * 100) + "%）");
        } catch (Exception e) {
            return ExecutionResult.fail(executorKey(), action.actionKey(), true,
                    "K8s 演算查询失败（" + e.getClass().getSimpleName() + "）");
        }
    }

    @Override
    public ExecutionResult execute(HealingAction action) {
        KubernetesClient k8s = resolveClient();
        if (k8s == null) {
            return noCluster(action.actionKey(), false);
        }
        String ns = (String) action.params().get("namespace");
        String pod = (String) action.params().get("pod");
        String guard = guardNamespace(ns, false);
        if (guard != null) {
            return ExecutionResult.fail(executorKey(), action.actionKey(), false, guard);
        }
        try {
            // 执行前二次确认（3-3.4）：pod 必须仍然存在，演算与执行之间可能已被删除
            Pod target = k8s.pods().inNamespace(ns).withName(pod).get();
            if (target == null) {
                return ExecutionResult.fail(executorKey(), action.actionKey(), false,
                        "执行时 Pod 已不存在: " + ns + "/" + pod + "（可能已被其他操作删除）");
            }

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("namespace", ns);
            snapshot.put("pod", pod);
            snapshot.put("uid", target.getMetadata() != null ? target.getMetadata().getUid() : null);
            snapshot.put("phase", phaseOf(target));
            snapshot.put("restartCount", restartCountOf(target));
            snapshot.put("labels", target.getMetadata() != null && target.getMetadata().getLabels() != null
                    ? target.getMetadata().getLabels() : Map.of());

            k8s.pods().inNamespace(ns).withName(pod).delete();
            log.warn("[K8sHealing] 已删除 Pod（Deployment 将重建）| ns={} pod={}", ns, pod);

            // undoToken 恒为 null：重启不可真正回滚（3-2.4 诚实标注，见类注释）
            return ExecutionResult.ok(executorKey(), action.actionKey(),
                    "已删除 Pod " + ns + "/" + pod + "，Deployment 将按期望状态重建",
                    snapshot, null);
        } catch (Exception e) {
            log.warn("[K8sHealing] 删除 Pod 失败 | ns={} pod={} err={}", ns, pod,
                    e.getClass().getSimpleName());
            return ExecutionResult.fail(executorKey(), action.actionKey(), false,
                    "K8s 删除 Pod 失败（" + e.getClass().getSimpleName() + "），请检查集群连通性与 RBAC 权限");
        }
    }

    /** 重启不支持撤销——编排器会因 undoToken=null 前置拒绝；此声明供路由层透传。 */
    @Override
    public boolean undoSupported(String actionKey) {
        return false;
    }

    // ---------------- 内部 ----------------

    /** 命名空间硬护栏；越界返回错误文案，合法返回 null。 */
    private String guardNamespace(String ns, boolean dryRun) {
        if (ns == null || ns.isBlank()) {
            return "参数 namespace 必填";
        }
        if (!namespaceAllowed(ns)) {
            return "命名空间「" + ns + "」不在写动作允许清单（" + allowedNamespaces
                    + "）——executor 侧硬护栏，配置层配错也伤不到 prod";
        }
        return null;
    }

    /** 同应用 Pod 计数：按 app / app.kubernetes.io/name 标签聚合，无标签则退化为全命名空间。 */
    private int countSiblingPods(KubernetesClient k8s, String ns, Pod target) {
        Map<String, String> labels = target.getMetadata() != null
                ? target.getMetadata().getLabels() : null;
        String appValue = null;
        String appKey = null;
        if (labels != null) {
            if (labels.containsKey("app")) {
                appKey = "app";
                appValue = labels.get("app");
            } else if (labels.containsKey("app.kubernetes.io/name")) {
                appKey = "app.kubernetes.io/name";
                appValue = labels.get("app.kubernetes.io/name");
            }
        }
        List<Pod> pods = appKey != null
                ? k8s.pods().inNamespace(ns).withLabel(appKey, appValue).list().getItems()
                : k8s.pods().inNamespace(ns).list().getItems();
        return pods == null ? 1 : Math.max(1, pods.size());
    }

    private static String phaseOf(Pod pod) {
        return pod.getStatus() != null && pod.getStatus().getPhase() != null
                ? pod.getStatus().getPhase() : "UNKNOWN";
    }

    private static int restartCountOf(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return 0;
        }
        return pod.getStatus().getContainerStatuses().stream()
                .mapToInt(cs -> cs.getRestartCount() == null ? 0 : cs.getRestartCount()).sum();
    }

}
