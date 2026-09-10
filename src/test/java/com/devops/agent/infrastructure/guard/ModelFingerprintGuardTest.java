package com.devops.agent.infrastructure.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型指纹锁 + REAL 启动守卫单测（方案 C，批 74）。
 *
 * <p>注入-还原验证在案（AGENTS §3.9）：本测试用例即「注入」的可执行形式——
 * 指纹不一致场景直接构造新旧指纹并断言 guard 报告变更，若产品代码里
 * 的比对逻辑被删除，本测试会立刻红。</p>
 */
@DisplayName("模型指纹锁（方案 C 第二道防线）")
class ModelFingerprintGuardTest {

    private static final String REAL_URL = "https://api.vendor-a.com/v1";
    private static final String OTHER_URL = "https://api.vendor-b.com/v1";

    @Test
    @DisplayName("指纹确定性：同配置同指纹；渠道/模型/维度任一变化指纹必变")
    void fingerprintIsDeterministicAndSensitive() {
        String a1 = ModelFingerprintGuard.fingerprint(REAL_URL, "model-x", 1536);
        String a2 = ModelFingerprintGuard.fingerprint(REAL_URL, "model-x", 1536);
        assertThat(a1).isEqualTo(a2).hasSize(16);

        // 三个变化源各自都要改变指纹——漏一个 = 锁形同虚设
        assertThat(ModelFingerprintGuard.fingerprint(OTHER_URL, "model-x", 1536))
                .as("换渠道（同名模型不同网关）必须触发指纹变更")
                .isNotEqualTo(a1);
        assertThat(ModelFingerprintGuard.fingerprint(REAL_URL, "model-y", 1536))
                .as("换模型必须触发指纹变更")
                .isNotEqualTo(a1);
        assertThat(ModelFingerprintGuard.fingerprint(REAL_URL, "model-x", 1024))
                .as("换维度必须触发指纹变更")
                .isNotEqualTo(a1);
    }

    @Test
    @DisplayName("首次运行记录指纹（verifyOrRecord 返回 null = 放行）")
    void firstRunRecordsFingerprint() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 模拟「表不存在 → 首次」：queryForObject 抛 DataAccessException（readStored
        // 只捕 DataAccessException——裸 RuntimeException 应该继续炸，契约测试同款纪律）
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("table does not exist"));

        ModelFingerprintGuard guard = new ModelFingerprintGuard(jdbc, "REAL", REAL_URL, "model-x", 1536);
        String stale = guard.verifyOrRecord();

        assertThat(stale).as("首次运行无历史指纹，必须放行").isNull();
        assertThat(guard.currentFingerprint()).isNotEmpty();
    }

    @Test
    @DisplayName("模型变更检出：库中指纹与当前不一致时返回旧指纹（调用方据此拒启）")
    void modelChangeIsDetected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 表存在，返回一个与当前配置不同的旧指纹（模拟「换了模型/渠道」）
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), anyString()))
                .thenReturn("deadbeefdeadbeef");

        ModelFingerprintGuard guard = new ModelFingerprintGuard(jdbc, "REAL", REAL_URL, "model-x", 1536);
        String stale = guard.verifyOrRecord();

        assertThat(stale)
                .as("指纹不一致时必须报告旧指纹——null 会导致静默混用（本测试防的就是这个）")
                .isNotNull()
                .isEqualTo("deadbeefdeadbeef");
    }

    @Test
    @DisplayName("指纹一致时放行")
    void sameFingerprintPasses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String current = ModelFingerprintGuard.fingerprint(REAL_URL, "model-x", 1536);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), anyString()))
                .thenReturn(current);

        ModelFingerprintGuard guard = new ModelFingerprintGuard(jdbc, "REAL", REAL_URL, "model-x", 1536);
        assertThat(guard.verifyOrRecord()).as("指纹一致必须放行").isNull();
    }

    @Test
    @DisplayName("MOCK 模式恒放行（假向量无语义空间概念）")
    void mockModeAlwaysPasses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), anyString()))
                .thenReturn("deadbeefdeadbeef");

        ModelFingerprintGuard guard = new ModelFingerprintGuard(jdbc, "MOCK", REAL_URL, "model-x", 1536);
        assertThat(guard.verifyOrRecord()).as("MOCK 模式不参与指纹锁").isNull();
        assertThat(guard.currentFingerprint()).isEmpty();
    }
}
