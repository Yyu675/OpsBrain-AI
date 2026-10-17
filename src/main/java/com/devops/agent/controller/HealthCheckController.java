package com.devops.agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import com.fasterxml.jackson.annotation.JsonInclude;


/**
 * 健康检查 Controller
 * <p>
 * 提供三档健康探测，按风险等级与是否消耗外部付费资源分级开放：
 * <ul>
 *   <li><b>/ping</b>（始终开放）：进程存活探针，零外部调用，零成本，可被 LB/K8s livenessProbe 高频拉取</li>
 *   <li><b>/db、/redis</b>（默认开放）：基础设施连通探针，仅探测本地库与缓存，不调付费 LLM</li>
 *   <li><b>/ai-model</b>（默认关闭）：会真实调用付费 LLM+embedding，<b>每次拉取都产生 API 计费</b>。
 *       仅在显式配置 {@code devops.ai.health.ai-model-enabled=true} 时开放，且不应暴露给匿名公网。
 *       REAL 模式下默认禁用以防被匿名刷接口造成成本失控（P1-7）。</li>
 * </ul>
 * <p>
 * 设计决策（P1-7，2026-08-12）：
 * 原实现把 /ai-model 与 /ping 放在同一无开关 Controller 内，REAL 模式下任意匿名访问者
 * 都能触发付费 LLM+embedding 探测。健康检查本该是"廉价且可被高频探测"的，混入付费探测
 * 与该语义冲突——K8s 默认 probe 间隔 10s，一天可产生 8640 次 LLM 调用。
 * 拆分并加开关后，付费探测仅由运维按需手动触发。
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthCheckController {

    private final DataSource dataSource;

    @Qualifier("turboModel")
    private final ChatModel turboModel;

    @Qualifier("reasonerModel")
    private final ChatModel reasonerModel;

    private final EmbeddingModel embeddingModel;

    @Value("${devops.ai.mode}")
    private String aiMode;

    /**
     * 付费探测开关（devops.ai.health.ai-model-enabled）。
     *
     * <p><b>必须用 {@code @Value} 在方法里手工判断，不能靠
     * {@code @ConditionalOnProperty}</b>——后者是 <b>Bean 注册阶段</b>的条件，
     * 标注在 {@code @GetMapping} 方法上<b>完全不生效</b>：
     * Controller 这个 Bean 一旦注册，它的全部 {@code @RequestMapping} 方法
     * 都会被 {@code RequestMappingHandlerMapping} 扫描并注册成路由，
     * 没有任何一步会去看方法上的 {@code @ConditionalOnProperty}。</p>
     *
     * <p>这个错误此前让 P1-7 的修复完全落空：开关配了（默认 false）、
     * 文档也写了「默认关闭」，但端点实际一直开放。
     * 叠加 {@code WebConfig} 把 {@code /api/v1/health/**} 放进鉴权白名单
     * （为 K8s 探针放行），结果是一个<b>匿名可访问的付费 LLM 端点</b>——
     * 正是 P1-7 声称已经堵上的那个成本失控风险。</p>
     */
    @Value("${devops.ai.health.ai-model-enabled:false}")
    private boolean aiModelProbeEnabled;

    /** Strongly typed probe payloads; NON_NULL keeps each probe's historic wire shape unchanged. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PingResponse(String status, String service, String mode, long timestamp) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DatabaseProbeResponse(long timestamp, String status, String database, String error) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatModelProbeResponse(String status, String response, String modelClass,
                                         String error, String hint) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmbeddingModelProbeResponse(String status, Integer dimension, String modelClass,
                                              String error, String hint) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AiModelProbeResponse(String mode, long timestamp, String overallStatus,
                                       String reason, String hint,
                                       ChatModelProbeResponse turboModel,
                                       ChatModelProbeResponse reasonerModel,
                                       EmbeddingModelProbeResponse embeddingModel) {}

    /**
     * 基础健康检查（进程存活探针）
     * <p>
     * 路径：GET /api/v1/health 或 GET /api/v1/health/ping
     * <b>始终开放</b>，零外部调用，零成本，可被 LB/K8s livenessProbe 高频拉取。
     */
    @GetMapping({"", "/ping"})
    public PingResponse ping() {
        return new PingResponse("UP", "OpsBrain AI DevOps Platform", aiMode, System.currentTimeMillis());
    }

    /**
     * 数据库连通探针
     * <p>
     * 执行 SELECT 1 验证 pgvector 数据库连通性。仅消耗一次连接池借取，不调付费 LLM。
     * 失败返回 503 + 错误信息，便于运维定位数据库故障。
     */
    @GetMapping("/db")
    public DatabaseProbeResponse db() {
        long timestamp = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(3); // 3s 超时
            return new DatabaseProbeResponse(timestamp, valid ? "UP" : "DEGRADED",
                    "PostgreSQL/pgvector", valid ? null : "Connection.isValid returned false within 3s");
        } catch (Exception e) {
            log.error("❌ [HealthCheck] 数据库连通性探测失败", e);
            return new DatabaseProbeResponse(timestamp, "DOWN", null, e.getMessage());
        }
    }

    /**
     * 验证 AI 模型连通性（付费探测，默认关闭）
     * <p>
     * Mock 模式：返回 Mock 模型信息，不调用真实 API。
     * Real 模式：<b>实际调用付费 LLM + embedding API</b>，每次拉取都产生计费。
     * <p>
     * <b>安全开关</b>（P1-7）：仅当 {@code devops.ai.health.ai-model-enabled=true} 时开放，
     * 默认关闭。REAL 模式下匿名可访问的付费探测是成本失控风险，曾可被任意刷接口。
     * 运维需手探测时临时开启或带鉴权调用，不应常开。
     * <p>
     * 即便开启，也仅供运维手动触发——不要接入 K8s probe 高频拉取。
     */
    @GetMapping("/ai-model")
    public AiModelProbeResponse checkAiModel() {
        long timestamp = System.currentTimeMillis();
        if (!aiModelProbeEnabled) {
            log.debug("⏸️ [HealthCheck] AI 模型探测已关闭（devops.ai.health.ai-model-enabled=false）");
            return new AiModelProbeResponse(aiMode, timestamp, "DISABLED",
                    "AI 模型探测默认关闭：它会真实调用付费 LLM 与 embedding API，"
                            + "每次拉取都产生计费。需要时请设置 devops.ai.health.ai-model-enabled=true，"
                            + "并且不要接入 K8s probe 高频拉取。", null, null, null, null);
        }

        log.info("🔍 [HealthCheck] 开始验证 AI 模型连通性，当前模式: {}", aiMode);
        ChatModelProbeResponse turbo = probeChatModel("Turbo", turboModel,
                "devops.ai.chat.base-url / api-key / turbo-model");
        ChatModelProbeResponse reasoner = probeChatModel("Reasoner", reasonerModel,
                "devops.ai.chat.base-url / api-key / reasoner-model");
        EmbeddingModelProbeResponse embedding = probeEmbeddingModel();
        boolean chatUp = "SUCCESS".equals(turbo.status()) || "SUCCESS".equals(reasoner.status());
        boolean embeddingUp = "SUCCESS".equals(embedding.status());

        if (chatUp && embeddingUp) {
            log.info("✅ [HealthCheck] AI 双渠道连通性验证成功");
            return new AiModelProbeResponse(aiMode, timestamp, "SUCCESS", null, null, turbo, reasoner, embedding);
        }
        if (chatUp) {
            log.warn("⚠️ [HealthCheck] embedding 渠道不可用（chat 正常）");
            return new AiModelProbeResponse(aiMode, timestamp, "DEGRADED_EMBEDDING_DOWN", null,
                    "chat 渠道正常，embedding 渠道不可用——检索/摄取将降级，纯对话不受影响",
                    turbo, reasoner, embedding);
        }
        if (embeddingUp) {
            log.warn("⚠️ [HealthCheck] chat 渠道不可用（embedding 正常）");
            return new AiModelProbeResponse(aiMode, timestamp, "DEGRADED_CHAT_DOWN", null,
                    "embedding 渠道正常，chat 渠道不可用——对话能力不可用，摄取/重建不受影响",
                    turbo, reasoner, embedding);
        }
        log.error("❌ [HealthCheck] AI 双渠道均不可用");
        return new AiModelProbeResponse(aiMode, timestamp, "FAILED", null, null, turbo, reasoner, embedding);
    }

    private ChatModelProbeResponse probeChatModel(String label, ChatModel model, String hint) {
        try {
            log.info("   -> 测试 {} 模型（chat 渠道）...", label);
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(UserMessage.from("ping"))
                    .build());
            return new ChatModelProbeResponse("SUCCESS", response.aiMessage().text(),
                    model.getClass().getSimpleName(), null, null);
        } catch (Exception e) {
            log.error("❌ [HealthCheck] chat 渠道（{}）探测失败", label, e);
            return new ChatModelProbeResponse("FAILED", null, null, String.valueOf(e.getMessage()), hint);
        }
    }

    private EmbeddingModelProbeResponse probeEmbeddingModel() {
        try {
            log.info("   -> 测试 Embedding 模型（embedding 渠道）...");
            var response = embeddingModel.embed("test");
            return new EmbeddingModelProbeResponse("SUCCESS", response.content().vector().length,
                    embeddingModel.getClass().getSimpleName(), null, null);
        } catch (Exception e) {
            log.error("❌ [HealthCheck] embedding 渠道探测失败", e);
            return new EmbeddingModelProbeResponse("FAILED", null, null, String.valueOf(e.getMessage()),
                    "检查 devops.ai.embedding.base-url / api-key / model");
        }
    }

}
