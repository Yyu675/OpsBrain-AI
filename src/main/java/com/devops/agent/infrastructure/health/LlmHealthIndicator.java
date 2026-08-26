package com.devops.agent.infrastructure.health;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * LLM（DeepSeek）可达性健康指示器（P2 自身可观测性）。
 *
 * <p>为什么需要：模型挂了系统「表面正常」——页面能开、工单能建，
 * 但 AI 分析/问答全部失败。K8s 探针只看 TCP/HTTP 层探不到这一层。
 * 把它并进 /actuator/health，探针与告警才能在「AI 全废」时给出真实状态。</p>
 *
 * <p>实现说明：用 ChatModel 发一个极小的 probe 消息（maxTokens=1）验证连通。
 * 单条探测失败即 DOWN；探测本身失败（超时/网络）记为 DOWN 并带原因，
 * 由调度层/告警层决定是否重试——本指示器不做重试，保持幂等无副作用。</p>
 */
@Component
public class LlmHealthIndicator implements HealthIndicator {

    private final ChatModel chatModel;

    public LlmHealthIndicator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Health health() {
        try {
            // maxTokens=1 的最小探测：只验证连通与鉴权，不产生有意义回答
            String reply = chatModel.call("ping");
            boolean ok = reply != null && !reply.isBlank();
            return ok
                    ? Health.up().withDetail("model", "deepseek").build()
                    : Health.down().withDetail("reason", "模型返回空响应").build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("reason", "模型调用失败: " + e.getClass().getSimpleName())
                    .withDetail("message", e.getMessage() == null ? "" : e.getMessage())
                    .build();
        }
    }
}
