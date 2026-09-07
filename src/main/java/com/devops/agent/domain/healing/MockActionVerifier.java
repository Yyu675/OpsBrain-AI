package com.devops.agent.domain.healing;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mock 验证器（S3-3 双轨的离线一轨）——验证所有 {@code mock.*} 执行。
 * <p>
 * 与 MockChatModel/MockActionExecutor 同族：让「执行 → 验证 →（失败）回滚 →
 * 升级人工」的完整闭环在无真集群时全程可联调、可演示、可作 CI 契约。
 * </p>
 * <p>
 * 行为：合成一条「错误率改善」曲线（before 取自执行参数回声，after 收敛到
 * 低位）。测试探针：动作参数带 {@code simulateVerifyFail=true} 时返回
 * UNHEALTHY——这是无真集群下演练自动回滚+升级链路的唯一通道。
 * </p>
 */
@Component
public class MockActionVerifier implements ActionVerifier {

    @Override
    public String verifierKey() {
        return "mock";
    }

    @Override
    public boolean supports(String actionKey) {
        return actionKey != null && actionKey.startsWith("mock.");
    }

    @Override
    public VerificationResult verify(HealingAction action, ExecutionResult execution) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("errorRate", 0.12);
        before.put("note", "执行参数回声: " + action.params());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("errorRate", 0.01);

        if (Boolean.TRUE.equals(action.params().get("simulateVerifyFail"))) {
            after.put("errorRate", 0.35);
            return VerificationResult.unhealthy(
                    "MOCK 验证失败：错误率 0.12 -> 0.35 不降反升（simulateVerifyFail 探针）",
                    before, after);
        }
        return VerificationResult.healthy(
                "MOCK 验证通过：错误率 0.12 -> 0.01", before, after);
    }
}
