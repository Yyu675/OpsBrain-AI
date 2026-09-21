package com.devops.agent.infrastructure.guard;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型指纹锁单测（方案 C，批 74；P1 适配 DB 权威源，批 82）。
 */
@DisplayName("模型指纹锁（方案 C 第二道防线 + P1 DB 权威源）")
class ModelFingerprintGuardTest {

    private static final String REAL_URL = "https://api.vendor-a.com/v1";
    private static final String OTHER_URL = "https://api.vendor-b.com/v1";

    /** DB 无渠道 → 指纹回落 yml（兼容既有场景）。 */
    private static AiChannelRepository emptyRepo() {
        AiChannelRepository repo = mock(AiChannelRepository.class);
        when(repo.findByKey(anyString())).thenReturn(Optional.empty());
        return repo;
    }

    @Test
    @DisplayName("指纹确定性：同配置同指纹；渠道/模型/维度任一变化指纹必变")
    void fingerprintIsDeterministicAndSensitive() {
        String a1 = ModelFingerprintGuard.fingerprint(REAL_URL, "model-x", 1536);
        String a2 = ModelFingerprintGuard.fingerprint(REAL_URL, "model-x", 1536);
        assertThat(a1).isEqualTo(a2).hasSize(16);
        assertThat(ModelFingerprintGuard.fingerprint(OTHER_URL, "model-x", 1536)).isNotEqualTo(a1);
        assertThat(ModelFingerprintGuard.fingerprint(REAL_URL, "model-y", 1536)).isNotEqualTo(a1);
        assertThat(ModelFingerprintGuard.fingerprint(REAL_URL, "model-x", 1024)).isNotEqualTo(a1);
    }

    @Test
    @DisplayName("首次运行记录指纹（verifyOrRecord 返回 null = 放行）")
    void firstRunRecordsFingerprint() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("table does not exist"));
        ModelFingerprintGuard guard = new ModelFingerprintGuard(jdbc, "REAL", REAL_URL, "model-x", 1536, emptyRepo());
        assertThat(guard.verifyOrRecord()).isNull();
        assertThat(guard.currentFingerprint()).isNotEmpty();
    }

    @Test
    @DisplayName("模型变更检出：库中指纹与当前不一致时返回旧指纹")
    void modelChangeIsDetected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), anyString()))
                .thenReturn("deadbeefdeadbeef");
        ModelFingerprintGuard guard = new ModelFingerprintGuard(jdbc, "REAL", REAL_URL, "model-x", 1536, emptyRepo());
        assertThat(guard.verifyOrRecord()).isNotNull().isEqualTo("deadbeefdeadbeef");
    }

    @Test
    @DisplayName("指纹一致时放行")
    void sameFingerprintPasses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String current = ModelFingerprintGuard.fingerprint(REAL_URL, "model-x", 1536);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), anyString()))
                .thenReturn(current);
        ModelFingerprintGuard guard = new ModelFingerprintGuard(jdbc, "REAL", REAL_URL, "model-x", 1536, emptyRepo());
        assertThat(guard.verifyOrRecord()).isNull();
    }

    @Test
    @DisplayName("MOCK 模式恒放行")
    void mockModeAlwaysPasses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), anyString()))
                .thenReturn("deadbeefdeadbeef");
        ModelFingerprintGuard guard = new ModelFingerprintGuard(jdbc, "MOCK", REAL_URL, "model-x", 1536, emptyRepo());
        assertThat(guard.verifyOrRecord()).isNull();
        assertThat(guard.currentFingerprint()).isEmpty();
    }

    @Test
    @DisplayName("P1 DB 权威源：embedding 渠道在 DB 有行 → 指纹从 DB 取 baseUrl+model（不随 yml 漂移）")
    void fingerprintReadsFromDbWhenRowExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiChannelRepository repo = mock(AiChannelRepository.class);
        when(repo.findByKey("embedding")).thenReturn(Optional.of(
                new AiChannel("embedding", "https://db-override.example.com/v1", null, null,
                        null, null, "db-model-v2", 1536, "ACTIVE", null)));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("table does not exist"));

        ModelFingerprintGuard guard = new ModelFingerprintGuard(jdbc, "REAL", REAL_URL, "model-x", 1536, repo);

        // 指纹必须用 DB 值（https://db-override.example.com/v1 + db-model-v2），
        // 而非 yml 值（REAL_URL + model-x）
        String dbFp = ModelFingerprintGuard.fingerprint("https://db-override.example.com/v1", "db-model-v2", 1536);
        String ymlFp = ModelFingerprintGuard.fingerprint(REAL_URL, "model-x", 1536);
        assertThat(guard.currentFingerprint()).isEqualTo(dbFp).isNotEqualTo(ymlFp);
    }
}