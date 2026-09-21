package com.devops.agent.infrastructure.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ApiKeyCrypt} 单元测试（阶段A：渠道 Key 安全落库）。
 *
 * <h3>为什么它是阶段A 的安全底线</h3>
 * 模型渠道配置落库时，API Key 不能明文躺进 {@code sys_ai_channel}——泄露面太大。
 * {@code ApiKeyCrypt} 用 AES-GCM 加密（密钥由 {@code MODEL_KEY_CRYPT_SECRET}
 * 经 SHA-256 派生），前端的 {@code ModelChannelController} 只回脱敏串。本测试
 * 锁住四条契约：
 * <ol>
 *   <li><b>加密可解</b>——加密-解密往返必须还原原 key（否则 key 等于被锁死）；</li>
 *   <li><b>密文不可读</b>——密文不回显明文片段、且 <b>每次随机 IV</b>（同一 key
 *       两次加密密文不同），防止静态比对侧信道；</li>
 *   <li><b>降级路径可控</b>——未配密钥时明文返回并置前缀，解密认前缀不误判；</li>
 *   <li><b>脱敏不泄漏</b>——{@code mask} 只留前 6 后 4，中间全打码。</li>
 * </ol>
 *
 * <p>安全边界：本测试<b>不</b>做加解密性能/算法强度断言（交给标准库），
 * 只锁「业务语义正确」这一层。</p>
 *
 * @author OpsBrain AI
 */
class ApiKeyCryptTest {

    private static final String SECRET = "test-crypt-secret-2026";
    private static final String RAW = "sk-ws-H.abcDeFgHijKlMnO1234567890";

    @Nested
    @DisplayName("加密-解密往返")
    class RoundTrip {

        @Test
        @DisplayName("配置密钥时：加密→解密封装为原 key")
        void encryptThenDecryptRestoresOriginal() {
            String enc = ApiKeyCrypt.encrypt(RAW, SECRET);
            assertThat(enc).startsWith("enc:v1:");
            assertThat(ApiKeyCrypt.decrypt(enc, SECRET)).isEqualTo(RAW);
        }

        @Test
        @DisplayName("同一 key 同一密钥两次加密，密文不同（随机 IV 防静态比对）")
        void sameKeyYieldsDifferentCiphertext() {
            String a = ApiKeyCrypt.encrypt(RAW, SECRET);
            String b = ApiKeyCrypt.encrypt(RAW, SECRET);
            assertThat(a).isNotEqualTo(b);
            assertThat(ApiKeyCrypt.decrypt(a, SECRET)).isEqualTo(RAW);
            assertThat(ApiKeyCrypt.decrypt(b, SECRET)).isEqualTo(RAW);
        }

        @Test
        @DisplayName("密钥变更后旧密文无法解开——提示需重新配置 key（而非静默返明文）")
        void wrongSecretCannotDecrypt() {
            String enc = ApiKeyCrypt.encrypt(RAW, SECRET);
            assertThatThrownBy(() -> ApiKeyCrypt.decrypt(enc, "another-secret-xyz"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("解密失败");
        }
    }

    @Nested
    @DisplayName("降级路径（未配 MODEL_KEY_CRYPT_SECRET）")
    class Degraded {

        @Test
        @DisplayName("未配密钥：加密返回明文（不抛、功能可用），enabled=false")
        void noSecretEncryptsToPlain() {
            assertThat(ApiKeyCrypt.enabled(null)).isFalse();
            assertThat(ApiKeyCrypt.enabled("  ")).isFalse();
            assertThat(ApiKeyCrypt.encrypt(RAW, null)).isEqualTo(RAW);
            assertThat(ApiKeyCrypt.encrypt(RAW, "  ")).isEqualTo(RAW);
        }

        @Test
        @DisplayName("无前缀旧数据/明文按原样返回（解密不误判）")
        void plainWithoutPrefixPassesThrough() {
            assertThat(ApiKeyCrypt.decrypt(RAW, SECRET)).isEqualTo(RAW);
            assertThat(ApiKeyCrypt.decrypt("", SECRET)).isNull();
            assertThat(ApiKeyCrypt.decrypt(null, SECRET)).isNull();
        }
    }

    @Nested
    @DisplayName("脱敏 mask")
    class Mask {

        @Test
        @DisplayName("长 key：保留前 6 + 后 4，中间打码，不回显完整 key")
        void masksLongKey() {
            String masked = ApiKeyCrypt.mask(RAW);
            assertThat(masked).doesNotContain("H.abcDeFgHijKlM"); // 中段不得泄漏
            assertThat(masked).startsWith("sk-ws-");
            assertThat(masked).endsWith("7890");
            assertThat(masked).contains("****");
        }

        @Test
        @DisplayName("过短 key（≤10 位）整串打码，避免只遮两位暴露大半")
        void masksShortKeyEntirely() {
            assertThat(ApiKeyCrypt.mask("abc")).isEqualTo("****");
            assertThat(ApiKeyCrypt.mask("abcdefghij")).isEqualTo("****");
        }

        @Test
        @DisplayName("空 key：mask 返回 null（不产脏串）")
        void maskNull() {
            assertThat(ApiKeyCrypt.mask(null)).isNull();
            assertThat(ApiKeyCrypt.mask("")).isNull();
        }
    }
}
