package com.devops.agent.infrastructure.guard;

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
 * Embedding 模型指纹锁（方案 C 第二道防线，批 74）。
 *
 * <h3>防什么</h3>
 * <p>
 * 换 embedding 模型（即使维度恰好一致）后，库内旧向量与新查询向量分属
 * <b>不同语义空间</b>——余弦相似度全是噪声，检索「能跑」但返回不相关结果，
 * <b>全程无任何报错</b>。这比维度不匹配（写库即炸）阴险得多：系统看起来
 * 一切正常，AI 回答开始一本正经地胡说。
 * </p>
 *
 * <h3>怎么防</h3>
 * <ol>
 *   <li>启动时算当前 embedding 渠道指纹 = SHA-256(baseUrl + model + dimension)；</li>
 *   <li>与 {@code sys_model_fingerprint} 表中上次记录比对；</li>
 *   <li>不一致 → 清空 Redis 语义缓存（旧查询向量同样作废）+ 重算全库切片
 *       向量的<b>入口拦截</b>：直接抛出启动期异常，拒绝带病运行——
 *       铁律「宁可启动失败，不可静默劣化」。</li>
 * </ol>
 *
 * <h3>为什么指纹含 baseUrl</h3>
 * <p>
 * 同名模型经不同网关转发，上游可能不同（网关 A 的 "qwen3-embedding-8b"
 * 与网关 B 的同名模型可以是不同部署）。指纹含 baseUrl 才能把这种情况
 * 识别为「模型变更」。
 * </p>
 *
 * <h3>重建后的解锁</h3>
 * <p>
 * 全库向量重算完成后调 {@link #recordAfterRebuild()}——把指纹写回表，
 * 下次启动比对通过。MOCK 模式不参与（假向量确定性生成，无语义空间概念）。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-10（批 74，方案 C）
 */
@Component
@ConditionalOnProperty(name = "devops.ai.model-fingerprint.enabled", havingValue = "true", matchIfMissing = true)
public class ModelFingerprintGuard {

    private static final Logger log = LoggerFactory.getLogger(ModelFingerprintGuard.class);

    /** 指纹键：单 embedding 渠道，一个键足够 */
    static final String FINGERPRINT_KEY = "embedding";

    private final JdbcTemplate jdbcTemplate;
    private final String currentFingerprint;

    public ModelFingerprintGuard(JdbcTemplate jdbcTemplate,
                                 @Value("${devops.ai.mode:MOCK}") String aiMode,
                                 @Value("${devops.ai.channels.embedding.base-url:}") String baseUrl,
                                 @Value("${devops.ai.channels.embedding.model:}") String model,
                                 @Value("${devops.ai.vector.dimension:1536}") int dimension) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentFingerprint = "MOCK".equalsIgnoreCase(aiMode) ? "" :
                fingerprint(baseUrl, model, dimension);
    }

    /** 指纹算法：SHA-256(url|model|dim) 取前 16 hex。静态——配置类与本类共用同一实现。 */
    public static String fingerprint(String baseUrl, String model, int dimension) {
        String raw = baseUrl + "|" + model + "|" + dimension;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 保证 SHA-256 存在——到这分支说明 JVM 异常，fail loud
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 启动期校验（REAL 模式由调用方在健康自检时触发）。
     *
     * @return null = 通过（首次运行或指纹一致）；非 null = 不一致，返回库中旧指纹
     * @throws IllegalStateException 首次运行时写入指纹失败
     */
    public String verifyOrRecord() {
        if (currentFingerprint.isEmpty()) {
            return null;  // MOCK 模式恒过
        }
        String stored = readStored();
        if (stored == null) {
            // 首次运行：记录当前指纹（表不存在时自动建表——防御非 Flyway 路径的老库）
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
        // 不一致：模型变更，旧向量作废
        log.error("⛔ [ModelFingerprintGuard] embedding 模型已变更！库中指纹 {} ≠ 当前指纹 {}。"
                + "旧向量语义空间与新模型不兼容——静默混用会返回垃圾检索结果且无任何报错。"
                + "必须执行全库向量重算（重摄取或批量 embed）并清空 Redis 语义缓存后方可恢复。",
                stored, currentFingerprint);
        return stored;
    }

    /** 全库向量重算完成后调用：解锁（写入新指纹）。换模型维护流程的最后一步。 */
    public void recordAfterRebuild() {
        jdbcTemplate.update(
                "INSERT INTO sys_model_fingerprint (fingerprint_key, fingerprint) VALUES (?, ?) " +
                "ON CONFLICT (fingerprint_key) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, updated_at = CURRENT_TIMESTAMP",
                FINGERPRINT_KEY, currentFingerprint);
        log.info("🔑 [ModelFingerprintGuard] 重建完成，新指纹已记录: {}", currentFingerprint);
    }

    public String currentFingerprint() {
        return currentFingerprint;
    }

    private String readStored() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT fingerprint FROM sys_model_fingerprint WHERE fingerprint_key = ?",
                    String.class, FINGERPRINT_KEY);
        } catch (org.springframework.dao.DataAccessException e) {
            // 表不存在 = 首次运行（预期分支，非故障）——降为 debug 防「首次启动刷屏」
            log.debug("[ModelFingerprintGuard] 指纹表尚未建立（首次运行）: {}", e.getMessage());
            return null;
        }
    }
}
