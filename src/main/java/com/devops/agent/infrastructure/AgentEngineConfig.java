package com.devops.agent.infrastructure;

import com.devops.agent.application.router.DevOpsAgentEngine;
import com.devops.agent.application.runtime.AgentStateManager;
import com.devops.agent.application.runtime.CostQuotaManager;
import com.devops.agent.domain.tools.DevOpsTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 引擎配置 - 双引擎架构（原生流式 + 多轮记忆）
 * <p>
 * 职责：创建 turboAgentEngine 和 reasoningAgentEngine 两个 Bean 实例。
 * 两个引擎均基于 LangChain4j 原生流式（{@link dev.langchain4j.service.TokenStream}），
 * 由 {@code StreamingChatModel} 驱动，支持 token 级流式推送与工具执行回调。
 * </p>
 * <ul>
 *   <li>turboAgentEngine：日常对话引擎（快速响应，低成本）</li>
 *   <li>reasoningAgentEngine：复杂推理引擎（深度分析）</li>
 * </ul>
 * <p>
 * 四层幻觉防护：
 * <ul>
 *   <li>L1: @SystemMessage Prompt 强约束（见 {@link DevOpsAgentEngine}）</li>
 *   <li>L2: 工具白名单（仅注册 DevOpsTools 的 2 个 @Tool 方法）</li>
 *   <li>L3: 工具边界 Schema 校验 + 框架自愈重试（{@code ToolParameterValidator}
 *          在 @Tool 方法内校验，失败抛 {@code IllegalArgumentException}，
 *          LangChain4j 将异常回传模型触发自愈重试）
 *          <br>注：原 {@code RetryLimitedChatModel}（同步 ChatModel 包装器）
 *          与流式模型不兼容，已弃用，L3 语义下沉至工具边界（2026-07-23 决策 A2）</li>
 *   <li>L4: Score 熔断（HybridRetrieverService minScore 0.73 过滤）</li>
 * </ul>
 * <p>
 * 多轮记忆（P0-1，2026-08-12）：
 * <ul>
 *   <li>热记忆（Redis）：最近 N 轮对话，由 Service 层预算裁剪后注入</li>
 *   <li>温记忆（PostgreSQL）：关键事实蒸馏，以 SystemMessage 形式注入</li>
 *   <li>LangChain4j {@link MessageWindowChatMemory} 配合 @MemoryId 按 sessionId 隔离</li>
 * </ul>
 * <p>
 * 双模支持：REAL / MOCK 模式均可装配（两种模式各自提供
 * turboStreamingModel / reasonerStreamingModel Bean），
 * 修复了此前 MOCK 模式下引擎 Bean 缺失导致 DevOpsIntentRouter 注入失败的问题。
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Configuration
public class AgentEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentEngineConfig.class);

    @Value("${devops.ai.memory.max-messages:20}")
    private int maxMessages;

    /**
     * Agent 状态管理器 Bean (MVP-2)
     */
    @Bean
    public AgentStateManager agentStateManager() {
        log.info("🚀 [AgentEngineConfig] 创建 AgentStateManager");
        return new AgentStateManager();
    }

    /**
     * 成本/Token 配额管理器 Bean (MVP-7)
     */
    @Bean
    public CostQuotaManager costQuotaManager() {
        log.info("🚀 [AgentEngineConfig] 创建 CostQuotaManager");
        return new CostQuotaManager();
    }

    /**
     * ChatMemory 存储后端 - 内存存储（多轮记忆）
     * <p>
     * LangChain4j 按 memoryId（即 sessionId）隔离不同会话的对话历史。
     * InMemoryChatMemoryStore 在进程内存中维护多个会话窗口，适用于单实例部署。
     * 分布式部署时可替换为 Redis / PostgreSQL backed store。
     * </p>
     *
     * @return ChatMemoryStore Bean
     */
    @Bean
    public ChatMemoryStore chatMemoryStore() {
        log.info("🚀 [AgentEngineConfig] 创建 InMemoryChatMemoryStore（多轮记忆后端）");
        return new InMemoryChatMemoryStore();
    }

    /**
     * ChatMemory 提供器 - 按 memoryId 创建独立窗口
     * <p>
     * LangChain4j AiServices 在每次调用 {@code engine.chat(memoryId, ...)} 时，
     * 会调用此 provider 获取对应 memoryId 的 ChatMemory 实例。
     * </p>
     * <p>
     * 返回类型必须是 {@link ChatMemoryProvider}（langchain4j 的函数式接口，
     * {@code Object -> ChatMemory}），而非 {@code java.util.function.Function}——
     * {@code AiServices.chatMemoryProvider(...)} 形参类型即此接口，二者虽都是
     * {@code Object -> ChatMemory}，但 Java 不自动把通用 Function 适配为目标
     * 函数式接口，必须显式声明为目标类型。
     * </p>
     * <p>
     * MessageWindowChatMemory 滑动窗口策略：
     * <ul>
     *   <li>maxMessages=20：保留最近 20 条消息（10 轮对话）</li>
     *   <li>超出窗口的历史消息自动丢弃（FIFO）</li>
     *   <li>与预算裁剪配合：Service 层先裁剪，通过此窗口注入引擎</li>
     * </ul>
     *
     * @param chatMemoryStore 存储后端
     * @return ChatMemoryProvider，入参为 memoryId（sessionId）
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore) {
        log.info("🚀 [AgentEngineConfig] 创建 ChatMemoryProvider（窗口上限: {} 条消息）", maxMessages);
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /**
     * Turbo 引擎 Bean - 日常对话（快速响应，原生流式 + 多轮记忆）
     *
     * @param turboStreamingModel Turbo 流式模型 Bean
     * @param devOpsTools         白名单工具 Bean（Spring 托管，@Autowired 字段已注入）
     * @param chatMemoryProvider  ChatMemory 提供器（按 sessionId 隔离）
     * @return turboAgentEngine 实例（TokenStream 引擎）
     */
    @Bean(name = "turboAgentEngine")
    public DevOpsAgentEngine turboAgentEngine(
            @Qualifier("turboStreamingModel") StreamingChatModel turboStreamingModel,
            DevOpsTools devOpsTools,
            ChatMemoryProvider chatMemoryProvider
    ) {
        log.info("🚀 [AgentEngineConfig] 正在创建 turboAgentEngine（流式日常对话引擎 + 多轮记忆）");

        DevOpsAgentEngine engine = AiServices.builder(DevOpsAgentEngine.class)
                .streamingChatModel(turboStreamingModel)  // 原生流式模型
                .tools(devOpsTools)                        // L2: 工具白名单
                .chatMemoryProvider(chatMemoryProvider)    // 多轮记忆：按 sessionId 隔离
                .build();

        log.info("✅ [AgentEngineConfig] turboAgentEngine 创建成功（原生流式 + 工具边界 L3 校验 + 多轮记忆）");
        return engine;
    }

    /**
     * Reasoning 引擎 Bean - 复杂推理（深度分析，原生流式 + 多轮记忆）
     *
     * @param reasonerStreamingModel Reasoning 流式模型 Bean
     * @param devOpsTools            白名单工具 Bean（Spring 托管，@Autowired 字段已注入）
     * @param chatMemoryProvider     ChatMemory 提供器（按 sessionId 隔离）
     * @return reasoningAgentEngine 实例（TokenStream 引擎）
     */
    @Bean(name = "reasoningAgentEngine")
    public DevOpsAgentEngine reasoningAgentEngine(
            @Qualifier("reasonerStreamingModel") StreamingChatModel reasonerStreamingModel,
            DevOpsTools devOpsTools,
            ChatMemoryProvider chatMemoryProvider
    ) {
        log.info("🚀 [AgentEngineConfig] 正在创建 reasoningAgentEngine（流式复杂推理引擎 + 多轮记忆）");

        DevOpsAgentEngine engine = AiServices.builder(DevOpsAgentEngine.class)
                .streamingChatModel(reasonerStreamingModel)  // 原生流式模型
                .tools(devOpsTools)                          // L2: 工具白名单
                .chatMemoryProvider(chatMemoryProvider)      // 多轮记忆：按 sessionId 隔离
                .build();

        log.info("✅ [AgentEngineConfig] reasoningAgentEngine 创建成功（原生流式 + 工具边界 L3 校验 + 多轮记忆）");
        return engine;
    }
}
