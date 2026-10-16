package com.devops.agent.domain.healing;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 验证器注册表（S3-3）：actionKey → 验证器的路由中枢。
 * <p>
 * 与 {@link ExecutorRegistry} 同一默认安全语义，但结论方向相反：
 * 查不到执行器 = 拒绝执行；查不到验证器 = 验证环节标记 SKIPPED
 * （不验证不能倒推力执行失败——但台账上必须留下「未验证」的事实，
 * 绝不能让一条未验证的 SUCCEEDED 看起来像已验证）。
 * </p>
 */
@Component
public class VerifierRegistry {

    private final List<ActionVerifier> verifiers;

    public VerifierRegistry(List<ActionVerifier> verifiers) {
        this.verifiers = List.copyOf(verifiers);
    }

    public Optional<ActionVerifier> locate(String actionKey) {
        return verifiers.stream()
                .filter(v -> v.supports(actionKey))
                .findFirst();
    }

    public List<String> registeredVerifierKeys() {
        return verifiers.stream().map(ActionVerifier::verifierKey).toList();
    }
}
