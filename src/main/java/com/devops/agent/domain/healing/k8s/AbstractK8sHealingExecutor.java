package com.devops.agent.domain.healing.k8s;

import com.devops.agent.domain.healing.ActionExecutor;
import com.devops.agent.domain.healing.ExecutionResult;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * fabric8 自愈执行器基座（S3-2/§7.2）：懒加载 client + 命名空间硬护栏 + 爆炸半径公式。
 * <p>
 * 三处刻意与 K8sOpsExecutor 同款：
 * <ul>
 *   <li><b>懒加载 client</b>：未配置 KubeConfig 时 resolveClient() 返回 null，
 *       执行器返回「未配置目标系统」明确错误——系统不崩溃（离线基调同族）；</li>
 *   <li><b>protected resolveClient()</b>：测试子类注入 mock client（轨道 A）——
 *       CI 契约测试不需要真集群；</li>
 *   <li><b>凭证隔离</b>：走 KubeConfig（~/.kube/config 或 KUBECONFIG），
 *       凭证不进仓库、不落日志。</li>
 * </ul>
 * </p>
 */
public abstract class AbstractK8sHealingExecutor implements ActionExecutor {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 懒加载的 KubernetesClient；未配置集群时为 null。 */
    private volatile KubernetesClient client;

    /**
     * 写动作允许的命名空间硬护栏（executor 侧双重保护——白名单 targetPattern
     * 是配置层，本字段是代码层，配置配错也伤不到 prod）。
     */
    @Value("${devops.healing.k8s.allowed-namespaces:staging,dev}")
    protected String allowedNamespaces = "staging,dev";

    /**
     * 爆炸半径比率（§7.2 3-2.5）：单次操作影响 Pod 数 ≤ 总数 × ratio。
     * 公式容许下限 1（{@code max(1, ceil(total × ratio))}）——
     * 单实例优雅重启属于 V1.x 允许口径（K8sOpsExecutor 注释的「强制单实例」）。
     */
    @Value("${devops.healing.k8s.max-blast-ratio:0.2}")
    protected double maxBlastRatio = 0.2;

    /** 副本数硬上限（与白名单 param_schema 种子的 max=10 对齐）。 */
    @Value("${devops.healing.k8s.max-replicas:10}")
    protected int maxReplicas = 10;

    /** 测试子类注入 mock client 的接缝（轨道 A）。 */
    protected KubernetesClient resolveClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    try {
                        client = new KubernetesClientBuilder().build();
                    } catch (Throwable t) {
                        client = null;
                        log.warn("[K8sHealing] KubeConfig 未就绪，client 置空 | {}",
                                t.getClass().getSimpleName());
                    }
                }
            }
        }
        return client;
    }

    /** 未配置集群的统一失败（不崩溃、不静默）。 */
    protected ExecutionResult noCluster(String actionKey, boolean dryRun) {
        return ExecutionResult.fail(executorKey(), actionKey, dryRun,
                "未配置 K8s 集群（KubeConfig 缺失）。请配置 ~/.kube/config 或 KUBECONFIG 后重试");
    }

    /** 命名空间硬护栏判定。 */
    protected boolean namespaceAllowed(String namespace) {
        Set<String> allowed = Arrays.stream(allowedNamespaces.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return allowed.contains(namespace);
    }

    /** 爆炸半径判定：affected 是否超过容许值（下限 1）。 */
    protected boolean blastExceeded(int affected, int total) {
        int ceiling = Math.max(1, (int) Math.ceil(total * maxBlastRatio));
        return affected > ceiling;
    }
}
