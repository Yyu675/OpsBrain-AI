package com.devops.agent.domain.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiChannelService} 渠道编辑单测（P1：DB 权威源）。
 *
 * <h3>为什么测领域层而非 Controller</h3>
 * 维度铁律、占位 key 拦截、未知渠道、合并语义——这些一旦只在 HTTP 层校验，
 * 启动镜像 / 未来 CLI 都会绕过。集中在 Service 后按纯逻辑可测。
 */
@DisplayName("AiChannelService 渠道编辑")
class AiChannelServiceTest {

    private static final String BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private final AiChannelRepository repo = mock(AiChannelRepository.class);
    private final AiChannelService service = new AiChannelService(repo, 1536, "test-secret");

    private AiChannel chatRow(String baseUrl, String key, String turbo, String reasoner) {
        return new AiChannel("chat", baseUrl, key, key == null ? null : "sk-ws-****7890",
                turbo, reasoner, null, null, "ACTIVE", LocalDateTime.of(2026, 9, 20, 10, 0));
    }

    private AiChannel embeddingRow(String model) {
        return new AiChannel("embedding", BASE, "enc:v1:Emb", "sk-ws-****1234",
                null, null, model, 1536, "ACTIVE", LocalDateTime.of(2026, 9, 20, 10, 0));
    }

    @Nested
    @DisplayName("chat 渠道合并")
    class ChatMerge {

        @Test
        @DisplayName("null 字段保持既有值；只改传入的 baseUrl 与 turbo")
        void mergesNullFieldsWithExisting() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow(BASE, "enc:v1:Old", "qwen-turbo", "deepseek-v4-flash-0731")));
            when(repo.updateMerged(any())).thenReturn(1);
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow("https://new.example.com/v1", "enc:v1:Old", "qwen-turbo-v2", "deepseek-v4-flash-0731")));

            AiChannel saved = service.update(new ChannelUpdate(
                    "chat", "https://new.example.com/v1", "qwen-turbo-v2", null, null, null, null, null));

            assertThat(saved.baseUrl()).isEqualTo("https://new.example.com/v1");
            assertThat(saved.turboModel()).isEqualTo("qwen-turbo-v2");
            // 未提供 reasoner → 保留既有
            assertThat(saved.reasonerModel()).isEqualTo("deepseek-v4-flash-0731");
        }

        @Test
        @DisplayName("key 未提供 → 保留既有密文与脱敏串（改模型不抹 key）")
        void keyOmittedKeepsEncryptedKey() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow(BASE, "enc:v1:KeepMe", "qwen-turbo", "deepseek-v4-flash-0731")));
            when(repo.updateMerged(any())).thenReturn(1);

            AiChannel saved = service.update(new ChannelUpdate(
                    "chat", BASE, "qwen-turbo-v2", null, null, null, null, null));

            assertThat(saved.apiKeyEnc()).isEqualTo("enc:v1:KeepMe");
            assertThat(saved.maskedKey()).isEqualTo("sk-ws-****7890");
        }

        @Test
        @DisplayName("chat 既有 turbo 为空且 patch 未补 → 400")
        void chatRequiresBothModels() {
            // 既有行 turbo 为空串（历史脏数据），patch 也没提供 → 必须拒绝
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow(BASE, "enc:v1:Old", "", "deepseek-v4-flash-0731")));
            when(repo.updateMerged(any())).thenReturn(1);

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "chat", BASE, null, null, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("turbo");
        }
    }

    @Nested
    @DisplayName("embedding 维度铁律 & 模型必填")
    class EmbeddingRules {

        @Test
        @DisplayName("embedding 改模型名：维度强制取铁律 1536（不因 patch 传 1024 而漂移）")
        void embeddingDimensionPinnedToFingerprint() {
            when(repo.findByKey("embedding")).thenReturn(Optional.of(embeddingRow("old-model")));
            when(repo.updateMerged(any())).thenReturn(1);
            when(repo.findByKey("embedding")).thenReturn(Optional.of(
                    new AiChannel("embedding", BASE, "enc:v1:Emb", "sk-ws-****1234",
                            null, null, "new-model", 1536, "ACTIVE", null)));

            AiChannel saved = service.update(new ChannelUpdate(
                    "embedding", BASE, null, null, "new-model", 1536, null, null));

            assertThat(saved.model()).isEqualTo("new-model");
            assertThat(saved.dimension()).isEqualTo(1536);
        }

        @Test
        @DisplayName("embedding 试图把 dimension 改成非铁律值 → 400")
        void embeddingDimensionChangeRejected() {
            when(repo.findByKey("embedding")).thenReturn(Optional.of(embeddingRow("m")));

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "embedding", BASE, null, null, "m", 1024, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("维度不允许");
        }

        @Test
        @DisplayName("embedding 既有模型为空且 patch 未补 → 400")
        void embeddingRequiresModel() {
            when(repo.findByKey("embedding")).thenReturn(Optional.of(embeddingRow("")));
            when(repo.updateMerged(any())).thenReturn(1);

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "embedding", BASE, null, null, null, 1536, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("安全与边界")
    class Safety {

        @Test
        @DisplayName("占位 API Key（your-…/…-here）→ 400")
        void placeholderKeyRejected() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow(BASE, "enc:v1:Old", "qwen-turbo", "deepseek-v4-flash-0731")));

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "chat", BASE, null, null, null, null, "your-placeholder-api-key-here", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("占位");
        }

        @Test
        @DisplayName("未知渠道键 → 400")
        void unknownChannelRejected() {
            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "weird", BASE, null, null, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("未知渠道");
        }

        @Test
        @DisplayName("渠道不存在（DB 无行）→ IllegalStateException（映射 404）")
        void missingChannelThrows404() {
            when(repo.findByKey("chat")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "chat", BASE, "t", "r", null, null, null, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不存在");
        }

        @Test
        @DisplayName("base-url 非 http/https → 400")
        void badBaseUrlRejected() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow(BASE, "enc:v1:Old", "qwen-turbo", "deepseek-v4-flash-0731")));

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "chat", "ftp://evil", "qwen-turbo", "deepseek-v4-flash-0731", null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http");
        }
    }

    // ==================== 备用模型（方案 A）====================

    @Nested
    @DisplayName("备用模型（方案 A：模型池降级）")
    class FallbackRules {

        private AiChannel chatWithFallback(String fbUrl, String fbModel, String fbKeyEnc) {
            return new AiChannel("chat", BASE, "enc:v1:Old", "sk-ws-****7890",
                    "qwen-turbo", "deepseek-v4-flash-0731", null, null, "ACTIVE",
                    LocalDateTime.of(2026, 9, 22, 10, 0),
                    fbUrl, fbModel, fbKeyEnc, fbKeyEnc == null ? null : "sk-fb-****5678");
        }

        @Test
        @DisplayName("完整备用配置（url+model+key）→ 合并保存，密文加密、脱敏入库")
        void fullFallbackSaved() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow(BASE, "enc:v1:Old", "qwen-turbo", "deepseek-v4-flash-0731")));
            when(repo.updateMerged(any())).thenReturn(1);

            org.mockito.ArgumentCaptor<AiChannel> captor = org.mockito.ArgumentCaptor.forClass(AiChannel.class);
            service.update(new ChannelUpdate("chat", null, null, null, null, null, null, null,
                    "https://api.deepseek.com/v1", "deepseek-chat", "sk-fb-RealKey5678", null));

            org.mockito.Mockito.verify(repo).updateMerged(captor.capture());
            AiChannel merged = captor.getValue();
            assertThat(merged.fallbackBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
            assertThat(merged.fallbackModel()).isEqualTo("deepseek-chat");
            // key 已加密（enc:v1: 前缀），绝不是明文
            assertThat(merged.fallbackApiKeyEnc()).startsWith("enc:v1:");
            assertThat(merged.fallbackApiKeyEnc()).doesNotContain("sk-fb-RealKey5678");
            assertThat(merged.fallbackMaskedKey()).isEqualTo("sk-fb-****5678");
        }

        @Test
        @DisplayName("只给 fallbackModel 缺 url（半套备用）→ 400（延迟爆炸拦截）")
        void halfFallbackRejected() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow(BASE, "enc:v1:Old", "qwen-turbo", "deepseek-v4-flash-0731")));

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "chat", null, null, null, null, null, null, null,
                    null, "deepseek-chat", "sk-fb-Key12345678", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("同时配置");
        }

        @Test
        @DisplayName("备用 url+model 但无 key → 400（无 key 的备用等于没有备用）")
        void fallbackWithoutKeyRejected() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow(BASE, "enc:v1:Old", "qwen-turbo", "deepseek-v4-flash-0731")));

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "chat", null, null, null, null, null, null, null,
                    "https://api.deepseek.com/v1", "deepseek-chat", null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("必须配置 API Key");
        }

        @Test
        @DisplayName("embedding 渠道配备用 → 400（维度铁律禁止换 embedding 模型）")
        void embeddingFallbackRejected() {
            when(repo.findByKey("embedding")).thenReturn(Optional.of(embeddingRow("m")));

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "embedding", null, null, null, null, null, null, null,
                    "https://other.example.com/v1", "other-emb", "sk-fb-Key12345678", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("仅 chat 渠道支持备用模型");
        }

        @Test
        @DisplayName("备用占位 key（your-…）→ 400")
        void fallbackPlaceholderKeyRejected() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatRow(BASE, "enc:v1:Old", "qwen-turbo", "deepseek-v4-flash-0731")));

            assertThatThrownBy(() -> service.update(new ChannelUpdate(
                    "chat", null, null, null, null, null, null, null,
                    "https://api.deepseek.com/v1", "deepseek-chat", "your-fallback-key-here", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("占位");
        }

        @Test
        @DisplayName("clearFallback=true → 整组备用清空（含 key）")
        void clearFallbackWipesAll() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatWithFallback("https://api.deepseek.com/v1", "deepseek-chat", "enc:v1:FbOld")));
            when(repo.updateMerged(any())).thenReturn(1);

            org.mockito.ArgumentCaptor<AiChannel> captor = org.mockito.ArgumentCaptor.forClass(AiChannel.class);
            service.update(new ChannelUpdate("chat", null, null, null, null, null, null, null,
                    null, null, null, true));

            org.mockito.Mockito.verify(repo).updateMerged(captor.capture());
            AiChannel merged = captor.getValue();
            assertThat(merged.fallbackBaseUrl()).isNull();
            assertThat(merged.fallbackModel()).isNull();
            assertThat(merged.fallbackApiKeyEnc()).isNull();
            assertThat(merged.fallbackMaskedKey()).isNull();
            // 主 key 不受清除备用影响
            assertThat(merged.apiKeyEnc()).isEqualTo("enc:v1:Old");
        }

        @Test
        @DisplayName("改备用时不提供 fallbackApiKey → 保留既有备用密文（改模型名不抹备用 key）")
        void fallbackKeyOmittedKeepsExisting() {
            when(repo.findByKey("chat")).thenReturn(Optional.of(
                    chatWithFallback("https://api.deepseek.com/v1", "deepseek-chat", "enc:v1:FbOld")));
            when(repo.updateMerged(any())).thenReturn(1);

            org.mockito.ArgumentCaptor<AiChannel> captor = org.mockito.ArgumentCaptor.forClass(AiChannel.class);
            service.update(new ChannelUpdate("chat", null, null, null, null, null, null, null,
                    "https://api.deepseek.com/v1", "deepseek-chat-v2", null, null));

            org.mockito.Mockito.verify(repo).updateMerged(captor.capture());
            AiChannel merged = captor.getValue();
            assertThat(merged.fallbackModel()).isEqualTo("deepseek-chat-v2");
            assertThat(merged.fallbackApiKeyEnc()).isEqualTo("enc:v1:FbOld");
        }
    }
}
