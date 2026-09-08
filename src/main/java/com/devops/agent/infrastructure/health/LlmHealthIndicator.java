package com.devops.agent.infrastructure.health;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * <p>实现说明：用 langchain4j 同步 {@link ChatModel} 发一个极小的 probe
 * 消息验证连通。单条探测失败即 DOWN；探测本身失败（超时/网络）记为 DOWN
 * 并带原因，由调度层/告警层决定是否重试——本指示器不做重试，保持幂等无副作用。</p>
 *
 * <p><b>为什么必须写 {@code @Qualifier("turboModel")}</b>：
 * {@code AiModelConfig} 在 REAL 与 MOCK 两种模式下<b>各自都注册了两个
 * {@code ChatModel}</b>（turboModel / reasonerModel）。按类型注入会抛
 * {@code NoUniqueBeanDefinitionException}，且它发生在 ApplicationContext
 * 启动阶段——后果不是这一个指示器不可用，而是<b>整个 Spring 上下文起不来，
 * 所有 {@code @SpringBootTest} 连锁失败</b>（CI 上表现为大批
 * "Failed to load ApplicationContext"，噪音掩盖真因）。
 * 项目内其余 ChatModel 注入点（HealthCheckController、AgentEngineConfig）
 * 均已用 {@code @Qualifier} 消歧，本类补齐该约定。</p>
 *
 * <p>选 turbo 而非 reasoner：健康探测要的是<b>快而便宜</b>。
 * reasoner 超时是 turbo 的两倍、单价更高，用它探活会让探针本身变成负担。</p>
 */
@Component
public class LlmHealthIndicator implements HealthIndicator {

    private final ChatModel chatModel;

    public LlmHealthIndicator(@Qualifier("turboModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Health health() {
        try {
            // 最小探测：只验证连通与鉴权，不产生有意义回答
            String reply = chatModel.chat("ping");
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
