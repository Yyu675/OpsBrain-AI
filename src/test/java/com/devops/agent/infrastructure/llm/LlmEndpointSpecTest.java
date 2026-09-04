package com.devops.agent.infrastructure.llm;

import com.devops.agent.infrastructure.AiModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 端点配置收敛的单元测试。
 *
 * <h3>这些用例能抓到什么</h3>
 * 大模型配置的错误有个共同特点：<b>启动时一切正常，用起来才炸，
 * 而报错信息指向的位置离真正的原因很远</b>。
 * <ul>
 *   <li>Embedding 漏传 dimensions → 启动无异常，第一次索引文档时
 *       PostgreSQL 报 {@code expected 1536 dimensions, not 3072}，
 *       看起来像是数据库或建表脚本的问题；</li>
 *   <li>reasoner 超时没翻倍 → 只有「复杂堆栈分析」这一类请求会超时，
 *       而普通对话正常，很容易被归因为「那个问题太难了」；</li>
 *   <li>apiKey 进日志 → 完全没有报错，直到密钥被滥用。</li>
 * </ul>
 * 把配置解读收敛成纯函数之后，这些都变成了不需要 Spring 上下文、
 * 不需要网络就能验证的断言。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("LLM 端点配置收敛")
class LlmEndpointSpecTest {

    private static final String URL = "https://example.com/compatible-mode/v1";
    private static final String KEY = "sk-secret-value-123456";

    @Nested
    @DisplayName("超时策略")
    class TimeoutPolicy {

        @Test
        @DisplayName("reasoner 超时是 turbo 的 REASONER_TIMEOUT_MULTIPLIER 倍")
        void reasonerTimeoutIsMultiplied() {
            Duration base = Duration.ofMillis(60_000);
            LlmEndpointSpec turbo = LlmEndpointSpec.chat(URL, KEY, "turbo", base, 2);
            LlmEndpointSpec reasoner = LlmEndpointSpec.reasoner(URL, KEY, "reasoner", base, 2);

            assertEquals(60_000, turbo.timeout().toMillis());
            assertEquals(60_000L * LlmEndpointSpec.REASONER_TIMEOUT_MULTIPLIER,
                    reasoner.timeout().toMillis(),
                    "推理模型耗时天然是普通对话的数倍，用同一超时会让复杂堆栈分析必然超时，"
                            + "而后端模型仍在计费运行");
        }

        @Test
        @DisplayName("倍数常量大于 1——等于 1 时这条策略形同虚设且无人察觉")
        void multiplierIsGreaterThanOne() {
            // 若有人为了「统一超时」把倍数改成 1，上面那条用例依然会通过
            // （两边都乘 1），策略却已消失。这条专门守住它
            assertTrue(LlmEndpointSpec.REASONER_TIMEOUT_MULTIPLIER > 1,
                    "推理端点的超时必须严格大于普通对话端点");
        }

        @Test
        @DisplayName("超时为 0 或负数被拒绝——部分 HTTP 客户端把 0 当作永不超时")
        void nonPositiveTimeoutRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> LlmEndpointSpec.chat(URL, KEY, "m", Duration.ZERO, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> LlmEndpointSpec.chat(URL, KEY, "m", Duration.ofMillis(-1), 1));
        }
    }

    @Nested
    @DisplayName("流式端点")
    class Streaming {

        @Test
        @DisplayName("流式派生把重试数归零——流式 builder 根本没有 maxRetries")
        void streamingHasNoRetries() {
            LlmEndpointSpec spec = LlmEndpointSpec.chat(URL, KEY, "turbo",
                    Duration.ofMillis(60_000), 3);
            assertEquals(3, spec.maxRetries());

            LlmEndpointSpec streaming = spec.streaming();
            assertEquals(0, streaming.maxRetries(),
                    "LangChain4j 1.1.0 的流式 builder 不支持 maxRetries，"
                            + "留个非 0 值会让人误以为流式也会重试");
        }

        @Test
        @DisplayName("流式派生不改动端点、模型与超时——只归零重试")
        void streamingKeepsEverythingElse() {
            // 若哪天有人在 streaming() 里顺手改了超时，
            // 流式与同步链路的超时会悄悄分叉，SSE 超时层级铁律随之失效
            LlmEndpointSpec spec = LlmEndpointSpec.reasoner(URL, KEY, "reasoner",
                    Duration.ofMillis(60_000), 3);
            LlmEndpointSpec streaming = spec.streaming();

            assertEquals(spec.baseUrl(), streaming.baseUrl());
            assertEquals(spec.modelName(), streaming.modelName());
            assertEquals(spec.timeout(), streaming.timeout());
            assertEquals(spec.apiKey(), streaming.apiKey());
        }
    }

    @Nested
    @DisplayName("Embedding 维度")
    class Dimensions {

        @Test
        @DisplayName("embedding 工厂带上维度，chat 工厂不带")
        void onlyEmbeddingCarriesDimensions() {
            assertNull(LlmEndpointSpec.chat(URL, KEY, "turbo",
                    Duration.ofMillis(1000), 1).dimensions());
            assertEquals(1536, LlmEndpointSpec.embedding(URL, KEY, "emb",
                    Duration.ofMillis(1000), 1, 1536).dimensions());
        }

        @Test
        @DisplayName("无维度的 spec 交给 embedding 工厂会立刻抛错，而不是静默用原生维度")
        void embeddingFactoryRejectsMissingDimensions() {
            // 这是本文件最重要的一条。静默通过的后果是：
            // 模型返回 3072 维（gemini）或 4096 维（qwen3），
            // 启动与向量化都不报错，直到写库那一刻 PostgreSQL 才报
            // expected 1536 dimensions, not 3072——排查者会先去怀疑建表脚本
            LlmEndpointSpec noDim = LlmEndpointSpec.chat(URL, KEY, "emb",
                    Duration.ofMillis(1000), 1);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> OpenAiCompatibleModelFactory.embedding(noDim));
            assertTrue(ex.getMessage().contains("dimensions"),
                    "报错必须点名 dimensions，否则排查方向会跑到数据库上");
        }

        @Test
        @DisplayName("维度为 0 或负数被拒绝")
        void nonPositiveDimensionRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> LlmEndpointSpec.embedding(URL, KEY, "emb",
                            Duration.ofMillis(1000), 1, 0));
        }
    }

    @Nested
    @DisplayName("密钥不得进日志")
    class SecretSafety {

        @Test
        @DisplayName("describe() 不含 apiKey")
        void describeHidesApiKey() {
            LlmEndpointSpec spec = LlmEndpointSpec.chat(URL, KEY, "turbo",
                    Duration.ofMillis(1000), 1);
            assertFalse(spec.describe().contains(KEY),
                    "配置日志每次启动都会打印，密钥一旦进去就会散布到所有日志归档系统");
            assertTrue(spec.describe().contains("turbo"), "但模型名要能看见，否则日志没用");
        }

        @Test
        @DisplayName("toString() 也不含 apiKey——record 的默认实现会原样打出来")
        void toStringHidesApiKey() {
            // 不覆盖 toString 的话，任何一处 log.info("spec={}", spec)
            // 都会把密钥写进日志，而且完全没有征兆
            LlmEndpointSpec spec = LlmEndpointSpec.chat(URL, KEY, "turbo",
                    Duration.ofMillis(1000), 1);
            assertFalse(spec.toString().contains(KEY));
            assertTrue(spec.toString().contains("***"));
        }
    }

    @Nested
    @DisplayName("必填项校验")
    class Required {

        @Test
        @DisplayName("空 baseUrl / apiKey / modelName 一律拒绝")
        void blankFieldsRejected() {
            Duration t = Duration.ofMillis(1000);
            assertThrows(IllegalArgumentException.class,
                    () -> LlmEndpointSpec.chat("", KEY, "m", t, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> LlmEndpointSpec.chat(URL, "  ", "m", t, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> LlmEndpointSpec.chat(URL, KEY, null, t, 1));
        }

        @Test
        @DisplayName("负重试数被拒绝")
        void negativeRetriesRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> LlmEndpointSpec.chat(URL, KEY, "m", Duration.ofMillis(1), -1));
        }
    }

    @Nested
    @DisplayName("AiModelConfig 的配置解读")
    class ConfigWiring {

        /** 按 application.yml 的键名手工装配一个 AiModelConfig（无需 Spring 上下文） */
        private AiModelConfig configured() {
            AiModelConfig cfg = new AiModelConfig();
            ReflectionTestUtils.setField(cfg, "alibabaBaseUrl", URL);
            ReflectionTestUtils.setField(cfg, "alibabaApiKey", KEY);
            ReflectionTestUtils.setField(cfg, "turboModel", "qwen-plus");
            ReflectionTestUtils.setField(cfg, "reasonerModel", "qwen-max");
            ReflectionTestUtils.setField(cfg, "embeddingModel", "text-embedding-v2");
            ReflectionTestUtils.setField(cfg, "timeout", 60_000L);
            ReflectionTestUtils.setField(cfg, "maxRetries", 2);
            ReflectionTestUtils.setField(cfg, "vectorDimension", 1536);
            return cfg;
        }

        @Test
        @DisplayName("三个端点各自取到正确的模型名——别再把 turbo 的名字用给 reasoner")
        void eachEndpointGetsItsOwnModel() {
            AiModelConfig cfg = configured();
            assertEquals("qwen-plus", cfg.turboSpec().modelName());
            assertEquals("qwen-max", cfg.reasonerSpec().modelName());
            assertEquals("text-embedding-v2", cfg.embeddingSpec().modelName());
        }

        @Test
        @DisplayName("reasoner 端点超时确实翻倍，turbo 保持原值")
        void reasonerTimeoutDoubledFromConfig() {
            AiModelConfig cfg = configured();
            assertEquals(60_000, cfg.turboSpec().timeout().toMillis());
            assertEquals(120_000, cfg.reasonerSpec().timeout().toMillis(),
                    "SSE 超时层级铁律依赖这个值：sse.timeout-ms(150s) > reasoner(120s) > turbo(60s)，"
                            + "倒挂会让前端先判超时而后端模型继续计费");
        }

        @Test
        @DisplayName("embedding 端点带上 devops.ai.vector.dimension 的值")
        void embeddingSpecCarriesConfiguredDimension() {
            AiModelConfig cfg = configured();
            assertNotNull(cfg.embeddingSpec().dimensions());
            assertEquals(1536, cfg.embeddingSpec().dimensions(),
                    "维度必须来自配置，与 init.sql 的 VECTOR(n) 同源；"
                            + "写死在代码里会在换 Embedding 模型时写库报错");
        }

        @Test
        @DisplayName("embedding 端点超时不翻倍——翻倍只针对推理模型")
        void embeddingTimeoutNotDoubled() {
            assertEquals(60_000, configured().embeddingSpec().timeout().toMillis());
        }
    }
}
