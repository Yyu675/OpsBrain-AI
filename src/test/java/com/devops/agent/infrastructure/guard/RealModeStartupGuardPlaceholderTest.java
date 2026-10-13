package com.devops.agent.infrastructure.guard;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelRepository;
import com.devops.agent.infrastructure.cache.SemanticCacheService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RealModeStartupGuard} 第 0 步：占位 key 启动拦截（批 88 A 阶段 + P1 DB 权威源适配）。
 *
 * <p>P1 起 key 来源为 DB 优先：mock repo 返回空时回落 yml（既有测试路径），
 * DB 有真 key 且 yml 为占位时本题加新例验证不误拦。</p>
 */
class RealModeStartupGuardPlaceholderTest {

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final ModelFingerprintGuard fingerprintGuard = mock(ModelFingerprintGuard.class);
    private final SemanticCacheService semanticCacheService = mock(SemanticCacheService.class);
    private final AiChannelRepository channelRepo = mock(AiChannelRepository.class);
    private RealModeStartupGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RealModeStartupGuard(embeddingModel, fingerprintGuard, semanticCacheService, channelRepo);
        ReflectionTestUtils.setField(guard, "vectorDimension", 1536);
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(new float[1536])));
        when(fingerprintGuard.verifyOrRecord()).thenReturn(null);
        // 默认 DB 无渠道 → 回落 yml（保持既有测试语义）
        when(channelRepo.findByKey(anyString())).thenReturn(Optional.empty());
    }

    private void keys(String chat, String embedding) {
        ReflectionTestUtils.setField(guard, "ymlChatKey", chat);
        ReflectionTestUtils.setField(guard, "ymlEmbeddingKey", embedding);
    }

    @Nested
    @DisplayName("占位 key 拒启（第 0 步，不打网络）")
    class PlaceholderRejected {

        @Test
        @DisplayName("chat 渠道 your-alibaba-api-key-here → 拒启，且不调 embedding")
        void rejectsChatPlaceholder() {
            keys("your-alibaba-api-key-here", "sk-ws-H.realkey123456");
            assertThatThrownBy(guard::verifyOnStartup)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("占位符")
                    .hasMessageContaining("your-alibaba-api-key-here");
            verify(embeddingModel, never()).embed(anyString());
        }

        @Test
        @DisplayName("embedding 渠道 your-real-embedding-api-key-here → 拒启")
        void rejectsEmbeddingPlaceholder() {
            keys("sk-ws-H.realkey123456", "your-real-embedding-api-key-here");
            assertThatThrownBy(guard::verifyOnStartup)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("占位符");
            verify(embeddingModel, never()).embed(anyString());
        }

        @Test
        @DisplayName("空串 / blank 同样拒启（未配 key 与占位同罪）")
        void rejectsBlank() {
            keys("   ", "sk-ws-H.realkey123456");
            assertThatThrownBy(guard::verifyOnStartup)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("占位符");
            verify(embeddingModel, never()).embed(anyString());
        }

        @Test
        @DisplayName("startsWith your- 的任意占位都拒")
        void rejectsYourPrefix() {
            keys("your-real-chat-api-key-here", "sk-ws-H.realkey123456");
            assertThatThrownBy(guard::verifyOnStartup)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("占位符");
        }
    }

    @Nested
    @DisplayName("配额耗尽分类（批93：上游 FreeTierOnly 400 ≠ 配置不可达）")
    class QuotaExhaustedClassified {

        @Test
        @DisplayName("上游回执 AllocationQuota.FreeTierOnly → 拒启且报「配额」修法（充值/关免费档），不误导去查配置")
        void classifiesQuotaExhaustion() {
            keys("sk-ws-H.realkey123456", "sk-real-embedding-x123");
            when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException(
                    "java.lang.RuntimeException: Error: 400 - "
                    + "{\"error\":{\"message\":\"Free quota exhausted. To continue accessing the model on a paid basis, "
                    + "please add funds or disable the \\\"use free tier only\\\" mode in the management console.\","
                    + "\"type\":\"AllocationQuota.FreeTierOnly\",\"param\":null,"
                    + "\"code\":\"AllocationQuota.FreeTierOnly\"},"
                    + "\"id\":\"481cbb29-1a72-9df1-ae97-ef06052969b6\"}"));

            // 断言即批93 台账口径：报「配额」、给充值修法、保留上游回执原文、不冒充配置错误
            assertThatThrownBy(guard::verifyOnStartup)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("配额已耗尽")
                    .hasMessageContaining("FreeTierOnly")
                    .hasMessageContaining("无需改 base-url")
                    .hasMessageContaining("Free quota exhausted")
                    .hasMessageNotContaining("渠道不可达");
        }

        @Test
        @DisplayName("非配额故障（网络/鉴权）仍走「渠道不可达」旧口径——分类不能反向误伤")
        void keepsUnreachableMessageOtherwise() {
            keys("sk-ws-H.realkey123456", "sk-real-embedding-x123");
            when(embeddingModel.embed(anyString()))
                    .thenThrow(new RuntimeException("Connection refused: dashscope.aliyuncs.com:443"));

            assertThatThrownBy(guard::verifyOnStartup)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("渠道不可达")
                    .hasMessageContaining("base-url")
                    .hasMessageNotContaining("配额已耗尽");
        }
    }

    @Test
    @DisplayName("P1 DB 权威源：yml 是占位 key 但 DB 有真 key → 不误拦（利用 DB 有效值通过占位检测）")
    void dbKeyOverridesPlaceholderPreventsFalseRejection() {
        keys("your-alibaba-api-key-here", "sk-ws-H.realkey123456");
        // chat 渠道 DB 里存了明文真实 key（model_key_crypt_secret 未配时明文落库）
        when(channelRepo.findByKey("chat")).thenReturn(Optional.of(
                new AiChannel("chat", "https://x", "sk-real-chat-x123", "sk-re****x123",
                        "t", "r", null, null, "ACTIVE", null)));

        // 不应抛异常：DB 里的实 key 覆盖了 yml 占位
        assertThatCode(guard::verifyOnStartup).doesNotThrowAnyException();
    }
}