package com.devops.agent.infrastructure.ai;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelRepository;
import com.devops.agent.infrastructure.AiModelConfig;
import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import com.devops.agent.infrastructure.llm.LlmEndpointSpec;
import com.devops.agent.infrastructure.llm.OpenAiCompatibleModelFactory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 渠道热更新引擎（P3-1：编辑保存 → 即时生效，无需重启）。</p>
 *
 * <p>AiModelConfig 把 REAL 模型 Bean 包在 Refreshable 包装器里注入 Spring；
 * 编辑保存后 Controller 调 refresh(channelKey)→从 DB 读最新配置重建模型→原子替换。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "devops.ai.mode", havingValue = "REAL")
public class ChannelRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ChannelRefreshService.class);

    private final RefreshableChatModel turboChatModel;
    private final RefreshableChatModel reasonerChatModel;
    private final RefreshableStreamingChatModel turboStreamingModel;
    private final RefreshableStreamingChatModel reasonerStreamingModel;
    private final RefreshableEmbeddingModel embeddingModel;
    private final AiModelConfig aiModelConfig;
    private final SemanticCacheService semanticCache;

    // 5 个参数均须 @Qualifier：turboModel/reasonerModel 同为 RefreshableChatModel、
    // turboStreamingModel/reasonerStreamingModel 同为 RefreshableStreamingChatModel，
    // 无限定符时 Spring 无法消歧 → NoUniqueBeanDefinitionException，REAL 上下文起不来。
    public ChannelRefreshService(
            @org.springframework.beans.factory.annotation.Qualifier("turboModel") RefreshableChatModel turboChatModel,
            @org.springframework.beans.factory.annotation.Qualifier("reasonerModel") RefreshableChatModel reasonerChatModel,
            @org.springframework.beans.factory.annotation.Qualifier("turboStreamingModel") RefreshableStreamingChatModel turboStreamingModel,
            @org.springframework.beans.factory.annotation.Qualifier("reasonerStreamingModel") RefreshableStreamingChatModel reasonerStreamingModel,
            @org.springframework.beans.factory.annotation.Qualifier("embeddingModel") RefreshableEmbeddingModel embeddingModel,
            AiModelConfig aiModelConfig, SemanticCacheService semanticCache) {
        this.turboChatModel = turboChatModel;
        this.reasonerChatModel = reasonerChatModel;
        this.turboStreamingModel = turboStreamingModel;
        this.reasonerStreamingModel = reasonerStreamingModel;
        this.embeddingModel = embeddingModel;
        this.aiModelConfig = aiModelConfig;
        this.semanticCache = semanticCache;
    }

    public String refresh(String channelKey) {
        try {
            if (AiChannel.KEY_CHAT.equals(channelKey)) {
                turboChatModel.refreshTo(aiModelConfig.buildTurboChat());
                reasonerChatModel.refreshTo(aiModelConfig.buildReasonerChat());
                turboStreamingModel.refreshTo(aiModelConfig.buildTurboStreaming());
                reasonerStreamingModel.refreshTo(aiModelConfig.buildReasonerStreaming());
                log.info("🔄 [ChannelRefresh] chat 渠道热更新完成");
                return "chat 渠道已热更新（turbo+reasoner+流式全部生效）";
            }
            if (AiChannel.KEY_EMBEDDING.equals(channelKey)) {
                embeddingModel.refreshTo(aiModelConfig.buildEmbedding());
                // 换 embedding 模型后旧向量的语义缓存命中会造成串库，必须同步失效；
                // 缓存清空失败不阻断热更新（模型替换已完成），但留 WARN 线索（静默 catch 契约）。
                try { semanticCache.clearAllCache(); }
                catch (Exception e) { log.warn("⚠️ [ChannelRefresh] 语义缓存清空失败（embedding 已热更新，缓存失效需人工确认）| {}", e.toString()); }
                log.info("🔄 [ChannelRefresh] embedding 渠道热更新完成 + 缓存清空");
                return "embedding 渠道已热更新（语义缓存已同步清空）";
            }
            return channelKey + " 渠道暂不支持热更新";
        } catch (Exception e) {
            throw new IllegalStateException("热更新失败：" + e.getMessage(), e);
        }
    }

    /** P2-3 连通性测试（委托自 Controller，避免 Controller 持有模型引用）。 */
    public record ConnectivityResult(boolean success, long latencyMs, Integer dimension, String message) {}

    public ConnectivityResult testConnectivity(String channelKey) {
        long start = System.currentTimeMillis();
        try {
            if (AiChannel.KEY_CHAT.equals(channelKey)) {
                // 直接从 spec 建独立模型探测，绕过 Fallback 装饰器——
                // 否则主模型挂了会被备用透明兜住，连通性反而报「正常」，
                // 把主模型故障藏起来（运维以为健康，实际已在靠备用跑）。
                long t0 = System.currentTimeMillis();
                String mainErr = pingChat(aiModelConfig.turboSpec());
                long mainMs = System.currentTimeMillis() - t0;
                if (mainErr != null) {
                    return new ConnectivityResult(false, mainMs, null, "主模型不通：" + mainErr);
                }
                LlmEndpointSpec fbSpec = aiModelConfig.turboFallbackSpec();
                if (fbSpec == null) {
                    return new ConnectivityResult(true, mainMs, null, "chat 主模型连通正常（" + mainMs + "ms），未配置备用");
                }
                long t1 = System.currentTimeMillis();
                String fbErr = pingChat(fbSpec);
                long fbMs = System.currentTimeMillis() - t1;
                if (fbErr != null) {
                    return new ConnectivityResult(true, mainMs, null,
                            "主模型正常（" + mainMs + "ms），但备用模型不通：" + fbErr + "（降级将不可用）");
                }
                return new ConnectivityResult(true, mainMs + fbMs, null,
                        "主模型正常（" + mainMs + "ms）+ 备用模型正常（" + fbMs + "ms），降级就绪");
            }
            if (AiChannel.KEY_EMBEDDING.equals(channelKey)) {
                Embedding emb = embeddingModel.embed("连通性测试").content();
                long ms = System.currentTimeMillis() - start;
                int dim = emb.vector().length;
                int expected = aiModelConfig.vectorDimension();
                if (dim != expected)
                    return new ConnectivityResult(false, ms, dim, "维度不符：实测" + dim + "≠配置" + expected);
                return new ConnectivityResult(true, ms, dim, "embedding 渠道连通正常，维度" + dim + "（" + ms + "ms）");
            }
            return new ConnectivityResult(false, 0, null, channelKey + " 渠道暂不支持连通性测试");
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getSimpleName();
            if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
            // 失败结果已返回给前端展示，同时留服务端日志便于排查（静默 catch 契约）
            log.warn("[ChannelRefresh] {} 连通性测试失败 | {}", channelKey, msg);
            return new ConnectivityResult(false, ms, null, msg);
        }
    }

    /** 用独立模型实例发一次 ping，返回 null=成功，否则错误摘要。绝不复用装配好的 Bean（绕过 Fallback）。 */
    private String pingChat(LlmEndpointSpec spec) {
        try {
            ChatModel probe = newProbe(spec);
            probe.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from("ping")))
                    .build()).aiMessage().text();
            return null;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            msg = msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
            // 错误摘要同时返回给调用方进 ConnectivityResult，debug 留痕防现场无证据（静默 catch 契约）
            log.debug("[ChannelRefresh] 探针调用失败 | {}", msg);
            return msg;
        }
    }

    /**
     * 探针模型创建接缝（测试子类覆写点）。生产实现走 {@link OpenAiCompatibleModelFactory}；
     * 抽成包级方法是为了让测试能用子类注入桩模型——本项目不引入 mockito-inline，
     * 静态工厂无法 mockStatic（与 KnowledgeWriteGuardTest 同一决策）。
     */
    ChatModel newProbe(LlmEndpointSpec spec) {
        return OpenAiCompatibleModelFactory.chat(spec, false);
    }
}