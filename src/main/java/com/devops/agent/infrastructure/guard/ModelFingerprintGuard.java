package com.devops.agent.infrastructure.guard;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Embedding 模型指纹锁（方案 C 第二道防线，批 74；P1 适配 DB 权威源，批 82）。
 *
 * <h3>P1 适配（批 82）</h3>
 * 指纹源从 yml 升级为 DB 优先：构造函数同时注入 Repository 与 yml 默认值；
 * yml 不再是单一真相源——当 {@code sys_ai_channel} 有 ACTIVE embedding 行时，
 * 从 DB 取 base-url + model 参与指纹计算，与 AiModelConfig.embeddingSpec()
 * 取到同样的 DB 覆盖值——消掉「模型 Bean 已用 DB 新模型、但指纹锁还在算旧值」
 * 的静默混用风险。
 *
 * @author OpsBrain AI
 * @since 2026-09-10（批 74，方案 C）
 */
@Component
@ConditionalOnProperty(name = "devops.ai.model-fingerprint.enabled", havingValue = "true", matchIfMissing = true)
public class ModelFingerprintGuard {

    private static final Logger log = LoggerFactory.getLogger(ModelFingerprintGuard.class);

    static final String FINGERPRINT_KEY = "embedding";

    private final JdbcTemplate jdbcTemplate;
    private final String currentFingerprint;

    public ModelFingerprintGuard(JdbcTemplate jdbcTemplate,
                                 @Value("${devops.ai.mode:MOCK}") String aiMode,
                                 @Value("${devops.ai.channels.embedding.base-url:}") String ymlBaseUrl,
                                 @Value("${devops.ai.channels.embedding.model:}") String ymlModel,
                                 @Value("${devops.ai.vector.dimension:1536}") int dimension,
                                 AiChannelRepository channelRepo) {
        this.jdbcTemplate = jdbcTemplate;
        if ("MOCK".equalsIgnoreCase(aiMode)) {
            this.currentFingerprint = "";
            return;
        }
        // P1 DB 权威源：指纹用与 AiModelConfig.embeddingSpec() 相同的数据源
        String url = ymlBaseUrl;
        String model = ymlModel;
        try {
            AiChannel ch = channelRepo.findByKey(AiChannel.KEY_EMBEDDING).orElse(null);
            if (ch != null && ch.baseUrl() != null && !ch.baseUrl().isBlank()) {
                url = ch.baseUrl();
                model = ch.model() != null && !ch.model().isBlank() ? ch.model() : ymlModel;
                log.info("🔑 [ModelFingerprintGuard] embedding 渠道指纹源=DB（{} / {}）", url, model);
            }
        } catch (Exception e) {
            log.warn("[ModelFingerprintGuard] 读 DB 失败，回落 yml 指纹: {}", e.getMessage());
        }
        this.currentFingerprint = fingerprint(url, model, dimension);
    }

    public static String fingerprint(String baseUrl, String model, int dimension) {
        String raw = baseUrl + "|" + model + "|" + dimension;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public String verifyOrRecord() {
        if (currentFingerprint.isEmpty()) return null;
        String stored = readStored();
        if (stored == null) {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS sys_model_fingerprint (
                        fingerprint_key VARCHAR(64) PRIMARY KEY,
                        fingerprint     VARCHAR(128) NOT NULL,
                        updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
            jdbcTemplate.update(
                    "INSERT INTO sys_model_fingerprint (fingerprint_key, fingerprint) VALUES (?, ?) " +
                    "ON CONFLICT (fingerprint_key) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, updated_at = CURRENT_TIMESTAMP",
                    FINGERPRINT_KEY, currentFingerprint);
            log.info("🔑 [ModelFingerprintGuard] 首次记录 embedding 渠道指纹: {}", currentFingerprint);
            return null;
        }
        if (stored.equals(currentFingerprint)) {
            log.info("🔑 [ModelFingerprintGuard] embedding 渠道指纹一致: {}", currentFingerprint);
            return null;
        }
        log.error("⛔ [ModelFingerprintGuard] embedding 模型已变更！库中指纹 {} ≠ 当前指纹 {}。"
                + "旧向量语义空间与新模型不兼容——静默混用会返回垃圾检索结果且无任何报错。"
                + "必须执行全库向量重算（重摄取或批量 embed）并清空 Redis 语义缓存后方可恢复。",
                stored, currentFingerprint);
        return stored;
    }

    public void recordAfterRebuild() {
        jdbcTemplate.update(
                "INSERT INTO sys_model_fingerprint (fingerprint_key, fingerprint) VALUES (?, ?) " +
                "ON CONFLICT (fingerprint_key) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, updated_at = CURRENT_TIMESTAMP",
                FINGERPRINT_KEY, currentFingerprint);
        log.info("🔑 [ModelFingerprintGuard] 重建完成，新指纹已记录: {}", currentFingerprint);
    }

    public String currentFingerprint() { return currentFingerprint; }

    private String readStored() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT fingerprint FROM sys_model_fingerprint WHERE fingerprint_key = ?",
                    String.class, FINGERPRINT_KEY);
        } catch (org.springframework.dao.DataAccessException e) {
            log.debug("[ModelFingerprintGuard] 指纹表尚未建立（首次运行）: {}", e.getMessage());
            return null;
        }
    }
}