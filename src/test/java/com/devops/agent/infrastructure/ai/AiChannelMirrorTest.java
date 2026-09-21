package com.devops.agent.infrastructure.ai;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelRepository;
import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChannelMirror} 启动镜像测试（阶段A：渠道配置镜像入表）。
 *
 * <h3>为什么测它</h3>
 * 这是阶段A 唯一触碰「配置 → DB」的启动组件，风险三处：地址/模型错配、
 * key 未加密就落库、镜像失败把启动搞挂。本测试锁住：
 * <ol>
 *   <li><b>三渠道各写一条</b>，且各自模型/维度归属正确（chat 的 turbo/reasoner
 *       不落到 embedding 的 model 列等）；</li>
 *   <li><b>key 加密落 apiKeyEnc、脱敏落 maskedKey</b>——明文 key 单独由
 *       {@code ApiKeyCrypt} 处理，镜像不自造格式；</li>
 *   <li><b>未配 {@code MODEL_KEY_CRYPT_SECRET} 降级明文</b>（功能可用，只告警）；</li>
 *   <li><b>镜像失败不阻断启动</b>——P0 为展示用途，镜像掉不拖垮核心链路。</li>
 * </ol>
 *
 * <p>私有 {@code @Value} 字段用 {@link ReflectionTestUtils} 注入，mock 仓储，
 * 零容器零 Spring 上下文。</p>
 *
 * @author OpsBrain AI
 */
class AiChannelMirrorTest {

    private static final String BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String SECRET = "test-secret-2026";
    private static final String RAW_CHAT = "sk-ws-H.aaaabbbbcccc";
    private static final String RAW_EMB = "sk-ws-H.ddddeeeeffff";

    private final AiChannelRepository repo = mock(AiChannelRepository.class);
    private final AiChannelMirror mirror = new AiChannelMirror(repo, "MOCK");
    private final List<AiChannel> captured = new ArrayList<>();

    @BeforeEach
    void stubUpsertCapture() {
        // 捕获每次 upsert 的实体，供后续断言（不真正写库）
        org.mockito.Mockito.doAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return null;
        }).when(repo).upsert(any(AiChannel.class));
        // 默认「渠道不存在」→ status 回落 ACTIVE（保持既有镜像语义）；
        // 测试「不覆盖已停用状态」时用 when(...).thenReturn(Optional.of(停用)) 覆写。
        when(repo.findByKey(any())).thenReturn(Optional.empty());
    }

    /** 注入三渠道的地址/key/模型。dimension 走 {@code devops.ai.vector.dimension}。 */
    private void seedChannels(String secret) {
        ReflectionTestUtils.setField(mirror, "chatBaseUrl", BASE);
        ReflectionTestUtils.setField(mirror, "chatKey", RAW_CHAT);
        ReflectionTestUtils.setField(mirror, "turboModel", "qwen-turbo");
        ReflectionTestUtils.setField(mirror, "reasonerModel", "deepseek-v4-flash-0731");

        ReflectionTestUtils.setField(mirror, "embeddingBaseUrl", BASE);
        ReflectionTestUtils.setField(mirror, "embeddingKey", RAW_EMB);
        ReflectionTestUtils.setField(mirror, "embeddingModel", "qwen3.7-text-embedding");

        ReflectionTestUtils.setField(mirror, "rerankerBaseUrl", BASE);
        ReflectionTestUtils.setField(mirror, "rerankerKey", "");
        ReflectionTestUtils.setField(mirror, "rerankerModel", "qwen3-reranker-8b");

        ReflectionTestUtils.setField(mirror, "dimension", 1536);
        ReflectionTestUtils.setField(mirror, "cryptSecret", secret);
    }

    @Nested
    @DisplayName("启动镜像三渠道")
    class MirrorThreeChannels {

        @Test
        @DisplayName("写入 chat/embedding/reranker 三条，各 model/dimension 归属正确")
        void mirrorsAllThreeChannels() {
            seedChannels(SECRET);

            mirror.run(null);

            verify(repo, times(3)).upsert(any(AiChannel.class));
            assertThat(captured).hasSize(3);
            assertThat(captured).extracting(AiChannel::channelKey)
                    .containsExactly("chat", "embedding", "reranker");

            AiChannel chat = byKey("chat");
            assertThat(chat.baseUrl()).isEqualTo(BASE);
            assertThat(chat.turboModel()).isEqualTo("qwen-turbo");
            assertThat(chat.reasonerModel()).isEqualTo("deepseek-v4-flash-0731");
            assertThat(chat.model()).isNull();          // chat 不占 model 列
            assertThat(chat.dimension()).isNull();

            AiChannel emb = byKey("embedding");
            assertThat(emb.model()).isEqualTo("qwen3.7-text-embedding");
            assertThat(emb.dimension()).isEqualTo(1536); // embedding 落维度
            assertThat(emb.turboModel()).isNull();       // 不串到 chat 列

            AiChannel rerank = byKey("reranker");
            assertThat(rerank.model()).isEqualTo("qwen3-reranker-8b");
            assertThat(rerank.dimension()).isNull();
        }

        @Test
        @DisplayName("key 经加密落 apiKeyEnc、脱敏落 maskedKey（明文/密文由 ApiKeyCrypt 兜底）")
        void keysEncryptedAndMasked() {
            seedChannels(SECRET);

            mirror.run(null);

            AiChannel chat = byKey("chat");
            // 镜像用 ApiKeyCrypt.encrypt（AES-GCM 随机 IV）→ 密文带 enc:v1: 前缀、
            // 可用同一密钥解密还原原文、且不与明文同串
            assertThat(chat.apiKeyEnc()).startsWith("enc:v1:");
            assertThat(chat.apiKeyEnc()).isNotEqualTo(RAW_CHAT);
            assertThat(ApiKeyCrypt.decrypt(chat.apiKeyEnc(), SECRET)).isEqualTo(RAW_CHAT);
            assertThat(chat.maskedKey()).isEqualTo(ApiKeyCrypt.mask(RAW_CHAT));
        }

        @Test
        @DisplayName("未配 MODEL_KEY_CRYPT_SECRET：降级明文落库（不抛、功能可用）")
        void degradesToPlainWhenNoSecret() {
            seedChannels(null);

            assertThatCode(() -> mirror.run(null)).doesNotThrowAnyException();

            AiChannel chat = byKey("chat");
            assertThat(chat.apiKeyEnc()).isEqualTo(RAW_CHAT); // 明文降级
            assertThat(chat.maskedKey()).isEqualTo(ApiKeyCrypt.mask(RAW_CHAT));
        }

        @Test
        @DisplayName("key 为空（未配置渠道）：apiKeyEnc/maskedKey 为 null，不产脏串")
        void emptyKeyStoredNull() {
            seedChannels(SECRET);
            ReflectionTestUtils.setField(mirror, "chatKey", "");

            mirror.run(null);

            AiChannel chat = byKey("chat");
            assertThat(chat.apiKeyEnc()).isNull();
            assertThat(chat.maskedKey()).isNull();
        }

        @Test
        @DisplayName("P1 权威源：已存在的渠道不覆盖（UI 编辑后重启不丢），缺的才种")
        void mirrorDoesNotOverwriteExistingChannel() {
            seedChannels(SECRET);
            // chat 渠道已存在（例如运维在 UI 编辑过）→ 镜像绝不 upsert；
            // embedding/reranker 无既有记录 → 种下 yml 当前值（ACTIVE）
            when(repo.findByKey("chat"))
                    .thenReturn(Optional.of(new AiChannel("chat", BASE, null, null,
                            "qwen-turbo", "deepseek-v4-flash-0731", null, null, "DISABLED", null)));

            mirror.run(null);

            // chat 不在捕获列表里 = 没有被 upsert 覆盖
            assertThat(captured).extracting(AiChannel::channelKey)
                    .containsExactly("embedding", "reranker");
            // 缺失渠道种下时 status 回落 ACTIVE
            assertThat(byKey("embedding").status()).isEqualTo("ACTIVE");
            assertThat(byKey("reranker").status()).isEqualTo("ACTIVE");
        }
    }

    @Nested
    @DisplayName("故障降级")
    class Failure {

        @Test
        @DisplayName("仓储写入异常不阻断启动（镜像掉是展示瑕疵，不是启动事故）")
        void mirrorFailureDoesNotBlockStartup() {
            seedChannels(SECRET);
            org.mockito.Mockito.reset(repo);
            doThrow(new RuntimeException("DB down")).when(repo).upsert(any(AiChannel.class));

            assertThatCode(() -> mirror.run(null)).doesNotThrowAnyException();
        }
    }

    private AiChannel byKey(String key) {
        return captured.stream().filter(c -> c.channelKey().equals(key)).findFirst().orElseThrow();
    }
}
