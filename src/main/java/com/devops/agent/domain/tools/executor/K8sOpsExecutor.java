package com.devops.agent.domain.tools.executor;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * K8s 执行器适配器（V1.2，D1 适配器模式第一个实现）。
 *
 * <p>职责：把 K8s 操作归一为 {@link OpsExecutor} 统一接口，供治理链路
 * （白名单 / 风险分级 / 审批 / 记录 / 审计）与 AI Agent 调用。</p>
 *
 * <p>安全设计：</p>
 * <ul>
 *   <li><b>只读优先</b>：V1.2 仅暴露 {@code queryPodStatus}（READ_ONLY，免审批）；
 *       写动作（restartPod 等）V1.3 再加，且强制非生产命名空间 + 单实例；</li>
 *   <li><b>懒加载</b>：未配置 KubeConfig 时 client 不初始化，动作返回
 *       「未配置目标系统」明确错误——系统不崩溃；</li>
 *   <li><b>凭证隔离</b>：走 KubeConfig（~/.kube/config 或 KUBECONFIG 环境变量），
 *       凭证不进仓库、不落日志；</li>
 *   <li><b>异常不外泄内部细节</b>：错误信息只给摘要与安全提示。</li>
 * </ul>
 */
@Component
public class K8sOpsExecutor implements OpsExecutor {

    private static final Logger log = LoggerFactory.getLogger(K8sOpsExecutor.class);

    /** 懒加载的 KubernetesClient；未配置集群时为 null */
    private volatile KubernetesClient client;

    private final OpsExecutorRegistry registry;

    public K8sOpsExecutor(OpsExecutorRegistry registry) {
        this.registry = registry;
        // 构造时自注册（适配器模式注册表）
        registry.register(this);
    }

    @Override
    public String targetSystem() {
        return "k8s";
    }

    @Override
    public Set<String> supportedActions() {
        // V1.2 只读切口；写动作 V1.3 扩展
        return Set.of("queryPodStatus");
    }

    @Override
    public OpsExecutionResult execute(OpsCommand command) {
        return switch (command.action()) {
            case "queryPodStatus" -> queryPodStatus(command);
            default -> OpsExecutionResult.fail("K8s 适配器不支持动作: " + command.action()
                    + "（支持: " + supportedActions() + "）");
        };
    }

    // ==================== 动作实现 ====================

    private OpsExecutionResult queryPodStatus(OpsCommand command) {
        String namespace = command.requireParam("namespace");
        String podName = command.requireParam("pod");

        KubernetesClient k8s = client();
        if (k8s == null) {
            return OpsExecutionResult.fail("未配置 K8s 集群（KubeConfig 缺失）。"
                    + "请配置 ~/.kube/config 或 KUBECONFIG 后重试——这是只读查询，不会改动集群。");
        }

        try {
            Pod pod = k8s.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null) {
                return OpsExecutionResult.fail("Pod 不存在: namespace=" + namespace + ", pod=" + podName
                        + "（请核对名称，可用 queryPodStatus 前先确认）");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("namespace", namespace);
            data.put("pod", podName);
            data.put("phase", pod.getStatus() != null ? pod.getStatus().getPhase() : "UNKNOWN");
            data.put("restartCount", restartCount(pod));
            data.put("nodeName", pod.getSpec() != null ? pod.getSpec().getNodeName() : null);
            data.put("podIp", pod.getStatus() != null ? pod.getStatus().getPodIP() : null);
            data.put("startTime", pod.getStatus() != null && pod.getStatus().getStartTime() != null
                    ? pod.getStatus().getStartTime() : null);
            data.put("containers", containerStates(pod));

            String phase = String.valueOf(data.get("phase"));
            String summary = "Pod " + podName + "（" + namespace + "）当前状态: " + phase
                    + "，重启次数: " + data.get("restartCount");
            log.info("[K8sExecutor] queryPodStatus 成功 | ns={} pod={} phase={}", namespace, podName, phase);
            return OpsExecutionResult.ok(summary, data);
        } catch (Exception e) {
            log.warn("[K8sExecutor] queryPodStatus 失败 | ns={} pod={} err={}",
                    namespace, podName, e.getClass().getSimpleName());
            // 只给摘要与安全提示，不外泄内部连接细节
            return OpsExecutionResult.fail("K8s 查询失败（" + e.getClass().getSimpleName()
                    + "），请稍后重试或检查集群连通性");
        }
    }

    // ==================== 辅助 ====================

    private int restartCount(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) return 0;
        return pod.getStatus().getContainerStatuses().stream()
                .mapToInt(ContainerStatus::getRestartCount)
                .sum();
    }

    private List<Map<String, Object>> containerStates(Pod pod) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) return out;
        for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", cs.getName());
            m.put("ready", cs.getReady());
            m.put("restartCount", cs.getRestartCount());
            m.put("state", cs.getState() != null
                    ? (cs.getState().getRunning() != null ? "Running"
                    : cs.getState().getWaiting() != null ? "Waiting:" + cs.getState().getWaiting().getReason()
                    : cs.getState().getTerminated() != null ? "Terminated:" + cs.getState().getTerminated().getReason()
                    : "Unknown")
                    : "Unknown");
            m.put("image", cs.getImage());
            out.add(m);
        }
        return out;
    }

    /** 懒加载 K8s 客户端（未配置 KubeConfig 返回 null，不抛异常） */
    private KubernetesClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    try {
                        // 默认读 ~/.kube/config；KUBECONFIG 环境变量可覆盖
                        client = new KubernetesClientBuilder().build();
                        log.info("[K8sExecutor] K8s 客户端初始化成功");
                    } catch (Exception e) {
                        log.warn("[K8sExecutor] K8s 客户端初始化失败（未配置集群?）: {}",
                                e.getClass().getSimpleName());
                        client = null;
                    }
                }
            }
        }
        return client;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 关闭失败无碍
            }
        }
    }
}
