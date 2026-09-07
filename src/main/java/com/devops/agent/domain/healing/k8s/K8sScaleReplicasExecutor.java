package com.devops.agent.domain.healing.k8s;

import com.devops.agent.domain.healing.ActionPermissionLevel;
import com.devops.agent.domain.healing.ExecutionResult;
import com.devops.agent.domain.healing.HealingAction;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 副本数调整执行器（§7.2 3-2.2，actionKey 对齐白名单种子 {@code k8s.deploy.scale}）。
 * <p>
 * 与重启执行器的回滚立场不同：扩缩容<b>可真正回滚</b>——
 * 执行时把原副本数写进 preSnapshot，undo 按快照恢复原值（3-2.4 后半句）。
 * 这是本执行器 undo_token 非空的原因。
 * </p>
 */
@Component
public class K8sScaleReplicasExecutor extends AbstractK8sHealingExecutor {

    public static final String ACTION_KEY = "k8s.deploy.scale";
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
        String ns = strParam(action, "namespace");
        String deploymentName = strParam(action, "deployment");
        Integer target = intParam(action, "replicas");
        String guard = guard(ns, target, true);
        if (guard != null) {
            return ExecutionResult.fail(executorKey(), action.actionKey(), true, guard);
        }
        try {
            Deployment deployment = k8s.apps().deployments().inNamespace(ns).withName(deploymentName).get();
            if (deployment == null) {
                return ExecutionResult.fail(executorKey(), action.actionKey(), true,
                        "Deployment 不存在: namespace=" + ns + ", deployment=" + deploymentName);
            }
            int current = currentReplicas(deployment);
            int affected = Math.abs(target - current);
            if (blastExceeded(affected, current)) {
                return ExecutionResult.fail(executorKey(), action.actionKey(), true,
                        "爆炸半径越限：当前 " + current + " 副本 -> 目标 " + target
                                + "，影响 " + affected + " 个，超过比率上限 "
                                + Math.round(maxBlastRatio * 100) + "%（可在配置调整 max-blast-ratio）");
            }
            return ExecutionResult.dryRunOk(executorKey(), action.actionKey(),
                    "将把 Deployment " + ns + "/" + deploymentName + " 的副本数从 " + current
                            + " 调整为 " + target + "（影响 " + affected + " 个 Pod，"
                            + "回滚方案：执行后按快照恢复为 " + current + "）");
        } catch (Exception e) {
            log.warn("⚠️ [K8sHealing] 演算查询失败 | action={} 异常={}",
                    action.actionKey(), e.getMessage());
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
        String ns = strParam(action, "namespace");
        String deploymentName = strParam(action, "deployment");
        Integer target = intParam(action, "replicas");
        String guard = guard(ns, target, false);
        if (guard != null) {
            return ExecutionResult.fail(executorKey(), action.actionKey(), false, guard);
        }
        try {
            Deployment deployment = k8s.apps().deployments().inNamespace(ns).withName(deploymentName).get();
            if (deployment == null) {
                return ExecutionResult.fail(executorKey(), action.actionKey(), false,
                        "执行时 Deployment 已不存在: " + ns + "/" + deploymentName);
            }
            int current = currentReplicas(deployment);
            int affected = Math.abs(target - current);
            // 执行前二次校验（3-3.4）：演算到执行之间副本数可能已被他人改动
            if (blastExceeded(affected, current)) {
                return ExecutionResult.fail(executorKey(), action.actionKey(), false,
                        "执行前爆炸半径复核越限（当前 " + current + " -> 目标 " + target + "），已拒绝执行");
            }

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("namespace", ns);
            snapshot.put("deployment", deploymentName);
            snapshot.put("previousReplicas", current);
            snapshot.put("targetReplicas", target);

            k8s.apps().deployments().inNamespace(ns).withName(deploymentName).scale(target);
            log.warn("[K8sHealing] 已调整副本数 | ns={} deploy={} {}->{}", ns, deploymentName, current, target);

            return ExecutionResult.ok(executorKey(), action.actionKey(),
                    "已把 Deployment " + ns + "/" + deploymentName + " 副本数从 " + current
                            + " 调整为 " + target,
                    snapshot, "scale-back-" + System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[K8sHealing] 调整副本数失败 | ns={} deploy={} err={}", ns, deploymentName,
                    e.getClass().getSimpleName());
            return ExecutionResult.fail(executorKey(), action.actionKey(), false,
                    "K8s 调整副本数失败（" + e.getClass().getSimpleName() + "），请检查集群连通性与 RBAC 权限");
        }
    }

    @Override
    public boolean undoSupported(String actionKey) {
        return true;
    }

    /** 回滚 = 按执行前快照恢复原副本数（3-2.4 后半句的诚实实现）。 */
    @Override
    public ExecutionResult undo(HealingAction action, String undoToken,
                                Map<String, Object> preSnapshot) {
        KubernetesClient k8s = resolveClient();
        if (k8s == null) {
            return noCluster(action.actionKey(), false);
        }
        String ns = strParam(action, "namespace");
        String deploymentName = strParam(action, "deployment");
        Object previous = preSnapshot.get("previousReplicas");
        if (previous == null) {
            return ExecutionResult.fail(executorKey(), action.actionKey(), false,
                    "执行前快照缺少 previousReplicas，无法回滚（快照受损，需人工核对）");
        }
        int previousReplicas = ((Number) previous).intValue();
        try {
            k8s.apps().deployments().inNamespace(ns).withName(deploymentName).scale(previousReplicas);
            log.warn("[K8sHealing] 已按快照回滚副本数 | ns={} deploy={} -> {}", ns, deploymentName,
                    previousReplicas);
            return ExecutionResult.ok(executorKey(), action.actionKey(),
                    "已按执行前快照把 Deployment " + ns + "/" + deploymentName
                            + " 副本数恢复为 " + previousReplicas,
                    Map.of(), null);
        } catch (Exception e) {
            // 回滚失败 = 事故升级的临界时刻，error 级留痕
            log.error("🚨 [K8sHealing] 回滚副本数失败 | ns={} deploy={} 异常={}",
                    action.environment(), action.target(), e.getMessage());
            return ExecutionResult.fail(executorKey(), action.actionKey(), false,
                    "K8s 回滚副本数失败（" + e.getClass().getSimpleName() + "）——升级人工处理");
        }
    }

    // ---------------- 内部 ----------------

    /** 参数与命名空间硬护栏；合法返回 null。 */
    private String guard(String ns, Integer target, boolean dryRun) {
        if (ns == null || ns.isBlank()) {
            return "参数 namespace 必填";
        }
        if (!namespaceAllowed(ns)) {
            return "命名空间「" + ns + "」不在写动作允许清单（" + allowedNamespaces + "）";
        }
        if (target == null) {
            return "参数 replicas 必填且必须是整数";
        }
        if (target < 1 || target > maxReplicas) {
            return "replicas=" + target + " 超出允许范围 [1, " + maxReplicas
                    + "]（与白名单 param_schema 对齐）";
        }
        return null;
    }

    private static String strParam(HealingAction action, String key) {
        Object v = action.params().get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static Integer intParam(HealingAction action, String key) {
        Object v = action.params().get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private static int currentReplicas(Deployment deployment) {
        return deployment.getSpec() != null && deployment.getSpec().getReplicas() != null
                ? deployment.getSpec().getReplicas() : 1;
    }
}
