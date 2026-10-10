package com.devops.agent.infrastructure;

import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 解密口「自包含可执行」异常测试（批89 P1）。
 *
 * <p>守什么：换钥后启动失败的报错必须<b>点名渠道 + 给出恢复路径</b>——
 * 泛泛的「解密失败」藏在 bean 链栈底，新值班看不懂该干什么（本批真机踩过）。</p>
 */
class AiModelConfigDecryptTest {

    private AiModelConfig configWithKey(String cryptSecret) {
        // 构造只做依赖赋值 + rateLimiter 查表（mock 返回 null 不影响 decrypt 纯函数路径）
        AiModelConfig cfg = new AiModelConfig(null, mock(RateLimiterRegistry.class), null);
        ReflectionTestUtils.setField(cfg, "modelKeyCryptSecret", cryptSecret);
        return cfg;
    }

    /**
     * 反射调用 decrypt 并解开 InvocationTargetException。
     * 成功 → 返回解密值；失败 → 返回业务异常本体（Object 双用途，断言处判型）。
     */
    private Object invokeDecrypt(AiModelConfig cfg, String stored, String channelKey)
            throws ReflectiveOperationException {
        try {
            java.lang.reflect.Method m = AiModelConfig.class.getDeclaredMethod(
                    "decrypt", String.class, String.class);
            m.setAccessible(true);
            return m.invoke(cfg, stored, channelKey);
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    @Test
    @DisplayName("主密钥变更 → 异常点名渠道、含恢复三步与 Runbook 指路、保留原始原因")
    void decryptFailureNamesChannelAndRecoveryPath() throws Exception {
        // 用密钥 A 加密、密钥 B 解密 = 主密钥变更的真形态
        String stored = ApiKeyCrypt.encrypt("sk-test-1234567890", "key-A");
        AiModelConfig cfg = configWithKey("key-B");

        Throwable ex = (Throwable) invokeDecrypt(cfg, stored, "embedding");
        assertNotNull(ex, "解不开必须抛");
        assertTrue(ex instanceof IllegalStateException, "应为 IllegalStateException，实际：" + ex.getClass());
        String msg = ex.getMessage();
        assertTrue(msg.contains("「embedding」渠道"), "必须点名渠道，实际：" + msg);
        assertTrue(msg.contains("AI_MODE=MOCK"), "必须给出 MOCK 起的恢复第一步");
        assertTrue(msg.contains("上线放行Runbook"), "必须指向 Runbook 七步");
        assertTrue(msg.contains("MODEL_KEY_CRYPT_SECRET"), "必须点名主密钥变量");
        assertTrue(ex.getCause() != null, "必须保留原始解密原因（不吞栈）");
    }

    @Test
    @DisplayName("明文（无 enc:v1: 前缀）原样返回——降级数据不炸")
    void plaintextPassesThrough() throws Exception {
        AiModelConfig cfg = configWithKey("whatever");
        Object out = invokeDecrypt(cfg, "sk-plain-1234567890", "chat");
        assertEquals("sk-plain-1234567890", out);
    }

    @Test
    @DisplayName("同密钥往返解密成功——正常路径零回归")
    void roundTripWithSameSecret() throws Exception {
        String stored = ApiKeyCrypt.encrypt("sk-roundtrip-abcdefgh", "key-A");
        AiModelConfig cfg = configWithKey("key-A");
        assertEquals("sk-roundtrip-abcdefgh", invokeDecrypt(cfg, stored, "chat"));
    }
}
