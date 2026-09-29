package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelRepository;
import com.devops.agent.domain.ai.AiChannelService;
import com.devops.agent.domain.ai.ChannelUpdate;
import com.devops.agent.infrastructure.ai.ChannelRefreshService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ModelChannelController} HTTP 契约测试（阶段A：渠道配置只读展示）。
 *
 * <h3>安全底线：明文/密文 key 永不外泄</h3>
 * 本切片 mock 掉仓储返回的 {@code AiChannel} 里带 {@code apiKeyEnc}
 * （{@code ApiKeyCrypt} 加密后的 key），并断言响应里<b>不存在</b>这条字段——
 * 「DB 存了 key，但 API 给前端的一律是脱敏串」是这条只读展示链路成立的前提。
 * 若哪天有字段透出，此测试即红灯。
 *
 * <h3>覆盖重点</h3>
 * <ul>
 *   <li>列表返回三渠道（chat/embedding/reranker）各自的干净视图字段；</li>
 *   <li>{@code apiKeyEnc} 不序列化——API 响应对象与实体是分离的 DTO，不是透传实体；</li>
 *   <li>{@code maskedKey} 有值而明文不存在；</li>
 *   <li>仓储无数据时返回空数组而非 null（前端可免空值防御）。</li>
 * </ul>
 *
 * <h3>权限边界声明</h3>
 * 控制器标了 {@code @SaCheckRole("ADMIN")}，但其注解拦截器注册在
 * {@code WebConfig}，本切片刻意排除它——因此本类请求均为「已放行」状态，
 * <b>不构成对 ADMIN 权限的任何保证</b>（与 {@code AgentTraceControllerWebTest}
 * 同一处理）。「非 ADMIN 拒绝访问」须由专门的鉴权集成测试覆盖。
 *
 * @author OpsBrain AI
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = ModelChannelController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        com.devops.agent.controller.config.WebConfig.class,
                        com.devops.agent.common.audit.OperationAuditInterceptor.class
                }),
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, com.devops.agent.common.web.TraceIdFilter.class})
class ModelChannelControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @MockitoBean
    private AiChannelRepository repo;

    @MockitoBean
    private AiChannelService channelService;

    @MockitoBean
    private com.devops.agent.domain.ai.AiChannelHistoryRepository historyRepo;

    @MockitoBean
    private ChannelRefreshService refreshService;

    @MockitoBean
    private com.devops.agent.infrastructure.ai.ChannelCapabilityProbe capabilityProbe;

    @MockitoBean
    private com.devops.agent.domain.ai.ChannelTemplateRepository templateRepo;

    @MockitoBean
    @Qualifier("turboModel")
    private ChatModel turboModel;

    @MockitoBean
    @Qualifier("embeddingModel")
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    // ==================== 夹具 ====================

    /**
     * 模拟 DB 里真实存在的一行——{@code apiKeyEnc} 承载 {@code ApiKeyCrypt}
     * 加密后的 key（阶段A 落库形态）。测试断言它绝不透出 API。
     */
    private static AiChannel channel(String key, String baseUrl, String encKey, String masked,
                                     String model, Integer dim) {
        return new AiChannel(key, baseUrl, encKey, masked, null, null, model, dim,
                "ACTIVE", LocalDateTime.of(2026, 9, 20, 10, 0));
    }

    // ==================================================================

    @Nested
    @DisplayName("渠道列表（只读展示）")
    class ListChannels {

        @Test
        @DisplayName("返回三渠道视图：脱敏 key 在场，明文/密文 apiKeyEnc 永不外泄")
        void listsChannelsWithoutExposingKey() throws Exception {
            when(repo.findAll()).thenReturn(List.of(
                    channel("chat", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                            "enc:v1:AbCdEfGhIjKlMnOp", "sk-ws-****7890", "qwen-turbo", null),
                    channel("embedding", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                            "enc:v1:XyZw1234567890ab", "sk-ws-****1234", "qwen3.7-text-embedding", 1536)));

            mockMvc.perform(get("/api/v1/model-channels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.channels.length()").value(2))
                    .andExpect(jsonPath("$.data.channels[0].channelKey").value("chat"))
                    .andExpect(jsonPath("$.data.channels[0].baseUrl")
                            .value("https://dashscope.aliyuncs.com/compatible-mode/v1"))
                    .andExpect(jsonPath("$.data.channels[0].maskedKey").value("sk-ws-****7890"))
                    .andExpect(jsonPath("$.data.channels[0].dimension", nullValue()))
                    // ---- 安全底线：apiKeyEnc 绝不出现 ----
                    .andExpect(jsonPath("$.data.channels[0].apiKeyEnc").doesNotExist())
                    .andExpect(jsonPath("$.data.channels[0].api_key_enc").doesNotExist())
                    .andExpect(jsonPath("$.data.channels[1].channelKey").value("embedding"))
                    .andExpect(jsonPath("$.data.channels[1].maskedKey").value("sk-ws-****1234"))
                    .andExpect(jsonPath("$.data.channels[1].dimension").value(1536))
                    .andExpect(jsonPath("$.data.channels[1].apiKeyEnc").doesNotExist());
        }

        @Test
        @DisplayName("仓储无渠道时返回空数组而非 null（前端免空值防御）")
        void emptyListReturnsEmptyArray() throws Exception {
            when(repo.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/model-channels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.channels.length()").value(0));
        }

        @Test
        @DisplayName("某渠道 key 未配置（DB 里 api_key_enc 为 null）→ maskedKey null，仍正常展示其它字段")
        void unmaskedWhenNoKeyConfigured() throws Exception {
            when(repo.findAll()).thenReturn(List.of(
                    channel("reranker", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                            null, null, "qwen3-reranker-8b", null)));

            mockMvc.perform(get("/api/v1/model-channels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.channels[0].channelKey").value("reranker"))
                    .andExpect(jsonPath("$.data.channels[0].maskedKey", nullValue()))
                    .andExpect(jsonPath("$.data.channels[0].apiKeyEnc").doesNotExist());
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("渠道编辑（P1+P3，PUT /{channelKey}，热更新即时生效）")
    class UpdateChannel {

        @Test
        @DisplayName("编辑成功：脱敏 key 在场、密文不离库、热更新成功 restartRequired=false（即时生效）")
        void updateReturnsMaskedViewAndRestartFlag() throws Exception {
            when(channelService.update(any(ChannelUpdate.class), any()))
                    .thenReturn(new AiChannel("chat", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                            "enc:v1:AbCdEfGhIjKlMnOp", "sk-ws-****7890",
                            "qwen-turbo", "deepseek-v4-flash-0731", null, null,
                            "ACTIVE", LocalDateTime.of(2026, 9, 20, 10, 0)));

            // P3-1：refreshService.refresh("chat") 成功 → 热更新即时生效，无需重启
            when(refreshService.refresh("chat")).thenReturn("chat 渠道已热更新");

            mockMvc.perform(put("/api/v1/model-channels/chat")
                            .contentType("application/json")
                            .content("{\"baseUrl\":\"https://dashscope.aliyuncs.com/compatible-mode/v1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.channelKey").value("chat"))
                    .andExpect(jsonPath("$.data.maskedKey").value("sk-ws-****7890"))
                    .andExpect(jsonPath("$.data.restartRequired").value(false))
                    // 安全底线：密文绝不出 API
                    .andExpect(jsonPath("$.data.apiKeyEnc").doesNotExist());
        }

        @Test
        @DisplayName("编辑保存成功但热更新失败 → restartRequired=true（回退重启生效，保存不丢）")
        void updateWithFailedHotReloadFallsBackToRestart() throws Exception {
            when(channelService.update(any(ChannelUpdate.class), any()))
                    .thenReturn(new AiChannel("embedding", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                            "enc:v1:XyZw1234567890ab", "sk-ws-****1234",
                            null, null, "qwen3.7-text-embedding", 1536,
                            "ACTIVE", LocalDateTime.of(2026, 9, 20, 10, 0)));

            // P3-1：热更新抛异常 → 保存仍成功，但需重启才生效（降级不丢配置）
            when(refreshService.refresh("embedding")).thenThrow(new IllegalStateException("维度不符"));

            mockMvc.perform(put("/api/v1/model-channels/embedding")
                            .contentType("application/json")
                            .content("{\"model\":\"qwen3.7-text-embedding\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.channelKey").value("embedding"))
                    .andExpect(jsonPath("$.data.restartRequired").value(true));
        }

        @Test
        @DisplayName("渠道不存在 → 404（不静默成功）")
        void unknownChannelReturns404() throws Exception {
            when(channelService.update(any(ChannelUpdate.class), any()))
                    .thenThrow(new IllegalStateException("渠道不存在: unknown-key"));

            mockMvc.perform(put("/api/v1/model-channels/unknown-key")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));
        }

        @Test
        @DisplayName("参数不合法（占位 key / 坏 base-url / 未知渠道）→ 400")
        void invalidInputReturns400() throws Exception {
            when(channelService.update(any(ChannelUpdate.class), any()))
                    .thenThrow(new IllegalArgumentException("拒绝占位 API Key（your-… / …-here）"));

            mockMvc.perform(put("/api/v1/model-channels/chat")
                            .contentType("application/json")
                            .content("{\"apiKey\":\"your-placeholder-api-key-here\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("渠道重置（POST /{channelKey}/reset，重置后同步热更新）")
    class ResetChannel {

        @Test
        @DisplayName("重置成功且热更新成功 → restartRequired=false（即时生效）")
        void resetWithHotReloadSucceeds() throws Exception {
            when(repo.findByKey("chat")).thenReturn(java.util.Optional.of(
                    channel("chat", "https://api.deepseek.com/v1", "enc:v1:abc", "sk-****0000", null, null)));

            when(refreshService.refresh("chat")).thenReturn("chat 渠道已热更新");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/chat/reset"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.channelKey").value("chat"))
                    .andExpect(jsonPath("$.data.restartRequired").value(false))
                    .andExpect(jsonPath("$.data.apiKeyEnc").doesNotExist());
        }

        @Test
        @DisplayName("重置成功但热更新失败 → restartRequired=true（配置已落库不丢，降级重启生效）")
        void resetWithHotReloadFailureFallsBack() throws Exception {
            when(repo.findByKey("chat")).thenReturn(java.util.Optional.of(
                    channel("chat", "https://api.deepseek.com/v1", "enc:v1:abc", "sk-****0000", null, null)));
            when(refreshService.refresh("chat")).thenThrow(new IllegalStateException("模型构建失败"));

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/chat/reset"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.restartRequired").value(true));
        }

        @Test
        @DisplayName("未知渠道 → 400（不静默成功）")
        void resetUnknownChannelReturns400() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/unknown/reset"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("重置前把当前自定义行快照进历史表（V5：重置错了可回滚）")
        void resetSnapshotsCurrentRowIntoHistory() throws Exception {
            AiChannel current = channel("chat", "https://custom.example.com/v1",
                    "enc:v1:Custom", "sk-cu-****0000", "custom-model", null);
            when(repo.findByKey("chat")).thenReturn(java.util.Optional.of(current));

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/chat/reset"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            org.mockito.ArgumentCaptor<com.devops.agent.domain.ai.AiChannelHistory> captor =
                    org.mockito.ArgumentCaptor.forClass(com.devops.agent.domain.ai.AiChannelHistory.class);
            org.mockito.Mockito.verify(historyRepo).snapshot(captor.capture());
            com.devops.agent.domain.ai.AiChannelHistory snap = captor.getValue();
            assertThat(snap.baseUrl()).isEqualTo("https://custom.example.com/v1");
            assertThat(snap.apiKeyEnc()).isEqualTo("enc:v1:Custom");
            assertThat(snap.changeNote()).contains("重置");
        }

        @Test
        @DisplayName("快照失败不阻断重置（历史是兜底，主流程降级不丢）")
        void resetSnapshotFailureDoesNotBlock() throws Exception {
            when(repo.findByKey("chat")).thenReturn(java.util.Optional.of(
                    channel("chat", "https://custom.example.com/v1", "enc:v1:Custom", "sk-cu-****0000", "m", null)));
            org.mockito.Mockito.doThrow(new RuntimeException("history table missing"))
                    .when(historyRepo).snapshot(org.mockito.ArgumentMatchers.any());

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/chat/reset"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("变更历史与回滚（V5）")
    class HistoryAndRollback {

        private com.devops.agent.domain.ai.AiChannelHistory historyRow(long id, String key,
                String baseUrl, String encKey, String masked, String turbo) {
            return new com.devops.agent.domain.ai.AiChannelHistory(id, key, baseUrl, encKey, masked,
                    turbo, "deepseek-v4-flash-0731", null, null, "ACTIVE",
                    null, null, null, null,
                    LocalDateTime.of(2026, 9, 22, 10, 0), "admin", "编辑");
        }

        @Test
        @DisplayName("拉历史：返回脱敏视图，密文 apiKeyEnc 永不外泄")
        void historyReturnsMaskedViewWithoutCipher() throws Exception {
            when(channelService.history("chat", 20)).thenReturn(List.of(
                    historyRow(5L, "chat", "https://api.deepseek.com/v1", "enc:v1:Secret", "sk-ws-****7890", "qwen-turbo")));

            mockMvc.perform(get("/api/v1/model-channels/chat/history").param("limit", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.history.length()").value(1))
                    .andExpect(jsonPath("$.data.history[0].id").value(5))
                    .andExpect(jsonPath("$.data.history[0].maskedKey").value("sk-ws-****7890"))
                    .andExpect(jsonPath("$.data.history[0].turboModel").value("qwen-turbo"))
                    .andExpect(jsonPath("$.data.history[0].changedBy").value("admin"))
                    // ---- 安全底线：历史表同样承载密文，绝不透出 ----
                    .andExpect(jsonPath("$.data.history[0].apiKeyEnc").doesNotExist())
                    .andExpect(jsonPath("$.data.history[0].fallbackApiKeyEnc").doesNotExist());
        }

        @Test
        @DisplayName("回滚成功且热更新成功 → restartRequired=false，返回回滚后视图")
        void rollbackWithHotReloadSucceeds() throws Exception {
            when(channelService.rollback("chat", 5L, "system")).thenReturn(
                    channel("chat", "https://api.deepseek.com/v1", "enc:v1:Old", "sk-ws-****7890", "qwen-turbo", null));
            when(refreshService.refresh("chat")).thenReturn("chat 渠道已热更新");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/chat/rollback/5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.channelKey").value("chat"))
                    .andExpect(jsonPath("$.data.restartRequired").value(false))
                    .andExpect(jsonPath("$.data.apiKeyEnc").doesNotExist());
        }

        @Test
        @DisplayName("回滚落库成功但热更新失败 → restartRequired=true（配置已回滚不丢）")
        void rollbackWithHotReloadFailureFallsBackToRestart() throws Exception {
            when(channelService.rollback("chat", 5L, "system")).thenReturn(
                    channel("chat", "https://api.deepseek.com/v1", "enc:v1:Old", "sk-ws-****7890", "qwen-turbo", null));
            when(refreshService.refresh("chat")).thenThrow(new IllegalStateException("模型构建失败"));

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/chat/rollback/5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.restartRequired").value(true));
        }

        @Test
        @DisplayName("回滚历史不存在 → 404；跨渠道回滚 → 400")
        void rollbackValidationErrors() throws Exception {
            when(channelService.rollback("chat", 99L, "system"))
                    .thenThrow(new IllegalStateException("历史版本不存在: #99"));
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/chat/rollback/99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));

            when(channelService.rollback("chat", 8L, "system"))
                    .thenThrow(new IllegalArgumentException("历史版本 #8 属于渠道 embedding，不能回滚到 chat"));
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/chat/rollback/8"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("能力探测（P4：三态语义 + 落库复用）")
    class CapabilityProbe {

        @Test
        @DisplayName("触发探测：返回三态结果（SUPPORTED/UNSUPPORTED/UNKNOWN 各字段齐全）")
        void probeReturnsTriStateResult() throws Exception {
            when(capabilityProbe.probe("chat")).thenReturn(new com.devops.agent.infrastructure.ai.ChannelCapabilityProbe.ProbeResult(
                    "chat",
                    java.util.Map.of(
                            "chat", new com.devops.agent.infrastructure.ai.ChannelCapabilityProbe.CapabilityItem("SUPPORTED", "基础对话正常"),
                            "function_calling", new com.devops.agent.infrastructure.ai.ChannelCapabilityProbe.CapabilityItem("UNSUPPORTED", "不支持工具调用"),
                            "streaming", new com.devops.agent.infrastructure.ai.ChannelCapabilityProbe.CapabilityItem("UNKNOWN", "超时")),
                    "2026-09-23T10:00:00"));

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/chat/capability-probe"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.channelKey").value("chat"))
                    .andExpect(jsonPath("$.data.capabilities.chat.state").value("SUPPORTED"))
                    .andExpect(jsonPath("$.data.capabilities.chat.detail").value("基础对话正常"))
                    .andExpect(jsonPath("$.data.capabilities.function_calling.state").value("UNSUPPORTED"))
                    .andExpect(jsonPath("$.data.capabilities.streaming.state").value("UNKNOWN"));
        }

        @Test
        @DisplayName("触发探测：渠道不存在 → 404")
        void probeUnknownChannelReturns404() throws Exception {
            when(capabilityProbe.probe("unknown-key"))
                    .thenThrow(new IllegalStateException("渠道不存在: unknown-key"));

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/model-channels/unknown-key/capability-probe"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));
        }

        @Test
        @DisplayName("读已落库结果：不重新实测，返回存储态（含 probedAt）")
        void readStoredCapabilitiesWithoutReProbe() throws Exception {
            when(capabilityProbe.readStored("chat")).thenReturn(new com.devops.agent.infrastructure.ai.ChannelCapabilityProbe.ProbeResult(
                    "chat",
                    java.util.Map.of("embed", new com.devops.agent.infrastructure.ai.ChannelCapabilityProbe.CapabilityItem("SUPPORTED", "向量化正常，维度 1536")),
                    "2026-09-23T09:00:00"));

            mockMvc.perform(get("/api/v1/model-channels/chat/capabilities"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.capabilities.embed.state").value("SUPPORTED"))
                    .andExpect(jsonPath("$.data.probedAt").value("2026-09-23T09:00:00"));

            // 关键：读操作不触发探测（省钱契约）
            org.mockito.Mockito.verify(capabilityProbe, org.mockito.Mockito.never()).probe(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("从未探测过 → data 为 null（前端渲染「未实测」态）")
        void noStoredResultReturnsNullData() throws Exception {
            when(capabilityProbe.readStored("chat")).thenReturn(null);

            mockMvc.perform(get("/api/v1/model-channels/chat/capabilities"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data", nullValue()));
        }
    }
}
